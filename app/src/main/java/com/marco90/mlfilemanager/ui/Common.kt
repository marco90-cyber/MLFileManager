package com.marco90.mlfilemanager.ui

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.marco90.mlfilemanager.data.Cat
import com.marco90.mlfilemanager.data.FileItem
import com.marco90.mlfilemanager.data.Kind
import com.marco90.mlfilemanager.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Coppia di colori (sfondo, simbolo) per chiaro e scuro. */
data class Tone(val lightBg: Color, val lightFg: Color, val darkBg: Color, val darkFg: Color)

@Composable
fun isDark() = MaterialTheme.colorScheme.surface.luminance() < 0.4f

@Composable
fun Tone.bg() = if (isDark()) darkBg else lightBg
@Composable
fun Tone.fg() = if (isDark()) darkFg else lightFg

object Tones {
    val blue = Tone(Color(0xFFD6E8F7), Color(0xFF0B4F7C), Color(0xFF0E3A5A), Color(0xFFB7DBF7))
    val purple = Tone(Color(0xFFEBDDFF), Color(0xFF4A2A8C), Color(0xFF3B2470), Color(0xFFDCC8FF))
    val teal = Tone(Color(0xFFCDEFE6), Color(0xFF0B5545), Color(0xFF0D4237), Color(0xFFA6E6D4))
    val red = Tone(Color(0xFFFFDAD6), Color(0xFF8C1D18), Color(0xFF6E1C17), Color(0xFFFFB4AB))
    val indigo = Tone(Color(0xFFD8E2FF), Color(0xFF1D3F8A), Color(0xFF223A73), Color(0xFFC4D3FF))
    val green = Tone(Color(0xFFDCF0C8), Color(0xFF2F5A10), Color(0xFF26451A), Color(0xFFC1E6A3))
    val slate = Tone(Color(0xFFE0E4EC), Color(0xFF33404F), Color(0xFF333A44), Color(0xFFD3DAE5))
    val pink = Tone(Color(0xFFF6DCE8), Color(0xFF6E2350), Color(0xFF5A2142), Color(0xFFF5C2DA))
    val orange = Tone(Color(0xFFFFE0C2), Color(0xFF7A3E00), Color(0xFF5E3410), Color(0xFFFFCB9A))
}

fun Cat.icon(): ImageVector = when (this) {
    Cat.DOWNLOAD -> Icons.Outlined.Download
    Cat.IMAGES -> Icons.Outlined.Image
    Cat.AUDIO -> Icons.Outlined.MusicNote
    Cat.VIDEO -> Icons.Outlined.Movie
    Cat.DOCUMENTS -> Icons.Outlined.Description
    Cat.APPS -> Icons.Outlined.Apps
    Cat.RECENT -> Icons.Outlined.Schedule
    Cat.SYSTEM -> Icons.Outlined.Terminal
    Cat.TRASH -> Icons.Outlined.Delete
    Cat.ARCHIVES -> Icons.Outlined.FolderZip
    Cat.APK -> Icons.Outlined.Android
}

fun Cat.tone(): Tone = when (this) {
    Cat.DOWNLOAD -> Tones.blue
    Cat.IMAGES -> Tones.purple
    Cat.AUDIO -> Tones.teal
    Cat.VIDEO -> Tones.red
    Cat.DOCUMENTS -> Tones.indigo
    Cat.APPS, Cat.APK -> Tones.green
    Cat.RECENT, Cat.SYSTEM -> Tones.slate
    Cat.TRASH -> Tones.pink
    Cat.ARCHIVES -> Tones.orange
}

fun Kind.icon(): ImageVector = when (this) {
    Kind.FOLDER -> Icons.Outlined.Folder
    Kind.IMAGE -> Icons.Outlined.Image
    Kind.VIDEO -> Icons.Outlined.Movie
    Kind.AUDIO -> Icons.Outlined.MusicNote
    Kind.DOCUMENT -> Icons.Outlined.Description
    Kind.TEXT -> Icons.AutoMirrored.Outlined.Article
    Kind.ARCHIVE -> Icons.Outlined.FolderZip
    Kind.APK -> Icons.Outlined.Android
    Kind.OTHER -> Icons.Outlined.InsertDriveFile
}

