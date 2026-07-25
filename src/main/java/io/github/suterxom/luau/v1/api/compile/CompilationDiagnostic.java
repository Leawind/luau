package io.github.suterxom.luau.v1.api.compile;

import java.util.Objects;

/** A stable Java representation of a source compilation diagnostic. */
public record CompilationDiagnostic(Stage stage, Severity severity, String code, String moduleName, String message) {
  public CompilationDiagnostic {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(moduleName, "moduleName");
    Objects.requireNonNull(message, "message");
  }
  
  public static CompilationDiagnostic compileError(String moduleName, String message) {
    return new CompilationDiagnostic(Stage.COMPILE, Severity.ERROR, "LUAU_COMPILE_ERROR", moduleName, message);
  }
  
  public static CompilationDiagnostic typeError(String moduleName, String message) {
    return new CompilationDiagnostic(Stage.TYPE, Severity.ERROR, "LUAU_TYPE_ERROR", moduleName, message);
  }
  
  public static CompilationDiagnostic parseError(String moduleName, String message) {
    return new CompilationDiagnostic(Stage.PARSE, Severity.ERROR, "LUAU_PARSE_ERROR", moduleName, message);
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
