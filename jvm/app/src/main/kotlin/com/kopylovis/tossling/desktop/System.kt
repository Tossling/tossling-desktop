package com.kopylovis.tossling.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object Autostart {

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE = "Tossling"

    val isSupported: Boolean get() = Platform.os == Os.WINDOWS && Platform.executable != null

    fun apply(enabled: Boolean) {
        if (!isSupported) return
        val command = "\"${Platform.executable}\""
        runCatching {
            val current = if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)) Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE) else null
            when {
                enabled && current != command -> Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE, command)
                !enabled && current != null -> Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
            }
        }.onFailure { Log.write("could not change the start at login: ${it.message}") }
    }
}

object ExplorerMenu {

    private const val KEY = "Software\\Classes\\*\\shell\\Tossling"

    fun apply() {
        val exe = Platform.executable
        if (Platform.os != Os.WINDOWS || exe == null) return
        runCatching {
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "$KEY\\command")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "", L("Отправить через Tossling", "Send via Tossling"))
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "Icon", "\"$exe\",0")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "$KEY\\command", "", "\"$exe\" --send \"%1\"")
        }.onFailure { Log.write("could not add Tossling to the Explorer menu: ${it.message}") }
    }
}

object Inbox {

    private val dir: File get() = File(Platform.home, "inbox").apply { mkdirs() }

    fun post(paths: List<String>) {
        val name = UUID.randomUUID().toString()
        val draft = File(dir, "$name.tmp")
        draft.writeText(paths.joinToString(separator = "\n"))
        draft.renameTo(File(dir, "$name.send"))
    }

    fun watch(onFiles: (List<File>) -> Unit) {
        thread(isDaemon = true, name = "tossling-inbox") {
            val watcher = runCatching { FileSystems.getDefault().newWatchService().also { dir.toPath().register(it, StandardWatchEventKinds.ENTRY_CREATE) } }.getOrNull()
            while (true) {
                drain(onFiles)
                val key = watcher?.poll(POLL_SECONDS, TimeUnit.SECONDS) ?: run {
                    if (watcher == null) Thread.sleep(POLL_SECONDS * 1000)
                    null
                }
                key?.pollEvents()
                key?.reset()
            }
        }
    }

    private fun drain(onFiles: (List<File>) -> Unit) {
        val requests = dir.listFiles { file -> file.name.endsWith(".send") }?.sortedBy { it.lastModified() }.orEmpty()
        val files = requests.flatMap { request ->
            runCatching { request.readLines() }.getOrDefault(emptyList()).also { request.delete() }
        }.filter { it.isNotBlank() }.distinct().map(::File)
        if (files.isNotEmpty()) runCatching { onFiles(files) }.onFailure { Log.write("could not send from the Explorer menu: ${it.message}") }
    }

    private const val POLL_SECONDS = 2L
}

object SingleInstance {

    private var lock: FileLock? = null

    fun acquire(): Boolean {
        val file = RandomAccessFile(File(Platform.home, "tossling.lock"), "rw")
        lock = runCatching { file.channel.tryLock() }.getOrNull()
        return lock != null
    }
}

object Qr {

    fun image(text: String, size: Int = 360): BufferedImage {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        return BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
        }
    }
}
