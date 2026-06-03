# Hermetic Android build image for ha-paneld.
#
# Lets anyone build the APK with only Docker installed — no local JDK / Android SDK / NDK / CMake.
# Versions are pinned to match .github/workflows/*.yml so a local build reproduces CI exactly.
# Used by tools/build/build.sh.
FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Android command-line tools (12.0 / 11076708).
RUN mkdir -p /opt/android-sdk/cmdline-tools \
 && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/clt.zip \
 && unzip -q /tmp/clt.zip -d /tmp/clt \
 && mv /tmp/clt/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
 && rm -rf /tmp/clt.zip /tmp/clt

# SDK / NDK / CMake — same versions the GitHub CI installs (the NDK + CMake build the
# clean-room /dev/ledjni LED driver under app/src/main/cpp).
RUN yes | sdkmanager --licenses >/dev/null \
 && sdkmanager \
      "platform-tools" \
      "platforms;android-35" \
      "build-tools;35.0.0" \
      "ndk;27.0.12077973" \
      "cmake;3.22.1" >/dev/null

WORKDIR /work
