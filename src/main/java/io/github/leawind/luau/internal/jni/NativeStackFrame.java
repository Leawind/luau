package io.github.leawind.luau.internal.jni;

public record NativeStackFrame(byte[] moduleNameUtf8, byte[] functionNameUtf8, int line) {
  public NativeStackFrame {
    moduleNameUtf8 = moduleNameUtf8.clone();
    functionNameUtf8 = functionNameUtf8.clone();
  }

  @Override
  public byte[] moduleNameUtf8() {
    return moduleNameUtf8.clone();
  }

  @Override
  public byte[] functionNameUtf8() {
    return functionNameUtf8.clone();
  }
}
