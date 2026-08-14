import java.util.Properties
import java.security.MessageDigest
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.cyclonedx.bom)
}

val featureCostsEnabled = providers.gradleProperty("featureCosts").orNull
    ?.toBooleanStrictOrNull() ?: true
val keystoreProps = rootProject.file("keystore.properties")
val hasReleaseSigning = keystoreProps.exists()

val helperIdentityFiles = rootProject.fileTree("helper/src") {
    include("*.c", "*.h", "*.def")
}.files.sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
val helperCompileContract = "contract:android-api=26;optimization=O2;strip=true"
val helperBuildId = MessageDigest.getInstance("SHA-256").let { digest ->
    digest.update(helperCompileContract.toByteArray())
    digest.update(0)
    helperIdentityFiles.forEach { file ->
        digest.update(file.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
        digest.update(0)
        digest.update(file.length().toString().toByteArray())
        digest.update(0)
        digest.update(file.readBytes())
        digest.update(0)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

dependencyLocking {
    lockAllConfigurations()
}

android {
    namespace = "io.github.maxlyth.hapaneld"
    compileSdk = 37

    // Pinned independently of AGP's newer default so CI and local builds continue to produce the
    // same native helper and LED-driver binaries for the supported ARM32/ARM64 panel fleet.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.github.maxlyth.hapaneld"
        // minSdk 26: clears the HiveMQ "<26 cannot connect over IoT" bug (#598) and covers
        // the supported panels (NSPanel Pro Android 8.1 = API 27, TPA10 Android 11 = API 30).
        minSdk = 26
        targetSdk = 35
        // versionCode bumps on EVERY internal build (it drives upgrades + the /health build token);
        // versionName identifies the release line/candidate; publication remains a separate explicit action.
        versionCode = 569
        versionName = "0.9.7-rc1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Local paired performance runs can build an otherwise identical no-op arm with
        // `-PfeatureCosts=false`; release/default builds retain the fixed-key event counters.
        buildConfigField("boolean", "FEATURE_COSTS_ENABLED", featureCostsEnabled.toString())
        buildConfigField("String", "HELPER_BUILD_ID", "\"$helperBuildId\"")

        // Only the fleet's ARM ABIs — bounds the native LED lib (libhapaneld_led.so) + APK size.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Clean-room rk3576 /dev/ledjni ioctl driver (app/src/main/cpp/led_jni.c → libhapaneld_led.so).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Optional local release signing — drop a gitignored `keystore.properties` (storeFile, storePassword,
    // keyAlias, keyPassword) in the repo root to sign release builds with your real key on a laptop /
    // devcontainer, so `assembleRelease` installs in place over the public releases (no uninstall dance).
    // CI has no such file: it builds an unsigned release APK and post-signs with apksigner from Actions
    // secrets, so this is a no-op there. NEVER commit keystore.properties or the .jks (both gitignored).
    // See docs/local-builds.md.
    signingConfigs {
        // Committed debug keystore keeps secretless CI and emulator artifacts deterministic. These
        // artifacts are test-only. Local builds with keystore.properties use the configured release
        // signing key for both variants below.
        getByName("debug") {
            storeFile = rootProject.file("gradle/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
        if (hasReleaseSigning) {
            val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    buildTypes {
        debug {
            // A checkout with a configured private release key produces one signer across debug and
            // release artifacts. Secretless public checkouts retain
            // the deterministic debug signer and still produce a normal installable development APK.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            // Keep production ABIs unchanged while allowing the optional Shizuku integration job to
            // install the real app/native library on an x86_64 Android emulator.
            ndk.abiFilters += "x86_64"
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        // HiveMQ + Ktor require Java 8 language features.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // JVM unit tests exercise code that touches android.util.Log etc.; return stub defaults instead of
        // throwing "not mocked", so controllers that legitimately log on the tested path stay unit-testable.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            // HiveMQ pulls Netty; these metadata files collide across the dependency graph.
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

// Treat the paired-build switch as an explicit generated-source input. This prevents a locally
// cached BuildConfig from the enabled arm being reused by a disabled performance comparison.
tasks.matching { it.name.startsWith("generate") && it.name.endsWith("BuildConfig") }.configureEach {
    inputs.property("featureCostsEnabled", featureCostsEnabled)
    inputs.files(helperIdentityFiles)
    inputs.property("helperBuildId", helperBuildId)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // Pull-to-refresh for the built-in dashboard renderer (drag down from the top = light page reload).
    implementation(libs.androidx.swiperefreshlayout)
    // WebSettingsCompat force-dark for the built-in renderer on pre-Android-13 panels.
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)

    // HTTP command surface (:8888) — Ktor CIO engine.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    // Ktor WS client — used by HaLink to read HA's non-admin `config/entity_registry/list_for_display`
    // (which carries each entity's device id) to resolve this panel's device-settings URL.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)

    // MQTT 5 auto-discovery + state publishing.
    implementation(libs.hivemq.mqtt.client)

    // mDNS advertise (_ha-paneld._tcp).
    implementation(libs.jmdns)

    // Ktor/HiveMQ log via SLF4J; route it to Logcat.
    implementation(libs.slf4j.android)

    // Strict YAML 1.2 parser for runtime-loadable device profiles. ProfileYaml applies tighter
    // byte/depth/alias/key bounds and maps only into the app's closed schema (never Java objects).
    implementation(libs.snakeyaml.engine)

    // Optional shell-UID bridge for non-root panels. The manager APK remains a separate, explicit
    // user opt-in; these small API/provider libraries only expose its authenticated Binder boundary.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // QR code for the on-device config URL (pure-Java encoder; no Android transitive deps).
    implementation("com.google.zxing:core:3.5.4")

    // JVM unit tests (no Android/emulator deps): pure-logic + coroutine serialization regression tests.
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    // Independent MQTT 5 broker for transport-level composition tests; never packaged in the APK.
    testImplementation(libs.moquette.broker)
    // Real org.json — the android.jar stub's returnDefaultValues would silently no-op JSON code under test.
    testImplementation(libs.org.json)
    // Real SQLite over JDBC — deterministic reproduction of cross-connection WAL BUSY contention
    // (Issue #91) that android.jar stubs cannot exercise; never packaged in the APK.
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}

tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("releaseRuntimeClasspath"))
    projectType.set(Component.Type.APPLICATION)
    componentGroup.set("io.github.maxlyth")
    componentName.set("ha-paneld")
    componentVersion.set(android.defaultConfig.versionName ?: "unspecified")
    // GitHub's SBOM attestation parser requires a CycloneDX serialNumber.
    includeBomSerialNumber.set(true)
    includeBuildSystem.set(false)
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx-direct/bom.json"))
    xmlOutput.unsetConvention()
}

// Compile the CDP relay (helper/cdprelay.c) into assets at build time for the fleet ABIs, using the
// pinned NDK that's already present in the Docker toolchain image and CI — so the repo ships source,
// not prebuilt binaries. Extracted + launched at runtime by control/CdpRelay.kt.
val ndkDirectory = androidComponents.sdkComponents.sdkDirectory.map {
    it.dir("ndk/${android.ndkVersion}")
}
val ndkToolchainBin = ndkDirectory.get().asFile.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")

val compileCdpRelayArm64 = tasks.register<Exec>("compileCdpRelayArm64") {
    val src = rootProject.file("helper/cdprelay.c")
    val policy = rootProject.file("helper/cdprelay_policy.h")
    val output = file("src/main/assets/cdprelay-arm64")
    inputs.files(src, policy)
    inputs.dir(ndkDirectory)
    inputs.property("ndkVersion", android.ndkVersion)
    outputs.file(output)
    commandLine(ndkToolchainBin.resolve("aarch64-linux-android26-clang"), "-O2", "-s", "-o", output, src)
}

val compileCdpRelayArm32 = tasks.register<Exec>("compileCdpRelayArm32") {
    val src = rootProject.file("helper/cdprelay.c")
    val policy = rootProject.file("helper/cdprelay_policy.h")
    val output = file("src/main/assets/cdprelay-arm")
    inputs.files(src, policy)
    inputs.dir(ndkDirectory)
    inputs.property("ndkVersion", android.ndkVersion)
    outputs.file(output)
    commandLine(ndkToolchainBin.resolve("armv7a-linux-androideabi26-clang"), "-O2", "-s", "-o", output, src)
}

val compileCdpRelay = tasks.register("compileCdpRelay") {
    dependsOn(compileCdpRelayArm64, compileCdpRelayArm32)
}

// Carry the matching root-helper protocol inside the APK as a migration backstop. Provisioning remains
// the durable installation path; after an in-app self-update, direct-su panels can atomically launch the
// new helper before exposing helper-versioned features. API 26 matches the app's supported floor.
val bundledRootHelperSources = rootProject.fileTree("helper/src") { include("*.c") }

val compileBundledRootHelperArm64 = tasks.register<Exec>("compileBundledRootHelperArm64") {
    val output = file("src/main/assets/hapaneld-helper-arm64")
    val sourcePaths = bundledRootHelperSources.files.sortedBy(File::getName)
    inputs.files(helperIdentityFiles)
    inputs.file(rootProject.file("helper/source-id.sh"))
    inputs.dir(ndkDirectory)
    inputs.property("helperBuildId", helperBuildId)
    inputs.property("ndkVersion", android.ndkVersion)
    outputs.file(output)
    commandLine(
        ndkToolchainBin.resolve("aarch64-linux-android26-clang"), "-O2", "-s", "-I${rootProject.file("helper/src").path}",
        "-DHAPANELD_BUILD_ID=\"$helperBuildId\"",
        "-o", output, *sourcePaths.toTypedArray(),
    )
}

val compileBundledRootHelperArm32 = tasks.register<Exec>("compileBundledRootHelperArm32") {
    val output = file("src/main/assets/hapaneld-helper-arm")
    val sourcePaths = bundledRootHelperSources.files.sortedBy(File::getName)
    inputs.files(helperIdentityFiles)
    inputs.file(rootProject.file("helper/source-id.sh"))
    inputs.dir(ndkDirectory)
    inputs.property("helperBuildId", helperBuildId)
    inputs.property("ndkVersion", android.ndkVersion)
    outputs.file(output)
    commandLine(
        ndkToolchainBin.resolve("armv7a-linux-androideabi26-clang"), "-O2", "-s", "-I${rootProject.file("helper/src").path}",
        "-DHAPANELD_BUILD_ID=\"$helperBuildId\"",
        "-o", output, *sourcePaths.toTypedArray(),
    )
}

val compileBundledRootHelper = tasks.register("compileBundledRootHelper") {
    dependsOn(compileBundledRootHelperArm64, compileBundledRootHelperArm32)
}
tasks.named("preBuild") { dependsOn(compileCdpRelay, compileBundledRootHelper) }

val helperSocketTestServer = rootProject.file("helper/build/socket-test-server")
val buildHelperSocketTestServer = tasks.register<Exec>("buildHelperSocketTestServer") {
    workingDir(rootProject.file("helper"))
    commandLine("make", "socket-test-server")
    inputs.files(
        rootProject.file("helper/Makefile"),
        rootProject.fileTree("helper/src"),
        rootProject.file("helper/test/socket_server.c"),
        rootProject.file("helper/test/sysexec_stub.c"),
        rootProject.file("helper/test/sysexec_stub.h"),
    )
    outputs.file(helperSocketTestServer)
}

tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
        dependsOn(buildHelperSocketTestServer)
        systemProperty("hapaneld.helper.socketTestServer", helperSocketTestServer.absolutePath)
    }
    providers.gradleProperty("autoSleepReplayInput").orNull?.let {
        systemProperty("hapaneld.autoSleepReplay.input", it)
        inputs.file(it)
        inputs.property("autoSleepReplayInputPath", it)
    }
    providers.gradleProperty("autoSleepReplayOutput").orNull?.let {
        systemProperty("hapaneld.autoSleepReplay.output", it)
        inputs.property("autoSleepReplayOutputPath", it)
    }
}
