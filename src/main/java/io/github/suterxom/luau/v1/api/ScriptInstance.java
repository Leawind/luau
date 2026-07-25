package io.github.suterxom.luau.v1.api;

import io.github.suterxom.luau.internal.jni.NativeBindings;
import io.github.suterxom.luau.internal.jni.NativeValueCodec;
import io.github.suterxom.luau.v1.api.runtime.ExecutionBudget;
import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import io.github.suterxom.luau.v1.api.value.LuauValue;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An isolated persistent Luau VM.
 *
 * <p>Mutable globals and required-module caches survive successful calls without being shared between
 * instances.
 */
public final class ScriptInstance implements AutoCloseable {
  private final Thread ownerThread;
  private final byte[] rootModuleNameUtf8;
  private final byte[][] moduleNamesUtf8;
  private final byte[][] bytecodes;
  private final long maxMemoryBytes;
  private long nativeHandle;
  private boolean faulted;
  private boolean initialized;

  ScriptInstance(
      byte[] rootModuleNameUtf8,
      byte[][] moduleNamesUtf8,
      byte[][] bytecodes,
      long maxMemoryBytes) {
    this.rootModuleNameUtf8 =
        Objects.requireNonNull(rootModuleNameUtf8, "rootModuleNameUtf8").clone();
    this.moduleNamesUtf8 = deepClone(moduleNamesUtf8, "moduleNamesUtf8");
    this.bytecodes = deepClone(bytecodes, "bytecodes");
    this.maxMemoryBytes = maxMemoryBytes;
    this.ownerThread = Thread.currentThread();
    this.nativeHandle =
        NativeBindings.createInstance(
            this.rootModuleNameUtf8,
            this.moduleNamesUtf8,
            this.bytecodes,
            maxMemoryBytes);
  }

  public synchronized ExecutionResult execute() {
    return execute(ExecutionBudget.DEFAULT);
  }

  public synchronized ExecutionResult execute(ExecutionBudget budget) {
    checkOwnerThread();
    checkUsable();
    Objects.requireNonNull(budget, "budget");
    if (initialized) {
      throw new IllegalStateException("Use invokeExport after the instance is initialized");
    }

    ExecutionResult result =
        CompiledChunk.decodeExecutionResult(
            NativeBindings.executeInstance(
                nativeHandle, budget.maxRuntimeNanos(), budget.maxInterruptChecks()));
    if (!(result instanceof ExecutionResult.Completed)) {
      faulted = true;
    }
    return result;
  }

  /** Executes the root chunk once and stores its returned export table. */
  public synchronized ExecutionResult initialize() {
    return initialize(ExecutionBudget.DEFAULT);
  }

  /** Executes the root chunk once under the supplied initialization budget. */
  public synchronized ExecutionResult initialize(ExecutionBudget budget) {
    checkOwnerThread();
    checkUsable();
    Objects.requireNonNull(budget, "budget");
    if (initialized) {
      throw new IllegalStateException("Script instance is already initialized");
    }

    ExecutionResult result =
        CompiledChunk.decodeExecutionResult(
            NativeBindings.initializeInstance(
                nativeHandle, budget.maxRuntimeNanos(), budget.maxInterruptChecks()));
    if (result instanceof ExecutionResult.Completed) {
      initialized = true;
    } else {
      faulted = true;
    }
    return result;
  }

  /** Invokes a function from the root module's export table. */
  public synchronized ExecutionResult invokeExport(String name, List<LuauValue> arguments) {
    return invokeExport(name, arguments, ExecutionBudget.DEFAULT);
  }

  /** Invokes an exported function under an explicit execution budget. */
  public synchronized ExecutionResult invokeExport(
      String name, List<LuauValue> arguments, ExecutionBudget budget) {
    checkOwnerThread();
    checkUsable();
    validateExportName(name);
    Objects.requireNonNull(arguments, "arguments");
    Objects.requireNonNull(budget, "budget");
    if (!initialized) {
      throw new IllegalStateException("Script instance is not initialized");
    }

    ExecutionResult result =
        CompiledChunk.decodeExecutionResult(
            NativeBindings.invokeExport(
                nativeHandle,
                name.getBytes(StandardCharsets.UTF_8),
                NativeValueCodec.toNativeValues(List.copyOf(arguments)),
                budget.maxRuntimeNanos(),
                budget.maxInterruptChecks()));
    if (!(result instanceof ExecutionResult.Completed)) {
      faulted = true;
    }
    return result;
  }

  public synchronized boolean isFaulted() {
    checkOwnerThread();
    if (nativeHandle == 0) {
      throw new IllegalStateException("Script instance is closed");
    }
    return faulted;
  }

  public Thread ownerThread() {
    return ownerThread;
  }

  /** Replaces a faulted or healthy VM with a clean VM using the same compiled chunk. */
  public synchronized void reset() {
    checkOwnerThread();
    if (nativeHandle == 0) {
      throw new IllegalStateException("Script instance is closed");
    }
    long replacement =
        NativeBindings.createInstance(
            rootModuleNameUtf8, moduleNamesUtf8, bytecodes, maxMemoryBytes);
    NativeBindings.closeInstance(nativeHandle);
    nativeHandle = replacement;
    faulted = false;
    initialized = false;
  }

  @Override
  public synchronized void close() {
    checkOwnerThread();
    if (nativeHandle != 0) {
      NativeBindings.closeInstance(nativeHandle);
      nativeHandle = 0;
      for (byte[] bytecode : bytecodes) {
        Arrays.fill(bytecode, (byte) 0);
      }
    }
  }

  private void checkUsable() {
    if (nativeHandle == 0) {
      throw new IllegalStateException("Script instance is closed");
    }
    if (faulted) {
      throw new IllegalStateException("Script instance is faulted");
    }
  }

  private void checkOwnerThread() {
    if (Thread.currentThread() != ownerThread) {
      throw new IllegalStateException("Script instance can only be used from its owner thread");
    }
  }

  private static void validateExportName(String name) {
    Objects.requireNonNull(name, "name");
    if (name.isBlank() || name.length() > 256 || name.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(
          "Export name must be non-blank, at most 256 characters, and contain no NUL");
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
