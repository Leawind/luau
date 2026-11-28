/**
 * Owner-thread inspection of running programs.
 *
 * <p>A program can be stopped at a source line, stepped one line at a time, or paused on request.
 * Stopping reports where execution stands and the Luau call stack at that point, and a stopped
 * program keeps its coroutine and remaining budget until it is resumed or discarded, so inspection
 * never silently changes what the program does.
 *
 * <p>Sessions belong to the thread that owns the program; only pause and termination requests may
 * come from other threads.
 */
package io.github.leawind.luau.v1.api.debug;
