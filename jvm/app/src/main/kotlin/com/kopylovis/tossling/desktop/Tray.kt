package com.kopylovis.tossling.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.awt.Color
import java.awt.Image
import java.awt.MouseInfo
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

class AppTray(private val build: JPopupMenu.() -> Unit) {

    private var icon: TrayIcon? = null

    fun install() {
        if (!SystemTray.isSupported()) {
            Log.write("this desktop has no system tray")
            return
        }
        MenuTheme.ensure()
        val trayIcon = TrayIcon(glyph(), "Tossling").apply {
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1 || event.isPopupTrigger) SwingUtilities.invokeLater(::open)
                }
            })
        }
        SystemTray.getSystemTray().add(trayIcon)
        icon = trayIcon
    }

    fun remove() {
        icon?.let { SystemTray.getSystemTray().remove(it) }
        icon = null
    }

    fun notify(text: String) {
        icon?.displayMessage("Tossling", text, TrayIcon.MessageType.NONE)
    }

    private fun glyph(): Image = TrayGlyph.image(size = SystemTray.getSystemTray().trayIconSize.width.coerceAtLeast(16))

    private fun open() {
        if (MenuTheme.ensure()) icon?.image = glyph()
        val point = MouseInfo.getPointerInfo()?.location ?: return
        val anchor = JDialog().apply {
            isUndecorated = true
            type = Window.Type.UTILITY
            isAlwaysOnTop = true
            background = Color(0, 0, 0, 0)
            setBounds(point.x, point.y, 1, 1)
        }
        val menu = JPopupMenu().apply(build)
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = anchor.dispose()
            override fun popupMenuCanceled(event: PopupMenuEvent) = anchor.dispose()
        })
        anchor.isVisible = true
        anchor.toFront()
        menu.show(anchor, 0, 0)
        menu.requestFocusInWindow()
    }
}

fun JComponent.item(text: String, enabled: Boolean = true, action: () -> Unit = {}) {
    add(JMenuItem(text).apply {
        putClientProperty("html.disable", true)
        isEnabled = enabled
        addActionListener { action() }
    })
}

fun JComponent.check(text: String, checked: Boolean, action: (Boolean) -> Unit) {
    add(JCheckBoxMenuItem(text, checked).apply {
        putClientProperty("html.disable", true)
        addActionListener { action(isSelected) }
    })
}

fun JComponent.submenu(text: String, enabled: Boolean = true, build: JMenu.() -> Unit) {
    add(JMenu(text).apply {
        putClientProperty("html.disable", true)
        isEnabled = enabled
        build()
    })
}

fun JComponent.separator() {
    add(JPopupMenu.Separator())
}

object TrayGlyph {

    private val DOTS = listOf(5.0 to 18.0, 6.47 to 14.31, 8.6 to 10.89, 11.8 to 8.63, 15.72 to 8.89)
    private const val DOT = 1.7
    private const val BALL = 3.8
    private const val FILL = 0.8
    private val SCALES = listOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)

    fun image(size: Int): Image = BaseMultiResolutionImage(*SCALES.map { draw(size = Math.round(size * it).toInt()) }.toTypedArray())

    fun draw(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        graphics.color = if (SystemTheme.isLight) Color(0x1F, 0x1F, 0x1F) else Color.WHITE
        graphics.translate(size * (1 - FILL) / 2, size * (1 - FILL) / 2)
        graphics.scale(size * FILL / 18.0, size * FILL / 18.0)
        DOTS.forEach { (x, y) -> graphics.fill(Ellipse2D.Double(x - 3 - DOT, y - 3 - DOT, 2 * DOT, 2 * DOT)) }
        graphics.fill(Ellipse2D.Double(14.5 - BALL, 4.5 - BALL, 2 * BALL, 2 * BALL))
        graphics.dispose()
        return image
    }
}

object SystemTheme {

    val isLight: Boolean
        get() = when (Platform.os) {
            Os.MAC -> true
            Os.LINUX -> false
            Os.WINDOWS -> runCatching {
                Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, PERSONALIZE, "SystemUsesLightTheme") &&
                    Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, PERSONALIZE, "SystemUsesLightTheme") == 1
            }.getOrDefault(false)
        }

    private const val PERSONALIZE = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
}

object MenuTheme {

    private var light: Boolean? = null

    fun ensure(): Boolean {
        val wanted = SystemTheme.isLight
        if (wanted == light && UIManager.getLookAndFeel() is FlatLaf) return false
        val changed = light != null && wanted != light
        apply(light = wanted)
        this.light = wanted
        return changed
    }

    private fun apply(light: Boolean) {
        val text = if (light) "#1A1A1A" else "#FFFFFF"
        val selection = if (light) "#E5E5E5" else "#414141"
        val items = listOf("MenuItem", "Menu", "CheckBoxMenuItem", "RadioButtonMenuItem")
        FlatLaf.setGlobalExtraDefaults(
            mapOf(
                "@menuBackground" to if (light) "#F9F9F9" else "#2B2B2B",
                "@menuSelectionBackground" to selection,
                "@menuItemMargin" to "5,12,5,12",
            ) + items.flatMap { listOf("$it.selectionBackground" to selection, "$it.selectionForeground" to text) },
        )
        runCatching { if (light) FlatLightLaf.setup() else FlatDarkLaf.setup() }.onFailure { Log.write("could not set up the menu look: ${it.message}") }
    }
}
