package io.github.leawind.luau.v1.api.host;

import java.util.Map;

/**
 * Creates one independent export set for each script instance.
 *
 * <p>A factory is invoked whenever a VM is created, that is for every {@link
 * io.github.leawind.luau.v1.api.CompiledChunk#createInstance()}, every {@link
 * io.github.leawind.luau.v1.api.CompiledChunk#execute()} that runs the program on its own, and
 * again when {@link io.github.leawind.luau.v1.api.ScriptInstance#reset()} replaces a VM.
 */
@FunctionalInterface
public interface HostModuleFactory {
  /**
   * Creates the export table of one script instance.
   *
   * @return the functions exported under this module's name, keyed by export name; the map is
   *     validated when the VM is created, so a null map, a null function, a duplicate name or a
   *     name that is blank, longer than 256 UTF-8 bytes or contains NUL fails that creation
   */
  Map<String, HostFunction> createExports();
}
