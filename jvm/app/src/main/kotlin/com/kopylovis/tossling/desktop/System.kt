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

    val isSupported: Boolean get() = Platform.os != Os.MAC && Platform.executable != null

    fun apply(enabled: Boolean) {
        if (!isSupported) return
        if (Platform.os == Os.LINUX) return applyLinux(enabled)
        val command = "\"${Platform.executable}\""
        runCatching {
            val current = if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)) Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE) else null
            when {
                enabled && current != command -> Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE, command)
                !enabled && current != null -> Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
            }
        }.onFailure { Log.write("could not change the start at login: ${it.message}") }
    }

    private fun applyLinux(enabled: Boolean) {
        val file = File(Platform.xdgConfig, "autostart/tossling.desktop")
        runCatching {
            if (!enabled) {
                file.delete()
                return
            }
            val entry = listOf(
                "[Desktop Entry]",
                "Type=Application",
                "Name=Tossling",
                "Exec=\"${Platform.executable}\"",
                "TryExec=${Platform.executable}",
                "X-GNOME-Autostart-enabled=true",
            ).joinToString(separator = "\n", postfix = "\n")
            if (file.isFile && file.readText() == entry) return
            file.parentFile.mkdirs()
            file.writeText(entry)
        }.onFailure { Log.write("could not change the start at login: ${it.message}") }
    }
}

object ExplorerMenu {

    private val KEYS = listOf("Software\\Classes\\*\\shell\\Tossling", "Software\\Classes\\Directory\\shell\\Tossling")

