package io.github.leawind.luau.v1.api.runtime;

/** Configuration fixed for the lifetime of one isolated Luau VM. */
public record InstanceConfig(long maxMemoryBytes) {
  public static final InstanceConfig DEFAULT = new InstanceConfig(64L * 1024 * 1024);

  private static final long MIN_MEMORY_BYTES = 16L * 1024 * 1024;
  private static final long MAX_MEMORY_BYTES = 1024L * 1024 * 1024;

  public InstanceConfig {
    if (maxMemoryBytes < MIN_MEMORY_BYTES || maxMemoryBytes > MAX_MEMORY_BYTES) {
      throw new IllegalArgumentException(
          "VM memory limit must be between "
              + MIN_MEMORY_BYTES
              + " and "
              + MAX_MEMORY_BYTES
              + " bytes");
    }
  }
}
