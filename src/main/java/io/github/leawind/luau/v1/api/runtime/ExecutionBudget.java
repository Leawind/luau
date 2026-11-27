package io.github.leawind.luau.v1.api.runtime;

import java.time.Duration;
import java.util.Objects;

/** Wall-clock, safepoint, and host-boundary limits for one Luau invocation. */
public record ExecutionBudget(
    Duration maxRuntime, long maxInterruptChecks, long maxHostCalls, long maxHostOutputBytes) {
  private static final Duration MAX_RUNTIME = Duration.ofHours(1);

  public static final ExecutionBudget DEFAULT =
      new ExecutionBudget(Duration.ofMillis(250), 1_000_000, 10_000, 1024 * 1024);

  /** Creates a budget with the default host-boundary limits. */
  public ExecutionBudget(Duration maxRuntime, long maxInterruptChecks) {
    this(maxRuntime, maxInterruptChecks, DEFAULT.maxHostCalls, DEFAULT.maxHostOutputBytes);
  }

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

  public long maxRuntimeNanos() {
    return maxRuntime.toNanos();
  }
}
