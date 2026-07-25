package io.github.suterxom.luau.v1.api.host;

import io.github.suterxom.luau.v1.api.value.LuauValue;

import java.util.List;

/** A Java function exposed through a registered host module. */
@FunctionalInterface
public interface HostFunction {
  /**
   * Invokes the function with safe decoded arguments.
   * @return all Luau return values
   */
  List<LuauValue> invoke(HostCallContext context, List<LuauValue> arguments) throws HostCallException;
}
