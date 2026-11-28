package io.github.leawind.luau.v1.api.value;

/**
 * A UTF-8 Luau string accepted by the safe Java API.
 *
 * <p>The text has to be well-formed UTF-16 so that it can be encoded as UTF-8 without loss; a null
 * value or text containing an unpaired surrogate is rejected.
 *
 * @param value the string contents, in well-formed UTF-16
 */
public record LuauString(String value) implements LuauValue {
  /** Rejects a null value and text that is not well-formed UTF-16. */
  public LuauString {
    value = LuauUtf8.requireWellFormed(value, "value");
  }
}
