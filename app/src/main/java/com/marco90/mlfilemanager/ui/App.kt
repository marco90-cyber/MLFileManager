package com.marco90.mlfilemanager.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marco90.mlfilemanager.AppUpdateHost
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.data.Cat
import com.marco90.mlfilemanager.data.FileItem
import com.marco90.mlfilemanager.data.FileOps
import com.marco90.mlfilemanager.data.Kind
import com.marco90.mlfilemanager.data.ROOT
import com.marco90.mlfilemanager.data.formatSize
import com.marco90.mlfilemanager.data.installApk
import com.marco90.mlfilemanager.data.openExternal
import com.marco90.mlfilemanager.viewer.AudioPlayerScreen
import com.marco90.mlfilemanager.viewer.ImageViewerScreen
import com.marco90.mlfilemanager.viewer.TextEditorScreen
import com.marco90.mlfilemanager.viewer.VideoPlayerScreen
import com.marco.mltheme.MLTheme
import java.io.File

/** Le schermate dell'app (una pila: Indietro torna alla precedente). */
sealed interface Screen {
    data object Home : Screen
    data class Folder(val path: String) : Screen
    data class Category(val cat: Cat) : Screen
    data class Search(val path: String) : Screen
    data object Analysis : Screen
    data object Apps : Screen
    data object Network : Screen
    data object Settings : Screen
    data object Trash : Screen
    data class Images(val paths: List<String>, val index: Int) : Screen
    data class Video(val path: String) : Screen
    data class Audio(val paths: List<String>, val index: Int) : Screen
    data class Text(val path: String) : Screen
}

class Nav(private val stack: MutableList<Screen>) {
    val current: Screen get() = stack.last()
    val depth: Int get() = stack.size
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun openFolder(f: File) = push(Screen.Folder(f.absolutePath))
}

@Composable
fun AppRoot() {
    MLTheme {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            val ctx = LocalContext.current
            var resumed by remember { mutableIntStateOf(0) }
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner) {
                val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { resumed++; FileOps.notifyChanged() } }
                owner.lifecycle.addObserver(obs)
                onDispose { owner.lifecycle.removeObserver(obs) }
            }
            val granted = remember(resumed) { Environment.isExternalStorageManager() }
            if (!granted) {
                PermissionScreen {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                    } catch (_: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                return@Surface
            }
            val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
            val nav = remember { Nav(stack) }
            val holder = rememberSaveableStateHolder()
            BackHandler(enabled = stack.size > 1) { nav.pop() }

            val key = "${stack.size}:${nav.current.hashCode()}"
            holder.SaveableStateProvider(key) {
                when (val s = nav.current) {
                    Screen.Home -> HomeScreen(nav)
                    is Screen.Folder -> BrowserScreen(nav, Source.Folder(File(s.path)))
                    is Screen.Category -> BrowserScreen(nav, Source.Category(s.cat))
                    is Screen.Search -> BrowserScreen(nav, Source.Search(File(s.path)))
                    Screen.Analysis -> AnalysisScreen(nav)
                    Screen.Apps -> AppsScreen(nav)
                    Screen.Network -> NetworkScreen(nav)
                    Screen.Settings -> SettingsScreen(nav)
                    Screen.Trash -> TrashScreen(nav)
                    is Screen.Images -> ImageViewerScreen(nav, s.paths, s.index)
                    is Screen.Video -> VideoPlayerScreen(nav, s.path)
                    is Screen.Audio -> AudioPlayerScreen(nav, s.paths, s.index)
                    is Screen.Text -> TextEditorScreen(nav, s.path)
                }
            }
            ProgressDialog()
            Messages()
            AppUpdateHost()
        }
    }
}

/** Apre un file con il visualizzatore giusto (o con un'altra app). */
fun openItem(ctx: android.content.Context, nav: Nav, item: FileItem, siblings: List<FileItem>) {
    if (item.isDir) { nav.openFolder(item.file); return }
    when (item.kind) {
        Kind.IMAGE -> {
            val imgs = siblings.filter { it.kind == Kind.IMAGE }
            nav.push(Screen.Images(imgs.map { it.path }, imgs.indexOfFirst { it.path == item.path }.coerceAtLeast(0)))
        }
        Kind.VIDEO -> nav.push(Screen.Video(item.path))
        Kind.AUDIO -> {
            val list = siblings.filter { it.kind == Kind.AUDIO }
            nav.push(Screen.Audio(list.map { it.path }, list.indexOfFirst { it.path == item.path }.coerceAtLeast(0)))
        }
        Kind.TEXT -> nav.push(Screen.Text(item.path))
        Kind.APK -> if (item.name.endsWith(".apk", true)) installApk(ctx, item.file) else openExternal(ctx, item.file)
        else -> openExternal(ctx, item.file)
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ToneIcon(Icons.Outlined.FolderOpen, Tones.indigo, 96.dp, 32.dp, 48.dp)
        Spacer(Modifier.height(24.dp))
        Text("Benvenuto in MLFileManager", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Per mostrare e gestire tutti i tuoi file, l'app ha bisogno del permesso «Accesso a tutti i file».",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Consenti l'accesso") }
    }
}

@Composable
private fun ProgressDialog() {
    val p by FileOps.progress.collectAsStateWithLifecycle()
    val pr = p ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(pr.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pr.total > 0) {
                    LinearProgressIndicator(progress = { (pr.done.toFloat() / pr.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(pr.current, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                val parts = buildList {
                    if (pr.total > 0) add("${formatSize(pr.done)} di ${formatSize(pr.total)}")
                    if (pr.totalFiles > 0) add("${pr.files} di ${pr.totalFiles} file")
                }
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { FileOps.cancel() }) { Text("Annulla") } }
    )
}

@Composable
private fun Messages() {
    val ctx = LocalContext.current
    val msg by FileOps.message.collectAsStateWithLifecycle()
    LaunchedEffect(msg) {
        msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); FileOps.clearMessage() }
    }
}

/** Radice della memoria come File, per le schermate. */
val rootDir: File get() = ROOT

@Suppress("unused")
private fun settingsOf(ctx: android.content.Context) = AppSettings.get(ctx)
