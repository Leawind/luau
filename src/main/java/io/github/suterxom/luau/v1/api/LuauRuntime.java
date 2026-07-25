package io.github.suterxom.luau.v1.api;

import io.github.suterxom.luau.internal.jni.NativeBindings;
import io.github.suterxom.luau.internal.jni.NativeCompilationDiagnostic;
import io.github.suterxom.luau.internal.jni.NativeLuauException;
import io.github.suterxom.luau.internal.loader.NativePlatform;
import io.github.suterxom.luau.v1.api.compile.CompilationDiagnostic;
import io.github.suterxom.luau.v1.api.compile.CompilationResult;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Entry point for compiling and executing sandboxed Luau code. */
public final class LuauRuntime {
  private static final int MAX_MODULES = 64;
  private static final int MAX_MODULE_NAME_BYTES = 256;
  private static final int MAX_SOURCE_BYTES = 1024 * 1024;
  private static final int MAX_TOTAL_SOURCE_BYTES = 4 * 1024 * 1024;
  private static final LuauRuntime INSTANCE = new LuauRuntime();

  private LuauRuntime() {}

  /** Returns the process-wide runtime facade. */
  public static LuauRuntime getInstance() {
    return INSTANCE;
  }

  /** Returns version information after loading and validating the native library. */
  public RuntimeInfo info() {
    return new RuntimeInfo(
        NativeBindings.runtimeVersion(),
        NativeBindings.luauVersion(),
        NativeBindings.luauCommit(),
        NativeBindings.abiVersion(),
        NativePlatform.current().id());
  }

  /** Compiles source using the default chunk name. */
  public CompilationResult compile(String source) {
    return compile("main", source);
  }

  /**
   * Compiles a single in-memory Luau chunk.
   *
   * <p>Syntax and compile errors are returned as diagnostics. Invalid Java API arguments still
   * throw {@link IllegalArgumentException}.
   */
  public CompilationResult compile(String chunkName, String source) {
    validateModuleName(chunkName);
    Objects.requireNonNull(source, "source");
    return compile(chunkName, Map.of(chunkName, source));
  }

  /**
   * Compiles a closed set of in-memory source modules.
   *
   * <p>{@code require("name")} performs an exact lookup in {@code sources}; module names are
   * logical identifiers and never resolve through the filesystem or a search path.
   */
  public CompilationResult compile(String rootModuleName, Map<String, String> sources) {
    validateModuleName(rootModuleName);
    Objects.requireNonNull(sources, "sources");
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("At least one source module is required");
    }
    if (sources.size() > MAX_MODULES) {
      throw new IllegalArgumentException(
          "Program exceeds the " + MAX_MODULES + " source module limit");
    }

    Map<String, String> sortedSources = new TreeMap<>();
    int totalSourceBytes = 0;
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      String moduleName = Objects.requireNonNull(entry.getKey(), "module name");
      String source = Objects.requireNonNull(entry.getValue(), "source");
      validateModuleName(moduleName);
      requireWellFormedUnicode(source, "source for module " + moduleName);

