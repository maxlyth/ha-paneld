import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        // the -rcN suffix in versionName increments ONLY when an rc is published to GitHub.
        versionCode = 75
        versionName = "0.8.6-rc1"

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
    }

    buildTypes {
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
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

    // QR code for the on-device config URL (pure-Java encoder; no Android transitive deps).
    implementation("com.google.zxing:core:3.5.3")

    // JVM unit tests (no Android/emulator deps): pure-logic + coroutine serialization regression tests.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Compile the CDP relay (helper/cdprelay.c) into assets at build time for the fleet ABIs, using the
// pinned NDK that's already present in the Docker toolchain image and CI — so the repo ships source,
// not prebuilt binaries. Extracted + launched at runtime by control/CdpRelay.kt.
val compileCdpRelay by tasks.registering {
    val ndkDir = android.ndkDirectory
    val src = rootProject.file("helper/cdprelay.c")
    val out64 = file("src/main/assets/cdprelay-arm64")
    val out32 = file("src/main/assets/cdprelay-arm")
    inputs.file(src)
    inputs.property("ndk", ndkDir.toString())
    outputs.files(out64, out32)
    doLast {
        val bin = "$ndkDir/toolchains/llvm/prebuilt/linux-x86_64/bin"
        out64.parentFile.mkdirs()
        exec { commandLine("$bin/aarch64-linux-android26-clang", "-O2", "-s", "-o", out64.path, src.path) }
        exec { commandLine("$bin/armv7a-linux-androideabi26-clang", "-O2", "-s", "-o", out32.path, src.path) }
    }
}
tasks.named("preBuild") { dependsOn(compileCdpRelay) }
