package com.marco90.mlfilemanager.viewer

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.marco90.mlfilemanager.data.openExternal
import com.marco90.mlfilemanager.data.shareFiles
import com.marco90.mlfilemanager.ui.Nav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// ---------------- Video ----------------

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(nav: Nav, path: String) {
    val ctx = LocalContext.current
    val file = File(path)
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White) }
            Text(file.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { shareFiles(ctx, listOf(file)) }) { Icon(Icons.Outlined.Share, "Condividi", tint = Color.White) }
            IconButton(onClick = { player.pause(); openExternal(ctx, file) }) { Icon(Icons.Outlined.OpenInNew, "Apri con", tint = Color.White) }
        }
    }
}

// ---------------- Musica ----------------

@Composable
fun AudioPlayerScreen(nav: Nav, paths: List<String>, start: Int) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItems(paths.map { MediaItem.fromUri(Uri.fromFile(File(it))) }, start.coerceIn(0, (paths.size - 1).coerceAtLeast(0)), 0L)
            prepare()
            playWhenReady = true
        }
    }
    var index by remember { mutableIntStateOf(start) }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffle by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf<MediaMetadata?>(null) }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { index = player.currentMediaItemIndex }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { meta = mediaMetadata }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    val file = File(paths.getOrElse(index) { paths.first() })
    val art by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            try {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(file.absolutePath)
                    r.embeddedPicture?.let { b -> android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }
                }
            } catch (_: Exception) { null }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            Text("${index + 1} di ${paths.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { shareFiles(ctx, listOf(file)) }) { Icon(Icons.Outlined.Share, "Condividi") }
            IconButton(onClick = { player.pause(); openExternal(ctx, file) }) { Icon(Icons.Outlined.OpenInNew, "Apri con") }
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(36.dp)).background(cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                val a = art
                if (a != null) Image(a, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Outlined.MusicNote, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(120.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    meta?.title?.toString() ?: file.nameWithoutExtension,
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(meta?.artist?.toString(), meta?.albumTitle?.toString()).joinToString(" · ").ifEmpty { file.parentFile?.name ?: "" },
                    style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Column {
                Slider(
                    value = seeking ?: if (dur > 0) pos.toFloat() / dur else 0f,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = { seeking?.let { player.seekTo((it * dur).toLong()) }; seeking = null }
                )
                Row {
                    Text(time(pos), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(time(dur), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { shuffle = !shuffle; player.shuffleModeEnabled = shuffle }) {
                    Icon(Icons.Outlined.Shuffle, "Casuale", tint = if (shuffle) cs.primary else cs.onSurfaceVariant)
                }
                IconButton(onClick = { player.seekToPreviousMediaItem() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipPrevious, "Precedente", modifier = Modifier.size(32.dp)) }
                FilledIconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors()
                ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pausa" else "Riproduci", modifier = Modifier.size(40.dp)) }
                IconButton(onClick = { player.seekToNextMediaItem() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipNext, "Successivo", modifier = Modifier.size(32.dp)) }
                IconButton(onClick = {
                    repeat = when (repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                    player.repeatMode = repeat
                }) {
                    Icon(
                        if (repeat == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat, "Ripeti",
                        tint = if (repeat != Player.REPEAT_MODE_OFF) cs.primary else cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun time(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

