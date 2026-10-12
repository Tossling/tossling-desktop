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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kopylovis.tossling.protocol.ClipKind
import com.kopylovis.tossling.protocol.Invites
import com.kopylovis.tossling.protocol.network.ServerRefusal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    System.setProperty("apple.awt.UIElement", "true")
    if (Platform.os == Os.LINUX) nameTheWindows()
    val sending = args.dropWhile { it != "--send" }.drop(1)
    val sendingClipboard = "--send-clipboard" in args
    if (sending.isNotEmpty()) Inbox.post(paths = sending)
    if (sendingClipboard) Inbox.requestClipboard()
    if (!SingleInstance.acquire()) {
        if (sending.isEmpty() && !sendingClipboard) Log.write("Tossling is already running")
        exitProcess(0)
    }
    val store = SettingsStore()
    val history = History()
    val clipboard = systemClipboard()
    Log.write("Tossling ${Platform.version} on ${System.getProperty("os.name")}, data in ${Platform.home}")
    val screens = Screens(onboarding = MutableStateFlow(!store.settings.value.isConfigured))
    lateinit var tray: TrayHost
    val notifier = Notifier { text -> tray.notify(text) }
    val room = Room(store = store, clipboard = clipboard, history = history, notifier = notifier)
    val updates = Updates(notifier = notifier, scope = tasks)
    val menu = TrayMenu(room = room, store = store, history = history, updates = updates, notifier = notifier, screens = screens, onQuit = {
        tray.remove()
        exitProcess(0)
    })
    tray = if (Platform.os == Os.LINUX) LinuxTray(build = menu::build, onOpen = menu::opened) else AppTray(build = menu::build, onOpen = menu::opened)
    SwingUtilities.invokeAndWait {
        if (!tray.install() && tray is LinuxTray) tray = AppTray(build = menu::build, onOpen = menu::opened).apply { install() }
    }
    ExplorerMenu.apply()
    Inbox.watch(
        onFiles = { files -> if (store.settings.value.isConfigured) room.sendFiles(files) else tray.notify(L("Сначала войди в комнату", "Join a room first")) },
        onClipboard = { if (store.settings.value.isConfigured) room.sendNow() },
    )
    Hotkey.start {
        if (store.settings.value.isConfigured) room.sendNow()
    }
    updates.start()
    val onboarding = screens.onboarding
    val adding = screens.adding
    val onDone = { joined: String? ->
        room.connect()
        if (joined == null) adding.value = true else tray.notify(L("Этот компьютер в комнате с $joined", "This computer is in the room with $joined"))
    }
    application {
        val settings by store.settings.collectAsState()
        val showOnboarding by onboarding.collectAsState()
        val showAdding by adding.collectAsState()
        val showServerToken by screens.serverToken.collectAsState()
        LaunchedEffect(Unit) {
            room.start()
            room.removed.collect { onboarding.value = true }
        }
        LaunchedEffect(settings.autostart) { Autostart.apply(enabled = settings.autostart) }
        if (showOnboarding) {
            OnboardingWindow(
                store = store,
                askingToken = false,
                onDone = { joined ->
                    onboarding.value = false
                    onDone(joined)
                },
                onClose = {
                    if (store.settings.value.isConfigured) {
                        onboarding.value = false
                    } else {
                        tray.remove()
                        exitApplication()
                    }
                },
            )
        }
        val trayShown by tray.shown.collectAsState()
        var trayDue by remember { mutableStateOf(false) }
        var trayHintClosed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(TRAY_WAIT_MS)
            trayDue = true
        }
        if (trayDue && !trayShown && !trayHintClosed && !showOnboarding) {
            NoTrayWindow(onClose = { trayHintClosed = true }, onQuit = {
                tray.remove()
                exitApplication()
            })
        }
        if (showServerToken && settings.isConfigured) {
            OnboardingWindow(
                store = store,
                askingToken = true,
                onDone = {
                    screens.serverToken.value = false
                    onDone(null)
                },
                onClose = { screens.serverToken.value = false },
            )
        }
        if (showAdding && settings.isConfigured) key(settings.room) { AddDeviceWindow(room = room, settings = settings, onClose = { adding.value = false }) }
    }
}

private class Screens(val onboarding: MutableStateFlow<Boolean>) {
    val adding = MutableStateFlow(false)
    val serverToken = MutableStateFlow(false)
}

