package io.github.suterxom.luau.internal.jni;

public record NativePauseInfo(
    int reason, byte[] moduleNameUtf8, int line, NativeStackFrame[] stackFrames) {
  public NativePauseInfo {
    moduleNameUtf8 = moduleNameUtf8.clone();
    stackFrames = stackFrames.clone();
  }

  @Override
  public byte[] moduleNameUtf8() {
    return moduleNameUtf8.clone();
  }

  @Override
  public NativeStackFrame[] stackFrames() {
    return stackFrames.clone();
  }
}
