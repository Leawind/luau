package io.github.suterxom.luau.v1.api.runtime;

import java.time.Duration;
import java.util.Objects;

/** Resource usage observed during one Luau invocation. */
public record ExecutionMetrics(
    Duration runtime,
    long interruptChecks,
    long currentMemoryBytes,
    long peakMemoryBytes) {
  public ExecutionMetrics {
    Objects.requireNonNull(runtime, "runtime");
    if (runtime.isNegative()
        || interruptChecks < 0
        || currentMemoryBytes < 0
        || peakMemoryBytes < currentMemoryBytes) {
      throw new IllegalArgumentException("Execution metrics must be non-negative and consistent");
    }
  }
}
