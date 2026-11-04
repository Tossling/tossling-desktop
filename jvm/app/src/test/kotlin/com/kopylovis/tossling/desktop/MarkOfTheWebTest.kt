package com.kopylovis.tossling.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MarkOfTheWebTest {

    @Test
    fun theStreamAndItsContentAreWhatWindowsReads() {
        assertEquals("C:\\Users\\a\\Downloads\\Tossling\\report.pdf:Zone.Identifier", zoneStream(File("C:\\Users\\a\\Downloads\\Tossling\\report.pdf")))
        assertEquals("[ZoneTransfer]\r\nZoneId=3\r\n", ZONE_IDENTIFIER)
    }

    @Test
    fun onlyWindowsGetsTheMark() {
        val dir = Files.createTempDirectory("tossling-zone").toFile()
        try {
            val file = File(dir, "report.pdf").apply { writeText("x") }
            assertFalse(markDownloaded(file = file, os = Os.LINUX))
            assertFalse(markDownloaded(file = file, os = Os.MAC))
            assertEquals(listOf("report.pdf"), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }
}
