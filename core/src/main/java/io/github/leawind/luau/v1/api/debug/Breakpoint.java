package io.github.leawind.luau.v1.api.debug;

import java.util.Objects;

/**
 * The result of resolving a requested source breakpoint.
 *
 * <p>A breakpoint that did not resolve to an executable line has no effect and is not retained by
 * the debugger that produced it. The requested line has to be positive, the resolved line must not
 * be negative, and the module name must not be null.
 *
 * @param moduleName the logical module the breakpoint was requested for
 * @param requestedLine the one-based line the caller asked for
 * @param resolvedLine the one-based executable line Luau selected, or {@code 0} when the request
 *     did not resolve
 */
public record Breakpoint(String moduleName, int requestedLine, int resolvedLine) {
  /** Rejects a null module name, a non-positive requested line, and a negative resolved line. */
  public Breakpoint {
    Objects.requireNonNull(moduleName, "moduleName");
    if (requestedLine <= 0) {
      throw new IllegalArgumentException("Requested breakpoint line must be positive");
    }
    if (resolvedLine < 0) {
      throw new IllegalArgumentException("Resolved breakpoint line must not be negative");
    }
  }

  /**
   * Returns whether the requested line resolved to an executable instruction.
   *
   * @return {@code true} when {@link #resolvedLine()} is positive
   */
  public boolean resolved() {
    return resolvedLine > 0;
  }
}
