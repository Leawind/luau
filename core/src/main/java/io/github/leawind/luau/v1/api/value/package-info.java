/**
 * The values that cross the Java/Luau boundary.
 *
 * <p>The set is closed on purpose: nil, booleans, numbers, strings, byte buffers, ordered sequences
 * and string-keyed tables. Every representation is immutable, compares by content, and is bounded
 * in size, string length and nesting depth, so a host can accept data from a sandbox without
 * validating its shape first.
 *
 * <p>Only data is representable. Functions, coroutines and tables with other key types stay inside
 * the sandbox and have to be converted there before they are returned.
 */
package io.github.leawind.luau.v1.api.value;