private class TrayMenu(
    private val room: Room,
    private val store: SettingsStore,
    private val history: History,
    private val updates: Updates,
    private val notifier: Notifier,
    private val screens: Screens,
    private val onQuit: () -> Unit,
) {

    private val setup = Setup(store = store)

    @Volatile private var creating = false

    fun build(): List<MenuEntry> = menuOf { trayMenu() }

    fun opened() = room.probe()

    private fun MenuBuilder.trayMenu() {
        val settings = store.settings.value
        val time = DateTimeFormatter.ofPattern("HH:mm")
        item(text = "Tossling ${Platform.version}", enabled = false)
        val status = when {
            !settings.isConfigured -> L("Не настроен", "Not set up")
            settings.isPaused -> L("Пауза до ${time.format(Instant.ofEpochMilli((settings.pausedUntil * 1000).toLong()).atZone(ZoneId.systemDefault()))}", "Paused until ${time.format(Instant.ofEpochMilli((settings.pausedUntil * 1000).toLong()).atZone(ZoneId.systemDefault()))}")
            room.refusal == ServerRefusal.TOKEN -> L("Сервер больше не принимает токен этого компьютера", "The server no longer accepts this computer's token")
            room.refusal == ServerRefusal.BUSY -> L("С этой сети слишком много запросов, сервер просит подождать", "Too many requests from this network, the server asks to wait")
            room.connected.value -> L("На связи: ${settings.server.substringAfter("://")}", "Connected to ${settings.server.substringAfter("://")}")
            else -> L("Нет связи с сервером", "No connection to the server")
        }
        item(text = status, enabled = false)
        if (room.refusal == ServerRefusal.TOKEN) {
            item(text = L("Новая комната…", "New Room…"), enabled = !creating) { newRoom() }
            item(text = L("Войти в другую комнату…", "Join Another Room…")) { screens.onboarding.value = true }
        }
        updates.available.value?.let { release ->
            if (updates.installsItself) {
                item(text = L("Установить версию ${release.version}", "Install Version ${release.version}"), enabled = !updates.busy.value) { updates.install(release) }
            } else {
                item(text = L("Скачать версию ${release.version}", "Download Version ${release.version}")) { openLink(release.notes) }
            }
        }
        separator()
        val items = history.items.value
        if (items.isEmpty()) {
            item(text = L("Пока ничего не было", "Nothing yet"), enabled = false)
        } else {
            items.take(RECENT).forEach { item ->
                val arrow = if (item.incoming) "←" else "→"
                val title = when (item.kind) {
                    ClipKind.TEXT -> item.text.orEmpty().replace(Regex("\\s+"), " ").trim().let { if (it.length > TITLE) it.take(TITLE) + "…" else it }
                    ClipKind.IMAGE -> L("Картинка ${sizeText(item.size)}", "Image ${sizeText(item.size)}")
                    ClipKind.FILE -> item.name ?: L("Файл", "File")
                }
                item(text = "$arrow $title") { history.clip(item)?.let(room::copyAgain) }
            }
        }
        separator()
        val shortcut = Hotkey.shortcut?.let { "   $it" }.orEmpty()
        item(text = L("Отправить буфер сейчас", "Send the Clipboard Now") + shortcut, enabled = settings.isConfigured) { room.sendNow() }
        item(text = L("Отправить файл…", "Send a File…"), enabled = settings.isConfigured) {
            val dialog = FileDialog(null as Frame?, L("Отправить в Tossling", "Send via Tossling"), FileDialog.LOAD).apply {
                isMultipleMode = true
                isVisible = true
            }
            dialog.files.takeIf { it.isNotEmpty() }?.let { room.sendFiles(it.toList()) }
        }
        submenu(text = L("Устройства", "Devices"), enabled = settings.isConfigured, live = ::presence) { devices() }
        if (settings.isPaused) {
            item(text = L("Снова отправлять", "Resume Sending")) { store.update { it.copy(pausedUntil = 0.0) } }
        } else {
            submenu(text = L("Пауза", "Pause")) {
                val pause = { minutes: Long -> store.update { it.copy(pausedUntil = (System.currentTimeMillis() + minutes * 60_000) / 1000.0) } }
                item(text = L("На 15 минут", "For 15 Minutes")) { pause(15) }
                item(text = L("На час", "For an Hour")) { pause(60) }
                item(text = L("До завтра", "Until Tomorrow")) {
                    val tomorrow = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    store.update { it.copy(pausedUntil = tomorrow / 1000.0) }
                }
            }
        }
        submenu(text = L("Настройки", "Settings")) {
            check(text = L("Отправлять при копировании", "Send When Copied"), checked = settings.auto) { on -> store.update { it.copy(auto = on) } }
            check(text = L("Отправлять картинки", "Send Images"), checked = settings.images) { on -> store.update { it.copy(images = on) } }
            check(text = L("Уведомления", "Notifications"), checked = settings.notifications) { on -> store.update { it.copy(notifications = on) } }
            if (Autostart.isSupported) {
                check(text = if (Platform.os == Os.WINDOWS) L("Запускать вместе с Windows", "Start with Windows") else L("Запускать при входе", "Start at Login"), checked = settings.autostart) { on -> store.update { it.copy(autostart = on) } }
            }
            submenu(text = L("Язык", "Language")) {
                listOf("auto" to L("Как в системе", "System"), "en" to "English", "ru" to "Русский").forEach { (code, title) ->
                    check(text = title, checked = settings.language == code) { store.update { it.copy(language = code) } }
                }
            }
            if (updates.isSupported) {
                separator()
                item(text = L("Проверить обновления", "Check for Updates"), enabled = !updates.busy.value) { updates.checkNow() }
            }
        }
        item(text = L("Открыть папку Tossling", "Open the Tossling Folder")) { runCatching { Desktop.getDesktop().open(Platform.downloads) } }
        separator()
        item(text = L("Выйти", "Quit"), action = onQuit)
    }

    private fun MenuBuilder.devices() {
        val settings = store.settings.value
        val now = System.currentTimeMillis()
        val others = room.roomState.value.members.values.filter { it.id != settings.deviceId }.sortedBy { it.since }
        val refused = room.refusal == ServerRefusal.TOKEN
        if (others.isEmpty()) item(text = L("Больше никого", "Nobody else yet"), enabled = false)
        others.forEach { member ->
            val online = now - member.seen < Room.ONLINE_MS
            val state = if (online) L("на связи", "online") else ago(seen = member.seen, now = now)
            submenu(text = "${if (online) "●" else "○"} ${room.nameOf(member)} — $state") {
                item(text = L("Переименовать…", "Rename…")) {
                    rename(title = L("Как подписывать «${member.name}» на этом компьютере", "How this computer shows «${member.name}»"), current = room.nameOf(member)) { room.setAlias(id = member.id, alias = it) }
                }
                if (refused) {
                    item(text = L("Отключить нельзя, сервер не принимает токен этого компьютера", "Cannot disconnect while the server refuses this computer's token"), enabled = false)
                } else {
                    item(text = L("Отключить от комнаты", "Disconnect from the Room"), enabled = member.id != settings.owner) {
                        tasks.launch { runCatching { room.revoke(member.id) }.onFailure { notifier.notify(it.message ?: it.toString()) } }
                    }
                }
            }
        }
        separator()
        item(text = L("Этот компьютер: ${settings.deviceName}…", "This Computer: ${settings.deviceName}…")) {
            rename(title = L("Имя этого компьютера в комнате", "The name of this computer in the room"), current = settings.deviceName) { name ->
                if (name.isNotEmpty()) room.rename(name = name)
            }
        }
        item(text = L("Добавить устройство…", "Add a Device…")) { screens.adding.value = true }
        item(text = L("Новая комната…", "New Room…"), enabled = !creating) { newRoom() }
        item(text = L("Войти в другую комнату…", "Join Another Room…")) { screens.onboarding.value = true }
    }

    private fun newRoom() {
        val server = store.settings.value.server.substringAfter("://")
        val create = L("Создать", "Create")
        val choice = JOptionPane.showOptionDialog(
            null,
            L(
                "Этот компьютер выйдет из текущей комнаты и создаст новую на $server. Остальные устройства останутся в старой комнате, пока ты не добавишь их в новую.",
                "This computer leaves the current room and creates a new one on $server. The other devices stay in the old room until you add them to the new one.",
            ),
            L("Новая комната", "New Room"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            arrayOf(create, L("Отмена", "Cancel")),
            create,
        )
        if (choice != 0 || creating) return
        creating = true
        tasks.launch {
            try {
                setup.createNewRoom()
                room.connect()
                screens.adding.value = true
            } catch (error: TokenRefusedException) {
                screens.serverToken.value = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val text = error.message ?: error.toString()
                SwingUtilities.invokeLater { JOptionPane.showMessageDialog(null, text, "Tossling", JOptionPane.WARNING_MESSAGE) }
            } finally {
                creating = false
            }
        }
    }

    private fun presence(): String {
        val now = System.currentTimeMillis()
        return room.roomState.value.members.values.sortedBy { it.id }.joinToString { "${it.id}:${room.nameOf(it)}:${now - it.seen < Room.ONLINE_MS}" }
    }

    private fun rename(title: String, current: String, apply: (String) -> Unit) {
        val name = JOptionPane.showInputDialog(null, title, "Tossling", JOptionPane.PLAIN_MESSAGE, null, null, current) as? String ?: return
        apply(name.trim())
    }
}

@Composable
private fun NoTrayWindow(onClose: () -> Unit, onQuit: () -> Unit) {
    Window(onCloseRequest = onClose, title = "Tossling", icon = resourcePainter("icon.png"), state = rememberWindowState(position = WindowPosition(Alignment.Center), size = DpSize(500.dp, 300.dp)), resizable = false) {
        TosslingTheme {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = L(
                        "Tossling работает и синхронизирует буфер, но на этом рабочем столе некуда поставить его значок. В GNOME поставь расширение «AppIndicator and KStatusNotifierItem Support»: значок появится сам, перезапускать Tossling не нужно.",
                        "Tossling is running and syncs the clipboard, but this desktop has no place for its icon. On GNOME install the «AppIndicator and KStatusNotifierItem Support» extension: the icon appears by itself, no restart needed.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { openLink(APPINDICATOR_URL) }) { Text(L("Открыть расширение", "Open the Extension")) }
                    OutlinedButton(onClick = onClose) { Text(L("Закрыть", "Close")) }
                    OutlinedButton(onClick = onQuit) { Text(L("Выйти", "Quit")) }
                }
            }
        }
    }
}

private fun openLink(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }.recoverCatching { ProcessBuilder("xdg-open", url).start() }
        .onFailure { Log.write("could not open $url: ${it.message}") }
}

