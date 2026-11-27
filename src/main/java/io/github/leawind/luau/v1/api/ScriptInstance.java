package io.github.leawind.luau.v1.api;

import io.github.leawind.luau.internal.jni.NativeBindings;
import io.github.leawind.luau.internal.jni.NativeHostRegistry;
import io.github.leawind.luau.internal.jni.NativeValueCodec;
import io.github.leawind.luau.v1.api.debug.PauseInfo;
import io.github.leawind.luau.v1.api.host.HostCallContext;
import io.github.leawind.luau.v1.api.host.HostModule;
import io.github.leawind.luau.v1.api.runtime.ExecutionBudget;
import io.github.leawind.luau.v1.api.runtime.ExecutionMetrics;
import io.github.leawind.luau.v1.api.runtime.ExecutionResult;
import io.github.leawind.luau.v1.api.value.LuauValue;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * An isolated persistent Luau VM.
 *
 * <p>Mutable globals and required-module caches survive successful calls without being shared
 * between instances.
 */
public final class ScriptInstance implements AutoCloseable {
  private final Thread ownerThread;
  private final byte[] rootModuleNameUtf8;
  private final byte[][] moduleNamesUtf8;
  private final byte[][] bytecodes;
  private final List<HostModule> hostModules;
  private final long maxMemoryBytes;
  private final ScriptDebugger debugger;
  private final ReentrantReadWriteLock nativeHandleLock = new ReentrantReadWriteLock();
  private long nativeHandle;
  private boolean faulted;
  private boolean initialized;
  private boolean running;
  private PausedOperation pausedOperation;
  private HostCallContext pausedHostContext;
  private ExecutionScope pausedScope;
  private PauseInfo pauseInfo;

  ScriptInstance(
      byte[] rootModuleNameUtf8,
      byte[][] moduleNamesUtf8,
      byte[][] bytecodes,
      List<HostModule> hostModules,
      long maxMemoryBytes) {
    this.rootModuleNameUtf8 =
        Objects.requireNonNull(rootModuleNameUtf8, "rootModuleNameUtf8").clone();
    this.moduleNamesUtf8 = deepClone(moduleNamesUtf8, "moduleNamesUtf8");
    this.bytecodes = deepClone(bytecodes, "bytecodes");
    this.hostModules = List.copyOf(Objects.requireNonNull(hostModules, "hostModules"));
    this.maxMemoryBytes = maxMemoryBytes;
    this.ownerThread = Thread.currentThread();
    this.nativeHandle =
        NativeBindings.createInstance(
            this.rootModuleNameUtf8,
            this.moduleNamesUtf8,
            this.bytecodes,
            NativeHostRegistry.create(this.hostModules),
            maxMemoryBytes);
    this.debugger = new ScriptDebugger(this);
  }

  public synchronized ExecutionResult execute() {
    return execute(HostCallContext.empty(), ExecutionBudget.DEFAULT);
  }

  public synchronized ExecutionResult execute(ExecutionBudget budget) {
    return execute(HostCallContext.empty(), budget);
  }

  public synchronized ExecutionResult execute(HostCallContext hostContext) {
    return execute(hostContext, ExecutionBudget.DEFAULT);
  }

  public synchronized ExecutionResult execute(HostCallContext hostContext, ExecutionBudget budget) {
    return executeInternal(hostContext, budget, null);
  }

  /** Executes the root chunk as part of a logical operation with a shared budget. */
  public synchronized ExecutionResult execute(HostCallContext hostContext, ExecutionScope scope) {
    return executeInternal(hostContext, null, scope);
  }

  private ExecutionResult executeInternal(
      HostCallContext hostContext, ExecutionBudget budget, ExecutionScope scope) {
    checkOwnerThread();
    checkUsable();
    Objects.requireNonNull(hostContext, "hostContext");
    if (scope == null) {
      Objects.requireNonNull(budget, "budget");
    }
    if (initialized) {
      throw new IllegalStateException("Use invokeExport after the instance is initialized");
    }

    ExecutionBudget effectiveBudget = effectiveBudget(budget, scope);
    if (effectiveBudget == null) {
      ExecutionResult result = scope.exhaustedResult();
      acceptInvocationResult(result, PausedOperation.EXECUTE, hostContext, scope);
      return result;
    }

    beginInvocation();
    try {
      ExecutionResult result =
          CompiledChunk.decodeExecutionResult(
              NativeBindings.executeInstance(
                  nativeHandle,
                  hostContext,
                  effectiveBudget.maxRuntimeNanos(),
                  effectiveBudget.maxInterruptChecks(),
                  effectiveBudget.maxHostCalls(),
                  effectiveBudget.maxHostOutputBytes()));
      result = applyScope(result, scope);
      acceptInvocationResult(result, PausedOperation.EXECUTE, hostContext, scope);
      return result;
    } catch (RuntimeException | Error throwable) {
      faulted = true;
      throw throwable;
    } finally {
      running = false;
    }
  }

