package com.marco90.mlfilemanager.net

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Piccolo server FTP (modalità passiva e attiva), radice = memoria principale.
 * Accesso con il nome utente e la password scelti nell'app.
 */
class FtpServer(
    private val root: File,
    private val port: Int,
    private val userName: String,
    private val password: String,
    private val showHidden: Boolean,
    private val readOnly: Boolean,
    private val onClients: (Int) -> Unit,
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private val clients = AtomicInteger(0)
    private val rootCanon = root.canonicalPath

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        running = true
        thread(name = "ftp-accept", isDaemon = true) {
            while (running) {
                val c = try { s.accept() } catch (_: IOException) { break }
                pool.execute { Session(c).run() }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) { }
        pool.shutdownNow()
    }

    private inner class Session(private val ctrl: Socket) {
        private val reader = BufferedReader(InputStreamReader(ctrl.getInputStream(), Charsets.UTF_8))
        private val writer = BufferedWriter(OutputStreamWriter(ctrl.getOutputStream(), Charsets.UTF_8))
        private var cwd = "/"
        private var user: String? = null
        private var authed = false
        private var pasv: ServerSocket? = null
        private var active: InetSocketAddress? = null
        private var renameFrom: File? = null
        private var rest = 0L

        private fun reply(s: String) {
            writer.write(s)
            writer.write("\r\n")
            writer.flush()
        }

        fun run() {
            onClients(clients.incrementAndGet())
            try {
                ctrl.soTimeout = 15 * 60 * 1000
                reply("220 MLFileManager FTP pronto")
                while (running) {
                    val line = reader.readLine() ?: break
                    val cmd = line.substringBefore(' ').uppercase(Locale.ROOT)
                    val arg = if (line.contains(' ')) line.substringAfter(' ') else ""
                    if (!handle(cmd, arg)) break
                }
            } catch (_: Exception) {
            } finally {
                try { pasv?.close() } catch (_: IOException) { }
                try { ctrl.close() } catch (_: IOException) { }
                onClients(clients.decrementAndGet())
            }
        }

        /** false = chiudi la sessione. */
        private fun handle(cmd: String, arg: String): Boolean {
            when (cmd) {
                "USER" -> { user = arg; authed = false; reply("331 Inserisci la password") ; return true }
                "PASS" -> {
                    if (user.orEmpty().trim().equals(userName, ignoreCase = true) && arg == password) { authed = true; reply("230 Accesso eseguito") }
                    else reply("530 Nome utente o password errati")
                    return true
                }
                "QUIT" -> { reply("221 Arrivederci"); return false }
                "SYST" -> { reply("215 UNIX Type: L8"); return true }
                "FEAT" -> {
                    reply("211-Funzioni:\r\n UTF8\r\n MLSD\r\n MLST type*;size*;modify*;\r\n SIZE\r\n MDTM\r\n EPSV\r\n PASV\r\n REST STREAM\r\n211 Fine")
                    return true
                }
                "OPTS" -> { reply("200 OK"); return true }
                "NOOP" -> { reply("200 OK"); return true }
            }
            if (!authed) { reply("530 Accedi prima con USER e PASS"); return true }
            when (cmd) {
                "PWD", "XPWD" -> reply("257 \"${cwd.replace("\"", "\"\"")}\"")
                "CWD", "XCWD" -> {
                    val f = resolve(arg)
                    if (f != null && f.isDirectory) { cwd = virtual(f); reply("250 Cartella: $cwd") } else reply("550 Cartella non trovata")
                }
                "CDUP", "XCUP" -> {
                    val f = resolve("..")
                    if (f != null) cwd = virtual(f)
                    reply("250 Cartella: $cwd")
                }
                "TYPE", "MODE", "STRU" -> reply("200 OK")
                "PASV" -> {
                    pasv?.close()
                    val ss = ServerSocket(0)
                    pasv = ss
                    active = null
                    val ip = localIpv4()
                    val p = ss.localPort
                    reply("227 Modalità passiva (${ip.replace('.', ',')},${p / 256},${p % 256})")
                }
                "EPSV" -> {
                    pasv?.close()
                    val ss = ServerSocket(0)
                    pasv = ss
                    active = null
                    reply("229 Modalità passiva estesa (|||${ss.localPort}|)")
                }
                "PORT" -> {
                    val n = arg.split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (n.size != 6) { reply("501 Parametri errati"); return true }
                    active = InetSocketAddress("${n[0]}.${n[1]}.${n[2]}.${n[3]}", n[4] * 256 + n[5])
                    pasv?.close(); pasv = null
                    reply("200 OK")
                }
                "EPRT" -> {
                    val d = arg.firstOrNull()?.toString() ?: "|"
                    val parts = arg.split(d)
                    if (parts.size < 4) { reply("501 Parametri errati"); return true }
                    active = InetSocketAddress(parts[2], parts[3].toIntOrNull() ?: 0)
                    pasv?.close(); pasv = null
                    reply("200 OK")
                }
                "LIST", "NLST", "MLSD" -> {
                    val path = arg.split(' ').filterNot { it.startsWith("-") }.joinToString(" ")
                    val dir = if (path.isBlank()) resolve(".") else resolve(path)
                    if (dir == null || !dir.exists()) { reply("550 Non trovato"); return true }
                    val files = if (dir.isDirectory) (dir.listFiles() ?: emptyArray()).filter { showHidden || !it.name.startsWith(".") } else listOf(dir)
                    val text = buildString {
                        for (f in files) {
                            when (cmd) {
                                "NLST" -> append(f.name)
                                "MLSD" -> append(facts(f)).append(' ').append(f.name)
                                else -> append(lsLine(f))
                            }
                            append("\r\n")
                        }
                    }
                    reply("150 Invio elenco")
                    val data = openData() ?: run { reply("425 Impossibile aprire la connessione dati"); return true }
                    data.use { it.getOutputStream().write(text.toByteArray(Charsets.UTF_8)) }
                    reply("226 Fatto")
                }
                "MLST" -> {
                    val f = if (arg.isBlank()) resolve(".") else resolve(arg)
                    if (f == null || !f.exists()) reply("550 Non trovato")
                    else reply("250-Informazioni\r\n ${facts(f)} ${virtual(f)}\r\n250 Fine")
                }
                "SIZE" -> {
                    val f = resolve(arg)
                    if (f != null && f.isFile) reply("213 ${f.length()}") else reply("550 Non trovato")
                }
                "MDTM" -> {
                    val f = resolve(arg)
                    if (f != null && f.exists()) reply("213 ${stamp(f.lastModified())}") else reply("550 Non trovato")
                }
                "REST" -> { rest = arg.toLongOrNull() ?: 0L; reply("350 Ripresa da $rest") }
                "RETR" -> {
                    val f = resolve(arg)
                    if (f == null || !f.isFile) { reply("550 File non trovato"); return true }
                    reply("150 Invio di ${f.name}")
                    val data = openData() ?: run { reply("425 Impossibile aprire la connessione dati"); return true }
                    val h = Transfers.begin(f.name, "FTP", false, ctrl.inetAddress?.hostAddress ?: "?", (f.length() - rest).coerceAtLeast(0))
                    try {
                        data.use { s ->
                            FileInputStream(f).use { input ->
                                if (rest > 0) input.skip(rest)
                                input.copyTo(Transfers.CountingOutput(s.getOutputStream(), h), 256 * 1024)
                            }
                        }
                        h.end(true)
                        reply("226 Trasferimento completato")
                    } catch (e: IOException) { h.end(false); reply("426 Trasferimento interrotto") }
                    rest = 0
                }
                "STOR", "APPE" -> {
                    if (readOnly) { reply("550 Sola lettura"); return true }
                    val f = resolve(arg) ?: run { reply("550 Percorso non valido"); return true }
                    reply("150 Ricezione di ${f.name}")
                    val data = openData() ?: run { reply("425 Impossibile aprire la connessione dati"); return true }
                    // La dimensione di un file in arrivo via FTP non è nota: si mostrano byte ricevuti e velocità
                    val h = Transfers.begin(f.name, "FTP", true, ctrl.inetAddress?.hostAddress ?: "?", -1)
                    try {
                        data.use { sock ->
                            val input = Transfers.CountingInput(sock.getInputStream(), h, -1)
                            if (rest > 0 && cmd == "STOR") {
                                RandomAccessFile(f, "rw").use { raf ->
                                    raf.seek(rest)
                                    val buf = ByteArray(256 * 1024)
                                    while (true) { val r = input.read(buf); if (r < 0) break; raf.write(buf, 0, r) }
                                }
                            } else {
                                FileOutputStream(f, cmd == "APPE").use { out -> input.copyTo(out, 256 * 1024) }
                            }
                        }
                        h.end(true)
                        Net.scan(f)
                        reply("226 Trasferimento completato")
                    } catch (e: IOException) { h.end(false); reply("426 Trasferimento interrotto") }
                    rest = 0
                }
                "DELE" -> {
                    if (readOnly) { reply("550 Sola lettura"); return true }
                    val f = resolve(arg)
                    if (f != null && f.isFile && f.delete()) { Net.scan(f); reply("250 Eliminato") } else reply("550 Impossibile eliminare")
                }
                "MKD", "XMKD" -> {
                    if (readOnly) { reply("550 Sola lettura"); return true }
                    val f = resolve(arg)
                    if (f != null && f.mkdirs()) reply("257 \"${virtual(f)}\" creata") else reply("550 Impossibile creare")
                }
                "RMD", "XRMD" -> {
                    if (readOnly) { reply("550 Sola lettura"); return true }
                    val f = resolve(arg)
                    if (f != null && f.isDirectory && f.deleteRecursively()) reply("250 Eliminata") else reply("550 Impossibile eliminare")
                }
                "RNFR" -> {
                    if (readOnly) { reply("550 Sola lettura"); return true }
                    val f = resolve(arg)
                    if (f != null && f.exists()) { renameFrom = f; reply("350 Pronto per il nuovo nome") } else reply("550 Non trovato")
                }
                "RNTO" -> {
                    val from = renameFrom
                    val to = resolve(arg)
                    renameFrom = null
                    if (from != null && to != null && from.renameTo(to)) { Net.scan(from); Net.scan(to); reply("250 Rinominato") } else reply("550 Impossibile rinominare")
                }
                "ABOR" -> reply("226 OK")
                "SITE", "ALLO" -> reply("202 Non necessario")
                else -> reply("502 Comando non supportato")
            }
            return true
        }

        private fun openData(): Socket? = try {
            val p = pasv
            if (p != null) {
                p.soTimeout = 30_000
                val s = p.accept()
                p.close()
                pasv = null
                s
            } else {
                active?.let { a -> Socket().apply { connect(a, 15_000) } }
            }
        } catch (_: IOException) { null }

        private fun localIpv4(): String {
            val la: InetAddress = ctrl.localAddress
            if (la is Inet4Address) return la.hostAddress ?: "0.0.0.0"
            val h = la.hostAddress ?: ""
            if (h.contains('.')) return h.substringAfterLast(':')
            return Net.addresses().firstOrNull()?.second ?: "0.0.0.0"
        }

        /** Da percorso FTP a file reale, senza poter uscire dalla radice. */
        private fun resolve(arg: String): File? {
            val raw = if (arg.startsWith("/")) arg else "$cwd/$arg"
            val parts = ArrayList<String>()
            raw.split('/').forEach { seg ->
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts += seg
                }
            }
            val f = File(root, parts.joinToString("/"))
            return try { if (f.canonicalPath.startsWith(rootCanon)) f else null } catch (_: IOException) { null }
        }

        private fun virtual(f: File): String {
            val p = f.absolutePath.removePrefix(root.absolutePath)
            return if (p.isEmpty()) "/" else p
        }
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun facts(f: File): String =
        "type=${if (f.isDirectory) "dir" else "file"};size=${if (f.isDirectory) 0 else f.length()};modify=${stamp(f.lastModified())};"

    private fun lsLine(f: File): String {
        val perm = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
        val sixMonths = 180L * 24 * 3600 * 1000
        val recent = System.currentTimeMillis() - f.lastModified() < sixMonths
        val date = SimpleDateFormat(if (recent) "MMM dd HH:mm" else "MMM dd  yyyy", Locale.US).format(Date(f.lastModified()))
        val size = if (f.isDirectory) 4096 else f.length()
        return String.format(Locale.US, "%s 1 ml ml %13d %s %s", perm, size, date, f.name)
    }
}
