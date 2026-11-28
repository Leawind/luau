package io.github.leawind.luau.v1.api.debug;

import java.util.List;
import java.util.Objects;

/**
 * Immutable debugger state captured when execution leaves the VM in a resumable pause.
 *
 * <p>The paused invocation stays alive until its debugger resumes, steps or terminates it, and the
 * instance rejects every other invocation meanwhile. The reason and the location must not be null,
 * and the frame list may hold at most 256 frames.
 *
 * @param reason why execution stopped
 * @param location the source location execution stopped at
 * @param stackFrames the Luau call stack at the pause, innermost frame first
 */
public record PauseInfo(Reason reason, SourceLocation location, List<StackFrame> stackFrames) {
  /** Rejects null arguments and null stack frames. */
  public PauseInfo {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(location, "location");
    stackFrames = List.copyOf(Objects.requireNonNull(stackFrames, "stackFrames"));
  }

  /** Why execution paused. */
  public enum Reason {
    /** A source breakpoint was reached. */
    BREAKPOINT,
    /** A pause was requested through the debugger. */
    REQUESTED,
    /** A debugger step completed. */
    STEP
  }
}
