package io.github.leawind.luau.internal.jni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.leawind.luau.v1.api.value.LuauArray;
import io.github.leawind.luau.v1.api.value.LuauBoolean;
import io.github.leawind.luau.v1.api.value.LuauString;
import io.github.leawind.luau.v1.api.value.LuauTable;
import io.github.leawind.luau.v1.api.value.LuauValue;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeValueCodecTest {
  @Test
  void preservesUtf8TableKeysWithoutSharingInternalArrays() {
    LuauValue original =
        new LuauTable(
            Map.of("emoji😀\0", new LuauArray(List.of(new LuauString(""), new LuauBoolean(true)))));

    NativeValue[] encoded = NativeValueCodec.toNativeValues(List.of(original));
    byte[][] leakedKeys = encoded[0].keysUtf8();
    leakedKeys[0][0] ^= 1;

    assertEquals(List.of(original), NativeValueCodec.fromNativeValues(encoded));
  }

  @Test
  void rejectsDeepAndOversizedValueGraphs() {
    LuauValue deep = new LuauTable(Map.of());
    for (int depth = 0; depth < 34; ++depth) {
      deep = new LuauTable(Map.of("child", deep));
    }
    LuauValue tooDeep = deep;

    assertThrows(
        IllegalArgumentException.class, () -> NativeValueCodec.toNativeValues(List.of(tooDeep)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NativeValueCodec.toNativeValues(
                List.of(new LuauArray(Collections.nCopies(10_001, new LuauBoolean(true))))));
  }

  @Test
  void rejectsMalformedUtf8FromTheNativeBoundary() {
    NativeValue malformed = new NativeValue(3, false, 0, new byte[] {(byte) 0xff}, null, null);

    assertThrows(
        IllegalArgumentException.class,
        () -> NativeValueCodec.fromNativeValues(new NativeValue[] {malformed}));
  }
}
