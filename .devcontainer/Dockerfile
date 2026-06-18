# VS Code devcontainer for building ha-paneld (Android/Kotlin + the native LED lib) — keeps the
# Android SDK/NDK off bare metal. Toolchain pinned to match CI (.github/workflows/ci.yml) and
# app/build.gradle.kts: JDK 17 · compileSdk 35 · build-tools 35.0.0 · NDK 27.0.12077973 · cmake 3.22.1.
#
# The release keystore is NEVER baked into this image. To sign locally, put a gitignored
# keystore.properties (+ the .jks) in the project root at runtime — see docs/local-builds.md.
FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip git ninja-build file \
    && rm -rf /var/lib/apt/lists/*

# Android command-line tools (bumpable — see https://developer.android.com/studio#command-line-tools-only).
ARG CMDLINE_TOOLS=11076708
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" -o /tmp/clt.zip \
    && unzip -q /tmp/clt.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/clt.zip

ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools"

# SDK packages — pinned to match CI + app/build.gradle.kts.
RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" \
        "ndk;27.0.12077973" \
        "cmake;3.22.1" > /dev/null
