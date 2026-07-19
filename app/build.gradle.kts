import java.util.Properties
import java.security.MessageDigest
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.cyclonedx.bom)
}

val featureCostsEnabled = providers.gradleProperty("featureCosts").orNull
    ?.toBooleanStrictOrNull() ?: true

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
    compileSdk = 35

    // Pinned so CI builds the native LED driver deterministically (matches the sdkmanager install
    // step in .github/workflows/*.yml). 27.0.12077973 is AGP 8.7's default NDK.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.github.maxlyth.hapaneld"
        // minSdk 26: clears the HiveMQ "<26 cannot connect over IoT" bug (#598) and covers
        // the supported panels (NSPanel Pro Android 8.1 = API 27, TPA10 Android 11 = API 30).
        minSdk = 26
        targetSdk = 35
        // versionCode bumps on EVERY internal build (it drives upgrades + the /health build token);
        // versionName identifies the release line/candidate; publication remains a separate explicit action.
        versionCode = 318
        versionName = "0.9.5-rc1"
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
    val keystoreProps = rootProject.file("keystore.properties")
    val hasReleaseSigning = keystoreProps.exists()

    signingConfigs {
        // Committed debug keystore so every CI build is signed identically — lets `install -r`
        // update a panel in place without uninstalling (which a device-admin install otherwise
        // blocks). A debug keystore is not a secret; password is the conventional "android".
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

    kotlinOptions {
        jvmTarget = "17"
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
    implementation(libs.ktor.client.cio)
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
    implementation("com.google.zxing:core:3.5.3")

    // JVM unit tests (no Android/emulator deps): pure-logic + coroutine serialization regression tests.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    // Independent MQTT 5 broker for transport-level composition tests; never packaged in the APK.
    testImplementation(libs.moquette.broker)
    // Real org.json — the android.jar stub's returnDefaultValues would silently no-op JSON code under test.
    testImplementation(libs.org.json)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
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
val compileCdpRelay by tasks.registering {
    val ndkDir = android.ndkDirectory
    val src = rootProject.file("helper/cdprelay.c")
    val policy = rootProject.file("helper/cdprelay_policy.h")
    val out64 = file("src/main/assets/cdprelay-arm64")
    val out32 = file("src/main/assets/cdprelay-arm")
    inputs.files(src, policy)
    inputs.property("ndk", ndkDir.toString())
    outputs.files(out64, out32)
    doLast {
        val bin = "$ndkDir/toolchains/llvm/prebuilt/linux-x86_64/bin"
        out64.parentFile.mkdirs()
        exec { commandLine("$bin/aarch64-linux-android26-clang", "-O2", "-s", "-o", out64.path, src.path) }
        exec { commandLine("$bin/armv7a-linux-androideabi26-clang", "-O2", "-s", "-o", out32.path, src.path) }
    }
}

// Carry the matching root-helper protocol inside the APK as a migration backstop. Provisioning remains
// the durable installation path; after an in-app self-update, direct-su panels can atomically launch the
// new helper before exposing helper-versioned features. API 26 matches the app's supported floor.
val compileBundledRootHelper by tasks.registering {
    val ndkDir = android.ndkDirectory
    val sources = rootProject.fileTree("helper/src") { include("*.c") }
    val out64 = file("src/main/assets/hapaneld-helper-arm64")
    val out32 = file("src/main/assets/hapaneld-helper-arm")
    inputs.files(helperIdentityFiles)
    inputs.file(rootProject.file("helper/source-id.sh"))
    inputs.property("helperBuildId", helperBuildId)
    inputs.property("ndk", ndkDir.toString())
    outputs.files(out64, out32)
    doLast {
        val bin = "$ndkDir/toolchains/llvm/prebuilt/linux-x86_64/bin"
        val sourcePaths = sources.files.sortedBy(File::getName).map(File::getPath)
        out64.parentFile.mkdirs()
        exec {
            commandLine(
                "$bin/aarch64-linux-android26-clang", "-O2", "-s", "-I${rootProject.file("helper/src").path}",
                "-DHAPANELD_BUILD_ID=\"$helperBuildId\"",
                "-o", out64.path, *sourcePaths.toTypedArray(),
            )
        }
        exec {
            commandLine(
                "$bin/armv7a-linux-androideabi26-clang", "-O2", "-s", "-I${rootProject.file("helper/src").path}",
                "-DHAPANELD_BUILD_ID=\"$helperBuildId\"",
                "-o", out32.path, *sourcePaths.toTypedArray(),
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(compileCdpRelay, compileBundledRootHelper) }

val helperSocketTestServer = rootProject.file("helper/build/socket-test-server")
val buildHelperSocketTestServer by tasks.registering(Exec::class) {
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
}
