plugins {
    // Скачивает JDK 25 для jvmToolchain(25), если подходящей нет на машине.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "flowtrace"
