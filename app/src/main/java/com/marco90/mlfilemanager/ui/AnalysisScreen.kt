package com.marco90.mlfilemanager.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marco90.mlfilemanager.data.AppEntry
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.data.Cat
import com.marco90.mlfilemanager.data.FileItem
import com.marco90.mlfilemanager.data.FileOps
import com.marco90.mlfilemanager.data.Media
import com.marco90.mlfilemanager.data.StorageInfo
import com.marco90.mlfilemanager.data.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private data class Analysis(
    val storage: StorageInfo,
    val cats: List<Triple<String, Long, Color>>,
    val apps: List<AppEntry>?,
    val large: List<FileItem>,
    val dups: List<List<FileItem>>,
)

@Composable
fun AnalysisScreen(nav: Nav) {
    val ctx = LocalContext.current
    val s = AppSettings.get(ctx)
    val cs = MaterialTheme.colorScheme
    val changed by FileOps.changed.collectAsStateWithLifecycle()
    var reload by remember { mutableIntStateOf(0) }

    val data by produceState<Analysis?>(null, reload, changed) {
        value = withContext(Dispatchers.IO) {
            coroutineScope {
                val st = Media.storage(ctx)
                val img = async { Media.stat(ctx, Cat.IMAGES).size }
                val vid = async { Media.stat(ctx, Cat.VIDEO).size }
                val aud = async { Media.stat(ctx, Cat.AUDIO).size }
                val doc = async { Media.stat(ctx, Cat.DOCUMENTS).size }
                val arc = async { Media.stat(ctx, Cat.ARCHIVES).size }
                val apps = async { if (Media.hasUsageAccess(ctx)) Media.apps(ctx).sortedByDescending { it.size } else null }
                val large = async { Media.largeFiles() }
                val dups = async { Media.duplicates() }
                val known = img.await() + vid.await() + aud.await() + doc.await() + arc.await()
                Analysis(
                    st,
                    listOf(
                        Triple("Immagini", img.await(), Color(0xFF6B3FD1)),
                        Triple("Video", vid.await(), Color(0xFFC2362F)),
                        Triple("Audio", aud.await(), Color(0xFF138A6E)),
                        Triple("Altri", (st.used - known).coerceAtLeast(0), cs.primary),
                        Triple("Archivi", arc.await(), Color(0xFFB26A00)),
                        Triple("Documenti", doc.await(), Color(0xFF1D3F8A)),
                    ),
                    apps.await(), large.await(), dups.await()
                )
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            Text("Analisi archiviazione", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Ricarica") }
        }
        val d = data
        if (d == null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Analisi dell'archiviazione…", modifier = Modifier.padding(top = 16.dp), color = cs.onSurfaceVariant)
            }
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Memoria
            MLCard {
                Header("Memoria principale", "${formatSize(d.storage.free, s.binaryUnits)} liberi")
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Donut(d.storage, d.cats)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.cats.forEach { (n, v, c) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Dot(c)
                                Text(n, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(formatSize(v, s.binaryUnits), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // App
            MLCard {
                val apps = d.apps
                if (apps == null) {
                    Header("Applicazioni", "")
                    Text(
                        "Consenti l'accesso ai dati di utilizzo per vedere quanto spazio usano le app e la loro cache.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)
                    )
                    Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Consenti l'accesso") }
                } else {
                    var all by rememberSaveable { mutableStateOf(false) }
                    Header("Applicazioni", formatSize(apps.sumOf { it.size }, s.binaryUnits))
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        apps.take(if (all) 30 else 3).forEach { a ->
                            AppLine(a.pkg, a.label, formatSize(a.size, s.binaryUnits)) { nav.push(Screen.Apps) }
                        }
                    }
                    if (apps.size > 3) TextButton(onClick = { all = !all }) { Text(if (all) "Mostra meno" else "Mostra tutto") }
                }
            }

            // File grandi
            MLCard {
                var all by rememberSaveable { mutableStateOf(false) }
                Header("File grandi", formatSize(d.large.sumOf { it.size }, s.binaryUnits))
                Text("Più grandi di 10 MB", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    d.large.take(if (all) 100 else 3).forEach { f ->
                        FileLine(f, s.binaryUnits, onOpen = { openItem(ctx, nav, f, listOf(f)) }, onDelete = {
                            FileOps.delete(ctx, listOf(f.file), s.useTrash)
                        })
                    }
                }
                if (d.large.size > 3) TextButton(onClick = { all = !all }) { Text(if (all) "Mostra meno" else "Mostra tutto") }
            }

            // Cache
            d.apps?.let { apps ->
                val withCache = apps.filter { it.cache > 0 }.sortedByDescending { it.cache }
                MLCard {
                    var all by rememberSaveable { mutableStateOf(false) }
                    Header("File di cache", formatSize(withCache.sumOf { it.cache }, s.binaryUnits))
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        withCache.take(if (all) 30 else 3).forEach { a ->
                            AppLine(a.pkg, a.label, formatSize(a.cache, s.binaryUnits)) {
                                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                            }
                        }
                    }
                    Text(
                        "Tocca un'app per svuotarne la cache dalle sue info (Android non permette di farlo per tutte insieme).",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp)
                    )
                    if (withCache.size > 3) TextButton(onClick = { all = !all }) { Text(if (all) "Mostra meno" else "Mostra tutto") }
                }
            }

            // Duplicati
            MLCard {
                val extra = d.dups.sumOf { g -> g.drop(1).sumOf { it.size } }
                Header("Download duplicati", formatSize(extra, s.binaryUnits))
                if (d.dups.isEmpty()) {
                    Text("Nessun file doppio nella cartella Download", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        d.dups.forEach { g ->
                            g.drop(1).forEach { f ->
                                FileLine(f, s.binaryUnits, sub = "Copia di ${g[0].name}", onOpen = { openItem(ctx, nav, f, listOf(f)) }, onDelete = {
                                    FileOps.delete(ctx, listOf(f.file), s.useTrash)
                                })
                            }
                        }
                    }
                    Button(
                        onClick = { FileOps.delete(ctx, d.dups.flatMap { g -> g.drop(1).map { it.file } }, s.useTrash) },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Text("Elimina i doppioni", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, extra: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(extra, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Donut(st: StorageInfo, cats: List<Triple<String, Long, Color>>) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(96.dp)) {
            val stroke = 13.dp.toPx()
            val r = Size(size.width - stroke, size.height - stroke)
            val o = Offset(stroke / 2, stroke / 2)
            drawArc(cs.outlineVariant, 0f, 360f, false, o, r, style = Stroke(stroke))
            var start = -90f
            val total = st.total.coerceAtLeast(1).toFloat()
            cats.forEach { (_, v, c) ->
                val sweep = 360f * v / total
                if (sweep > 0.5f) drawArc(c, start, sweep, false, o, r, style = Stroke(stroke))
                start += sweep
            }
        }
        Text("${st.percent}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AppLine(pkg: String, label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(pkg, 36.dp)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FileLine(f: FileItem, binary: Boolean, sub: String? = null, onOpen: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FileThumb(f, thumbnails = true, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub ?: (f.file.parent?.removePrefix(com.marco90.mlfilemanager.data.ROOT.absolutePath)?.ifEmpty { "/" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatSize(f.size, binary), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = cs.primary)
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Elimina", tint = cs.onSurfaceVariant) }
    }
}

