// SPIKE (branch spike/esphome-api): ESPHome native-API protobuf bindings, generated from the OFFICIAL
// ESPHome .proto vendored from esphome/aioesphomeapi (MIT — see src/main/proto/UPSTREAM-LICENSE-MIT.txt).
// Just `protoc` output from ESPHome's protocol — no Ava code. The server lives in the app.
// Java codegen only (no kotlin builtin) so this stays a plain java-library; the app (Kotlin) uses the
// generated Java builder API directly.
plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
}

dependencies {
    api(libs.protobuf.java)
}
