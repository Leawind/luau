package io.github.leawind.luau.v1.api.compile;

import java.util.Objects;

/**
 * A stable Java representation of a source compilation diagnostic.
 *
 * <p>The message carries the upstream Luau text; source positions are exposed separately through
 * {@link #sourceRange()}. No component may be null.
 *
 * @param stage the compilation stage that produced the diagnostic
 * @param severity the severity of the diagnostic
 * @param code a stable identifier for the diagnostic category
 * @param moduleName the logical module the diagnostic belongs to
 * @param message the upstream Luau text
 * @param sourceRange the source span, or {@link SourceRange#UNKNOWN} when the stage reports none
 */
public record CompilationDiagnostic(
    Stage stage,
    Severity severity,
    String code,
    String moduleName,
    String message,
    SourceRange sourceRange) {
  /** Rejects null components. */
  public CompilationDiagnostic {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(moduleName, "moduleName");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(sourceRange, "sourceRange");
  }

  /**
   * Creates a compile-stage error diagnostic.
   *
   * @param moduleName the logical module the error belongs to
   * @param message the upstream Luau text
   * @return a diagnostic with stage {@link Stage#COMPILE}, severity {@link Severity#ERROR}, code
   *     {@code LUAU_COMPILE_ERROR} and an unknown source range
   */
  public static CompilationDiagnostic compileError(String moduleName, String message) {
    return new CompilationDiagnostic(
        Stage.COMPILE,
        Severity.ERROR,
        "LUAU_COMPILE_ERROR",
        moduleName,
        message,
        SourceRange.UNKNOWN);
  }

  /**
   * Creates a type-stage error diagnostic.
   *
   * @param moduleName the logical module the error belongs to
   * @param message the upstream Luau text
   * @param sourceRange the source span of the error
   * @return a diagnostic with stage {@link Stage#TYPE}, severity {@link Severity#ERROR} and code
   *     {@code LUAU_TYPE_ERROR}
   */
  public static CompilationDiagnostic typeError(
      String moduleName, String message, SourceRange sourceRange) {
    return new CompilationDiagnostic(
        Stage.TYPE, Severity.ERROR, "LUAU_TYPE_ERROR", moduleName, message, sourceRange);
  }

  /**
   * Creates a parse-stage error diagnostic.
   *
   * @param moduleName the logical module the error belongs to
   * @param message the upstream Luau text
   * @param sourceRange the source span of the error
   * @return a diagnostic with stage {@link Stage#PARSE}, severity {@link Severity#ERROR} and code
   *     {@code LUAU_PARSE_ERROR}
   */
  public static CompilationDiagnostic parseError(
      String moduleName, String message, SourceRange sourceRange) {
    return new CompilationDiagnostic(
        Stage.PARSE, Severity.ERROR, "LUAU_PARSE_ERROR", moduleName, message, sourceRange);
  }

  /** The compilation stage a diagnostic comes from. */
  public enum Stage {
    /** Parsing the source text. */
    PARSE,
    /** Strict type checking. */
    TYPE,
    /** Linting. No compilation result currently carries this stage. */
    LINT,
    /** Compiling the source to bytecode. */
    COMPILE
  }

  /** How severe a diagnostic is. */
  public enum Severity {
    /** The diagnostic failed the compilation. */
    ERROR,
    /** A warning. No compilation result currently carries this severity. */
    WARNING,
    /** Information. No compilation result currently carries this severity. */
    INFORMATION
  }
}
