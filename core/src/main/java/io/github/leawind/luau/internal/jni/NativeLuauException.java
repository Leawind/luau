package io.github.leawind.luau.internal.jni;

public final class NativeLuauException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public NativeLuauException(String message) {
    super(message);
  }
}
