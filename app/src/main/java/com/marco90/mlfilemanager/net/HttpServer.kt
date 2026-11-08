package com.marco90.mlfilemanager.net

import android.util.Base64
import com.marco90.mlfilemanager.data.formatSize
import com.marco90.mlfilemanager.data.mimeOf
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Accesso da browser: sfoglia, scarica, carica, crea cartelle ed elimina. */
class HttpServer(
    port: Int,
    private val root: File,
    private val userName: String,
    private val password: String,
    private val showHidden: Boolean,
    private val readOnly: Boolean,
) : NanoHTTPD(port) {

    private val rootCanon = root.canonicalPath

    fun startServer() = start(SOCKET_READ_TIMEOUT, false)

    override fun serve(session: IHTTPSession): Response {
        if (!authorized(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Nome utente e password richiesti").apply {
                addHeader("WWW-Authenticate", "Basic realm=\"MLFileManager\", charset=\"UTF-8\"")
            }
        }
        return try {
            when (session.uri) {
                "/", "/browse" -> page(param(session, "path") ?: "/")
                "/dl" -> download(param(session, "path") ?: "")
                "/upload" -> upload(session)
                "/mkdir" -> mkdir(session)
                "/delete" -> delete(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Non trovato")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Errore: ${e.message}")
        }
    }

    private fun authorized(s: IHTTPSession): Boolean {
        val h = s.headers["authorization"] ?: return false
        if (!h.startsWith("Basic ")) return false
        val decoded = try { String(Base64.decode(h.removePrefix("Basic ").trim(), Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { return false }
        return decoded.substringBefore(':').trim().equals(userName, ignoreCase = true) && decoded.substringAfter(':') == password
    }

    private fun param(s: IHTTPSession, name: String): String? = s.parameters[name]?.firstOrNull()

    private fun resolve(vpath: String): File? {
        val parts = ArrayList<String>()
        vpath.split('/').forEach { seg ->
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        val f = File(root, parts.joinToString("/"))
        return try { if (f.canonicalPath.startsWith(rootCanon)) f else null } catch (_: IOException) { null }
    }

    private fun vpath(f: File): String = f.absolutePath.removePrefix(root.absolutePath).ifEmpty { "/" }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun download(path: String): Response {
        val f = resolve(path)
        if (f == null || !f.isFile) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File non trovato")
        return newFixedLengthResponse(Response.Status.OK, mimeOf(f.name), FileInputStream(f), f.length()).apply {
            addHeader("Content-Disposition", "attachment; filename*=UTF-8''${enc(f.name)}")
        }
    }

    private fun upload(s: IHTTPSession): Response {
        if (readOnly) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Sola lettura")
        if (s.method != Method.POST) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Usa POST")
        val dir = resolve(param(s, "path") ?: "/")
        if (dir == null || !dir.isDirectory) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Cartella non trovata")
        val files = HashMap<String, String>()
        s.parseBody(files)
        val tmp = files["file"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Nessun file")
        val name = (s.parameters["file"]?.firstOrNull() ?: "file").substringAfterLast('/').substringAfterLast('\\')
        var target = File(dir, name)
        var i = 1
        while (target.exists()) {
            val base = name.substringBeforeLast('.', name)
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            target = File(dir, "$base ($i)$ext"); i++
        }
        File(tmp).copyTo(target)
        File(tmp).delete()
        Net.scan(target)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
    }

    private fun mkdir(s: IHTTPSession): Response {
        if (readOnly) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Sola lettura")
        s.parseBody(HashMap())
        val dir = resolve(param(s, "path") ?: "/")
        val name = param(s, "name")?.trim().orEmpty()
        if (dir == null || name.isEmpty() || name.any { it in "\\/:*?\"<>|" }) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Nome non valido")
        return if (File(dir, name).mkdirs()) newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        else newFixedLengthResponse(Response.Status.CONFLICT, "text/plain", "Impossibile creare")
    }

    private fun delete(s: IHTTPSession): Response {
        if (readOnly) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Sola lettura")
        s.parseBody(HashMap())
        val f = resolve(param(s, "path") ?: "")
        if (f == null || f.absolutePath == root.absolutePath) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Percorso non valido")
        val ok = f.deleteRecursively()
        Net.scan(f)
        return if (ok) newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        else newFixedLengthResponse(Response.Status.CONFLICT, "text/plain", "Impossibile eliminare")
    }

    private fun page(path: String): Response {
        val dir = resolve(path)
        if (dir == null || !dir.isDirectory) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Cartella non trovata")
        val vp = vpath(dir)
        val df = SimpleDateFormat("d MMM yyyy HH:mm", Locale.ITALIAN)
        val files = (dir.listFiles() ?: emptyArray())
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        val crumbs = StringBuilder("<a href=\"/\">Memoria principale</a>")
        var acc = ""
        vp.split('/').filter { it.isNotEmpty() }.forEach { seg ->
            acc += "/$seg"
            crumbs.append(" <span>›</span> <a href=\"/?path=${enc(acc)}\">${esc(seg)}</a>")
        }
        val rows = StringBuilder()
        if (vp != "/") {
            val up = vp.substringBeforeLast('/').ifEmpty { "/" }
            rows.append("<a class=\"row\" href=\"/?path=${enc(up)}\"><span class=\"ic\">↩</span><span class=\"n\">..</span><span></span><span></span></a>")
        }
        for (f in files) {
            val p = vpath(f)
            val href = if (f.isDirectory) "/?path=${enc(p)}" else "/dl?path=${enc(p)}"
            val icon = if (f.isDirectory) "📁" else "📄"
            val size = if (f.isDirectory) "${f.list()?.size ?: 0} elementi" else formatSize(f.length())
            val del = if (readOnly) "" else "<button class=\"del\" onclick=\"event.preventDefault();del('${esc(p).replace("'", "\\'")}')\" title=\"Elimina\">✕</button>"
            rows.append("<a class=\"row\" href=\"$href\"><span class=\"ic\">$icon</span><span class=\"n\">${esc(f.name)}</span><span class=\"m\">$size</span><span class=\"m\">${df.format(Date(f.lastModified()))}</span>$del</a>")
        }
        val tools = if (readOnly) "<p class=\"ro\">Sola lettura</p>" else """
<div class="tools">
<label class="btn">Carica file<input id="up" type="file" multiple hidden></label>
<button class="btn t" onclick="mk()">Nuova cartella</button>
<span id="st"></span>
</div>"""
        val html = """<!doctype html><html lang="it"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MLFileManager · ${esc(vp)}</title>
<style>
body{margin:0;font-family:system-ui,Roboto,sans-serif;background:#F8F9FF;color:#191C22}
header{padding:20px 24px 8px}h1{margin:0 0 6px;font-size:24px;font-weight:600}
.cr{font-size:15px;color:#44474F}.cr a{color:#2B5BB5;text-decoration:none}
.tools{display:flex;gap:10px;align-items:center;padding:12px 24px;flex-wrap:wrap}
.btn{background:#2B5BB5;color:#fff;border:0;border-radius:20px;padding:10px 18px;font-size:14px;font-weight:600;cursor:pointer}
.btn.t{background:#D8E2FF;color:#001A43}
.list{margin:8px 16px 32px;border-radius:24px;overflow:hidden;display:flex;flex-direction:column;gap:2px}
.row{display:grid;grid-template-columns:40px 1fr 120px 160px 36px;align-items:center;gap:8px;background:#ECEEF6;padding:12px 16px;color:inherit;text-decoration:none}
.row:hover{background:#E3E6EF}.ic{font-size:22px}.n{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.m{font-size:13px;color:#44474F}
.del{border:0;background:transparent;color:#8C1D18;font-size:16px;cursor:pointer}
.ro{padding:0 24px;color:#44474F}
@media(max-width:640px){.row{grid-template-columns:36px 1fr 36px}.m{display:none}}
</style></head><body>
<header><h1>MLFileManager</h1><div class="cr">$crumbs</div></header>
$tools
<div class="list">$rows</div>
<script>
const path=${jsString(vp)};
const st=document.getElementById('st');
const up=document.getElementById('up');
if(up){up.onchange=async()=>{const fs=[...up.files];for(let i=0;i<fs.length;i++){st.textContent='Caricamento '+(i+1)+' di '+fs.length+': '+fs[i].name;const fd=new FormData();fd.append('file',fs[i],fs[i].name);const r=await fetch('/upload?path='+encodeURIComponent(path),{method:'POST',body:fd});if(!r.ok){alert('Errore: '+await r.text());break}}location.reload()}}
async function mk(){const n=prompt('Nome della nuova cartella');if(!n)return;const b=new URLSearchParams({path:path,name:n});const r=await fetch('/mkdir',{method:'POST',body:b});if(!r.ok)alert(await r.text());location.reload()}
async function del(p){if(!confirm('Eliminare definitivamente?'))return;const r=await fetch('/delete',{method:'POST',body:new URLSearchParams({path:p})});if(!r.ok)alert(await r.text());location.reload()}
</script></body></html>"""
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""
}
