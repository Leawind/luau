package io.github.leawind.luau.v1.api.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * Wall-clock, safepoint, and host-boundary limits for one Luau invocation.
 *
 * <p>The default budget allows 250 milliseconds of runtime, one million interrupt checks, 10,000
 * host calls and 1 MiB of host output. A limit outside its range is rejected with an {@code
 * IllegalArgumentException}.
 *
 * @param maxRuntime the maximum wall-clock runtime, positive and at most one hour
 * @param maxInterruptChecks the maximum number of Luau interrupt checks, positive
 * @param maxHostCalls the maximum number of host calls, non-negative
 * @param maxHostOutputBytes the maximum number of bytes host functions may emit, non-negative
 */
public record ExecutionBudget(
    Duration maxRuntime, long maxInterruptChecks, long maxHostCalls, long maxHostOutputBytes) {
  private static final Duration MAX_RUNTIME = Duration.ofHours(1);

  /**
   * The default budget: 250 milliseconds of runtime, one million interrupt checks, 10,000 host
   * calls and 1 MiB of host output.
   */
  public static final ExecutionBudget DEFAULT =
      new ExecutionBudget(Duration.ofMillis(250), 1_000_000, 10_000, 1024 * 1024);

  /**
   * Creates a budget with the default host-boundary limits.
   *
   * @param maxRuntime the maximum wall-clock runtime, positive and at most one hour
   * @param maxInterruptChecks the maximum number of Luau interrupt checks, positive
   * @throws IllegalArgumentException if a limit is out of range
   * @throws NullPointerException if {@code maxRuntime} is null
   */
  public ExecutionBudget(Duration maxRuntime, long maxInterruptChecks) {
    this(maxRuntime, maxInterruptChecks, DEFAULT.maxHostCalls, DEFAULT.maxHostOutputBytes);
  }

  /** Rejects limits outside their documented ranges. */
  public ExecutionBudget {
    Objects.requireNonNull(maxRuntime, "maxRuntime");
    if (maxRuntime.isZero() || maxRuntime.isNegative()) {
      throw new IllegalArgumentException("Maximum runtime must be positive");
    }
    if (maxRuntime.compareTo(MAX_RUNTIME) > 0) {
      throw new IllegalArgumentException("Maximum runtime must not exceed " + MAX_RUNTIME);
    }
    if (maxInterruptChecks <= 0) {
      throw new IllegalArgumentException("Maximum interrupt checks must be positive");
    }
    if (maxHostCalls < 0) {
      throw new IllegalArgumentException("Maximum host calls must not be negative");
    }
    if (maxHostOutputBytes < 0) {
      throw new IllegalArgumentException("Maximum host output bytes must not be negative");
    }
  }

  /**
   * Returns the runtime limit in nanoseconds.
   *
   * @return the maximum runtime expressed in nanoseconds
   */
  public long maxRuntimeNanos() {
    return maxRuntime.toNanos();
  }
}
