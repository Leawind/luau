import java.security.MessageDigest

plugins {
    `java-library`
    `maven-publish`
    id("com.diffplug.spotless") version "8.12.0"
}

group = providers.gradleProperty("mod.group").get()
version = providers.gradleProperty("mod.version").get()

/**
 * Name of the published library. The archive name and the Maven artifact id both come from here,
 * so the JAR in `build/libs` and the one in a repository cannot drift apart.
 */
val artifactName = rootProject.name

val luauVersion = providers.gradleProperty("luau.version").get()
val luauCommit = providers.gradleProperty("luau.commit").get()
val jniAbiVersion = providers.gradleProperty("luau.jniAbiVersion").get()

/**
 * Sanitizer group the native library is built with, for example `undefined` or `address`. Empty
 * builds the release library; the CI passes `-PnativeSanitizers=undefined` to test an
 * instrumented one. The address group needs [nativeSanitizerRuntime] as well and is a local tool
 * for now: the suite reports a pre-existing heap-use-after-free inside the vendored Luau teardown
 * with it, so no CI job runs that group yet.
 */
val nativeSanitizers = providers.gradleProperty("nativeSanitizers").getOrElse("")

/**
 * Shared sanitizer runtime the test JVM preloads, for example the `libasan.so.8` of the toolchain
 * that built the library. The address group only sees the whole process when the runtime is
 * initialized before every other library, so it has to be named here; the other groups do not
 * use it.
 */
val nativeSanitizerRuntime = providers.gradleProperty("nativeSanitizerRuntime").getOrElse("")

/** Platforms a release ships, read from `luau.releasePlatforms` so both modules share one list. */
val releasePlatforms = providers.gradleProperty("luau.releasePlatforms").get()
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
require(releasePlatforms.isNotEmpty()) {
    "luau.releasePlatforms in gradle.properties is empty, so there would be no platform to test."
}

// The mod module shares the repository root, so the vendored Luau tree stays where the submodule
// and the CI jobs expect it.
val nativeDirectory = rootProject.layout.projectDirectory.dir("native")

base {
    archivesName = artifactName
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("2.0.0")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
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

val hostOs = normalizedOs()
val hostArch = normalizedArch()

// CMake refuses the sanitizer switch on MSVC, and its message is buried in the configure output;
// fail here instead so the reason reaches the console.
if (nativeSanitizers.isNotEmpty() && hostOs == "windows") {
    throw GradleException(
        "-PnativeSanitizers=$nativeSanitizers needs a GCC or Clang toolchain, and MSVC has no " +
            "-fsanitize=$nativeSanitizers. Run the instrumented tests on Linux.",
    )
}

/** Sanitizer groups of [nativeSanitizers], which GCC also accepts as a comma separated list. */
val sanitizerGroups = nativeSanitizers.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Whether the test JVM has to preload the sanitizer runtime. AddressSanitizer only instruments
 * the whole process, heap allocations included, when its runtime is the first library that gets
 * initialized, and the JVM loads the native library long after that point.
 */
val preloadsSanitizerRuntime = "address" in sanitizerGroups

// A runtime that is missing or that the loader refuses makes the process start without the
// sanitizer, which would turn the instrumented suite into a passing but meaningless run.
if (preloadsSanitizerRuntime) {
    require(nativeSanitizerRuntime.isNotEmpty()) {
        "-PnativeSanitizers=address needs -PnativeSanitizerRuntime=<path to libasan.so>; the test " +
            "JVM preloads that runtime so that the sanitizer covers the whole process."
    }
    require(File(nativeSanitizerRuntime).isFile) {
        "-PnativeSanitizerRuntime=$nativeSanitizerRuntime is not a file"
    }
}

/**
 * Libraries the test JVM preloads, AddressSanitizer first.
 *
 * Luau reports errors by throwing a C++ exception. AddressSanitizer interposes `__cxa_throw`, and
 * resolves the function it delegates to when it initialises. Preloading only the sanitizer runtime
 * means that resolution happens while the C++ runtime is still absent, so the interceptor aborts
 * the process with a CHECK failure on the first script error instead of sanitizing anything.
 * Loading the C++ runtime right behind the sanitizer keeps the sanitizer first and gives the
 * interceptor something to resolve.
 */
val sanitizerPreloadLibraries: List<String> =
    if (preloadsSanitizerRuntime) {
        val cxxRuntime = run {
            val besideRuntime = File(nativeSanitizerRuntime).parentFile?.resolve("libstdc++.so.6")
            when {
                besideRuntime != null && besideRuntime.isFile -> besideRuntime.absolutePath
                else -> {
                    val candidates =
                        listOf(
                            "/usr/lib/x86_64-linux-gnu/libstdc++.so.6",
                            "/usr/lib/aarch64-linux-gnu/libstdc++.so.6",
                            "/usr/lib64/libstdc++.so.6",
                            "/lib64/libstdc++.so.6",
                        )
                    candidates.firstOrNull { File(it).isFile }
                }
            }
        }
        require(cxxRuntime != null) {
            "AddressSanitizer interposes the C++ exception path, so libstdc++.so.6 has to be " +
                "preloaded next to $nativeSanitizerRuntime; none was found beside it or in the " +
                "usual system directories."
        }
        listOf(File(nativeSanitizerRuntime).absolutePath, cxxRuntime)
    } else {
        emptyList()
    }

val hostPlatform = "$hostOs-$hostArch"
val hostNativeName = nativeLibraryName(hostOs)
val nativeBuildDirectory = layout.buildDirectory.dir("native/cmake")
val nativeOutputDirectory = nativeBuildDirectory.map { it.dir("out") }
val nativeLibrary = nativeOutputDirectory.map { it.file(hostNativeName) }
val cmakeGenerator = if (hostOs == "windows") "Ninja" else null

fun captureProcessOutput(command: List<String>): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "Command failed with exit code $exitCode: ${command.joinToString(" ")}" }
    return output.trim()
}

