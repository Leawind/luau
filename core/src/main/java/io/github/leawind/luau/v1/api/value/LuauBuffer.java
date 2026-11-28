package io.github.leawind.luau.v1.api.value;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable byte buffer.
 *
 * <p>The contents are copied in and out, so later changes to a caller's array never reach the
 * buffer, and changes to a returned array never reach the buffer either.
 */
public final class LuauBuffer implements LuauValue {
  private final byte[] value;

  /**
   * Creates a buffer holding a copy of the given bytes.
   *
   * @param value the bytes to copy
   * @throws NullPointerException if {@code value} is null
   */
  public LuauBuffer(byte[] value) {
    this.value = Objects.requireNonNull(value, "value").clone();
  }

  /**
   * Returns a copy of the buffer contents.
   *
   * @return a fresh array holding the contents
   */
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
