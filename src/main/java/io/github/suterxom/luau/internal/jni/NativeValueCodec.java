package io.github.suterxom.luau.internal.jni;

import io.github.suterxom.luau.v1.api.value.LuauArray;
import io.github.suterxom.luau.v1.api.value.LuauBoolean;
import io.github.suterxom.luau.v1.api.value.LuauBuffer;
import io.github.suterxom.luau.v1.api.value.LuauNil;
import io.github.suterxom.luau.v1.api.value.LuauNumber;
import io.github.suterxom.luau.v1.api.value.LuauString;
import io.github.suterxom.luau.v1.api.value.LuauTable;
import io.github.suterxom.luau.v1.api.value.LuauValue;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class NativeValueCodec {
  private static final int NIL = 0;
  private static final int BOOLEAN = 1;
  private static final int NUMBER = 2;
  private static final int STRING = 3;
  private static final int BUFFER = 4;
  private static final int ARRAY = 5;
  private static final int TABLE = 6;
  private static final int MAX_DEPTH = 32;
  private static final int MAX_NODES = 10_000;
  private static final int MAX_STRING_BYTES = 512 * 1024;
  private static final int MAX_BUFFER_BYTES = 1024 * 1024;
  private static final int MAX_TOTAL_BYTES = 1024 * 1024;
  
  private NativeValueCodec() {}
  
  public static List<LuauValue> fromNativeValues(NativeValue[] values) {
    Budget budget = new Budget();
    return Arrays.stream(values).map(value->fromNativeValue(value, budget, 0)).toList();
  }
  
  public static NativeValue[] toNativeValues(List<LuauValue> values) {
    Budget budget = new Budget();
    return values.stream().map(value->toNativeValue(value, budget, 0)).toArray(NativeValue[]::new);
  }
  
  private static LuauValue fromNativeValue(NativeValue value, Budget budget, int depth) {
    budget.visit(depth);
    return switch(value.kind()){
      case NIL -> LuauNil.INSTANCE;
      case BOOLEAN -> new LuauBoolean(value.booleanValue());
      case NUMBER -> new LuauNumber(value.numberValue());
      case STRING -> {
        byte[] bytes = Objects.requireNonNull(value.bytes(), "Native string bytes");
        budget.addStringBytes(bytes.length);
        yield new LuauString(decodeUtf8(bytes, "Native Luau string"));
      }
      case BUFFER -> {
        byte[] bytes = Objects.requireNonNull(value.bytes(), "Native buffer bytes");
        budget.addBufferBytes(bytes.length);
        yield new LuauBuffer(bytes);
      }
      case ARRAY -> new LuauArray(Arrays.stream(Objects.requireNonNull(value.elements(), "Native array elements"))
                                        .map(element->fromNativeValue(element, budget, depth + 1))
                                        .toList());
      case TABLE -> {
        byte[][] keysUtf8 = Objects.requireNonNull(value.keysUtf8(), "Native table UTF-8 keys");
        NativeValue[] elements = Objects.requireNonNull(value.elements(), "Native table elements");
        if(keysUtf8.length != elements.length){
          throw new IllegalArgumentException("Native table key/value length mismatch");
        }
        Map<String, LuauValue> table = new LinkedHashMap<>();
        for(int index = 0; index < keysUtf8.length; ++index){
          byte[] keyBytes = Objects.requireNonNull(keysUtf8[index], "Native table UTF-8 key");
          budget.addStringBytes(keyBytes.length);
          String key = decodeUtf8(keyBytes, "Native Luau table key");
          if(table.put(key, fromNativeValue(elements[index], budget, depth + 1)) != null){
            throw new IllegalArgumentException("Duplicate native table key: " + key);
          }
        }
        yield new LuauTable(table);
      }
      default -> throw new IllegalArgumentException("Unknown native Luau value kind: " + value.kind());
    };
  }
  
  private static NativeValue toNativeValue(LuauValue value, Budget budget, int depth) {
    Objects.requireNonNull(value, "value");
    budget.visit(depth);
    if(value instanceof LuauNil){
      return new NativeValue(NIL, false, 0, null, null, null);
    }
    if(value instanceof LuauBoolean booleanValue){
      return new NativeValue(BOOLEAN, booleanValue.value(), 0, null, null, null);
    }
    if(value instanceof LuauNumber numberValue){
      return new NativeValue(NUMBER, false, numberValue.value(), null, null, null);
    }
    if(value instanceof LuauString stringValue){
      byte[] bytes = stringValue.value().getBytes(StandardCharsets.UTF_8);
      budget.addStringBytes(bytes.length);
      return new NativeValue(STRING, false, 0, bytes, null, null);
    }
    if(value instanceof LuauBuffer bufferValue){
      byte[] bytes = bufferValue.value();
      budget.addBufferBytes(bytes.length);
      return new NativeValue(BUFFER, false, 0, bytes, null, null);
    }
    if(value instanceof LuauArray arrayValue){
      NativeValue[] elements = arrayValue.values()
                                         .stream()
                                         .map(element->toNativeValue(element, budget, depth + 1))
                                         .toArray(NativeValue[]::new);
      return new NativeValue(ARRAY, false, 0, null, elements, null);
    }
    if(value instanceof LuauTable tableValue){
      byte[][] keysUtf8 = new byte[tableValue.values().size()][];
      NativeValue[] elements = new NativeValue[keysUtf8.length];
      int index = 0;
      for(Map.Entry<String, LuauValue> entry: tableValue.values().entrySet()){
        keysUtf8[index] = entry.getKey().getBytes(StandardCharsets.UTF_8);
        budget.addStringBytes(keysUtf8[index].length);
        elements[index] = toNativeValue(entry.getValue(), budget, depth + 1);
        ++index;
      }
      return new NativeValue(TABLE, false, 0, null, elements, keysUtf8);
    }
    throw new IllegalArgumentException("Unsupported Luau value type: " + value.getClass());
  }
  
  private static String decodeUtf8(byte[] bytes, String name) {
    try{
      return StandardCharsets.UTF_8.newDecoder()
                                   .onMalformedInput(CodingErrorAction.REPORT)
                                   .onUnmappableCharacter(CodingErrorAction.REPORT)
                                   .decode(ByteBuffer.wrap(bytes))
                                   .toString();
    }catch(CharacterCodingException exception){
      throw new IllegalArgumentException(name + " contains invalid UTF-8", exception);
    }
  }
  
  private static final class Budget {
    private int nodes;
    private int stringBytes;
    private int bufferBytes;
    private int totalBytes;
    
    void visit(int depth) {
      if(depth > MAX_DEPTH){
        throw new IllegalArgumentException("Luau value exceeds maximum depth " + MAX_DEPTH);
      }
      if(++nodes > MAX_NODES){
        throw new IllegalArgumentException("Luau value exceeds maximum node count " + MAX_NODES);
      }
    }
    
    void addStringBytes(int count) {
      stringBytes = addBytes("string", stringBytes, count, MAX_STRING_BYTES);
    }
    
    void addBufferBytes(int count) {
      bufferBytes = addBytes("buffer", bufferBytes, count, MAX_BUFFER_BYTES);
    }
    
    private int addBytes(String kind, int current, int count, int maximum) {
      if(count < 0 || current > maximum - count){
        throw new IllegalArgumentException("Luau value exceeds maximum " + kind + " byte count " + maximum);
      }
      if(totalBytes > MAX_TOTAL_BYTES - count){
        throw new IllegalArgumentException("Luau value exceeds maximum total byte count " + MAX_TOTAL_BYTES);
      }
      totalBytes += count;
      return current + count;
    }
  }
}
