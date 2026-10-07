package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.SyncJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.system.exitProcess

@Serializable
data class Release(
    val version: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val signature: String,
    val notes: String = "",
)

class Updates(private val notifier: Notifier, private val scope: CoroutineScope) {

    private val _available = MutableStateFlow<Release?>(null)
    private val _busy = MutableStateFlow(false)

    val available: StateFlow<Release?> = _available.asStateFlow()
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val isSupported: Boolean get() = Platform.os == Os.WINDOWS && Platform.executable != null

    fun start() {
        if (!isSupported) return
        scope.launch {
            delay(FIRST_CHECK_MS)
            while (isActive) {
                runCatching { check(userInitiated = false) }.onFailure { Log.write("could not check for updates: ${it.message}") }
                delay(CHECK_EVERY_MS)
            }
        }
    }

    fun checkNow() = scope.launch {
        runCatching { check(userInitiated = true) }.onFailure {
            notifier.notify(L("Не проверил обновления: ${it.message}", "Could not check for updates: ${it.message}"))
        }
    }

    fun install(release: Release) = scope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        try {
            val installer = download(release)
            Log.write("installing Tossling ${release.version}")
            restartInto(installer)
        } catch (error: Exception) {
            Log.write("could not install ${release.version}: ${error.message}")
            notifier.notify(L("Не установил версию ${release.version}: ${error.message}", "Could not install version ${release.version}: ${error.message}"))
        } finally {
            _busy.value = false
        }
    }

    private suspend fun check(userInitiated: Boolean) {
        val release = withContext(Dispatchers.IO) { fetch(FEED_URL)?.let { SyncJson.decodeFromString(Release.serializer(), it.decodeToString()) } }
        if (release == null || !isNewer(release.version, Platform.version)) {
            _available.value = null
            if (userInitiated) notifier.notify(L("Стоит последняя версия ${Platform.version}", "Tossling ${Platform.version} is the latest version"))
            return
        }
        val isFirstNotice = _available.value?.version != release.version
        _available.value = release
        Log.write("version ${release.version} is available")
        if (isFirstNotice || userInitiated) notifier.notify(L("Доступна версия ${release.version}: установить можно из меню Tossling", "Version ${release.version} is available: install it from the Tossling menu"))
    }

    private suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        val url = URI(release.url)
        if (url.scheme != "https" || url.host !in TRUSTED_HOSTS) throw IllegalStateException(L("чужая ссылка на установщик", "the installer link is not trusted"))
        val dir = File(Platform.cache, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Tossling-${release.version.filter { it.isLetterOrDigit() || it == '.' }}.msi")
        open(release.url).let { connection ->
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${connection.responseCode}")
                connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
        }
        if (!isGenuine(file = file, release = release)) {
            file.delete()
            throw IllegalStateException(L("установщик не прошёл проверку подписи", "the installer failed the signature check"))
        }
        file
    }

    private fun restartInto(installer: File) {
        val exe = Platform.executable ?: throw IllegalStateException(L("не нашёл Tossling.exe", "could not find Tossling.exe"))
        val script = File(installer.parentFile, "install.vbs")
        script.writeText(
            listOf(
                "WScript.Sleep 2000",
                "Set shell = CreateObject(\"WScript.Shell\")",
                "shell.Run \"msiexec /i \"\"${installer.absolutePath}\"\" /qb /norestart\", 1, True",
                "shell.Run \"\"\"$exe\"\"\", 1, False",
            ).joinToString(separator = "\r\n"),
        )
        ProcessBuilder("wscript.exe", "//nologo", script.absolutePath).start()
        exitProcess(0)
    }

    companion object {
        const val FEED_URL = "https://monoroh.com/tossling/windows.json"
        private const val PUBLIC_KEY = "ZS/cGMRgf1n4zmwdXpGqy96Yi1pHypgQJp+Ff30pu/A="
        private const val ED25519_PREFIX = "302a300506032b6570032100"
        private const val FIRST_CHECK_MS = 20_000L
        private const val CHECK_EVERY_MS = 6 * 3_600_000L
        private const val TIMEOUT_MS = 30_000
        private const val BUFFER = 1 shl 16
        private val TRUSTED_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

        fun isGenuine(file: File, release: Release, publicKey: String = PUBLIC_KEY): Boolean = runCatching {
            if (file.length() != release.size) return false
            val prefix = ED25519_PREFIX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(prefix + Base64.getDecoder().decode(publicKey)))
            val verifier = Signature.getInstance("Ed25519").apply { initVerify(key) }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    verifier.update(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
            }
            val sha = digest.digest().joinToString(separator = "") { "%02x".format(it) }
            sha.equals(release.sha256, ignoreCase = true) && verifier.verify(Base64.getDecoder().decode(release.signature))
        }.getOrDefault(false)

        fun isNewer(candidate: String, current: String): Boolean {
            val a = parts(candidate) ?: return false
            val b = parts(current) ?: return true
            return (0 until maxOf(a.size, b.size)).map { (a.getOrElse(it) { 0 }).compareTo(b.getOrElse(it) { 0 }) }.firstOrNull { it != 0 }?.let { it > 0 } ?: false
        }

        private fun parts(version: String): List<Int>? = version.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }

        private fun fetch(url: String): ByteArray? {
            val connection = open(url)
            try {
                if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${connection.responseCode}")
                return connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }

        private fun open(url: String): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Tossling/${Platform.version} (Windows)")
        }
    }
}
