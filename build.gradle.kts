import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    `java-library`
}

group = providers.gradleProperty("mod.group").get()
version = providers.gradleProperty("mod.version").get()

val modId = providers.gradleProperty("mod.id").get()
val modName = providers.gradleProperty("mod.name").get()
val modAuthor = providers.gradleProperty("mod.author").get()
val modDescription = providers.gradleProperty("mod.description").get()
val modLicense = providers.gradleProperty("mod.license").get()
val minecraftVersion = providers.gradleProperty("mod.minecraftVersion").get()
val luauVersion = providers.gradleProperty("luau.version").get()
val luauCommit = providers.gradleProperty("luau.commit").get()
val jniAbiVersion = providers.gradleProperty("luau.jniAbiVersion").get()

base {
    archivesName = modId
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

fun normalizedOs(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("linux") -> "linux"
        osName.contains("windows") -> "windows"
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        else -> error("Unsupported build operating system: ${System.getProperty("os.name")}")
    }
}

fun normalizedArch(): String {
    return when (val arch = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> error("Unsupported build architecture: $arch")
    }
}

fun nativeLibraryName(os: String): String = when (os) {
    "windows" -> "luau_jni.dll"
    "macos" -> "libluau_jni.dylib"
    else -> "libluau_jni.so"
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun sha256Bytes(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

val hostOs = normalizedOs()
val hostArch = normalizedArch()
val hostPlatform = "$hostOs-$hostArch"
val hostNativeName = nativeLibraryName(hostOs)
val nativeBuildDirectory = layout.buildDirectory.dir("native/cmake")
val nativeOutputDirectory = nativeBuildDirectory.map { it.dir("out") }
val nativeLibrary = nativeOutputDirectory.map { it.file(hostNativeName) }

val configureNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Configures the native Luau JNI library for the current platform."

    inputs.file("native/CMakeLists.txt")
    inputs.dir("native/src")
    inputs.file("native/luau/CMakeLists.txt")
    inputs.property("runtimeVersion", version)
    inputs.property("luauVersion", luauVersion)
    inputs.property("luauCommit", luauCommit)
    inputs.property("jniAbiVersion", jniAbiVersion)
    outputs.file(nativeBuildDirectory.map { it.file("CMakeCache.txt") })

    doFirst {
        nativeBuildDirectory.get().asFile.mkdirs()
    }

    commandLine(
        "cmake",
        "-S", layout.projectDirectory.dir("native").asFile.absolutePath,
        "-B", nativeBuildDirectory.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DLUAU_BUILD_CLI=OFF",
        "-DLUAU_BUILD_TESTS=OFF",
        "-DLUAU_BUILD_WEB=OFF",
        "-DLUAU_RUNTIME_VERSION=$version",
        "-DLUAU_UPSTREAM_VERSION=$luauVersion",
        "-DLUAU_UPSTREAM_COMMIT=$luauCommit",
        "-DLUAU_JNI_ABI_VERSION=$jniAbiVersion",
    )
    environment("JAVA_HOME", System.getProperty("java.home"))
}

val buildNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds the native Luau JNI library for the current platform."
    dependsOn(configureNative)

    inputs.dir("native/src")
    inputs.dir("native/luau")
    outputs.file(nativeLibrary)

    commandLine(
        "cmake",
        "--build", nativeBuildDirectory.get().asFile.absolutePath,
        "--target", "luau_jni",
        "--config", "Release",
        "--parallel",
    )
}

val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")

val stageNativeResources by tasks.registering(Sync::class) {
    group = "native"
    description = "Stages the current platform native library and its integrity manifest."
    dependsOn(buildNative)

    into(generatedNativeResources)
    from(nativeLibrary) {
        into("META-INF/luau/natives/$hostPlatform")
    }

    doLast {
        val nativeFile = nativeLibrary.get().asFile
        check(nativeFile.isFile) { "Native library was not produced: $nativeFile" }

        val manifest = generatedNativeResources.get()
            .file("META-INF/luau/natives/manifest.properties")
            .asFile
        manifest.parentFile.mkdirs()
        manifest.writeText(
            """
            platforms=$hostPlatform
            $hostPlatform.filename=$hostNativeName
            $hostPlatform.size=${nativeFile.length()}
            $hostPlatform.sha256=${sha256(nativeFile)}
            jniAbiVersion=$jniAbiVersion
            luauVersion=$luauVersion
            luauCommit=$luauCommit
            runtimeVersion=$version
            """.trimIndent() + "\n",
        )
    }
}

tasks.processResources {
    dependsOn(stageNativeResources)
    from(generatedNativeResources)

    val properties = mapOf(
        "id" to modId,
        "name" to modName,
        "version" to version,
        "author" to modAuthor,
        "description" to modDescription,
        "license" to modLicense,
        "minecraftVersion" to minecraftVersion,
        "luauVersion" to luauVersion,
        "luauCommit" to luauCommit,
        "jniAbiVersion" to jniAbiVersion,
    )
    inputs.properties(properties)

    filesMatching(
        listOf(
            "fabric.mod.json",
            "META-INF/mods.toml",
            "META-INF/neoforge.mods.toml",
            "META-INF/luau/runtime.properties",
        ),
    ) {
        expand(properties)
    }

    from("LICENSE") {
        into("META-INF")
    }
    from("native/luau/LICENSE.txt") {
        into("META-INF/licenses/luau")
    }
    from("native/luau/lua_LICENSE.txt") {
        into("META-INF/licenses/luau")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to modName,
            "Implementation-Version" to version,
            "Luau-Version" to luauVersion,
            "Luau-Commit" to luauCommit,
            "Luau-JNI-ABI-Version" to jniAbiVersion,
        )
    }
}

