/**
 * Java capabilities exposed to sandboxed code.
 *
 * <p>Nothing from the JVM is reachable unless it is granted: a host module is named at compilation
 * time, contributes its type declaration to the check, and supplies the functions that the program
 * may call at run time. Each VM receives its own export set, so state cannot leak between VMs
 * through a module.
 *
 * <p>Calls into Java carry values that follow the boundary's transfer rules, and per-invocation
 * state travels beside them instead of through Luau values. A host function may report an expected
 * failure to the script, and any other throwable stays on the Java side.
 */
package io.github.leawind.luau.v1.api.host;
