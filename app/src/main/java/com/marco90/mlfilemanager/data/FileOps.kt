package com.marco90.mlfilemanager.data

import android.content.Context
import android.media.MediaScannerConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** Avanzamento dell'operazione in corso (null = nessuna). */
data class OpProgress(
    val title: String,
    val current: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val files: Int = 0,
    val totalFiles: Int = 0,
)

/** Appunti per Copia / Taglia → Incolla. */
data class Clipboard(val files: List<File>, val cut: Boolean)

/**
 * Tutte le operazioni sui file: copia, sposta, elimina (o cestino), comprimi, estrai.
 * Girano in background; l'interfaccia osserva [progress] e [changed] per ricaricare.
 */
object FileOps {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _progress = MutableStateFlow<OpProgress?>(null)
    val progress: StateFlow<OpProgress?> = _progress

    /** Aumenta dopo ogni operazione finita: le schermate lo usano per ricaricare. */
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun clearMessage() { _message.value = null }

    var clipboard by mutableStateOf<Clipboard?>(null)

    fun notifyChanged() { _changed.value++ }

    fun cancel() { job?.cancel() }

    private fun launchOp(ctx: Context, title: String, block: suspend () -> String?) {
        if (job?.isActive == true) { _message.value = "Attendi la fine dell'operazione in corso"; return }
        job = scope.launch {
            _progress.value = OpProgress(title)
            val msg = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                "Operazione annullata"
            } catch (e: Exception) {
                AppLog.e("File", "$title non riuscita", e)
                "Errore: ${e.message ?: e.javaClass.simpleName}"
            }
            _progress.value = null
            if (msg != null) AppLog.i("File", "$title: $msg")
            _message.value = msg
            _changed.value++
            rescan(ctx)
        }
    }

    private val touched = mutableListOf<String>()
    private fun rescan(ctx: Context) {
        val paths = synchronized(touched) { touched.toTypedArray().also { touched.clear() } }
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx.applicationContext, paths, null, null)
    }
    private fun touch(f: File) { synchronized(touched) { if (touched.size < 2000) touched += f.absolutePath } }

    // ---------- Copia / Sposta ----------

    fun paste(ctx: Context, dest: File) {
        val clip = clipboard ?: return
        if (clip.cut) clipboard = null
        if (clip.cut) move(ctx, clip.files, dest) else copy(ctx, clip.files, dest)
    }

    fun copy(ctx: Context, sources: List<File>, dest: File) = launchOp(ctx, "Copia in corso") {
        for (s in sources) if (dest.absolutePath.startsWith(s.absolutePath + "/") || dest == s) return@launchOp "Non puoi copiare una cartella dentro sé stessa"
        val (bytes, count) = measure(sources)
        var done = 0L
        var n = 0
        for (s in sources) {
            val target = uniqueFile(dest, s.name)
            copyRec(s, target) { b, name ->
                done += b
                if (b == 0L) n++
                _progress.value = OpProgress("Copia in corso", name, done, bytes, n, count)
            }
        }
        if (sources.size == 1) "Copiato «${sources[0].name}»" else "Copiati ${sources.size} elementi"
    }

    fun move(ctx: Context, sources: List<File>, dest: File) = launchOp(ctx, "Spostamento in corso") {
        for (s in sources) if (dest.absolutePath.startsWith(s.absolutePath + "/") || dest == s) return@launchOp "Non puoi spostare una cartella dentro sé stessa"
        val (bytes, count) = measure(sources)
        var done = 0L
        var n = 0
        for (s in sources) {
            if (s.parentFile == dest) continue
            val target = uniqueFile(dest, s.name)
            if (s.renameTo(target)) {
                touch(s); touch(target)
                n++
                _progress.value = OpProgress("Spostamento in corso", s.name, done, bytes, n, count)
                continue
            }
            // Su un'altra memoria (es. scheda SD): copia e poi elimina
            copyRec(s, target) { b, name ->
                done += b
                if (b == 0L) n++
                _progress.value = OpProgress("Spostamento in corso", name, done, bytes, n, count)
            }
            deleteRec(s)
        }
        if (sources.size == 1) "Spostato «${sources[0].name}»" else "Spostati ${sources.size} elementi"
    }

    fun copyAs(ctx: Context, src: File, dest: File) = launchOp(ctx, "Salvataggio") {
        val total = src.length()
        var done = 0L
        copyRec(src, dest) { b, name ->
            done += b
            _progress.value = OpProgress("Salvataggio", name, done, total, 0, 0)
        }
        "Salvato in ${dest.parentFile?.name}/${dest.name}"
    }

    private fun measure(files: List<File>): Pair<Long, Int> {
        var b = 0L
        var c = 0
        files.forEach { f -> f.walkTopDown().forEach { if (it.isFile) { b += it.length(); c++ } } }
        return b to c
    }

    /** onBytes(n, nome): n > 0 byte copiati, n == 0 file finito. */
    private suspend fun copyRec(src: File, dst: File, onBytes: (Long, String) -> Unit) {
        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) throw IOException("Impossibile creare ${dst.name}")
            src.listFiles()?.forEach { copyRec(it, File(dst, it.name), onBytes) }
        } else {
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (!coroutineContext.isActive) {
                            out.close(); dst.delete()
                            throw kotlinx.coroutines.CancellationException()
                        }
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        onBytes(r.toLong(), src.name)
                    }
                }
            }
            dst.setLastModified(src.lastModified())
            touch(dst)
            onBytes(0, src.name)
        }
    }

    // ---------- Elimina / Cestino ----------

    fun delete(ctx: Context, files: List<File>, toTrash: Boolean) = launchOp(ctx, if (toTrash) "Spostamento nel cestino" else "Eliminazione") {
        var n = 0
        for (f in files) {
            _progress.value = OpProgress(if (toTrash) "Spostamento nel cestino" else "Eliminazione", f.name, 0, 0, n, files.size)
            val inTrash = f.absolutePath.startsWith(Trash.dir.absolutePath)
            if (toTrash && !inTrash) Trash.put(f) else { deleteRec(f); Trash.forget(f) }
            touch(f)
            n++
        }
        val what = if (files.size == 1) "«${files[0].name}»" else "${files.size} elementi"
        if (toTrash) "$what nel cestino" else "$what eliminati"
    }

    internal suspend fun deleteRec(f: File) {
        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
        if (f.isDirectory) f.listFiles()?.forEach { deleteRec(it) }
        if (!f.delete() && f.exists()) throw IOException("Impossibile eliminare ${f.name}")
    }

    fun restore(ctx: Context, files: List<File>) = launchOp(ctx, "Ripristino") {
        files.forEach { Trash.restore(it)?.let { r -> touch(r) } }
        if (files.size == 1) "Ripristinato" else "Ripristinati ${files.size} elementi"
    }

    // ---------- Comprimi / Estrai ----------

    fun zip(ctx: Context, sources: List<File>, target: File) = launchOp(ctx, "Compressione") {
        val (bytes, count) = measure(sources)
        var done = 0L
        var n = 0
        ZipOutputStream(FileOutputStream(target).buffered()).use { zos ->
            val buf = ByteArray(256 * 1024)
            for (s in sources) {
                val base = s.parentFile ?: s
                s.walkTopDown().forEach { f ->
                    if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                    if (f == target) return@forEach
                    val rel = f.absolutePath.removePrefix(base.absolutePath).trimStart('/')
                    if (f.isDirectory) {
                        zos.putNextEntry(ZipEntry("$rel/").apply { time = f.lastModified() })
                        zos.closeEntry()
                    } else {
                        zos.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                        FileInputStream(f).use { input ->
                            while (true) {
                                val r = input.read(buf)
                                if (r < 0) break
                                zos.write(buf, 0, r)
                                done += r
                                _progress.value = OpProgress("Compressione", f.name, done, bytes, n, count)
                            }
                        }
                        zos.closeEntry()
                        n++
                    }
                }
            }
        }
        touch(target)
        "Creato «${target.name}»"
    }

    fun unzip(ctx: Context, zip: File, dest: File) = launchOp(ctx, "Estrazione") {
        if (extOf(zip.name) !in setOf("zip", "jar", "apk")) return@launchOp "Per ora si possono estrarre solo i file ZIP"
        if (!dest.exists()) dest.mkdirs()
        val total = zip.length()
        var done = 0L
        var n = 0
        val canon = dest.canonicalPath
        ZipInputStream(FileInputStream(zip).buffered()).use { zis ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val e = zis.nextEntry ?: break
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(canon)) continue // protezione "zip slip"
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { os ->
                        while (true) {
                            val r = zis.read(buf)
                            if (r < 0) break
                            os.write(buf, 0, r)
                        }
                    }
                    if (e.time > 0) out.setLastModified(e.time)
                    touch(out)
                    n++
                    done += e.compressedSize.coerceAtLeast(0)
                    _progress.value = OpProgress("Estrazione", out.name, done.coerceAtMost(total), total, n, 0)
                }
            }
        }
        "Estratti $n file in «${dest.name}»"
    }

    // ---------- Semplici (immediate) ----------

    fun rename(f: File, newName: String): String? {
        invalidName(newName)?.let { return it }
        val t = File(f.parentFile, newName)
        if (t.exists()) return "Esiste già un elemento con questo nome"
        if (!f.renameTo(t)) return "Impossibile rinominare"
        notifyChanged()
        return null
    }

    fun newFolder(dir: File, name: String): String? {
        invalidName(name)?.let { return it }
        val t = File(dir, name)
        if (t.exists()) return "Esiste già un elemento con questo nome"
        if (!t.mkdirs()) return "Impossibile creare la cartella"
        notifyChanged()
        return null
    }

    fun newFile(dir: File, name: String): String? {
        invalidName(name)?.let { return it }
        val t = File(dir, name)
        if (t.exists()) return "Esiste già un elemento con questo nome"
        return try {
            if (!t.createNewFile()) "Impossibile creare il file" else { notifyChanged(); null }
        } catch (e: IOException) { "Impossibile creare il file: ${e.message}" }
    }
}

