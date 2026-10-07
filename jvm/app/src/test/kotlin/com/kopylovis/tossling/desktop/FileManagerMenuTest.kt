package com.kopylovis.tossling.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileManagerMenuTest {

    private val mine = "<action>\n\t<name>Send via Tossling</name>\n\t<unique-id>tossling-send</unique-id>\n</action>"

    @Test
    fun keepsTheUsersActionsAndAddsOursOnce() {
        val xml = "<?xml version=\"1.0\"?>\n<actions>\n<action>\n\t<name>Open Terminal Here</name>\n\t<unique-id>1-1</unique-id>\n</action>\n" +
            "<action>\n\t<name>Отправить через Tossling</name>\n\t<unique-id>tossling-send</unique-id>\n</action>\n</actions>\n"
        val once = ExplorerMenu.withThunarAction(xml = xml, action = mine)!!
        assertEquals(1, Regex("tossling-send").findAll(once).count())
        assertEquals(true, once.contains("Open Terminal Here"))
        assertEquals(false, once.contains("Отправить через Tossling"))
        assertEquals(once, ExplorerMenu.withThunarAction(xml = once, action = mine))
    }

    @Test
    fun leavesABrokenFileAlone() {
        assertNull(ExplorerMenu.withThunarAction(xml = "<actions>", action = mine))
    }
}
