package com.kopylovis.tossling.desktop

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.concurrent.thread

sealed interface Clip {
    val digest: String

    data class Text(val text: String) : Clip {
        override val digest: String get() = sha256(text.toByteArray())
    }

    class Image(val bytes: ByteArray, val mime: String) : Clip {
        override val digest: String by lazy { sha256(bytes) }
    }

    data class Files(val files: List<File>) : Clip {
        override val digest: String get() = sha256(files.joinToString(separator = "\n") { it.absolutePath }.toByteArray())
    }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

interface SystemClipboard {
    fun watch(onChange: () -> Unit)
    fun read(): Clip?
    fun isPrivate(): Boolean

    /** [secret]: the content arrived with `sens`, so it goes on the clipboard marked for history and clipboard managers to leave out. */
    fun write(clip: Clip, secret: Boolean = false)
    fun echo(clip: Clip): String = clip.digest
}

fun systemClipboard(): SystemClipboard = if (Platform.os == Os.WINDOWS) WindowsClipboard() else PollingClipboard()

open class AwtClipboard : SystemClipboard, ClipboardOwner {

    protected val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard

    override fun watch(onChange: () -> Unit) = Unit

    override fun isPrivate(): Boolean = false

    /** The native formats, with their data, that mark a secret on this platform's clipboard; none where it has no such mark. */
    protected open val secretMarks: List<Pair<String, ByteArray>> = emptyList()

    override fun read(): Clip? = retry {
        val contents = clipboard.getContents(null) ?: return@retry null
        when {
            contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
                (contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>()?.takeIf { it.isNotEmpty() }?.let { Clip.Files(it) }

            contents.isDataFlavorSupported(DataFlavor.imageFlavor) ->
                (contents.getTransferData(DataFlavor.imageFlavor) as? Image)?.let(::pngOf)?.let { Clip.Image(bytes = it, mime = "image/png") }

            contents.isDataFlavorSupported(DataFlavor.stringFlavor) ->
                (contents.getTransferData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotEmpty() }?.let { Clip.Text(it) }

            else -> null
        }
    }

    override fun write(clip: Clip, secret: Boolean) {
        val content: Transferable = when (clip) {
            is Clip.Text -> StringSelection(clip.text)
            is Clip.Image -> ImageSelection(ImageIO.read(ByteArrayInputStream(clip.bytes)) ?: return)
            is Clip.Files -> FileSelection(clip.files)
        }
        val marks = if (secret) secretMarks else emptyList()
        val transferable = if (marks.isEmpty()) content else MarkedSelection(content = content, marks = marks)
        retry { clipboard.setContents(transferable, this) }
    }

    override fun echo(clip: Clip): String = when (clip) {
        is Clip.Image -> runCatching { ImageIO.read(ByteArrayInputStream(clip.bytes))?.let(::pngOf)?.let(::sha256) }.getOrNull() ?: clip.digest
        else -> clip.digest
    }

    override fun lostOwnership(clipboard: Clipboard?, contents: Transferable?) = Unit

    protected fun <T> retry(block: () -> T): T? {
        repeat(times = 5) { attempt ->
            try {
                return block()
            } catch (busy: IllegalStateException) {
                Thread.sleep(40L * (attempt + 1))
            } catch (error: UnsupportedFlavorException) {
                return null
            } catch (error: java.io.IOException) {
                return null
            }
        }
        return null
    }

    private class ImageSelection(private val image: Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
    }

    private class FileSelection(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.javaFileListFlavor) files else throw UnsupportedFlavorException(flavor)
    }

    /**
     * [content] with extra native formats. Each mark gets a flavor of its own mapped to exactly that native name, and AWT
     * writes an InputStream flavor's bytes to the native format as they are, in the same clipboard write as the content.
     */
    internal class MarkedSelection(private val content: Transferable, marks: List<Pair<String, ByteArray>>) : Transferable {
        private val marks = marks.map { (native, data) -> markFlavor(native) to data }
        override fun getTransferDataFlavors(): Array<DataFlavor> = content.transferDataFlavors + marks.map { it.first }
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = marks.any { it.first == flavor } || content.isDataFlavorSupported(flavor)
        override fun getTransferData(flavor: DataFlavor): Any = marks.firstOrNull { it.first == flavor }?.second?.let(::ByteArrayInputStream) ?: content.getTransferData(flavor)
    }

