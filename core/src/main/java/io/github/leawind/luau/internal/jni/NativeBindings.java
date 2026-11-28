package io.github.leawind.luau.internal.jni;

import io.github.leawind.luau.internal.loader.NativeLibraryLoader;
import io.github.leawind.luau.v1.api.host.HostCallContext;

public final class NativeBindings {
  public static final int EXPECTED_ABI_VERSION = 1;

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

  public static native NativeCompilationDiagnostic[] typecheckModules(
      byte[] rootModuleNameUtf8, byte[][] moduleNamesUtf8, byte[][] sourcesUtf8);

  public static native NativeExecutionResult execute(
      byte[] rootModuleNameUtf8,
      byte[][] moduleNamesUtf8,
      byte[][] bytecodes,
      NativeHostRegistry hostRegistry,
      HostCallContext hostContext,
      long maxRuntimeNanos,
      long maxInterruptChecks,
      long maxHostCalls,
      long maxHostOutputBytes,
      long maxMemoryBytes);

  public static native long createInstance(
      byte[] rootModuleNameUtf8,
      byte[][] moduleNamesUtf8,
      byte[][] bytecodes,
      NativeHostRegistry hostRegistry,
      long maxMemoryBytes);

  public static native NativeExecutionResult executeInstance(
      long handle,
      HostCallContext hostContext,
      long maxRuntimeNanos,
      long maxInterruptChecks,
      long maxHostCalls,
      long maxHostOutputBytes);

  public static native NativeExecutionResult initializeInstance(
      long handle,
      HostCallContext hostContext,
      long maxRuntimeNanos,
      long maxInterruptChecks,
      long maxHostCalls,
      long maxHostOutputBytes);

  public static native NativeExecutionResult invokeExport(
      long handle,
      byte[] exportNameUtf8,
      NativeValue[] arguments,
      HostCallContext hostContext,
      long maxRuntimeNanos,
      long maxInterruptChecks,
      long maxHostCalls,
      long maxHostOutputBytes);

  public static native int setBreakpoint(
      long handle, byte[] moduleNameUtf8, int line, boolean enabled);

  public static native void requestPauseInstance(long handle);

  public static native void requestTerminateInstance(long handle);

  public static native NativeExecutionResult resumeInstance(
      long handle, HostCallContext hostContext, int stepMode);

  public static native NativeExecutionResult terminatePausedInstance(long handle);

  public static native void closeInstance(long handle);
}
