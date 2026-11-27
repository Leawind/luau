package io.github.leawind.luau.v1.api.value;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class LuauValueTest {
  @Test
  void bufferIsDefensivelyCopied() {
    byte[] input = {1, 2, 3};
    LuauBuffer buffer = new LuauBuffer(input);
    input[0] = 9;

    byte[] output = buffer.value();
    output[1] = 9;

    assertArrayEquals(new byte[] {1, 2, 3}, buffer.value());
  }

  @Test
  void containersAreImmutableCopies() {
    var list = new ArrayList<LuauValue>();
    list.add(LuauNil.INSTANCE);
    LuauArray array = new LuauArray(list);
    list.add(new LuauBoolean(true));

    var map = new HashMap<String, LuauValue>();
    map.put("array", array);
    LuauTable table = new LuauTable(map);
    map.clear();

    assertEquals(1, array.values().size());
    assertEquals(1, table.values().size());
    assertThrows(UnsupportedOperationException.class, () -> array.values().add(new LuauNumber(1)));
    assertThrows(
        UnsupportedOperationException.class, () -> table.values().put("x", LuauNil.INSTANCE));
  }

  @Test
  void listCopyRejectsNullValues() {
    assertThrows(
        NullPointerException.class,
        () -> new LuauArray(java.util.Arrays.asList(LuauNil.INSTANCE, null)));
  }

  @Test
  void stringsAndTableKeysRejectUnpairedSurrogates() {
    String malformed = "\ud800";

    assertThrows(IllegalArgumentException.class, () -> new LuauString(malformed));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LuauTable(java.util.Map.of(malformed, LuauNil.INSTANCE)));
  }
}
