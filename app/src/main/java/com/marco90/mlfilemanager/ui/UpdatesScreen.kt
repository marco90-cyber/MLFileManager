package com.marco90.mlfilemanager.ui

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mltheme.MLGroup
import com.marco.mltheme.MLNavRow
import com.marco.mltheme.MLSettingsScaffold
import com.marco.mltheme.MLSwitchRow
import com.marco90.mlfilemanager.AppUpdate
import com.marco90.mlfilemanager.data.AppLog
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.data.uriFor

/** Ricompone quando cambia lo stato del motore degli aggiornamenti. */
@Composable
private fun rememberUpdateTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l = AppUpdate.Listener { tick++ }
        AppUpdate.addListener(l)
        onDispose { AppUpdate.removeListener(l) }
    }
    return tick
}

private fun statusText(s: AppUpdate.State): String = when (s) {
    AppUpdate.State.Idle -> "Non ancora controllato"
    AppUpdate.State.Checking -> "Controllo in corso…"
    is AppUpdate.State.UpToDate -> "Hai l'ultima versione"
    is AppUpdate.State.Available -> "Nuova versione: ${s.release.name}"
    is AppUpdate.State.Downloading -> "Download: ${AppUpdate.progressText(s.done, s.total, s.speed)}"
    is AppUpdate.State.Paused -> "Download in pausa"
    is AppUpdate.State.Ready -> "${s.release.name} pronta da installare"
    is AppUpdate.State.Failed -> s.message
}

/** Riga di sintesi per la voce "Aggiornamenti" nelle impostazioni. */
@Composable
fun updateSummary(): String {
    rememberUpdateTick()
    return when (val s = AppUpdate.state) {
        AppUpdate.State.Idle -> "Dalle Release di GitHub"
        else -> statusText(s)
    }
}

@Composable
fun UpdatesScreen(nav: Nav) {
    val ctx = LocalContext.current
    val s = AppSettings.get(ctx)
    rememberUpdateTick()
    val state = AppUpdate.state
    val latest = AppUpdate.latest
    val installed = remember { AppUpdate.installedName(ctx) }
    val busy = state is AppUpdate.State.Checking || state is AppUpdate.State.Downloading

    MLSettingsScaffold(title = "Aggiornamenti", onBack = { nav.pop() }) {
        MLGroup(null) {
            MLNavRow("Cerca aggiornamenti", statusText(state), icon = Icons.Outlined.Refresh, trailing = null) {
                if (!busy) AppUpdate.check(ctx)
            }
            if (latest != null && state !is AppUpdate.State.UpToDate) {
                MLNavRow(
                    if (state is AppUpdate.State.Ready) "Installa ${latest.name}" else "Scarica e installa",
                    "Download con pausa e ripresa · ${AppUpdate.mb(latest.size)}",
                    icon = Icons.Outlined.CloudDownload
                ) { AppUpdate.open(ctx) }
            }
        }

        MLGroup("Versioni") {
            MLNavRow("Versione installata", installed, icon = Icons.Outlined.Info, trailing = null) {}
            if (latest != null) MLNavRow("Ultima su GitHub", latest.name, icon = Icons.Outlined.CloudDownload, trailing = null) {}
            MLNavRow("Pagina delle release", "github.com/marco90-cyber/MLFileManager/releases", icon = Icons.Outlined.OpenInBrowser) {
                AppUpdate.openPage(ctx, latest?.pageUrl ?: AppUpdate.RELEASES_PAGE)
            }
        }

        MLGroup("Opzioni") {
            MLSwitchRow("Controlla all'avvio", "Cerca una nuova versione ogni volta che apri l'app", s.autoUpdateCheck, icon = Icons.Outlined.Schedule) {
                s.autoUpdateCheck = it
            }
        }

        if (latest != null && latest.notes.isNotBlank()) {
            MLGroup("Novità di ${latest.name}") {
                Text(
                    latest.notes.take(3000),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)
                )
            }
        }

        MLGroup("Debug") {
            MLNavRow("Log", "${AppLog.snapshot().size} righe", icon = Icons.Outlined.Terminal) { nav.push(Screen.Log) }
        }
    }
}

private fun shareLog(ctx: android.content.Context) {
    try {
        val f = AppLog.exportFile(ctx)
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
            .putExtra(Intent.EXTRA_SUBJECT, "Log di MLFileManager")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Condividi il log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        AppLog.e("Log", "Condivisione del log non riuscita", e)
    }
}

@Composable
fun LogScreen(nav: Nav) {
    val ctx = LocalContext.current
    val s = AppSettings.get(ctx)
    val cs = MaterialTheme.colorScheme
    val rev = AppLog.revision
    val lines = remember(rev) { AppLog.snapshot() }
    val listState = rememberLazyListState()
    LaunchedEffect(rev) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
    val dark = cs.surface.red + cs.surface.green + cs.surface.blue < 1.5f

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            Text("Log (${lines.size})", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                s.verboseLog = !s.verboseLog
                AppLog.verbose = s.verboseLog
                AppLog.i("Log", "Log dettagliato ${if (s.verboseLog) "attivato" else "disattivato"}")
            }) { Icon(Icons.Outlined.Tune, if (s.verboseLog) "Log dettagliato: attivo" else "Log dettagliato: spento", tint = if (s.verboseLog) cs.primary else cs.onSurfaceVariant) }
            IconButton(onClick = { shareLog(ctx) }) { Icon(Icons.Outlined.Share, "Esporta il log") }
            IconButton(onClick = { AppLog.clear() }) { Icon(Icons.Outlined.DeleteSweep, "Cancella il log") }
        }
        Text(
            if (s.verboseLog) "Log dettagliato attivo" else "Tocca il cursore in alto per il log dettagliato",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
        )
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Il log è vuoto", color = cs.onSurfaceVariant) }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 0.dp)
            ) {
                itemsIndexed(lines) { i, line ->
                    val color = when {
                        line.contains(" E/") -> if (dark) Color(0xFFFF8A80) else Color(0xFFB3261E)
                        line.contains(" W/") -> if (dark) Color(0xFFFFD180) else Color(0xFF8A5300)
                        line.contains(" D/") -> cs.onSurfaceVariant
                        else -> cs.onSurface
                    }
                    Text(
                        line,
                        color = color,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(groupShape(i, lines.size))
                            .background(cs.surfaceContainer)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
