package io.github.suterxom.luau.v1.api.host;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java-only capabilities and accounting for one logical host call context.
 *
 * <p>Capabilities are looked up by their explicitly registered API type and are never encoded as
 * Luau values.
 */
public final class HostCallContext {
  private final Map<Class<?>, Object> capabilities;
  private final AtomicLong outputBytes = new AtomicLong();

  private HostCallContext(Map<Class<?>, Object> capabilities) {
    this.capabilities = Map.copyOf(capabilities);
  }

  /** Creates a context without capabilities. */
  public static HostCallContext empty() {
    return new HostCallContext(Map.of());
  }

  /** Creates a builder for explicitly typed Java-only capabilities. */
  public static Builder builder() {
    return new Builder();
  }

  /** Finds a capability registered under exactly {@code type}. */
  public <T> Optional<T> find(Class<T> type) {
    Objects.requireNonNull(type, "type");
    return Optional.ofNullable(type.cast(capabilities.get(type)));
  }

  /** Returns a required capability or fails the host call. */
  public <T> T require(Class<T> type) {
    return find(type)
        .orElseThrow(
            () -> new NoSuchElementException("Missing host capability: " + type.getName()));
  }

  /** Adds bytes intentionally emitted by host functions to this context's accounting. */
  public void recordOutputBytes(long bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("Host output byte count must not be negative");
    }
    outputBytes.updateAndGet(
        current -> {
          try {
            return Math.addExact(current, bytes);
          } catch (ArithmeticException exception) {
            throw new IllegalStateException("Host output byte count overflow", exception);
          }
        });
  }

  /** Returns all host output bytes registered on this context so far. */
  public long outputBytes() {
    return outputBytes.get();
  }

  public static final class Builder {
    private final Map<Class<?>, Object> capabilities = new LinkedHashMap<>();

    private Builder() {}

    public <T> Builder put(Class<T> type, T capability) {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(capability, "capability");
      if (!type.isInstance(capability)) {
        throw new IllegalArgumentException(
            "Capability does not implement registered type " + type.getName());
      }
      if (capabilities.putIfAbsent(type, capability) != null) {
        throw new IllegalArgumentException(
            "Duplicate host capability type: " + type.getName());
      }
      return this;
    }

    public HostCallContext build() {
      return new HostCallContext(capabilities);
    }
  }
}