private fun ago(seen: Long, now: Long): String {
    val minutes = (now - seen) / 60_000
    return when {
        minutes < 1 -> L("только что", "just now")
        minutes < 60 -> L("был $minutes мин назад", "seen $minutes min ago")
        minutes < 1_440 -> L("был ${minutes / 60} ч назад", "seen ${minutes / 60} h ago")
        else -> L("был ${minutes / 1_440} дн назад", "seen ${minutes / 1_440} d ago")
    }
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
private fun OnboardingWindow(store: SettingsStore, askingToken: Boolean, onDone: (joined: String?) -> Unit, onClose: () -> Unit) {
    val setup = remember { Setup(store = store) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(if (askingToken) 1 else 0) }
    var server by remember { mutableStateOf(store.settings.value.server.substringAfter("://")) }
    var token by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var work by remember { mutableStateOf<Job?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    Window(
        onCloseRequest = onClose,
        title = "Tossling",
        icon = resourcePainter("icon.png"),
        state = rememberWindowState(position = WindowPosition(Alignment.Center), size = DpSize(520.dp, 470.dp)),
        resizable = false,
    ) {
        TosslingTheme {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (askingToken) {
                    Text(text = L("Сервер не принимает токен этого компьютера", "The server refuses this computer's token"), style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(text = L("Один буфер обмена на компьютеры и телефон", "One clipboard for your computers and phone"), style = MaterialTheme.typography.titleMedium)
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0; problem = null }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(L("Войти в комнату", "Join a room")) })
                        Tab(selected = tab == 1, onClick = { tab = 1; problem = null }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(L("Новая комната", "New room")) })
                    }
                }
                if (tab == 0) {
                    Text(
                        text = L(
                            "На компьютере, который уже в комнате, открой меню Tossling → Устройства → Добавить устройство (на Mac подойдет и команда tossling invite) и вставь сюда строку с адресом и кодом.",
                            "On a computer that is already in the room, open the Tossling menu → Devices → Add a Device (on a Mac, the tossling invite command works too) and paste the line with the address and code here.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(value = invite, onValueChange = { invite = it }, label = { Text(L("Адрес и код", "Address and code")) }, placeholder = { Text("tossling.example.com/7KQ2-M9XD") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(
                        text = if (askingToken) {
                            L(
                                "Чтобы создать новую комнату, вставь токен сервера. Он есть на странице настройки сервера и в разделе Security в его панели. Потом откроется окно, где можно добавить устройства.",
                                "To create a new room, paste the server token. It is on the server's setup page and under Security in its panel. Then a window opens where you can add devices.",
                            )
                        } else {
                            L(
                                "Адрес сервера Tossling и токен с его страницы настройки. Комната будет новой: телефон потом подключится по QR-коду.",
                                "The address of your Tossling server and the token from its setup page. The room is new: the phone joins it by a QR code afterwards.",
                            )
                        },
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
                            work = scope.launch {
                                try {
                                    if (tab == 0) onDone(setup.join(input = invite)) else setup.createRoom(address = server, token = token).also { onDone(null) }
                                } catch (error: CancellationException) {
                                    problem = null
                                } catch (error: SetupException) {
                                    problem = error.message
                                } catch (error: Exception) {
                                    problem = error.message ?: error.toString()
                                } finally {
                                    busy = false
                                    work = null
                                }
                            }
                        },
                    ) { Text(if (tab == 0) L("Войти", "Join") else L("Создать комнату", "Create the room")) }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        if (tab == 0) Text(text = L("Жду приглашение…", "Waiting for the invite…"), style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { work?.cancel() }) { Text(L("Отмена", "Cancel")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddDeviceWindow(room: Room, settings: Settings, onClose: () -> Unit) {
    val image = remember(settings) { Qr.image(text = room.pairingPayload()).toComposeImageBitmap() }
    val code = remember { Invites.newCode() }
    val line = remember(settings.server) { "${settings.server.removePrefix("https://")}/$code" }
    val known = remember { room.roomState.value.members.keys }
    val state by room.roomState.collectAsState()
    var computer by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(code) {
        try {
            computer = room.invite(code = code) ?: run {
                problem = L("Строка для компьютера истекла, открой это окно заново", "The line for a computer has expired, open this window again")
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            problem = L("Не отправил приглашение для компьютера. ${error.message}", "Could not send the invite for a computer: ${error.message}")
        }
    }
    val joined = (state.members.values.filter { it.id !in known && it.id != settings.deviceId }.map(room::nameOf) + listOfNotNull(computer)).distinct()
    Window(onCloseRequest = onClose, title = L("Добавить устройство", "Add a Device"), icon = resourcePainter("icon.png"), state = rememberWindowState(position = WindowPosition(Alignment.Center), size = DpSize(860.dp, 600.dp)), resizable = false) {
        TosslingTheme {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(text = L("Телефон", "Phone"), style = MaterialTheme.typography.titleMedium)
                        Text(text = L("Открой Tossling на телефоне и отсканируй этот код.", "Open Tossling on the phone and scan this code."), style = MaterialTheme.typography.bodyMedium)
                        Image(bitmap = image, contentDescription = null, modifier = Modifier.size(300.dp))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(text = L("Компьютер", "Computer"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = L(
                                "На Windows или Linux вставь эту строку в Tossling → Устройства → Войти в другую комнату, а при первом запуске во вкладку Войти в комнату. На Mac выполни в Терминале tossling join и эту строку.",
                                "On Windows or Linux, paste this line in Tossling → Devices → Join Another Room, or in the Join a room tab on the first start. On a Mac, run tossling join with this line in Terminal.",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        SelectionContainer { Text(text = line, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace) }
                        Button(enabled = computer == null && problem == null, onClick = {
                            room.copyAgain(Clip.Text(line))
                            copied = true
                        }) { Text(if (copied) L("Скопировано", "Copied") else L("Скопировать", "Copy")) }
                        problem?.let { Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                Text(text = L("С этими кодами входят в комнату, не показывай их посторонним.", "These codes let anyone into the room, do not show them to others."), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (joined.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = if (problem == null) {
                                L("Жду новые устройства. Строка для компьютера действует 10 минут.", "Waiting for new devices. The line for a computer works for 10 minutes.")
                            } else {
                                L("Жду телефон.", "Waiting for the phone.")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        val names = joined.joinToString()
                        Text(text = L("$names в комнате", if (joined.size == 1) "$names is in the room" else "$names are in the room"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onClose) { Text(if (joined.isNotEmpty()) L("Готово", "Done") else L("Закрыть", "Close")) }
                }
            }
        }
    }
}

@Composable
private fun resourcePainter(name: String): Painter = remember(name) {
    BitmapPainter(Room::class.java.getResourceAsStream("/$name")!!.use(ImageIO::read).toComposeImageBitmap())
}

private fun nameTheWindows() {
    runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(toolkit, "tossling-Tossling")
    }.onFailure { Log.write("could not name the windows: ${it.message}") }
}

private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val RECENT = 10
private const val TRAY_WAIT_MS = 15_000L
private const val APPINDICATOR_URL = "https://extensions.gnome.org/extension/615/appindicator-support/"
private const val TITLE = 48
