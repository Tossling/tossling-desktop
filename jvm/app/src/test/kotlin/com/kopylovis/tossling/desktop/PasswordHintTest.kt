package com.kopylovis.tossling.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.datatransfer.StringSelection

/** Linux takes a copy with KDE's password manager hint set to secret as private, the hint Tossling itself writes. */
class PasswordHintTest {

    private fun marked(value: String) = AwtClipboard.MarkedSelection(content = StringSelection("hunter2"), marks = listOf(PollingClipboard.PASSWORD_HINT to value.toByteArray()))

    private fun isPrivate(contents: java.awt.datatransfer.Transferable) = AwtClipboard.hasMark(contents = contents, native = PollingClipboard.PASSWORD_HINT, value = PollingClipboard.SECRET)

    @Test
    fun aPasswordManagersCopyIsPrivate() {
        assertTrue(isPrivate(marked("secret")))
        assertTrue(isPrivate(marked("secret\n")))
    }

    @Test
    fun otherCopiesAreNot() {
        assertFalse(isPrivate(StringSelection("hunter2")))
        assertFalse(isPrivate(marked("public")))
    }
}
