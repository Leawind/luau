package io.github.suterxom.luau.internal.jni;

public record NativeHostCallResult(int status, NativeValue[] values, byte[] messageUtf8, long outputBytes) {
  public NativeHostCallResult {
    values = values == null ? new NativeValue[0] : values.clone();
    messageUtf8 = messageUtf8 == null ? null : messageUtf8.clone();
    if(outputBytes < 0){
      throw new IllegalArgumentException("Host output byte count must not be negative");
    }
  }
  
  @Override
  public NativeValue[] values() {
    return values.clone();
  }
  
  @Override
  public byte[] messageUtf8() {
    return messageUtf8 == null ? null : messageUtf8.clone();
  }
}
