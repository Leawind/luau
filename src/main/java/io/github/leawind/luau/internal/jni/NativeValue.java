package io.github.leawind.luau.internal.jni;

public record NativeValue(
    int kind,
    boolean booleanValue,
    double numberValue,
    byte[] bytes,
    NativeValue[] elements,
    byte[][] keysUtf8) {
  public NativeValue {
    bytes = bytes == null ? null : bytes.clone();
    elements = elements == null ? null : elements.clone();
    keysUtf8 = deepClone(keysUtf8);
  }

  @Override
  public byte[] bytes() {
    return bytes == null ? null : bytes.clone();
  }

  @Override
  public NativeValue[] elements() {
    return elements == null ? null : elements.clone();
  }

  @Override
  public byte[][] keysUtf8() {
    return deepClone(keysUtf8);
  }

  private static byte[][] deepClone(byte[][] values) {
    if (values == null) {
      return null;
    }
    byte[][] copy = values.clone();
    for (int index = 0; index < copy.length; ++index) {
      copy[index] = copy[index] == null ? null : copy[index].clone();
    }
    return copy;
  }
}
