package com.marco90.mlfilemanager.net

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marco90.mlfilemanager.data.AppLog
import com.marco90.mlfilemanager.data.FileOps
import com.marco90.mlfilemanager.data.uniqueFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.coroutineContext

data class RemoteLogin(val user: String, val password: String)

data class RemoteItem(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

/** Elementi copiati da un altro dispositivo, da incollare in una cartella del telefono. */
data class RemoteClipData(val device: RemoteDevice, val login: RemoteLogin, val items: List<RemoteItem>)

object RemoteClip {
    var value by mutableStateOf<RemoteClipData?>(null)
}

/** Dispositivi salvati con le credenziali (solo su questo telefono). */
object DeviceStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val session = HashMap<String, RemoteLogin>()

    data class Saved(val device: RemoteDevice, val login: RemoteLogin?)

    fun saved(ctx: Context): List<Saved> = prefs(ctx).all.mapNotNull { (_, v) ->
        try {
            val o = JSONObject(v as String)
            val d = RemoteDevice(o.getString("name"), o.getString("host"), o.getInt("port"), o.optBoolean("fm"), online = false)
            val login = if (o.has("user")) RemoteLogin(o.getString("user"), o.optString("pass")) else null
            Saved(d, login)
        } catch (_: Exception) { null }
    }

    fun login(ctx: Context, d: RemoteDevice): RemoteLogin? =
        synchronized(session) { session[d.key] } ?: saved(ctx).firstOrNull { it.device.key == d.key }?.login

    fun remember(ctx: Context, d: RemoteDevice, login: RemoteLogin, persist: Boolean) {
        synchronized(session) { session[d.key] = login }
        val o = JSONObject().put("name", d.name).put("host", d.host).put("port", d.port).put("fm", d.fmPlus)
        if (persist) o.put("user", login.user).put("pass", login.password)
        prefs(ctx).edit().putString(d.key, o.toString()).apply()
    }

    fun forget(ctx: Context, d: RemoteDevice) {
        synchronized(session) { session.remove(d.key) }
        prefs(ctx).edit().remove(d.key).apply()
    }
}

/** Collegamento FTP verso un altro dispositivo. */
object RemoteFtp {

    fun connect(d: RemoteDevice, login: RemoteLogin): FTPClient {
        val c = FTPClient()
        c.controlEncoding = "UTF-8"
        c.connectTimeout = 8000
        c.setDataTimeout(Duration.ofSeconds(30))
        try {
            c.connect(d.host, d.port)
        } catch (e: IOException) {
            throw IOException("${d.name} non risponde. Controlla che sia sulla stessa rete e che il servizio sia attivo.")
        }
        if (!FTPReply.isPositiveCompletion(c.replyCode)) { safeClose(c); throw IOException("${d.name} ha rifiutato la connessione") }
        try { c.sendCommand("OPTS UTF8 ON") } catch (_: Exception) { }
        val user = login.user.ifBlank { "anonymous" }
        if (!c.login(user, login.password)) { safeClose(c); throw IOException("Nome utente o password errati") }
        c.soTimeout = 30000
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        return c
    }

    fun safeClose(c: FTPClient) {
        try { if (c.isConnected) c.logout() } catch (_: Exception) { }
        try { if (c.isConnected) c.disconnect() } catch (_: Exception) { }
    }

    fun list(c: FTPClient, path: String): List<RemoteItem> {
        val files = c.listFiles(path) ?: throw IOException("Impossibile leggere la cartella")
        if (!FTPReply.isPositiveCompletion(c.replyCode) && files.isEmpty() && c.replyCode >= 400) throw IOException("Impossibile leggere la cartella (${c.replyCode})")
        return files.filter { it != null && it.name != "." && it.name != ".." }.map {
            val p = join(path, it.name)
            RemoteItem(it.name, p, it.isDirectory, if (it.isDirectory) 0 else it.size, it.timestamp?.timeInMillis ?: 0)
        }.sortedWith(compareBy<RemoteItem> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
    fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
}

/** Sessione di navigazione: una connessione tenuta aperta, un comando alla volta. */
class RemoteSession(val device: RemoteDevice, val login: RemoteLogin) {
    private val mutex = Mutex()
    private var client: FTPClient? = null

    private suspend fun <T> use(block: (FTPClient) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val c = client?.takeIf { it.isConnected && try { it.sendNoOp() } catch (_: Exception) { false } }
                ?: RemoteFtp.connect(device, login).also { client = it }
            try { block(c) } catch (e: IOException) {
                // riprova una volta con una connessione nuova
                RemoteFtp.safeClose(c); client = null
                val c2 = RemoteFtp.connect(device, login).also { client = it }
                block(c2)
            }
        }
    }

    suspend fun list(path: String) = use { RemoteFtp.list(it, path) }
    suspend fun mkdir(path: String) = use { if (!it.makeDirectory(path)) throw IOException("Impossibile creare la cartella") }
    suspend fun rename(from: String, to: String) = use { if (!it.rename(from, to)) throw IOException("Impossibile rinominare") }
    suspend fun delete(items: List<RemoteItem>) = use { c -> items.forEach { deleteRec(c, it) } }

    private fun deleteRec(c: FTPClient, it: RemoteItem) {
        if (it.isDir) {
            RemoteFtp.list(c, it.path).forEach { ch -> deleteRec(c, ch) }
            if (!c.removeDirectory(it.path)) throw IOException("Impossibile eliminare ${it.name}")
        } else if (!c.deleteFile(it.path)) throw IOException("Impossibile eliminare ${it.name}")
    }

    fun close() {
        val c = client ?: return
        client = null
        Thread { RemoteFtp.safeClose(c) }.start()
    }
}

/** Trasferimenti tra questo telefono e un altro dispositivo, con avanzamento in Transfers. */
object RemoteOps {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ArrayList<Job>()

