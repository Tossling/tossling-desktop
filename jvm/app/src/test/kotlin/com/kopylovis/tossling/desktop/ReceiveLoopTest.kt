package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.ClipMeta
import com.kopylovis.tossling.protocol.SyncJson
import com.kopylovis.tossling.protocol.crypto.ClipCipher
import com.kopylovis.tossling.protocol.crypto.sealStream
import com.kopylovis.tossling.protocol.network.NtfyAttachment
import com.kopylovis.tossling.protocol.network.NtfyEvent
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The event loop keeps going, and a file on its way does not hold back texts, with attachments from a server in the test. */
class ReceiveLoopTest {

    private val dir = Files.createTempDirectory("tossling-loop").toFile()
    private val key = Random.nextBytes(32)
    private val cipher = ClipCipher(key)
    private val notes = CopyOnWriteArrayList<String>()
    private val clipboard = FakeClipboard()
    private val release = CountDownLatch(1)
    private val fileBody = Random.nextBytes(300_000)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/file/slow") { exchange ->
            release.await(10, TimeUnit.SECONDS)
            val sealed = ByteArrayOutputStream().also { cipher.sealStream(input = fileBody.inputStream(), output = it, size = fileBody.size.toLong()) {} }.toByteArray()
            exchange.sendResponseHeaders(200, sealed.size.toLong())
            exchange.responseBody.use { it.write(sealed) }
        }
        createContext("/file/huge") { exchange ->
            exchange.sendResponseHeaders(200, 3L * 1024 * 1024)
            runCatching { exchange.responseBody.use { it.write(ByteArray(1024)) } }
        }
        start()
    }
    private val origin = "http://127.0.0.1:${server.address.port}"
    private val room = Room(
        store = SettingsStore(file = File(dir, "config.json")).apply {
            update { it.copy(server = origin, token = "tk_x", room = "tossling-r", key = Base64.getEncoder().encodeToString(key), deviceId = "me", language = "en") }
        },
        clipboard = clipboard,
        history = History(dir = dir),
        notifier = { notes.add(it) },
        stateFile = File(dir, "state.json"),
        downloads = { dir },
    )
    private var events = 0

    @After
    fun cleanUp() {
        release.countDown()
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun meta(kind: String, text: String? = null, fileName: String? = null, size: Long? = null) = ClipMeta(
        kind = kind, source = "android", device = "Pixel", sender = "aaaaaaaaaaaaaaaa", text = text, fileName = fileName, size = size,
        format = if (kind == ClipMeta.FILE) ClipCipher.STREAM_FORMAT else null, sentAt = System.currentTimeMillis(), messageId = "%016x".format(Random.nextLong()),
    )

    private fun receive(meta: ClipMeta, attachment: String? = null) = runBlocking {
        val message = cipher.sealToText(plain = SyncJson.encodeToString(ClipMeta.serializer(), meta).toByteArray())
        room.handle(NtfyEvent(id = "event${events++}", time = System.currentTimeMillis() / 1000, event = "message", topic = "tossling-r", message = message, attachment = attachment?.let { NtfyAttachment(url = "$origin$it") }))
    }

    @Test
    fun theLoopOutlivesAnOutOfMemoryErrorAndAStrayCancellation() = runBlocking {
        val channel = Channel<NtfyEvent>(capacity = Channel.UNLIMITED)
        listOf("oom", "cancel", "fail", "ok").forEach { channel.trySend(NtfyEvent(id = it)) }
        channel.close()
        val handled = mutableListOf<String>()
        Room.receiveEach(events = channel) { event ->
            handled += event.id
            when (event.id) {
                "oom" -> throw OutOfMemoryError("Java heap space")
                "cancel" -> throw CancellationException("a timeout inside the work")
                "fail" -> error("broken")
            }
        }
        assertEquals(listOf("oom", "cancel", "fail", "ok"), handled)
    }

    @Test
    fun aFileOnItsWayDoesNotHoldBackText() {
        val started = System.currentTimeMillis()
        receive(meta(kind = ClipMeta.FILE, fileName = "movie.bin", size = fileBody.size.toLong()), attachment = "/file/slow")
        assertTrue("handing over a file does not wait for its download", System.currentTimeMillis() - started < 3_000)
        receive(meta(kind = ClipMeta.TEXT, text = "while the file comes"))
        assertEquals(Clip.Text("while the file comes"), clipboard.current)
        release.countDown()
        val file = File(dir, "movie.bin")
        val deadline = System.currentTimeMillis() + 10_000
        while (notes.none { it.contains("movie.bin") && it.contains("ready to paste") } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(file.readBytes().contentEquals(fileBody))
    }

    @Test
    fun anAttachmentOverTheLimitForItsKindIsSkipped() {
        receive(meta(kind = ClipMeta.TEXT), attachment = "/file/huge")
        assertNull(clipboard.current)
        assertFalse(notes.any { it.contains("ready to paste") })
    }
}
