/**
 * Compilation of Luau source into executable programs.
 *
 * <p>A program is compiled as a closed world: the root module and every module it may require are
 * compiled together, so nothing is resolved from disk or from globals at run time. Host modules
 * take part in the same strict type check, and the bytecode that comes out is bound to the logical
 * module names it was compiled with.
 *
 * <p>Compilation either produces a program or reports diagnostics that carry the Luau message
 * together with the source span that produced it, so a host can surface errors without parsing
 * text.
 */
package io.github.leawind.luau.v1.api.compile;