    /** Aumenta quando un trasferimento finisce: la schermata remota ricarica la cartella. */
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed

    fun cancelAll() { synchronized(jobs) { jobs.forEach { it.cancel() } } }

    private fun launch(title: String, block: suspend () -> String?) {
        val j = scope.launch {
            val msg = try { block() } catch (e: kotlinx.coroutines.CancellationException) {
                "Trasferimento annullato"
            } catch (e: Exception) {
                AppLog.e("Rete", "$title non riuscito", e)
                "Errore: ${e.message ?: e.javaClass.simpleName}"
            }
            _changed.value++
            FileOps.notifyChanged()
            if (msg != null) FileOps.post(msg)
        }
        synchronized(jobs) { jobs += j; jobs.removeAll { !it.isActive } }
    }

    /** Scarica file e cartelle dal dispositivo nella cartella [dest] del telefono. */
    fun download(d: RemoteDevice, login: RemoteLogin, items: List<RemoteItem>, dest: File, done: ((List<File>) -> Unit)? = null) =
        launch("Download") {
            val c = RemoteFtp.connect(d, login)
            val out = ArrayList<File>()
            try {
                for (it in items) out += downloadRec(c, d, it, dest)
            } finally { RemoteFtp.safeClose(c) }
            out.forEach { Net.scan(it) }
            if (done != null) { withContext(Dispatchers.Main) { done(out) }; null }
            else if (items.size == 1) "Scaricato «${items[0].name}» da ${d.name}" else "Scaricati ${items.size} elementi da ${d.name}"
        }

    private suspend fun downloadRec(c: FTPClient, d: RemoteDevice, it: RemoteItem, dest: File): File {
        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
        if (it.isDir) {
            val dir = uniqueFile(dest, it.name).apply { mkdirs() }
            RemoteFtp.list(c, it.path).forEach { ch -> downloadRec(c, d, ch, dir) }
            return dir
        }
        val target = uniqueFile(dest, it.name)
        val part = File(dest, ".${target.name}.part")
        val h = Transfers.begin(it.name, "Rete", true, d.name, it.size)
        try {
            val input = c.retrieveFileStream(it.path) ?: throw IOException("Impossibile scaricare ${it.name}")
            input.use { ins ->
                FileOutputStream(part).use { os ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                        val r = ins.read(buf)
                        if (r < 0) break
                        os.write(buf, 0, r)
                        h.add(r.toLong())
                    }
                }
            }
            if (!c.completePendingCommand()) throw IOException("Download di ${it.name} non completato")
            if (!part.renameTo(target)) throw IOException("Impossibile salvare ${it.name}")
            if (it.modified > 0) target.setLastModified(it.modified)
            h.end(true)
            return target
        } catch (e: Exception) {
            part.delete()
            h.end(false)
            throw e
        }
    }

    /** Invia file e cartelle del telefono nella cartella [remoteDir] del dispositivo. */
    fun upload(d: RemoteDevice, login: RemoteLogin, files: List<File>, remoteDir: String, deleteAfter: Boolean = false) =
        launch("Invio") {
            val c = RemoteFtp.connect(d, login)
            try {
                val existing = RemoteFtp.list(c, remoteDir).map { it.name }.toMutableSet()
                for (f in files) {
                    var name = f.name
                    var i = 1
                    val base = name.substringBeforeLast('.'); val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
                    while (name in existing) { name = "$base ($i)$ext"; i++ }
                    existing += name
                    uploadRec(c, d, f, RemoteFtp.join(remoteDir, name))
                }
            } finally { RemoteFtp.safeClose(c) }
            if (deleteAfter) files.forEach { it.deleteRecursively(); Net.scan(it) }
            if (files.size == 1) "Inviato «${files[0].name}» a ${d.name}" else "Inviati ${files.size} elementi a ${d.name}"
        }

    private suspend fun uploadRec(c: FTPClient, d: RemoteDevice, f: File, remote: String) {
        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
        if (f.isDirectory) {
            c.makeDirectory(remote)
            f.listFiles()?.forEach { uploadRec(c, d, it, RemoteFtp.join(remote, it.name)) }
            return
        }
        val h = Transfers.begin(f.name, "Rete", false, d.name, f.length())
        try {
            val os = c.storeFileStream(remote) ?: throw IOException("${d.name} non accetta il file ${f.name}")
            os.use { out ->
                FileInputStream(f).use { ins ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                        val r = ins.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        h.add(r.toLong())
                    }
                }
            }
            if (!c.completePendingCommand()) throw IOException("Invio di ${f.name} non completato")
            h.end(true)
        } catch (e: Exception) {
            h.end(false)
            throw e
        }
    }

    /** Apre un file del dispositivo: lo scarica nella cache e poi lo passa a [open]. */
    fun open(ctx: Context, d: RemoteDevice, login: RemoteLogin, item: RemoteItem, open: (File) -> Unit) {
        val dir = File(ctx.cacheDir, "remote").apply { mkdirs(); listFiles()?.forEach { it.deleteRecursively() } }
        download(d, login, listOf(item), dir) { files -> files.firstOrNull()?.let(open) }
    }
}
