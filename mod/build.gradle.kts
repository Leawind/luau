import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.file.DuplicatesStrategy

plugins {
    `java-library`
}

group = providers.gradleProperty("mod.group").get()
version = providers.gradleProperty("mod.version").get()

val modId = providers.gradleProperty("mod.id").get()
val modName = providers.gradleProperty("mod.name").get()
val modAuthor = providers.gradleProperty("mod.author").get()
val modDescription = providers.gradleProperty("mod.description").get()
val modLogo = providers.gradleProperty("mod.logo").get()
val modLicense = providers.gradleProperty("mod.license").get()
val modHomepage = providers.gradleProperty("mod.homepage").get()
val modIssues = providers.gradleProperty("mod.issues").get()
val modSources = providers.gradleProperty("mod.sources").get()
val minecraftVersion = providers.gradleProperty("mod.minecraftVersion").get()
val packFormat = providers.gradleProperty("mod.packFormat").get()
val luauVersion = providers.gradleProperty("luau.version").get()
val luauCommit = providers.gradleProperty("luau.commit").get()
val jniAbiVersion = providers.gradleProperty("luau.jniAbiVersion").get()
val glibcFloor = providers.gradleProperty("luau.glibcFloor").get()

/** Platforms a release ships, read from `luau.releasePlatforms` so both modules share one list. */
val releasePlatforms = providers.gradleProperty("luau.releasePlatforms").get()
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
require(releasePlatforms.isNotEmpty()) {
    "luau.releasePlatforms in gradle.properties is empty, so the universal JAR would carry no native."
}

base {
    archivesName = modId
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // The mod artifact carries the core library inside it, so the loader sees one JAR.
    implementation(project(":core"))
}

