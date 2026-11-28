package io.github.leawind.luau.v1.api;

import io.github.leawind.luau.internal.jni.NativeBindings;
import io.github.leawind.luau.internal.jni.NativeExecutionResult;
import io.github.leawind.luau.internal.jni.NativeHostRegistry;
import io.github.leawind.luau.internal.jni.NativeLuauException;
import io.github.leawind.luau.internal.jni.NativePauseInfo;
import io.github.leawind.luau.internal.jni.NativeStackFrame;
import io.github.leawind.luau.internal.jni.NativeValueCodec;
import io.github.leawind.luau.v1.api.debug.PauseInfo;
import io.github.leawind.luau.v1.api.debug.SourceLocation;
import io.github.leawind.luau.v1.api.debug.StackFrame;
import io.github.leawind.luau.v1.api.host.HostCallContext;
import io.github.leawind.luau.v1.api.host.HostModule;
import io.github.leawind.luau.v1.api.runtime.ExecutionBudget;
import io.github.leawind.luau.v1.api.runtime.ExecutionMetrics;
import io.github.leawind.luau.v1.api.runtime.ExecutionResult;
import io.github.leawind.luau.v1.api.runtime.InstanceConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An opaque compiled program containing a trusted root chunk and its source modules.
 *
 * <p>A chunk runs either on its own, in a fresh VM whose state is discarded afterwards, or through
 * instances that keep one VM and its globals alive across calls. Closing the chunk wipes the
 * embedded bytecode, after which it can no longer execute or create instances.
 */
public final class CompiledChunk implements AutoCloseable {
  private static final long DEFAULT_MAX_MEMORY_BYTES = 64L * 1024 * 1024;

  private final String chunkName;
  private final byte[] rootModuleNameUtf8;
  private final byte[][] moduleNamesUtf8;
  private final Set<String> moduleNames;
  private final List<HostModule> hostModules;
  private final Set<String> hostModuleNames;
  private final Map<String, String> hostModuleVersions;
  private byte[][] bytecodes;

  CompiledChunk(
      String chunkName,
      byte[][] moduleNamesUtf8,
      byte[][] bytecodes,
      List<HostModule> hostModules) {
    this.chunkName = Objects.requireNonNull(chunkName, "chunkName");
    this.rootModuleNameUtf8 = chunkName.getBytes(StandardCharsets.UTF_8);
    this.moduleNamesUtf8 = deepClone(moduleNamesUtf8, "moduleNamesUtf8");
    this.bytecodes = deepClone(bytecodes, "bytecodes");
    if (this.moduleNamesUtf8.length != this.bytecodes.length) {
      throw new IllegalArgumentException("Module name and bytecode counts must match");
    }
    LinkedHashSet<String> names = new LinkedHashSet<>();
    for (byte[] moduleName : this.moduleNamesUtf8) {
      names.add(new String(moduleName, StandardCharsets.UTF_8));
    }
    this.moduleNames = Set.copyOf(names);
    this.hostModules = List.copyOf(Objects.requireNonNull(hostModules, "hostModules"));
    LinkedHashSet<String> hostNames = new LinkedHashSet<>();
    LinkedHashMap<String, String> hostVersions = new LinkedHashMap<>();
    this.hostModules.forEach(module -> {
      hostNames.add(module.name());
      hostVersions.put(module.name(), module.interfaceVersion());
    });
    this.hostModuleNames = Set.copyOf(hostNames);
    this.hostModuleVersions = Map.copyOf(hostVersions);
  }

  /**
   * Returns the logical name of the root module.
   *
   * @return the root module name
   */
  public String chunkName() {
    return chunkName;
  }

  /**
   * Returns all source modules embedded in this compiled program.
   *
   * @return an immutable set of logical module names
   */
  public Set<String> moduleNames() {
    return moduleNames;
  }

  /**
   * Returns all host modules this program was compiled against.
   *
   * @return an immutable set of logical module names
   */
  public Set<String> hostModuleNames() {
    return hostModuleNames;
  }

  /**
   * Returns the interface version retained for each host module.
   *
   * @return an immutable map from host module name to its interface version
   */
  public Map<String, String> hostModuleVersions() {
    return hostModuleVersions;
  }

  /**
   * Executes the root module in a fresh isolated and sandboxed VM.
   *
   * <p>The VM limits its heap to 64 MiB and is discarded afterwards, so nothing it creates survives
   * the call.
   *
   * @return the execution result
   * @throws IllegalStateException if the chunk is closed, or the native execution boundary fails
   */
  public synchronized ExecutionResult execute() {
    return execute(HostCallContext.empty(), ExecutionBudget.DEFAULT);
  }

  /**
   * Executes the root module in a fresh VM with an explicit execution budget.
   *
   * @param budget the limits for this execution
   * @return the execution result
   * @throws IllegalStateException if the chunk is closed, or the native execution boundary fails
   * @throws NullPointerException if {@code budget} is null
   */
  public synchronized ExecutionResult execute(ExecutionBudget budget) {
    return execute(HostCallContext.empty(), budget);
  }

  /**
   * Executes the root module in a fresh VM with Java-only host capabilities.
   *
   * @param hostContext the capabilities passed to every host call of this execution
   * @return the execution result
   * @throws IllegalStateException if the chunk is closed, or the native execution boundary fails
   * @throws NullPointerException if {@code hostContext} is null
   */
  public synchronized ExecutionResult execute(HostCallContext hostContext) {
    return execute(hostContext, ExecutionBudget.DEFAULT);
  }

