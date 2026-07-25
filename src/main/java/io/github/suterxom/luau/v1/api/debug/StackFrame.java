package io.github.suterxom.luau.v1.api.debug;

import java.util.Objects;

/** A read-only Luau stack frame captured at a debugger pause. */
public record StackFrame(SourceLocation location, String functionName) {
  public StackFrame {
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(functionName, "functionName");
  }
}
