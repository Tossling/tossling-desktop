package com.kopylovis.tossling.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipFile

class UpdatesTest {

    private val dir: File = Files.createTempDirectory("tossling-updates").toFile().apply { deleteOnExit() }

    @Test
    fun comparesVersions() {
        assertTrue(Updates.isNewer("0.4.0", "0.3.3"))
        assertTrue(Updates.isNewer("0.10.0", "0.9.9"))
        assertTrue(Updates.isNewer("v1.0", "0.9"))
        assertFalse(Updates.isNewer("0.3.3", "0.3.3"))
        assertFalse(Updates.isNewer("0.3.2", "0.3.3"))
        assertTrue(Updates.isNewer("0.4.0", "dev"))
        assertFalse(Updates.isNewer("broken", "0.3.3"))
    }

    @Test
    fun acceptsOnlyTheSignedInstaller() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded.takeLast(32).toByteArray())
        val installer = File(dir, "Tossling.msi").apply { writeBytes(ByteArray(200_000) { (it * 7).toByte() }) }
        val sha = MessageDigest.getInstance("SHA-256").digest(installer.readBytes()).joinToString(separator = "") { "%02x".format(it) }
        val unsigned = Release(version = "0.4.1", url = "https://github.com/x", size = installer.length(), sha256 = sha)
        val release = unsigned.copy(manifestSignature = sign(pair = pair, message = Updates.manifest(unsigned)))
        assertEquals(null, Updates.verify(file = installer, release = release, publicKey = publicKey))
        assertFalse(Updates.isGenuine(file = installer, release = unsigned, publicKey = publicKey))
        assertFalse(Updates.isGenuine(file = installer, release = release.copy(version = "9.9.9"), publicKey = publicKey))
        assertFalse(Updates.isGenuine(file = installer, release = release.copy(sha256 = "0".repeat(64)), publicKey = publicKey))
        assertFalse(Updates.isGenuine(file = installer, release = release.copy(size = 1), publicKey = publicKey))
        val other = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded.takeLast(32).toByteArray())
        assertEquals("the signature does not match", Updates.verify(file = installer, release = release, publicKey = other))
        installer.appendBytes(byteArrayOf(1))
        assertFalse(Updates.isGenuine(file = installer, release = release.copy(size = installer.length()), publicKey = publicKey))
    }

    @Test
    fun signsTheSameManifestAsThePublishScript() {
        val release = Release(version = "0.4.1", url = "https://github.com/x", size = 5, sha256 = "ABC")
        assertEquals("tossling-windows-update\n0.4.1\n5\nabc\n", Updates.manifest(release).decodeToString())
    }

    private fun sign(pair: java.security.KeyPair, message: ByteArray): String = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update(message)
        Base64.getEncoder().encodeToString(sign())
    }

    @Test
    fun readsTheFeed() {
        val feed = """{"version":"0.4.0","url":"https://github.com/tossling/tossling-desktop/releases/download/v0.4.0/Tossling-0.4.0.msi","size":1,"sha256":"ab","signature":"cd","notes":"https://github.com/tossling/tossling-desktop/releases/tag/v0.4.0"}"""
        val release = com.kopylovis.tossling.protocol.SyncJson.decodeFromString(Release.serializer(), feed)
        assertEquals("0.4.0", release.version)
    }

    @Test
    fun packsAFolderWithItsName() {
        val folder = File(dir, "Photos").apply { mkdirs() }
        File(folder, "a.txt").writeText("a")
        File(folder, "trip").mkdirs()
        File(folder, "trip/b.txt").writeText("bb")
        val zip = File(dir, "Photos.zip")
        zipFolder(folder = folder, zip = zip)
        val names = ZipFile(zip).use { file -> file.entries().toList().map { it.name }.sorted() }
        assertEquals(listOf("Photos/", "Photos/a.txt", "Photos/trip/", "Photos/trip/b.txt"), names)
    }

    @Test
    fun eachPlatformReadsItsOwnFeed() {
        assertEquals("https://monoroh.com/tossling/windows.json", Updates.feedUrl(os = Os.WINDOWS, arm = false))
        assertEquals("https://monoroh.com/tossling/windows-arm64.json", Updates.feedUrl(os = Os.WINDOWS, arm = true))
        assertEquals("https://monoroh.com/tossling/linux-amd64.json", Updates.feedUrl(os = Os.LINUX, arm = false))
        assertEquals("https://monoroh.com/tossling/linux-arm64.json", Updates.feedUrl(os = Os.LINUX, arm = true))
    }
}