    companion object {
        private val markFlavors = mutableMapOf<String, DataFlavor>()

        /** The flavor for the native format [native], mapped both ways: AWT writes it as it is and offers it when another app has. */
        @Synchronized
        internal fun markFlavor(native: String): DataFlavor = markFlavors.getOrPut(native) {
            val flavor = DataFlavor("application/x-tossling-mark-${native.lowercase().filter { it.isLetterOrDigit() }}; class=java.io.InputStream")
            (SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap)?.apply {
                setNativesForFlavor(flavor, arrayOf(native))
                setFlavorsForNative(native, arrayOf(flavor))
            }
            flavor
        }

        /** Whether [contents] carry the native format [native] with the value [value], as a password manager marks its copy. */
        internal fun hasMark(contents: Transferable, native: String, value: String): Boolean {
            val flavor = markFlavor(native)
            if (!contents.isDataFlavorSupported(flavor)) return false
            val data = when (val raw = contents.getTransferData(flavor)) {
                is java.io.InputStream -> raw.use { it.readNBytes(MARK_READ_LIMIT) }
                is ByteArray -> raw
                else -> return false
            }
            return data.decodeToString().trim { it.isWhitespace() || it == '\u0000' } == value
        }

        private const val MARK_READ_LIMIT = 256

        fun pngOf(image: Image): ByteArray? {
            val width = image.getWidth(null)
            val height = image.getHeight(null)
            if (width <= 0 || height <= 0) return null
            val buffered = image as? BufferedImage ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { copy ->
                copy.createGraphics().apply { drawImage(image, 0, 0, null) }.dispose()
            }
            return ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
        }
    }
}

class PollingClipboard : AwtClipboard() {

    // KDE's Klipper and other clipboard managers on Linux leave out content with this hint.
    override val secretMarks: List<Pair<String, ByteArray>> =
        if (Platform.os == Os.LINUX) listOf(PASSWORD_HINT to SECRET.toByteArray()) else emptyList()

    // KeePassXC and other password managers put the same hint on what they copy: the automatic sending leaves it out,
    // and Send the Clipboard Now sends it marked secret, as on Windows.
    override fun isPrivate(): Boolean {
        if (Platform.os != Os.LINUX) return false
        return retry { clipboard.getContents(null)?.let { hasMark(contents = it, native = PASSWORD_HINT, value = SECRET) } ?: false } ?: false
    }

    override fun watch(onChange: () -> Unit) {
        thread(isDaemon = true, name = "clipboard-poll") {
            var last = signature()
            while (true) {
                Thread.sleep(POLL_MS)
                val now = signature()
                if (now != last) {
                    last = now
                    onChange()
                }
            }
        }
    }

    private fun signature(): String? = retry {
        val contents = clipboard.getContents(null) ?: return@retry null
        val flavors = contents.transferDataFlavors.joinToString(separator = ",") { it.mimeType }
        val text = if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) contents.getTransferData(DataFlavor.stringFlavor) as? String else null
        val image = if (text == null && contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            (contents.getTransferData(DataFlavor.imageFlavor) as? Image)?.let { "${it.getWidth(null)}x${it.getHeight(null)}:${(it as? BufferedImage)?.let(::sample)}" }
        } else {
            null
        }
        "$flavors|${text?.let { sha256(it.toByteArray()) }}|$image"
    }

    private fun sample(image: BufferedImage): Int {
        var hash = 17
        val stepX = maxOf(1, image.width / 16)
        val stepY = maxOf(1, image.height / 16)
        for (y in 0 until image.height step stepY) for (x in 0 until image.width step stepX) hash = hash * 31 + image.getRGB(x, y)
        return hash
    }

    companion object {
        const val PASSWORD_HINT = "x-kde-passwordManagerHint"
        const val SECRET = "secret"
        private const val POLL_MS = 600L
    }
}
