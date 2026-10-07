package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.SyncJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsTest {

    private val dir: File = Files.createTempDirectory("tossling-settings").toFile().apply { deleteOnExit() }

    @Test
    fun usesTheKeysOfTheMacConfig() {
        val mac = """{"server":"https://s","token":"tk","room":"tossling-r","key":"k","device_id":"d","owner":"d","paused_until":1.5,"images":false,"aliases":{"x":"Phone"},"legacy_to_mac":""}"""
        val settings = SyncJson.decodeFromString(Settings.serializer(), mac)
        assertEquals("d", settings.deviceId)
        assertEquals(1.5, settings.pausedUntil, 0.0)
        assertFalse(settings.images)
        assertEquals("Phone", settings.aliases["x"])
        assertTrue(settings.isOwner)
        val written = Json.parseToJsonElement(SyncJson.encodeToString(Settings.serializer(), settings)).jsonObject
        assertEquals("d", written["device_id"]?.jsonPrimitive?.content)
        assertEquals("1.5", written["paused_until"]?.jsonPrimitive?.content)
    }

    @Test
    fun keepsChangesOnDisk() {
        val file = File(dir, "config.json")
        SettingsStore(file = file).update { it.copy(server = "https://s", room = "tossling-r", key = "k", deviceId = "d") }
        val again = SettingsStore(file = file).settings.value
        assertTrue(again.isConfigured)
        assertEquals("https://s", again.server)
    }

    @Test
    fun readsTheJoinCommand() {
        assertEquals("tossling.example.com" to "7KQ2-M9XD", Setup.parseJoin("tossling join tossling.example.com/7KQ2-M9XD"))
        assertEquals("http://127.0.0.1:8090" to "7kq2m9xd", Setup.parseJoin(" http://127.0.0.1:8090/7kq2m9xd "))
        assertNull(Setup.parseJoin("tossling.example.com"))
        assertNull(Setup.parseJoin("tossling.example.com/123"))
        assertEquals("https://tossling.example.com", Setup.serverOf("tossling.example.com/"))
        assertEquals("http://127.0.0.1:8090", Setup.serverOf("http://127.0.0.1:8090"))
    }

    @Test
    fun namesReceivedFilesSafely() {
        assertEquals("a_b.txt", safeName("../x/a:b.txt"))
        assertEquals("evil.exe", safeName("C:\\Windows\\evil.exe"))
        assertEquals("file", safeName("  "))
        File(dir, "report.pdf").writeText("x")
        assertEquals("report 2.pdf", uniqueFile(dir = dir, name = "report.pdf").name)
        assertEquals("notes", uniqueFile(dir = dir, name = "notes").name)
    }

    @Test
    fun keepsTheLastThirtyItems() {
        val history = History(dir = File(dir, "history-test"))
        repeat(times = 35) { history.addText(text = "item $it", incoming = true, device = "Mac") }
        assertEquals(30, history.items.value.size)
        assertEquals("item 34", history.items.value.first().text)
    }

    @Test
    fun pairingCodeMatchesWhatThePhoneReads() {
        val store = SettingsStore(file = File(dir, "pair.json"))
        store.update { it.copy(server = "https://s", token = "tk", room = "tossling-r", key = "a2V5", deviceId = "d1", owner = "d1", name = "Laptop") }
        val room = Room(store = store, clipboard = FakeClipboard(), history = History(dir = File(dir, "pair-history")), notifier = {}, stateFile = File(dir, "pair-state.json"))
        val payload = Json.parseToJsonElement(room.pairingPayload()).jsonObject
        assertEquals(listOf("id", "k", "n", "o", "r", "s", "t", "v"), payload.keys.toList())
        assertEquals("2", payload.getValue("v").jsonPrimitive.content)
        assertEquals("Laptop", payload.getValue("n").jsonPrimitive.content)
    }
}
