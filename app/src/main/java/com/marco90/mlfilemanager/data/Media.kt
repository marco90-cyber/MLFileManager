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

    /** Elenco dei file di una categoria (dal database dei media di Android). */
    fun list(ctx: Context, cat: Cat, showHidden: Boolean): List<FileItem> {
        val out = ArrayList<FileItem>()
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
                    val f = File(path)
                    out += FileItem(f, name = name, isDir = false, size = c.getLong(1), modified = c.getLong(2) * 1000, children = 0)
                }
            }
        } catch (_: Exception) { }
        return out.filter { it.file.exists() }
    }

    fun stat(ctx: Context, cat: Cat): CatStat {
        val l = list(ctx, cat, false)
        return CatStat(l.sumOf { it.size }, l.size)
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

    fun apps(ctx: Context, includeSystem: Boolean = false): List<AppEntry> {
        val pm = ctx.packageManager
        val usage = hasUsageAccess(ctx)
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val pkgs = try { pm.getInstalledPackages(0) } catch (_: Exception) { emptyList() }
        return pkgs.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (system && !includeSystem) return@mapNotNull null
            val apk = File(ai.sourceDir ?: return@mapNotNull null)
            val splits = ai.splitSourceDirs?.size ?: 0
            var size = apk.length() + (ai.splitSourceDirs?.sumOf { File(it).length() } ?: 0L)
            var cache = 0L
            if (usage) try {
                val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pi.packageName, Process.myUserHandle())
                size = st.appBytes + st.dataBytes
                cache = st.cacheBytes
            } catch (_: Exception) { }
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
            files.groupBy { md5(it) }.filter { it.key != null && it.value.size > 1 }.forEach { (_, same) ->
                groups += same.map { FileItem(it) }.sortedBy { it.modified }
            }
        }
        return groups
    }

    private fun md5(f: File): String? = try {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(f).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val r = input.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    fun checksum(f: File, algo: String): String? = try {
        val md = MessageDigest.getInstance(algo)
        FileInputStream(f).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val r = input.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }
}
