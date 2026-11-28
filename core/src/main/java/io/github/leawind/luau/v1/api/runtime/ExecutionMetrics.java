package io.github.leawind.luau.v1.api.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * Resource usage observed during one Luau invocation.
 *
 * <p>When the invocation belongs to an {@link io.github.leawind.luau.v1.api.ExecutionScope}, the
 * reported metrics are the totals of the whole operation so far rather than of the last call alone.
 * Values below are rejected when negative, and the peak may not be below the current memory.
 *
 * @param runtime the accumulated wall-clock runtime
 * @param interruptChecks the number of Luau interrupt checks
 * @param currentMemoryBytes the VM memory in use when the invocation settled
 * @param peakMemoryBytes the highest VM memory usage observed, never below {@code
 *     currentMemoryBytes}
 * @param hostCalls the number of host calls
 * @param hostOutputBytes the number of bytes host functions emitted
 */
public record ExecutionMetrics(
    Duration runtime,
    long interruptChecks,
    long currentMemoryBytes,
    long peakMemoryBytes,
    long hostCalls,
    long hostOutputBytes) {
  /** Rejects negative values and a peak below the current memory. */
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
