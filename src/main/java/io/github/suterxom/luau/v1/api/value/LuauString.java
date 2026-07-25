package io.github.suterxom.luau.v1.api.value;

import java.util.Objects;

/** A UTF-8 Luau string accepted by the safe Java API. */
public record LuauString(String value) implements LuauValue {
  public LuauString {
    Objects.requireNonNull(value, "value");
  }
}
