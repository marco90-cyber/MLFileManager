package com.marco90.mlfilemanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.data.Cat
import com.marco90.mlfilemanager.data.Clipboard
import com.marco90.mlfilemanager.data.FileItem
import com.marco90.mlfilemanager.data.FileOps
import com.marco90.mlfilemanager.data.Kind
import com.marco90.mlfilemanager.data.Media
import com.marco90.mlfilemanager.data.ROOT
import com.marco90.mlfilemanager.data.displayName
import com.marco90.mlfilemanager.data.extOf
import com.marco90.mlfilemanager.data.formatDate
import com.marco90.mlfilemanager.data.formatSize
import com.marco90.mlfilemanager.data.itemsLabel
import com.marco90.mlfilemanager.data.listFolder
import com.marco90.mlfilemanager.data.openExternal
import com.marco90.mlfilemanager.data.sortItems
import com.marco90.mlfilemanager.data.uniqueFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Source {
    data class Folder(val dir: File) : Source
    data class Category(val cat: Cat) : Source
    data class Search(val dir: File) : Source
}

private sealed interface Dlg {
    data object NewFolder : Dlg
    data object NewFile : Dlg
    data class Rename(val item: FileItem) : Dlg
    data class Delete(val items: List<FileItem>) : Dlg
    data class Props(val items: List<FileItem>) : Dlg
    data class Zip(val items: List<FileItem>) : Dlg
    data class Archive(val item: FileItem) : Dlg
    data object Sort : Dlg
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(nav: Nav, source: Source) {
    val ctx = LocalContext.current
    val s = AppSettings.get(ctx)
    val cs = MaterialTheme.colorScheme
    val changed by FileOps.changed.collectAsStateWithLifecycle()
    val clip = FileOps.clipboard

    var searching by rememberSaveable { mutableStateOf(source is Source.Search) }
    var query by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    // Ultimo elemento selezionato: tenendo premuto un altro si seleziona tutto quello che sta in mezzo
    var anchor by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var menu by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val dir: File? = when (source) {
        is Source.Folder -> source.dir
        is Source.Search -> source.dir
        else -> null
    }

    val items by produceState<List<FileItem>?>(null, source, changed, s.sortBy, s.sortDesc, s.showHidden, s.foldersFirst, query, searching) {
        value = withContext(Dispatchers.IO) {
            val q = query.trim()
            when {
                searching && q.isNotEmpty() && dir != null -> {
                    val out = ArrayList<FileItem>()
                    dir.walkTopDown()
                        .onEnter { d -> d == dir || s.showHidden || !d.name.startsWith(".") }
                        .forEach { f -> if (f != dir && out.size < 1000 && f.name.contains(q, ignoreCase = true) && (s.showHidden || !f.name.startsWith("."))) out += FileItem(f) }
                    sortItems(out, s.sortBy, s.sortDesc, s.foldersFirst)
                }
                source is Source.Folder -> listFolder(source.dir, s)
                source is Source.Category -> {
                    val l = Media.list(ctx, source.cat, s.showHidden)
                    val f = if (q.isNotEmpty()) l.filter { it.name.contains(q, true) } else l
                    sortItems(f, if (s.sortBy == "name" && source.cat == Cat.RECENT) "date" else s.sortBy, if (source.cat == Cat.RECENT && s.sortBy == "name") true else s.sortDesc, false)
                }
                else -> emptyList()
            }
        }
    }
    val list = items.orEmpty()
    val selItems = list.filter { it.path in selected }
    val selecting = selected.isNotEmpty()

    BackHandler(enabled = selecting) { selected.clear() }
    BackHandler(enabled = !selecting && searching && source !is Source.Search) { searching = false; query = "" }

    val title = when (source) {
        is Source.Folder -> displayName(source.dir)
        is Source.Category -> source.cat.label
        is Source.Search -> "Cerca"
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---------- Barra in alto ----------
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    selecting -> {
                        IconButton(onClick = { selected.clear() }) { Icon(Icons.Outlined.Close, "Annulla selezione") }
                        Text("${selected.size} selezionati", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            if (selected.size == list.size) selected.clear() else { selected.clear(); selected.addAll(list.map { it.path }) }
                        }) { Icon(Icons.Outlined.SelectAll, "Seleziona tutto") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Altre opzioni") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MenuItem(
                                    if (selItems.any { it.isDir }) "Condividi (cartelle in ZIP)" else "Condividi",
                                    Icons.Outlined.Share
                                ) { menu = false; FileOps.share(ctx, selItems.map { it.file }); selected.clear() }
                                if (selItems.size == 1) {
                                    if (!selItems[0].isDir) MenuItem("Apri con", Icons.Outlined.OpenInNew) { menu = false; openExternal(ctx, selItems[0].file); selected.clear() }
                                    if (selItems[0].kind == Kind.ARCHIVE) MenuItem("Estrai", Icons.Outlined.Unarchive) { menu = false; dialog = Dlg.Archive(selItems[0]) }
                                }
                                MenuItem("Proprietà", Icons.Outlined.Info) { menu = false; dialog = Dlg.Props(selItems) }
                            }
                        }
                    }
                    searching -> {
                        IconButton(onClick = { if (source is Source.Search) nav.pop() else { searching = false; query = "" } }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                        }
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text(if (dir != null) "Cerca in ${displayName(dir)}" else "Cerca") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = cs.surfaceContainerHigh, unfocusedContainerColor = cs.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(26.dp),
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Cancella") } },
                            modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus)
                        )
                    }
                    else -> {
                        IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (source is Source.Category && items != null) {
                                Text("${list.size} file · ${formatSize(list.sumOf { it.size }, s.binaryUnits)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Cerca") }
                        IconButton(onClick = { dialog = Dlg.Sort }) { Icon(Icons.Outlined.Tune, "Ordina") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Altre opzioni") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (source is Source.Folder && source.dir.canWrite()) {
                                    MenuItem("Nuova cartella", Icons.Outlined.CreateNewFolder) { menu = false; dialog = Dlg.NewFolder }
                                    MenuItem("Nuovo file", Icons.Outlined.NoteAdd) { menu = false; dialog = Dlg.NewFile }
                                }
                                MenuItem("Analizza", Icons.Outlined.PieChart) { menu = false; nav.push(Screen.Analysis) }
                                MenuItem("Ricarica", Icons.Outlined.Refresh) { menu = false; FileOps.notifyChanged() }
                                MenuItem(if (s.showHidden) "Nascondi file nascosti" else "Mostra file nascosti", Icons.Outlined.Visibility) {
                                    menu = false; s.showHidden = !s.showHidden
                                }
                            }
                        }
                    }
                }
            }

            // ---------- Percorso ----------
            if (source is Source.Folder && !searching) Breadcrumb(nav, source.dir)

            // ---------- Lista ----------
            when {
                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            searching && query.isBlank() -> "Scrivi per cercare"
                            searching -> "Nessun risultato"
                            source is Source.Folder && source.dir.listFiles() == null -> "Cartella non leggibile"
                            else -> "Cartella vuota"
                        },
                        color = cs.onSurfaceVariant
                    )
                }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(list, key = { _, it -> it.path }) { i, item ->
                        val sel = item.path in selected
                        val shape = RoundedCornerShape(
                            topStart = if (i == 0) 24.dp else 4.dp, topEnd = if (i == 0) 24.dp else 4.dp,
                            bottomStart = if (i == list.lastIndex) 24.dp else 4.dp, bottomEnd = if (i == list.lastIndex) 24.dp else 4.dp
                        )
                        FileRow(
                            item, sel, s.thumbnails, s.dateFormat, s.binaryUnits,
                            showPath = searching || source is Source.Category,
                            modifier = Modifier.clip(shape).background(if (sel) cs.primaryContainer else cs.surfaceContainer)
                                .combinedClickable(
                                    onClick = {
                                        when {
                                            selecting -> if (sel) selected.remove(item.path) else { selected.add(item.path); anchor = item.path }
                                            item.kind == Kind.ARCHIVE && extOf(item.name) in setOf("zip", "jar") -> dialog = Dlg.Archive(item)
                                            else -> openItem(ctx, nav, item, list)
                                        }
                                    },
                                    onLongClick = {
                                        val from = anchor?.takeIf { it in selected }?.let { a -> list.indexOfFirst { it.path == a } } ?: -1
                                        when {
                                            // Selezione a intervallo: dal primo selezionato fino a questo
                                            selecting && !sel && from >= 0 -> {
                                                val range = if (from <= i) from..i else i..from
                                                range.forEach { k -> list[k].path.let { if (it !in selected) selected.add(it) } }
                                                anchor = item.path
                                            }
                                            sel -> selected.remove(item.path)
                                            else -> { selected.add(item.path); anchor = item.path }
                                        }
                                    }
                                )
                        )
                    }
                }
            }
        }

        // ---------- Barre in basso ----------
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
            when {
                selecting -> ActionBar(
                    onCopy = { FileOps.clipboard = Clipboard(selItems.map { it.file }, cut = false); selected.clear() },
                    onCut = { FileOps.clipboard = Clipboard(selItems.map { it.file }, cut = true); selected.clear() },
                    canRename = selItems.size == 1,
                    onRename = { if (selItems.size == 1) dialog = Dlg.Rename(selItems[0]) },
                    onZip = { dialog = Dlg.Zip(selItems) },
                    onDelete = {
                        if (s.confirmDelete) dialog = Dlg.Delete(selItems)
                        else { FileOps.delete(ctx, selItems.map { it.file }, s.useTrash); selected.clear() }
                    },
                )
                clip != null && source is Source.Folder -> PasteBar(clip,
                    onCancel = { FileOps.clipboard = null },
                    onPaste = { FileOps.paste(ctx, source.dir) })
            }
        }
        if (source is Source.Folder && !selecting && clip == null && !searching && source.dir.canWrite()) {
            Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) {
                FloatingActionButton(onClick = { fabMenu = true }, containerColor = cs.primaryContainer) { Icon(Icons.Outlined.Add, "Nuovo") }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    MenuItem("Cartella", Icons.Outlined.CreateNewFolder) { fabMenu = false; dialog = Dlg.NewFolder }
                    MenuItem("File", Icons.Outlined.NoteAdd) { fabMenu = false; dialog = Dlg.NewFile }
                }
            }
        }
    }

    // ---------- Finestre ----------
    when (val d = dialog) {
        null -> {}
        Dlg.NewFolder -> NameDialog("Nuova cartella", "", "Crea", onDismiss = { dialog = null }) { n ->
            FileOps.newFolder(dir ?: ROOT, n).also { if (it == null) dialog = null }
        }
        Dlg.NewFile -> NameDialog("Nuovo file", "nuovo.txt", "Crea", onDismiss = { dialog = null }) { n ->
            FileOps.newFile(dir ?: ROOT, n).also { if (it == null) dialog = null }
        }
        is Dlg.Rename -> NameDialog("Rinomina", d.item.name, "Rinomina", onDismiss = { dialog = null }) { n ->
            FileOps.rename(d.item.file, n).also { if (it == null) { dialog = null; selected.clear() } }
        }
        is Dlg.Delete -> DeleteDialog(d.items, s.useTrash, onDismiss = { dialog = null }) { trash ->
            FileOps.delete(ctx, d.items.map { it.file }, trash); dialog = null; selected.clear()
        }
        is Dlg.Props -> PropertiesDialog(d.items, s) { dialog = null }
        is Dlg.Zip -> {
            val base = if (d.items.size == 1) d.items[0].name.substringBeforeLast('.', d.items[0].name) else (dir?.name ?: "archivio")
            NameDialog("Comprimi in ZIP", "$base.zip", "Comprimi", onDismiss = { dialog = null }) { n ->
                val target = File(dir ?: d.items[0].file.parentFile ?: ROOT, if (n.endsWith(".zip", true)) n else "$n.zip")
                if (target.exists()) "Esiste già un file con questo nome" else {
                    FileOps.zip(ctx, d.items.map { it.file }, target); dialog = null; selected.clear(); null
                }
            }
        }
        is Dlg.Archive -> ArchiveDialog(d.item,
            onDismiss = { dialog = null },
            onHere = { FileOps.unzip(ctx, d.item.file, d.item.file.parentFile ?: ROOT); dialog = null; selected.clear() },
            onFolder = {
                val parent = d.item.file.parentFile ?: ROOT
                FileOps.unzip(ctx, d.item.file, uniqueFile(parent, d.item.name.substringBeforeLast('.'))); dialog = null; selected.clear()
            },
            onOpenWith = { openExternal(ctx, d.item.file); dialog = null })
        Dlg.Sort -> SortDialog(s) { dialog = null }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun Breadcrumb(nav: Nav, dir: File) {
    val cs = MaterialTheme.colorScheme
    val root = ROOT
    val chain = ArrayList<File>()
    var f: File? = dir
    val base = if (isUnder(dir, root)) root else File("/")
    while (f != null) {
        chain.add(0, f)
        if (f.absolutePath == base.absolutePath) break
        f = f.parentFile
    }
    val ctx = LocalContext.current
    val percent: Int? = remember(dir) { Media.storage(ctx).percent }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState(Int.MAX_VALUE)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer)
                    .combinedClickableCompat { nav.home() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Home, "Home", tint = cs.primary, modifier = Modifier.size(18.dp)) }
            chain.forEachIndexed { i, c ->
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                val last = i == chain.lastIndex
                Box(
                    Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (last) cs.primaryContainer else cs.surfaceContainer)
                        .combinedClickableCompat { if (!last) nav.openFolder(c) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        displayName(c), style = MaterialTheme.typography.labelLarge,
                        color = if (last) cs.onPrimaryContainer else cs.onSurfaceVariant, maxLines = 1
                    )
                }
            }
        }
        if (percent != null && isUnder(dir, root)) {
            Spacer(Modifier.size(8.dp))
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Outlined.Storage, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text("$percent%", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun FileRow(
    item: FileItem, selected: Boolean, thumbnails: Boolean, datePattern: String, binary: Boolean,
    showPath: Boolean = false, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FileThumb(item, thumbnails, selected)
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (item.isDir) itemsLabel(item.children) else formatSize(item.size, binary)
            Text(
                if (showPath) "$sub · ${item.file.parent?.removePrefix(ROOT.absolutePath)?.ifEmpty { "/" } ?: ""}" else sub,
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) trailing()
        else Text(formatDate(item.modified, datePattern), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ActionBar(onCopy: () -> Unit, onCut: () -> Unit, canRename: Boolean, onRename: () -> Unit, onZip: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(72.dp).shadow(8.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
            .background(cs.surfaceContainerHighest).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BarAction("Copia", Icons.Outlined.ContentCopy, onCopy)
        BarAction("Taglia", Icons.Outlined.ContentCut, onCut)
        BarAction("Rinomina", Icons.Outlined.DriveFileRenameOutline, onRename, enabled = canRename)
        BarAction("Comprimi", Icons.Outlined.FolderZip, onZip)
        BarAction("Elimina", Icons.Outlined.Delete, onDelete)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarAction(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    // Rinomina vale per un solo elemento: con più elementi selezionati resta in grigio
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).then(if (enabled) Modifier.combinedClickableCompat(onClick) else Modifier).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun PasteBar(clip: Clipboard, onCancel: () -> Unit, onPaste: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
            .background(cs.inverseSurface).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            val n = clip.files.size
            Text(
                "${if (n == 1) "1 elemento" else "$n elementi"} da ${if (clip.cut) "spostare" else "copiare"}",
                color = cs.inverseOnSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium
            )
            Text("Scegli la cartella e incolla", color = cs.inverseOnSurface.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onCancel) { Text("Annulla", color = cs.inverseOnSurface) }
        androidx.compose.material3.Button(onClick = onPaste) { Text("Incolla qui") }
    }
}

/** Clic semplice. */
fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
