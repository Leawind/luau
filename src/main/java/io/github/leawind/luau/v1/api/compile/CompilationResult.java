package io.github.leawind.luau.v1.api.compile;

import io.github.leawind.luau.v1.api.CompiledChunk;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Result of compiling Luau source. */
public record CompilationResult(
    List<CompilationDiagnostic> diagnostics, Optional<CompiledChunk> chunk) {
  public CompilationResult {
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    chunk = Objects.requireNonNull(chunk, "chunk");
  }

  public static CompilationResult success(CompiledChunk chunk) {
    return new CompilationResult(List.of(), Optional.of(chunk));
  }

  public static CompilationResult failure(List<CompilationDiagnostic> diagnostics) {
    if (diagnostics.isEmpty()) {
      throw new IllegalArgumentException("A failed compilation requires a diagnostic");
    }
    return new CompilationResult(diagnostics, Optional.empty());
  }

  public boolean successful() {
    return chunk.isPresent();
  }
}
