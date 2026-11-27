package io.github.leawind.luau.v1.api.value;

import java.util.Map;
import java.util.Objects;

/** An immutable string-keyed table of safe Luau values. */
public record LuauTable(Map<String, LuauValue> values) implements LuauValue {
  public LuauTable {
    values = Objects.requireNonNull(values, "values");
    values.keySet().forEach(key -> LuauUtf8.requireWellFormed(key, "table key"));
    values = Map.copyOf(values);
  }
}
