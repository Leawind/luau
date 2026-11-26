package io.github.suterxom.luau.v1.api.value;

import java.util.Arrays;
import java.util.Objects;

/** An immutable byte buffer. */
public final class LuauBuffer implements LuauValue {
  private final byte[] value;

  public LuauBuffer(byte[] value) {
    this.value = Objects.requireNonNull(value, "value").clone();
  }

  public byte[] value() {
    return value.clone();
  }

  @Override
  public boolean equals(Object object) {
    return object instanceof LuauBuffer other && Arrays.equals(value, other.value);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }

  @Override
  public String toString() {
    return "LuauBuffer[length=" + value.length + "]";
  }
}
