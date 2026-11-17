@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://git.bartoostveen.nl/api/packages/bart/maven")
    }
}

pluginManagement {
    resolutionStrategy {
        repositories {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

// Car build: the Foojay toolchain-resolver plugin was removed. It only
// auto-downloads JDKs, and CI already installs JDK 25 via setup-java;
// Gradle finds that toolchain through JAVA_HOME (see gradle.properties:
// org.gradle.java.installations.fromEnv). Removing the plugin also avoids
// a flaky resolution against the Gradle Plugin Portal at settings time.
rootProject.name = "vitune"

include(":app")
include(":core:data")
include(":core:material-compat")
include(":core:ui")
include(":compose:persist")
include(":compose:preferences")
include(":compose:routing")
include(":compose:reordering")
include(":ktor-client-brotli")
include(":providers:common")
include(":providers:github")
include(":providers:innertube")
include(":providers:kugou")
include(":providers:lrclib")
include(":providers:piped")
include(":providers:sponsorblock")
include(":providers:translate")
