package io.github.leawind.luau.v1.api;

import io.github.leawind.luau.v1.api.runtime.ExecutionBudget;
import io.github.leawind.luau.v1.api.runtime.ExecutionMetrics;
import io.github.leawind.luau.v1.api.runtime.ExecutionResult;
import java.time.Duration;
import java.util.Objects;

/**
 * One logical operation's shared execution budget.
 *
 * <p>Pass the same scope to initialization and export calls to make them share the time, interrupt,
 * host-call and host-output limits. A scope is intended for one script instance's owner thread.
 */
public final class ExecutionScope {
  private final ExecutionBudget budget;
  private ExecutionMetrics metrics = new ExecutionMetrics(Duration.ZERO, 0, 0, 0, 0, 0);

  public ExecutionScope(ExecutionBudget budget) {
    this.budget = Objects.requireNonNull(budget, "budget");
  }

  ExecutionBudget remainingBudget() {
    long runtimeNanos = budget.maxRuntimeNanos() - metrics.runtime().toNanos();
    long interruptChecks = budget.maxInterruptChecks() - metrics.interruptChecks();
    if (runtimeNanos <= 0 || interruptChecks <= 0) {
      return null;
    }
    return new ExecutionBudget(
        Duration.ofNanos(runtimeNanos),
        interruptChecks,
        Math.max(0, budget.maxHostCalls() - metrics.hostCalls()),
        Math.max(0, budget.maxHostOutputBytes() - metrics.hostOutputBytes()));
  }

  ExecutionResult exhaustedResult() {
    ExecutionResult.LimitExceeded.Kind kind =
        metrics.runtime().toNanos() >= budget.maxRuntimeNanos()
            ? ExecutionResult.LimitExceeded.Kind.RUNTIME
            : ExecutionResult.LimitExceeded.Kind.INTERRUPT_CHECKS;
    String message =
        kind == ExecutionResult.LimitExceeded.Kind.RUNTIME
            ? "Luau execution exceeded its maximum runtime"
            : "Luau execution exceeded its interrupt-check budget";
    return new ExecutionResult.LimitExceeded(kind, message, metrics);
  }

  ExecutionMetrics aggregate(ExecutionMetrics current) {
    return add(metrics, current);
  }

  ExecutionMetrics metrics() {
    return metrics;
  }

  void consume(ExecutionMetrics current) {
    metrics = add(metrics, current);
  }

  private static ExecutionMetrics add(ExecutionMetrics left, ExecutionMetrics right) {
    return new ExecutionMetrics(
        left.runtime().plus(right.runtime()),
        addSaturated(left.interruptChecks(), right.interruptChecks()),
        right.currentMemoryBytes(),
        Math.max(left.peakMemoryBytes(), right.peakMemoryBytes()),
        addSaturated(left.hostCalls(), right.hostCalls()),
        addSaturated(left.hostOutputBytes(), right.hostOutputBytes()));
  }

  private static long addSaturated(long left, long right) {
    long sum = left + right;
    return sum < 0 || sum < left ? Long.MAX_VALUE : sum;
  }
}
