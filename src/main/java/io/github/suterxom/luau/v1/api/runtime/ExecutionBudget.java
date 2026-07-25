package io.github.suterxom.luau.v1.api.runtime;

import java.time.Duration;
import java.util.Objects;

/** Wall-clock and safepoint limits for one Luau invocation. */
public record ExecutionBudget(Duration maxRuntime, long maxInterruptChecks) {
  private static final Duration MAX_RUNTIME = Duration.ofHours(1);

  public static final ExecutionBudget DEFAULT =
      new ExecutionBudget(Duration.ofMillis(250), 1_000_000);

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
  }

  public long maxRuntimeNanos() {
    return maxRuntime.toNanos();
  }
}
