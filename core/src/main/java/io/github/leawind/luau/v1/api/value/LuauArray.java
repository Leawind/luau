package io.github.leawind.luau.v1.api.value;

import java.util.List;
import java.util.Objects;

/**
 * An immutable sequence of safe Luau values.
 *
 * <p>The order of the elements is preserved across the boundary. The sequence and its elements must
 * not be null.
 *
 * @param values the elements, in order
 */
public record LuauArray(List<LuauValue> values) implements LuauValue {
  /** Rejects a null sequence and null elements. */
  public LuauArray {
    values = List.copyOf(Objects.requireNonNull(values, "values"));
  }
}
