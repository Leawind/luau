package io.github.leawind.luau.internal.jni;

import io.github.leawind.luau.v1.api.compile.CompilationDiagnostic;
import io.github.leawind.luau.v1.api.compile.SourceRange;
import io.github.leawind.luau.v1.api.debug.PauseInfo;
import io.github.leawind.luau.v1.api.debug.SourceLocation;
import io.github.leawind.luau.v1.api.debug.StackFrame;
import io.github.leawind.luau.v1.api.runtime.ExecutionMetrics;
import io.github.leawind.luau.v1.api.runtime.ExecutionResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public final class NativeResultDecoder {
  private NativeResultDecoder() {}

  public static ExecutionResult decodeExecutionResult(NativeExecutionResult result) {
    ExecutionMetrics metrics =
        new ExecutionMetrics(
            Duration.ofNanos(result.elapsedNanos()),
            result.interruptChecks(),
            result.currentMemoryBytes(),
            result.peakMemoryBytes(),
            result.hostCalls(),
            result.hostOutputBytes());
    String message =
        result.messageUtf8() == null
            ? ""
            : new String(result.messageUtf8(), StandardCharsets.UTF_8);
    return switch (result.status()) {
      case 0 ->
          new ExecutionResult.Completed(
              NativeValueCodec.fromNativeValues(result.values()), metrics);
      case 1 -> new ExecutionResult.RuntimeFailed(message, metrics);
      case 2 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.RUNTIME, message, metrics);
      case 3 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.INTERRUPT_CHECKS, message, metrics);
      case 4 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.MEMORY, message, metrics);
      case 5 -> new ExecutionResult.RuntimeFailed(message, metrics);
      case 6 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.VALUE_CODEC, message, metrics);
      case 7 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.HOST_CALLS, message, metrics);
      case 8 ->
          new ExecutionResult.LimitExceeded(
              ExecutionResult.LimitExceeded.Kind.HOST_OUTPUT, message, metrics);
      case 9 -> new ExecutionResult.Paused(decodePauseInfo(result.pauseInfo()), metrics);
      case 10 -> new ExecutionResult.Terminated(message, metrics);
      default -> throw new LinkageError("Unknown native execution status: " + result.status());
    };
  }

  public static PauseInfo decodePauseInfo(NativePauseInfo nativePause) {
    if (nativePause == null) {
      throw new LinkageError("Native paused execution did not include pause information");
    }
    PauseInfo.Reason reason =
        switch (nativePause.reason()) {
          case 0 -> PauseInfo.Reason.BREAKPOINT;
          case 1 -> PauseInfo.Reason.REQUESTED;
          case 2 -> PauseInfo.Reason.STEP;
          default -> throw new LinkageError("Unknown native pause reason: " + nativePause.reason());
        };
    SourceLocation location =
        new SourceLocation(
            new String(nativePause.moduleNameUtf8(), StandardCharsets.UTF_8), nativePause.line());
    List<StackFrame> frames =
        Arrays.stream(nativePause.stackFrames())
            .map(NativeResultDecoder::decodeStackFrame)
            .toList();
    return new PauseInfo(reason, location, frames);
  }

  public static StackFrame decodeStackFrame(NativeStackFrame frame) {
    return new StackFrame(
        new SourceLocation(
            new String(frame.moduleNameUtf8(), StandardCharsets.UTF_8), frame.line()),
        new String(frame.functionNameUtf8(), StandardCharsets.UTF_8));
  }

  public static List<CompilationDiagnostic> decodeDiagnostics(
      NativeCompilationDiagnostic[] diagnostics) {
    return Arrays.stream(diagnostics).map(NativeResultDecoder::decodeDiagnostic).toList();
  }

  public static CompilationDiagnostic decodeDiagnostic(NativeCompilationDiagnostic diagnostic) {
    String moduleName = new String(diagnostic.moduleNameUtf8(), StandardCharsets.UTF_8);
    String message = new String(diagnostic.messageUtf8(), StandardCharsets.UTF_8);
    SourceRange sourceRange =
        new SourceRange(
            diagnostic.startLine(),
            diagnostic.startColumn(),
            diagnostic.endLine(),
            diagnostic.endColumn());
    return switch (diagnostic.stage()) {
      case 0 -> CompilationDiagnostic.parseError(moduleName, message, sourceRange);
      case 1 -> CompilationDiagnostic.typeError(moduleName, message, sourceRange);
      default ->
          throw new LinkageError(
              "Unknown native compilation diagnostic stage: " + diagnostic.stage());
    };
  }
}
