// SPIKE (branch spike/esphome-api): ESPHome native-API protobuf bindings, generated from the OFFICIAL
// ESPHome .proto vendored from esphome/aioesphomeapi (MIT — see src/main/proto/UPSTREAM-LICENSE-MIT.txt).
// This module is JUST `protoc` output from ESPHome's protocol — no Ava code. The server lives in the app.
//
// TODO(spike): reconcile the protobuf-plugin + protoc versions with gradle/libs.versions.toml, and add
//   `include(":esphome")` to settings.gradle.kts, before the first build.
plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.28.2" }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("kotlin")   // Kotlin DSL builders on top of the default Java classes
            }
        }
    }
}

dependencies {
    api("com.google.protobuf:protobuf-kotlin:4.28.2")  // pulls protobuf-java transitively
}
