package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.ClipMeta
import com.kopylovis.tossling.protocol.SyncJson
import com.kopylovis.tossling.protocol.crypto.ClipCipher
import com.kopylovis.tossling.protocol.crypto.sealStream
import com.kopylovis.tossling.protocol.network.NtfyAttachment
import com.kopylovis.tossling.protocol.network.NtfyEvent
import com.sun.net.httpserver.HttpServer
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

class TransfersTest {

    private val dir = Files.createTempDirectory("tossling-transfers").toFile()
    private val release = CountDownLatch(1)
    private var server: HttpServer? = null

    @After
    fun cleanUp() {
        release.countDown()
        server?.stop(0)
        dir.deleteRecursively()
        Language.choice = "auto"
    }

    private fun info(name: String, incoming: Boolean, done: Long, total: Long) = TransferInfo(id = 1, name = name, incoming = incoming, done = done, total = total)

    @Test
    fun theFractionIsTheSumOfDoneOverTheSumOfTotal() {
        val items = listOf(info("report.pdf", false, 12_000_000, 30_000_000), info("movie.mov", true, 18_000_000, 70_000_000))
        assertEquals(0.3, transferFraction(items)!!, 1e-9)
        assertNull(transferFraction(listOf(info("x", true, 5, 0))))
        assertEquals(1.0, transferFraction(listOf(info("x", true, 50, 10)))!!, 1e-9)
    }

    @Test
    fun theMenuLineAndTheTooltip() {
        Language.choice = "en"
        assertEquals("Sending report.pdf: 40%, 12.0 of 30.0 MB", transferLine(info("report.pdf", false, 12_000_000, 30_000_000)))
        assertEquals("Receiving report.pdf: 1%, 500 KB of 30.0 MB", transferLine(info("report.pdf", true, 500_000, 30_000_000)))
        assertEquals("Sending report.pdf: 0%, 0 of 30.0 MB", transferLine(info("report.pdf", false, 0, 30_000_000)))
        assertEquals("Sending report.pdf", transferLine(info("report.pdf", false, 10, 0)))
        assertEquals("Sending report.pdf: 42%", transferTip(listOf(info("report.pdf", false, 42, 100))))
        assertEquals("2 transfers: 30%", transferTip(listOf(info("a", false, 10, 50), info("b", true, 20, 50))))
        assertNull(transferTip(emptyList()))
        Language.choice = "ru"
        assertEquals("Получаю movie.mov: 25%, 17.5 из 70.0 MB", transferLine(info("movie.mov", true, 17_500_000, 70_000_000)))
        assertEquals("Получаю movie.mov: 25%", transferTip(listOf(info("movie.mov", true, 25, 100))))
    }

    @Test
    fun aCancelledTransferThrowsAtItsNextChunkAndEndingForgetsIt() {
        val transfers = Transfers()
        val transfer = transfers.start(name = "a.bin", incoming = true, total = 100)
        transfer.progress(40)
        assertEquals(40L, transfers.items.value.single().done)
        transfers.cancel(transfer.id)
        assertTrue(runCatching { transfer.progress(80) }.exceptionOrNull() is TransferCancelled)
        transfer.end()
        assertTrue(transfers.items.value.isEmpty())
        assertFalse(transfer.isCancelled)
    }

    @Test
    fun theRingFillsClockwiseFromTheTop() {
        val half = TrayGlyph.drawProgress(size = 32, fraction = 0.5)
        val alpha = { x: Int, y: Int -> half.getRGB(x, y) ushr 24 }
        assertEquals(32, half.width)
        assertTrue("the arc covers the right side", alpha(31, 16) > 150 || alpha(30, 16) > 150)
        assertTrue("the left side has only the faint track", alpha(1, 16) < 120 && alpha(0, 16) < 120)
    }

    @Test
    fun cancellingAReceivedFileLeavesNoHalfFile() {
        val key = Random.nextBytes(32)
        val cipher = ClipCipher(key)
        val body = Random.nextBytes(ClipCipher.CHUNK_SIZE * 3)
        val sealed = ByteArrayOutputStream().also { cipher.sealStream(input = body.inputStream(), output = it, size = body.size.toLong()) {} }.toByteArray()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/file/big") { exchange ->
                exchange.sendResponseHeaders(200, sealed.size.toLong())
                runCatching {
                    exchange.responseBody.use { output ->
                        output.write(sealed, 0, ClipCipher.CHUNK_SIZE + 1_000)
                        output.flush()
                        release.await(10, TimeUnit.SECONDS)
                        output.write(sealed, ClipCipher.CHUNK_SIZE + 1_000, sealed.size - ClipCipher.CHUNK_SIZE - 1_000)
                    }
                }
            }
            start()
        }
        server = http
        val origin = "http://127.0.0.1:${http.address.port}"
        val notes = CopyOnWriteArrayList<String>()
        val transfers = Transfers()
        val room = Room(
            store = SettingsStore(file = File(dir, "config.json")).apply {
                update { it.copy(server = origin, token = "tk_x", room = "tossling-r", key = Base64.getEncoder().encodeToString(key), deviceId = "me", language = "en") }
            },
            clipboard = FakeClipboard(),
            history = History(dir = dir),
            notifier = { notes.add(it) },
            stateFile = File(dir, "state.json"),
            downloads = { dir },
            transfers = transfers,
        )
        val meta = ClipMeta(kind = ClipMeta.FILE, source = "android", device = "Pixel", sender = "aaaaaaaaaaaaaaaa", fileName = "big.bin", size = body.size.toLong(), format = ClipCipher.STREAM_FORMAT, sentAt = System.currentTimeMillis(), messageId = "0123456789abcdef")
        runBlocking {
            room.handle(NtfyEvent(id = "e1", time = System.currentTimeMillis() / 1000, event = "message", topic = "tossling-r", message = cipher.sealToText(plain = SyncJson.encodeToString(ClipMeta.serializer(), meta).toByteArray()), attachment = NtfyAttachment(url = "$origin/file/big")))
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (transfers.items.value.none { it.done > 0 } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val active = transfers.items.value.single()
        assertEquals("big.bin", active.name)
        assertTrue(active.incoming)
        transfers.cancel(active.id)
        release.countDown()
        while (notes.none { "cancelled" in it } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("Receiving «big.bin» from Pixel was cancelled", notes.last())
        assertFalse(File(dir, "big.bin").exists())
        assertTrue(transfers.items.value.isEmpty())
    }
}
