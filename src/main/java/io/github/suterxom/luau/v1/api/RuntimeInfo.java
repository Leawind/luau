package io.github.suterxom.luau.v1.api;

/**
 * Immutable version information for the loaded Java and native runtime.
 * @param runtimeVersion Luau Runtime Mod version
 * @param luauVersion    pinned upstream Luau version
 * @param luauCommit     pinned upstream Luau commit
 * @param jniAbiVersion  Java/native ABI version
 * @param nativePlatform normalized native platform identifier
 */
public record RuntimeInfo(String runtimeVersion, String luauVersion, String luauCommit, int jniAbiVersion,
                          String nativePlatform) {}
