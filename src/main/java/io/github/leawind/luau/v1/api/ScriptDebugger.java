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

/** Owner-thread debugger for one {@link ScriptInstance}. */
public final class ScriptDebugger {
  private final ScriptInstance instance;
  private final Map<RequestKey, Breakpoint> breakpoints = new LinkedHashMap<>();
  private final Map<ResolvedKey, Integer> resolvedReferences = new LinkedHashMap<>();

  ScriptDebugger(ScriptInstance instance) {
    this.instance = Objects.requireNonNull(instance, "instance");
  }

  /**
   * Enables a source breakpoint and returns the executable line selected by Luau.
   *
   * <p>An unresolved breakpoint has {@code resolvedLine() == 0} and is not retained.
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

  /** Disables a breakpoint previously returned by {@link #setBreakpoint}. */
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

  /** Disables every active breakpoint. */
  public void clearBreakpoints() {
    instance.checkDebuggerAccess(false);
    for (ResolvedKey breakpoint : Set.copyOf(resolvedReferences.keySet())) {
      instance.updateBreakpoint(breakpoint.moduleName(), breakpoint.line(), false);
    }
    resolvedReferences.clear();
    breakpoints.clear();
  }

  /** Returns an immutable snapshot of active resolved breakpoints. */
  public Set<Breakpoint> breakpoints() {
    instance.checkDebuggerReadAccess();
    return Set.copyOf(breakpoints.values());
  }

  public boolean isPaused() {
    return instance.debuggerPauseInfo().isPresent();
  }

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

  /** Continues the currently paused invocation with its original context and remaining budget. */
  public ExecutionResult resume() {
    return instance.resumePaused();
  }

  /** Continues until execution reaches a different visible source location. */
  public ExecutionResult stepInto() {
    return instance.stepPaused(1);
  }

  /** Continues until the current frame reaches a new location or returns. */
  public ExecutionResult stepOver() {
    return instance.stepPaused(2);
  }

  /** Continues until execution returns to a shallower Luau call stack. */
  public ExecutionResult stepOut() {
    return instance.stepPaused(3);
  }

  /** Discards the currently paused invocation and faults the instance. */
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
