package io.github.leawind.luau.v1.api.value;

/**
 * A value that is safe to transfer across the Java/Luau boundary.
 *
 * <p>Only these types cross the boundary: {@code nil}, booleans, numbers, strings, buffers, ordered
 * sequences and string-keyed tables. Two values are equal exactly when their contents are equal.
 *
 * <p>A transfer is bounded: at most 32 levels of nesting, 10,000 nodes, 512 KiB per string, 1 MiB
 * per buffer and 1 MiB in total. A value that breaks those bounds cannot cross the boundary, and
 * the call that carries it fails.
 *
 * <p>All implementations are immutable and safe to share between threads.
 */
public sealed interface LuauValue
    permits LuauArray, LuauBoolean, LuauBuffer, LuauNil, LuauNumber, LuauString, LuauTable {}
