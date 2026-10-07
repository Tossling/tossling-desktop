package com.kopylovis.tossling.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import kotlin.concurrent.thread

@Suppress("FunctionName")
private interface ClipboardApi : StdCallLibrary {
    fun AddClipboardFormatListener(hwnd: HWND): Boolean
    fun RegisterClipboardFormatW(name: WString): Int
    fun IsClipboardFormatAvailable(format: Int): Boolean
    fun OpenClipboard(hwnd: HWND?): Boolean
    fun CloseClipboard(): Boolean
    fun GetClipboardData(format: Int): Pointer?
}

@Suppress("FunctionName")
private interface GlobalMemory : StdCallLibrary {
    fun GlobalLock(memory: Pointer): Pointer?
    fun GlobalUnlock(memory: Pointer): Boolean
}

class WindowsClipboard : AwtClipboard() {

    private val api = Native.load("user32", ClipboardApi::class.java)
    private val memory = Native.load("kernel32", GlobalMemory::class.java)
    private val exclude = format("ExcludeClipboardContentFromMonitorProcessing")
    private val viewerIgnore = format("Clipboard Viewer Ignore")
    private val history = format("CanIncludeInClipboardHistory")
    private val cloud = format("CanUploadToCloudClipboard")
    private var procedure: WinUser.WindowProc? = null

    override fun watch(onChange: () -> Unit) {
        thread(isDaemon = true, name = "clipboard-listener") {
            val user32 = User32.INSTANCE
            val instance = Kernel32.INSTANCE.GetModuleHandle(null)
            val callback = WinUser.WindowProc { hwnd, message, wParam, lParam ->
                if (message == WM_CLIPBOARDUPDATE) {
                    runCatching(onChange).onFailure { Log.write("clipboard change failed: ${it.message}") }
                    LRESULT(0)
                } else {
                    user32.DefWindowProc(hwnd, message, wParam, lParam)
                }
            }
            procedure = callback
            val windowClass = WinUser.WNDCLASSEX().apply {
                hInstance = instance
                lpfnWndProc = callback
                lpszClassName = CLASS_NAME
            }
            user32.RegisterClassEx(windowClass)
            val window = user32.CreateWindowEx(0, CLASS_NAME, "Tossling", 0, 0, 0, 0, 0, HWND(Pointer.createConstant(HWND_MESSAGE)), null, instance, null)
            if (window == null || !api.AddClipboardFormatListener(window)) {
                Log.write("the clipboard listener did not start (error ${Kernel32.INSTANCE.GetLastError()}), watching by polling")
                PollingClipboard().watch(onChange)
                return@thread
            }
            Log.write("watching the clipboard")
            val message = WinUser.MSG()
            while (user32.GetMessage(message, null, 0, 0) > 0) {
                user32.TranslateMessage(message)
                user32.DispatchMessage(message)
            }
        }
    }

    override fun isPrivate(): Boolean {
        if (api.IsClipboardFormatAvailable(exclude) || api.IsClipboardFormatAvailable(viewerIgnore)) return true
        return isZero(history) || isZero(cloud)
    }

    private fun isZero(format: Int): Boolean {
        if (!api.IsClipboardFormatAvailable(format) || !open()) return false
        try {
            val handle = api.GetClipboardData(format) ?: return false
            val data = memory.GlobalLock(handle) ?: return false
            try {
                return data.getInt(0) == 0
            } finally {
                memory.GlobalUnlock(handle)
            }
        } finally {
            api.CloseClipboard()
        }
    }

    private fun open(): Boolean {
        repeat(times = 5) { attempt ->
            if (api.OpenClipboard(null)) return true
            Thread.sleep(20L * (attempt + 1))
        }
        return false
    }

    private fun format(name: String): Int = api.RegisterClipboardFormatW(WString(name))

    private companion object {
        private const val CLASS_NAME = "TosslingClipboardListener"
        private const val WM_CLIPBOARDUPDATE = 0x031D
        private const val HWND_MESSAGE = -3L
    }
}
