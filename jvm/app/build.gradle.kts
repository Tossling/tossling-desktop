import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(desktopLibs.plugins.compose)
}

val appVersion = rootDir.resolve("../VERSION").readText().trim()

kotlin {
    jvmToolchain(jdkVersion = 21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(desktopLibs.kotlinx.coroutines.swing)
    implementation(desktopLibs.dbus.java.core)
    implementation(desktopLibs.dbus.java.unixsocket)
    implementation(desktopLibs.flatlaf)
    implementation(desktopLibs.jna.platform)
    implementation(desktopLibs.zxing.core)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.kopylovis.tossling.desktop.MainKt"
        javaHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }.get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf("-Xmx256m", "-XX:+UseSerialGC", "-Dtossling.version=$appVersion", "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Tossling"
            packageVersion = appVersion
            vendor = "Tossling"
            description = "One clipboard for your computers and phone"
            modules("java.naming", "jdk.crypto.ec", "jdk.security.auth", "jdk.unsupported")
            windows {
                menuGroup = "Tossling"
                perUserInstall = true
                shortcut = true
                upgradeUuid = "6f1d0c62-3d4b-4f6e-9a57-2a3f6b1c9e40"
                iconFile.set(project.file("icons/tossling.ico"))
            }
            linux {
                packageName = "tossling"
                shortcut = true
                menuGroup = "Utility"
                appCategory = "Utility"
                iconFile.set(project.file("icons/tossling.png"))
            }
        }
    }
}

val windowsInstaller = tasks.register("windowsInstallerResources") {
    val template = layout.projectDirectory.file("wix/main.wxs")
    val target = layout.buildDirectory.dir("wix")
    val version = appVersion
    inputs.file(template)
    inputs.property("version", version)
    outputs.dir(target)
    doLast {
        val dir = target.get().asFile.apply { mkdirs() }
        template.asFile.copyTo(dir.resolve("main.wxs"), overwrite = true)
        dir.resolve("overrides.wxi").writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<Include>\n  <?define TosslingVersion = \"$version\" ?>\n</Include>\n")
    }
}

if (System.getProperty("os.name").startsWith("Windows")) {
    tasks.register<Exec>("windowsRelease") {
        dependsOn("createDistributable", windowsInstaller, rootProject.tasks.named("unzipWix"))
        val image = layout.buildDirectory.dir("compose/binaries/main/app/Tossling").get().asFile
        val resources = layout.buildDirectory.dir("wix").get().asFile
        val output = layout.buildDirectory.dir("release").get().asFile
        val wix = rootProject.layout.buildDirectory.dir("wix311").get().asFile
        val jpackage = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }.get().metadata.installationPath.file("bin/jpackage.exe").asFile
        val version = appVersion
        executable = jpackage.absolutePath
        args(
            "--type", "msi",
            "--app-image", image.absolutePath,
            "--name", "Tossling",
            "--app-version", version,
            "--vendor", "Tossling",
            "--description", "One clipboard for your computers and phone",
            "--resource-dir", resources.absolutePath,
            "--dest", output.absolutePath,
            "--win-per-user-install",
            "--win-menu",
            "--win-menu-group", "Tossling",
            "--win-shortcut",
            "--win-upgrade-uuid", "6f1d0c62-3d4b-4f6e-9a57-2a3f6b1c9e40",
        )
        environment("PATH", wix.absolutePath + File.pathSeparator + System.getenv("PATH"))
        doFirst { output.deleteRecursively() }
        doLast {
            val built = output.listFiles { file -> file.extension == "msi" }.orEmpty().single()
            built.renameTo(output.resolve("Tossling-$version.msi"))
        }
    }
}
