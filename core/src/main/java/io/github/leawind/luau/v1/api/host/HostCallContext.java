package io.github.leawind.luau.v1.api.host;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java-only capabilities and accounting for one host call context.
 *
 * <p>Capabilities are looked up by their explicitly registered API type and are never encoded as
 * Luau values.
 *
 * <p>A context belongs to one invocation and is passed unchanged to every host function that
 * invocation calls, including calls it makes after a debugger pause. The registered capabilities
 * are fixed at build time; the output byte count may be updated from any thread.
 */
public final class HostCallContext {
  private final Map<Class<?>, Object> capabilities;
  private final AtomicLong outputBytes = new AtomicLong();

  private HostCallContext(Map<Class<?>, Object> capabilities) {
    this.capabilities = Map.copyOf(capabilities);
  }

  /**
   * Creates a context without capabilities.
   *
   * @return a context that holds no capability
   */
  public static HostCallContext empty() {
    return new HostCallContext(Map.of());
  }

  /**
   * Creates a builder for explicitly typed Java-only capabilities.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Finds a capability registered under exactly {@code type}.
   *
   * <p>A capability registered under a supertype is not returned.
   *
   * @param <T> the capability type
   * @param type the registered type
   * @return the capability, or empty when none is registered under that type
   * @throws NullPointerException if {@code type} is null
   */
  public <T> Optional<T> find(Class<T> type) {
    Objects.requireNonNull(type, "type");
    return Optional.ofNullable(type.cast(capabilities.get(type)));
  }

  /**
   * Returns a required capability or fails the host call.
   *
   * @param <T> the capability type
   * @param type the registered type
   * @return the capability registered under exactly {@code type}
   * @throws NoSuchElementException if no capability is registered under that type
   * @throws NullPointerException if {@code type} is null
   */
  public <T> T require(Class<T> type) {
    return find(type)
        .orElseThrow(
            () -> new NoSuchElementException("Missing host capability: " + type.getName()));
  }

  /**
   * Adds bytes intentionally emitted by host functions to this context's accounting.
   *
   * <p>The bytes recorded here are charged against the invocation's host-output budget together
   * with the payload the host function returns. This method is thread-safe.
   *
   * @param bytes the number of bytes to add, non-negative
   * @throws IllegalArgumentException if {@code bytes} is negative
   * @throws IllegalStateException if the total overflows
   */
  public void recordOutputBytes(long bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("Host output byte count must not be negative");
    }
    outputBytes.updateAndGet(current -> {
      try {
        return Math.addExact(current, bytes);
      } catch (ArithmeticException exception) {
        throw new IllegalStateException("Host output byte count overflow", exception);
      }
    });
  }

  /**
   * Returns all host output bytes registered on this context so far.
   *
   * @return the accumulated byte count
   */
  public long outputBytes() {
    return outputBytes.get();
  }

  /** Collects explicitly typed capabilities for one context. */
  public static final class Builder {
    private final Map<Class<?>, Object> capabilities = new LinkedHashMap<>();

    private Builder() {}

    /**
     * Registers a capability under its API type.
     *
     * @param <T> the capability type
     * @param type the type the capability is looked up under
     * @param capability the capability, which has to implement {@code type}
     * @return this builder
     * @throws IllegalArgumentException if {@code type} is already registered or {@code capability}
     *     does not implement it
     * @throws NullPointerException if an argument is null
     */
    public <T> Builder put(Class<T> type, T capability) {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(capability, "capability");
      if (!type.isInstance(capability)) {
        throw new IllegalArgumentException(
            "Capability does not implement registered type " + type.getName());
      }
      if (capabilities.putIfAbsent(type, capability) != null) {
        throw new IllegalArgumentException("Duplicate host capability type: " + type.getName());
      }
      return this;
    }

    /**
     * Creates the context.
     *
     * @return a context holding the registered capabilities
     */
    public HostCallContext build() {
      return new HostCallContext(capabilities);
    }
  }
}
