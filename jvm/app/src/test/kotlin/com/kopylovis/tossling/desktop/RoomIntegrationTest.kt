package com.kopylovis.tossling.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class RoomIntegrationTest {

    private val invite = System.getenv("TOSSLING_IT_INVITE").orEmpty()
    private val macHelper = System.getenv("TOSSLING_IT_MAC_HELPER").orEmpty()
    private val macConfig = System.getenv("TOSSLING_IT_MAC_CONFIG").orEmpty()

    private class Device(val dir: File, store: SettingsStore? = null) {
        val store = store ?: SettingsStore(file = File(dir, "config.json"))
        val clipboard = FakeClipboard()
        val notes = CopyOnWriteArrayList<String>()
        val downloads = File(dir, "downloads").apply { mkdirs() }
        val history = History(dir = dir)
        val room = Room(store = this.store, clipboard = clipboard, history = history, notifier = { notes.add(it) }, stateFile = File(dir, "state.json"), downloads = { downloads })
    }

    @Test
    fun aWindowsComputerJoinsAMacRoomAndSharesTheClipboard() = runBlocking {
        assumeTrue("set TOSSLING_IT_INVITE to run against a live server", invite.isNotEmpty())
        val root = Files.createTempDirectory("tossling-it").toFile()

        val laptop = Device(dir = File(root, "laptop").apply { mkdirs() })
        val inviter = Setup(store = laptop.store).join(input = invite)
        assertTrue(inviter.isNotEmpty())
        val joined = laptop.store.settings.value
        assertTrue(joined.isConfigured)
        assertTrue(joined.room.startsWith("tossling-"))
        log("joined the room of $inviter")

        val desktopDir = File(root, "desktop").apply { mkdirs() }
        File(laptop.dir, "config.json").copyTo(File(desktopDir, "config.json"))
        val desktop = Device(dir = desktopDir).apply { store.update { it.copy(deviceId = "d2d2d2d2d2d2d2d2", name = "Desktop", identity = "") } }

        laptop.room.start()
        desktop.room.start()
        waitFor("both connected") { laptop.room.connected.value && desktop.room.connected.value }
        waitFor("they see each other") {
            laptop.room.roomState.value.members.containsKey("d2d2d2d2d2d2d2d2") && desktop.room.roomState.value.members.containsKey(joined.deviceId)
        }

        desktop.clipboard.copy(Clip.Text("short text from the desktop"))
        waitFor("short text arrives") { (laptop.clipboard.current as? Clip.Text)?.text == "short text from the desktop" }

        val long = "long text ".repeat(1_000)
        laptop.clipboard.copy(Clip.Text(long))
        waitFor("long text arrives as an attachment") { (desktop.clipboard.current as? Clip.Text)?.text == long }
        delay(1_500)
        assertEquals("no echo back to the laptop", long, (laptop.clipboard.current as Clip.Text).text)
        assertEquals("the laptop did not get its own text", 1, laptop.clipboard.written.count { it is Clip.Text })

        val png = Room::class.java.getResourceAsStream("/tray.png")!!.readBytes()
        desktop.clipboard.copy(Clip.Image(bytes = png, mime = "image/png"))
        waitFor("image arrives") { (laptop.clipboard.current as? Clip.Image)?.bytes?.contentEquals(png) == true }

        laptop.clipboard.copy(Clip.Text("secret password"), isPrivate = true)
        delay(2_000)
        assertNotEquals("a password is not sent", "secret password", (desktop.clipboard.current as? Clip.Text)?.text)

        val payload = File(root, "payload.bin").apply { writeBytes(ByteArray(2_500_000) { (it * 31).toByte() }) }
        laptop.room.sendFiles(listOf(payload))
        waitFor("file arrives in downloads") { File(desktop.downloads, "payload.bin").length() == payload.length() }
        assertArrayEquals(payload.readBytes(), File(desktop.downloads, "payload.bin").readBytes())
        waitFor("the file is on the clipboard") { (desktop.clipboard.current as? Clip.Files)?.files?.single()?.name == "payload.bin" }

        if (macHelper.isNotEmpty()) {
            val text = File(root, "mac.txt").apply { writeText("hello from the Mac helper") }
            val process = ProcessBuilder(macHelper, "--config", macConfig, "--text", text.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
            waitFor("the Mac's text arrives") { (laptop.clipboard.current as? Clip.Text)?.text == "hello from the Mac helper" }
            log("Mac → JVM ok")
        }

        val oldRoom = laptop.store.settings.value.room
        val removed = CopyOnWriteArrayList<String>()
        val watcher = launch(Dispatchers.IO) { desktop.room.removed.collect { removed.add(it) } }
        laptop.room.revoke(id = "d2d2d2d2d2d2d2d2")
        waitFor("the desktop is removed") { removed.isNotEmpty() && !desktop.store.settings.value.isConfigured }
        watcher.cancel()
        assertNotEquals(oldRoom, laptop.store.settings.value.room)
        waitFor("the laptop is back online in the new room") { laptop.room.connected.value }
        log("revoke ok: ${laptop.notes}")

        laptop.room.stop()
        desktop.room.stop()
        root.deleteRecursively()
        Unit
    }

    private suspend fun waitFor(what: String, check: () -> Boolean) {
        withTimeout(TIMEOUT_MS) { while (!check()) delay(100) }
        log("ok $what")
    }

    private fun log(text: String) = println("[it] $text")

    private companion object {
        private const val TIMEOUT_MS = 20_000L
    }
}
