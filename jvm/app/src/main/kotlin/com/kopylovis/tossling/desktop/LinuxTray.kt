@file:Suppress("FunctionName")

package com.kopylovis.tossling.desktop

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun ContextMenu(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

@JvmSuppressWildcards
@DBusInterfaceName("com.canonical.dbusmenu")
interface DbusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Twin<UInt32, Layout>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEvent>): List<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: List<Int>): Twin<List<Int>, List<Int>>

    class LayoutUpdated(path: String, revision: UInt32, parent: Int) : DBusSignal(path, revision, parent)
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Notifications")
interface Notifications : DBusInterface {
    fun Notify(appName: String, replacesId: UInt32, appIcon: String, summary: String, body: String, actions: List<String>, hints: Map<String, Variant<*>>, expireTimeout: Int): UInt32
}

@JvmSuppressWildcards
class Pixmap(@JvmField @field:Position(0) val width: Int, @JvmField @field:Position(1) val height: Int, @JvmField @field:Position(2) val pixels: ByteArray) : Struct()

@JvmSuppressWildcards
class ToolTip(@JvmField @field:Position(0) val icon: String, @JvmField @field:Position(1) val pixmaps: List<Pixmap>, @JvmField @field:Position(2) val title: String, @JvmField @field:Position(3) val text: String) : Struct()

@JvmSuppressWildcards
class Layout(@JvmField @field:Position(0) val id: Int, @JvmField @field:Position(1) val properties: Map<String, Variant<*>>, @JvmField @field:Position(2) val children: List<Variant<*>>) : Struct()

@JvmSuppressWildcards
class Twin<A, B>(@JvmField @field:Position(0) val first: A, @JvmField @field:Position(1) val second: B) : Tuple()

@JvmSuppressWildcards
class ItemProperties(@JvmField @field:Position(0) val id: Int, @JvmField @field:Position(1) val properties: Map<String, Variant<*>>) : Struct()

@JvmSuppressWildcards
class MenuEvent(@JvmField @field:Position(0) val id: Int, @JvmField @field:Position(1) val eventId: String, @JvmField @field:Position(2) val data: Variant<*>, @JvmField @field:Position(3) val timestamp: UInt32) : Struct()


class LinuxTray(private val build: () -> List<MenuEntry>, private val onOpen: () -> Unit) : TrayHost {

