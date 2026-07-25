package io.github.suterxom.luau.v1.api.runtime;

import io.github.suterxom.luau.v1.api.value.LuauValue;
import java.util.List;
import java.util.Objects;

/** Result of one sandboxed Luau execution. */
public sealed interface ExecutionResult
    permits ExecutionResult.Completed, ExecutionResult.RuntimeFailed {

  /** Successful execution and all values returned by the chunk. */
  record Completed(List<LuauValue> values) implements ExecutionResult {
    public Completed {
      values = List.copyOf(Objects.requireNonNull(values, "values"));
    }
  }

  /** A Luau runtime or host boundary error. */
  record RuntimeFailed(String message) implements ExecutionResult {
    public RuntimeFailed {
      Objects.requireNonNull(message, "message");
    }
  }
}
