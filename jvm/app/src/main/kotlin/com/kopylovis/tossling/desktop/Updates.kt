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
import kotlinx.serialization.SerialName
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
    val signature: String = "",
    @SerialName("manifest_signature") val manifestSignature: String = "",
    val notes: String = "",
)

class Updates(private val notifier: Notifier, private val scope: CoroutineScope) {

    private val _available = MutableStateFlow<Release?>(null)
    private val _busy = MutableStateFlow(false)

    val available: StateFlow<Release?> = _available.asStateFlow()
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val isSupported: Boolean get() = Platform.os != Os.MAC && Platform.executable != null
    val installsItself: Boolean get() = Platform.os == Os.WINDOWS || Platform.executable?.startsWith(LINUX_PACKAGE_DIR) == true

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
        if (isFirstNotice || userInitiated) {
            notifier.notify(
                if (installsItself) {
                    L("Доступна версия ${release.version}: установить можно из меню Tossling", "Version ${release.version} is available: install it from the Tossling menu")
                } else {
                    L("Доступна версия ${release.version}: скачать можно из меню Tossling", "Version ${release.version} is available: download it from the Tossling menu")
                },
            )
        }
    }

    private suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        val url = URI(release.url)
        if (url.scheme != "https" || url.host !in TRUSTED_HOSTS) throw IllegalStateException(L("чужая ссылка на установщик", "the installer link is not trusted"))
        val dir = File(Platform.cache, "updates").apply { deleteRecursively(); mkdirs() }
        val extension = if (Platform.os == Os.LINUX) "deb" else "msi"
        val file = File(dir, "Tossling-${release.version.filter { it.isLetterOrDigit() || it == '.' }}.$extension")
        open(release.url).let { connection ->
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${connection.responseCode}")
                connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
        }
        verify(file = file, release = release)?.let { reason ->
            Log.write("the installer of ${release.version} failed the check: $reason")
            file.delete()
            throw IllegalStateException(L("установщик не прошёл проверку подписи", "the installer failed the signature check"))
        }
        file
    }

    private fun restartInto(installer: File) {
        if (Platform.os == Os.LINUX) return installPackage(installer)
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

    private fun installPackage(installer: File) {
        val exe = Platform.executable ?: throw IllegalStateException(L("не нашёл Tossling", "could not find Tossling"))
        val process = runCatching { ProcessBuilder("pkexec", "dpkg", "-i", installer.absolutePath).redirectErrorStream(true).start() }
            .getOrElse { throw IllegalStateException(L("нет pkexec, поставьте ${installer.name} вручную", "pkexec is missing, install ${installer.name} by hand")) }
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            Log.write("dpkg did not install ${installer.name}: ${output.trim()}")
            throw IllegalStateException(L("установка отменена или не прошла", "the installation was cancelled or failed"))
        }
        ProcessBuilder("setsid", "sh", "-c", "sleep 2; exec \"\$0\"", exe).start()
        exitProcess(0)
    }

    companion object {
        val FEED_URL: String = System.getenv("TOSSLING_UPDATE_FEED")?.takeIf { it.isNotBlank() } ?: feedUrl(os = Platform.os, arm = Platform.isArm)
        private const val LINUX_PACKAGE_DIR = "/opt/tossling/"
        private const val PUBLIC_KEY = "ZS/cGMRgf1n4zmwdXpGqy96Yi1pHypgQJp+Ff30pu/A="
        private const val ED25519_PREFIX = "302a300506032b6570032100"
        private const val FIRST_CHECK_MS = 20_000L
        private const val CHECK_EVERY_MS = 6 * 3_600_000L
        private const val TIMEOUT_MS = 30_000
        private const val BUFFER = 1 shl 16
        private val TRUSTED_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

        fun feedUrl(os: Os, arm: Boolean): String = "https://monoroh.com/tossling/" + when {
            os == Os.LINUX && arm -> "linux-arm64.json"
            os == Os.LINUX -> "linux-amd64.json"
            arm -> "windows-arm64.json"
            else -> "windows.json"
        }

        fun isGenuine(file: File, release: Release, publicKey: String = PUBLIC_KEY): Boolean = verify(file = file, release = release, publicKey = publicKey) == null

        fun verify(file: File, release: Release, publicKey: String = PUBLIC_KEY): String? = try {
            val sha = lazy { digestOf(file) }
            when {
                release.manifestSignature.isEmpty() -> "the feed has no manifest signature"
                file.length() != release.size -> "${file.length()} bytes instead of ${release.size}"
                !sha.value.equals(release.sha256, ignoreCase = true) -> "SHA-256 ${sha.value} instead of ${release.sha256}"
                !signs(message = manifest(release), signature = release.manifestSignature, publicKey = publicKey) -> "the signature does not match"
                else -> null
            }
        } catch (error: Exception) {
            error.toString()
        }

        fun manifest(release: Release): ByteArray = "tossling-windows-update\n${release.version}\n${release.size}\n${release.sha256.lowercase()}\n".toByteArray()

        private fun signs(message: ByteArray, signature: String, publicKey: String): Boolean {
            val prefix = ED25519_PREFIX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(prefix + Base64.getDecoder().decode(publicKey)))
            return Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(Base64.getDecoder().decode(signature))
            }
        }

        private fun digestOf(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString(separator = "") { "%02x".format(it) }
        }

        fun isNewer(candidate: String, current: String): Boolean {
            val a = parts(candidate) ?: return false
            val b = parts(current) ?: return true
            return (0 until maxOf(a.size, b.size)).map { (a.getOrElse(it) { 0 }).compareTo(b.getOrElse(it) { 0 }) }.firstOrNull { it != 0 }?.let { it > 0 } ?: false
        }

        private fun parts(version: String): List<Int>? = version.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }

        private fun fetch(url: String): ByteArray? {
            if (url.startsWith("file:")) return File(URI(url)).takeIf { it.isFile }?.readBytes()
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
