import java.util.Properties

rootProject.name = "tossling-jvm"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val local = Properties().apply { file("local.properties").takeIf { it.isFile }?.reader()?.use(::load) }
val mobile = file(local.getProperty("tossling.mobile") ?: "external/tossling-mobile")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("libs") { from(files(mobile.resolve("gradle/libs.versions.toml"))) }
        create("desktopLibs") { from(files("gradle/desktop.versions.toml")) }
    }
}

include(":protocol")
project(":protocol").projectDir = mobile.resolve("modules/common/protocol")
include(":app")
