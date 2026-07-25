package io.github.suterxom.luau.v1.api;

import io.github.suterxom.luau.internal.jni.NativeBindings;
import io.github.suterxom.luau.internal.jni.NativeExecutionResult;
import io.github.suterxom.luau.internal.jni.NativeHostRegistry;
import io.github.suterxom.luau.internal.jni.NativeLuauException;
import io.github.suterxom.luau.internal.jni.NativeValueCodec;
import io.github.suterxom.luau.v1.api.host.HostCallContext;
import io.github.suterxom.luau.v1.api.host.HostModule;
import io.github.suterxom.luau.v1.api.runtime.ExecutionBudget;
import io.github.suterxom.luau.v1.api.runtime.ExecutionMetrics;
import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import io.github.suterxom.luau.v1.api.runtime.InstanceConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** An opaque compiled program containing a trusted root chunk and its source modules. */
public final class CompiledChunk implements AutoCloseable {
  private static final long DEFAULT_MAX_MEMORY_BYTES = 64L * 1024 * 1024;

  private final LuauRuntime runtime;
  private final String chunkName;
  private final byte[] rootModuleNameUtf8;
  private final byte[][] moduleNamesUtf8;
  private final Set<String> moduleNames;
  private final List<HostModule> hostModules;
  private final Set<String> hostModuleNames;
  private final Map<String, String> hostModuleVersions;
  private byte[][] bytecodes;

  CompiledChunk(
      LuauRuntime runtime,
      String chunkName,
      byte[][] moduleNamesUtf8,
      byte[][] bytecodes,
      List<HostModule> hostModules) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
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
    this.hostModules.forEach(
        module -> {
          hostNames.add(module.name());
          hostVersions.put(module.name(), module.interfaceVersion());
        });
    this.hostModuleNames = Set.copyOf(hostNames);
    this.hostModuleVersions = Map.copyOf(hostVersions);
  }

  /** Returns the logical name of the root module. */
  public String chunkName() {
    return chunkName;
  }

  /** Returns all source modules embedded in this compiled program. */
  public Set<String> moduleNames() {
    return moduleNames;
  }

  /** Returns all explicitly granted host module names. */
  public Set<String> hostModuleNames() {
    return hostModuleNames;
  }

  /** Returns the interface version retained for each explicitly granted host module. */
  public Map<String, String> hostModuleVersions() {
    return hostModuleVersions;
  }

  /** Executes the root module in a fresh isolated and sandboxed Luau state. */
  public synchronized ExecutionResult execute() {
    return execute(HostCallContext.empty(), ExecutionBudget.DEFAULT);
  }

  /** Executes the root module with explicit wall-clock and interrupt-check limits. */
  public synchronized ExecutionResult execute(ExecutionBudget budget) {
    return execute(HostCallContext.empty(), budget);
  }

  /** Executes the root module with Java-only host capabilities and default limits. */
  public synchronized ExecutionResult execute(HostCallContext hostContext) {
    return execute(hostContext, ExecutionBudget.DEFAULT);
  }

  /** Executes the root module with explicit host capabilities and resource limits. */
  public synchronized ExecutionResult execute(
      HostCallContext hostContext, ExecutionBudget budget) {
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

  /** Creates an isolated VM whose mutable globals survive successful executions. */
  public synchronized ScriptInstance createInstance() {
    return createInstance(InstanceConfig.DEFAULT);
  }

  /** Creates an isolated VM with a fixed hard heap limit. */
  public synchronized ScriptInstance createInstance(InstanceConfig config) {
    if (bytecodes == null) {
      throw new IllegalStateException("Compiled chunk is closed");
    }
    Objects.requireNonNull(config, "config");
    return new ScriptInstance(
        rootModuleNameUtf8,
        moduleNamesUtf8,
        bytecodes,
        hostModules,
        config.maxMemoryBytes());
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
      default -> throw new LinkageError("Unknown native execution status: " + result.status());
    };
  }

  LuauRuntime runtime() {
    return runtime;
  }

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
