package io.github.leawind.luau.v1.api.runtime;

/**
 * Configuration fixed for the lifetime of one isolated Luau VM.
 *
 * <p>The limit bounds the VM's own heap: an allocation that would exceed it fails the invocation
 * with {@link ExecutionResult.LimitExceeded.Kind#MEMORY}.
 *
 * @param maxMemoryBytes the hard heap limit in bytes, between 16 MiB and 1 GiB
 */
public record InstanceConfig(long maxMemoryBytes) {
  /** The default configuration: a 64 MiB VM heap limit. */
  public static final InstanceConfig DEFAULT = new InstanceConfig(64L * 1024 * 1024);

  private static final long MIN_MEMORY_BYTES = 16L * 1024 * 1024;
  private static final long MAX_MEMORY_BYTES = 1024L * 1024 * 1024;

  /** Rejects a heap limit outside the supported range. */
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
