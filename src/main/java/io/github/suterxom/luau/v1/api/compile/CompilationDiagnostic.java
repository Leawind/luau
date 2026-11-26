package io.github.suterxom.luau.v1.api.compile;

import java.util.Objects;

/**
 * A stable Java representation of a source compilation diagnostic.
 *
 * <p>The message carries the upstream Luau text; source positions are exposed separately through
 * {@link #sourceRange()}.
 */
public record CompilationDiagnostic(
    Stage stage,
    Severity severity,
    String code,
    String moduleName,
    String message,
    SourceRange sourceRange) {
  public CompilationDiagnostic {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(moduleName, "moduleName");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(sourceRange, "sourceRange");
  }

  public static CompilationDiagnostic compileError(String moduleName, String message) {
    return new CompilationDiagnostic(
        Stage.COMPILE,
        Severity.ERROR,
        "LUAU_COMPILE_ERROR",
        moduleName,
        message,
        SourceRange.UNKNOWN);
  }

  public static CompilationDiagnostic typeError(
      String moduleName, String message, SourceRange sourceRange) {
    return new CompilationDiagnostic(
        Stage.TYPE, Severity.ERROR, "LUAU_TYPE_ERROR", moduleName, message, sourceRange);
  }

  public static CompilationDiagnostic parseError(
      String moduleName, String message, SourceRange sourceRange) {
    return new CompilationDiagnostic(
        Stage.PARSE, Severity.ERROR, "LUAU_PARSE_ERROR", moduleName, message, sourceRange);
  }

  public enum Stage {
    PARSE,
    TYPE,
    LINT,
    COMPILE
  }

  public enum Severity {
    ERROR,
    WARNING,
    INFORMATION
  }
}
