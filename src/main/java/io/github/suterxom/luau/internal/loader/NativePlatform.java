package io.github.suterxom.luau.internal.loader;

import java.util.Locale;

public record NativePlatform(String id, String libraryFileName) {
  private static final String LINUX = "linux";
  private static final String WINDOWS = "windows";
  private static final String MACOS = "macos";
  private static final String X86_64 = "x86_64";
  private static final String AARCH64 = "aarch64";

  public static NativePlatform current() {
    String os = detectOperatingSystem();
    String architecture = detectArchitecture();
    if (WINDOWS.equals(os) && AARCH64.equals(architecture)) {
      throw new UnsupportedPlatformException("Windows ARM64 is not currently supported");
    }
    return new NativePlatform(os + "-" + architecture, libraryFileName(os));
  }

  private static String detectOperatingSystem() {
    if (isAndroid()) {
      // Android reports os.name as Linux, so it would otherwise be taken for a glibc Linux host and
      // fail later with an unsatisfiable link error.
      throw new UnsupportedPlatformException("Android is not supported");
    }
    String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    if (osName.contains(LINUX)) {
      return LINUX;
    }
    if (osName.contains(WINDOWS)) {
      return WINDOWS;
    }
    if (osName.contains("mac") || osName.contains("darwin")) {
      return MACOS;
    }
    throw new UnsupportedPlatformException(
        "Unsupported operating system: " + System.getProperty("os.name"));
  }

  private static boolean isAndroid() {
    String vmName = System.getProperty("java.vm.name", "");
    String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
    return vmName.equalsIgnoreCase("Dalvik") || vendor.contains("android");
  }

  private static String detectArchitecture() {
    String rawArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
    switch (rawArch) {
      case "amd64", "x86_64":
        return X86_64;
      case "aarch64", "arm64":
        return AARCH64;
      default:
        throw new UnsupportedPlatformException(
            "Unsupported architecture: " + System.getProperty("os.arch"));
    }
  }

  private static String libraryFileName(String os) {
    return switch (os) {
      case WINDOWS -> "luau_jni.dll";
      case MACOS -> "libluau_jni.dylib";
      default -> "libluau_jni.so";
    };
  }
}
