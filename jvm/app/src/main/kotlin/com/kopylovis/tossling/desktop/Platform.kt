package com.kopylovis.tossling.desktop

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import java.io.File
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Os { WINDOWS, MAC, LINUX }

object Platform {

    val os: Os = System.getProperty("os.name").orEmpty().lowercase().let { name ->
        when {
            name.startsWith("windows") -> Os.WINDOWS
            name.startsWith("mac") -> Os.MAC
            else -> Os.LINUX
        }
    }

    val source: String = when (os) {
        Os.WINDOWS -> "windows"
        Os.MAC -> "mac"
        Os.LINUX -> "linux"
    }

    val version: String = System.getProperty("tossling.version") ?: "dev"

    val home: File by lazy {
        val custom = System.getenv("TOSSLING_HOME")?.takeIf { it.isNotBlank() }
        val dir = when {
            custom != null -> File(custom)
            os == Os.WINDOWS -> File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Tossling")
            os == Os.MAC -> File(System.getProperty("user.home"), "Library/Application Support/Tossling JVM")
            else -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.config", "tossling")
        }
        dir.apply { mkdirs() }
    }

    val cache: File by lazy { File(home, "cache").apply { mkdirs() } }

    val downloads: File
        get() {
            val base = if (os == Os.WINDOWS) {
                runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads)) }.getOrNull()
            } else {
                null
            } ?: File(System.getProperty("user.home"), "Downloads")
            return File(base, "Tossling").apply { mkdirs() }
        }

    val computerName: String by lazy {
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Computer"
    }

    val executable: String? get() = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }
}

object Language {

    @Volatile var choice: String = "auto"

    val russian: Boolean
        get() = when (choice) {
            "ru" -> true
            "en" -> false
            else -> Locale.getDefault().language == "ru"
        }
}

fun L(ru: String, en: String): String = if (Language.russian) ru else en

object Log {

    private val file by lazy { File(Platform.home, "tossling.log") }
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Synchronized
    fun write(text: String) {
        val line = "${LocalDateTime.now().format(stamp)} $text"
        System.err.println(line)
        runCatching {
            if (file.length() > MAX_SIZE) file.renameTo(File(file.parentFile, "tossling.old.log"))
            file.appendText(line + "\n")
        }
    }

    private const val MAX_SIZE = 1_000_000L
}
