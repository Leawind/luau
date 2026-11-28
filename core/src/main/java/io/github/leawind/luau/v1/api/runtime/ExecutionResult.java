package io.github.leawind.luau.v1.api.runtime;

import io.github.leawind.luau.v1.api.debug.PauseInfo;
import io.github.leawind.luau.v1.api.value.LuauValue;
import java.util.List;
import java.util.Objects;

/**
 * Result of one sandboxed Luau execution.
 *
 * <p>Execution either completes, pauses in the debugger, fails inside Luau or at the host boundary,
 * stops because a hard limit was reached, or is terminated by the debugger. Every outcome except
 * completion and pausing faults the instance that ran it.
 */
public sealed interface ExecutionResult
    permits ExecutionResult.Completed,
        ExecutionResult.Paused,
        ExecutionResult.RuntimeFailed,
        ExecutionResult.LimitExceeded,
        ExecutionResult.Terminated {

  /**
   * Returns the resource usage of the invocation that produced this result.
   *
   * @return the metrics of the invocation
   */
  ExecutionMetrics metrics();

  /**
   * Successful execution and all values returned by the chunk.
   *
   * <p>The value list may not contain null.
   *
   * @param values the values the chunk returned, empty when it returned none
   * @param metrics the resource usage of the invocation
   */
  record Completed(List<LuauValue> values, ExecutionMetrics metrics) implements ExecutionResult {
    /** Rejects null arguments and null values. */
    public Completed {
      values = List.copyOf(Objects.requireNonNull(values, "values"));
      Objects.requireNonNull(metrics, "metrics");
    }
  }

  /**
   * A debugger pause that retains a resumable coroutine and its cumulative budget.
   *
   * <p>A paused invocation keeps its instance busy: other invocations are rejected until the
   * debugger resumes, steps or terminates it.
   *
   * @param pauseInfo why and where execution stopped
   * @param metrics the resource usage accumulated up to the pause
   */
  record Paused(PauseInfo pauseInfo, ExecutionMetrics metrics) implements ExecutionResult {
    /** Rejects null arguments. */
    public Paused {
      Objects.requireNonNull(pauseInfo, "pauseInfo");
      Objects.requireNonNull(metrics, "metrics");
    }
  }

  /**
   * A Luau runtime or host boundary error.
   *
   * <p>The message is the Luau error text; for a host function failure it is the message of the
   * {@code HostCallException} that function threw.
   *
   * @param message the error text
   * @param metrics the resource usage of the invocation
   */
  record RuntimeFailed(String message, ExecutionMetrics metrics) implements ExecutionResult {
    /** Rejects null arguments. */
    public RuntimeFailed {
      Objects.requireNonNull(message, "message");
      Objects.requireNonNull(metrics, "metrics");
    }
  }

  /**
   * Execution stopped because a hard resource limit was reached.
   *
   * @param kind the limit that stopped execution
   * @param message a human-readable explanation
   * @param metrics the resource usage at the point the limit was hit
   */
  record LimitExceeded(Kind kind, String message, ExecutionMetrics metrics)
      implements ExecutionResult {
    /** Rejects null arguments. */
    public LimitExceeded {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(message, "message");
      Objects.requireNonNull(metrics, "metrics");
    }

    /** The limit that stopped execution. */
    public enum Kind {
      /** The wall-clock runtime limit was reached. */
      RUNTIME,
      /** The interrupt-check budget was used up. */
      INTERRUPT_CHECKS,
      /** The VM heap limit was reached. */
      MEMORY,
      /** The host-call budget was used up. */
      HOST_CALLS,
      /** The host-output budget was used up. */
      HOST_OUTPUT,
      /** A value could not be transferred within the codec limits. */
      VALUE_CODEC
    }
  }

  /**
   * A paused invocation discarded explicitly by its debugger.
   *
   * <p>The instance is faulted afterwards; only resetting or closing it remains possible.
   *
   * @param message a human-readable explanation
   * @param metrics the resource usage accumulated before the termination
   */
  record Terminated(String message, ExecutionMetrics metrics) implements ExecutionResult {
    /** Rejects null arguments. */
    public Terminated {
      Objects.requireNonNull(message, "message");
      Objects.requireNonNull(metrics, "metrics");
    }
  }
}
