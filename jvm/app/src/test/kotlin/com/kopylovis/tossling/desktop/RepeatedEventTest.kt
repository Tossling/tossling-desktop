package com.kopylovis.tossling.desktop

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatedEventTest {

    @Test
    fun aRepeatedEventIsSkippedAndOnlyTheLatestAreKept() {
        val first = Room.afterHandling(handled = emptyList(), id = "a", keep = 2)!!
        val second = Room.afterHandling(handled = first, id = "b", keep = 2)!!
        assertNull(Room.afterHandling(handled = second, id = "a", keep = 2))
        assertEquals(listOf("b", "c"), Room.afterHandling(handled = second, id = "c", keep = 2))
    }

    @Test
    fun anOlderStateFileStillReads() {
        val state = Json { ignoreUnknownKeys = true }.decodeFromString(RoomState.serializer(), """{"room":"tossling-r","last_id":"x"}""")
        assertEquals(emptyList<String>(), state.handled)
    }
}
