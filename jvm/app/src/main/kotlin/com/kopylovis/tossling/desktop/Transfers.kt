package com.kopylovis.tossling.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A file being sent or received, as the tray shows it. [total] is 0 when the size is not known. */
data class TransferInfo(val id: Long, val name: String, val incoming: Boolean, val done: Long, val total: Long)

/** Thrown from inside a transfer the user cancelled: the transfer stops, cleans up and says "cancelled". */
class TransferCancelled : IOException("cancelled")

/** The files on their way right now. A transfer reports its progress and checks there whether the user cancelled it. */
class Transfers {

    private val ids = AtomicLong()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()
    private val _items = MutableStateFlow<List<TransferInfo>>(emptyList())

    val items: StateFlow<List<TransferInfo>> = _items.asStateFlow()

    fun start(name: String, incoming: Boolean, total: Long): Transfer {
        val id = ids.incrementAndGet()
        _items.update { it + TransferInfo(id = id, name = name, incoming = incoming, done = 0, total = maxOf(total, 0)) }
        return Transfer(id)
    }

    /** Asks the transfer to stop: it notices at its next chunk. */
    fun cancel(id: Long) {
        if (_items.value.any { it.id == id }) cancelled += id
    }

    inner class Transfer(val id: Long) {

        val isCancelled: Boolean get() = id in cancelled

        /** Records [done] bytes, or throws [TransferCancelled] when the user cancelled the transfer. */
        fun progress(done: Long) {
            if (isCancelled) throw TransferCancelled()
            _items.update { list -> list.map { if (it.id == id) it.copy(done = done) else it } }
        }

        fun end() {
            _items.update { list -> list.filterNot { it.id == id } }
            cancelled -= id
        }
    }
}

/** The share of all active transfers that is done: the sum of done over the sum of total, null when nothing has a size. */
fun transferFraction(items: List<TransferInfo>): Double? {
    val total = items.sumOf { it.total }
    if (total <= 0) return null
    val done = items.sumOf { minOf(it.done, it.total) }
    return (done.toDouble() / total).coerceIn(0.0, 1.0)
}

/** "Sending report.pdf: 40%, 12.0 of 30.0 MB"; the unit of the done part is left out when it is the total's. */
fun transferLine(transfer: TransferInfo): String {
    val name = transfer.name
    if (transfer.total <= 0) return if (transfer.incoming) L("Получаю $name", "Receiving $name") else L("Отправляю $name", "Sending $name")
    val done = minOf(transfer.done, transfer.total)
    val percent = done * 100 / transfer.total
    val total = sizeText(transfer.total)
    var shown = if (done <= 0) "0" else sizeText(done)
    if (shown.substringAfterLast(' ') == total.substringAfterLast(' ') && ' ' in shown) shown = shown.substringBeforeLast(' ')
    return if (transfer.incoming) L("Получаю $name: $percent%, $shown из $total", "Receiving $name: $percent%, $shown of $total")
    else L("Отправляю $name: $percent%, $shown из $total", "Sending $name: $percent%, $shown of $total")
}

/** The tooltip for the active transfers with their combined percentage: "Sending report.pdf: 42%", or null for none. */
fun transferTip(items: List<TransferInfo>): String? {
    if (items.isEmpty()) return null
    val single = items.singleOrNull()
    val head = when {
        single == null -> L("Передачи (${items.size})", "${items.size} transfers")
        single.incoming -> L("Получаю ${single.name}", "Receiving ${single.name}")
        else -> L("Отправляю ${single.name}", "Sending ${single.name}")
    }
    val percent = transferFraction(items)?.let { (it * 100).toInt() } ?: return head
    return "$head: $percent%"
}

/**
 * Keeps the tray icon and tooltip in step with [transfers]: the icon in a progress ring while anything is on its way,
 * the normal icon after. Redrawn only when the whole percent or the tooltip changes, at most every quarter of a second.
 */
fun CoroutineScope.showTransfers(transfers: Transfers, tray: () -> TrayHost) = launch {
    var shown: Pair<Int?, String?> = null to null
    transfers.items.collect { items ->
        val percent = if (items.isEmpty()) null else ((transferFraction(items) ?: 0.0) * 100).toInt()
        val next = percent to transferTip(items)
        if (next != shown) {
            shown = next
            runCatching { tray().showTransfers(percent = next.first, tip = next.second) }.onFailure { Log.write("could not show the transfers in the tray: ${it.message}") }
            delay(REDRAW_MS)
        }
    }
}

private const val REDRAW_MS = 250L
