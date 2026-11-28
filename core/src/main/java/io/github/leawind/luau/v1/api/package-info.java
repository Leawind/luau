/**
 * A sandboxed Luau runtime for Java hosts.
 *
 * <p>Programs are compiled ahead of execution, run in isolated VMs whose globals belong to one
 * owner thread, and are observed through a debugger that stops and resumes them. Values crossing
 * the boundary are confined to a small closed set, Java code is reachable only through modules
 * granted at compilation time, and every invocation runs under explicit resource limits.
 *
 * <p>The subpackages own one concern each: the value layer, host modules, compilation, debugging
 * and the runtime's limits and results.
 *
 * <p>This package is the stable surface of the library. Everything under {@code
 * io.github.leawind.luau.internal} is an implementation detail, and the {@code v1} segment marks
 * the API revision.
 */
package io.github.leawind.luau.v1.api;
