package com.marco90.mlfilemanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.marco.mltheme.MLSwitch
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.data.FileItem
import com.marco90.mlfilemanager.data.Media
import com.marco90.mlfilemanager.data.formatDate
import com.marco90.mlfilemanager.data.formatSize
import com.marco90.mlfilemanager.data.mimeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finestra con un nome da scrivere. [onConfirm] restituisce un errore o null se è andato bene. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> String?) {
    val dot = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, dot))) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; error = null },
                singleLine = true,
                isError = error != null,
                supportingText = if (error != null) ({ Text(error ?: "") }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        confirmButton = { TextButton(onClick = { error = onConfirm(value.text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun DeleteDialog(items: List<FileItem>, useTrash: Boolean, onDismiss: () -> Unit, onConfirm: (toTrash: Boolean) -> Unit) {
    var trash by remember { mutableStateOf(useTrash) }
    val what = if (items.size == 1) "«${items[0].name}»" else "${items.size} elementi"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Elimina") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (trash) "Spostare $what nel cestino?" else "Eliminare definitivamente $what?")
                Row(
                    Modifier.fillMaxWidth().clickable { trash = !trash },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sposta nel cestino", modifier = Modifier.weight(1f))
                    MLSwitch(checked = trash, onCheckedChange = { trash = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(trash) }) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun PropertiesDialog(items: List<FileItem>, s: AppSettings, onDismiss: () -> Unit) {
    val totals by produceState<Triple<Long, Int, Int>?>(null, items) {
        value = withContext(Dispatchers.IO) {
            var size = 0L; var files = 0; var dirs = 0
            items.forEach { it.file.walkTopDown().forEach { f -> if (f.isFile) { size += f.length(); files++ } else dirs++ } }
            Triple(size, files, dirs)
        }
    }
    var md5 by remember { mutableStateOf<String?>(null) }
    var sha by remember { mutableStateOf<String?>(null) }
    var askHash by remember { mutableStateOf(false) }
    if (askHash && items.size == 1 && !items[0].isDir) {
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                md5 = Media.checksum(items[0].file, "MD5")
                sha = Media.checksum(items[0].file, "SHA-256")
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Proprietà") },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val t = totals
                    if (items.size == 1) {
                        val it = items[0]
                        Prop("Nome", it.name)
                        Prop("Percorso", it.file.parent ?: "/")
                        Prop("Tipo", if (it.isDir) "Cartella" else mimeOf(it.name))
                        Prop("Modificato", formatDate(it.modified, "d MMMM yyyy, HH:mm"))
                    } else {
                        Prop("Elementi", "${items.size}")
                    }
                    Prop(
                        "Dimensione",
                        if (t == null) "Calcolo…" else "${formatSize(t.first, s.binaryUnits)} (${"%,d".format(t.first).replace(',', '.')} byte)"
                    )
                    if (t != null && (items.size > 1 || items[0].isDir)) Prop("Contenuto", "${t.second} file, ${(t.third - items.count { it.isDir }).coerceAtLeast(0)} cartelle")
                    if (items.size == 1) {
                        val f = items[0].file
                        Prop("Permessi", buildString { append(if (f.canRead()) "Lettura" else "—"); append(" · "); append(if (f.canWrite()) "Scrittura" else "Sola lettura") })
                        if (!items[0].isDir) {
                            HorizontalDivider()
                            if (!askHash) TextButton(onClick = { askHash = true }) { Text("Calcola checksum") }
                            else {
                                Prop("MD5", md5 ?: "Calcolo…", mono = true)
                                Prop("SHA-256", sha ?: "Calcolo…", mono = true)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )
}

@Composable
private fun Prop(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
fun ArchiveDialog(item: FileItem, onDismiss: () -> Unit, onHere: () -> Unit, onFolder: () -> Unit, onOpenWith: () -> Unit) {
    val folder = item.name.substringBeforeLast('.')
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column {
                ChoiceLine("Estrai qui", "Nella stessa cartella dello ZIP", onHere)
                ChoiceLine("Estrai in «$folder»", "In una nuova cartella", onFolder)
                ChoiceLine("Apri con…", "Con un'altra app", onOpenWith)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
private fun ChoiceLine(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SortDialog(s: AppSettings, onDismiss: () -> Unit) {
    val opts = listOf("name" to "Nome", "date" to "Data", "size" to "Dimensione", "type" to "Tipo")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ordina per") },
        text = {
            Column {
                opts.forEach { (k, l) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { s.sortBy = k }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = s.sortBy == k, onClick = { s.sortBy = k })
                        Spacer(Modifier.width(8.dp))
                        Text(l)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth().clickable { s.sortDesc = !s.sortDesc }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = s.sortDesc, onCheckedChange = { s.sortDesc = it })
                    Text("Ordine inverso")
                }
                Row(Modifier.fillMaxWidth().clickable { s.foldersFirst = !s.foldersFirst }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = s.foldersFirst, onCheckedChange = { s.foldersFirst = it })
                    Text("Cartelle in alto")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fatto") } }
    )
}
