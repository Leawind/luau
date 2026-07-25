package io.github.suterxom.luau.v1.api.value;

import java.util.Map;
import java.util.Objects;

/** An immutable string-keyed table of safe Luau values. */
public record LuauTable(Map<String, LuauValue> values) implements LuauValue {
  public LuauTable {
    values = Map.copyOf(Objects.requireNonNull(values, "values"));
  }
}
