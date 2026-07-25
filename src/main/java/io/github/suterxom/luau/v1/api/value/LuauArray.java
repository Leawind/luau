package io.github.suterxom.luau.v1.api.value;

import java.util.List;
import java.util.Objects;

/** An immutable sequence of safe Luau values. */
public record LuauArray(List<LuauValue> values) implements LuauValue {
  public LuauArray {
    values = List.copyOf(Objects.requireNonNull(values, "values"));
  }
}
