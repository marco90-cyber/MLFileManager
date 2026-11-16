package com.marco90.mlfilemanager.data

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Le categorie della Home. */
enum class Cat(val label: String) {
    DOWNLOAD("Download"), IMAGES("Immagini"), AUDIO("Audio"), VIDEO("Video"), DOCUMENTS("Documenti"),
    APPS("Applicazioni"), RECENT("Nuovi file"), SYSTEM("Sistema"), TRASH("Cestino"), ARCHIVES("Archivi"), APK("File APK")
}

data class CatStat(val size: Long, val count: Int)

data class StorageInfo(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0)
    val percent: Int get() = if (total > 0) ((used * 100) / total).toInt() else 0
}

data class AppEntry(
    val label: String,
    val pkg: String,
    val version: String,
    val size: Long,
    val cache: Long,
    val apk: File,
    val splits: Int,
    val updated: Long,
    val system: Boolean,
)

object Media {
    private val filesUri: Uri get() = MediaStore.Files.getContentUri("external")

    fun storage(ctx: Context): StorageInfo = try {
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        StorageInfo(ssm.getTotalBytes(StorageManager.UUID_DEFAULT), ssm.getFreeBytes(StorageManager.UUID_DEFAULT))
    } catch (_: Exception) {
        val f = Environment.getExternalStorageDirectory()
        StorageInfo(f.totalSpace, f.freeSpace)
    }

    private fun uriOf(cat: Cat): Uri = when (cat) {
        Cat.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        Cat.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        Cat.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> filesUri
    }

