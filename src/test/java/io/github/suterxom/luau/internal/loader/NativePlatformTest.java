package io.github.suterxom.luau.internal.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativePlatformTest {
  @Test
  void detectsASupportedPlatform() {
    NativePlatform platform = NativePlatform.current();

    assertTrue(
        java.util.Set.of(
                "windows-x86_64", "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64")
            .contains(platform.id()));
    assertTrue(platform.libraryFileName().contains("luau_jni"));
    String os = platform.id().substring(0, platform.id().indexOf('-'));
    assertEquals(
        switch (os) {
          case "windows" -> "luau_jni.dll";
          case "macos" -> "libluau_jni.dylib";
          default -> "libluau_jni.so";
        },
        platform.libraryFileName());
  }
}
