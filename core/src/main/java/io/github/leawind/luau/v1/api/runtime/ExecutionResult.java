package io.github.leawind.luau.v1.api.runtime;

import io.github.leawind.luau.v1.api.debug.PauseInfo;
import io.github.leawind.luau.v1.api.value.LuauValue;
import java.util.List;
import java.util.Objects;

/** Result of one sandboxed Luau execution. */
public sealed interface ExecutionResult
    permits ExecutionResult.Completed,
        ExecutionResult.Paused,
        ExecutionResult.RuntimeFailed,
        ExecutionResult.LimitExceeded,
        ExecutionResult.Terminated {

  ExecutionMetrics metrics();

  /** Successful execution and all values returned by the chunk. */
  record Completed(List<LuauValue> values, ExecutionMetrics metrics) implements ExecutionResult {
    public Completed {
      values = List.copyOf(Objects.requireNonNull(values, "values"));
      Objects.requireNonNull(metrics, "metrics");
    }
  }

  /** A debugger pause that retains a resumable coroutine and its cumulative budget. */
  record Paused(PauseInfo pauseInfo, ExecutionMetrics metrics) implements ExecutionResult {
    public Paused {
      Objects.requireNonNull(pauseInfo, "pauseInfo");
      Objects.requireNonNull(metrics, "metrics");
    }
  }

  /** A Luau runtime or host boundary error. */
  record RuntimeFailed(String message, ExecutionMetrics metrics) implements ExecutionResult {
    public RuntimeFailed {
      Objects.requireNonNull(message, "message");
      Objects.requireNonNull(metrics, "metrics");
    }
  }

  /** Execution stopped because a hard resource limit was reached. */
  record LimitExceeded(Kind kind, String message, ExecutionMetrics metrics)
      implements ExecutionResult {
    public LimitExceeded {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(message, "message");
      Objects.requireNonNull(metrics, "metrics");
    }

    public enum Kind {
      RUNTIME,
      INTERRUPT_CHECKS,
      MEMORY,
      HOST_CALLS,
      HOST_OUTPUT,
      VALUE_CODEC
    }
  }

  /** A paused invocation discarded explicitly by its debugger. */
  record Terminated(String message, ExecutionMetrics metrics) implements ExecutionResult {
    public Terminated {
      Objects.requireNonNull(message, "message");
      Objects.requireNonNull(metrics, "metrics");
    }
  }
}