/** Imports the MSVC toolchain environment so the native build does not require a developer shell. */
fun msvcEnvironment(): Map<String, String> {
    check(hostOs == "windows") { "MSVC environment is only available on Windows" }
    val vswhere = File(
        System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)",
        "Microsoft Visual Studio/Installer/vswhere.exe",
    )
    check(vswhere.isFile) {
        "Building the native library on Windows requires Visual Studio Build Tools with the C++ workload"
    }
    val installationPath = captureProcessOutput(
        listOf(vswhere.absolutePath, "-latest", "-products", "*", "-property", "installationPath"),
    )
    check(installationPath.isNotBlank()) {
        "Building the native library on Windows requires Visual Studio Build Tools with the C++ workload"
    }
    val vcvars = File(installationPath, "VC/Auxiliary/Build/vcvars64.bat")
    check(vcvars.isFile) { "Missing MSVC environment script: $vcvars" }
    return captureProcessOutput(listOf("cmd", "/c", "call \"${vcvars.absolutePath}\" >nul && set"))
        .lineSequence()
        .filter { it.isNotEmpty() && !it.startsWith("=") && it.contains('=') }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
}

val msvcEnv: Map<String, String> by lazy { if (hostOs == "windows") msvcEnvironment() else emptyMap() }

val configureNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Configures the native Luau JNI library for the current platform."

    inputs.file(nativeDirectory.file("CMakeLists.txt"))
    inputs.dir(nativeDirectory.dir("src"))
    inputs.file(nativeDirectory.file("luau/CMakeLists.txt"))
    inputs.property("runtimeVersion", version)
    inputs.property("luauVersion", luauVersion)
    inputs.property("luauCommit", luauCommit)
    inputs.property("jniAbiVersion", jniAbiVersion)
    inputs.property("cmakeGenerator", cmakeGenerator ?: "cmake-default")
    inputs.property("nativeSanitizers", nativeSanitizers)
    outputs.file(nativeBuildDirectory.map { it.file("CMakeCache.txt") })

    doFirst {
        val buildDirectory = nativeBuildDirectory.get().asFile
        val cache = buildDirectory.resolve("CMakeCache.txt")
        if (cache.isFile) {
            val cached = cache.readLines()
                .filter { it.contains('=') }
                .associate { it.substringBefore(':') to it.substringAfter('=') }
            // A cache written by another generator or another sanitizer setting would be reused
            // silently, so the whole build directory is thrown away when either changed.
            val generatorChanged = cmakeGenerator != null && cached["CMAKE_GENERATOR"] != cmakeGenerator
            val sanitizersChanged = cached["LUAU_JNI_SANITIZE"].orEmpty() != nativeSanitizers
            if (generatorChanged || sanitizersChanged) buildDirectory.deleteRecursively()
        }
        buildDirectory.mkdirs()
        if (cmakeGenerator != null) environment(msvcEnv)
    }

    commandLine(
        buildList {
            add("cmake")
            add("-S")
            add(nativeDirectory.asFile.absolutePath)
            add("-B")
            add(nativeBuildDirectory.get().asFile.absolutePath)
            if (cmakeGenerator != null) {
                add("-G")
                add(cmakeGenerator)
            }
            add("-DCMAKE_BUILD_TYPE=Release")
            add("-DLUAU_BUILD_CLI=OFF")
            add("-DLUAU_BUILD_TESTS=OFF")
            add("-DLUAU_BUILD_WEB=OFF")
            add("-DLUAU_RUNTIME_VERSION=$version")
            add("-DLUAU_UPSTREAM_VERSION=$luauVersion")
            add("-DLUAU_UPSTREAM_COMMIT=$luauCommit")
            add("-DLUAU_JNI_ABI_VERSION=$jniAbiVersion")
            add("-DLUAU_JNI_SANITIZE=$nativeSanitizers")
        },
    )
    environment("JAVA_HOME", System.getProperty("java.home"))
}

val buildNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds the native Luau JNI library for the current platform."
    dependsOn(configureNative)

    inputs.dir(nativeDirectory.dir("src"))
    inputs.dir(nativeDirectory.dir("luau"))
    // The compile definitions come from these, so a change has to rebuild the library: without
    // them a version bump only reconfigures CMake and the stale binary stays in place, still
    // reporting the old version.
    inputs.property("runtimeVersion", version)
    inputs.property("luauVersion", luauVersion)
    inputs.property("luauCommit", luauCommit)
    inputs.property("jniAbiVersion", jniAbiVersion)
    inputs.property("nativeSanitizers", nativeSanitizers)
    outputs.file(nativeLibrary)

    doFirst {
        if (cmakeGenerator != null) environment(msvcEnv)
    }

    commandLine(
        "cmake",
        "--build", nativeBuildDirectory.get().asFile.absolutePath,
        "--target", "luau_jni",
        "--config", "Release",
        "--parallel",
    )
}

val nativeDistributionDirectory = layout.buildDirectory.dir("native/dist")

val stageNativeDistribution by tasks.registering(Sync::class) {
    group = "native"
    description = "Stages the current platform native library alone in a clean directory for CI upload."
    dependsOn(buildNative)

    into(nativeDistributionDirectory)
    from(nativeLibrary)
}

