package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.Endpoint
import com.kopylovis.tossling.protocol.Member
import com.kopylovis.tossling.protocol.SyncJson
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

@Serializable
data class Settings(
    val server: String = "",
    val token: String = "",
    val room: String = "",
    val key: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val owner: String = "",
    val identity: String = "",
    val name: String = "",
    val aliases: Map<String, String> = emptyMap(),
    @SerialName("paused_until") val pausedUntil: Double = 0.0,
    val images: Boolean = true,
    val auto: Boolean = true,
    val notifications: Boolean = true,
    val autostart: Boolean = true,
    val language: String = "auto",
    val retire: List<RetiredToken> = emptyList(),
) {
    val isConfigured: Boolean get() = server.isNotEmpty() && room.isNotEmpty() && key.isNotEmpty() && deviceId.isNotEmpty()

    val endpoint: Endpoint get() = Endpoint(server = server, token = token)

    val deviceName: String get() = name.ifBlank { Platform.computerName }

    val isOwner: Boolean get() = owner.isNotEmpty() && owner == deviceId

    val isPaused: Boolean get() = pausedUntil * 1000 > System.currentTimeMillis()
}

@Serializable
data class RetiredToken(
    @SerialName("t") val token: String,
    @SerialName("at") val at: Double,
)

@Serializable
data class RoomState(
    val room: String = "",
    @SerialName("last_id") val lastId: String = "",
    val members: Map<String, Member> = emptyMap(),
)

class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>, private val empty: T) {

    private val state = MutableStateFlow(read())
    val value: StateFlow<T> = state.asStateFlow()

    @Synchronized
    fun update(change: (T) -> T): T {
        val next = change(state.value)
        if (next != state.value) {
            write(next)
            state.value = next
        }
        return next
    }

    private fun read(): T = runCatching { SyncJson.decodeFromString(serializer, file.readText()) }.getOrNull() ?: empty

    private fun write(value: T) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(SyncJson.encodeToString(serializer, value))
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

object Vault {

    private const val PREFIX = "dpapi:"

    fun seal(value: String): String {
        if (Platform.os != Os.WINDOWS || value.isEmpty()) return value
        return PREFIX + Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(value.toByteArray()))
    }

    fun open(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        return runCatching { String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(value.removePrefix(PREFIX)))) }
            .onFailure { Log.write("could not unlock a saved secret: ${it.message}") }
            .getOrDefault("")
    }

    fun sealed(settings: Settings): Settings =
        settings.copy(token = seal(settings.token), key = seal(settings.key), identity = seal(settings.identity))

    fun opened(settings: Settings): Settings =
        settings.copy(token = open(settings.token), key = open(settings.key), identity = open(settings.identity))
}

class SettingsStore(file: File = File(Platform.home, "config.json")) {

    private val stored = JsonFile(file = file, serializer = Settings.serializer(), empty = Settings())
    private val state = MutableStateFlow(Vault.opened(stored.value.value))
    val settings: StateFlow<Settings> = state.asStateFlow()

    init {
        Language.choice = state.value.language
    }

    @Synchronized
    fun update(change: (Settings) -> Settings): Settings {
        val next = change(state.value)
        if (next != state.value) {
            stored.update { Vault.sealed(next) }
            state.value = next
            Language.choice = next.language
        }
        return next
    }
}
