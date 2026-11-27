package io.github.suterxom.luau.internal.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class NativePlatformTest {
  private static final String PLATFORM_MANIFEST = "META-INF/luau/platforms.properties";

  @Test
  void detectsASupportedPlatform() throws IOException {
    List<String> releasePlatforms = releasePlatforms();
    NativePlatform platform = NativePlatform.current();

    assertTrue(
        releasePlatforms.contains(platform.id()),
        () -> platform.id() + " is missing from the release platforms " + releasePlatforms);
    String os = platform.id().substring(0, platform.id().indexOf('-'));
    assertEquals(
        switch (os) {
          case "windows" -> "luau_jni.dll";
          case "macos" -> "libluau_jni.dylib";
          default -> "libluau_jni.so";
        },
        platform.libraryFileName());
  }

  /** Android reports os.name as Linux, so it has to be recognised on its own. */
  @Test
  void rejectsAndroid() {
    String vendor = System.getProperty("java.vendor");
    try {
      System.setProperty("java.vendor", "The Android Project");

      UnsupportedPlatformException failure =
          assertThrows(UnsupportedPlatformException.class, NativePlatform::current);

      assertTrue(failure.getMessage().contains("Android"), failure::getMessage);
    } finally {
      if (vendor == null) {
        System.clearProperty("java.vendor");
      } else {
        System.setProperty("java.vendor", vendor);
      }
    }
  }

  /**
   * The unsupported-platform message tells users what does work, so it must follow the manifest.
   */
  @Test
  void namesOnlyThePlatformsTheBuildContains() {
    Properties manifest = new Properties();
    manifest.setProperty("platforms", "windows-x86_64,macos-aarch64");

    String message =
        NativeLibraryLoader.unsupported("This JAR does not contain a native library", manifest)
            .getMessage();

    assertTrue(
        message.contains("windows-x86_64"), () -> "missing a shipped platform in: " + message);
    assertTrue(
        message.contains("macos-aarch64"), () -> "missing a shipped platform in: " + message);
    assertFalse(
        message.contains("linux-aarch64"),
        () -> "named a platform the build does not contain: " + message);
  }

  /** A build without a readable manifest still reports the reason. */
  @Test
  void reportsTheReasonWithoutAPlatformListWhenTheManifestIsAbsent() {
    String message =
        NativeLibraryLoader.unsupported("Unsupported operating system: Plan 9", null).getMessage();

    assertEquals("Unsupported operating system: Plan 9", message);
  }

  /** Reads the release platform list that the build script generates. */
  private static List<String> releasePlatforms() throws IOException {
    Properties properties = new Properties();
    try (InputStream input =
        NativePlatformTest.class.getClassLoader().getResourceAsStream(PLATFORM_MANIFEST)) {
      assertNotNull(input, "Missing generated platform resource: " + PLATFORM_MANIFEST);
      properties.load(input);
    }
    String platforms = properties.getProperty("platforms");
    assertNotNull(platforms, "Missing platforms property in " + PLATFORM_MANIFEST);
    return List.of(platforms.split(","));
  }
}
