package io.github.leawind.luau.internal.loader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * What the packaging recorded about this build, read from {@code META-INF/luau/runtime.properties}.
 *
 * <p>The project version travels with the Java artifact instead of being compiled into the native
 * library, so nothing has to be rebuilt when it changes. The upstream Luau version, its commit and
 * the JNI ABI version stay in the library, where they describe the binary that was actually built.
 */
public final class RuntimeProperties {
  private static final String RESOURCE = "META-INF/luau/runtime.properties";
  private static Properties properties;

  private RuntimeProperties() {}

  /**
   * Returns one value of the packaged properties, loading them on first use.
   *
   * @param name the property to read
   * @return the value, trimmed
   * @throws IllegalStateException if the resource or the property is absent
   */
  public static synchronized String get(String name) {
    Properties loaded = properties;
    if (loaded == null) {
      loaded = read();
      properties = loaded;
    }
    String value = loaded.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          RESOURCE
              + " does not declare "
              + name
              + ". The build writes that resource into the JAR, so a JAR without the value was "
              + "assembled incorrectly.");
    }
    return value.trim();
  }

  private static Properties read() {
    try (InputStream input =
        RuntimeProperties.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException(
            "Missing build metadata: "
                + RESOURCE
                + ". The build writes it into the JAR, so a JAR without it was assembled "
                + "incorrectly.");
      }
      Properties read = new Properties();
      read.load(input);
      return read;
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to read build metadata: " + RESOURCE, exception);
    }
  }
}
