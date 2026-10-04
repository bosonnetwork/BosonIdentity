# Consumer R8 rules for the Boson stack (Vert.x / Netty / Jackson / BouncyCastle / sqlite-jdbc).
# Finalized in M6-9. This stack is reflection- and ServiceLoader-heavy and a stripped/renamed class
# surfaces only as a runtime crash, so we keep it wholesale and prefer correctness over shrink size.

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Boson client + its Vert.x/Netty runtime.
-keep class io.bosonnetwork.** { *; }
-keep class io.vertx.** { *; }
-keep class io.netty.** { *; }
-dontwarn io.vertx.**
-dontwarn io.netty.**

# Jackson (Boson encodes message content / CBOR via reflection over model classes).
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
}
-dontwarn com.fasterxml.jackson.**

# BouncyCastle (bcprov): the Boson crypto provider (Ed25519 / crypto_box, the default backend) loads
# algorithm implementations reflectively by class name, so keep the whole tree. (The BCJSSE TLS stack
# is no longer bundled - services present ECDSA certs that Conscrypt handles natively.)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Slf4j / logback / sqlite are referenced by the stack but not all present on Android.
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn org.sqlite.**

# Caffeine is compile-referenced by core-api (VertxCaffeine) and identifier (CachedResolver /
# CachedCryptoIdentity) but does NOT run on Android - the mobile node=null path uses the in-client
# AsyncCache instead, so these references are never reached. (See project notes on the Caffeine swap.)
-dontwarn com.github.benmanes.caffeine.cache.**

# bucket4j backs core-api's shared RateLimiter (promoted to the common API in Boson e556beb), which
# only the services and the Director ever construct - the client never touches it. It is optional in
# core-api's pom, so the jar is absent here while RateLimiter.class still references it, and R8
# resolves every reference in its program input before deciding what is live.
-dontwarn io.github.bucket4j.**

# Reactor BlockHound (optional debug SPI) and JDK9+ ProcessHandle (ApplicationLock, desktop-only) are
# referenced but not on the mobile path.
-dontwarn reactor.blockhound.**
-dontwarn java.lang.ProcessHandle
