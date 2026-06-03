plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.maxlyth.hapaneld"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.maxlyth.hapaneld"
        // minSdk 26: clears the HiveMQ "<26 cannot connect over IoT" bug (#598) and covers
        // the whole panel fleet (NSPanelPro Android 8.1 = API 27, Hall TPA10 Android 11 = API 30).
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        // Committed debug keystore so every CI build is signed identically — lets `install -r`
        // update a panel in place without uninstalling (which a device-admin install otherwise
        // blocks). A debug keystore is not a secret; password is the conventional "android".
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
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

    // MQTT 5 auto-discovery + state publishing.
    implementation(libs.hivemq.mqtt.client)

    // mDNS advertise (_ha-paneld._tcp).
    implementation(libs.jmdns)

    // Ktor/HiveMQ log via SLF4J; route it to Logcat.
    implementation(libs.slf4j.android)
}
