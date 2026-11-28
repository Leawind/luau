package io.github.leawind.luau.v1.api.value;

import java.util.Map;
import java.util.Objects;

/**
 * An immutable string-keyed table of safe Luau values.
 *
 * <p>Only string keys are representable across the boundary; a table that uses other key types has
 * to be converted by the script before it is returned. Keys and values must not be null, and keys
 * have to be well-formed UTF-16.
 *
 * @param values the entries, keyed by their Luau string key
 */
public record LuauTable(Map<String, LuauValue> values) implements LuauValue {
  /** Rejects a null map, null keys or values, and keys that are not well-formed UTF-16. */
  public LuauTable {
    values = Objects.requireNonNull(values, "values");
    values.keySet().forEach(key -> LuauUtf8.requireWellFormed(key, "table key"));
    values = Map.copyOf(values);
  }
}
