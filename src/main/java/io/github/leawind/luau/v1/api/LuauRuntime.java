package io.github.leawind.luau.v1.api;

import io.github.leawind.luau.internal.jni.NativeBindings;
import io.github.leawind.luau.internal.jni.NativeCompilationDiagnostic;
import io.github.leawind.luau.internal.jni.NativeLuauException;
import io.github.leawind.luau.internal.loader.NativePlatform;
import io.github.leawind.luau.internal.validation.ModuleNames;
import io.github.leawind.luau.v1.api.compile.CompilationDiagnostic;
import io.github.leawind.luau.v1.api.compile.CompilationResult;
import io.github.leawind.luau.v1.api.compile.SourceRange;
import io.github.leawind.luau.v1.api.host.HostModule;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Entry point for compiling and executing sandboxed Luau code. */
public final class LuauRuntime {
  private static final int MAX_MODULES = 64;
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
    ModuleNames.requireValid(chunkName);
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
    return compile(rootModuleName, sources, List.of());
  }

  /**
   * Compiles source modules with an explicit set of typed host modules.
   *
   * <p>A host module declaration participates in strict type checking under its exact logical
   * module name. Its factory is retained for later per-instance export creation and is not invoked
   * during compilation.
   */
  public CompilationResult compile(
      String rootModuleName, Map<String, String> sources, List<HostModule> hostModules) {
    ModuleNames.requireValid(rootModuleName);
    Objects.requireNonNull(sources, "sources");
    Objects.requireNonNull(hostModules, "hostModules");
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("At least one source module is required");
    }
    if (sources.size() + hostModules.size() > MAX_MODULES) {
      throw new IllegalArgumentException(
          "Program exceeds the " + MAX_MODULES + " total module limit");
    }

    Map<String, String> sortedSources = new TreeMap<>();
    int totalSourceBytes = 0;
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      String moduleName = Objects.requireNonNull(entry.getKey(), "module name");
      String source = Objects.requireNonNull(entry.getValue(), "source");
      ModuleNames.requireValid(moduleName);
      ModuleNames.requireWellFormedUnicode(source, "source for module " + moduleName);

      byte[] sourceUtf8 = source.getBytes(StandardCharsets.UTF_8);
      if (sourceUtf8.length > MAX_SOURCE_BYTES) {
        throw new IllegalArgumentException(
            "Source module " + moduleName + " exceeds the " + MAX_SOURCE_BYTES + " byte limit");
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

    Map<String, HostModule> sortedHostModules = new TreeMap<>();
    for (HostModule module : hostModules) {
      Objects.requireNonNull(module, "hostModule");
      if (sortedSources.containsKey(module.name())) {
        throw new IllegalArgumentException(
            "Source and host modules use the same name: " + module.name());
      }
      if (sortedHostModules.putIfAbsent(module.name(), module) != null) {
        throw new IllegalArgumentException("Duplicate host module: " + module.name());
      }

      byte[] declarationUtf8 = module.typeDeclaration().getBytes(StandardCharsets.UTF_8);
      if (declarationUtf8.length > MAX_SOURCE_BYTES) {
        throw new IllegalArgumentException(
            "Host module declaration "
                + module.name()
                + " exceeds the "
                + MAX_SOURCE_BYTES
                + " byte limit");
      }
      if (totalSourceBytes > MAX_TOTAL_SOURCE_BYTES - declarationUtf8.length) {
        throw new IllegalArgumentException(
            "Program exceeds the " + MAX_TOTAL_SOURCE_BYTES + " total source byte limit");
      }
      totalSourceBytes += declarationUtf8.length;
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

    Map<String, String> typecheckSources = new TreeMap<>(sortedSources);
    sortedHostModules.forEach(
        (name, module) -> typecheckSources.put(name, module.typeDeclaration()));
    byte[][] typecheckNamesUtf8 = new byte[typecheckSources.size()][];
    byte[][] typecheckSourcesUtf8 = new byte[typecheckSources.size()][];
    index = 0;
    for (Map.Entry<String, String> entry : typecheckSources.entrySet()) {
      typecheckNamesUtf8[index] = entry.getKey().getBytes(StandardCharsets.UTF_8);
      typecheckSourcesUtf8[index] = entry.getValue().getBytes(StandardCharsets.UTF_8);
      ++index;
    }

    try {
      NativeCompilationDiagnostic[] nativeDiagnostics =
          NativeBindings.typecheckModules(rootNameUtf8, typecheckNamesUtf8, typecheckSourcesUtf8);
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
              List.of(CompilationDiagnostic.compileError(moduleName, exception.getMessage())));
        }
      }
      return CompilationResult.success(
          new CompiledChunk(
              rootModuleName,
              moduleNamesUtf8,
              bytecodes,
              new ArrayList<>(sortedHostModules.values())));
    } catch (NativeLuauException exception) {
      return CompilationResult.failure(
          List.of(CompilationDiagnostic.compileError(rootModuleName, exception.getMessage())));
    }
  }

  private static CompilationDiagnostic decodeDiagnostic(NativeCompilationDiagnostic diagnostic) {
    String moduleName = new String(diagnostic.moduleNameUtf8(), StandardCharsets.UTF_8);
    String message = new String(diagnostic.messageUtf8(), StandardCharsets.UTF_8);
    SourceRange sourceRange =
        new SourceRange(
            diagnostic.startLine(),
            diagnostic.startColumn(),
            diagnostic.endLine(),
            diagnostic.endColumn());
    return switch (diagnostic.stage()) {
      case 0 -> CompilationDiagnostic.parseError(moduleName, message, sourceRange);
      case 1 -> CompilationDiagnostic.typeError(moduleName, message, sourceRange);
      default ->
          throw new LinkageError(
              "Unknown native compilation diagnostic stage: " + diagnostic.stage());
    };
  }
}
