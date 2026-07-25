package io.github.suterxom.luau.v1.api.debug;

import java.util.List;
import java.util.Objects;

/** Immutable debugger state captured when execution leaves the VM in a resumable pause. */
public record PauseInfo(Reason reason, SourceLocation location, List<StackFrame> stackFrames) {
  public PauseInfo {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(location, "location");
    stackFrames = List.copyOf(Objects.requireNonNull(stackFrames, "stackFrames"));
  }

  public enum Reason {
    BREAKPOINT,
    REQUESTED,
    STEP
  }
}
