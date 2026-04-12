plugins {
    // Enables automatic JDK download for jvmToolchain() when the required
    // version is not locally installed (e.g. CI, fresh developer machines).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "engram-engine"
