package com.marco90.mlfilemanager.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un file o una cartella mostrata nelle liste. */
data class FileItem(
    val file: File,
    val name: String = file.name,
    val isDir: Boolean = file.isDirectory,
    val size: Long = if (file.isDirectory) 0L else file.length(),
    val modified: Long = file.lastModified(),
    /** Elementi contenuti (solo cartelle, -1 se non leggibile). */
    val children: Int = if (file.isDirectory) (file.list()?.size ?: -1) else 0,
) {
    val path: String get() = file.absolutePath
    val kind: Kind get() = if (isDir) Kind.FOLDER else kindOf(name)
}

enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, TEXT, ARCHIVE, APK, OTHER }

val ROOT: File get() = Environment.getExternalStorageDirectory()

private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng")
private val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts", "flv", "wmv")
private val AUDIO = setOf("mp3", "flac", "wav", "ogg", "opus", "m4a", "aac", "wma", "alac", "aiff", "dsf", "ape", "mka")
private val TEXT = setOf(
    "txt", "md", "log", "json", "xml", "html", "htm", "css", "js", "kt", "kts", "java", "py", "c", "cpp", "h",
    "sh", "ini", "cfg", "conf", "yml", "yaml", "csv", "srt", "properties", "gradle", "sql", "toml", "nfo", "lrc"
)
private val DOCUMENT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub")
private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "jar")

fun extOf(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

fun kindOf(name: String): Kind = when (extOf(name)) {
    in IMAGE -> Kind.IMAGE
    in VIDEO -> Kind.VIDEO
    in AUDIO -> Kind.AUDIO
    in TEXT -> Kind.TEXT
    in DOCUMENT -> Kind.DOCUMENT
    in ARCHIVE -> Kind.ARCHIVE
    "apk", "apks", "xapk" -> Kind.APK
    else -> Kind.OTHER
}

fun mimeOf(name: String): String {
    val ext = extOf(name)
    if (ext == "apk") return "application/vnd.android.package-archive"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (kindOf(name)) {
        Kind.TEXT -> "text/plain"
        Kind.IMAGE -> "image/*"
        Kind.VIDEO -> "video/*"
        Kind.AUDIO -> "audio/*"
        else -> "*/*"
    }
}

// ---------- Formattazione ----------

fun formatSize(bytes: Long, binary: Boolean = false): String {
    val unit = if (binary) 1024.0 else 1000.0
    if (bytes < unit) return "$bytes B"
    val units = if (binary) listOf("KiB", "MiB", "GiB", "TiB") else listOf("kB", "MB", "GB", "TB")
    var v = bytes / unit
    var i = 0
    while (v >= unit && i < units.lastIndex) { v /= unit; i++ }
    return if (v >= 100) String.format(Locale.ITALIAN, "%.0f %s", v, units[i])
    else String.format(Locale.ITALIAN, "%.1f %s", v, units[i])
}

fun formatDate(ms: Long, pattern: String): String =
    try { SimpleDateFormat(pattern, Locale.ITALIAN).format(Date(ms)) } catch (_: Exception) { "" }

fun itemsLabel(n: Int) = when {
    n < 0 -> "Non leggibile"
    n == 1 -> "1 elemento"
    else -> "$n elementi"
}

/** Nome mostrato al posto del percorso tecnico. */
fun displayName(f: File): String = when (f.absolutePath) {
    ROOT.absolutePath -> "Memoria principale"
    "/" -> "Sistema"
    else -> f.name.ifEmpty { f.absolutePath }
}

// ---------- Elenco e ordinamento ----------

fun listFolder(dir: File, s: AppSettings): List<FileItem> {
    val files = dir.listFiles() ?: return emptyList()
    val items = files.asSequence()
        .filter { s.showHidden || !it.name.startsWith(".") }
        .map { FileItem(it) }
        .toList()
    return sortItems(items, s.sortBy, s.sortDesc, s.foldersFirst)
}

fun sortItems(items: List<FileItem>, by: String, desc: Boolean, foldersFirst: Boolean): List<FileItem> {
    var cmp: Comparator<FileItem> = when (by) {
        "date" -> compareBy { it.modified }
        "size" -> compareBy { if (it.isDir) it.children.toLong() else it.size }
        "type" -> compareBy<FileItem> { extOf(it.name) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
    if (desc) cmp = cmp.reversed()
    if (foldersFirst) cmp = compareBy<FileItem> { !it.isDir }.then(cmp)
    return items.sortedWith(cmp)
}

/** Nome libero nella cartella: "foto.jpg" -> "foto (1).jpg". */
fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    if (!f.exists()) return f
    val base = if (name.contains('.') && !name.startsWith(".")) name.substringBeforeLast('.') else name
    val ext = if (name.contains('.') && !name.startsWith(".")) "." + name.substringAfterLast('.') else ""
    var i = 1
    while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
    return f
}

fun invalidName(name: String): String? = when {
    name.isBlank() -> "Il nome non può essere vuoto"
    name.any { it in "\\/:*?\"<>|" } -> "Il nome non può contenere \\ / : * ? \" < > |"
    else -> null
}

// ---------- Aprire e condividere ----------

fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)

fun openExternal(ctx: Context, f: File, chooser: Boolean = true) {
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(ctx, f), mimeOf(f.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    try {
        ctx.startActivity(if (chooser) Intent.createChooser(i, "Apri con").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "Nessuna app può aprire questo file", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Impossibile aprire: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun installApk(ctx: Context, f: File) {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(ctx, "Consenti a MLFileManager di installare app", Toast.LENGTH_LONG).show()
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
    openExternal(ctx, f, chooser = false)
}

fun shareFiles(ctx: Context, files: List<File>) {
    val list = files.filter { it.isFile }
    if (list.isEmpty()) {
        Toast.makeText(ctx, "Si possono condividere solo i file", Toast.LENGTH_SHORT).show()
        return
    }
    val uris = ArrayList(list.map { uriFor(ctx, it) })
    val i = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(mimeOf(list[0].name)).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Condividi tramite").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
