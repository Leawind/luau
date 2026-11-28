package io.github.leawind.luau.v1.api;

import io.github.leawind.luau.internal.validation.ModuleNames;
import io.github.leawind.luau.v1.api.debug.Breakpoint;
import io.github.leawind.luau.v1.api.debug.PauseInfo;
import io.github.leawind.luau.v1.api.runtime.ExecutionResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owner-thread debugger for one {@link ScriptInstance}.
 *
 * <p>Breakpoints and stepping refer to the compiled program the instance was created from. Every
 * method except {@link #requestPause()} and {@link #requestTerminate()} has to run on the
 * instance's owner thread while it is not executing; changing breakpoints additionally requires a
 * healthy, unpaused instance, while the read-only methods also work while it is paused and while it
 * is faulted.
 */
public final class ScriptDebugger {
  private final ScriptInstance instance;
  private final Map<RequestKey, Breakpoint> breakpoints = new LinkedHashMap<>();
  private final Map<ResolvedKey, Integer> resolvedReferences = new LinkedHashMap<>();

  ScriptDebugger(ScriptInstance instance) {
    this.instance = Objects.requireNonNull(instance, "instance");
  }

  /**
   * Enables a source breakpoint and returns the executable line Luau selected.
   *
   * <p>A breakpoint that resolved is retained until it is removed or cleared and stops execution
   * whenever that line is about to run. A request that did not resolve reports {@code
   * resolvedLine() == 0} and is not retained. Asking for the same line again returns the existing
   * breakpoint.
   *
   * @param moduleName the logical module name
   * @param line the one-based requested line
   * @return the resolved breakpoint
   * @throws IllegalArgumentException if the module name is not a valid logical module name or the
   *     line is not positive
   * @throws IllegalStateException if the instance is closed, faulted, executing or paused
   * @throws NullPointerException if {@code moduleName} is null
   */
  public Breakpoint setBreakpoint(String moduleName, int line) {
    ModuleNames.requireValid(moduleName);
    if (line <= 0) {
      throw new IllegalArgumentException("Breakpoint line must be positive");
    }
    instance.checkDebuggerAccess(false);
    RequestKey request = new RequestKey(moduleName, line);
    Breakpoint existing = breakpoints.get(request);
    if (existing != null) {
      return existing;
    }

    int resolvedLine = instance.updateBreakpoint(moduleName, line, true);
    Breakpoint breakpoint = new Breakpoint(moduleName, line, Math.max(resolvedLine, 0));
    if (breakpoint.resolved()) {
      breakpoints.put(request, breakpoint);
      ResolvedKey resolved = new ResolvedKey(moduleName, resolvedLine);
      resolvedReferences.merge(resolved, 1, Integer::sum);
    }
    return breakpoint;
  }

  /**
   * Disables a breakpoint previously returned by {@link #setBreakpoint}.
   *
   * <p>Only the exact active breakpoint can be removed; a breakpoint that already was removed, or
   * one that never resolved, is rejected.
   *
   * @param breakpoint the active breakpoint to disable
   * @throws IllegalArgumentException if the breakpoint is not active on this debugger
   * @throws IllegalStateException if the instance is closed, faulted, executing or paused
   * @throws NullPointerException if {@code breakpoint} is null
   */
  public void removeBreakpoint(Breakpoint breakpoint) {
    instance.checkDebuggerAccess(false);
    Objects.requireNonNull(breakpoint, "breakpoint");
    RequestKey request = new RequestKey(breakpoint.moduleName(), breakpoint.requestedLine());
    Breakpoint active = breakpoints.get(request);
    if (!breakpoint.equals(active)) {
      throw new IllegalArgumentException("Breakpoint is not active on this debugger");
    }

    ResolvedKey resolved = new ResolvedKey(breakpoint.moduleName(), breakpoint.resolvedLine());
    int references = resolvedReferences.getOrDefault(resolved, 0);
    if (references <= 1) {
      instance.updateBreakpoint(breakpoint.moduleName(), breakpoint.resolvedLine(), false);
      resolvedReferences.remove(resolved);
    } else {
      resolvedReferences.put(resolved, references - 1);
    }
    breakpoints.remove(request);
  }

  /**
   * Disables every active breakpoint.
   *
   * @throws IllegalStateException if the instance is closed, faulted, executing or paused
   */
  public void clearBreakpoints() {
    instance.checkDebuggerAccess(false);
    for (ResolvedKey breakpoint : Set.copyOf(resolvedReferences.keySet())) {
      instance.updateBreakpoint(breakpoint.moduleName(), breakpoint.line(), false);
    }
    resolvedReferences.clear();
    breakpoints.clear();
  }

  /**
   * Returns a snapshot of the breakpoints that are currently active.
   *
   * @return an immutable set of resolved breakpoints
   * @throws IllegalStateException if the instance is closed or executing
   */
  public Set<Breakpoint> breakpoints() {
    instance.checkDebuggerReadAccess();
    return Set.copyOf(breakpoints.values());
  }

  /**
   * Returns whether an invocation is paused in this debugger.
   *
   * @return {@code true} while a resumable invocation waits for the debugger
   * @throws IllegalStateException if the instance is closed or executing
   */
  public boolean isPaused() {
    return instance.debuggerPauseInfo().isPresent();
  }

  /**
   * Returns where and why the currently paused invocation stopped.
   *
   * @return the pause state, or empty when no invocation is paused
   * @throws IllegalStateException if the instance is closed or executing
   */
  public Optional<PauseInfo> pauseInfo() {
    return instance.debuggerPauseInfo();
  }

  /**
   * Requests a resumable pause at the next yieldable Luau safepoint.
   *
   * <p>This method is thread-safe and does not access the Luau stack from the calling thread.
   */
  public void requestPause() {
    instance.requestDebuggerPause();
  }

  /**
   * Requests termination at the next Luau safepoint or before a paused invocation resumes.
   *
   * <p>This method is thread-safe and does not access the Luau stack from the calling thread.
   */
  public void requestTerminate() {
    instance.requestDebuggerTermination();
  }

  /**
   * Continues the paused invocation with its original context and remaining budget.
   *
   * @return the result of the continued execution
   * @throws IllegalStateException if the instance is closed, faulted, executing or not paused
   */
  public ExecutionResult resume() {
    return instance.resumePaused();
  }

  /**
   * Continues until execution reaches a different visible source location.
   *
   * @return the result of the continued execution
   * @throws IllegalStateException if the instance is closed, faulted, executing or not paused
   */
  public ExecutionResult stepInto() {
    return instance.stepPaused(1);
  }

  /**
   * Continues until the current frame reaches a new location or returns.
   *
   * @return the result of the continued execution
   * @throws IllegalStateException if the instance is closed, faulted, executing or not paused
   */
  public ExecutionResult stepOver() {
    return instance.stepPaused(2);
  }

  /**
   * Continues until execution returns from the current Luau call level.
   *
   * @return the result of the continued execution
   * @throws IllegalStateException if the instance is closed, faulted, executing or not paused
   */
  public ExecutionResult stepOut() {
    return instance.stepPaused(3);
  }

  /**
   * Discards the paused invocation and faults the instance.
   *
   * <p>Only resetting or closing the instance remains possible afterwards.
   *
   * @return a result that reports the termination
   * @throws IllegalStateException if the instance is closed, faulted, executing or not paused
   */
  public ExecutionResult terminate() {
    return instance.terminatePaused();
  }

  void reapplyAfterReset() {
    for (ResolvedKey breakpoint : resolvedReferences.keySet()) {
      int resolved = instance.updateBreakpoint(breakpoint.moduleName(), breakpoint.line(), true);
      if (resolved != breakpoint.line()) {
        throw new IllegalStateException(
            "Breakpoint resolution changed after resetting the same compiled program");
      }
    }
  }

  private record RequestKey(String moduleName, int line) {}

  private record ResolvedKey(String moduleName, int line) {}
}
