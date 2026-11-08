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
                "/dl" -> download(session, param(session, "path") ?: "")
                "/put" -> put(session)
                "/api/transfers" -> newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", Transfers.json()).apply {
                    addHeader("Cache-Control", "no-store")
                }
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

    private fun download(session: IHTTPSession, path: String): Response {
        val f = resolve(path)
        if (f == null || !f.isFile) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File non trovato")
        val h = Transfers.begin(f.name, "Browser", false, session.remoteIpAddress ?: "?", f.length())
        val input = Transfers.CountingInput(FileInputStream(f), h, f.length())
        return newFixedLengthResponse(Response.Status.OK, mimeOf(f.name), input, f.length()).apply {
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

    /**
     * Caricamento "grezzo": il browser invia il file così com'è (corpo della richiesta),
     * così il telefono vede avanzamento e velocità mentre arriva e non serve una copia temporanea.
     */
    private fun put(s: IHTTPSession): Response {
        if (readOnly) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Sola lettura")
        if (s.method != Method.POST && s.method != Method.PUT) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Usa POST")
        val dir = resolve(param(s, "path") ?: "/")
        if (dir == null || !dir.isDirectory) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Cartella non trovata")
        val name = (param(s, "name") ?: "file").substringAfterLast('/').substringAfterLast('\\').trim().ifEmpty { "file" }
        if (name.any { it in ":*?\"<>|" }) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Nome non valido")
        val len = s.headers["content-length"]?.toLongOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Dimensione mancante")
        val target = com.marco90.mlfilemanager.data.uniqueFile(dir, name)
        val part = File(dir, ".${target.name}.part")
        val h = Transfers.begin(target.name, "Browser", true, s.remoteIpAddress ?: "?", len)
        try {
            val input = s.inputStream
            java.io.FileOutputStream(part).use { out ->
                val buf = ByteArray(256 * 1024)
                var left = len
                while (left > 0) {
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) break
                    out.write(buf, 0, r)
                    left -= r
                    h.add(r.toLong())
                }
                if (left > 0) throw java.io.IOException("Caricamento interrotto")
            }
            if (!part.renameTo(target)) throw java.io.IOException("Impossibile salvare il file")
            h.end(true)
            Net.scan(target)
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        } catch (e: Exception) {
            part.delete()
            h.end(false)
            throw e
        }
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
</div>
<div id="q" class="card" hidden>
<div class="qh"><b>Caricamento</b><button class="btn t" onclick="stopUp()">Annulla</button></div>
<div id="qn"></div><div class="bar"><i id="qb"></i></div><small id="qi"></small>
<div class="bar"><i id="qa"></i></div><small id="qt"></small>
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
.card{margin:4px 16px 12px;padding:16px 18px;border-radius:24px;background:#D8E2FF;color:#001A43;display:flex;flex-direction:column;gap:6px}
.qh{display:flex;justify-content:space-between;align-items:center}
.bar{height:8px;border-radius:4px;background:#fff;overflow:hidden}.bar i{display:block;height:100%;width:0;background:#2B5BB5;border-radius:4px;transition:width .3s}
.card small{color:#2B3A55}.t{display:flex;flex-direction:column;gap:4px;margin-top:6px}.tn{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
@media(max-width:640px){.row{grid-template-columns:36px 1fr 36px}.m{display:none}}
</style></head><body>
<header><h1>MLFileManager</h1><div class="cr">$crumbs</div></header>
$tools
<div id="tr" class="card" hidden></div>
<div class="list">$rows</div>
<script>
const path=${jsString(vp)};
const st=document.getElementById('st');
const up=document.getElementById('up');
const q=document.getElementById('q');
function fmt(b){if(b<1000)return b+' B';const u=['kB','MB','GB','TB'];let v=b,i=-1;do{v/=1000;i++}while(v>=1000&&i<3);return (v>=100?v.toFixed(0):v.toFixed(1)).replace('.',',')+' '+u[i]}
function eta(s){if(!isFinite(s)||s<0)return '';s=Math.round(s);if(s<60)return s+' s';if(s<3600)return Math.floor(s/60)+' min '+(s%60)+' s';return Math.floor(s/3600)+' h '+Math.floor(s%3600/60)+' min'}
let cur=null;
if(up){up.onchange=async()=>{const fs=[...up.files];up.value='';if(!fs.length)return;q.hidden=false;
const all=fs.reduce((a,f)=>a+f.size,0);let sent=0,t0=performance.now();
for(let i=0;i<fs.length;i++){const f=fs[i];
const ok=await new Promise(res=>{const x=new XMLHttpRequest();cur=x;let last=0,lastT=performance.now(),sp=0;
x.open('POST','/put?path='+encodeURIComponent(path)+'&name='+encodeURIComponent(f.name));
x.upload.onprogress=e=>{const now=performance.now(),dt=(now-lastT)/1000;if(dt>=0.4){const inst=(e.loaded-last)/dt;sp=sp?sp*0.6+inst*0.4:inst;last=e.loaded;lastT=now}
const tot=sent+e.loaded,el=(now-t0)/1000,avg=tot/Math.max(el,0.1);
document.getElementById('qn').textContent='File '+(i+1)+' di '+fs.length+': '+f.name;
document.getElementById('qb').style.width=(e.total?e.loaded/e.total*100:0)+'%';
document.getElementById('qi').textContent=fmt(e.loaded)+' di '+fmt(f.size)+(sp?' · '+fmt(sp)+'/s':'')+(sp?' · '+eta((f.size-e.loaded)/sp)+' rimanenti':'');
document.getElementById('qa').style.width=(all?tot/all*100:0)+'%';
document.getElementById('qt').textContent='Totale: '+fmt(tot)+' di '+fmt(all)+' · '+eta((all-tot)/avg)+' rimanenti'};
x.onload=()=>res(x.status==200||(alert('Errore con '+f.name+': '+x.responseText),false));
x.onerror=()=>{alert('Errore di rete con '+f.name);res(false)};x.onabort=()=>res(false);
x.setRequestHeader('Content-Type','application/octet-stream');x.send(f)});
if(!ok)break;sent+=f.size}
cur=null;location.reload()}}
function stopUp(){if(cur)cur.abort()}
const tr=document.getElementById('tr');
async function poll(){try{const r=await fetch('/api/transfers',{cache:'no-store'});const l=await r.json();const act=l.filter(t=>t.active);
tr.hidden=!act.length;tr.innerHTML='<b>Trasferimenti in corso</b>'+act.map(t=>'<div class="t"><div class="tn">'+(t.up?'⬆ ':'⬇ ')+t.name.replace(/</g,'&lt;')+' <small>'+t.proto+' · '+t.client+'</small></div><div class="bar"><i style="width:'+(t.total>0?t.done/t.total*100:0)+'%"></i></div><small>'+fmt(t.done)+(t.total>0?' di '+fmt(t.total):'')+(t.speed?' · '+fmt(t.speed)+'/s':'')+(t.eta>=0?' · '+eta(t.eta)+' rimanenti':'')+'</small></div>').join('')}catch(e){}
setTimeout(poll,1000)}
poll();
async function mk(){const n=prompt('Nome della nuova cartella');if(!n)return;const b=new URLSearchParams({path:path,name:n});const r=await fetch('/mkdir',{method:'POST',body:b});if(!r.ok)alert(await r.text());location.reload()}
async function del(p){if(!confirm('Eliminare definitivamente?'))return;const r=await fetch('/delete',{method:'POST',body:new URLSearchParams({path:p})});if(!r.ok)alert(await r.text());location.reload()}
</script></body></html>"""
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""
}
