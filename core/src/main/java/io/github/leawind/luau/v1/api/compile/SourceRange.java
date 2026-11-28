package io.github.leawind.luau.v1.api.compile;

/**
 * A span of source text inside a logical Luau module.
 *
 * <p>Lines and columns are one-based; {@code 0} means the coordinate is unknown. The end position
 * uses Luau's exclusive end offset, so it points just past the last character of the span. Negative
 * coordinates are rejected.
 *
 * @param startLine the one-based first line, or {@code 0} when unknown
 * @param startColumn the one-based first column, or {@code 0} when unknown
 * @param endLine the one-based last line, or {@code 0} when unknown
 * @param endColumn the one-based exclusive end column, or {@code 0} when unknown
 */
public record SourceRange(int startLine, int startColumn, int endLine, int endColumn) {
  /** A range whose position is unknown. */
  public static final SourceRange UNKNOWN = new SourceRange(0, 0, 0, 0);

  /** Rejects negative coordinates. */
  public SourceRange {
    if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
      throw new IllegalArgumentException("Source range coordinates must not be negative");
    }
  }
}
