package com.kopylovis.tossling.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    @Test
    fun theScriptRemovesOurEntriesOnceTosslingIsGone() {
        assumeTrue(Platform.os != Os.WINDOWS)
        val root = Files.createTempDirectory("tossling menu").toFile()
        val exe = File(root, "opt/tossling/bin/Tossling").path
        val nautilus = File(root, "nautilus/scripts").apply { mkdirs() }
        val caja = File(root, "caja/scripts").apply { mkdirs() }
        val files = ExplorerMenu.LinuxFiles(
            autostart = File(root, "autostart/tossling.desktop"),
            scripts = listOf(nautilus, caja),
            entries = listOf(File(root, "nemo/tossling.nemo_action"), File(root, "tossling/send")),
            thunar = File(root, "Thunar/uca.xml"),
        )
        val script = ExplorerMenu.sendScript(exe = exe, files = files)
        listOf(File(nautilus, "Send via Tossling"), File(caja, "Отправить через Tossling"), files.entries[1]).forEach { it.parentFile.mkdirs(); it.writeText(script) }
        File(nautilus, "Someone else's script").writeText("#!/bin/sh\necho hi\n")
        files.entries[0].apply { parentFile.mkdirs(); writeText("[Nemo Action]\n") }
        files.autostart.apply { parentFile.mkdirs(); writeText("[Desktop Entry]\nExec=\"$exe\"\n") }
        files.thunar.apply {
            parentFile.mkdirs()
            writeText("<?xml version=\"1.0\"?>\n<actions>\n<action>\n\t<name>Open Terminal Here</name>\n\t<unique-id>1-1</unique-id>\n</action>\n$mine\n</actions>\n")
        }
        val run = ProcessBuilder("sh", files.entries[1].path, "/tmp/a file").redirectErrorStream(true).start()
        val output = run.inputStream.bufferedReader().readText()
        assertEquals(output, 0, run.waitFor())
        assertEquals(listOf("Someone else's script"), nautilus.list()!!.toList())
        assertEquals(emptyList<String>(), caja.list()!!.toList())
        assertEquals(false, files.autostart.exists() || files.entries.any { it.exists() })
        val uca = files.thunar.readText()
        assertEquals(true, uca.contains("Open Terminal Here") && uca.contains("</actions>"))
        assertEquals(false, uca.contains("tossling-send"))
        root.deleteRecursively()
    }
}
