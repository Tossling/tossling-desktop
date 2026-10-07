import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(desktopLibs.plugins.compose)
}

val appVersion = rootDir.resolve("../VERSION").readText().trim()
val packagedVersion = "1." + appVersion.substringAfter(".")

kotlin {
    jvmToolchain(jdkVersion = 21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(desktopLibs.kotlinx.coroutines.swing)
    implementation(desktopLibs.flatlaf)
    implementation(desktopLibs.jna.platform)
    implementation(desktopLibs.zxing.core)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.kopylovis.tossling.desktop.MainKt"
        javaHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }.get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf("-Xmx256m", "-XX:+UseSerialGC", "-Dtossling.version=$appVersion")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Tossling"
            packageVersion = packagedVersion
            vendor = "Tossling"
            description = "One clipboard for your computers and phone"
            modules("java.naming", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                menuGroup = "Tossling"
                perUserInstall = true
                shortcut = true
                upgradeUuid = "6f1d0c62-3d4b-4f6e-9a57-2a3f6b1c9e40"
                iconFile.set(project.file("icons/tossling.ico"))
            }
            linux {
                iconFile.set(project.file("icons/tossling.png"))
            }
        }
    }
}
