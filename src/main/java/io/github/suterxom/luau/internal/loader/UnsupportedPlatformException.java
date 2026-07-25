package io.github.suterxom.luau.internal.loader;

public final class UnsupportedPlatformException extends RuntimeException {
  public UnsupportedPlatformException(String message) {
    super(message + ". Supported platforms: windows-x86_64, linux-x86_64, linux-aarch64, " + "macos-x86_64, macos-aarch64");
  }
}
