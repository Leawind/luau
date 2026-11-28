package io.github.leawind.luau.v1.api;

import io.github.leawind.luau.internal.jni.NativeBindings;
import io.github.leawind.luau.internal.jni.NativeLuauException;
import io.github.leawind.luau.internal.jni.NativeResultDecoder;
import io.github.leawind.luau.internal.loader.NativePlatform;
import io.github.leawind.luau.internal.validation.ModuleNames;
import io.github.leawind.luau.v1.api.compile.CompilationDiagnostic;
import io.github.leawind.luau.v1.api.compile.CompilationResult;
import io.github.leawind.luau.v1.api.host.HostModule;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Entry point for compiling and executing sandboxed Luau code.
 *
 * <p>The runtime holds no state: compilation is a pure function of its sources, and every {@link
 * ScriptInstance} owns its own VM. There is nothing to configure, so there is no instance to
 * obtain.
 *
 * <p>The native library is loaded and verified on first use. A platform this build does not bundle,
 * and a library that fails size or digest verification, fail the call with an unchecked exception
 * instead of a result.
 */
public final class LuauRuntime {
  private static final int MAX_MODULES = 64;
  private static final int MAX_SOURCE_BYTES = 1024 * 1024;
  private static final int MAX_TOTAL_SOURCE_BYTES = 4 * 1024 * 1024;

  private LuauRuntime() {}

  /**
   * Returns version information, loading and validating the native library first.
   *
   * @return the library version, the Luau version, the native ABI version and the detected platform
   *     identifier
   */
  public static RuntimeInfo info() {
    return new RuntimeInfo(
        NativeBindings.runtimeVersion(),
        NativeBindings.luauVersion(),
        NativeBindings.luauCommit(),
        NativeBindings.abiVersion(),
        NativePlatform.current().id());
  }

  /**
   * Compiles one source text under the default chunk name.
   *
   * @param source the Luau source text
   * @return the compilation result
   */
  public static CompilationResult compile(String source) {
    return compile("main", source);
  }

  /**
   * Compiles one in-memory Luau chunk.
   *
   * <p>Syntax, type and compile errors are returned as diagnostics rather than thrown.
   *
   * @param chunkName the logical module name of the chunk, which is also the name its export table
   *     and breakpoints use
   * @param source the Luau source text, at most 1 MiB once encoded as UTF-8
   * @return the compilation result
   * @throws IllegalArgumentException if the chunk name is not a valid logical module name, or the
   *     source is not well-formed UTF-16 or exceeds the size limit
   * @throws NullPointerException if an argument is null
   */
  public static CompilationResult compile(String chunkName, String source) {
    ModuleNames.requireValid(chunkName);
    Objects.requireNonNull(source, "source");
    return compile(chunkName, Map.of(chunkName, source));
  }

  /**
   * Compiles a closed set of in-memory source modules without host modules.
   *
   * @param rootModuleName the module the program starts from
   * @param sources the source text of every module, keyed by its logical name
   * @return the compilation result
   * @throws IllegalArgumentException if a name is not a valid logical module name, the source set
   *     is empty, more than 64 modules are compiled, a single source exceeds 1 MiB, the sources
   *     together exceed 4 MiB, or the root module is not a key of {@code sources}
   * @throws NullPointerException if an argument, a key or a value is null
   */
  public static CompilationResult compile(String rootModuleName, Map<String, String> sources) {
    return compile(rootModuleName, sources, List.of());
  }

  /**
   * Compiles source modules together with an explicit set of typed host modules.
   *
   * <p>{@code require("name")} performs an exact lookup among these modules; a logical name never
   * resolves through the filesystem, a search path or the globals. A host module contributes its
   * declaration to strict type checking, and its factory is kept for later per-instance export
   * creation instead of being invoked here.
   *
   * @param rootModuleName the module the program starts from
   * @param sources the source text of every source module, keyed by its logical name
   * @param hostModules the host modules the program may require
   * @return the compilation result
   * @throws IllegalArgumentException if a name is not a valid logical module name, the source set
   *     is empty, source and host modules together exceed 64, a single source or host declaration
   *     exceeds 1 MiB, the sources together exceed 4 MiB, a host module name is duplicated, a host
   *     and a source module share a name, or the root module is not a key of {@code sources}
   * @throws NullPointerException if an argument, a key, a value or a list element is null
   */
  public static CompilationResult compile(
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
      var nativeDiagnostics =
          NativeBindings.typecheckModules(rootNameUtf8, typecheckNamesUtf8, typecheckSourcesUtf8);
      if (nativeDiagnostics.length > 0) {
        return CompilationResult.failure(NativeResultDecoder.decodeDiagnostics(nativeDiagnostics));
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
}
