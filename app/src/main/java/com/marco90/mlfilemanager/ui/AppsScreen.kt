package com.marco90.mlfilemanager.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.marco90.mlfilemanager.data.ROOT
import com.marco90.mlfilemanager.data.formatDate
import com.marco90.mlfilemanager.data.formatSize
import com.marco90.mlfilemanager.data.shareFiles
import com.marco90.mlfilemanager.data.uniqueFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val s = AppSettings.get(ctx)
    val cs = MaterialTheme.colorScheme
    val changed by FileOps.changed.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var system by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<AppEntry?>(null) }

    val apps by produceState<List<AppEntry>?>(null, changed, system) {
        value = withContext(Dispatchers.IO) { Media.apps(ctx, system).sortedByDescending { it.size } }
    }
    val apks by produceState<List<FileItem>?>(null, changed) {
        value = withContext(Dispatchers.IO) { Media.list(ctx, Cat.APK, s.showHidden).sortedByDescending { it.modified } }
    }
    val usage = remember(changed) { Media.hasUsageAccess(ctx) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            if (searching) {
                TextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Cerca un'app") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = cs.surfaceContainerHigh, unfocusedContainerColor = cs.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
            } else {
                Text("Applicazioni", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Cerca") }
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Installate · ${apps?.size ?: "…"}") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("File APK · ${apks?.size ?: "…"}") })
            if (tab == 0) FilterChip(selected = system, onClick = { system = !system }, label = { Text("Di sistema") })
        }
        if (tab == 0 && !usage) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.secondaryContainer)
                    .clickable { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Consenti l'accesso ai dati di utilizzo per vedere lo spazio reale (app + dati) di ogni app",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f)
                )
            }
        }
        val q = query.trim()
        if (tab == 0) {
            val l = apps
            if (l == null) Loading() else {
                val f = if (q.isEmpty()) l else l.filter { it.label.contains(q, true) || it.pkg.contains(q, true) }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding()
                ) {
                    itemsIndexed(f, key = { _, a -> a.pkg }) { i, a ->
                        Row(
                            Modifier.fillMaxWidth().clip(groupShape(i, f.size)).background(cs.surfaceContainer).clickable { sheet = a }
                                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            AppIcon(a.pkg)
                            Column(Modifier.weight(1f)) {
                                Text(a.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "v${a.version} · ${a.pkg}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(formatSize(a.size, s.binaryUnits), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = cs.primary)
                            IconButton(onClick = { sheet = a }) { Icon(Icons.Outlined.MoreVert, "Opzioni", tint = cs.onSurfaceVariant) }
                        }
                    }
                }
            }
        } else {
            val l = apks
            if (l == null) Loading() else {
                val f = if (q.isEmpty()) l else l.filter { it.name.contains(q, true) }
                if (f.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nessun file APK", color = cs.onSurfaceVariant) }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding()
                ) {
                    itemsIndexed(f, key = { _, it -> it.path }) { i, item ->
                        FileRow(
                            item, false, true, s.dateFormat, s.binaryUnits, showPath = true,
                            modifier = Modifier.clip(groupShape(i, f.size)).background(cs.surfaceContainer)
                                .clickable { openItem(ctx, nav, item, f) }
                        )
                    }
                }
            }
        }
    }

    sheet?.let { a ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppIcon(a.pkg)
                    Column {
                        Text(a.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${formatSize(a.size, s.binaryUnits)} · aggiornata il ${formatDate(a.updated, s.dateFormat)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                SheetItem(Icons.Outlined.Apps, "Apri") {
                    val i = ctx.packageManager.getLaunchIntentForPackage(a.pkg)
                    if (i != null) ctx.startActivity(i) else Toast.makeText(ctx, "Questa app non si può aprire", Toast.LENGTH_SHORT).show()
                    sheet = null
                }
                SheetItem(Icons.Outlined.Info, "Info app") {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))); sheet = null
                }
                SheetItem(Icons.Outlined.Download, "Salva l'APK in Download") {
                    val dest = uniqueFile(File(ROOT, Environment.DIRECTORY_DOWNLOADS), "${a.label.replace(Regex("[\\\\/:*?\"<>|]"), "_")}_${a.version}.apk")
                    FileOps.copyAs(ctx, a.apk, dest)
                    if (a.splits > 0) Toast.makeText(ctx, "Attenzione: questa app è divisa in più parti, viene salvata solo quella principale", Toast.LENGTH_LONG).show()
                    sheet = null
                }
                SheetItem(Icons.Outlined.Share, "Condividi APK") {
                    val tmp = File(ctx.cacheDir, "${a.label.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")
                    try { a.apk.copyTo(tmp, overwrite = true); shareFiles(ctx, listOf(tmp)) } catch (_: Exception) { }
                    sheet = null
                }
                if (!a.system) SheetItem(Icons.Outlined.Delete, "Disinstalla") {
                    ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))); sheet = null
                }
            }
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

fun groupShape(i: Int, size: Int) = RoundedCornerShape(
    topStart = if (i == 0) 24.dp else 4.dp, topEnd = if (i == 0) 24.dp else 4.dp,
    bottomStart = if (i == size - 1) 24.dp else 4.dp, bottomEnd = if (i == size - 1) 24.dp else 4.dp
)

