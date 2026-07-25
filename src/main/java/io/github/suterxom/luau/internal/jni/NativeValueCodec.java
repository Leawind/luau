package io.github.suterxom.luau.internal.jni;

import io.github.suterxom.luau.v1.api.value.LuauBoolean;
import io.github.suterxom.luau.v1.api.value.LuauNil;
import io.github.suterxom.luau.v1.api.value.LuauNumber;
import io.github.suterxom.luau.v1.api.value.LuauString;
import io.github.suterxom.luau.v1.api.value.LuauValue;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class NativeValueCodec {
  private static final int NIL = 0;
  private static final int BOOLEAN = 1;
  private static final int NUMBER = 2;
  private static final int STRING = 3;

  private NativeValueCodec() {}

  public static List<LuauValue> fromNativeValues(NativeValue[] values) {
    return Arrays.stream(values).map(NativeValueCodec::fromNativeValue).toList();
  }

  public static NativeValue[] toNativeValues(List<LuauValue> values) {
    return values.stream().map(NativeValueCodec::toNativeValue).toArray(NativeValue[]::new);
  }

  private static LuauValue fromNativeValue(NativeValue value) {
    return switch (value.kind()) {
      case NIL -> LuauNil.INSTANCE;
      case BOOLEAN -> new LuauBoolean(value.booleanValue());
      case NUMBER -> new LuauNumber(value.numberValue());
      case STRING -> new LuauString(new String(value.bytes(), StandardCharsets.UTF_8));
      default -> throw new IllegalArgumentException("Unknown native Luau value kind: " + value.kind());
    };
  }

  private static NativeValue toNativeValue(LuauValue value) {
    Objects.requireNonNull(value, "value");
    if (value instanceof LuauNil) {
      return new NativeValue(NIL, false, 0, null);
    }
    if (value instanceof LuauBoolean booleanValue) {
      return new NativeValue(BOOLEAN, booleanValue.value(), 0, null);
    }
    if (value instanceof LuauNumber numberValue) {
      return new NativeValue(NUMBER, false, numberValue.value(), null);
    }
    if (value instanceof LuauString stringValue) {
      return new NativeValue(
          STRING, false, 0, stringValue.value().getBytes(StandardCharsets.UTF_8));
    }
    throw new IllegalArgumentException(
        "This runtime phase does not yet support Java-to-Luau value type "
            + value.getClass().getSimpleName());
  }
}
