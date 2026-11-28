/**
 * Resource limits, configuration and outcomes of execution.
 *
 * <p>An invocation runs under a budget that bounds wall-clock time, interrupt checks, host calls
 * and host output, while the VM itself enforces a heap limit fixed at creation. Limits are enforced
 * rather than checked afterwards, and the limit that stopped execution is reported as itself
 * instead of as a generic failure.
 *
 * <p>Every outcome carries the resource usage that produced it, so a host can account for what a
 * sandbox consumed without instrumenting it.
 */
package io.github.leawind.luau.v1.api.runtime;