tasks.named("build") {
    dependsOn(stageNativeDistribution)
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
    val properties = mapOf(
        "version" to version,
        "luauVersion" to luauVersion,
        "luauCommit" to luauCommit,
        "jniAbiVersion" to jniAbiVersion,
    )
    inputs.properties(properties)

    filesMatching(listOf("META-INF/luau/runtime.properties")) {
        expand(properties)
    }

    from(rootProject.layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
    from(nativeDirectory.file("luau/LICENSE.txt")) {
        into("META-INF/licenses/luau")
    }
    from(nativeDirectory.file("luau/lua_LICENSE.txt")) {
        into("META-INF/licenses/luau")
    }
}

val validateJniAbiVersion by tasks.registering {
    group = "verification"
    description = "Verifies that NativeBindings.EXPECTED_ABI_VERSION matches luau.jniAbiVersion."

    val bindingsSource = layout.projectDirectory.file(
        "src/main/java/io/github/leawind/luau/internal/jni/NativeBindings.java",
    )
    inputs.file(bindingsSource)
    inputs.property("jniAbiVersion", jniAbiVersion)

    doLast {
        val sourceFile = bindingsSource.asFile
        val declared = Regex("""EXPECTED_ABI_VERSION\s*=\s*(\d+)\s*;""")
            .find(sourceFile.readText())
            ?.groupValues
            ?.get(1)
            ?: throw GradleException(
                "Cannot read the EXPECTED_ABI_VERSION constant from $sourceFile. " +
                    "The guard parses that declaration; keep it a plain integer constant.",
            )
        check(declared == jniAbiVersion) {
            "NativeBindings.EXPECTED_ABI_VERSION is $declared but luau.jniAbiVersion is " +
                "$jniAbiVersion. The loader rejects a native library built for a different ABI " +
                "version, so update NativeBindings and gradle.properties together."
        }
    }
}

val validateLuauSubmoduleCommit by tasks.registering {
    group = "verification"
    description = "Verifies that luau.commit matches the native/luau submodule HEAD."

    val submoduleDirectory = nativeDirectory.dir("luau")
    inputs.property("luauCommit", luauCommit)

    doLast {
        val repositoryDirectory = submoduleDirectory.asFile
        if (!repositoryDirectory.resolve(".git").exists()) {
            throw GradleException(
                "The native/luau submodule is not initialized, so luau.commit cannot be verified. " +
                    "Run: git submodule update --init --recursive",
            )
        }
        val head = try {
            captureProcessOutput(
                listOf("git", "-C", repositoryDirectory.absolutePath, "rev-parse", "HEAD"),
            )
        } catch (exception: Exception) {
            throw GradleException(
                "Cannot read the native/luau submodule HEAD. " +
                    "Run: git submodule update --init --recursive",
                exception,
            )
        }
        check(head.equals(luauCommit, ignoreCase = true)) {
            "luau.commit in gradle.properties is $luauCommit but native/luau is at $head. " +
                "Check out the submodule commit that should ship, or update luau.commit."
        }
    }
}

val validateClangFormatConfig by tasks.registering {
    group = "verification"
    description = "Verifies that native/.clang-format copies the upstream Luau style byte for byte."

    val sharedConfig = nativeDirectory.file(".clang-format")
    val upstreamConfig = nativeDirectory.file("luau/.clang-format")
    // Both files are inputs: the guard exists to notice the upstream style changing.
    inputs.files(sharedConfig, upstreamConfig)

    doLast {
        val upstream = upstreamConfig.asFile
        if (!upstream.isFile) {
            throw GradleException(
                "Cannot read native/luau/.clang-format, so the shared C++ style cannot be verified. " +
                    "Run: git submodule update --init --recursive",
            )
        }
        val shared = sharedConfig.asFile
        if (!shared.isFile) {
            throw GradleException(
                "native/.clang-format is missing. It mirrors the upstream style byte for byte; " +
                    "restore it from native/luau/.clang-format.",
            )
        }
        // Line endings are compared normalised: the submodule is checked out with the line endings
        // of the host, and a Windows checkout would otherwise fail this guard spuriously.
        val sharedText = shared.readText().replace("\r\n", "\n").replace("\r", "\n")
        val upstreamText = upstream.readText().replace("\r\n", "\n").replace("\r", "\n")
        check(sharedText == upstreamText) {
            "native/.clang-format (${sharedText.length} characters) differs from " +
                "native/luau/.clang-format (${upstreamText.length} characters). The JNI layer is " +
                "compiled next to Luau sources laid out by the upstream style, so the two files stay " +
                "identical: re-copy the upstream file, or change both together and say why."
        }
    }
}

/** The C++ sources this repository formats; the vendored native/luau tree keeps its own style. */
val nativeCppSources = fileTree(nativeDirectory.dir("src")) {
    include("*.cc", "*.cpp", "*.h", "*.hpp")
}

/** Runs a command and returns its trimmed standard output, or null when it cannot be run at all. */
fun probeCommandOutput(command: List<String>): String? = try {
    val process = ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) output else null
} catch (exception: Exception) {
    null
}

