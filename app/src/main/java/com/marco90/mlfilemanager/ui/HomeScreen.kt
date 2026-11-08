package com.marco90.mlfilemanager.ui

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marco.mltheme.MLSwitch
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.data.Cat
import com.marco90.mlfilemanager.data.CatStat
import com.marco90.mlfilemanager.data.FileOps
import com.marco90.mlfilemanager.data.Media
import com.marco90.mlfilemanager.data.ROOT
import com.marco90.mlfilemanager.data.StorageInfo
import com.marco90.mlfilemanager.data.Trash
import com.marco90.mlfilemanager.data.formatSize
import com.marco90.mlfilemanager.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val s = AppSettings.get(ctx)
    val cs = MaterialTheme.colorScheme
    val changed by FileOps.changed.collectAsStateWithLifecycle()
    val net by Net.status.collectAsStateWithLifecycle()

    val storage by produceState<StorageInfo?>(null, changed) { value = withContext(Dispatchers.IO) { Media.storage(ctx) } }
    val stats = remember { mutableStateMapOf<Cat, CatStat>() }
    // Dimensioni delle categorie calcolate in parallelo (si aggiornano dopo ogni operazione)
    produceState(0, changed) {
        withContext(Dispatchers.IO) {
            coroutineScope {
                listOf(Cat.IMAGES, Cat.AUDIO, Cat.VIDEO, Cat.DOCUMENTS, Cat.RECENT, Cat.APPS, Cat.DOWNLOAD, Cat.TRASH).map { c ->
                    async {
                        val st = when (c) {
                            Cat.DOWNLOAD -> Media.folderStat(File(ROOT, Environment.DIRECTORY_DOWNLOADS))
                            Cat.TRASH -> Trash.list().let { l -> CatStat(l.sumOf { it.file.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }, l.size) }
                            Cat.APPS -> Media.apps(ctx).let { l -> CatStat(l.sumOf { it.size }, l.size) }
                            else -> Media.stat(ctx, c)
                        }
                        withContext(Dispatchers.Main) { stats[c] = st }
                    }
                }.forEach { it.await() }
            }
        }
        value = 1
    }

    var menu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)
    ) {
        // Barra in alto
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MLFileManager", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = { nav.push(Screen.Search(ROOT.absolutePath)) }) { Icon(Icons.Outlined.Search, "Cerca") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Altre opzioni") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Analisi archiviazione") }, leadingIcon = { Icon(Icons.Outlined.PieChart, null) }, onClick = { menu = false; nav.push(Screen.Analysis) })
                    DropdownMenuItem(text = { Text("Cestino") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; nav.push(Screen.Trash) })
                    DropdownMenuItem(text = { Text("Impostazioni") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { menu = false; nav.push(Screen.Settings) })
                }
            }
        }

        // Scheda della memoria
        val st = storage
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.primaryContainer)
                .clickable { nav.openFolder(ROOT) }.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Storage, null, tint = cs.onPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text("Memoria principale", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer)
                    Text(
                        if (st != null) "${formatSize(st.used, s.binaryUnits)} usati di ${formatSize(st.total, s.binaryUnits)}" else "Calcolo…",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer.copy(alpha = .8f)
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onPrimaryContainer)
            }
            UsageBar(st, stats)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (st != null) "Usato al ${st.percent}% · ${formatSize(st.free, s.binaryUnits)} liberi" else "",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { nav.push(Screen.Analysis) }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text("Analizza", modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }

        SectionTitle("Categorie")
        val tiles = buildList {
            add(Cat.DOWNLOAD); add(Cat.IMAGES); add(Cat.AUDIO); add(Cat.VIDEO); add(Cat.DOCUMENTS); add(Cat.APPS); add(Cat.RECENT)
            if (s.showSystem) add(Cat.SYSTEM)
            add(Cat.TRASH)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(3).forEach { rowCats ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowCats.forEach { c ->
                        CategoryTile(c, stats[c], s.binaryUnits, Modifier.weight(1f)) {
                            when (c) {
                                Cat.DOWNLOAD -> nav.openFolder(File(ROOT, Environment.DIRECTORY_DOWNLOADS))
                                Cat.SYSTEM -> nav.openFolder(File("/"))
                                Cat.APPS -> nav.push(Screen.Apps)
                                Cat.TRASH -> nav.push(Screen.Trash)
                                else -> nav.push(Screen.Category(c))
                            }
                        }
                    }
                    repeat(3 - rowCats.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        SectionTitle("Strumenti")
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer)
                .clickable { nav.push(Screen.Network) }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ToneIcon(Icons.Outlined.Devices, Tones.orange, 44.dp, 16.dp, 24.dp)
            Column(Modifier.weight(1f)) {
                Text("Accesso da rete", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    if (net.running) listOfNotNull(net.ftpUrl, net.httpUrl).firstOrNull() ?: "Attivo" else "FTP e browser · spento",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Box(Modifier.width(1.dp).height(32.dp).background(cs.outlineVariant))
            MLSwitch(checked = net.running, onCheckedChange = { on -> if (on) Net.start(ctx) else Net.stop(ctx) })
        }
    }
}

@Composable
private fun UsageBar(st: StorageInfo?, stats: Map<Cat, CatStat>) {
    val cs = MaterialTheme.colorScheme
    val total = (st?.total ?: 0L).coerceAtLeast(1L)
    val parts = listOf(
        (stats[Cat.IMAGES]?.size ?: 0L) to Color(0xFF6B3FD1),
        (stats[Cat.VIDEO]?.size ?: 0L) to Color(0xFFC2362F),
        (stats[Cat.AUDIO]?.size ?: 0L) to Color(0xFF138A6E),
    )
    val known = parts.sumOf { it.first }
    val other = ((st?.used ?: 0L) - known).coerceAtLeast(0L)
    val all = parts + (other to cs.primary)
    Row(
        Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        all.forEach { (size, color) ->
            val w = size.toFloat() / total
            if (w > 0.003f) Box(Modifier.weight(w).fillMaxHeight().background(color))
        }
        val free = ((st?.free ?: total).toFloat() / total).coerceAtLeast(0.001f)
        Spacer(Modifier.weight(free))
    }
}

@Composable
private fun CategoryTile(c: Cat, st: CatStat?, binary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.heightIn(min = 112.dp).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ToneIcon(c.icon(), c.tone(), 44.dp, 16.dp, 24.dp)
        Column {
            Text(c.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                c == Cat.SYSTEM -> "Radice /"
                c == Cat.TRASH && st != null && st.count == 0 -> "Vuoto"
                st == null -> "…"
                else -> "${formatSize(st.size, binary)} · ${st.count}"
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
