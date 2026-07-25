package io.github.suterxom.luau.internal.jni;

public record NativeValue(int kind, boolean booleanValue, double numberValue, byte[] bytes) {
  public NativeValue {
    bytes = bytes == null ? null : bytes.clone();
  }

  @Override
  public byte[] bytes() {
    return bytes == null ? null : bytes.clone();
  }
}