fun Kind.tone(): Tone = when (this) {
    Kind.FOLDER -> Tones.indigo
    Kind.IMAGE -> Tones.purple
    Kind.VIDEO -> Tones.red
    Kind.AUDIO -> Tones.teal
    Kind.DOCUMENT, Kind.TEXT -> Tones.blue
    Kind.ARCHIVE -> Tones.orange
    Kind.APK -> Tones.green
    Kind.OTHER -> Tones.slate
}

/** Simbolino sulle cartelle speciali (come in File Manager+). */
private fun badgeFor(name: String): ImageVector? = when (name) {
    "DCIM", "Camera" -> Icons.Outlined.PhotoCamera
    "Documents" -> Icons.Outlined.Description
    "Download" -> Icons.Outlined.Download
    "Movies" -> Icons.Outlined.Movie
    "Music", "Ringtones", "Alarms", "Notifications", "Podcasts", "Audiobooks", "Recordings" -> Icons.Outlined.MusicNote
    "Pictures", "Screenshots" -> Icons.Outlined.Image
    "Android" -> Icons.Outlined.Android
    else -> null
}

object Thumbs {
    @Volatile private var loader: ImageLoader? = null
    fun loader(ctx: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(ctx.applicationContext)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build().also { loader = it }
    }
}

@Composable
fun ToneIcon(icon: ImageVector, tone: Tone, size: Dp = 40.dp, corner: Dp = 14.dp, iconSize: Dp = 22.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(tone.bg()),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tone.fg(), modifier = Modifier.size(iconSize)) }
}

/** Icona o anteprima di un file nelle liste. */
@Composable
fun FileThumb(item: FileItem, thumbnails: Boolean, selected: Boolean = false, size: Dp = 40.dp) {
    val cs = MaterialTheme.colorScheme
    if (selected) {
        Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(cs.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = cs.onPrimary)
        }
        return
    }
    val kind = item.kind
    when {
        kind == Kind.FOLDER -> Box(Modifier.size(size)) {
            ToneIcon(Icons.Outlined.Folder, Tones.indigo, size)
            badgeFor(item.name)?.let { b ->
                Box(
                    Modifier.size(20.dp).align(Alignment.BottomEnd).offset(3.dp, 3.dp).clip(CircleShape).background(cs.surface),
                    contentAlignment = Alignment.Center
                ) { Icon(b, null, tint = cs.primary, modifier = Modifier.size(13.dp)) }
            }
        }
        thumbnails && (kind == Kind.IMAGE || kind == Kind.VIDEO) -> {
            val ctx = LocalContext.current
            Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(kind.tone().bg()), contentAlignment = Alignment.Center) {
                Icon(kind.icon(), null, tint = kind.tone().fg(), modifier = Modifier.size(22.dp))
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(item.file).size(160).build(),
                    imageLoader = Thumbs.loader(ctx),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size)
                )
            }
        }
        thumbnails && kind == Kind.APK -> {
            val ctx = LocalContext.current
            val icon by produceState<Drawable?>(null, item.path) {
                value = withContext(Dispatchers.IO) { Media.apkIcon(ctx, item.file) }
            }
            val d = icon
            if (d != null) DrawableImage(d, size) else ToneIcon(kind.icon(), kind.tone(), size)
        }
        else -> ToneIcon(kind.icon(), kind.tone(), size)
    }
}

@Composable
fun DrawableImage(d: Drawable, size: Dp) {
    val bmp = androidx.compose.runtime.remember(d) { d.toBitmap(128, 128).asImageBitmap() }
    Image(bmp, null, modifier = Modifier.size(size))
}

@Composable
fun AppIcon(pkg: String, size: Dp = 44.dp) {
    val ctx = LocalContext.current
    val icon by produceState<Drawable?>(null, pkg) { value = withContext(Dispatchers.IO) { Media.appIcon(ctx, pkg) } }
    val d = icon
    if (d != null) DrawableImage(d, size) else ToneIcon(Icons.Outlined.Android, Tones.green, size, 22.dp)
}

/** Titolo di sezione colorato (come nelle impostazioni delle MLapps). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 32.dp, top = 18.dp, bottom = 8.dp)
    )
}

/** Scheda arrotondata da 28 dp. */
@Composable
fun MLCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainer, content: @Composable () -> Unit) {
    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(color).padding(18.dp)
    ) { content() }
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

fun isUnder(child: File, parent: File) = child.absolutePath == parent.absolutePath || child.absolutePath.startsWith(parent.absolutePath + "/")
