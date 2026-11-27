package io.github.leawind.luau.v1.api.value;

/** A UTF-8 Luau string accepted by the safe Java API. */
public record LuauString(String value) implements LuauValue {
  public LuauString {
    value = LuauUtf8.requireWellFormed(value, "value");
  }
}