      byte[] sourceUtf8 = source.getBytes(StandardCharsets.UTF_8);
      if (sourceUtf8.length > MAX_SOURCE_BYTES) {
        throw new IllegalArgumentException(
            "Source module "
                + moduleName
                + " exceeds the "
                + MAX_SOURCE_BYTES
                + " byte limit");
      }
      if (totalSourceBytes > MAX_TOTAL_SOURCE_BYTES - sourceUtf8.length) {
        throw new IllegalArgumentException(
            "Program exceeds the " + MAX_TOTAL_SOURCE_BYTES + " total source byte limit");
      }
      totalSourceBytes += sourceUtf8.length;
      sortedSources.put(moduleName, source);
    }
    if (!sortedSources.containsKey(rootModuleName)) {
      throw new IllegalArgumentException(
          "Root module is not present in the source set: " + rootModuleName);
    }

    byte[] rootNameUtf8 = rootModuleName.getBytes(StandardCharsets.UTF_8);
    byte[][] moduleNamesUtf8 = new byte[sortedSources.size()][];
    byte[][] sourcesUtf8 = new byte[sortedSources.size()][];
    int index = 0;
    for (Map.Entry<String, String> entry : sortedSources.entrySet()) {
      moduleNamesUtf8[index] = entry.getKey().getBytes(StandardCharsets.UTF_8);
      sourcesUtf8[index] = entry.getValue().getBytes(StandardCharsets.UTF_8);
      ++index;
    }

    try {
      NativeCompilationDiagnostic[] nativeDiagnostics =
          NativeBindings.typecheckModules(rootNameUtf8, moduleNamesUtf8, sourcesUtf8);
      if (nativeDiagnostics.length > 0) {
        return CompilationResult.failure(
            Arrays.stream(nativeDiagnostics).map(LuauRuntime::decodeDiagnostic).toList());
      }

      byte[][] bytecodes = new byte[sourcesUtf8.length][];
      for (index = 0; index < sourcesUtf8.length; ++index) {
        try {
          bytecodes[index] = NativeBindings.compile(moduleNamesUtf8[index], sourcesUtf8[index]);
        } catch (NativeLuauException exception) {
          String moduleName = new String(moduleNamesUtf8[index], StandardCharsets.UTF_8);
          return CompilationResult.failure(
              List.of(
                  CompilationDiagnostic.compileError(moduleName, exception.getMessage())));
        }
      }
      return CompilationResult.success(
          new CompiledChunk(this, rootModuleName, moduleNamesUtf8, bytecodes));
    } catch (NativeLuauException exception) {
      return CompilationResult.failure(
          List.of(
              CompilationDiagnostic.compileError(rootModuleName, exception.getMessage())));
    }
  }

  private static CompilationDiagnostic decodeDiagnostic(
      NativeCompilationDiagnostic diagnostic) {
    String moduleName =
        new String(diagnostic.moduleNameUtf8(), StandardCharsets.UTF_8);
    String message = new String(diagnostic.messageUtf8(), StandardCharsets.UTF_8);
    return switch (diagnostic.stage()) {
      case 0 -> CompilationDiagnostic.parseError(moduleName, message);
      case 1 -> CompilationDiagnostic.typeError(moduleName, message);
      default -> throw new LinkageError(
          "Unknown native compilation diagnostic stage: " + diagnostic.stage());
    };
  }

  private static void validateModuleName(String moduleName) {
    Objects.requireNonNull(moduleName, "moduleName");
    requireWellFormedUnicode(moduleName, "moduleName");
    byte[] nameUtf8 = moduleName.getBytes(StandardCharsets.UTF_8);
    if (moduleName.isBlank()) {
      throw new IllegalArgumentException("Module name must not be blank");
    }
    if (nameUtf8.length > MAX_MODULE_NAME_BYTES) {
      throw new IllegalArgumentException(
          "Module name exceeds " + MAX_MODULE_NAME_BYTES + " UTF-8 bytes");
    }
    if (moduleName.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Module name must not contain NUL");
    }
    if (moduleName.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("Module name must not contain backslashes");
    }
    for (int index = 0; index < moduleName.length(); ++index) {
      char character = moduleName.charAt(index);
      if (Character.isWhitespace(character) || Character.isISOControl(character)) {
        throw new IllegalArgumentException(
            "Module name must not contain whitespace or control characters");
      }
    }
    for (String segment : moduleName.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException(
            "Module name must use non-empty logical segments other than '.' or '..'");
      }
    }
  }

  private static void requireWellFormedUnicode(String value, String name) {
    for (int index = 0; index < value.length(); ++index) {
      char character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (index + 1 >= value.length()
            || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException(name + " contains an unpaired UTF-16 surrogate");
        }
        ++index;
      } else if (Character.isLowSurrogate(character)) {
        throw new IllegalArgumentException(name + " contains an unpaired UTF-16 surrogate");
      }
    }
  }
}