  /**
   * Executes the root module in a fresh VM with explicit host capabilities and limits.
   *
   * @param hostContext the capabilities passed to every host call of this execution
   * @param budget the limits for this execution
   * @return the execution result
   * @throws IllegalStateException if the chunk is closed, or the native execution boundary fails
   * @throws NullPointerException if an argument is null
   */
  public synchronized ExecutionResult execute(HostCallContext hostContext, ExecutionBudget budget) {
    if (bytecodes == null) {
      throw new IllegalStateException("Compiled chunk is closed");
    }
    Objects.requireNonNull(hostContext, "hostContext");
    Objects.requireNonNull(budget, "budget");

    try {
      NativeExecutionResult result =
          NativeBindings.execute(
              rootModuleNameUtf8,
              moduleNamesUtf8,
              bytecodes,
              NativeHostRegistry.create(hostModules),
              hostContext,
              budget.maxRuntimeNanos(),
              budget.maxInterruptChecks(),
              budget.maxHostCalls(),
              budget.maxHostOutputBytes(),
              DEFAULT_MAX_MEMORY_BYTES);
      return decodeExecutionResult(result);
    } catch (NativeLuauException exception) {
      throw new IllegalStateException("Native Luau execution boundary failed", exception);
    }
  }

  /**
   * Creates an isolated VM whose mutable globals survive successful calls.
   *
   * <p>The instance belongs to the calling thread and starts with a 64 MiB heap limit.
   *
   * @return a new instance owned by the calling thread
   * @throws IllegalStateException if the chunk is closed
   */
  public synchronized ScriptInstance createInstance() {
    return createInstance(InstanceConfig.DEFAULT);
  }

  /**
   * Creates an isolated VM with a fixed heap limit.
   *
   * @param config the configuration fixed for the lifetime of the instance
   * @return a new instance owned by the calling thread
   * @throws IllegalStateException if the chunk is closed
   * @throws NullPointerException if {@code config} is null
   */
  public synchronized ScriptInstance createInstance(InstanceConfig config) {
    if (bytecodes == null) {
      throw new IllegalStateException("Compiled chunk is closed");
    }
    Objects.requireNonNull(config, "config");
    return new ScriptInstance(
        rootModuleNameUtf8, moduleNamesUtf8, bytecodes, hostModules, config.maxMemoryBytes());
  }

  static ExecutionResult decodeExecutionResult(NativeExecutionResult result) {
    ExecutionMetrics metrics =
        new ExecutionMetrics(
            Duration.ofNanos(result.elapsedNanos()),
            result.interruptChecks(),
            result.currentMemoryBytes(),
            result.peakMemoryBytes(),
            result.hostCalls(),
            result.hostOutputBytes());
    String message =
        result.messageUtf8() == null
            ? ""
            : new String(result.messageUtf8(), StandardCharsets.UTF_8);
    return switch (result.status()) {
      case 0 ->
          new ExecutionResult.Completed(
              NativeValueCodec.fromNativeValues(result.values()), metrics);
      case 1 -> new ExecutionResult.RuntimeFailed(message, metrics);
      case 2 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.RUNTIME, message, metrics);
      case 3 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.INTERRUPT_CHECKS, message, metrics);
      case 4 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.MEMORY, message, metrics);
      case 5 -> new ExecutionResult.RuntimeFailed(message, metrics);
      case 6 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.VALUE_CODEC, message, metrics);
      case 7 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.HOST_CALLS, message, metrics);
      case 8 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.HOST_OUTPUT, message, metrics);
      case 9 -> new ExecutionResult.Paused(decodePauseInfo(result.pauseInfo()), metrics);
      case 10 -> new ExecutionResult.Terminated(message, metrics);
      default -> throw new LinkageError("Unknown native execution status: " + result.status());
    };
  }

  private static PauseInfo decodePauseInfo(NativePauseInfo nativePause) {
    if (nativePause == null) {
      throw new LinkageError("Native paused execution did not include pause information");
    }
    PauseInfo.Reason reason =
        switch (nativePause.reason()) {
          case 0 -> PauseInfo.Reason.BREAKPOINT;
          case 1 -> PauseInfo.Reason.REQUESTED;
          case 2 -> PauseInfo.Reason.STEP;
          default -> throw new LinkageError("Unknown native pause reason: " + nativePause.reason());
        };
    SourceLocation location =
        new SourceLocation(
            new String(nativePause.moduleNameUtf8(), StandardCharsets.UTF_8), nativePause.line());
    List<StackFrame> frames =
        Arrays.stream(nativePause.stackFrames()).map(CompiledChunk::decodeStackFrame).toList();
    return new PauseInfo(reason, location, frames);
  }

  private static StackFrame decodeStackFrame(NativeStackFrame frame) {
    return new StackFrame(
        new SourceLocation(
            new String(frame.moduleNameUtf8(), StandardCharsets.UTF_8), frame.line()),
        new String(frame.functionNameUtf8(), StandardCharsets.UTF_8));
  }

  /**
   * Wipes the embedded bytecode so the program can no longer run.
   *
   * <p>Closing twice has no effect, and instances created earlier keep their own copy.
   */
  @Override
  public synchronized void close() {
    if (bytecodes != null) {
      for (byte[] bytecode : bytecodes) {
        Arrays.fill(bytecode, (byte) 0);
      }
      bytecodes = null;
    }
  }

  private static byte[][] deepClone(byte[][] values, String name) {
    Objects.requireNonNull(values, name);
    byte[][] copy = values.clone();
    for (int index = 0; index < copy.length; ++index) {
      copy[index] = Objects.requireNonNull(copy[index], name + "[" + index + "]").clone();
    }
    return copy;
  }
}
