package io.github.suterxom.luau.v1.api;

import io.github.suterxom.luau.internal.jni.NativeBindings;
import io.github.suterxom.luau.internal.jni.NativeLuauException;
import io.github.suterxom.luau.internal.loader.NativePlatform;
import io.github.suterxom.luau.v1.api.compile.CompilationDiagnostic;
import io.github.suterxom.luau.v1.api.compile.CompilationResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Entry point for compiling and executing sandboxed Luau code. */
public final class LuauRuntime {
  private static final int MAX_SOURCE_BYTES = 1024 * 1024;
  private static final int MAX_CHUNK_NAME_LENGTH = 256;
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
    validateChunkName(chunkName);
    Objects.requireNonNull(source, "source");

    byte[] sourceUtf8 = source.getBytes(StandardCharsets.UTF_8);
    if (sourceUtf8.length > MAX_SOURCE_BYTES) {
      throw new IllegalArgumentException(
          "Source exceeds the " + MAX_SOURCE_BYTES + " byte compilation limit");
    }

    try {
      byte[] chunkNameUtf8 = chunkName.getBytes(StandardCharsets.UTF_8);
      byte[] bytecode = NativeBindings.compile(chunkNameUtf8, sourceUtf8);
      byte[][] typeErrors = NativeBindings.typecheck(chunkNameUtf8, sourceUtf8);
      if (typeErrors.length > 0) {
        return CompilationResult.failure(
            java.util.Arrays.stream(typeErrors)
                .map(message -> new String(message, StandardCharsets.UTF_8))
                .map(message -> CompilationDiagnostic.typeError(chunkName, message))
                .toList());
      }
      return CompilationResult.success(new CompiledChunk(this, chunkName, bytecode));
    } catch (NativeLuauException exception) {
      return CompilationResult.failure(
          List.of(CompilationDiagnostic.compileError(chunkName, exception.getMessage())));
    }
  }

  private static void validateChunkName(String chunkName) {
    Objects.requireNonNull(chunkName, "chunkName");
    if (chunkName.isBlank()) {
      throw new IllegalArgumentException("Chunk name must not be blank");
    }
    if (chunkName.length() > MAX_CHUNK_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "Chunk name exceeds " + MAX_CHUNK_NAME_LENGTH + " characters");
    }
    if (chunkName.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Chunk name must not contain NUL");
    }
  }
}