/**
 * Cestino: i file vanno in /Memoria principale/.MLTrash con un nome unico;
 * un piccolo indice ricorda da dove venivano.
 */
object Trash {
    val dir: File get() = File(ROOT, ".MLTrash")
    private val index: File get() = File(dir, ".index.json")

    data class Entry(val file: File, val original: String, val deleted: Long)

    @Synchronized
    private fun read(): JSONObject = try {
        if (index.exists()) JSONObject(index.readText()) else JSONObject()
    } catch (_: Exception) { JSONObject() }

    @Synchronized
    private fun write(o: JSONObject) {
        dir.mkdirs()
        index.writeText(o.toString())
        File(dir, ".nomedia").let { if (!it.exists()) it.createNewFile() }
    }

    @Synchronized
    fun put(f: File) {
        dir.mkdirs()
        val name = "${System.currentTimeMillis()}_${f.name}"
        val t = File(dir, name)
        if (!f.renameTo(t)) throw IOException("Impossibile spostare ${f.name} nel cestino")
        val o = read()
        o.put(name, JSONObject().put("path", f.absolutePath).put("time", System.currentTimeMillis()))
        write(o)
    }

    @Synchronized
    fun forget(f: File) {
        if (f.parentFile != dir) return
        val o = read()
        if (o.has(f.name)) { o.remove(f.name); write(o) }
    }

    @Synchronized
    fun restore(f: File): File? {
        val o = read()
        val info = o.optJSONObject(f.name)
        val orig = info?.optString("path")?.takeIf { it.isNotEmpty() }?.let { File(it) }
            ?: File(ROOT, f.name.substringAfter('_'))
        orig.parentFile?.mkdirs()
        val target = uniqueFile(orig.parentFile ?: ROOT, orig.name)
        if (!f.renameTo(target)) return null
        o.remove(f.name)
        write(o)
        return target
    }

    fun list(): List<Entry> {
        val o = read()
        return (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .map { f ->
                val info = o.optJSONObject(f.name)
                Entry(f, info?.optString("path") ?: f.name.substringAfter('_'), info?.optLong("time") ?: f.lastModified())
            }
            .sortedByDescending { it.deleted }
    }
}
