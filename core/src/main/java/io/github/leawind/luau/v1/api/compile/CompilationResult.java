package io.github.leawind.luau.v1.api.compile;

import io.github.leawind.luau.v1.api.CompiledChunk;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of compiling Luau source.
 *
 * <p>A successful result carries a chunk and no diagnostics; a failed result carries at least one
 * diagnostic and no chunk. The diagnostics may not contain null.
 *
 * @param diagnostics the diagnostics, empty on success
 * @param chunk the compiled program, empty on failure
 */
public record CompilationResult(
    List<CompilationDiagnostic> diagnostics, Optional<CompiledChunk> chunk) {
  /** Rejects null arguments and null diagnostics. */
  public CompilationResult {
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    chunk = Objects.requireNonNull(chunk, "chunk");
  }

  /**
   * Creates a successful result carrying a compiled chunk.
   *
   * @param chunk the compiled program
   * @return a result with no diagnostics
   * @throws NullPointerException if {@code chunk} is null
   */
  public static CompilationResult success(CompiledChunk chunk) {
    return new CompilationResult(List.of(), Optional.of(chunk));
  }

  /**
   * Creates a failed result.
   *
   * @param diagnostics the diagnostics that explain the failure, at least one
   * @return a result without a compiled chunk
   * @throws IllegalArgumentException if {@code diagnostics} is empty
   * @throws NullPointerException if {@code diagnostics} is null or contains null
   */
  public static CompilationResult failure(List<CompilationDiagnostic> diagnostics) {
    if (diagnostics.isEmpty()) {
      throw new IllegalArgumentException("A failed compilation requires a diagnostic");
    }
    return new CompilationResult(diagnostics, Optional.empty());
  }

  /**
   * Returns whether compilation produced a chunk.
   *
   * @return {@code true} when {@link #chunk()} is present
   */
  public boolean successful() {
    return chunk.isPresent();
  }
}