val prebuiltNativesDirectory = providers.gradleProperty("prebuiltNativesDir")
    .map { layout.projectDirectory.dir(it) }
val universalNativeResources = layout.buildDirectory.dir("generated/universal-native-resources")

val stageUniversalNativeResources by tasks.registering {
    group = "native"
    description = "Stages all release native libraries supplied by CI."

    inputs.dir(prebuiltNativesDirectory)
    outputs.dir(universalNativeResources)

    doLast {
        val inputRoot = prebuiltNativesDirectory.orNull?.asFile
            ?: error("Set -PprebuiltNativesDir=<directory> to build the universal JAR")
        val expectedPlatforms = listOf(
            "windows-x86_64",
            "linux-x86_64",
            "linux-aarch64",
            "macos-x86_64",
            "macos-aarch64",
        )
        val outputRoot = universalNativeResources.get().asFile
        outputRoot.deleteRecursively()

        val manifestLines = mutableListOf("platforms=${expectedPlatforms.joinToString(",")}")
        for (platform in expectedPlatforms) {
            val platformDir = inputRoot.resolve(platform)
            val os = platform.substringBefore('-')
            val libraryName = nativeLibraryName(os)
            val source = platformDir.resolve(libraryName)
            check(source.isFile) { "Missing native library for $platform: $source" }

            val target = outputRoot.resolve("META-INF/luau/natives/$platform/$libraryName")
            target.parentFile.mkdirs()
            Files.copy(source.toPath(), target.toPath())

            manifestLines += "$platform.filename=$libraryName"
            manifestLines += "$platform.size=${source.length()}"
            manifestLines += "$platform.sha256=${sha256(source)}"
        }
        manifestLines += "jniAbiVersion=$jniAbiVersion"
        manifestLines += "luauVersion=$luauVersion"
        manifestLines += "luauCommit=$luauCommit"
        manifestLines += "runtimeVersion=$version"

        val manifest = outputRoot.resolve("META-INF/luau/natives/manifest.properties")
        manifest.parentFile.mkdirs()
        manifest.writeText(manifestLines.joinToString("\n", postfix = "\n"))
    }
}

val universalJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds a release JAR containing every supported native platform."
    dependsOn(tasks.classes, stageUniversalNativeResources)
    archiveClassifier = "universal"

    from(sourceSets.main.get().output) {
        exclude("META-INF/luau/natives/**")
    }
    from(universalNativeResources)

    manifest {
        attributes(
            "Implementation-Title" to modName,
            "Implementation-Version" to version,
            "Luau-Version" to luauVersion,
            "Luau-Commit" to luauCommit,
            "Luau-JNI-ABI-Version" to jniAbiVersion,
        )
    }
}

val verifyUniversalJar by tasks.registering {
    group = "verification"
    description = "Verifies that the universal JAR contains every native and matching metadata."
    dependsOn(universalJar)

    val archive = universalJar.flatMap { it.archiveFile }
    inputs.file(archive)

    doLast {
        val expectedPlatforms = listOf(
            "windows-x86_64",
            "linux-x86_64",
            "linux-aarch64",
            "macos-x86_64",
            "macos-aarch64",
        )

        ZipFile(archive.get().asFile).use { jar ->
            val manifestEntry = checkNotNull(
                jar.getEntry("META-INF/luau/natives/manifest.properties"),
            ) { "Universal JAR is missing the native manifest" }
            val nativeManifest = Properties().apply {
                jar.getInputStream(manifestEntry).use(::load)
            }

            check(nativeManifest.getProperty("platforms") == expectedPlatforms.joinToString(",")) {
                "Universal JAR native platform list does not match the release platform list"
            }
            check(nativeManifest.getProperty("jniAbiVersion") == jniAbiVersion)
            check(nativeManifest.getProperty("luauVersion") == luauVersion)
            check(nativeManifest.getProperty("luauCommit") == luauCommit)
            check(nativeManifest.getProperty("runtimeVersion") == version.toString())

            for (platform in expectedPlatforms) {
                val fileName = checkNotNull(nativeManifest.getProperty("$platform.filename"))
                val entry = checkNotNull(
                    jar.getEntry("META-INF/luau/natives/$platform/$fileName"),
                ) { "Universal JAR is missing its $platform native library" }
                check(nativeManifest.getProperty("$platform.size") == entry.size.toString()) {
                    "Native size mismatch for $platform"
                }
                val bytes = jar.getInputStream(entry).use { it.readAllBytes() }
                check(nativeManifest.getProperty("$platform.sha256") == sha256Bytes(bytes)) {
                    "Native SHA-256 mismatch for $platform"
                }
            }

            for (metadata in listOf(
                "fabric.mod.json",
                "META-INF/mods.toml",
                "META-INF/neoforge.mods.toml",
                "META-INF/LICENSE",
                "META-INF/licenses/luau/LICENSE.txt",
                "META-INF/licenses/luau/lua_LICENSE.txt",
            )) {
                check(jar.getEntry(metadata) != null) {
                    "Universal JAR is missing required entry: $metadata"
                }
            }
        }
    }
}
