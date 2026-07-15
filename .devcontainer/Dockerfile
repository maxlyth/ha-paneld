# VS Code devcontainer for building ha-paneld (Android/Kotlin + the native LED lib) — keeps the
# Android SDK/NDK off bare metal. Toolchain pinned to match CI (.github/workflows/ci.yml) and
# app/build.gradle.kts: JDK 17 · compileSdk 35 · build-tools 35.0.0 · NDK 27.0.12077973 · cmake 3.22.1.
#
# The release keystore is NEVER baked into this image. To sign locally, put a gitignored
# keystore.properties (+ the .jks) in the project root at runtime — see docs/local-builds.md.
# Noble (Ubuntu 24.04) base. On arm64 dev hosts (e.g. Apple Silicon under OrbStack) the official
# x86_64 SDK build-tools (aapt2/zipalign/aidl) and NDK clang run under OrbStack's Rosetta at
# near-native speed; the JVM/Gradle/Kotlin half runs native arm64. The arm64-conditional multi-arch
# block below installs the x86_64 runtime libs Rosetta needs. All tooling stays official.
FROM eclipse-temurin:25-jdk-noble@sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328afdbb7abed90f617

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip git ninja-build file \
    && rm -rf /var/lib/apt/lists/*

# arm64 dev hosts only: enable x86_64 (amd64) multi-arch so the official x86_64 SDK build-tools + NDK
# clang run under OrbStack's Rosetta. amd64 packages come from archive.ubuntu.com (not the arm64
# ports mirror), so the existing ports entries are scoped to arm64 and an amd64 archive is added.
# No-op on x86_64 hosts, where amd64 is already native. (deb822 ubuntu.sources is the noble default.)
RUN if [ "$(dpkg --print-architecture)" = "arm64" ]; then \
        sed -i '/^Types: deb$/a Architectures: arm64' /etc/apt/sources.list.d/ubuntu.sources && \
        printf '%s\n' \
            'Types: deb' \
            'URIs: http://archive.ubuntu.com/ubuntu/' \
            'Suites: noble noble-updates noble-backports' \
            'Components: main universe restricted multiverse' \
            'Architectures: amd64' \
            'Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg' \
            '' \
            'Types: deb' \
            'URIs: http://security.ubuntu.com/ubuntu/' \
            'Suites: noble-security' \
            'Components: main universe restricted multiverse' \
            'Architectures: amd64' \
            'Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg' \
            > /etc/apt/sources.list.d/amd64.sources && \
        dpkg --add-architecture amd64 && \
        apt-get update && \
        apt-get install -y --no-install-recommends \
            libc6:amd64 libstdc++6:amd64 zlib1g:amd64 libgcc-s1:amd64 && \
        rm -rf /var/lib/apt/lists/* ; \
    fi

# Android command-line tools (bumpable — see https://developer.android.com/studio#command-line-tools-only).
ARG CMDLINE_TOOLS=11076708
ARG CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL --proto '=https' --proto-redir '=https' "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" -o /tmp/clt.zip \
    && echo "$CMDLINE_TOOLS_SHA256  /tmp/clt.zip" | sha256sum -c - \
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
