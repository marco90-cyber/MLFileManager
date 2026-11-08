package com.marco90.mlfilemanager.viewer

import android.app.WallpaperManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.data.FileItem
import com.marco90.mlfilemanager.data.FileOps
import com.marco90.mlfilemanager.data.formatSize
import com.marco90.mlfilemanager.data.openExternal
import com.marco90.mlfilemanager.data.shareFiles
import com.marco90.mlfilemanager.ui.DeleteDialog
import com.marco90.mlfilemanager.ui.Nav
import com.marco90.mlfilemanager.ui.PropertiesDialog
import com.marco90.mlfilemanager.ui.Thumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(nav: Nav, paths: List<String>, start: Int) {
    val ctx = LocalContext.current
    val s = AppSettings.get(ctx)
    val list = remember { mutableStateListOf<String>().apply { addAll(paths) } }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (paths.size - 1).coerceAtLeast(0))) { list.size }
    var ui by remember { mutableStateOf(true) }
    var askDelete by remember { mutableStateOf(false) }
    var props by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (list.isEmpty()) { androidx.compose.runtime.LaunchedEffect(Unit) { nav.pop() }; return }
    val current = File(list[pager.currentPage.coerceIn(0, list.lastIndex)])

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { list[it] }) { page ->
            ZoomImage(File(list[page])) { ui = !ui }
        }
        AnimatedVisibility(ui, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .45f)).statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1} di ${list.size} · ${formatSize(current.length(), s.binaryUnits)}", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { props = true }) { Icon(Icons.Outlined.Info, "Proprietà", tint = Color.White) }
            }
        }
        AnimatedVisibility(ui, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .45f)).navigationBarsPadding().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Action(Icons.Outlined.Share, "Condividi") { shareFiles(ctx, listOf(current)) }
                Action(Icons.Outlined.OpenInNew, "Apri con") { openExternal(ctx, current) }
                Action(Icons.Outlined.Wallpaper, "Sfondo") {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                val bmp = BitmapFactory.decodeFile(current.absolutePath) ?: return@withContext false
                                WallpaperManager.getInstance(ctx).setBitmap(bmp); true
                            } catch (_: Exception) { false }
                        }
                        Toast.makeText(ctx, if (ok) "Sfondo impostato" else "Impossibile impostare lo sfondo", Toast.LENGTH_SHORT).show()
                    }
                }
                Action(Icons.Outlined.Delete, "Elimina") { askDelete = true }
            }
        }
    }
    if (askDelete) {
        DeleteDialog(listOf(FileItem(current)), s.useTrash, onDismiss = { askDelete = false }) { trash ->
            FileOps.delete(ctx, listOf(current), trash)
            list.remove(current.absolutePath)
            askDelete = false
        }
    }
    if (props) PropertiesDialog(listOf(FileItem(current)), s) { props = false }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** Foto con zoom a due dita, doppio tocco e spostamento. */
@Composable
private fun ZoomImage(file: File, onTap: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f }
                )
            }
            .pointerInput(Unit) {
                // Zoom solo con due dita (o a foto già ingrandita): con un dito si scorre tra le foto
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = file,
            imageLoader = Thumbs.loader(ctx),
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            }
        )
    }
}