/**
 * Locates clang-format: the clangFormatPath property, the CLANG_FORMAT environment variable, PATH,
 * the uv tool bin directory, then the executable shipped inside the pip clang-format package.
 */
fun resolveClangFormat(): String {
    val fromProperty = providers.gradleProperty("clangFormatPath").orNull
    if (!fromProperty.isNullOrBlank()) {
        val executable = File(fromProperty)
        check(executable.isFile) {
            "clangFormatPath is $fromProperty, which is not a file. Point it at the clang-format executable."
        }
        return executable.absolutePath
    }

    val fromEnvironment = System.getenv("CLANG_FORMAT")
    if (!fromEnvironment.isNullOrBlank()) {
        val executable = File(fromEnvironment)
        check(executable.isFile) {
            "CLANG_FORMAT is $fromEnvironment, which is not a file. Point it at the clang-format " +
                "executable, or unset it to search PATH instead."
        }
        return executable.absolutePath
    }

    if (probeCommandOutput(listOf("clang-format", "--version")) != null) return "clang-format"

    // uv installs tools outside PATH, and a Gradle daemon keeps the PATH it was started with.
    val uvBinDirectory = probeCommandOutput(listOf("uv", "tool", "dir", "--bin"))
    if (!uvBinDirectory.isNullOrBlank()) {
        val executableName = if (hostOs == "windows") "clang-format.exe" else "clang-format"
        val executable = File(uvBinDirectory, executableName)
        if (executable.isFile) return executable.absolutePath
    }

    val fromPip = probeCommandOutput(
        listOf(
            "py",
            "-3",
            "-c",
            "import clang_format, os; print(os.path.join(os.path.dirname(clang_format.__file__), 'data', 'bin', 'clang-format.exe'))",
        ),
    )
    if (fromPip != null && File(fromPip).isFile) return File(fromPip).absolutePath

    throw GradleException(
        "Cannot find clang-format. Pass -PclangFormatPath=<exe>, install the package with " +
            "py -3 -m pip install clang-format==23.1.5, or install the tool with " +
            "uv tool install clang-format==23.1.5.",
    )
}

/** Runs a tool, capturing both output streams, and reports its exit code with everything it printed. */
fun runCapturingOutput(command: List<String>): Pair<Int, String> {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trimEnd()
    return process.waitFor() to output
}

val clangFormat by tasks.registering {
    description = "Formats the native C++ sources in place with clang-format."

    doLast {
        val executable = resolveClangFormat()
        logger.info("Using clang-format: $executable")
        val sources = nativeCppSources.files.map { it.absolutePath }.sorted()
        check(sources.isNotEmpty()) { "No C++ sources found under native/src" }
        val (exitCode, output) = runCapturingOutput(listOf(executable, "-i") + sources)
        if (output.isNotEmpty()) logger.lifecycle(output)
        check(exitCode == 0) { "clang-format failed with exit code $exitCode" }
    }
}

val clangFormatCheck by tasks.registering {
    group = "verification"
    description = "Verifies that the native C++ sources are formatted with clang-format."

    inputs.files(nativeCppSources)

    doLast {
        val executable = resolveClangFormat()
        logger.info("Using clang-format: $executable")
        val sources = nativeCppSources.files.map { it.absolutePath }.sorted()
        check(sources.isNotEmpty()) { "No C++ sources found under native/src" }
        val (exitCode, output) = runCapturingOutput(listOf(executable, "--dry-run", "--Werror") + sources)
        if (output.isNotEmpty()) logger.lifecycle(output)
        check(exitCode == 0) {
            "clang-format reported formatting violations. Run ./gradlew :core:clangFormat and " +
                "commit the result."
        }
    }
}

