plugins {
    // JDK 21 is pre-installed; foojay resolver disabled to avoid IBM_SEMERU
    // compatibility issue with Gradle 9 (foojay ≤0.9.0 references removed enum constant).
    // id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "engram-engine"
