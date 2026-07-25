package io.github.suterxom.luau.internal.jni;

import io.github.suterxom.luau.v1.api.value.LuauBoolean;
import io.github.suterxom.luau.v1.api.value.LuauNil;
import io.github.suterxom.luau.v1.api.value.LuauNumber;
import io.github.suterxom.luau.v1.api.value.LuauString;
import io.github.suterxom.luau.v1.api.value.LuauValue;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class NativeValueCodec {
  private static final int NIL = 0;
  private static final int BOOLEAN = 1;
  private static final int NUMBER = 2;
  private static final int STRING = 3;

  private NativeValueCodec() {}

  public static List<LuauValue> fromNativeValues(NativeValue[] values) {
    return Arrays.stream(values).map(NativeValueCodec::fromNativeValue).toList();
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
}
