package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.ClipMeta
import com.kopylovis.tossling.protocol.SyncJson
import com.kopylovis.tossling.protocol.crypto.ClipCipher
import com.kopylovis.tossling.protocol.network.NtfyEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** Inline text goes through [Room.handle] without a server: replays, freshness and secret content. */
class ReceivingTest {

    private val dir = Files.createTempDirectory("tossling-receiving").toFile()
    private val key = Random.nextBytes(32)
    private val cipher = ClipCipher(key)
    private val notes = CopyOnWriteArrayList<String>()
    private val clipboard = FakeClipboard()
    private val history = History(dir = dir)
    private val room = Room(
        store = SettingsStore(file = File(dir, "config.json")).apply {
            update { it.copy(server = "http://127.0.0.1:9", token = "tk_x", room = "tossling-r", key = Base64.getEncoder().encodeToString(key), deviceId = "me", language = "en") }
        },
        clipboard = clipboard,
        history = history,
        notifier = { notes.add(it) },
        stateFile = File(dir, "state.json"),
        downloads = { dir },
    )
    private var events = 0

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun text(text: String, mid: String = "%016x".format(Random.nextLong()), sentAt: Long? = System.currentTimeMillis(), secret: Boolean = false) =
        ClipMeta(kind = ClipMeta.TEXT, source = "android", device = "Pixel", sender = "aaaaaaaaaaaaaaaa", text = text, sentAt = sentAt, messageId = mid, sensitive = secret.takeIf { it })

    private fun receive(meta: ClipMeta) = runBlocking {
        val message = cipher.sealToText(plain = SyncJson.encodeToString(ClipMeta.serializer(), meta).toByteArray())
        room.handle(NtfyEvent(id = "event${events++}", time = System.currentTimeMillis() / 1000, event = "message", topic = "tossling-r", message = message))
    }

    @Test
    fun plainTextGoesToTheClipboardAndTheHistory() {
        receive(text("hello"))
        assertEquals(Clip.Text("hello"), clipboard.current)
        assertTrue(clipboard.secrets.isEmpty())
        assertEquals(1, history.items.value.size)
    }

    @Test
    fun aSecretIsMarkedKeptOutOfTheHistoryAndNotShown() {
        receive(text("hunter2", secret = true))
        assertEquals(listOf<Clip>(Clip.Text("hunter2")), clipboard.secrets.toList())
        assertTrue(history.items.value.isEmpty())
        assertEquals(listOf("From Pixel: secret text, ready to paste"), notes.toList())
    }

    @Test
    fun aMessageHandledAlreadyIsSkippedEvenUnderAnotherEvent() {
        receive(text("first", mid = "0123456789abcdef"))
        receive(text("second", mid = "0123456789abcdef"))
        assertEquals(Clip.Text("first"), clipboard.current)
        val again = Room(store = SettingsStore(file = File(dir, "config.json")), clipboard = clipboard, history = history, notifier = { notes.add(it) }, stateFile = File(dir, "state.json"), downloads = { dir })
        runBlocking {
            again.handle(NtfyEvent(id = "after-restart", time = System.currentTimeMillis() / 1000, event = "message", topic = "tossling-r", message = cipher.sealToText(plain = SyncJson.encodeToString(ClipMeta.serializer(), text("third", mid = "0123456789abcdef")).toByteArray())))
        }
        assertEquals("the handled mids survive a restart", Clip.Text("first"), clipboard.current)
    }

    @Test
    fun contentSealedLongAgoOrFarAheadIsSkipped() {
        receive(text("replayed", sentAt = System.currentTimeMillis() - 16 * 60_000))
        receive(text("ahead", sentAt = System.currentTimeMillis() + 6 * 60_000))
        assertEquals(null, clipboard.current)
        receive(text("older sender", sentAt = null))
        assertEquals(Clip.Text("older sender"), clipboard.current)
    }
}