  /** Executes the root chunk once and stores its returned export table. */
  public synchronized ExecutionResult initialize() {
    return initialize(HostCallContext.empty(), ExecutionBudget.DEFAULT);
  }

  /** Executes the root chunk once under the supplied initialization budget. */
  public synchronized ExecutionResult initialize(ExecutionBudget budget) {
    return initialize(HostCallContext.empty(), budget);
  }

  /** Executes the root chunk once with Java-only host capabilities and default limits. */
  public synchronized ExecutionResult initialize(HostCallContext hostContext) {
    return initialize(hostContext, ExecutionBudget.DEFAULT);
  }

  /** Executes the root chunk once with explicit host capabilities and resource limits. */
  public synchronized ExecutionResult initialize(
      HostCallContext hostContext, ExecutionBudget budget) {
    return initializeInternal(hostContext, budget, null);
  }

  /** Executes the root chunk once as part of a logical operation with a shared budget. */
  public synchronized ExecutionResult initialize(
      HostCallContext hostContext, ExecutionScope scope) {
    return initializeInternal(hostContext, null, scope);
  }

  private ExecutionResult initializeInternal(
      HostCallContext hostContext, ExecutionBudget budget, ExecutionScope scope) {
    checkOwnerThread();
    checkUsable();
    Objects.requireNonNull(hostContext, "hostContext");
    if (scope == null) {
      Objects.requireNonNull(budget, "budget");
    }
    if (initialized) {
      throw new IllegalStateException("Script instance is already initialized");
    }

    ExecutionBudget effectiveBudget = effectiveBudget(budget, scope);
    if (effectiveBudget == null) {
      ExecutionResult result = scope.exhaustedResult();
      acceptInvocationResult(result, PausedOperation.INITIALIZE, hostContext, scope);
      return result;
    }

    beginInvocation();
    try {
      ExecutionResult result =
          CompiledChunk.decodeExecutionResult(
              NativeBindings.initializeInstance(
                  nativeHandle,
                  hostContext,
                  effectiveBudget.maxRuntimeNanos(),
                  effectiveBudget.maxInterruptChecks(),
                  effectiveBudget.maxHostCalls(),
                  effectiveBudget.maxHostOutputBytes()));
      result = applyScope(result, scope);
      acceptInvocationResult(result, PausedOperation.INITIALIZE, hostContext, scope);
      return result;
    } catch (RuntimeException | Error throwable) {
      faulted = true;
      throw throwable;
    } finally {
      running = false;
    }
  }

  /** Invokes a function from the root module's export table. */
  public synchronized ExecutionResult invokeExport(String name, List<LuauValue> arguments) {
    return invokeExport(name, arguments, HostCallContext.empty(), ExecutionBudget.DEFAULT);
  }

  /** Invokes an exported function under an explicit execution budget. */
  public synchronized ExecutionResult invokeExport(
      String name, List<LuauValue> arguments, ExecutionBudget budget) {
    return invokeExport(name, arguments, HostCallContext.empty(), budget);
  }

  /** Invokes an exported function with Java-only host capabilities and default limits. */
  public synchronized ExecutionResult invokeExport(
      String name, List<LuauValue> arguments, HostCallContext hostContext) {
    return invokeExport(name, arguments, hostContext, ExecutionBudget.DEFAULT);
  }

  /** Invokes an exported function with explicit host capabilities and resource limits. */
  public synchronized ExecutionResult invokeExport(
      String name, List<LuauValue> arguments, HostCallContext hostContext, ExecutionBudget budget) {
    return invokeExportInternal(name, arguments, hostContext, budget, null);
  }

  /** Invokes an exported function as part of a logical operation with a shared budget. */
  public synchronized ExecutionResult invokeExport(
      String name, List<LuauValue> arguments, HostCallContext hostContext, ExecutionScope scope) {
    return invokeExportInternal(name, arguments, hostContext, null, scope);
  }

