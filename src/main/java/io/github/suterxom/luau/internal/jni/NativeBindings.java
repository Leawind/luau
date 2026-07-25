package io.github.suterxom.luau.internal.jni;

import io.github.suterxom.luau.internal.loader.NativeLibraryLoader;

public final class NativeBindings {
  public static final int EXPECTED_ABI_VERSION = 4;

  static {
    NativeLibraryLoader.load();
    int actualAbiVersion = abiVersion();
    if (actualAbiVersion != EXPECTED_ABI_VERSION) {
      throw new LinkageError(
          "Luau JNI ABI mismatch: expected "
              + EXPECTED_ABI_VERSION
              + ", found "
              + actualAbiVersion);
    }
  }

  private NativeBindings() {}

  public static native int abiVersion();

  public static native String luauVersion();

  public static native String luauCommit();

  public static native String runtimeVersion();

  public static native byte[] compile(byte[] chunkNameUtf8, byte[] sourceUtf8);

  public static native byte[][] typecheck(byte[] chunkNameUtf8, byte[] sourceUtf8);

  public static native NativeExecutionResult execute(
      byte[] chunkNameUtf8,
      byte[] bytecode,
      long maxRuntimeNanos,
      long maxInterruptChecks,
      long maxMemoryBytes);

  public static native long createInstance(
      byte[] chunkNameUtf8, byte[] bytecode, long maxMemoryBytes);

  public static native NativeExecutionResult executeInstance(
      long handle, long maxRuntimeNanos, long maxInterruptChecks);

  public static native NativeExecutionResult initializeInstance(
      long handle, long maxRuntimeNanos, long maxInterruptChecks);

  public static native NativeExecutionResult invokeExport(
      long handle,
      byte[] exportNameUtf8,
      NativeValue[] arguments,
      long maxRuntimeNanos,
      long maxInterruptChecks);

  public static native void closeInstance(long handle);
}