tasks.check {
    dependsOn(
        validateJniAbiVersion,
        validateLuauSubmoduleCommit,
        validateClangFormatConfig,
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
    options.compilerArgs.add("-parameters")
    // Every warning is fixed rather than suppressed. javac only reports [restricted] for
    // System.load when compiling for release 24 or later, so release 17 needs no -Xlint:- flag.
    options.compilerArgs.add("-Xlint:all")
    options.compilerArgs.add("-Werror")
}

val generatedPlatformResources = layout.buildDirectory.dir("generated/platform-resources")

val generatePlatformManifest by tasks.registering {
    group = "build"
    description = "Writes the release platform list as a resource for the platform tests."

    inputs.property("releasePlatforms", releasePlatforms)
    outputs.dir(generatedPlatformResources)

    doLast {
        val manifest = generatedPlatformResources.get()
            .file("META-INF/luau/platforms.properties")
            .asFile
        manifest.parentFile.mkdirs()
        manifest.writeText("platforms=${releasePlatforms.joinToString(",")}\n")
    }
}

tasks.test {
    useJUnitPlatform()

    // The library loads its native half with System.load; from JDK 24 that needs native access.
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    // The native library is loaded from the classpath, so it only has to reach the test runtime.
    dependsOn(stageNativeResources, generatePlatformManifest)
    classpath += files(generatedNativeResources, generatedPlatformResources)

    if (nativeSanitizers.isNotEmpty()) {
        // A sanitizer reports on stderr and aborts the forked test JVM, so the per-test reports
        // never get written: the streams have to reach the build log to be seen at all.
        testLogging.showStandardStreams = true
        if (preloadsSanitizerRuntime) {
            // Preloading the runtime puts the JVM's own heap behind AddressSanitizer as well, and
            // every allocation there carries shadow memory and red zones. The runtime also reserves
            // a large virtual address range, so the heap is bounded explicitly instead of being
            // sized from the host's physical memory.
            maxHeapSize = "1g"

            // The runtime has to be the first library of the process, which only preloading can
            // arrange. The JVM manages its own heap and takes SEGV itself for null checks and
            // safepoint polling, so leak checking has to go and the signal handlers have to stay
            // with the JVM.
            environment(
                "ASAN_OPTIONS",
                "detect_leaks=0:handle_segv=0:allow_user_segv_handler=1",
            )
            environment("LD_PRELOAD", sanitizerPreloadLibraries.joinToString(" "))
            inputs.property("nativeSanitizerRuntime", sanitizerPreloadLibraries)
        } else {
            environment("UBSAN_OPTIONS", "print_stacktrace=1")
        }
    }
}

tasks.jar {
    dependsOn(stageNativeResources)
    from(generatedNativeResources)

    manifest {
        attributes(
            "Implementation-Title" to "Luau Runtime",
            "Implementation-Version" to version,
            "Luau-Version" to luauVersion,
            "Luau-Commit" to luauCommit,
            "Luau-JNI-ABI-Version" to jniAbiVersion,
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = artifactName
            pom {
                name.set("Luau Runtime")
                description.set(
                    "Sandboxed Luau runtime for the JVM, with the native library bundled for " +
                        "every supported platform.",
                )
                url.set(providers.gradleProperty("mod.homepage").get())
                licenses {
                    license {
                        name.set(providers.gradleProperty("mod.license").get())
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("Leawind")
                        name.set(providers.gradleProperty("mod.author").get())
                    }
                }
                scm {
                    connection.set("scm:git:${providers.gradleProperty("mod.sources").get()}.git")
                    developerConnection.set("scm:git:${providers.gradleProperty("mod.sources").get()}.git")
                    url.set(providers.gradleProperty("mod.sources").get())
                }
                issueManagement {
                    system.set("GitHub")
                    url.set(providers.gradleProperty("mod.issues").get())
                }
            }
        }
    }

    // Publishing into the build directory keeps the resolution check self-contained: the layout is
    // a plain Maven repository that a consuming build can point at without touching ~/.m2.
    repositories {
        maven {
            name = "local"
            url = uri(layout.buildDirectory.dir("local-repo"))
        }
    }
}
