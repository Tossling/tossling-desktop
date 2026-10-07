package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.ClipMeta
import com.kopylovis.tossling.protocol.Endpoint
import com.kopylovis.tossling.protocol.Invite
import com.kopylovis.tossling.protocol.Invites
import com.kopylovis.tossling.protocol.Member
import com.kopylovis.tossling.protocol.OLD_ROOM_PREFIX
import com.kopylovis.tossling.protocol.ROOM_PREFIX
import com.kopylovis.tossling.protocol.SyncJson
import com.kopylovis.tossling.protocol.acceptsServerMove
import com.kopylovis.tossling.protocol.crypto.ClipCipher
import com.kopylovis.tossling.protocol.crypto.DeviceIdentity
import com.kopylovis.tossling.protocol.crypto.RoomKeys
import com.kopylovis.tossling.protocol.crypto.RoomSecret
import com.kopylovis.tossling.protocol.network.NtfyClient
import com.kopylovis.tossling.protocol.network.NtfyEvent
import com.kopylovis.tossling.protocol.network.NtfyException
import com.kopylovis.tossling.protocol.serverMoveTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun interface Notifier {
    fun notify(text: String)
}

class Room(
    private val store: SettingsStore,
    private val clipboard: SystemClipboard,
    private val history: History,
    private val notifier: Notifier,
    private val client: NtfyClient = NtfyClient(),
    stateFile: File = File(Platform.home, "state.json"),
    private val downloads: () -> File = { Platform.downloads },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> Log.write("failed: $error") })
    private val state = JsonFile(file = stateFile, serializer = RoomState.serializer(), empty = RoomState())
    private val events = Channel<NtfyEvent>(capacity = Channel.UNLIMITED)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val random = SecureRandom()
    private val contentSeq = AtomicInteger()
    private val _connected = MutableStateFlow(false)
    private val _removed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var session: Job? = null

    @Volatile private var lastReceived: Pair<Set<String>, Long>? = null

    @Volatile private var lastSent = ""

    @Volatile private var invitation: Pair<String, CompletableDeferred<String>>? = null

    val roomState: StateFlow<RoomState> = state.value
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    val removed: SharedFlow<String> = _removed.asSharedFlow()

    private val settings: Settings get() = store.settings.value

    fun start() {
        clipboard.watch { changes.tryEmit(Unit) }
        scope.launch {
            changes.collectLatest {
                delay(SETTLE_MS)
                runCatching { capture() }.onFailure { Log.write("could not read the clipboard: ${it.message}") }
            }
        }
        scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.write("message ${event.id} failed: $error")
                }
            }
        }
        scope.launch {
            delay(MAINTENANCE_DELAY_MS)
            var round = 0
            while (isActive) {
                runCatching { retireTokens() }
                if (round % MOVE_EVERY_ROUNDS == 0) runCatching { followServerMove() }
                round++
                delay(MAINTENANCE_MS)
            }
        }
        connect()
    }

    fun connect() {
        session?.cancel()
        _connected.value = false
        if (!settings.isConfigured) return
        ensureIdentity()
        if (state.value.value.room != settings.room) state.update { RoomState(room = settings.room) }
        session = scope.launch { listen() }
    }

    fun stop() {
        session?.cancel()
        _connected.value = false
    }

    fun sendNow() = scope.launch {
        val clip = clipboard.read()
        when {
            clip == null -> notifier.notify(L("Нечего отправлять: буфер пуст", "Nothing to send: the clipboard is empty"))
            clipboard.isPrivate() -> notifier.notify(L("Не отправил: в буфере пароль или служебные данные", "Did not send: the clipboard holds a password or private data"))
            clip is Clip.Files -> clip.files.forEach { sendFile(it) }
            else -> prepared(clip)?.let { send(clip = it, seq = contentSeq.incrementAndGet(), announce = true) }
        }
    }

    fun sendFiles(files: List<File>) = scope.launch { files.forEach { sendFile(it) } }

    fun copyAgain(clip: Clip) {
        lastReceived = setOf(clip.digest, clipboard.echo(clip)) to System.currentTimeMillis()
        clipboard.write(clip)
    }

    fun rename(name: String) = scope.launch {
        store.update { it.copy(name = name.trim()) }
        runCatching { publishControl(kind = ClipMeta.HELLO) }
    }

    fun setAlias(id: String, alias: String) {
        store.update { it.copy(aliases = if (alias.isBlank()) it.aliases - id else it.aliases + (id to alias.trim())) }
    }

    fun nameOf(member: Member): String = settings.aliases[member.id]?.takeIf { it.isNotBlank() } ?: member.name

    fun pairingPayload(): String {
        val conf = settings
        val fields = buildList {
            add("id" to conf.deviceId)
            add("k" to conf.key)
            add("n" to conf.deviceName)
            if (conf.owner.isNotEmpty()) add("o" to conf.owner)
            identity()?.let { add("pk" to it.publicText) }
            add("r" to conf.room)
            add("s" to conf.server)
            add("t" to conf.token)
        }
        val body = fields.joinToString(separator = ",") { (name, value) -> "\"$name\":${SyncJson.encodeToString(String.serializer(), value)}" }
        return "{$body,\"v\":2}"
    }

    suspend fun invite(code: String): String? {
        val conf = settings
        val topic = Invites.topic(code = code, prefix = roomPrefix(server = conf.server))
        val key = withContext(Dispatchers.Default) { Invites.key(code = code) }
        val offer = Invites.seal(
            invite = Invite(server = conf.server, token = conf.token, room = conf.room, key = conf.key, name = conf.deviceName, expires = now() / 1000.0 + Invites.LIFETIME_SECONDS, owner = conf.owner.ifEmpty { null }),
            key = key,
        )
        val joined = CompletableDeferred<String>()
        invitation = topic to joined
        try {
            client.publish(endpoint = conf.endpoint, topic = topic, message = offer, body = null, headers = NO_CACHE)
            Log.write("invited a computer: waiting for it to join")
            return withTimeoutOrNull(Invites.LIFETIME_SECONDS * 1000L) {
                val repeat = launch {
                    while (isActive) {
                        delay(Invites.OFFER_INTERVAL_MS)
                        runCatching { client.publish(endpoint = conf.endpoint, topic = topic, message = offer, body = null, headers = NO_CACHE) }
                    }
                }
                try {
                    joined.await()
                } finally {
                    repeat.cancel()
                }
            }
        } finally {
            invitation = null
        }
    }

    suspend fun revoke(id: String) {
        val conf = settings
        if (id == conf.owner) throw IllegalStateException(L("Создателя комнаты нельзя отключить", "The creator of the room cannot be disconnected"))
        val members = state.value.value.members
        val name = members[id]?.let(::nameOf) ?: L("устройство", "a device")
        val others = members.filterKeys { it != id && it != conf.deviceId && !it.startsWith(LEGACY_PREFIX) }
        val isLegacy = others.values.any { RoomKeys.decodeKey(it.pk) == null }
        val room = roomPrefix(server = conf.server) + hex(ROOM_BYTES)
        val key = Base64.getEncoder().encodeToString(ByteArray(ClipCipher.KEY_SIZE).also(random::nextBytes))
        val token = if (isLegacy || conf.token.isEmpty()) null else runCatching { client.createToken(endpoint = conf.endpoint, label = TOKEN_LABEL) }
            .onFailure { Log.write("kept the old token: ${it.message}") }
            .getOrNull()
        publishControl(kind = ClipMeta.KICK, to = listOf(id))
        if (others.isNotEmpty()) {
            val sealed = RoomKeys.seal(secret = RoomSecret(key = key, room = room, token = token), recipients = others.mapValues { it.value.pk })
            publish(
                meta = control(kind = ClipMeta.REKEY).copy(
                    to = others.keys.toList(),
                    ephemeral = sealed.ephemeral,
                    keys = sealed.keys,
                    room = room.takeIf { isLegacy },
                    key = key.takeIf { isLegacy },
                ),
                body = null,
            )
        }
        store.update { current ->
            current.copy(
                room = room,
                key = key,
                token = token ?: current.token,
                retire = if (token != null) current.retire + RetiredToken(token = conf.token, at = now() / 1000.0 + DAY_SECONDS) else current.retire,
            )
        }
        state.update { RoomState(room = room, members = it.members - id) }
        Log.write("disconnected $name: a new room and key${if (token == null) "" else ", a new token"}")
        notifier.notify(L("$name отключён от комнаты", "$name is disconnected from the room"))
        connect()
    }

    private suspend fun listen() {
        val conf = settings
        val knowsOthers = state.value.value.members.keys.any { it != conf.deviceId && !it.startsWith(LEGACY_PREFIX) }
        scope.launch { runCatching { publishControl(kind = if (knowsOthers) ClipMeta.HELLO else ClipMeta.PING) } }
        var retry = FIRST_RETRY_MS
        while (true) {
            try {
                val since = state.value.value.lastId.ifEmpty { "${STALE_SECONDS}s" }
                client.stream(
                    endpoint = conf.endpoint,
                    topics = listOf(conf.room),
                    since = since,
                    onOpen = {
                        if (!_connected.value) Log.write("connected to ${conf.server}")
                        _connected.value = true
                        retry = FIRST_RETRY_MS
                    },
                    onEvent = { events.trySend(it) },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: NtfyException) {
                Log.write("the server answered HTTP ${error.code}")
                if (error.code == 401 || error.code == 403) retry = maxOf(retry, AUTH_RETRY_MS)
            } catch (error: Exception) {
                if (_connected.value) Log.write("the connection broke: ${error.message}")
            }
            _connected.value = false
            delay(retry)
            retry = minOf(retry * 2, MAX_RETRY_MS)
        }
    }

    private suspend fun handle(event: NtfyEvent) {
        if (event.event != MESSAGE_EVENT) return
        val conf = settings
        if (event.topic.isNotEmpty() && event.topic != conf.room) return
        state.update { it.copy(lastId = event.id) }
        val cipher = ClipCipher.fromBase64(key = conf.key)
        val meta = runCatching { SyncJson.decodeFromString(ClipMeta.serializer(), cipher.openText(text = event.message.orEmpty()).decodeToString()) }.getOrElse {
            Log.write("could not decrypt the message ${event.id}: a different key?")
            return
        }
        if (meta.sender.isNotEmpty() && meta.sender == conf.deviceId) return
        if (meta.to?.contains(conf.deviceId) == false) return
        val age = now() / 1000 - event.time
        val kind = meta.kind
        val from = shownName(meta)
        if (age >= STALE_SECONDS && kind !in setOf(ClipMeta.BYE, ClipMeta.REKEY, ClipMeta.KICK, ClipMeta.FILE)) return
        if (kind == ClipMeta.FILE && age >= FILE_STALE_SECONDS) {
            notifier.notify(L("Не получил «${meta.fileName ?: "file"}» от $from: сервер его уже удалил", "Did not receive «${meta.fileName ?: "file"}» from $from: the server has already deleted it"))
            return
        }
        when (kind) {
            ClipMeta.PING -> {
                remember(meta = meta, time = event.time)
                if (meta.sender.isNotEmpty()) runCatching { publishControl(kind = ClipMeta.HELLO, to = listOf(meta.sender)) }
                return
            }

            ClipMeta.BYE -> {
                state.update { it.copy(members = it.members - memberId(meta)) }
                Log.write("left the room: $from")
                return
            }

            ClipMeta.REKEY -> return rekey(meta = meta, from = from)
            ClipMeta.KICK -> return kicked(from = from)
        }
        val isNew = remember(meta = meta, time = event.time)
        if (kind == ClipMeta.HELLO) {
            meta.invite?.let { topic -> invitation?.takeIf { it.first == topic }?.second?.complete(from) }
            if (isNew) {
                Log.write("a new device: $from")
                notifier.notify(L("Подключён $from", "$from is connected"))
            }
            return
        }
        val url = event.attachment?.url?.takeIf { it.isNotEmpty() }
        when (kind) {
            ClipMeta.TEXT -> {
                val text = meta.text ?: url?.let { download(endpoint = conf.endpoint, url = it, cipher = cipher, meta = meta, from = from) }?.decodeToString() ?: return
                apply(clip = Clip.Text(text), from = from)
            }

            ClipMeta.IMAGE -> {
                val bytes = url?.let { download(endpoint = conf.endpoint, url = it, cipher = cipher, meta = meta, from = from) } ?: return
                apply(clip = Clip.Image(bytes = bytes, mime = meta.mime), from = from)
            }

            ClipMeta.FILE -> receiveFile(meta = meta, url = url ?: return, cipher = cipher, fresh = age < STALE_SECONDS, from = from)
            ClipMeta.MOVE -> Unit
            else -> Log.write("an unknown message: $kind")
        }
    }

    private fun apply(clip: Clip, from: String) {
        if (clip is Clip.Text && (clipboard.read() as? Clip.Text)?.text == clip.text) {
            Log.write("← $from: the text is already in the clipboard, skipped")
            return
        }
        lastReceived = setOf(clip.digest, clipboard.echo(clip)) to now()
        clipboard.write(clip)
        val what = when (clip) {
            is Clip.Text -> {
                history.addText(text = clip.text, incoming = true, device = from)
                L("текст ${clip.text.length} зн.", "text, ${clip.text.length} chars")
            }

            is Clip.Image -> {
                history.addImage(bytes = clip.bytes, mime = clip.mime, incoming = true, device = from)
                L("картинка ${sizeText(clip.bytes.size.toLong())}", "image ${sizeText(clip.bytes.size.toLong())}")
            }

            is Clip.Files -> ""
        }
        Log.write("← $from: $what")
        if (settings.notifications) notifier.notify(L("С $from: $what — можно вставлять", "From $from: $what, ready to paste"))
    }

    private suspend fun download(endpoint: Endpoint, url: String, cipher: ClipCipher, meta: ClipMeta, from: String): ByteArray? = try {
        cipher.open(sealed = client.download(endpoint = endpoint, url = url))
    } catch (error: NtfyException) {
        if (error.code != 404 && error.code != 410) throw error
        Log.write("the attachment of ${meta.kind} from $from is gone")
        null
    }

    private suspend fun receiveFile(meta: ClipMeta, url: String, cipher: ClipCipher, fresh: Boolean, from: String) {
        val name = safeName(meta.fileName ?: "file")
        val size = meta.size ?: 0
        val target = uniqueFile(dir = downloads(), name = name)
        Log.write("← $from: receiving the file $name (${sizeText(size)})")
        if (size >= BIG_FILE) notifier.notify(L("Получаю «$name» (${sizeText(size)}) от $from…", "Receiving «$name» (${sizeText(size)}) from $from…"))
        val received = try {
            client.downloadTo(endpoint = settings.endpoint, url = url) { input ->
                target.outputStream().buffered().use { output ->
                    if (meta.format == ClipCipher.STREAM_FORMAT) cipher.openStream(input = input, output = output) {} else output.write(cipher.open(sealed = input.readBytes()))
                }
            }
            target.length()
        } catch (error: CancellationException) {
            target.delete()
            throw error
        } catch (error: Exception) {
            target.delete()
            val gone = error is NtfyException && (error.code == 404 || error.code == 410)
            val reason = if (gone) L("сервер его уже удалил, отправьте ещё раз", "the server has already deleted it, send it again") else error.message.orEmpty()
            Log.write("did not receive the file $name from $from: $reason")
            notifier.notify(L("Не получил «$name» от $from: $reason", "Did not receive «$name» from $from: $reason"))
            return
        }
        if (fresh) copyAgain(Clip.Files(listOf(target)))
        history.addFile(file = target, size = received, incoming = true, device = from)
        val where = L("в Загрузках/Tossling", "in Downloads/Tossling")
        Log.write("← $from: the file ${target.name} (${sizeText(received)}) $where")
        notifier.notify(
            if (fresh) {
                L("С $from: файл ${target.name} (${sizeText(received)}) $where — можно вставлять", "From $from: the file ${target.name} (${sizeText(received)}) is $where, ready to paste")
            } else {
                L("С $from: файл ${target.name} (${sizeText(received)}) $where", "From $from: the file ${target.name} (${sizeText(received)}) is $where")
            },
        )
    }

    private fun rekey(meta: ClipMeta, from: String) {
        val conf = settings
        val box = meta.keys?.get(conf.deviceId)
        val ephemeral = meta.ephemeral
        val identity = identity()
        val opened = if (box != null && ephemeral != null && identity != null) RoomKeys.open(identity = identity, id = conf.deviceId, ephemeral = ephemeral, box = box) else null
        val room = opened?.room ?: meta.room
        val key = opened?.key ?: meta.key
        if (room == null || key == null || runCatching { ClipCipher.fromBase64(key = key) }.isFailure) {
            Log.write("$from changed the room key, but the new key is not for this device")
            return
        }
        val keep = meta.to.orEmpty().toSet() + memberId(meta)
        store.update { it.copy(room = room, key = key, token = opened?.token?.takeIf { token -> token.isNotEmpty() } ?: it.token) }
        state.update { RoomState(room = room, members = it.members.filterKeys { id -> id in keep }) }
        Log.write("$from disconnected a device: the room has a new key")
        connect()
    }

    private fun kicked(from: String) {
        if (settings.isOwner) {
            Log.write("$from tried to disconnect this device, but it created the room: staying")
            return
        }
        Log.write("$from disconnected this device from the room")
        notifier.notify(L("$from отключил этот компьютер от комнаты", "$from disconnected this computer from the room"))
        store.update { it.copy(room = "", key = "") }
        state.update { RoomState() }
        stop()
        _removed.tryEmit(from)
    }

    private fun capture() {
        val conf = settings
        if (!conf.isConfigured || conf.isPaused || !conf.auto) return
        if (clipboard.isPrivate()) {
            Log.write("skipped: a password or private data")
            return
        }
        val clip = clipboard.read() ?: return
        val digest = clip.digest
        lastReceived?.let { (received, at) -> if (digest in received && now() - at < ECHO_MS) return }
        if (digest == lastSent) return
        lastSent = digest
        if (clip is Clip.Files) {
            Log.write("skipped: files go only when asked")
            return
        }
        val ready = prepared(clip) ?: return
        send(clip = ready, seq = contentSeq.incrementAndGet(), announce = false)
    }

    private fun prepared(clip: Clip): Clip? = when (clip) {
        is Clip.Text -> clip.takeIf { it.text.toByteArray().size <= MAX_TEXT } ?: run {
            Log.write("skipped: the text is larger than ${MAX_TEXT / 1_000_000} MB")
            null
        }

        is Clip.Image -> if (!settings.images) null else Images.payload(clip)
        is Clip.Files -> clip
    }

    private fun send(clip: Clip, seq: Int, announce: Boolean) = scope.launch {
        val what = when (clip) {
            is Clip.Text -> L("текст ${clip.text.length} зн.", "text, ${clip.text.length} chars")
            is Clip.Image -> L("картинка ${sizeText(clip.bytes.size.toLong())}", "image ${sizeText(clip.bytes.size.toLong())}")
            is Clip.Files -> return@launch
        }
        var attempt = 0
        while (true) {
            val failure = try {
                publishContent(clip)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error
            }
            if (failure == null) {
                when (clip) {
                    is Clip.Text -> history.addText(text = clip.text, incoming = false, device = "")
                    is Clip.Image -> history.addImage(bytes = clip.bytes, mime = clip.mime, incoming = false, device = "")
                    is Clip.Files -> Unit
                }
                Log.write("→ room: $what")
                if (announce) notifier.notify(L("Отправил: $what", "Sent: $what"))
                return@launch
            }
            val transient = failure is IOException || failure is NtfyException && (failure.code == 429 || failure.code >= 500)
            if (!transient || attempt >= RETRY_MS.size) {
                Log.write("did not send $what: ${failure.message}")
                if (announce) notifier.notify(L("Не отправил: ${failure.message}", "Did not send: ${failure.message}"))
                return@launch
            }
            Log.write("did not send $what: ${failure.message}, retrying in ${RETRY_MS[attempt] / 1000} s")
            delay(RETRY_MS[attempt++])
            if (seq != contentSeq.get()) {
                Log.write("not retrying $what: something new was copied")
                return@launch
            }
        }
    }

    private suspend fun publishContent(clip: Clip) {
        val conf = settings
        val cipher = ClipCipher.fromBase64(key = conf.key)
        val base = ClipMeta(kind = ClipMeta.TEXT, source = Platform.source, device = conf.deviceName, sender = conf.deviceId)
        when (clip) {
            is Clip.Text -> {
                val bytes = clip.text.toByteArray()
                if (bytes.size <= INLINE_LIMIT) publish(meta = base.copy(text = clip.text), body = null) else publish(meta = base, body = cipher.seal(plain = bytes))
            }

            is Clip.Image -> publish(meta = base.copy(kind = ClipMeta.IMAGE, mime = clip.mime), body = cipher.seal(plain = clip.bytes))
            is Clip.Files -> Unit
        }
    }

    private suspend fun sendFile(file: File) {
        val name = file.name
        Log.write("→ room: sending ${file.path}")
        if (file.isDirectory) return sendFolder(folder = file)
        if (!file.isFile) {
            notifier.notify(L("Не нашёл «$name»", "Could not find «$name»"))
            return
        }
        val size = file.length()
        if (size > MAX_FILE) {
            notifier.notify(L("Не отправил: «$name» больше ${MAX_FILE / 1_000_000} МБ", "Did not send: «$name» is larger than ${MAX_FILE / 1_000_000} MB"))
            return
        }
        val conf = settings
        val cipher = ClipCipher.fromBase64(key = conf.key)
        val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: "application/octet-stream"
        val meta = ClipMeta(kind = ClipMeta.FILE, mime = mime, source = Platform.source, device = conf.deviceName, sender = conf.deviceId, fileName = name, format = ClipCipher.STREAM_FORMAT, size = size)
        if (size >= BIG_FILE) notifier.notify(L("Отправляю «$name» (${sizeText(size)})…", "Sending «$name» (${sizeText(size)})…"))
        try {
            client.publishStream(endpoint = conf.endpoint, topic = conf.room, message = cipher.sealToText(plain = encode(meta)), length = ClipCipher.sealedSize(size)) { output ->
                file.inputStream().buffered().use { input -> cipher.sealStream(input = input, output = output, size = size) {} }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.write("did not send the file $name: ${error.message}")
            notifier.notify(L("Не отправил «$name»: ${error.message}", "Did not send «$name»: ${error.message}"))
            return
        }
        history.addFile(file = file, size = size, incoming = false, device = "")
        Log.write("→ room: the file $name (${sizeText(size)})")
        notifier.notify(L("Отправил «$name»", "Sent «$name»"))
    }

    private suspend fun sendFolder(folder: File) {
        val name = folder.name.ifEmpty { "folder" }
        notifier.notify(L("Упаковываю папку «$name»…", "Packing the folder «$name»…"))
        val dir = Files.createTempDirectory(Platform.cache.apply { mkdirs() }.toPath(), "zip").toFile()
        try {
            val zip = File(dir, "$name.zip")
            val packed = runCatching { withContext(Dispatchers.IO) { zipFolder(folder = folder, zip = zip) } }
            if (packed.isFailure) {
                Log.write("could not pack the folder $name: ${packed.exceptionOrNull()?.message}")
                notifier.notify(L("Не упаковал папку «$name»", "Could not pack the folder «$name»"))
                return
            }
            sendFile(file = zip)
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun publishControl(kind: String, to: List<String>? = null) = publish(meta = control(kind = kind).copy(to = to), body = null)

    private fun control(kind: String): ClipMeta {
        val conf = settings
        return ClipMeta(kind = kind, source = Platform.source, device = conf.deviceName, sender = conf.deviceId, publicKey = identity()?.publicText)
    }

    private suspend fun publish(meta: ClipMeta, body: ByteArray?) {
        val conf = settings
        client.publish(endpoint = conf.endpoint, topic = conf.room, message = ClipCipher.fromBase64(key = conf.key).sealToText(plain = encode(meta)), body = body)
    }

    private fun remember(meta: ClipMeta, time: Long): Boolean {
        val id = memberId(meta)
        val offered = meta.publicKey?.takeIf { RoomKeys.decodeKey(it) != null }.orEmpty()
        val renewed = meta.renewed == true && meta.kind == ClipMeta.HELLO && offered.isNotEmpty()
        val seen = minOf(now(), time * 1000)
        var isNew = false
        state.update { current ->
            val known = current.members[id]
            isNew = known == null
            val pk = if (known == null || known.pk.isEmpty() || renewed) offered else known.pk
            val member = Member(id = id, name = meta.device.ifEmpty { "?" }, source = meta.source, seen = seen, since = known?.since ?: seen, pk = pk)
            current.copy(members = current.members + (id to member))
        }
        return isNew
    }

    private suspend fun retireTokens() {
        val conf = settings
        val due = conf.retire.filter { it.at * 1000 <= now() && it.token != conf.token }
        due.forEach { entry ->
            val done = try {
                client.deleteToken(endpoint = conf.endpoint, token = entry.token)
                Log.write("deleted the old server token")
                true
            } catch (error: NtfyException) {
                error.code in 400..499
            } catch (error: IOException) {
                false
            }
            if (done) store.update { it.copy(retire = it.retire - entry) }
        }
    }

    private suspend fun followServerMove() {
        val conf = settings
        if (!conf.isConfigured) return
        val target = serverMoveTarget(current = conf.server, url = client.tosslingHealth(endpoint = conf.endpoint)?.url) ?: return
        val isTossling = client.tosslingHealth(endpoint = Endpoint(server = target))?.isTossling == true
        val accountOk = runCatching { client.subscriptions(endpoint = Endpoint(server = target, token = conf.token)) }.isSuccess
        if (!acceptsServerMove(isTossling = isTossling, accountOk = accountOk)) {
            Log.write("the server is now $target, but it did not accept the token: staying on ${conf.server}")
            return
        }
        store.update { if (it.server == conf.server) it.copy(server = target) else it }
        Log.write("the server moved: ${conf.server} → $target")
        connect()
    }

    private suspend fun roomPrefix(server: String): String =
        if (client.tosslingHealth(endpoint = Endpoint(server = server))?.isTossling == true) ROOM_PREFIX else OLD_ROOM_PREFIX

    private fun ensureIdentity() {
        if (identity() == null) store.update { it.copy(identity = Base64.getEncoder().encodeToString(DeviceIdentity.generate().privateKey)) }
    }

    private fun identity(): DeviceIdentity? =
        runCatching { DeviceIdentity(privateKey = Base64.getDecoder().decode(settings.identity)) }.getOrNull()?.takeIf { settings.identity.isNotEmpty() }

    private fun shownName(meta: ClipMeta): String =
        settings.aliases[meta.sender]?.takeIf { it.isNotBlank() } ?: meta.device.ifEmpty { L("устройства", "a device") }

    private fun memberId(meta: ClipMeta): String = meta.sender.ifEmpty { "$LEGACY_PREFIX${meta.source}-${meta.device}" }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString(separator = "") { "%02x".format(it) }

    private fun encode(meta: ClipMeta): ByteArray = SyncJson.encodeToString(ClipMeta.serializer(), meta).toByteArray()

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        const val LEGACY_PREFIX = "legacy-"
        const val ONLINE_MS = 5 * 60_000L
        private const val MESSAGE_EVENT = "message"
        private const val TOKEN_LABEL = "tossling"
        private const val ROOM_BYTES = 12
        private const val STALE_SECONDS = 15 * 60
        private const val FILE_STALE_SECONDS = 3 * 60 * 60
        private const val INLINE_LIMIT = 2_400
        private const val MAX_TEXT = 1_000_000
        private const val MAX_FILE = 500_000_000L
        private const val BIG_FILE = 5_000_000L
        private const val ECHO_MS = 60_000L
        private const val SETTLE_MS = 150L
        private const val FIRST_RETRY_MS = 1_000L
        private const val AUTH_RETRY_MS = 30_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val MAINTENANCE_DELAY_MS = 30_000L
        private const val MAINTENANCE_MS = 3_600_000L
        private const val MOVE_EVERY_ROUNDS = 6
        private const val DAY_SECONDS = 86_400
        private val NO_CACHE = mapOf("X-Cache" to "no")
        private val RETRY_MS = longArrayOf(3_000, 10_000, 30_000, 60_000, 120_000, 300_000)
    }
}

fun sizeText(size: Long): String = when {
    size < 1_000 -> "$size B"
    size < 1_000_000 -> "%.0f KB".format(size / 1_000.0)
    size < 1_000_000_000 -> "%.1f MB".format(size / 1_000_000.0)
    else -> "%.2f GB".format(size / 1_000_000_000.0)
}

fun safeName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().take(120).ifBlank { "file" }

fun uniqueFile(dir: File, name: String): File {
    var file = File(dir, name)
    val base = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
    var n = 2
    while (file.exists()) file = File(dir, "$base $n$extension").also { n++ }
    return file
}

fun zipFolder(folder: File, zip: File) {
    val root = folder.toPath()
    val parent = root.parent ?: root
    ZipOutputStream(zip.outputStream().buffered()).use { output ->
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                val entry = parent.relativize(path).joinToString(separator = "/")
                when {
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> output.putNextEntry(ZipEntry("$entry/")).also { output.closeEntry() }
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                        output.putNextEntry(ZipEntry(entry))
                        Files.copy(path, output)
                        output.closeEntry()
                    }
                }
            }
        }
    }
}