    private var connection: DBusConnection? = null
    private val name = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
    private val ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "tray-refresh").apply { isDaemon = true } }
    private val menu = MenuTree()

    override fun install(): Boolean = try {
        val bus = DBusConnectionBuilder.forSessionBus().build()
        connection = bus
        bus.requestBusName(name)
        bus.exportObject(ITEM_PATH, Item())
        bus.exportObject(MENU_PATH, menu)
        bus.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
            if (signal.name == WATCHER && signal.newOwner.isNotEmpty()) register()
        }
        menu.refresh(announce = false)
        register()
        ticker.scheduleWithFixedDelay({ runCatching { menu.refresh(announce = true) } }, REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS)
        true
    } catch (error: Exception) {
        Log.write("the tray over D-Bus did not start: $error")
        false
    } catch (error: LinkageError) {
        Log.write("the tray over D-Bus did not start: $error")
        false
    }

    override fun remove() {
        ticker.shutdownNow()
        runCatching { connection?.close() }
        connection = null
    }

    override fun notify(text: String) {
        val bus = connection ?: return
        runCatching {
            bus.getRemoteObject(NOTIFICATIONS, "/org/freedesktop/Notifications", Notifications::class.java)
                .Notify("Tossling", UInt32(0), notificationIcon, "Tossling", text, emptyList(), emptyMap(), -1)
        }.onFailure { Log.write("could not show a notification: ${it.message}") }
    }

    private fun register() {
        val bus = connection ?: return
        runCatching {
            bus.getRemoteObject(WATCHER, "/StatusNotifierWatcher", StatusNotifierWatcher::class.java).RegisterStatusNotifierItem(name)
            Log.write("the tray icon is in the panel")
        }.onFailure { Log.write("no panel for tray icons yet (${it.message}): waiting for one") }
    }

    private val notificationIcon: String by lazy {
        runCatching {
            File(Platform.cache, "tossling.png").apply {
                if (!isFile) LinuxTray::class.java.getResourceAsStream("/icon.png")!!.use { input -> outputStream().use { input.copyTo(it) } }
            }.absolutePath
        }.getOrDefault("")
    }

    private inner class Item : StatusNotifierItem, Properties {

        override fun getObjectPath() = ITEM_PATH

        override fun Activate(x: Int, y: Int) = Unit

        override fun SecondaryActivate(x: Int, y: Int) = Unit

        override fun ContextMenu(x: Int, y: Int) = Unit

        override fun Scroll(delta: Int, orientation: String) = Unit

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = GetAll(interfaceName)[propertyName] as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = Unit

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> {
            val pixmaps = ICON_SIZES.map(::pixmap)
            return mapOf(
                "Category" to Variant("ApplicationStatus"),
                "Id" to Variant("tossling"),
                "Title" to Variant("Tossling"),
                "Status" to Variant("Active"),
                "WindowId" to Variant(0),
                "IconName" to Variant(""),
                "IconThemePath" to Variant(""),
                "IconPixmap" to Variant(pixmaps, "a(iiay)"),
                "OverlayIconName" to Variant(""),
                "AttentionIconName" to Variant(""),
                "ToolTip" to Variant(ToolTip(icon = "", pixmaps = emptyList(), title = "Tossling", text = ""), "(sa(iiay)ss)"),
                "ItemIsMenu" to Variant(true),
                "Menu" to Variant(org.freedesktop.dbus.DBusPath(MENU_PATH)),
            )
        }

        private fun pixmap(size: Int): Pixmap {
            val image = TrayGlyph.draw(size = size)
            val bytes = ByteArray(size * size * 4)
            var i = 0
            for (y in 0 until size) for (x in 0 until size) {
                val argb = image.getRGB(x, y)
                bytes[i++] = (argb ushr 24).toByte()
                bytes[i++] = (argb ushr 16).toByte()
                bytes[i++] = (argb ushr 8).toByte()
                bytes[i++] = argb.toByte()
            }
            return Pixmap(width = size, height = size, pixels = bytes)
        }
    }

    private class Node(val id: Int, val entry: MenuEntry?, val children: List<Node>)

    private inner class MenuTree : DbusMenu, Properties {

        @Volatile private var root = Node(id = 0, entry = null, children = emptyList())
        @Volatile private var revision = 0L
        @Volatile private var signature = ""

        override fun getObjectPath() = MENU_PATH

        @Synchronized
        fun refresh(announce: Boolean) {
            var next = 1
            fun nodes(entries: List<MenuEntry>): List<Node> = entries.map { entry ->
                val id = next++
                Node(id = id, entry = entry, children = if (entry is MenuEntry.Submenu) nodes(entry.content()) else emptyList())
            }
            val tree = Node(id = 0, entry = null, children = nodes(build()))
            val current = signatureOf(tree)
            if (current == signature) return
            signature = current
            root = tree
            revision++
            if (announce) connection?.sendMessage(DbusMenu.LayoutUpdated(MENU_PATH, UInt32(revision), 0))
        }

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Twin<UInt32, Layout> {
            val node = find(root, parentId) ?: root
            return Twin(first = UInt32(revision), second = layout(node, recursionDepth))
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties> =
            ids.mapNotNull { id -> find(root, id)?.let { ItemProperties(id = id, properties = propertiesOf(it)) } }

        override fun GetProperty(id: Int, name: String): Variant<*> = find(root, id)?.let { propertiesOf(it)[name] } ?: Variant("")

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            when (eventId) {
                "clicked" -> (find(root, id)?.entry as? MenuEntry.Item)?.takeIf { it.enabled }?.let { item ->
                    SwingUtilities.invokeLater {
                        runCatching(item.action).onFailure { Log.write("a menu action failed: ${it.message}") }
                        ticker.schedule({ runCatching { refresh(announce = true) } }, 150, TimeUnit.MILLISECONDS)
                    }
                }

                "opened" -> if (id == 0) onOpen()
            }
        }

        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            events.forEach { Event(id = it.id, eventId = it.eventId, data = it.data, timestamp = it.timestamp) }
            return emptyList()
        }

        override fun AboutToShow(id: Int): Boolean {
            if (id == 0) onOpen()
            val before = revision
            refresh(announce = false)
            return revision != before
        }

        override fun AboutToShowGroup(ids: List<Int>): Twin<List<Int>, List<Int>> {
            if (0 in ids) onOpen()
            val before = revision
            refresh(announce = false)
            return Twin(first = if (revision != before) ids else emptyList(), second = emptyList())
        }

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = GetAll(interfaceName)[propertyName] as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = Unit

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = mapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )

        private fun layout(node: Node, depth: Int): Layout = Layout(
            id = node.id,
            properties = propertiesOf(node),
            children = if (depth == 0) emptyList() else node.children.map { Variant(layout(it, depth - 1), "(ia{sv}av)") },
        )

        private fun find(node: Node, id: Int): Node? = if (node.id == id) node else node.children.firstNotNullOfOrNull { find(it, id) }

        private fun propertiesOf(node: Node): Map<String, Variant<*>> = when (val entry = node.entry) {
            null -> mapOf("children-display" to Variant("submenu"))
            MenuEntry.Separator -> mapOf("type" to Variant("separator"))
            is MenuEntry.Submenu -> mapOf("label" to Variant(label(entry.text)), "enabled" to Variant(entry.enabled), "children-display" to Variant("submenu"))
            is MenuEntry.Item -> buildMap {
                put("label", Variant(label(entry.text)))
                put("enabled", Variant(entry.enabled))
                entry.checked?.let { checked ->
                    put("toggle-type", Variant("checkmark"))
                    put("toggle-state", Variant(if (checked) 1 else 0))
                }
            }
        }

        private fun signatureOf(node: Node): String = buildString {
            when (val entry = node.entry) {
                null -> append("root")
                MenuEntry.Separator -> append("-")
                is MenuEntry.Submenu -> append("${entry.text}|${entry.enabled}")
                is MenuEntry.Item -> append("${entry.text}|${entry.enabled}|${entry.checked}")
            }
            if (node.children.isNotEmpty()) append(node.children.joinToString(prefix = "[", postfix = "]", transform = ::signatureOf))
        }

        private fun label(text: String) = text.replace("_", "__")
    }

    private companion object {
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        const val WATCHER = "org.kde.StatusNotifierWatcher"
        const val NOTIFICATIONS = "org.freedesktop.Notifications"
        const val REFRESH_MS = 1_500L
        val ICON_SIZES = listOf(16, 22, 24, 32, 48)
    }
}
