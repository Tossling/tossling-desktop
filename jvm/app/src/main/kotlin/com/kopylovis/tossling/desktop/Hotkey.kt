package com.kopylovis.tossling.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import kotlin.concurrent.thread

@Suppress("FunctionName")
private interface KeyboardApi : StdCallLibrary {
    fun GetClipboardSequenceNumber(): Int
    fun keybd_event(key: Byte, scan: Byte, flags: Int, extra: BaseTSD.ULONG_PTR?)
}

object Hotkey {

    @Volatile var shortcut: String? = null
        private set

    fun start(onCopied: () -> Unit) {
        if (Platform.os != Os.WINDOWS) return
        thread(isDaemon = true, name = "hotkey") {
            val user32 = User32.INSTANCE
            val chosen = CHOICES.firstOrNull { (modifiers, _) -> user32.RegisterHotKey(null, ID, modifiers or MOD_NOREPEAT, KEY_C) }
            if (chosen == null) {
                Log.write("could not register a hotkey: both shortcuts are taken")
                return@thread
            }
            shortcut = chosen.second
            Log.write("${chosen.second} sends the clipboard")
            val message = WinUser.MSG()
            while (user32.GetMessage(message, null, 0, 0) > 0) {
                if (message.message != WM_HOTKEY) continue
                runCatching { copySelection() }.onFailure { Log.write("could not copy the selection: ${it.message}") }
                onCopied()
            }
        }
    }

    private fun copySelection() {
        val user32 = User32.INSTANCE
        val deadline = System.currentTimeMillis() + RELEASE_WAIT_MS
        while (MODIFIERS.any { user32.GetAsyncKeyState(it).toInt() and PRESSED != 0 } && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        if (isConsole()) return
        val before = keyboard.GetClipboardSequenceNumber()
        keyboard.keybd_event(VK_CONTROL, 0, 0, null)
        keyboard.keybd_event(KEY_C.toByte(), 0, 0, null)
        keyboard.keybd_event(KEY_C.toByte(), 0, KEYEVENTF_KEYUP, null)
        keyboard.keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, null)
        val copied = System.currentTimeMillis() + COPY_WAIT_MS
        while (keyboard.GetClipboardSequenceNumber() == before && System.currentTimeMillis() < copied) Thread.sleep(POLL_MS)
        Thread.sleep(SETTLE_MS)
    }

    private fun isConsole(): Boolean {
        val window = User32.INSTANCE.GetForegroundWindow() ?: return false
        val name = CharArray(CLASS_LENGTH)
        val length = User32.INSTANCE.GetClassName(window, name, name.size)
        return String(name, 0, length) in CONSOLES
    }

    private val keyboard by lazy { Native.load("user32", KeyboardApi::class.java) }

    private const val ID = 1
    private const val WM_HOTKEY = 0x0312
    private const val MOD_ALT = 0x1
    private const val MOD_CONTROL = 0x2
    private const val MOD_SHIFT = 0x4
    private const val MOD_WIN = 0x8
    private const val MOD_NOREPEAT = 0x4000
    private const val KEY_C = 0x43
    private const val VK_CONTROL: Byte = 0x11
    private const val KEYEVENTF_KEYUP = 0x2
    private const val PRESSED = 0x8000
    private const val POLL_MS = 15L
    private const val RELEASE_WAIT_MS = 1_000L
    private const val COPY_WAIT_MS = 400L
    private const val SETTLE_MS = 60L
    private const val CLASS_LENGTH = 256
    private val MODIFIERS = listOf(0x5B, 0x5C, 0x10, 0x11, 0x12)
    private val CONSOLES = setOf("ConsoleWindowClass", "CASCADIA_HOSTING_WINDOW_CLASS", "mintty", "VirtualConsoleClass")
    private val CHOICES = listOf(MOD_WIN or MOD_SHIFT to "Win+Shift+C", MOD_CONTROL or MOD_ALT or MOD_SHIFT to "Ctrl+Alt+Shift+C")
}
