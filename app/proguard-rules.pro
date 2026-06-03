# ha-paneld ProGuard/R8 rules.
# Release build ships unminified for v0.x (isMinifyEnabled = false); these rules are kept
# so enabling minification later is a one-flag change.

# Netty (transitive via HiveMQ) reflects heavily and references optional codecs.
-dontwarn io.netty.**
-keep class io.netty.** { *; }

# HiveMQ client.
-dontwarn com.hivemq.**
-keep class com.hivemq.** { *; }

# Ktor / kotlinx.coroutines.
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-keep class io.ktor.** { *; }

# JmDNS.
-dontwarn javax.jmdns.**
-keep class javax.jmdns.** { *; }

# SLF4J optional bindings.
-dontwarn org.slf4j.**
