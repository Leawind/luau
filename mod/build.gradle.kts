import java.util.zip.ZipFile
import org.gradle.api.file.DuplicatesStrategy

plugins {
    `java-library`
}

// The mod is the library's packaging and is released from the same tag, so it takes the
// library's coordinates rather than keeping its own.
group = providers.gradleProperty("luau.group").get()
version = providers.gradleProperty("luau.artifactVersion").get()

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

// The in-game mod id stays `luau`; the artifact name carries the suffix so the library keeps the
// plain name it publishes under.
base {
    archivesName = "${rootProject.name}-mod"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // The mod artifact carries the core library inside it, so the loader sees one JAR.
    implementation(project(":core"))
}

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
val coreUniversalJar = project(":core").tasks.named<Jar>("universalJar")
val coreVerifyUniversalJar = project(":core").tasks.named("verifyUniversalJar")

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
 * The loader-visible artifact: the library's universal JAR, which already carries every native
 * platform, with the loader metadata of this module on top of it.
 */
val universalJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds the release JAR with every native platform and the loader metadata."
    dependsOn(coreUniversalJar)
    archiveClassifier = "universal"
    duplicatesStrategy = DuplicatesStrategy.FAIL

    from(coreUniversalJar.flatMap { it.archiveFile }.map { zipTree(it) }) {
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
 * The universal packaging itself is verified where it is assembled. This check covers what the mod
 * adds: the loader metadata, whose ids validateModMetadata guards.
 */
val verifyUniversalJar by tasks.registering {
    group = "verification"
    description = "Verifies that the universal JAR carries the loader metadata."
    dependsOn(universalJar, coreVerifyUniversalJar, validateModMetadata)

    val archive = universalJar.flatMap { it.archiveFile }
    inputs.file(archive)

    doLast {
        ZipFile(archive.get().asFile).use { jar ->
            for (metadata in listOf(
                "fabric.mod.json",
                "META-INF/mods.toml",
                "META-INF/neoforge.mods.toml",
                "pack.mcmeta",
                "logo.png",
            )) {
                check(jar.getEntry(metadata) != null) {
                    "Universal JAR is missing required entry: $metadata"
                }
            }
        }
    }
}

// The core module resolves the prebuilt natives directory; its presence decides whether this
// module can assemble its universal JAR at all.
val prebuiltNativesAvailable = project(":core").extra["prebuiltNativesAvailable"] as Boolean
if (prebuiltNativesAvailable) {
    tasks.named("build") {
        dependsOn(universalJar)
    }
    tasks.named("check") {
        dependsOn(verifyUniversalJar)
    }
}
