package io.github.suterxom.luau.v1.api.host;

/** An expected host failure that is safe to report to Luau code. */
public final class HostCallException extends Exception {
  public HostCallException(String message) {
    super(message);
  }
  
  public HostCallException(String message, Throwable cause) {
    super(message, cause);
  }
}
