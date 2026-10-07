package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.ClipMeta
import com.kopylovis.tossling.protocol.Endpoint
import com.kopylovis.tossling.protocol.Invite
import com.kopylovis.tossling.protocol.Invites
import com.kopylovis.tossling.protocol.OLD_ROOM_PREFIX
import com.kopylovis.tossling.protocol.ROOM_PREFIX
import com.kopylovis.tossling.protocol.SyncJson
import com.kopylovis.tossling.protocol.crypto.ClipCipher
import com.kopylovis.tossling.protocol.crypto.DeviceIdentity
import com.kopylovis.tossling.protocol.network.NtfyClient
import com.kopylovis.tossling.protocol.network.NtfyException
import com.kopylovis.tossling.protocol.normalizedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

class SetupException(message: String) : Exception(message)

class Setup(private val store: SettingsStore, private val client: NtfyClient = NtfyClient()) {

    private val random = SecureRandom()

    suspend fun createRoom(address: String, token: String) {
        val server = serverOf(address)
        val clean = token.trim()
        try {
            client.subscriptions(endpoint = Endpoint(server = server, token = clean))
        } catch (error: NtfyException) {
            throw SetupException(
                when (error.code) {
                    401, 403 -> L("Сервер не принял токен", "The server did not accept the token")
                    else -> L("Сервер ответил HTTP ${error.code}", "The server answered HTTP ${error.code}")
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw SetupException(L("Нет связи с сервером: ${error.message}", "No connection to the server: ${error.message}"))
        }
        val prefix = if (client.tosslingHealth(endpoint = Endpoint(server = server))?.isTossling == true) ROOM_PREFIX else OLD_ROOM_PREFIX
        val key = Base64.getEncoder().encodeToString(ByteArray(ClipCipher.KEY_SIZE).also(random::nextBytes))
        store.update { current ->
            val id = current.deviceId.ifEmpty { hex(DEVICE_BYTES) }
            withIdentity(current).copy(server = server, token = clean, room = prefix + hex(ROOM_BYTES), key = key, deviceId = id, owner = id)
        }
        Log.write("created a room on $server")
    }

    suspend fun join(input: String): String {
        val (address, code) = parseJoin(input) ?: throw SetupException(
            L("Нужен адрес и код, например tossling.example.com/7KQ2-M9XD", "An address and a code are needed, for example tossling.example.com/7KQ2-M9XD"),
        )
        val server = serverOf(address)
        val topics = if (client.tosslingHealth(endpoint = Endpoint(server = server))?.isTossling == true) Invites.topics(code = code) else listOf(Invites.topic(code = code, prefix = OLD_ROOM_PREFIX))
        val key = withContext(Dispatchers.Default) { Invites.key(code = code) }
        val found = CompletableDeferred<Pair<Invite, String>>()
        val listener = CoroutineScope(Dispatchers.IO).launch {
            try {
                client.stream(endpoint = Endpoint(server = server), topics = topics, onOpen = {}, onEvent = { event ->
                    if (event.event == "message") Invites.open(message = event.message.orEmpty(), key = key)?.let { found.complete(it to event.topic) }
                })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                found.completeExceptionally(error)
            }
        }
        val (invite, topic) = try {
            withTimeoutOrNull(WAIT_MS) { found.await() }
                ?: throw SetupException(L("Приглашение не пришло: на первом компьютере должно быть открыто приглашение с этим кодом", "The invite did not come: the first computer must be showing an invite with this code"))
        } catch (error: NtfyException) {
            throw SetupException(
                if (error.code == 401 || error.code == 403) {
                    L("Сервер не пускает к приглашениям", "The server does not let anyone read invites")
                } else {
                    L("Сервер ответил HTTP ${error.code}", "The server answered HTTP ${error.code}")
                },
            )
        } catch (error: IOException) {
            throw SetupException(L("Нет связи с сервером: ${error.message}", "No connection to the server: ${error.message}"))
        } finally {
            listener.cancel()
        }
        if (invite.isExpired()) throw SetupException(L("Приглашение устарело: запусти tossling invite ещё раз", "The invite has expired: run tossling invite again"))
        if (runCatching { ClipCipher.fromBase64(key = invite.key) }.isFailure) throw SetupException(L("Приглашение повреждено", "The invite is broken"))
        val joined = store.update { current ->
            withIdentity(current).copy(
                server = normalizedServer(invite.server) ?: server,
                token = invite.token,
                room = invite.room,
                key = invite.key,
                deviceId = current.deviceId.ifEmpty { hex(DEVICE_BYTES) },
                owner = invite.owner.orEmpty(),
            )
        }
        val identity = DeviceIdentity(privateKey = Base64.getDecoder().decode(joined.identity))
        val hello = ClipMeta(
            kind = ClipMeta.HELLO,
            source = Platform.source,
            device = joined.deviceName,
            sender = joined.deviceId,
            publicKey = identity.publicText,
            renewed = true,
            invite = topic,
        )
        runCatching {
            client.publish(endpoint = joined.endpoint, topic = joined.room, message = ClipCipher.fromBase64(key = joined.key).sealToText(plain = SyncJson.encodeToString(ClipMeta.serializer(), hello).toByteArray()), body = null)
        }.onFailure { Log.write("could not say hello to the room: ${it.message}") }
        Log.write("joined the room of ${invite.name} on ${joined.server}")
        return invite.name.ifEmpty { L("компьютер", "a computer") }
    }

    private fun withIdentity(settings: Settings): Settings =
        if (settings.identity.isNotEmpty()) settings else settings.copy(identity = Base64.getEncoder().encodeToString(DeviceIdentity.generate().privateKey))

    private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString(separator = "") { "%02x".format(it) }

    companion object {
        private const val ROOM_BYTES = 12
        private const val DEVICE_BYTES = 8
        private const val WAIT_MS = 70_000L

        fun serverOf(address: String): String {
            val raw = address.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            return normalizedServer(raw) ?: throw SetupException(L("Не понял адрес сервера", "Could not read the server address"))
        }

        fun parseJoin(input: String): Pair<String, String>? {
            val target = input.trim().removePrefix("tossling join").removePrefix("tossy join").trim()
            val address = target.substringBeforeLast('/', "")
            val code = target.substringAfterLast('/')
            if (address.isBlank() || !Regex("^[0-9A-Za-z]{4}-?[0-9A-Za-z]{4}$").matches(code)) return null
            return address to code
        }
    }
}