    /**
     * Legge una categoria dal database dei media di Android e chiama [row] per ogni file
     * (percorso, nome, dimensione, data in secondi). Usato sia per l'elenco sia per i totali.
     */
    private inline fun query(ctx: Context, cat: Cat, showHidden: Boolean, row: (String, String, Long, Long) -> Unit) {
        val cols = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        var sel: String? = null
        var args: Array<String>? = null
        when (cat) {
            Cat.RECENT -> {
                sel = "${MediaStore.MediaColumns.DATE_ADDED} > ? AND ${MediaStore.MediaColumns.SIZE} > 0"
                args = arrayOf(((System.currentTimeMillis() / 1000) - 7 * 24 * 3600).toString())
            }
            Cat.DOCUMENTS, Cat.ARCHIVES, Cat.APK -> {
                sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} AND ${MediaStore.MediaColumns.SIZE} > 0"
            }
            else -> {}
        }
        val want: (String) -> Boolean = when (cat) {
            Cat.DOCUMENTS -> { n -> kindOf(n) == Kind.DOCUMENT || kindOf(n) == Kind.TEXT }
            Cat.ARCHIVES -> { n -> kindOf(n) == Kind.ARCHIVE }
            Cat.APK -> { n -> kindOf(n) == Kind.APK }
            else -> { _ -> true }
        }
        try {
            ctx.contentResolver.query(uriOf(cat), cols, sel, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    if (!showHidden && path.contains("/.")) continue
                    val name = path.substringAfterLast('/')
                    if (!want(name)) continue
                    row(path, name, c.getLong(1), c.getLong(2))
                }
            }
        } catch (_: Exception) { }
    }

    /** Elenco dei file di una categoria (solo quelli che esistono ancora). */
    fun list(ctx: Context, cat: Cat, showHidden: Boolean): List<FileItem> {
        val out = ArrayList<FileItem>()
        query(ctx, cat, showHidden) { path, name, size, modified ->
            val f = File(path)
            if (f.exists()) out += FileItem(f, name = name, isDir = false, size = size, modified = modified * 1000, children = 0)
        }
        return out
    }

    /**
     * Totale di una categoria per la Home e l'Analisi: somma direttamente dal database
     * senza creare l'elenco né controllare ogni file sul disco (molto più veloce).
     */
    fun stat(ctx: Context, cat: Cat): CatStat {
        var size = 0L
        var n = 0
        query(ctx, cat, false) { _, _, s, _ -> size += s; n++ }
        return CatStat(size, n)
    }

    fun folderStat(dir: File): CatStat {
        var s = 0L
        var n = 0
        dir.walkTopDown().onEnter { !it.name.startsWith(".") || it == dir }.forEach {
            if (it.isFile) { s += it.length(); n++ }
        }
        return CatStat(s, n)
    }

    // ---------- App ----------

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Numero e spazio delle app installate (per la Home: non carica nomi e icone). */
    fun appsStat(ctx: Context): CatStat {
        val usage = hasUsageAccess(ctx)
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        var size = 0L
        var n = 0
        val pkgs = try { ctx.packageManager.getInstalledPackages(0) } catch (_: Exception) { emptyList() }
        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            if (isSystemApp(ai)) continue
            size += appSize(ai, pi.packageName, usage, ssm).first
            n++
        }
        return CatStat(size, n)
    }

    /** App di sistema non aggiornata dall'utente. */
    private fun isSystemApp(ai: ApplicationInfo) =
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

    /** (spazio, cache) di un'app: reale con l'accesso ai dati di utilizzo, altrimenti solo gli APK. */
    private fun appSize(ai: ApplicationInfo, pkg: String, usage: Boolean, ssm: StorageStatsManager): Pair<Long, Long> {
        if (usage) try {
            val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
            return (st.appBytes + st.dataBytes) to st.cacheBytes
        } catch (_: Exception) { }
        val apk = ai.sourceDir?.let { File(it).length() } ?: 0L
        return (apk + (ai.splitSourceDirs?.sumOf { File(it).length() } ?: 0L)) to 0L
    }

    fun apps(ctx: Context, includeSystem: Boolean = false): List<AppEntry> {
        val pm = ctx.packageManager
        val usage = hasUsageAccess(ctx)
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val pkgs = try { pm.getInstalledPackages(0) } catch (_: Exception) { emptyList() }
        return pkgs.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val system = isSystemApp(ai)
            if (system && !includeSystem) return@mapNotNull null
            val apk = File(ai.sourceDir ?: return@mapNotNull null)
            val splits = ai.splitSourceDirs?.size ?: 0
            val (size, cache) = appSize(ai, pi.packageName, usage, ssm)
            AppEntry(
                label = ai.loadLabel(pm).toString(),
                pkg = pi.packageName,
                version = pi.versionName ?: "",
                size = size,
                cache = cache,
                apk = apk,
                splits = splits,
                updated = pi.lastUpdateTime,
                system = system,
            )
        }
    }

    fun appIcon(ctx: Context, pkg: String): android.graphics.drawable.Drawable? =
        try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: PackageManager.NameNotFoundException) { null }

    fun apkIcon(ctx: Context, f: File): android.graphics.drawable.Drawable? = try {
        val pm = ctx.packageManager
        val pi = pm.getPackageArchiveInfo(f.absolutePath, 0)
        pi?.applicationInfo?.let { ai ->
            ai.sourceDir = f.absolutePath
            ai.publicSourceDir = f.absolutePath
            ai.loadIcon(pm)
        }
    } catch (_: Exception) { null }

    // ---------- Analisi ----------

    fun largeFiles(min: Long = 10_000_000L, limit: Int = 200): List<FileItem> {
        val out = ArrayList<FileItem>()
        val root = ROOT
        root.walkTopDown()
            .onEnter { d -> d == root || !(d.absolutePath == "${root.absolutePath}/Android/data" || d.absolutePath == "${root.absolutePath}/Android/obb") }
            .forEach { f -> if (f.isFile && f.length() >= min) out += FileItem(f) }
        return out.sortedByDescending { it.size }.take(limit)
    }

    /** Gruppi di file uguali nella cartella Download (stessa dimensione e stesso contenuto). */
    fun duplicates(dir: File = File(ROOT, Environment.DIRECTORY_DOWNLOADS)): List<List<FileItem>> {
        val bySize = dir.walkTopDown().filter { it.isFile && it.length() > 0 }.groupBy { it.length() }.filter { it.value.size > 1 }
        val groups = ArrayList<List<FileItem>>()
        for ((_, files) in bySize) {
            files.groupBy { checksum(it, "MD5") }.filter { it.key != null && it.value.size > 1 }.forEach { (_, same) ->
                groups += same.map { FileItem(it) }.sortedBy { it.modified }
            }
        }
        return groups
    }

    fun checksum(f: File, algo: String): String? = try {
        val md = MessageDigest.getInstance(algo)
        FileInputStream(f).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val r = input.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        md.digest().toHex()
    } catch (_: Exception) { null }

    private fun ByteArray.toHex(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) { val v = b.toInt() and 0xFF; sb.append(hex[v ushr 4]).append(hex[v and 0x0F]) }
        return sb.toString()
    }
}
