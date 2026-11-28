package io.github.leawind.luau.v1.api.debug;

import java.util.Objects;

/**
 * A read-only Luau stack frame captured at a debugger pause.
 *
 * <p>Neither component may be null.
 *
 * @param location the frame's current source location
 * @param functionName the name Luau reports for the function, empty when it has none
 */
public record StackFrame(SourceLocation location, String functionName) {
  /** Rejects null arguments. */
  public StackFrame {
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(functionName, "functionName");
  }
}
