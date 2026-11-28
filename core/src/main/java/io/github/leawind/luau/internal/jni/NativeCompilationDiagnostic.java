package io.github.leawind.luau.internal.jni;

public record NativeCompilationDiagnostic(
    int stage,
    byte[] moduleNameUtf8,
    byte[] messageUtf8,
    int startLine,
    int startColumn,
    int endLine,
    int endColumn) {
  public NativeCompilationDiagnostic {
    moduleNameUtf8 = moduleNameUtf8.clone();
    messageUtf8 = messageUtf8.clone();
  }

  @Override
  public byte[] moduleNameUtf8() {
    return moduleNameUtf8.clone();
  }

  @Override
  public byte[] messageUtf8() {
    return messageUtf8.clone();
  }
}
