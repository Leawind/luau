package io.github.suterxom.luau.internal.jni;

import io.github.suterxom.luau.internal.validation.ModuleNames;
import io.github.suterxom.luau.v1.api.host.HostCallContext;
import io.github.suterxom.luau.v1.api.host.HostCallException;
import io.github.suterxom.luau.v1.api.host.HostFunction;
import io.github.suterxom.luau.v1.api.host.HostModule;
import io.github.suterxom.luau.v1.api.value.LuauValue;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Per-instance Java callback registry used only by JNI. */
public final class NativeHostRegistry {
  private static final System.Logger LOGGER = System.getLogger(NativeHostRegistry.class.getName());
  private static final int COMPLETED = 0;
  private static final int DECLARED_FAILURE = 1;
  private static final int INTERNAL_FAILURE = 2;
  private static final int VALUE_LIMIT = 3;
  private static final int MAX_EXPORTS_PER_MODULE = 256;

  private final Map<String, Map<String, HostFunction>> modules;

  private NativeHostRegistry(Map<String, Map<String, HostFunction>> modules) {
    this.modules = Map.copyOf(modules);
  }

  public static NativeHostRegistry create(List<HostModule> hostModules) {
    Objects.requireNonNull(hostModules, "hostModules");
    Map<String, Map<String, HostFunction>> modules = new LinkedHashMap<>();
    for (HostModule module : hostModules) {
      Objects.requireNonNull(module, "hostModule");
      Map<String, HostFunction> created =
          Objects.requireNonNull(
              module.factory().createExports(),
              "Host module factory returned null: " + module.name());
      if (created.size() > MAX_EXPORTS_PER_MODULE) {
        throw new IllegalArgumentException(
            "Host module "
                + module.name()
                + " exceeds the "
                + MAX_EXPORTS_PER_MODULE
                + " export limit");
      }
      Map<String, HostFunction> exports = new LinkedHashMap<>();
      for (Map.Entry<String, HostFunction> entry : created.entrySet()) {
        String name = requireValidExportName(entry.getKey());
        HostFunction function = Objects.requireNonNull(entry.getValue(), "host function");
        if (exports.putIfAbsent(name, function) != null) {
          throw new IllegalArgumentException("Duplicate host export: " + name);
        }
      }
      if (modules.putIfAbsent(module.name(), Map.copyOf(exports)) != null) {
        throw new IllegalArgumentException("Duplicate host module: " + module.name());
      }
    }
    return new NativeHostRegistry(modules);
  }

  /** Called by JNI. Returns null when the module is not registered. */
  public byte[][] exports(byte[] moduleNameUtf8) {
    Map<String, HostFunction> exports = modules.get(decodeUtf8(moduleNameUtf8, "module name"));
    if (exports == null) {
      return null;
    }
    return exports.keySet().stream()
        .map(name -> name.getBytes(StandardCharsets.UTF_8))
        .toArray(byte[][]::new);
  }

  /** Called by JNI. All Java failures are converted before returning to native code. */
  public NativeHostCallResult invoke(
      byte[] moduleNameUtf8,
      byte[] exportNameUtf8,
      NativeValue[] arguments,
      HostCallContext context) {
    Objects.requireNonNull(context, "host call context");
    String moduleName;
    String exportName;
    List<LuauValue> decoded;
    try {
      moduleName = decodeUtf8(moduleNameUtf8, "module name");
      exportName = decodeUtf8(exportNameUtf8, "export name");
      decoded =
          NativeValueCodec.fromNativeValues(Objects.requireNonNull(arguments, "host arguments"));
    } catch (IllegalArgumentException exception) {
      LOGGER.log(System.Logger.Level.ERROR, "Host argument boundary rejected a value", exception);
      return failure(VALUE_LIMIT, "Host value boundary limit exceeded", 0);
    }

    Map<String, HostFunction> exports = modules.get(moduleName);
    HostFunction function = exports == null ? null : exports.get(exportName);
    if (function == null) {
      return failure(INTERNAL_FAILURE, "Host export is not registered", 0);
    }

    long outputBefore = context.outputBytes();
    List<LuauValue> returned;
    try {
      returned =
          List.copyOf(
              Objects.requireNonNull(
                  function.invoke(context, decoded), "Host function returned null"));
    } catch (HostCallException exception) {
      return failure(
          DECLARED_FAILURE,
          exception.getMessage() == null ? "Host call failed" : exception.getMessage(),
          outputDelta(context, outputBefore));
    } catch (Throwable throwable) {
      LOGGER.log(System.Logger.Level.ERROR, "Unexpected Java host function failure", throwable);
      return failure(
          INTERNAL_FAILURE, "Host function failed internally", outputDelta(context, outputBefore));
    }

    long outputBytes = outputDelta(context, outputBefore);
    try {
      return new NativeHostCallResult(
          COMPLETED, NativeValueCodec.toNativeValues(returned), null, outputBytes);
    } catch (IllegalArgumentException exception) {
      LOGGER.log(System.Logger.Level.ERROR, "Host return boundary rejected a value", exception);
      return failure(VALUE_LIMIT, "Host value boundary limit exceeded", outputBytes);
    }
  }

  private static NativeHostCallResult failure(int status, String message, long outputBytes) {
    return new NativeHostCallResult(
        status, null, message.getBytes(StandardCharsets.UTF_8), outputBytes);
  }

  private static long outputDelta(HostCallContext context, long before) {
    if (context == null) {
      return 0;
    }
    try {
      return Math.max(0, Math.subtractExact(context.outputBytes(), before));
    } catch (ArithmeticException exception) {
      return Long.MAX_VALUE;
    }
  }

  private static String requireValidExportName(String name) {
    Objects.requireNonNull(name, "host export name");
    ModuleNames.requireWellFormedUnicode(name, "host export name");
    int length = name.getBytes(StandardCharsets.UTF_8).length;
    if (name.isBlank() || length > ModuleNames.MAX_UTF8_BYTES || name.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(
          "Host export name must be non-blank, at most "
              + ModuleNames.MAX_UTF8_BYTES
              + " UTF-8 bytes, and contain no NUL");
    }
    return name;
  }

  private static String decodeUtf8(byte[] bytes, String name) {
    Objects.requireNonNull(bytes, name);
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException(name + " contains invalid UTF-8", exception);
    }
  }
}
