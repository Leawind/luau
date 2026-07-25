package io.github.suterxom.luau.internal.validation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Shared validation for exact logical module identifiers. */
public final class ModuleNames {
  public static final int MAX_UTF8_BYTES = 256;
  
  private ModuleNames() {}
  
  public static String requireValid(String moduleName) {
    Objects.requireNonNull(moduleName, "moduleName");
    requireWellFormedUnicode(moduleName, "moduleName");
    byte[] nameUtf8 = moduleName.getBytes(StandardCharsets.UTF_8);
    if(moduleName.isBlank()){
      throw new IllegalArgumentException("Module name must not be blank");
    }
    if(nameUtf8.length > MAX_UTF8_BYTES){
      throw new IllegalArgumentException("Module name exceeds " + MAX_UTF8_BYTES + " UTF-8 bytes");
    }
    if(moduleName.indexOf('\0') >= 0){
      throw new IllegalArgumentException("Module name must not contain NUL");
    }
    if(moduleName.indexOf('\\') >= 0){
      throw new IllegalArgumentException("Module name must not contain backslashes");
    }
    for(int index = 0; index < moduleName.length(); ++index){
      char character = moduleName.charAt(index);
      if(Character.isWhitespace(character) || Character.isISOControl(character)){
        throw new IllegalArgumentException("Module name must not contain whitespace or control characters");
      }
    }
    for(String segment: moduleName.split("/", -1)){
      if(segment.isEmpty() || segment.equals(".") || segment.equals("..")){
        throw new IllegalArgumentException("Module name must use non-empty logical segments other than '.' or '..'");
      }
    }
    return moduleName;
  }
  
  public static String requireWellFormedUnicode(String value, String name) {
    Objects.requireNonNull(value, name);
    for(int index = 0; index < value.length(); ++index){
      char character = value.charAt(index);
      if(Character.isHighSurrogate(character)){
        if(index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))){
          throw new IllegalArgumentException(name + " contains an unpaired UTF-16 surrogate");
        }
        ++index;
      }else if(Character.isLowSurrogate(character)){
        throw new IllegalArgumentException(name + " contains an unpaired UTF-16 surrogate");
      }
    }
    return value;
  }
}
