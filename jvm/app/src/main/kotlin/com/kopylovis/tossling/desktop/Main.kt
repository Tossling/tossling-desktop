package com.kopylovis.tossling.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.kopylovis.tossling.protocol.ClipKind
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.time.Instant
import javax.imageio.ImageIO
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

fun main() {
    System.setProperty("apple.awt.UIElement", "true")
    if (!SingleInstance.acquire()) {
        Log.write("Tossling is already running")
        exitProcess(0)
    }
    val store = SettingsStore()
    val history = History()
    val clipboard = systemClipboard()
    Log.write("Tossling ${Platform.version} on ${System.getProperty("os.name")}, data in ${Platform.home}")
    application {
        val tray = rememberTrayState()
        val notifier = remember { Notifier { text -> tray.sendNotification(Notification(title = "Tossling", message = text)) } }
        val room = remember { Room(store = store, clipboard = clipboard, history = history, notifier = notifier) }
        val settings by store.settings.collectAsState()
        var onboarding by remember { mutableStateOf(!store.settings.value.isConfigured) }
        var pairing by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            room.start()
            room.removed.collect { onboarding = true }
        }
        LaunchedEffect(settings.autostart) { Autostart.apply(enabled = settings.autostart) }
        Tray(
            icon = resourcePainter("tray.png"),
            state = tray,
            tooltip = "Tossling",
            menu = { TrayMenu(room = room, store = store, history = history, notifier = notifier, onPair = { pairing = true }, onSetup = { onboarding = true }) },
        )
        if (onboarding) {
            OnboardingWindow(
                store = store,
                onDone = { joined ->
                    room.connect()
                    onboarding = false
                    if (joined == null) pairing = true else notifier.notify(L("Этот компьютер в комнате с $joined", "This computer is in the room with $joined"))
                },
                onClose = { if (store.settings.value.isConfigured) onboarding = false else exitApplication() },
            )
        }
        if (pairing && settings.isConfigured) PairWindow(payload = room.pairingPayload(), onClose = { pairing = false })
    }
}

