package io.github.leawind.luau.v1.api.value;

import java.util.Objects;

final class LuauUtf8 {
  private LuauUtf8() {}

  static String requireWellFormed(String value, String name) {
    Objects.requireNonNull(value, name);
    for (int index = 0; index < value.length(); ++index) {
      char character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException(name + " contains an unpaired UTF-16 surrogate");
        }
        ++index;
      } else if (Character.isLowSurrogate(character)) {
        throw new IllegalArgumentException(name + " contains an unpaired UTF-16 surrogate");
      }
    }
    return value;
  }
}
