package io.github.leawind.luau.internal.jni;

public record NativeExecutionResult(
    int status,
    NativeValue[] values,
    byte[] messageUtf8,
    long elapsedNanos,
    long interruptChecks,
    long currentMemoryBytes,
    long peakMemoryBytes,
    long hostCalls,
    long hostOutputBytes,
    NativePauseInfo pauseInfo) {
  public NativeExecutionResult {
    values = values == null ? new NativeValue[0] : values.clone();
    messageUtf8 = messageUtf8 == null ? null : messageUtf8.clone();
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