fun nativeLibraryName(os: String): String = when (os) {
    "windows" -> "luau_jni.dll"
    "macos" -> "libluau_jni.dylib"
    else -> "libluau_jni.so"
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256(file: File): String = sha256(file.readBytes())

tasks.processResources {
    val properties = mapOf(
        "id" to modId,
        "name" to modName,
        "version" to version,
        "author" to modAuthor,
        "description" to modDescription,
        "logo" to modLogo,
        "license" to modLicense,
        "homepage" to modHomepage,
        "issues" to modIssues,
        "sources" to modSources,
        "minecraftVersion" to minecraftVersion,
        "packFormat" to packFormat,
    )
    inputs.properties(properties)

    filesMatching(
        listOf(
            "fabric.mod.json",
            "META-INF/mods.toml",
            "META-INF/neoforge.mods.toml",
            "pack.mcmeta",
        ),
    ) {
        expand(properties)
    }
}

/** Splits a TOML table header into its dotted key path, honouring quoted keys as single segments. */
fun tomlKeyPath(header: String): List<String> {
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    for (character in header) {
        when {
            character == '"' -> quoted = !quoted
            character == '.' && !quoted -> {
                segments += current.toString().trim()
                current.clear()
            }
            else -> current.append(character)
        }
    }
    segments += current.toString().trim()
    return segments
}

val validateModMetadata by tasks.registering {
    group = "verification"
    description = "Verifies that the packaged loader metadata declares the mod id and the Minecraft dependency."

    val resourcesDirectory = tasks.processResources.map { it.destinationDir }
    inputs.dir(resourcesDirectory)

    doLast {
        val root = resourcesDirectory.get()
        val tableHeaders = Regex("""^\[\[(.+)]]$""", RegexOption.MULTILINE)
        for (metadataFile in listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
            val text = root.resolve(metadataFile).readText()
            val keyPaths = tableHeaders.findAll(text).map { tomlKeyPath(it.groupValues[1]) }.toList()
            check(keyPaths.contains(listOf("dependencies", modId))) {
                "$metadataFile must declare its dependencies as [[dependencies.$modId]]. " +
                    "A quoted header is a single literal key that loaders never look up; found $keyPaths"
            }
        }

        val fabricMetadata = root.resolve("fabric.mod.json").readText()
        val parsed = groovy.json.JsonSlurper().parseText(fabricMetadata) as Map<*, *>
        check(parsed["id"] == modId) { "fabric.mod.json must declare the mod id $modId" }
        val depends = parsed["depends"] as? Map<*, *>
        check(depends?.get("minecraft") != null) { "fabric.mod.json must declare the Minecraft dependency" }
    }
}

tasks.check {
    dependsOn(validateModMetadata)
}

val coreJar = project(":core").tasks.named<Jar>("jar")

tasks.jar {
    dependsOn(coreJar)
    // The artifact merges the core JAR with this module's own resources, so an overlapping path
    // would silently end up in the archive twice and the loader would see whichever came first.
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(coreJar.flatMap { it.archiveFile }.map { zipTree(it) }) {
        exclude("META-INF/MANIFEST.MF")
    }
    from(sourceSets.main.get().output)

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

/**
 * Directory the platform builds hand their native libraries over in, one subdirectory per platform.
 * CI passes a path relative to the repository root, so it is resolved against the root project
 * rather than against this module.
 */
val prebuiltNativesDirectory = providers.gradleProperty("prebuiltNativesDir")
    .map { rootProject.layout.projectDirectory.dir(it) }
val universalNativeResources = layout.buildDirectory.dir("generated/universal-native-resources")

val stageUniversalNativeResources by tasks.registering {
    group = "native"
    description = "Stages all release native libraries supplied by CI."

    val prebuiltNatives = prebuiltNativesDirectory.orNull
    if (prebuiltNatives != null) {
        inputs.dir(prebuiltNatives)
    }
    outputs.dir(universalNativeResources)

    doLast {
        val inputRoot = prebuiltNativesDirectory.orNull?.asFile ?: throw GradleException(
            "The universal JAR needs the native libraries from the platform builds. " +
                "Pass -PprebuiltNativesDir=<directory> containing one subdirectory per platform.",
        )
        check(inputRoot.isDirectory) { "Prebuilt natives directory does not exist: $inputRoot" }
        val outputRoot = universalNativeResources.get().asFile
        outputRoot.deleteRecursively()

        val manifestLines = mutableListOf("platforms=${releasePlatforms.joinToString(",")}")
        for (platform in releasePlatforms) {
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
    dependsOn(stageUniversalNativeResources)
    archiveClassifier = "universal"
    duplicatesStrategy = DuplicatesStrategy.FAIL

    from(coreJar.flatMap { it.archiveFile }.map { zipTree(it) }) {
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/luau/natives/**")
    }
    from(sourceSets.main.get().output)
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

/** Reads a NUL-terminated string out of an ELF file image. */
fun readNullTerminatedString(bytes: ByteArray, offset: Int): String {
    val builder = StringBuilder()
    var index = offset
    while (index < bytes.size && bytes[index] != 0.toByte()) {
        builder.append((bytes[index].toInt() and 0xFF).toChar())
        ++index
    }
    return builder.toString()
}

/** Compares dotted numeric versions, treating missing components as zero. */
fun compareVersions(left: String, right: String): Int {
    val leftParts = left.split('.').map { it.toIntOrNull() ?: 0 }
    val rightParts = right.split('.').map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
        val difference =
            leftParts.getOrElse(index) { 0 }.compareTo(rightParts.getOrElse(index) { 0 })
        if (difference != 0) return difference
    }
    return 0
}

/**
 * Reads the versioned symbol requirements of a 64-bit little-endian ELF shared object, keyed by
 * the library that provides them, for example libc.so.6 -> [GLIBC_2.2.5, GLIBC_2.28].
 */
fun elfVersionRequirements(bytes: ByteArray): Map<String, Set<String>> {
    val magic = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
    if (bytes.size < 64 || magic.indices.any { bytes[it] != magic[it] }) {
        throw GradleException("The native library is not an ELF file")
    }
    check(bytes[4].toInt() == 2) { "The native library is not a 64-bit ELF file" }
    check(bytes[5].toInt() == 1) { "The native library is not a little-endian ELF file" }

    fun u16(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)

    fun u64(offset: Int): Long = u32(offset) or (u32(offset + 4) shl 32)

    val sectionHeaders = u64(0x28).toInt()
    val sectionHeaderSize = u16(0x3A)
    val sectionCount = u16(0x3C)
    fun sectionAt(index: Int) = sectionHeaders + index * sectionHeaderSize

    val sectionNames = u64(sectionAt(u16(0x3E)) + 24).toInt()
    fun sectionName(index: Int): String =
        readNullTerminatedString(bytes, sectionNames + u32(sectionAt(index)).toInt())
    fun sectionOffset(index: Int) = u64(sectionAt(index) + 24).toInt()
    fun sectionSize(index: Int) = u64(sectionAt(index) + 32).toInt()

    var stringTable = -1
    var versionNeeds = -1
    var versionNeedsSize = 0
    for (index in 0 until sectionCount) {
        when (sectionName(index)) {
            ".dynstr" -> stringTable = sectionOffset(index)
            ".gnu.version_r" -> {
                versionNeeds = sectionOffset(index)
                versionNeedsSize = sectionSize(index)
            }
        }
    }
    if (versionNeeds < 0) return emptyMap()
    check(stringTable >= 0) { "The native library has version needs but no .dynstr section" }

    val requirements = mutableMapOf<String, MutableSet<String>>()
    var entry = versionNeeds
    while (entry < versionNeeds + versionNeedsSize) {
        val count = u16(entry + 2)
        val provider = readNullTerminatedString(bytes, stringTable + u32(entry + 4).toInt())
        var auxiliary = entry + u32(entry + 8).toInt()
        repeat(count) {
            val version = readNullTerminatedString(bytes, stringTable + u32(auxiliary + 8).toInt())
            requirements.getOrPut(provider) { mutableSetOf() }.add(version)
            auxiliary += u32(auxiliary + 12).toInt()
        }
        val next = u32(entry + 12).toInt()
        if (next == 0) break
        entry += next
    }
    return requirements
}

val verifyUniversalJar by tasks.registering {
    group = "verification"
    description = "Verifies that the universal JAR contains every native and matching metadata."
    dependsOn(universalJar)

    val archive = universalJar.flatMap { it.archiveFile }
    inputs.file(archive)

    doLast {
        ZipFile(archive.get().asFile).use { jar ->
            val manifestEntry = checkNotNull(
                jar.getEntry("META-INF/luau/natives/manifest.properties"),
            ) { "Universal JAR is missing the native manifest" }
            val nativeManifest = Properties().apply {
                jar.getInputStream(manifestEntry).use(::load)
            }

            check(nativeManifest.getProperty("platforms") == releasePlatforms.joinToString(",")) {
                "Universal JAR native platform list does not match the release platform list"
            }
            check(nativeManifest.getProperty("jniAbiVersion") == jniAbiVersion)
            check(nativeManifest.getProperty("luauVersion") == luauVersion)
            check(nativeManifest.getProperty("luauCommit") == luauCommit)
            check(nativeManifest.getProperty("runtimeVersion") == version.toString())

            for (platform in releasePlatforms) {
                val fileName = checkNotNull(nativeManifest.getProperty("$platform.filename"))
                val entry = checkNotNull(
                    jar.getEntry("META-INF/luau/natives/$platform/$fileName"),
                ) { "Universal JAR is missing its $platform native library" }
                check(nativeManifest.getProperty("$platform.size") == entry.size.toString()) {
                    "Native size mismatch for $platform"
                }
                val bytes = jar.getInputStream(entry).use { it.readAllBytes() }
                check(nativeManifest.getProperty("$platform.sha256") == sha256(bytes)) {
                    "Native SHA-256 mismatch for $platform"
                }
                if (platform.startsWith("linux-")) {
                    val requirements = elfVersionRequirements(bytes)
                    val cxxRuntimes = requirements.keys.filter {
                        it.startsWith("libstdc++") || it.startsWith("libc++")
                    }
                    check(cxxRuntimes.isEmpty()) {
                        "$platform/$fileName depends on ${cxxRuntimes.joinToString()}. Link the C++ " +
                            "runtime statically so the library only needs the system C library."
                    }
                    val tooNew = requirements["libc.so.6"].orEmpty()
                        .filter { compareVersions(it.removePrefix("GLIBC_"), glibcFloor) > 0 }
                        .sorted()
                    check(tooNew.isEmpty()) {
                        "$platform/$fileName requires ${tooNew.joinToString()}, but the declared " +
                            "glibc floor is $glibcFloor. Build that platform on an older baseline."
                    }
                }
            }

            for (metadata in listOf(
                "fabric.mod.json",
                "META-INF/mods.toml",
                "META-INF/neoforge.mods.toml",
                "pack.mcmeta",
                "META-INF/luau/runtime.properties",
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
