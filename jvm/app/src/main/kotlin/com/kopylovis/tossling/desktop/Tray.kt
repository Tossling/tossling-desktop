package com.kopylovis.tossling.desktop

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
        if (Platform.os == Os.WINDOWS) runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val size = SystemTray.getSystemTray().trayIconSize.width.coerceAtLeast(16)
        val trayIcon = TrayIcon(TrayGlyph.image(size = size), "Tossling").apply {
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

    private fun open() {
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
    private const val DOT = 1.55
    private const val BALL = 3.5
    private val SCALES = listOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)

    fun image(size: Int): Image = BaseMultiResolutionImage(*SCALES.map { draw(size = Math.round(size * it).toInt()) }.toTypedArray())

    fun draw(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        graphics.color = if (lightTaskbar()) Color(0x1F, 0x1F, 0x1F) else Color.WHITE
        graphics.scale(size / 18.0, size / 18.0)
        DOTS.forEach { (x, y) -> graphics.fill(Ellipse2D.Double(x - 3 - DOT, y - 3 - DOT, 2 * DOT, 2 * DOT)) }
        graphics.fill(Ellipse2D.Double(14.5 - BALL, 4.5 - BALL, 2 * BALL, 2 * BALL))
        graphics.dispose()
        return image
    }

    private fun lightTaskbar(): Boolean = when (Platform.os) {
        Os.MAC -> true
        Os.LINUX -> false
        Os.WINDOWS -> runCatching {
            Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, PERSONALIZE, "SystemUsesLightTheme") &&
                Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, PERSONALIZE, "SystemUsesLightTheme") == 1
        }.getOrDefault(false)
    }

    private const val PERSONALIZE = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
}
