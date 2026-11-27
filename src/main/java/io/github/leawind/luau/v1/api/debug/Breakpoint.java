package io.github.leawind.luau.v1.api.debug;

import java.util.Objects;

/** The result of resolving a requested source breakpoint. */
public record Breakpoint(String moduleName, int requestedLine, int resolvedLine) {
  public Breakpoint {
    Objects.requireNonNull(moduleName, "moduleName");
    if (requestedLine <= 0) {
      throw new IllegalArgumentException("Requested breakpoint line must be positive");
    }
    if (resolvedLine < 0) {
      throw new IllegalArgumentException("Resolved breakpoint line must not be negative");
    }
  }

  /** Returns whether the requested line resolved to an executable instruction. */
  public boolean resolved() {
    return resolvedLine > 0;
  }
}
