package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.ClipMeta
import com.kopylovis.tossling.protocol.HandledMessages
import com.kopylovis.tossling.protocol.Member
import com.kopylovis.tossling.protocol.crypto.DeviceIdentity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {

    private val now = 1_791_360_000_000L
    private val keyA = DeviceIdentity.generate().publicText
    private val keyB = DeviceIdentity.generate().publicText
    private val hello = ClipMeta(kind = ClipMeta.HELLO, source = "android", device = "Pixel", sender = "aaaaaaaaaaaaaaaa")

    @Test
    fun aMessageHandledInTheLastThreeHoursIsSkipped() {
        val first = Room.afterMessage(handled = "", mid = "0123456789abcdef", nowMs = now)!!
        assertNull(Room.afterMessage(handled = first, mid = "0123456789abcdef", nowMs = now + 2 * 60 * 60_000L))
        assertNotNull(Room.afterMessage(handled = first, mid = "0123456789abcdef", nowMs = now + HandledMessages.WINDOW_MS))
        assertNotNull(Room.afterMessage(handled = first, mid = "fedcba9876543210", nowMs = now))
    }

    @Test
    fun anOlderStateFileHasNoMids() {
        val state = Json { ignoreUnknownKeys = true }.decodeFromString(RoomState.serializer(), """{"room":"tossling-r","last_id":"x","handled":["x"]}""")
        assertEquals("", state.mids)
    }

    @Test
    fun everyMessageIsStampedAndARetryKeepsItsMid() {
        val stamped = Room.stamp(meta = hello, nowMs = now)
        assertEquals(now, stamped.sentAt)
        assertTrue(Regex("^[0-9a-f]{16}$").matches(stamped.messageId!!))
        assertNotEquals(stamped.messageId, Room.stamp(meta = hello, nowMs = now).messageId)
        val retry = Room.stamp(meta = hello.copy(messageId = "0123456789abcdef"), nowMs = now + 3_000)
        assertEquals("0123456789abcdef", retry.messageId)
        assertEquals(now + 3_000, retry.sentAt)
    }

    @Test
    fun theFirstKeyIsKeptWithoutAMark() {
        val met = Room.rememberedMember(known = null, id = hello.sender, meta = hello.copy(publicKey = keyA), time = now / 1000, nowMs = now, fresh = true)
        assertEquals(keyA, met.pk)
        assertEquals(0L, met.keyChanged)
        val other = Room.rememberedMember(known = met, id = hello.sender, meta = hello.copy(publicKey = keyB), time = now / 1000, nowMs = now, fresh = true)
        assertEquals("another key without re is ignored", keyA, other.pk)
        assertEquals(0L, other.keyChanged)
    }

    @Test
    fun aStaleReHelloDoesNotChangeTheKey() {
        val known = Member(id = hello.sender, name = "Pixel", source = "android", seen = now - 60_000, pk = keyA)
        val replayed = Room.rememberedMember(known = known, id = hello.sender, meta = hello.copy(publicKey = keyB, renewed = true), time = now / 1000, nowMs = now, fresh = false)
        assertEquals(keyA, replayed.pk)
        assertEquals(0L, replayed.keyChanged)
    }

    @Test
    fun aFreshReHelloWithAnotherKeyIsTakenAndMarked() {
        val known = Member(id = hello.sender, name = "Pixel", source = "android", seen = now - 60_000, since = now - 120_000, pk = keyA)
        val changed = Room.rememberedMember(known = known, id = hello.sender, meta = hello.copy(publicKey = keyB, renewed = true), time = now / 1000, nowMs = now, fresh = true)
        assertEquals(keyB, changed.pk)
        assertEquals(now, changed.keyChanged)
        assertEquals(now - 120_000, changed.since)
        val later = Room.rememberedMember(known = changed, id = hello.sender, meta = hello.copy(kind = ClipMeta.PING, publicKey = keyB), time = now / 1000 + 60, nowMs = now + 60_000, fresh = true)
        assertEquals("the mark keeps its time", now, later.keyChanged)
        val same = Room.rememberedMember(known = known, id = hello.sender, meta = hello.copy(publicKey = keyA, renewed = true), time = now / 1000, nowMs = now, fresh = true)
        assertEquals("the same key again is no change", 0L, same.keyChanged)
    }
}
