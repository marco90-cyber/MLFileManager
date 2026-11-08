package com.marco90.mlfilemanager.net

import com.marco90.mlfilemanager.data.AppLog
import com.marco90.mlfilemanager.data.formatSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Trasferimenti dell'Accesso da rete (FTP e browser): avanzamento, velocità e tempo restante.
 * Li mostrano la pagina Accesso da rete, la notifica e la pagina web.
 */
object Transfers {

    data class Item(
        val id: Long,
        val name: String,
        /** "FTP" o "Browser". */
        val proto: String,
        /** true = file in arrivo sul telefono, false = file inviato dal telefono. */
        val upload: Boolean,
        val client: String,
        /** -1 se la dimensione non è nota (es. invio FTP). */
        val total: Long,
        val done: Long = 0,
        /** byte al secondo */
        val speed: Long = 0,
        val started: Long = System.currentTimeMillis(),
        val ended: Long = 0,
        val ok: Boolean = true,
    ) {
        val active get() = ended == 0L
        val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
        /** secondi rimanenti, -1 se non calcolabile */
        val eta get() = if (total > 0 && speed > 0) (total - done).coerceAtLeast(0) / speed else -1L
        val averageSpeed get() = ((ended.takeIf { it > 0 } ?: System.currentTimeMillis()) - started).let { ms -> if (ms > 0) done * 1000 / ms else 0 }
    }

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private val ids = AtomicLong(0)
    private const val KEEP_FINISHED = 15

    @Synchronized
    private fun put(it: Item) {
        val l = _items.value.toMutableList()
        val i = l.indexOfFirst { x -> x.id == it.id }
        if (i >= 0) l[i] = it else l.add(0, it)
        // tieni tutti quelli attivi e gli ultimi finiti
        val active = l.filter { x -> x.active }
        val done = l.filter { x -> !x.active }.sortedByDescending { x -> x.ended }.take(KEEP_FINISHED)
        _items.value = active + done
    }

    @Synchronized
    fun clearFinished() { _items.value = _items.value.filter { it.active } }

    fun begin(name: String, proto: String, upload: Boolean, client: String, total: Long): Handle {
        val item = Item(ids.incrementAndGet(), name, proto, upload, client, total)
        put(item)
        AppLog.i("Rete", "${if (upload) "Ricevo" else "Invio"} $name ($proto, $client${if (total > 0) ", ${formatSize(total)}" else ""})")
        return Handle(item)
    }

    class Handle internal constructor(private var item: Item) {
        private var done = 0L
        private var lastPublish = 0L
        private var lastSampleTime = System.currentTimeMillis()
        private var lastSampleBytes = 0L
        private var speed = 0.0
        @Volatile private var closed = false

        fun add(n: Long) {
            if (closed || n <= 0) return
            done += n
            val now = System.currentTimeMillis()
            val dt = now - lastSampleTime
            if (dt >= 500) {
                val inst = (done - lastSampleBytes) * 1000.0 / dt
                speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                lastSampleTime = now
                lastSampleBytes = done
            }
            if (now - lastPublish >= 300) {
                lastPublish = now
                item = item.copy(done = done, speed = speed.toLong())
                put(item)
            }
        }

        fun end(ok: Boolean) {
            if (closed) return
            closed = true
            val okReal = ok && (item.total <= 0 || done >= item.total)
            item = item.copy(done = done, speed = 0, ended = System.currentTimeMillis(), ok = okReal)
            put(item)
            val avg = item.averageSpeed
            AppLog.i(
                "Rete",
                "${if (okReal) "Completato" else "Interrotto"}: ${item.name} · ${formatSize(done)} · ${formatSize(avg)}/s"
            )
        }
    }

    /** Conta i byte letti; chiude il trasferimento alla chiusura dello stream. */
    class CountingInput(input: InputStream, private val h: Handle, private val total: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val r = super.read()
            if (r >= 0) { count++; h.add(1) }
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) { count += r; h.add(r.toLong()) }
            return r
        }
        override fun close() {
            try { super.close() } finally { h.end(total <= 0 || count >= total) }
        }
    }

    class CountingOutput(out: OutputStream, private val h: Handle) : FilterOutputStream(out) {
        override fun write(b: Int) { out.write(b); h.add(1) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); h.add(len.toLong()) }
    }

    // ---------- testi comuni ----------

    fun etaText(sec: Long): String = when {
        sec < 0 -> ""
        sec < 60 -> "$sec s"
        sec < 3600 -> "${sec / 60} min ${sec % 60} s"
        else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    }

    fun summary(it: Item, binary: Boolean = false): String {
        val size = if (it.total > 0) "${formatSize(it.done, binary)} di ${formatSize(it.total, binary)}" else formatSize(it.done, binary)
        if (!it.active) {
            val avg = formatSize(it.averageSpeed, binary) + "/s"
            val secs = ((it.ended - it.started) / 1000).coerceAtLeast(0)
            return if (it.ok) "$size · media $avg · ${etaText(secs).ifEmpty { "0 s" }}" else "Interrotto · $size"
        }
        val sp = if (it.speed > 0) " · ${formatSize(it.speed, binary)}/s" else ""
        val eta = it.eta.takeIf { e -> e >= 0 }?.let { e -> " · ${etaText(e)} rimanenti" } ?: ""
        return size + sp + eta
    }

    /** Elenco in JSON per la pagina web. */
    fun json(): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return _items.value.joinToString(",", "[", "]") {
            "{\"name\":\"${esc(it.name)}\",\"proto\":\"${it.proto}\",\"up\":${it.upload},\"client\":\"${esc(it.client)}\"," +
                "\"total\":${it.total},\"done\":${it.done},\"speed\":${it.speed},\"eta\":${it.eta},\"active\":${it.active},\"ok\":${it.ok}}"
        }
    }
}
