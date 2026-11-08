package com.marco90.mlfilemanager.viewer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco90.mlfilemanager.data.FileOps
import com.marco90.mlfilemanager.data.openExternal
import com.marco90.mlfilemanager.ui.Loading
import com.marco90.mlfilemanager.ui.Nav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_SIZE = 4L * 1024 * 1024

@Composable
fun TextEditorScreen(nav: Nav, path: String) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val file = File(path)
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var fontSize by remember { mutableFloatStateOf(14f) }
    var askSave by remember { mutableStateOf(false) }
    var tooBig by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        if (file.length() > MAX_SIZE) { tooBig = true; return@LaunchedEffect }
        val t = withContext(Dispatchers.IO) { try { file.readText() } catch (_: Exception) { "" } }
        original = t
        if (text == null) text = t
    }
    val modified = text != null && original != null && text != original

    fun save(then: () -> Unit = {}) {
        val t = text ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { try { file.writeText(t); true } catch (_: Exception) { false } }
            if (ok) { original = t; FileOps.notifyChanged(); Toast.makeText(ctx, "Salvato", Toast.LENGTH_SHORT).show(); then() }
            else Toast.makeText(ctx, "Impossibile salvare (file di sola lettura?)", Toast.LENGTH_LONG).show()
        }
    }

    BackHandler(enabled = modified) { askSave = true }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (modified) askSave = true else nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (modified) "Modificato" else "UTF-8", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Button(onClick = { save() }, enabled = modified, modifier = Modifier.padding(end = 8.dp)) { Text("Salva") }
        }
        when {
            tooBig -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Il file è troppo grande per l'editor (più di 4 MB)", color = cs.onSurfaceVariant)
                TextButton(onClick = { openExternal(ctx, file) }) { Text("Apri con un'altra app") }
            }
            text == null -> Loading()
            else -> {
                val hScroll = rememberScrollState()
                Box(
                    Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer)
                        .verticalScroll(rememberScrollState())
                        .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))
                        .padding(16.dp)
                ) {
                    BasicTextField(
                        value = text ?: "",
                        onValueChange = { text = it },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize * 1.6f).sp, color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        modifier = if (wrap) Modifier.fillMaxWidth() else Modifier
                    )
                }
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(selected = wrap, onClick = { wrap = !wrap }, label = { Text("A capo automatico") })
                    IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(9f) }) { Icon(Icons.Outlined.Remove, "Testo più piccolo") }
                    Text("${fontSize.toInt()}", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(28f) }) { Icon(Icons.Outlined.Add, "Testo più grande") }
                }
            }
        }
    }

    if (askSave) AlertDialog(
        onDismissRequest = { askSave = false },
        title = { Text("Vuoi salvare le modifiche?") },
        confirmButton = { TextButton(onClick = { askSave = false; save { nav.pop() } }) { Text("Salva") } },
        dismissButton = {
            Row {
                TextButton(onClick = { askSave = false; nav.pop() }) { Text("Non salvare") }
                TextButton(onClick = { askSave = false }) { Text("Annulla") }
            }
        }
    )
}
