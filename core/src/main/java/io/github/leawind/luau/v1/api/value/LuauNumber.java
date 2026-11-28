package io.github.leawind.luau.v1.api.value;

/**
 * A Luau number.
 *
 * <p>Luau has one numeric type, so every number is a double.
 *
 * @param value the numeric value
 */
public record LuauNumber(double value) implements LuauValue {}
