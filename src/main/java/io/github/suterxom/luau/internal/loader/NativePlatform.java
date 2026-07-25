package io.github.suterxom.luau.internal.loader;

import java.util.Locale;

public record NativePlatform(String id, String libraryFileName) {
  public static NativePlatform current() {
    String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String os = osName.contains("linux")
                ? "linux"
                : osName.contains("windows") ? "windows" : osName.contains("mac") || osName.contains("darwin") ? "macos" : null;
    if(os == null){
      throw new UnsupportedPlatformException("Unsupported operating system: " + System.getProperty("os.name"));
    }
    
    String rawArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
    String arch = switch(rawArch){
      case "amd64", "x86_64" -> "x86_64";
      case "aarch64", "arm64" -> "aarch64";
      default -> null;
    };
    if(arch == null){
      throw new UnsupportedPlatformException("Unsupported architecture: " + System.getProperty("os.arch"));
    }
    if(os.equals("windows") && arch.equals("aarch64")){
      throw new UnsupportedPlatformException("Windows ARM64 is not currently supported");
    }
    
    String libraryFileName = switch(os){
      case "windows" -> "luau_jni.dll";
      case "macos" -> "libluau_jni.dylib";
      default -> "libluau_jni.so";
    };
    return new NativePlatform(os + "-" + arch, libraryFileName);
  }
}
