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
 * <p>Mutable globals and required-module caches survive successful calls and are never shared
 * between instances.
 *
 * <p>An instance belongs to the thread that created it and rejects calls from any other thread.
 * Before initialization it runs its root chunk with {@code execute}; after it, the stored export
 * table is reached through {@code invokeExport}, and initializing again is rejected. A failed
 * invocation faults the instance, which then rejects every call until it is reset or closed. A
 * debugger pause keeps the invoked coroutine alive and makes the instance reject other calls
 * meanwhile.
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

  /**
   * Runs the root chunk once with an empty host context and the default budget.
   *
   * <p>The export table the chunk returns is discarded; use {@link #initialize()} to keep it for
   * {@link #invokeExport}.
   *
   * @return the execution result
   */
  public synchronized ExecutionResult execute() {
    return execute(HostCallContext.empty(), ExecutionBudget.DEFAULT);
  }

  /**
   * Runs the root chunk once with an empty host context and an explicit budget.
   *
   * @param budget the limits for this invocation
   * @return the execution result
   */
  public synchronized ExecutionResult execute(ExecutionBudget budget) {
    return execute(HostCallContext.empty(), budget);
  }

  /**
   * Runs the root chunk once with Java-only host capabilities and the default budget.
   *
   * @param hostContext the capabilities passed to every host call of this invocation
   * @return the execution result
   */
  public synchronized ExecutionResult execute(HostCallContext hostContext) {
    return execute(hostContext, ExecutionBudget.DEFAULT);
  }

  /**
   * Runs the root chunk once with explicit host capabilities and limits.
   *
   * @param hostContext the capabilities passed to every host call of this invocation
   * @param budget the limits for this invocation
   * @return the execution result
   */
  public synchronized ExecutionResult execute(HostCallContext hostContext, ExecutionBudget budget) {
    return executeInternal(hostContext, budget, null);
  }

  /**
   * Runs the root chunk once as part of a logical operation with a shared budget.
   *
   * <p>Only the budget that remains in the scope is applied. When the scope is already exhausted,
   * the call returns a {@link ExecutionResult.LimitExceeded} result without entering Luau and
   * faults the instance.
   *
   * @param hostContext the capabilities passed to every host call of this invocation
   * @param scope the operation whose remaining budget this invocation consumes
   * @return the execution result
   */
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

  /**
   * Runs the root chunk once, keeps its export table, and marks the instance initialized.
   *
   * <p>The table has to be a Luau table; its fields are then reachable through {@link
   * #invokeExport(String, List)} until the instance is reset or closed.
   *
   * @return the execution result
   */
  public synchronized ExecutionResult initialize() {
    return initialize(HostCallContext.empty(), ExecutionBudget.DEFAULT);
  }

  /**
   * Initializes the instance with an explicit initialization budget.
   *
   * @param budget the limits for the initialization invocation
   * @return the execution result
   */
  public synchronized ExecutionResult initialize(ExecutionBudget budget) {
    return initialize(HostCallContext.empty(), budget);
  }

  /**
   * Initializes the instance with Java-only host capabilities and the default budget.
   *
   * @param hostContext the capabilities passed to every host call of the invocation
   * @return the execution result
   */
  public synchronized ExecutionResult initialize(HostCallContext hostContext) {
    return initialize(hostContext, ExecutionBudget.DEFAULT);
  }

  /**
   * Initializes the instance with explicit host capabilities and limits.
   *
   * @param hostContext the capabilities passed to every host call of the invocation
   * @param budget the limits for the initialization invocation
   * @return the execution result
   */
  public synchronized ExecutionResult initialize(
      HostCallContext hostContext, ExecutionBudget budget) {
    return initializeInternal(hostContext, budget, null);
  }

  /**
   * Initializes the instance as part of a logical operation with a shared budget.
   *
   * <p>Only the budget that remains in the scope is applied. When the scope is already exhausted,
   * the call returns a {@link ExecutionResult.LimitExceeded} result without entering Luau and
   * faults the instance.
   *
   * @param hostContext the capabilities passed to every host call of the invocation
   * @param scope the operation whose remaining budget this invocation consumes
   * @return the execution result
   */
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

  /**
   * Invokes a function from the root module's stored export table.
   *
   * @param name the field name in the export table
   * @param arguments the arguments to pass, in order
   * @return the execution result
   * @throws IllegalArgumentException if the name is blank, longer than 256 characters or contains
   *     NUL
   * @throws IllegalStateException if the instance is not initialized, closed, faulted, executing or
   *     paused
   * @throws NullPointerException if an argument is null or {@code arguments} contains null
   */
  public synchronized ExecutionResult invokeExport(String name, List<LuauValue> arguments) {
    return invokeExport(name, arguments, HostCallContext.empty(), ExecutionBudget.DEFAULT);
  }

  /**
   * Invokes an exported function with an explicit budget.
   *
   * @param name the field name in the export table
   * @param arguments the arguments to pass, in order
   * @param budget the limits for this invocation
   * @return the execution result
   * @throws IllegalArgumentException if the name is blank, longer than 256 characters or contains
   *     NUL
   * @throws IllegalStateException if the instance is not initialized, closed, faulted, executing or
   *     paused
   * @throws NullPointerException if an argument is null or {@code arguments} contains null
   */
  public synchronized ExecutionResult invokeExport(
      String name, List<LuauValue> arguments, ExecutionBudget budget) {
    return invokeExport(name, arguments, HostCallContext.empty(), budget);
  }

  /**
   * Invokes an exported function with Java-only host capabilities.
   *
   * @param name the field name in the export table
   * @param arguments the arguments to pass, in order
   * @param hostContext the capabilities passed to every host call of this invocation
   * @return the execution result
   * @throws IllegalArgumentException if the name is blank, longer than 256 characters or contains
   *     NUL
   * @throws IllegalStateException if the instance is not initialized, closed, faulted, executing or
   *     paused
   * @throws NullPointerException if an argument is null or {@code arguments} contains null
   */
  public synchronized ExecutionResult invokeExport(
      String name, List<LuauValue> arguments, HostCallContext hostContext) {
    return invokeExport(name, arguments, hostContext, ExecutionBudget.DEFAULT);
  }

  /**
   * Invokes an exported function with explicit host capabilities and limits.
   *
   * @param name the field name in the export table
   * @param arguments the arguments to pass, in order
   * @param hostContext the capabilities passed to every host call of this invocation
   * @param budget the limits for this invocation
   * @return the execution result
   * @throws IllegalArgumentException if the name is blank, longer than 256 characters or contains
   *     NUL
   * @throws IllegalStateException if the instance is not initialized, closed, faulted, executing or
   *     paused
   * @throws NullPointerException if an argument is null or {@code arguments} contains null
   */
  public synchronized ExecutionResult invokeExport(
      String name, List<LuauValue> arguments, HostCallContext hostContext, ExecutionBudget budget) {
    return invokeExportInternal(name, arguments, hostContext, budget, null);
  }

  /**
   * Invokes an exported function as part of a logical operation with a shared budget.
   *
   * <p>Only the budget that remains in the scope is applied. When the scope is already exhausted,
   * the call returns a {@link ExecutionResult.LimitExceeded} result without entering Luau and
   * faults the instance.
   *
   * @param name the field name in the export table
   * @param arguments the arguments to pass, in order
   * @param hostContext the capabilities passed to every host call of this invocation
   * @param scope the operation whose remaining budget this invocation consumes
   * @return the execution result
   * @throws IllegalArgumentException if the name is blank, longer than 256 characters or contains
   *     NUL
   * @throws IllegalStateException if the instance is not initialized, closed, faulted, executing or
   *     paused
   * @throws NullPointerException if an argument is null or {@code arguments} contains null
   */
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

  /**
   * Returns whether the VM rejected an invocation and now requires a reset.
   *
   * @return {@code true} when every invocation is refused until the instance is reset
   * @throws IllegalStateException if the instance is closed or called from another thread
   */
  public synchronized boolean isFaulted() {
    checkOwnerThread();
    if (nativeHandle == 0) {
      throw new IllegalStateException("Script instance is closed");
    }
    return faulted;
  }

  /**
   * Returns the thread this instance belongs to.
   *
   * @return the thread that created the instance
   */
  public Thread ownerThread() {
    return ownerThread;
  }

  /**
   * Returns the debugger bound to this instance.
   *
   * <p>The debugger accepts calls only from the owner thread and only while the instance is idle or
   * paused.
   *
   * @return the owner-thread debugger of this instance
   */
  public ScriptDebugger debugger() {
    return debugger;
  }

  /**
   * Replaces the VM with a clean one built from the same compiled bytecode.
   *
   * <p>Globals, module caches and the export table are dropped, the fault flag and the initialized
   * flag are cleared, and resolved breakpoints are carried over to the new VM. The instance stays
   * usable from its owner thread only.
   *
   * @throws IllegalStateException if the instance is closed, still executing, paused in the
   *     debugger, or called from another thread
   */
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

  /**
   * Closes the underlying VM and wipes the bytecode held by this instance.
   *
   * <p>Closing twice has no effect. A paused invocation is not terminated by closing; the instance
   * has to be idle.
   *
   * @throws IllegalStateException if the instance is still executing or called from another thread
   */
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