    fun apply() {
        val exe = Platform.executable ?: return
        if (Platform.os == Os.LINUX) return applyLinux(exe)
        if (Platform.os != Os.WINDOWS) return
        runCatching {
            KEYS.forEach { key ->
                Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "$key\\command")
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "", L("Отправить через Tossling", "Send via Tossling"))
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "Icon", "\"$exe\",0")
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "$key\\command", "", "\"$exe\" --send \"%1\"")
            }
        }.onFailure { Log.write("could not add Tossling to the Explorer menu: ${it.message}") }
    }

    private fun applyLinux(exe: String) {
        val label = L("Отправить через Tossling", "Send via Tossling")
        val data = Platform.xdgData
        val scripts = listOf(File(data, "nautilus/scripts"), File(Platform.xdgConfig, "caja/scripts"))
        val helper = File(data, "tossling/send")
        val files = LinuxFiles(
            autostart = File(Platform.xdgConfig, "autostart/tossling.desktop"),
            scripts = scripts,
            entries = listOf(File(data, "nemo/actions/tossling.nemo_action"), File(data, "kio/servicemenus/tossling.desktop"), File(data, "kservices5/ServiceMenus/tossling.desktop"), helper),
            thunar = File(Platform.xdgConfig, "Thunar/uca.xml"),
        )
        runCatching {
            val script = sendScript(exe = exe, files = files)
            write(helper, script, executable = true)
            scripts.forEach { dir ->
                dir.listFiles { file -> file.isFile && file.name != label && file.readText().let { it.contains(SCRIPT_MARK) || (it.contains("--send") && it.contains(exe)) } }?.forEach { it.delete() }
                write(File(dir, label), script, executable = true)
            }
            write(
                File(data, "nemo/actions/tossling.nemo_action"),
                "[Nemo Action]\nName=$label\nExec=\"$exe\" --send %F\nIcon-Name=document-send\nSelection=notnone\nExtensions=any;\nDependencies=$exe;\n",
            )
            val service = "[Desktop Entry]\nType=Service\nMimeType=application/octet-stream;inode/directory;\nActions=send\nTryExec=$exe\nX-KDE-ServiceTypes=KonqPopupMenu/Plugin\n\n" +
                "[Desktop Action send]\nName=$label\nIcon=document-send\nExec=\"$exe\" --send %F\n"
            write(File(data, "kio/servicemenus/tossling.desktop"), service, executable = true)
            write(File(data, "kservices5/ServiceMenus/tossling.desktop"), service)
            thunarAction(command = helper.absolutePath, label = label)
        }.onFailure { Log.write("could not add Tossling to the file manager menu: ${it.message}") }
    }

    class LinuxFiles(val autostart: File, val scripts: List<File>, val entries: List<File>, val thunar: File)

    fun sendScript(exe: String, files: LinuxFiles): String {
        fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"
        return listOf(
            "#!/bin/sh",
            "# $SCRIPT_MARK",
            "if [ -x ${quote(exe)} ]; then exec ${quote(exe)} --send \"$@\"; fi",
            "grep -qsF ${quote(exe)} ${quote(files.autostart.path)} && rm -f ${quote(files.autostart.path)}",
            files.scripts.joinToString(separator = "\n") { dir -> "grep -lsF $SCRIPT_MARK ${quote(dir.path)}/* | while IFS= read -r f; do rm -f \"\$f\"; done" },
            "rm -f " + files.entries.joinToString(separator = " ") { quote(it.path) },
            "uca=${quote(files.thunar.path)}",
            "if [ -f \"\$uca\" ]; then awk '/<action>/ { block = \"\"; inside = 1 } inside { block = block \$0 \"\\n\"; if (/<\\/action>/) { inside = 0; if (block !~ /$THUNAR_ID/) printf \"%s\", block } next } { print }' \"\$uca\" > \"\$uca.tmp\" && mv \"\$uca.tmp\" \"\$uca\"; fi",
        ).joinToString(separator = "\n", postfix = "\n")
    }

    private fun thunarAction(command: String, label: String) {
        val file = File(Platform.xdgConfig, "Thunar/uca.xml")
        if (!file.parentFile.isDirectory) return
        val action = listOf(
            "<action>",
            "\t<icon>document-send</icon>",
            "\t<name>$label</name>",
            "\t<unique-id>$THUNAR_ID</unique-id>",
            "\t<command>&quot;$command&quot; %F</command>",
            "\t<description>$label</description>",
            "\t<patterns>*</patterns>",
            "\t<directories/>",
            "\t<audio-files/>",
            "\t<image-files/>",
            "\t<other-files/>",
            "\t<text-files/>",
            "\t<video-files/>",
            "</action>",
        ).joinToString(separator = "\n")
        val current = if (file.isFile) file.readText() else "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<actions>\n</actions>\n"
        val updated = withThunarAction(xml = current, action = action) ?: return
        if (updated != current) file.writeText(updated)
    }

    fun withThunarAction(xml: String, action: String): String? {
        val without = xml.replace(Regex("<action>(?:(?!</action>).)*?<unique-id>$THUNAR_ID</unique-id>.*?</action>\\s*", RegexOption.DOT_MATCHES_ALL), "")
        if (!without.contains("</actions>")) return null
        return without.replace("</actions>", "$action\n</actions>")
    }

    private const val THUNAR_ID = "tossling-send"
    private const val SCRIPT_MARK = "tossling-send-helper"

    private fun write(file: File, text: String, executable: Boolean = false) {
        if (!file.isFile || file.readText() != text) {
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        if (executable) file.setExecutable(true, true)
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

    fun requestClipboard() {
        File(dir, "${UUID.randomUUID()}.clip").createNewFile()
    }

    fun watch(onFiles: (List<File>) -> Unit, onClipboard: () -> Unit) {
        thread(isDaemon = true, name = "tossling-inbox") {
            val watcher = runCatching { FileSystems.getDefault().newWatchService().also { dir.toPath().register(it, StandardWatchEventKinds.ENTRY_CREATE) } }.getOrNull()
            while (true) {
                drain(onFiles)
                dir.listFiles { file -> file.name.endsWith(".clip") }?.takeIf { it.isNotEmpty() }?.let { requests ->
                    requests.forEach { it.delete() }
                    runCatching(onClipboard).onFailure { Log.write("could not send the clipboard: ${it.message}") }
                }
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
