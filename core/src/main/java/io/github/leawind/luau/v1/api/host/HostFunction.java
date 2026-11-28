package io.github.leawind.luau.v1.api.host;

import io.github.leawind.luau.v1.api.value.LuauValue;
import java.util.List;

/**
 * A Java function exposed through a registered host module.
 *
 * <p>Host functions run on the owner thread of the script instance that called them, inside the
 * invocation that called them, and share its budget: every call counts against the host-call limit,
 * and every byte counted through {@link HostCallContext#recordOutputBytes(long)} against the
 * host-output limit.
 */
@FunctionalInterface
public interface HostFunction {
  /**
   * Invokes the function with safe decoded arguments.
   *
   * @param context the capabilities and output accounting of the current invocation
   * @param arguments the arguments the script passed, in order; empty when there were none
   * @return all Luau return values; an empty list returns nothing
   * @throws HostCallException to raise a Luau error carrying the exception message
   */
  List<LuauValue> invoke(HostCallContext context, List<LuauValue> arguments)
      throws HostCallException;
}
