package io.github.leawind.luau.v1.api.host;

import io.github.leawind.luau.internal.validation.ModuleNames;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A typed Java host module available only to programs that explicitly compile with it.
 *
 * <p>{@code typeDeclaration} is a strict Luau module body describing the value returned by {@code
 * require(name)}. It is type-checked but never executed.
 *
 * @param name the logical module name: non-blank, at most 256 UTF-8 bytes, with no backslash, NUL,
 *     whitespace or control character and no empty, {@code .} or {@code ..} segment
 * @param interfaceVersion a version label for the module interface: non-blank, at most 256 UTF-8
 *     bytes and free of NUL
 * @param typeDeclaration the Luau type declaration of the export table; non-blank
 * @param factory creates the export table for each script instance; must not be null
 */
public record HostModule(
    String name, String interfaceVersion, String typeDeclaration, HostModuleFactory factory) {
  /**
   * Rejects a name, version or declaration that breaks the documented rules, and a null factory.
   */
  public HostModule {
    name = ModuleNames.requireValid(name);
    interfaceVersion = ModuleNames.requireWellFormedUnicode(interfaceVersion, "interfaceVersion");
    if (interfaceVersion.isBlank()
        || interfaceVersion.indexOf('\0') >= 0
        || interfaceVersion.getBytes(StandardCharsets.UTF_8).length > ModuleNames.MAX_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "Host module interface version must be non-blank, at most "
              + ModuleNames.MAX_UTF8_BYTES
              + " UTF-8 bytes, and contain no NUL");
    }
    typeDeclaration = ModuleNames.requireWellFormedUnicode(typeDeclaration, "typeDeclaration");
    if (typeDeclaration.isBlank()) {
      throw new IllegalArgumentException("Host module type declaration must not be blank");
    }
    factory = Objects.requireNonNull(factory, "factory");
  }
}
