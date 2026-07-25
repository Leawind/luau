package io.github.suterxom.luau.v1.api.host;

import java.util.Map;

/** Creates one independent export set for each script instance. */
@FunctionalInterface
public interface HostModuleFactory {
  Map<String, HostFunction> createExports();
}
