package io.github.suterxom.luau.internal.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
