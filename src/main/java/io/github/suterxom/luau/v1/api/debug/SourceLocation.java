package io.github.suterxom.luau.v1.api.debug;

import java.util.Objects;

/** A one-based source line in a logical Luau module. */
public record SourceLocation(String moduleName, int line) {
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
