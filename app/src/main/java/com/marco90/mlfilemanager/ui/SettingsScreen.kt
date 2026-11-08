package com.marco90.mlfilemanager.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marco.mltheme.MLChoiceRow
import com.marco.mltheme.MLGroup
import com.marco.mltheme.MLNavRow
import com.marco.mltheme.MLSettingsScaffold
import com.marco.mltheme.MLSwitchRow
import com.marco.mltheme.MLThemeSettingsGroup
import com.marco90.mlfilemanager.AppUpdate
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.data.FileItem
import com.marco90.mlfilemanager.data.FileOps
import com.marco90.mlfilemanager.data.Trash
import com.marco90.mlfilemanager.data.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val s = AppSettings.get(ctx)
    val version = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" } }

    MLSettingsScaffold(title = "Impostazioni", onBack = { nav.pop() }) {
        MLThemeSettingsGroup()

        MLGroup("Visualizzazione") {
            MLSwitchRow("Mostra file nascosti", "File e cartelle che iniziano con il punto", s.showHidden, icon = Icons.Outlined.Visibility) { s.showHidden = it }
            MLSwitchRow("Cartelle in alto", null, s.foldersFirst, icon = Icons.Outlined.Folder) { s.foldersFirst = it }
            MLSwitchRow("Anteprime di foto e video", null, s.thumbnails, icon = Icons.Outlined.Image) { s.thumbnails = it }
            MLChoiceRow("Unità di misura", listOf("dec" to "GB (1000)", "bin" to "GiB (1024)"), if (s.binaryUnits) "bin" else "dec") { s.binaryUnits = it == "bin" }
            MLChoiceRow(
                "Formato data",
                listOf("d MMM yyyy" to "8 nov 2026", "dd/MM/yyyy" to "08/11/2026", "dd/MM/yyyy HH:mm" to "08/11/2026 13:05", "d MMMM yyyy" to "8 novembre 2026"),
                s.dateFormat
            ) { s.dateFormat = it }
        }

        MLGroup("Cestino") {
            MLSwitchRow("Usa il cestino", "I file eliminati si possono recuperare", s.useTrash, icon = Icons.Outlined.Delete) { s.useTrash = it }
            MLSwitchRow("Chiedi conferma prima di eliminare", null, s.confirmDelete, icon = Icons.Outlined.Check) { s.confirmDelete = it }
            MLNavRow("Apri il cestino", null, icon = Icons.Outlined.Delete) { nav.push(Screen.Trash) }
        }

        MLGroup("Avanzate") {
            MLSwitchRow("Mostra la cartella Sistema", "Radice / in sola lettura", s.showSystem, icon = Icons.Outlined.Terminal) { s.showSystem = it }
            MLNavRow(
                "Accesso a tutti i file",
                if (Environment.isExternalStorageManager()) "Consentito" else "Non consentito",
                icon = Icons.Outlined.Lock
            ) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            }
            MLNavRow("Accesso ai dati di utilizzo", "Per lo spazio delle app e la cache", icon = Icons.Outlined.Straighten) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        MLGroup("Info") {
            MLNavRow("MLFileManager", "Versione $version · fa parte delle MLapps", icon = Icons.Outlined.Info, trailing = null) {}
            MLNavRow("Controlla aggiornamenti", "Dalle Release di GitHub", icon = Icons.Outlined.SystemUpdate) { AppUpdate.open(ctx) }
            MLNavRow("Codice sorgente", "github.com/marco90-cyber/MLFileManager", icon = Icons.Outlined.Code) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/marco90-cyber/MLFileManager")))
            }
        }
    }
}

@Composable
fun TrashScreen(nav: Nav) {
    val ctx = LocalContext.current
    val s = AppSettings.get(ctx)
    val cs = MaterialTheme.colorScheme
    val changed by FileOps.changed.collectAsStateWithLifecycle()
    val entries by produceState<List<Trash.Entry>?>(null, changed) { value = withContext(Dispatchers.IO) { Trash.list() } }
    val selected = remember { mutableStateListOf<String>() }
    var confirmEmpty by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            Text(if (selected.isEmpty()) "Cestino" else "${selected.size} selezionati", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val l = entries.orEmpty()
            if (selected.isNotEmpty()) {
                IconButton(onClick = { FileOps.restore(ctx, l.filter { it.file.path in selected }.map { it.file }); selected.clear() }) { Icon(Icons.Outlined.Restore, "Ripristina") }
                IconButton(onClick = { FileOps.delete(ctx, l.filter { it.file.path in selected }.map { it.file }, false); selected.clear() }) { Icon(Icons.Outlined.DeleteForever, "Elimina definitivamente") }
            } else if (l.isNotEmpty()) {
                TextButton(onClick = { confirmEmpty = true }) { Text("Svuota") }
            }
        }
        val l = entries
        when {
            l == null -> Loading()
            l.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToneIcon(Icons.Outlined.Delete, Tones.pink, 72.dp, 24.dp, 36.dp)
                    Text("Il cestino è vuoto", color = cs.onSurfaceVariant)
                    if (!s.useTrash) Text("Il cestino è disattivato nelle impostazioni", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize().navigationBarsPadding()
            ) {
                itemsIndexed(l, key = { _, e -> e.file.path }) { i, e ->
                    val sel = e.file.path in selected
                    val item = FileItem(e.file, name = e.file.name.substringAfter('_'))
                    FileRow(
                        item, sel, s.thumbnails, s.dateFormat, s.binaryUnits,
                        modifier = Modifier.clip(groupShape(i, l.size)).background(if (sel) cs.primaryContainer else cs.surfaceContainer)
                            .clickable { if (sel) selected.remove(e.file.path) else selected.add(e.file.path) },
                        trailing = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Eliminato", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                Text(formatDate(e.deleted, s.dateFormat), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        }
                    )
                }
            }
        }
    }
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text("Svuotare il cestino?") },
        text = { Text("Tutti i file nel cestino verranno eliminati definitivamente.") },
        confirmButton = {
            TextButton(onClick = { FileOps.delete(ctx, entries.orEmpty().map { it.file }, false); confirmEmpty = false }) { Text("Svuota") }
        },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Annulla") } }
    )
}
