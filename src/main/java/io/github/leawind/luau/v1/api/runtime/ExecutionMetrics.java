package io.github.leawind.luau.v1.api.runtime;

import java.time.Duration;
import java.util.Objects;

/** Resource usage observed during one Luau invocation. */
public record ExecutionMetrics(
    Duration runtime,
    long interruptChecks,
    long currentMemoryBytes,
    long peakMemoryBytes,
    long hostCalls,
    long hostOutputBytes) {
  public ExecutionMetrics {
    Objects.requireNonNull(runtime, "runtime");
    if (runtime.isNegative()
        || interruptChecks < 0
        || currentMemoryBytes < 0
        || peakMemoryBytes < currentMemoryBytes
        || hostCalls < 0
        || hostOutputBytes < 0) {
      throw new IllegalArgumentException("Execution metrics must be non-negative and consistent");
    }
  }
}
