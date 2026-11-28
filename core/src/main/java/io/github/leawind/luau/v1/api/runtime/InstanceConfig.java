package io.github.leawind.luau.v1.api.runtime;

/**
 * Configuration fixed for the lifetime of one isolated Luau VM.
 *
 * <p>The limit bounds the VM's own heap: an allocation that would exceed it fails the invocation
 * with {@link ExecutionResult.LimitExceeded.Kind#MEMORY}.
 *
 * @param maxMemoryBytes the hard heap limit in bytes, between {@link #MIN_MEMORY_BYTES} and {@link
 *     #MAX_MEMORY_BYTES}
 */
public record InstanceConfig(long maxMemoryBytes) {
  /** The smallest heap limit a VM accepts: 16 MiB. */
  public static final long MIN_MEMORY_BYTES = 16L * 1024 * 1024;

  /** The largest heap limit a VM accepts: 1 GiB. */
  public static final long MAX_MEMORY_BYTES = 1024L * 1024 * 1024;

  /** The default configuration: a 64 MiB VM heap limit. */
  public static final InstanceConfig DEFAULT = new InstanceConfig(64L * 1024 * 1024);

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