@Composable
private fun MenuScope.TrayMenu(room: Room, store: SettingsStore, history: History, notifier: Notifier, onPair: () -> Unit, onSetup: () -> Unit) {
    val settings by store.settings.collectAsState()
    val connected by room.connected.collectAsState()
    val state by room.roomState.collectAsState()
    val items by history.items.collectAsState()
    val scope = rememberCoroutineScope()
    val time = DateTimeFormatter.ofPattern("HH:mm")
    Item(text = "Tossling ${Platform.version}", enabled = false, onClick = {})
    val status = when {
        !settings.isConfigured -> L("Не настроен", "Not set up")
        settings.isPaused -> L("Пауза до ${time.format(Instant.ofEpochMilli((settings.pausedUntil * 1000).toLong()).atZone(ZoneId.systemDefault()))}", "Paused until ${time.format(Instant.ofEpochMilli((settings.pausedUntil * 1000).toLong()).atZone(ZoneId.systemDefault()))}")
        connected -> L("На связи: ${settings.server.substringAfter("://")}", "Connected to ${settings.server.substringAfter("://")}")
        else -> L("Нет связи с сервером", "No connection to the server")
    }
    Item(text = status, enabled = false, onClick = {})
    Separator()
    if (items.isEmpty()) {
        Item(text = L("Пока ничего не было", "Nothing yet"), enabled = false, onClick = {})
    } else {
        items.take(RECENT).forEach { item ->
            val arrow = if (item.incoming) "←" else "→"
            val title = when (item.kind) {
                ClipKind.TEXT -> item.text.orEmpty().replace(Regex("\\s+"), " ").trim().let { if (it.length > TITLE) it.take(TITLE) + "…" else it }
                ClipKind.IMAGE -> L("Картинка ${sizeText(item.size)}", "Image ${sizeText(item.size)}")
                ClipKind.FILE -> item.name ?: L("Файл", "File")
            }
            Item(text = "$arrow $title", onClick = { history.clip(item)?.let(room::copyAgain) })
        }
    }
    Separator()
    Item(text = L("Отправить буфер сейчас", "Send the Clipboard Now"), enabled = settings.isConfigured, onClick = { room.sendNow() })
    Item(text = L("Отправить файл…", "Send a File…"), enabled = settings.isConfigured, onClick = {
        val dialog = FileDialog(null as Frame?, L("Отправить в Tossling", "Send via Tossling"), FileDialog.LOAD).apply {
            isMultipleMode = true
            isVisible = true
        }
        dialog.files.takeIf { it.isNotEmpty() }?.let { room.sendFiles(it.toList()) }
    })
    Menu(text = L("Устройства", "Devices"), enabled = settings.isConfigured) {
        val now = System.currentTimeMillis()
        val others = state.members.values.filter { it.id != settings.deviceId }.sortedBy { it.since }
        if (others.isEmpty()) Item(text = L("Больше никого", "Nobody else yet"), enabled = false, onClick = {})
        others.forEach { member ->
            val online = now - member.seen < Room.ONLINE_MS
            Menu(text = "${if (online) "●" else "○"} ${room.nameOf(member)}") {
                Item(text = L("Отключить от комнаты", "Disconnect from the Room"), enabled = member.id != settings.owner, onClick = {
                    scope.launch { runCatching { room.revoke(member.id) }.onFailure { notifier.notify(it.message ?: it.toString()) } }
                })
            }
        }
        Separator()
        Item(text = L("Подключить телефон…", "Connect a Phone…"), onClick = onPair)
        Item(text = L("Войти в другую комнату…", "Join Another Room…"), onClick = onSetup)
    }
    if (settings.isPaused) {
        Item(text = L("Снова отправлять", "Resume Sending"), onClick = { store.update { it.copy(pausedUntil = 0.0) } })
    } else {
        Menu(text = L("Пауза", "Pause")) {
            val pause = { minutes: Long -> store.update { it.copy(pausedUntil = (System.currentTimeMillis() + minutes * 60_000) / 1000.0) } }
            Item(text = L("На 15 минут", "For 15 Minutes"), onClick = { pause(15) })
            Item(text = L("На час", "For an Hour"), onClick = { pause(60) })
            Item(text = L("До завтра", "Until Tomorrow"), onClick = {
                val tomorrow = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                store.update { it.copy(pausedUntil = tomorrow / 1000.0) }
            })
        }
    }
    Menu(text = L("Настройки", "Settings")) {
        CheckboxItem(text = L("Отправлять при копировании", "Send When Copied"), checked = settings.auto, onCheckedChange = { on -> store.update { it.copy(auto = on) } })
        CheckboxItem(text = L("Отправлять картинки", "Send Images"), checked = settings.images, onCheckedChange = { on -> store.update { it.copy(images = on) } })
        CheckboxItem(text = L("Уведомления", "Notifications"), checked = settings.notifications, onCheckedChange = { on -> store.update { it.copy(notifications = on) } })
        if (Autostart.isSupported) {
            CheckboxItem(text = L("Запускать вместе с Windows", "Start with Windows"), checked = settings.autostart, onCheckedChange = { on -> store.update { it.copy(autostart = on) } })
        }
        Menu(text = L("Язык", "Language")) {
            listOf("auto" to L("Как в системе", "System"), "en" to "English", "ru" to "Русский").forEach { (code, title) ->
                CheckboxItem(text = title, checked = settings.language == code, onCheckedChange = { store.update { it.copy(language = code) } })
            }
        }
    }
    Item(text = L("Открыть папку Tossling", "Open the Tossling Folder"), onClick = { runCatching { Desktop.getDesktop().open(Platform.downloads) } })
    Separator()
    Item(text = L("Выйти", "Quit"), onClick = { exitProcess(0) })
}

@Composable
private fun TosslingTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFF7FA6E8)) else lightColorScheme(primary = Color(0xFF3067B8)),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun OnboardingWindow(store: SettingsStore, onDone: (joined: String?) -> Unit, onClose: () -> Unit) {
    val setup = remember { Setup(store = store) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var server by remember { mutableStateOf(store.settings.value.server.substringAfter("://")) }
    var token by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Window(
        onCloseRequest = onClose,
        title = "Tossling",
        icon = resourcePainter("icon.png"),
        state = rememberWindowState(size = DpSize(520.dp, 470.dp)),
        resizable = false,
    ) {
        TosslingTheme {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(text = L("Один буфер обмена на компьютеры и телефон", "One clipboard for your computers and phone"), style = MaterialTheme.typography.titleMedium)
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0; problem = null }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(L("Войти в комнату", "Join a room")) })
                    Tab(selected = tab == 1, onClick = { tab = 1; problem = null }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(L("Новая комната", "New room")) })
                }
                if (tab == 0) {
                    Text(
                        text = L(
                            "На Mac, который уже в комнате, выполни tossling invite и вставь сюда то, что она покажет после tossling join.",
                            "On a Mac that is already in the room, run tossling invite and paste here what it shows after tossling join.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(value = invite, onValueChange = { invite = it }, label = { Text(L("Адрес и код", "Address and code")) }, placeholder = { Text("tossling.example.com/7KQ2-M9XD") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(
                        text = L(
                            "Адрес сервера Tossling и токен с его страницы настройки. Комната будет новой: телефон потом подключится по QR-коду.",
                            "The address of your Tossling server and the token from its setup page. The room is new: the phone joins it by a QR code afterwards.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text(L("Сервер", "Server")) }, placeholder = { Text("tossling.example.com") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text(L("Токен", "Token")) }, singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                problem?.let { Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !busy && (if (tab == 0) invite.isNotBlank() else server.isNotBlank() && token.isNotBlank()),
                        onClick = {
                            busy = true
                            problem = null
                            scope.launch {
                                try {
                                    if (tab == 0) onDone(setup.join(input = invite)) else setup.createRoom(address = server, token = token).also { onDone(null) }
                                } catch (error: SetupException) {
                                    problem = error.message
                                } catch (error: Exception) {
                                    problem = error.message ?: error.toString()
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(if (tab == 0) L("Войти", "Join") else L("Создать комнату", "Create the room")) }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        if (tab == 0) Text(text = L("Жду приглашение…", "Waiting for the invite…"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PairWindow(payload: String, onClose: () -> Unit) {
    val image = remember(payload) { Qr.image(text = payload).toComposeImageBitmap() }
    Window(onCloseRequest = onClose, title = L("Подключить телефон", "Connect a Phone"), icon = resourcePainter("icon.png"), state = rememberWindowState(size = DpSize(440.dp, 560.dp)), resizable = false) {
        TosslingTheme {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = L("Открой Tossling на телефоне и отсканируй код. В коде ключ комнаты: не показывай его посторонним.", "Open Tossling on the phone and scan the code. It holds the room key: do not show it to anyone else."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Image(bitmap = image, contentDescription = null, modifier = Modifier.size(340.dp))
                OutlinedButton(onClick = onClose) { Text(L("Готово", "Done")) }
            }
        }
    }
}

@Composable
private fun resourcePainter(name: String): Painter = remember(name) {
    BitmapPainter(Room::class.java.getResourceAsStream("/$name")!!.use(ImageIO::read).toComposeImageBitmap())
}

private const val RECENT = 10
private const val TITLE = 48
