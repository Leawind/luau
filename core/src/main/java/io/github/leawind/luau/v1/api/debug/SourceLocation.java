package io.github.leawind.luau.v1.api.debug;

import java.util.Objects;

/**
 * A one-based source line in a logical Luau module.
 *
 * <p>The module name has to be non-blank and the line positive.
 *
 * @param moduleName the logical module name
 * @param line the one-based source line
 */
public record SourceLocation(String moduleName, int line) {
  /** Rejects a null or blank module name and a non-positive line. */
  public SourceLocation {
    Objects.requireNonNull(moduleName, "moduleName");
    if (moduleName.isBlank()) {
      throw new IllegalArgumentException("Source module name must not be blank");
    }
    if (line <= 0) {
      throw new IllegalArgumentException("Source line must be positive");
    }
  }
}
