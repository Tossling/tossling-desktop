package com.kopylovis.tossling.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class TrayLabelTest {

    private fun gnomeShows(label: String) = label.replaceFirst(Regex("_([^_])"), "$1")

    @Test
    fun gnomeShowsUnderscoresAsTheyAre() {
        listOf("tossling_0.5.1_amd64.deb", "plain", "___init__", "end_", "a__b_c").forEach { text ->
            assertEquals(text, gnomeShows(LinuxTray.menuLabel(text = text, gnome = true)))
        }
    }

    @Test
    fun otherDesktopsGetEscapedUnderscores() {
        assertEquals("tossling__0.5.1__amd64.deb", LinuxTray.menuLabel(text = "tossling_0.5.1_amd64.deb", gnome = false))
    }
}
