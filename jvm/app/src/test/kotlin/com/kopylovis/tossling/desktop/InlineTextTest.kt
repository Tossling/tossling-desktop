package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.ClipMeta
import com.kopylovis.tossling.protocol.crypto.ClipCipher
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class InlineTextTest {

    private val cipher = ClipCipher(Random.nextBytes(32))
    private val meta = ClipMeta(kind = ClipMeta.TEXT, source = "linux", device = "Test PC", sender = "0123456789abcdef")

    @Test
    fun plainTextUpToTheLimitGoesInline() {
        assertNotNull(Room.inlineEnvelope(cipher = cipher, meta = meta.copy(text = "x".repeat(Room.INLINE_LIMIT))))
    }

    @Test
    fun textThatGrowsPastTheMessageLimitGoesAsAnAttachment() {
        assertNull(Room.inlineEnvelope(cipher = cipher, meta = meta.copy(text = "\"".repeat(Room.INLINE_LIMIT))))
        assertNull(Room.inlineEnvelope(cipher = cipher, meta = meta.copy(text = "a\tb\n".repeat(Room.INLINE_LIMIT / 4))))
    }
}
