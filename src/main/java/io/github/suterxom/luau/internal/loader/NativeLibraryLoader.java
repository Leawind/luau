package io.github.suterxom.luau.internal.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

public final class NativeLibraryLoader {
  public static final String OVERRIDE_PATH_PROPERTY = "io.github.suterxom.luau.native.path";
  private static final String MANIFEST_RESOURCE = "META-INF/luau/natives/manifest.properties";
  private static boolean loaded;

  private NativeLibraryLoader() {}

  public static synchronized void load() {
    if (loaded) {
      return;
    }

    String overridePath = System.getProperty(OVERRIDE_PATH_PROPERTY);
    if (overridePath != null && !overridePath.isBlank()) {
      System.load(Path.of(overridePath).toAbsolutePath().normalize().toString());
      loaded = true;
      return;
    }

    NativePlatform platform;
    try {
      platform = NativePlatform.current();
    } catch (UnsupportedPlatformException detectionFailure) {
      throw unsupported(detectionFailure.getMessage(), readManifestOrNull());
    }
    Properties manifest = readManifest();
    String platforms = requiredProperty(manifest, "platforms");
    if (!("," + platforms + ",").contains("," + platform.id() + ",")) {
      throw unsupported(
          "This JAR does not contain a native library for " + platform.id(), manifest);
    }

    String fileName = requiredProperty(manifest, platform.id() + ".filename");
    long expectedSize = requiredLongProperty(manifest, platform.id() + ".size");
    String expectedSha256 = requiredProperty(manifest, platform.id() + ".sha256");
    if (!fileName.equals(platform.libraryFileName())) {
      throw new LinkageError(
          "Native manifest file name mismatch for "
              + platform.id()
              + ": expected "
              + platform.libraryFileName()
              + ", found "
              + fileName);
    }

    String resourceName = "META-INF/luau/natives/" + platform.id() + "/" + fileName;
    byte[] libraryBytes = readResource(resourceName);
    if (libraryBytes.length != expectedSize) {
      throw new LinkageError(
          "Embedded Luau native library size mismatch: expected "
              + expectedSize
              + ", found "
              + libraryBytes.length);
    }
    String actualSha256 = sha256(libraryBytes);
    if (!MessageDigest.isEqual(
        expectedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        actualSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
      throw new LinkageError("Embedded Luau native library failed SHA-256 verification");
    }

    String runtimeVersion =
        requiredProperty(manifest, "runtimeVersion").replaceAll("[^A-Za-z0-9._-]", "_");
    Path extractionDirectory =
        Path.of(
            System.getProperty("java.io.tmpdir"), "suterxom-luau", runtimeVersion, actualSha256);
    Path extractedLibrary = extractionDirectory.resolve(fileName);
    Path lockFile = extractionDirectory.resolve(fileName + ".lock");

    try {
      Files.createDirectories(extractionDirectory);
      try (FileChannel channel =
          FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
        FileLock lock = channel.lock();
        try {
          if (!isExpectedFile(extractedLibrary, expectedSha256)) {
            Path temporaryFile =
                extractionDirectory.resolve(fileName + ".tmp-" + ProcessHandle.current().pid());
            Files.write(temporaryFile, libraryBytes);
            try {
              Files.move(
                  temporaryFile,
                  extractedLibrary,
                  StandardCopyOption.ATOMIC_MOVE,
                  StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignoredAtomicMove) {
              Files.move(temporaryFile, extractedLibrary, StandardCopyOption.REPLACE_EXISTING);
            } finally {
              Files.deleteIfExists(temporaryFile);
            }
          }
        } finally {
          lock.release();
        }
      }
    } catch (IOException exception) {
      throw new LinkageError("Failed to extract the embedded Luau native library", exception);
    }

    System.load(extractedLibrary.toAbsolutePath().toString());
    loaded = true;
  }

  /**
   * Names the platforms this build actually contains, taken from its own manifest.
   *
   * <p>The list is not a literal, so a build that ships a different set of platforms describes
   * itself correctly instead of advertising whatever the source happened to list.
   */
  static UnsupportedPlatformException unsupported(String reason, Properties manifest) {
    String platforms = manifest == null ? null : manifest.getProperty("platforms");
    if (platforms == null || platforms.isBlank()) {
      return new UnsupportedPlatformException(reason);
    }
    return new UnsupportedPlatformException(
        reason + ". Supported platforms: " + String.join(", ", platforms.split(",")));
  }

  private static Properties readManifestOrNull() {
    try (InputStream input =
        NativeLibraryLoader.class.getClassLoader().getResourceAsStream(MANIFEST_RESOURCE)) {
      if (input == null) {
        return null;
      }
      Properties properties = new Properties();
      properties.load(input);
      return properties;
    } catch (IOException exception) {
      throw new LinkageError("Failed to read native manifest", exception);
    }
  }

  private static Properties readManifest() {
    Properties properties = readManifestOrNull();
    if (properties == null) {
      throw new LinkageError("Missing native manifest: " + MANIFEST_RESOURCE);
    }
    return properties;
  }

  private static byte[] readResource(String resourceName) {
    try (InputStream input =
        NativeLibraryLoader.class.getClassLoader().getResourceAsStream(resourceName)) {
      if (input == null) {
        throw new LinkageError("Missing embedded native library: " + resourceName);
      }
      return input.readAllBytes();
    } catch (IOException exception) {
      throw new LinkageError("Failed to read embedded native library: " + resourceName, exception);
    }
  }

  private static String requiredProperty(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new LinkageError("Native manifest is missing property: " + name);
    }
    return value.trim();
  }

  private static long requiredLongProperty(Properties properties, String name) {
    String value = requiredProperty(properties, name);
    try {
      long parsed = Long.parseLong(value);
      if (parsed < 0) {
        throw new NumberFormatException("negative value");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new LinkageError("Native manifest property is not a valid size: " + name, exception);
    }
  }

  private static boolean isExpectedFile(Path path, String expectedSha256) throws IOException {
    return Files.isRegularFile(path) && sha256(Files.readAllBytes(path)).equals(expectedSha256);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 is required by the Java platform", exception);
    }
  }
}
