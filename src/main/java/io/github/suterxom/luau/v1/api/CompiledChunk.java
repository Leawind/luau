package io.github.suterxom.luau.v1.api;

import io.github.suterxom.luau.internal.jni.NativeBindings;
import io.github.suterxom.luau.internal.jni.NativeExecutionResult;
import io.github.suterxom.luau.internal.jni.NativeLuauException;
import io.github.suterxom.luau.internal.jni.NativeValueCodec;
import io.github.suterxom.luau.v1.api.runtime.ExecutionBudget;
import io.github.suterxom.luau.v1.api.runtime.ExecutionMetrics;
import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import io.github.suterxom.luau.v1.api.runtime.InstanceConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/** An opaque, trusted bytecode chunk produced by this runtime. */
public final class CompiledChunk implements AutoCloseable {
  private static final long DEFAULT_MAX_MEMORY_BYTES = 64L * 1024 * 1024;

  private final LuauRuntime runtime;
  private final String chunkName;
  private byte[] bytecode;

  CompiledChunk(LuauRuntime runtime, String chunkName, byte[] bytecode) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.chunkName = Objects.requireNonNull(chunkName, "chunkName");
    this.bytecode = Objects.requireNonNull(bytecode, "bytecode").clone();
  }

  public String chunkName() {
    return chunkName;
  }

  /** Executes this chunk in a fresh isolated and sandboxed Luau state. */
  public synchronized ExecutionResult execute() {
    return execute(ExecutionBudget.DEFAULT);
  }

  /** Executes this chunk with explicit wall-clock and interrupt-check limits. */
  public synchronized ExecutionResult execute(ExecutionBudget budget) {
    if (bytecode == null) {
      throw new IllegalStateException("Compiled chunk is closed");
    }
    Objects.requireNonNull(budget, "budget");

    try {
      NativeExecutionResult result =
          NativeBindings.execute(
              chunkName.getBytes(StandardCharsets.UTF_8),
              bytecode,
              budget.maxRuntimeNanos(),
              budget.maxInterruptChecks(),
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
    if (bytecode == null) {
      throw new IllegalStateException("Compiled chunk is closed");
    }
    Objects.requireNonNull(config, "config");
    return new ScriptInstance(chunkName, bytecode, config.maxMemoryBytes());
  }

  static ExecutionResult decodeExecutionResult(NativeExecutionResult result) {
    ExecutionMetrics metrics =
        new ExecutionMetrics(
            Duration.ofNanos(result.elapsedNanos()),
            result.interruptChecks(),
            result.currentMemoryBytes(),
            result.peakMemoryBytes());
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
      default -> throw new LinkageError("Unknown native execution status: " + result.status());
    };
  }

  LuauRuntime runtime() {
    return runtime;
  }

  @Override
  public synchronized void close() {
    if (bytecode != null) {
      Arrays.fill(bytecode, (byte) 0);
      bytecode = null;
    }
  }
}
