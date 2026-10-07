package com.kopylovis.tossling.desktop

import com.kopylovis.tossling.protocol.ClipItem
import com.kopylovis.tossling.protocol.ClipKind
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

class History(private val dir: File = Platform.home) {

    private val stored = JsonFile(file = File(dir, "history.json"), serializer = ListSerializer(ClipItem.serializer()), empty = emptyList())
    private val files = File(dir, "history").apply { mkdirs() }

    val items: StateFlow<List<ClipItem>> = stored.value

    fun addText(text: String, incoming: Boolean, device: String) {
        add(ClipItem(id = newId(), incoming = incoming, kind = ClipKind.TEXT, text = text.take(MAX_TEXT), device = device, time = System.currentTimeMillis()))
    }

    fun addImage(bytes: ByteArray, mime: String, incoming: Boolean, device: String) {
        val id = newId()
        val file = File(files, "$id.${if (mime == "image/jpeg") "jpg" else "png"}").apply { writeBytes(bytes) }
        add(ClipItem(id = id, incoming = incoming, kind = ClipKind.IMAGE, file = file.absolutePath, mime = mime, size = bytes.size.toLong(), device = device, time = System.currentTimeMillis()))
    }

    fun addFile(file: File, size: Long, incoming: Boolean, device: String) {
        add(ClipItem(id = newId(), incoming = incoming, kind = ClipKind.FILE, file = file.absolutePath, name = file.name, size = size, device = device, time = System.currentTimeMillis()))
    }

    fun clip(item: ClipItem): Clip? = when (item.kind) {
        ClipKind.TEXT -> item.text?.let { Clip.Text(it) }
        ClipKind.IMAGE -> item.file?.let(::File)?.takeIf { it.isFile }?.let { Clip.Image(bytes = it.readBytes(), mime = item.mime ?: "image/png") }
        ClipKind.FILE -> item.file?.let(::File)?.takeIf { it.exists() }?.let { Clip.Files(listOf(it)) }
    }

    fun clear() {
        stored.update { emptyList() }
        files.listFiles()?.forEach { it.delete() }
    }

    private fun add(item: ClipItem) {
        var dropped = emptyList<ClipItem>()
        stored.update { list ->
            val next = listOf(item) + list
            dropped = next.drop(KEEP)
            next.take(KEEP)
        }
        dropped.mapNotNull { it.file }.map(::File).filter { it.parentFile == files }.forEach { it.delete() }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        private const val KEEP = 30
        private const val MAX_TEXT = 200_000
    }
}
