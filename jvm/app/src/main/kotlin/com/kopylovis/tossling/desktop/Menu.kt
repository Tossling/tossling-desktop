package com.kopylovis.tossling.desktop

import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.Timer
import javax.swing.event.MenuEvent
import javax.swing.event.MenuListener

sealed interface MenuEntry {

    data class Item(val text: String, val enabled: Boolean = true, val checked: Boolean? = null, val action: () -> Unit = {}) : MenuEntry

    class Submenu(val text: String, val enabled: Boolean = true, val live: (() -> String)? = null, val content: () -> List<MenuEntry>) : MenuEntry

    data object Separator : MenuEntry
}

class MenuBuilder {

    val entries = mutableListOf<MenuEntry>()

    fun item(text: String, enabled: Boolean = true, action: () -> Unit = {}) {
        entries += MenuEntry.Item(text = text, enabled = enabled, action = action)
    }

    fun check(text: String, checked: Boolean, action: (Boolean) -> Unit) {
        entries += MenuEntry.Item(text = text, checked = checked) { action(!checked) }
    }

    fun submenu(text: String, enabled: Boolean = true, live: (() -> String)? = null, build: MenuBuilder.() -> Unit) {
        entries += MenuEntry.Submenu(text = text, enabled = enabled, live = live) { MenuBuilder().apply(build).entries }
    }

    fun separator() {
        entries += MenuEntry.Separator
    }
}

fun menuOf(build: MenuBuilder.() -> Unit): List<MenuEntry> = MenuBuilder().apply(build).entries

fun JComponent.render(entries: List<MenuEntry>) {
    entries.forEach { entry ->
        when (entry) {
            is MenuEntry.Item -> add(
                (if (entry.checked == null) JMenuItem(entry.text) else JCheckBoxMenuItem(entry.text, entry.checked)).apply {
                    putClientProperty("html.disable", true)
                    isEnabled = entry.enabled
                    addActionListener { entry.action() }
                },
            )

            is MenuEntry.Submenu -> add(
                JMenu(entry.text).apply {
                    putClientProperty("html.disable", true)
                    isEnabled = entry.enabled
                    render(entry.content())
                    entry.live?.let { live -> refreshWhileOpen(live = live) { render(entry.content()) } }
                },
            )

            MenuEntry.Separator -> add(JPopupMenu.Separator())
        }
    }
}

private fun JMenu.refreshWhileOpen(live: () -> String, fill: JMenu.() -> Unit) {
    var shown = live()
    val timer = Timer(REFRESH_MS) {
        val current = live()
        if (isPopupMenuVisible && current != shown) {
            shown = current
            removeAll()
            fill()
            popupMenu.pack()
        }
    }
    addMenuListener(object : MenuListener {
        override fun menuSelected(event: MenuEvent) = timer.start()
        override fun menuDeselected(event: MenuEvent) = timer.stop()
        override fun menuCanceled(event: MenuEvent) = timer.stop()
    })
}

private const val REFRESH_MS = 700
