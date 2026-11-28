package io.github.leawind.luau.v1.api.host;

/**
 * An expected host failure that is safe to report to Luau code.
 *
 * <p>Throwing one from a {@link HostFunction} raises a Luau error carrying the message, so the
 * script observes a normal script error and may catch it with {@code pcall}. Any other throwable is
 * reported as an internal host failure and keeps its details on the Java side.
 */
public final class HostCallException extends Exception {
  private static final long serialVersionUID = 1L;

  /**
   * Creates a host failure whose message is reported to Luau.
   *
   * @param message the message Luau code receives
   */
  public HostCallException(String message) {
    super(message);
  }

  /**
   * Creates a host failure whose message is reported to Luau while the cause stays on the Java
   * side.
   *
   * @param message the message Luau code receives
   * @param cause the Java-side cause, which is not exposed to Luau
   */
  public HostCallException(String message, Throwable cause) {
    super(message, cause);
  }
}
