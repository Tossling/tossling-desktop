package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.Member
import com.kopylovis.tossling.protocol.SyncJson
import com.kopylovis.tossling.protocol.crypto.DeviceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class RemovalTest {

    private val dir = Files.createTempDirectory("tossling-removal").toFile()
    private val key = DeviceIdentity.generate().publicText
    private val notes = CopyOnWriteArrayList<String>()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun room(vararg members: Member): Room {
        val state = RoomState(room = "tossling-r", members = members.associateBy { it.id })
        File(dir, "state.json").writeText(SyncJson.encodeToString(RoomState.serializer(), state))
        // Port 9 on localhost: anything that reached the network would fail instead of the checks.
        val store = SettingsStore(file = File(dir, "config.json")).apply {
            update { it.copy(server = "http://127.0.0.1:9", token = "tk_x", room = "tossling-r", key = "a2V5", deviceId = "me", owner = "me", language = "en") }
        }
        return Room(store = store, clipboard = FakeClipboard(), history = History(dir = dir), notifier = { notes.add(it) }, stateFile = File(dir, "state.json"), downloads = { dir })
    }

    private fun member(id: String, name: String, pk: String = key, keyChanged: Long = 0) = Member(id = id, name = name, source = "android", seen = 1, pk = pk, keyChanged = keyChanged)

    private fun refusal(room: Room, id: String, accepting: Set<String> = emptySet()): String =
        runBlocking { runCatching { room.revoke(id = id, accepting = accepting) }.exceptionOrNull()?.message.orEmpty() }

    @Test
    fun aRemainingDeviceWithoutAKeyStopsTheRemovalAndIsNamed() {
        val room = room(member("me", "Laptop"), member("gone", "Old PC"), member("old", "Pixel", pk = ""), member("ok", "iPhone"), member("legacy-android-Tab", "Tab", pk = ""))
        assertEquals(listOf("old"), room.withoutKey(id = "gone").map { it.id })
        assertEquals("Did not disconnect Old PC: no key from Pixel. Open Tossling there or update it", refusal(room, "gone"))
        assertEquals("tossling-r", room.roomState.value.room)
    }

    @Test
    fun theRemovedDeviceAndThisOneDoNotStopARemoval() {
        val room = room(member("me", "Laptop", pk = ""), member("gone", "Old PC", pk = ""), member("ok", "iPhone"))
        assertTrue(room.withoutKey(id = "gone").isEmpty())
    }

    @Test
    fun aChangedKeyStopsTheRemovalUntilTheUserAcceptsIt() {
        val room = room(member("me", "Laptop"), member("gone", "Old PC"), member("moved", "Pixel", keyChanged = 5))
        assertEquals(listOf("moved"), room.keyChangedAmong(id = "gone").map { it.id })
        assertEquals("Did not disconnect Old PC: the key of Pixel changed, try again", refusal(room, "gone"))
        room.acceptKey(id = "moved")
        assertEquals(0L, room.roomState.value.members.getValue("moved").keyChanged)
        assertTrue(room.keyChangedAmong(id = "gone").isEmpty())
    }
}
