package io.github.suterxom.luau.v1.api;

import io.github.suterxom.luau.internal.jni.NativeBindings;
import io.github.suterxom.luau.internal.jni.NativeLuauException;
import io.github.suterxom.luau.internal.jni.NativeValueCodec;
import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** An opaque, trusted bytecode chunk produced by this runtime. */
public final class CompiledChunk implements AutoCloseable {
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
    if (bytecode == null) {
      throw new IllegalStateException("Compiled chunk is closed");
    }

    try {
      return new ExecutionResult.Completed(
          NativeValueCodec.fromNativeValues(
              NativeBindings.execute(chunkName.getBytes(StandardCharsets.UTF_8), bytecode)));
    } catch (NativeLuauException exception) {
      return new ExecutionResult.RuntimeFailed(exception.getMessage());
    }
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
