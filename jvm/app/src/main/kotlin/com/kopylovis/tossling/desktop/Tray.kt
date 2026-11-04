package com.kopylovis.tossling.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Image
import java.awt.MouseInfo
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import javax.swing.JDialog
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

interface TrayHost {
    val shown: StateFlow<Boolean>
    fun install(): Boolean
    fun remove()
    fun notify(text: String)

    /** [percent] of the active transfers, null when there are none: the icon goes back to normal. [tip] says what is on its way. */
    fun showTransfers(percent: Int?, tip: String?) = Unit
}

class AppTray(private val build: () -> List<MenuEntry>, private val onOpen: () -> Unit) : TrayHost {

    private var icon: TrayIcon? = null

    @Volatile private var percent: Int? = null
    private val _shown = MutableStateFlow(false)

    override val shown: StateFlow<Boolean> = _shown.asStateFlow()

    override fun install(): Boolean {
        if (!SystemTray.isSupported()) {
            Log.write("this desktop has no system tray")
            return false
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
        _shown.value = true
        return true
    }

    override fun remove() {
        icon?.let { SystemTray.getSystemTray().remove(it) }
        icon = null
        _shown.value = false
    }

    override fun notify(text: String) {
        icon?.displayMessage("Tossling", text, TrayIcon.MessageType.NONE)
    }

    override fun showTransfers(percent: Int?, tip: String?) {
        this.percent = percent
        SwingUtilities.invokeLater {
            val trayIcon = icon ?: return@invokeLater
            trayIcon.image = glyph()
            trayIcon.toolTip = tip?.let { "Tossling — $it" } ?: "Tossling"
        }
    }

    private fun glyph(): Image {
        val size = SystemTray.getSystemTray().trayIconSize.width.coerceAtLeast(16)
        return percent?.let { TrayGlyph.progress(size = size, fraction = it / 100.0) } ?: TrayGlyph.image(size = size)
    }

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
        onOpen()
        val menu = JPopupMenu().apply { render(build()) }
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

object TrayGlyph {

    private val DOTS = listOf(5.0 to 18.0, 6.47 to 14.31, 8.6 to 10.89, 11.8 to 8.63, 15.72 to 8.89)
    private const val DOT = 1.7
    private const val BALL = 3.8
    private const val FILL = 0.8
    private val SCALES = listOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)
    private const val INNER = 0.7
    private const val TRACK_ALPHA = 80

    fun image(size: Int): Image = BaseMultiResolutionImage(*SCALES.map { draw(size = Math.round(size * it).toInt()) }.toTypedArray())

    fun progress(size: Int, fraction: Double): Image = BaseMultiResolutionImage(*SCALES.map { drawProgress(size = Math.round(size * it).toInt(), fraction = fraction) }.toTypedArray())

    /** The icon a little smaller inside a ring: a faint full circle and an arc from the top, clockwise, for the done share. */
    fun drawProgress(size: Int, fraction: Double): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val inner = draw(size = Math.round(size * INNER).toInt())
        graphics.drawImage(inner, (size - inner.width) / 2, (size - inner.height) / 2, null)
        val color = color()
        val width = maxOf(1.5f, size / 11f)
        val inset = width / 2.0 + 0.25
        val box = Ellipse2D.Double(inset, inset, size - 2 * inset, size - 2 * inset)
        graphics.stroke = BasicStroke(width)
        graphics.color = Color(color.red, color.green, color.blue, TRACK_ALPHA)
        graphics.draw(box)
        val share = fraction.coerceIn(0.0, 1.0)
        if (share > 0) {
            graphics.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            graphics.color = color
            graphics.draw(Arc2D.Double(box.x, box.y, box.width, box.height, 90.0, -360.0 * share, Arc2D.OPEN))
        }
        graphics.dispose()
        return image
    }

    private fun color(): Color = if (SystemTheme.isLight) Color(0x1F, 0x1F, 0x1F) else Color.WHITE

    fun draw(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        graphics.color = color()
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
