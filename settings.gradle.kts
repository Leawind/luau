pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "luau"

// `core` is the library any JVM program can depend on. `mod` wraps it into the loader-visible
// artifact, so the two share one build and one tag.
include(":core")
include(":mod")
