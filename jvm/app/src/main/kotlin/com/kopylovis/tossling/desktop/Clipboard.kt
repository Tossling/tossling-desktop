package com.kopylovis.tossling.desktop

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
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
    fun write(clip: Clip)
}

fun systemClipboard(): SystemClipboard = if (Platform.os == Os.WINDOWS) WindowsClipboard() else PollingClipboard()

open class AwtClipboard : SystemClipboard, ClipboardOwner {

    protected val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard

    override fun watch(onChange: () -> Unit) = Unit

    override fun isPrivate(): Boolean = false

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

    override fun write(clip: Clip) {
        val transferable: Transferable = when (clip) {
            is Clip.Text -> StringSelection(clip.text)
            is Clip.Image -> ImageSelection(ImageIO.read(ByteArrayInputStream(clip.bytes)) ?: return)
            is Clip.Files -> FileSelection(clip.files)
        }
        retry { clipboard.setContents(transferable, this) }
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

    companion object {
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

    private companion object {
        private const val POLL_MS = 600L
    }
}