  private ExecutionResult invokeExportInternal(
      String name,
      List<LuauValue> arguments,
      HostCallContext hostContext,
      ExecutionBudget budget,
      ExecutionScope scope) {
    checkOwnerThread();
    checkUsable();
    validateExportName(name);
    Objects.requireNonNull(arguments, "arguments");
    Objects.requireNonNull(hostContext, "hostContext");
    if (scope == null) {
      Objects.requireNonNull(budget, "budget");
    }
    if (!initialized) {
      throw new IllegalStateException("Script instance is not initialized");
    }

    ExecutionBudget effectiveBudget = effectiveBudget(budget, scope);
    if (effectiveBudget == null) {
      ExecutionResult result = scope.exhaustedResult();
      acceptInvocationResult(result, PausedOperation.INVOKE_EXPORT, hostContext, scope);
      return result;
    }

    var nativeArguments = NativeValueCodec.toNativeValues(List.copyOf(arguments));
    beginInvocation();
    try {
      ExecutionResult result =
          CompiledChunk.decodeExecutionResult(
              NativeBindings.invokeExport(
                  nativeHandle,
                  name.getBytes(StandardCharsets.UTF_8),
                  nativeArguments,
                  hostContext,
                  effectiveBudget.maxRuntimeNanos(),
                  effectiveBudget.maxInterruptChecks(),
                  effectiveBudget.maxHostCalls(),
                  effectiveBudget.maxHostOutputBytes()));
      result = applyScope(result, scope);
      acceptInvocationResult(result, PausedOperation.INVOKE_EXPORT, hostContext, scope);
      return result;
    } catch (RuntimeException | Error throwable) {
      faulted = true;
      throw throwable;
    } finally {
      running = false;
    }
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

  /** Returns the owner-thread debugger associated with this instance. */
  public ScriptDebugger debugger() {
    return debugger;
  }

  /** Replaces a faulted or healthy VM with a clean VM using the same compiled chunk. */
  public synchronized void reset() {
    checkOwnerThread();
    if (nativeHandle == 0) {
      throw new IllegalStateException("Script instance is closed");
    }
    checkNotRunning();
    if (pausedOperation != null) {
      throw new IllegalStateException(
          "Terminate a paused invocation before resetting its script instance");
    }
    var writeLock = nativeHandleLock.writeLock();
    writeLock.lock();
    try {
      long replacement =
          NativeBindings.createInstance(
              rootModuleNameUtf8,
              moduleNamesUtf8,
              bytecodes,
              NativeHostRegistry.create(hostModules),
              maxMemoryBytes);
      NativeBindings.closeInstance(nativeHandle);
      nativeHandle = replacement;
      faulted = false;
      initialized = false;
      clearPause();
      debugger.reapplyAfterReset();
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public synchronized void close() {
    checkOwnerThread();
    checkNotRunning();
    var writeLock = nativeHandleLock.writeLock();
    writeLock.lock();
    try {
      if (nativeHandle != 0) {
        NativeBindings.closeInstance(nativeHandle);
        nativeHandle = 0;
        clearPause();
        for (byte[] bytecode : bytecodes) {
          Arrays.fill(bytecode, (byte) 0);
        }
      }
    } finally {
      writeLock.unlock();
    }
  }

  private void checkUsable() {
    if (nativeHandle == 0) {
      throw new IllegalStateException("Script instance is closed");
    }
    if (faulted) {
      throw new IllegalStateException("Script instance is faulted");
    }
    if (pausedOperation != null) {
      throw new IllegalStateException(
          "Script instance is paused; use its debugger to resume or terminate");
    }
  }

  private void beginInvocation() {
    checkNotRunning();
    running = true;
  }

  private void checkNotRunning() {
    if (running) {
      throw new IllegalStateException("Script instance is already executing");
    }
  }

  private void checkOwnerThread() {
    if (Thread.currentThread() != ownerThread) {
      throw new IllegalStateException("Script instance can only be used from its owner thread");
    }
  }

  synchronized void checkDebuggerAccess(boolean allowPaused) {
    checkOwnerThread();
    if (nativeHandle == 0) {
      throw new IllegalStateException("Script instance is closed");
    }
    if (faulted) {
      throw new IllegalStateException("Script instance is faulted");
    }
    checkNotRunning();
    if (!allowPaused && pausedOperation != null) {
      throw new IllegalStateException("Breakpoints cannot change while execution is paused");
    }
  }

  synchronized void checkDebuggerReadAccess() {
    checkOwnerThread();
    if (nativeHandle == 0) {
      throw new IllegalStateException("Script instance is closed");
    }
    checkNotRunning();
  }

  synchronized int updateBreakpoint(String moduleName, int line, boolean enabled) {
    checkDebuggerAccess(false);
    return NativeBindings.setBreakpoint(
        nativeHandle, moduleName.getBytes(StandardCharsets.UTF_8), line, enabled);
  }

  synchronized Optional<PauseInfo> debuggerPauseInfo() {
    checkDebuggerReadAccess();
    return Optional.ofNullable(pauseInfo);
  }

  void requestDebuggerPause() {
    var readLock = nativeHandleLock.readLock();
    readLock.lock();
    try {
      if (nativeHandle == 0) {
        throw new IllegalStateException("Script instance is closed");
      }
      NativeBindings.requestPauseInstance(nativeHandle);
    } finally {
      readLock.unlock();
    }
  }

  void requestDebuggerTermination() {
    var readLock = nativeHandleLock.readLock();
    readLock.lock();
    try {
      if (nativeHandle == 0) {
        throw new IllegalStateException("Script instance is closed");
      }
      NativeBindings.requestTerminateInstance(nativeHandle);
    } finally {
      readLock.unlock();
    }
  }

  synchronized ExecutionResult resumePaused() {
    return continuePaused(0);
  }

  synchronized ExecutionResult stepPaused(int stepMode) {
    if (stepMode < 1 || stepMode > 3) {
      throw new IllegalArgumentException("Debugger step mode must be between 1 and 3");
    }
    return continuePaused(stepMode);
  }

  private ExecutionResult continuePaused(int stepMode) {
    checkDebuggerAccess(true);
    if (pausedOperation == null || pausedHostContext == null || pauseInfo == null) {
      throw new IllegalStateException("Script instance is not paused");
    }

    PausedOperation operation = pausedOperation;
    HostCallContext hostContext = pausedHostContext;
    ExecutionScope scope = pausedScope;
    beginInvocation();
    try {
      ExecutionResult result =
          CompiledChunk.decodeExecutionResult(
              NativeBindings.resumeInstance(nativeHandle, hostContext, stepMode));
      result = applyScope(result, scope);
      acceptInvocationResult(result, operation, hostContext, scope);
      return result;
    } catch (RuntimeException | Error throwable) {
      faulted = true;
      clearPause();
      throw throwable;
    } finally {
      running = false;
    }
  }

  synchronized ExecutionResult terminatePaused() {
    checkDebuggerAccess(true);
    if (pausedOperation == null) {
      throw new IllegalStateException("Script instance is not paused");
    }

    ExecutionResult result =
        CompiledChunk.decodeExecutionResult(NativeBindings.terminatePausedInstance(nativeHandle));
    if (!(result instanceof ExecutionResult.Terminated)) {
      faulted = true;
      clearPause();
      throw new LinkageError("Native debugger termination returned an invalid result");
    }
    result = applyScope(result, pausedScope);
    faulted = true;
    clearPause();
    return result;
  }

  private void acceptInvocationResult(
      ExecutionResult result,
      PausedOperation operation,
      HostCallContext hostContext,
      ExecutionScope scope) {
    if (result instanceof ExecutionResult.Paused paused) {
      pausedOperation = operation;
      pausedHostContext = hostContext;
      pausedScope = scope;
      pauseInfo = paused.pauseInfo();
      return;
    }

    clearPause();
    if (result instanceof ExecutionResult.Completed) {
      if (operation == PausedOperation.INITIALIZE) {
        initialized = true;
      }
    } else {
      faulted = true;
    }
  }

  private void clearPause() {
    pausedOperation = null;
    pausedHostContext = null;
    pausedScope = null;
    pauseInfo = null;
  }

  private static ExecutionBudget effectiveBudget(ExecutionBudget budget, ExecutionScope scope) {
    return scope == null ? budget : scope.remainingBudget();
  }

  private static ExecutionResult applyScope(ExecutionResult result, ExecutionScope scope) {
    if (scope == null) {
      return result;
    }
    if (result instanceof ExecutionResult.Paused) {
      return withMetrics(result, scope.aggregate(result.metrics()));
    }
    scope.consume(result.metrics());
    return withMetrics(result, scope.metrics());
  }

  private static ExecutionResult withMetrics(ExecutionResult result, ExecutionMetrics metrics) {
    if (result instanceof ExecutionResult.Completed completed) {
      return new ExecutionResult.Completed(completed.values(), metrics);
    }
    if (result instanceof ExecutionResult.Paused paused) {
      return new ExecutionResult.Paused(paused.pauseInfo(), metrics);
    }
    if (result instanceof ExecutionResult.RuntimeFailed failed) {
      return new ExecutionResult.RuntimeFailed(failed.message(), metrics);
    }
    if (result instanceof ExecutionResult.LimitExceeded exceeded) {
      return new ExecutionResult.LimitExceeded(exceeded.kind(), exceeded.message(), metrics);
    }
    if (result instanceof ExecutionResult.Terminated terminated) {
      return new ExecutionResult.Terminated(terminated.message(), metrics);
    }
    throw new LinkageError("Unknown execution result type: " + result.getClass());
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

  private enum PausedOperation {
    EXECUTE,
    INITIALIZE,
    INVOKE_EXPORT
  }
}
