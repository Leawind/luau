package io.github.suterxom.luau.internal.loader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NativePlatformTest {
  @Test
  void detectsASupportedPlatform() {
    NativePlatform platform = NativePlatform.current();
    
    assertTrue(java.util.Set.of("windows-x86_64", "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64")
                            .contains(platform.id()));
    assertTrue(platform.libraryFileName().contains("luau_jni"));
  }
}
