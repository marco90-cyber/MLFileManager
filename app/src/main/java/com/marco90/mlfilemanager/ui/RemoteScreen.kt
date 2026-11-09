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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.data.FileItem
import com.marco90.mlfilemanager.data.FileOps
import com.marco90.mlfilemanager.data.Kind
import com.marco90.mlfilemanager.data.ROOT
import com.marco90.mlfilemanager.data.formatDate
import com.marco90.mlfilemanager.data.formatSize
import com.marco90.mlfilemanager.data.invalidName
import com.marco90.mlfilemanager.data.kindOf
import com.marco90.mlfilemanager.net.DeviceStore
import com.marco90.mlfilemanager.net.Discovery
import com.marco90.mlfilemanager.net.RemoteClip
import com.marco90.mlfilemanager.net.RemoteClipData
import com.marco90.mlfilemanager.net.RemoteDevice
import com.marco90.mlfilemanager.net.RemoteFtp
import com.marco90.mlfilemanager.net.RemoteItem
import com.marco90.mlfilemanager.net.RemoteLogin
import com.marco90.mlfilemanager.net.RemoteOps
import com.marco90.mlfilemanager.net.RemoteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// =====================================================================
//  Home: sezione "Dispositivi in rete"
// =====================================================================

@Composable
fun DevicesSection(nav: Nav) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val settings = AppSettings.get(ctx)
    val net by com.marco90.mlfilemanager.net.Net.status.collectAsStateWithLifecycle()
    // La ricerca gira solo se è accesa e se l'Accesso da rete di questo telefono è spento
    val searching = settings.discoverDevices && !net.running
    DisposableEffect(searching) {
        if (searching) Discovery.start(ctx)
        onDispose { if (searching) Discovery.stop() }
    }
    val foundNow by Discovery.devices.collectAsStateWithLifecycle()
    val found = if (searching) foundNow else emptyList()
    var savedTick by remember { mutableStateOf(0) }
    val saved = remember(savedTick, found) { DeviceStore.saved(ctx).map { it.device } }
    // Trovati ora + salvati non trovati (in grigio)
    val all = found + saved.filter { s -> found.none { it.key == s.key } }
    var login by remember { mutableStateOf<RemoteDevice?>(null) }
    var forget by remember { mutableStateOf<RemoteDevice?>(null) }

    Row(Modifier.fillMaxWidth().padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Dispositivi in rete", Modifier.weight(1f))
        Text(
            if (searching) "Ricerca attiva" else "Ricerca spenta",
            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, end = 10.dp)
        )
        Box(Modifier.padding(top = 10.dp)) {
            com.marco.mltheme.MLSwitch(checked = settings.discoverDevices, onCheckedChange = { settings.discoverDevices = it })
        }
    }
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!searching) {
            Row(
                Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ToneIcon(Icons.Outlined.WifiFind, Tones.slate, 44.dp, 16.dp, 24.dp)
                Text(
                    if (settings.discoverDevices && net.running) "Ricerca in pausa: l'Accesso da rete di questo telefono è attivo"
                    else "Accendi la ricerca per trovare gli altri dispositivi sulla stessa Wi-Fi",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
            }
        } else if (all.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ToneIcon(Icons.Outlined.WifiFind, Tones.teal, 44.dp, 16.dp, 24.dp)
                Column(Modifier.weight(1f)) {
                    Text("Ricerca in corso…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "Sugli altri dispositivi avvia l'Accesso da rete di MLFileManager (o di File Manager+)",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
        }
        all.forEach { d ->
            @OptIn(ExperimentalFoundationApi::class)
            Row(
                Modifier.fillMaxWidth().background(cs.surfaceContainer)
                    .combinedClickable(
                        onClick = {
                            val l = DeviceStore.login(ctx, d)
                            if (l != null) nav.push(Screen.Remote(d)) else login = d
                        },
                        onLongClick = { if (saved.any { it.key == d.key }) forget = d }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ToneIcon(Icons.Outlined.PhoneAndroid, if (d.online) Tones.teal else Tones.slate, 44.dp, 16.dp, 24.dp)
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (d.online) cs.onSurface else cs.onSurfaceVariant)
                    Text(
                        (if (d.fmPlus) "File Manager+" else "MLFileManager") + " · " +
                            (if (d.online) "${d.host}:${d.port}" else "Non trovato ora · ultimo ${d.host}:${d.port}"),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
            }
        }
    }

    login?.let { d ->
        LoginDialog(d, onDismiss = { login = null }) {
            login = null
            savedTick++
            nav.push(Screen.Remote(d))
        }
    }
    forget?.let { d ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text(d.name) },
            text = { Text("Dimenticare questo dispositivo e la password salvata?") },
            confirmButton = { TextButton(onClick = { DeviceStore.forget(ctx, d); forget = null; savedTick++ }) { Text("Dimentica") } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Annulla") } }
        )
    }
}

/** Chiede nome utente e password, prova a collegarsi e, se va, li ricorda. */
@Composable
fun LoginDialog(d: RemoteDevice, onDismiss: () -> Unit, onOk: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prev = remember { DeviceStore.login(ctx, d) }
    var user by remember { mutableStateOf(prev?.user ?: if (d.fmPlus) "" else "mlapps") }
    var pass by remember { mutableStateOf(prev?.password ?: "") }
    var save by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(d.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (d.fmPlus) "File Manager+ · inserisci la password mostrata nel suo Accesso da rete (nome utente: lascia vuoto se non lo chiede)"
                    else "Inserisci il nome utente e la password mostrati nell'Accesso da rete dell'altro dispositivo",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(user, { user = it; error = null }, label = { Text("Nome utente") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it; error = null }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { save = !save }) {
                    Checkbox(save, { save = it })
                    Text("Ricorda la password")
                }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Collegamento…")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                val l = RemoteLogin(user.trim(), pass)
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        try { RemoteFtp.safeClose(RemoteFtp.connect(d, l)); null } catch (e: Exception) { e.message ?: "Collegamento non riuscito" }
                    }
                    busy = false
                    if (err == null) { DeviceStore.remember(ctx, d, l, save); onOk() } else error = err
                }
            }) { Text("Collega") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Annulla") } }
    )
}

// =====================================================================
//  Navigazione nei file dell'altro dispositivo
// =====================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(nav: Nav, device: RemoteDevice) {
    val ctx = LocalContext.current
    val s = AppSettings.get(ctx)
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val login = remember { DeviceStore.login(ctx, device) }
    if (login == null) {
        LoginDialog(device, onDismiss = { nav.pop() }) { nav.replace(Screen.Remote(device)) }
        return
    }
    val session = remember { RemoteSession(device, login) }
    DisposableEffect(Unit) { onDispose { session.close() } }

    var path by remember { mutableStateOf("/") }
    var items by remember { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val selected = remember { mutableStateListOf<String>() }
    var menu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<RemoteItem?>(null) }
    var confirmDelete by remember { mutableStateOf<List<RemoteItem>?>(null) }
    val changed by RemoteOps.changed.collectAsStateWithLifecycle()
    val clip = FileOps.clipboard
    val selecting = selected.isNotEmpty()

    LaunchedEffect(path, reload, changed) {
        loading = true; error = null
        try { items = session.list(path) } catch (e: Exception) { error = e.message ?: "Errore di collegamento" }
        loading = false
        selected.retainAll(items.map { it.path }.toSet())
    }
    BackHandler(enabled = selecting || path != "/") {
        if (selecting) selected.clear() else path = RemoteFtp.parent(path)
    }
    val selItems = items.filter { it.path in selected }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: Exception) { FileOps.post("Errore: ${e.message}") }
            reload++
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---------- Barra in alto ----------
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    IconButton(onClick = { selected.clear() }) { Icon(Icons.Outlined.Close, "Annulla selezione") }
                    Text("${selected.size} selezionati", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        if (selected.size == items.size) selected.clear() else { selected.clear(); selected.addAll(items.map { it.path }) }
                    }) { Icon(Icons.Outlined.SelectAll, "Seleziona tutto") }
                } else {
                    IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (device.fmPlus) "File Manager+ · ${device.host}" else "MLFileManager · ${device.host}",
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Ricarica") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Altre opzioni") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Nuova cartella") }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                onClick = { menu = false; newFolder = true })
                            DropdownMenuItem(text = { Text("Dimentica la password") }, leadingIcon = { Icon(Icons.Outlined.Close, null) },
                                onClick = { menu = false; DeviceStore.forget(ctx, device); nav.pop() })
                        }
                    }
                }
            }
            // ---------- Percorso ----------
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(cs.surfaceContainer).clickable { path = "/" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Home, "Radice", tint = cs.primary, modifier = Modifier.size(18.dp))
                }
                var acc = ""
                path.split('/').filter { it.isNotEmpty() }.forEachIndexed { i, seg ->
                    acc += "/$seg"
                    val target = acc
                    val last = i == path.split('/').count { it.isNotEmpty() } - 1
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Text(
                        seg,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (last) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (last) cs.primaryContainer else cs.surfaceContainer)
                            .clickable { path = target }.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // ---------- Elenco ----------
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "", color = cs.error, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { reload++ }) { Text("Riprova") }
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Cartella vuota", color = cs.onSurfaceVariant)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(items, key = { _, it -> it.path }) { i, it ->
                        val sel = it.path in selected
                        Row(
                            Modifier.fillMaxWidth().clip(groupShape(i, items.size))
                                .background(if (sel) cs.primaryContainer else cs.surfaceContainer)
                                .combinedClickable(
                                    onClick = {
                                        when {
                                            selecting -> if (sel) selected.remove(it.path) else selected.add(it.path)
                                            it.isDir -> path = it.path
                                            else -> RemoteOps.open(ctx, device, login, it) { f ->
                                                val fi = FileItem(f)
                                                openItem(ctx, nav, fi, listOf(fi))
                                            }
                                        }
                                    },
                                    onLongClick = { if (sel) selected.remove(it.path) else selected.add(it.path) }
                                )
                                .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            val kind = if (it.isDir) Kind.FOLDER else kindOf(it.name)
                            if (sel) ToneIcon(Icons.Outlined.SelectAll, Tones.indigo) else ToneIcon(kind.icon(), kind.tone())
                            Column(Modifier.weight(1f)) {
                                Text(it.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (it.isDir) "Cartella" else formatSize(it.size, s.binaryUnits),
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            if (it.modified > 0) Text(formatDate(it.modified, s.dateFormat), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ---------- In basso: trasferimenti e barre ----------
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TransfersCard(s.binaryUnits, onlyActive = true)
            Box(Modifier.padding(horizontal = 12.dp)) {
                when {
                    selecting -> RemoteActionBar(
                        canRename = selItems.size == 1,
                        onCopy = {
                            RemoteClip.value = RemoteClipData(device, login, selItems)
                            FileOps.post("Apri una cartella del telefono e tocca «Incolla qui»")
                            selected.clear()
                        },
                        onDownload = {
                            val dest = File(ROOT, "Download").apply { mkdirs() }
                            RemoteOps.download(device, login, selItems, dest)
                            selected.clear()
                        },
                        onRename = { rename = selItems.firstOrNull() },
                        onDelete = { confirmDelete = selItems },
                    )
                    clip != null -> Row(
                        Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                            .background(cs.inverseSurface).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            val n = clip.files.size
                            Text("${if (n == 1) "1 elemento" else "$n elementi"} da inviare", color = cs.inverseOnSurface,
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Text("su ${device.name}", color = cs.inverseOnSurface.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { FileOps.clipboard = null }) { Text("Annulla", color = cs.inverseOnSurface) }
                        Button(onClick = {
                            RemoteOps.upload(device, login, clip.files, path, deleteAfter = clip.cut)
                            FileOps.clipboard = null
                        }) { Text("Incolla qui") }
                    }
                }
            }
        }
    }

    if (newFolder) NameDialog("Nuova cartella", "", "Crea", onDismiss = { newFolder = false }) { n ->
        invalidName(n) ?: run { newFolder = false; act { session.mkdir(RemoteFtp.join(path, n)) }; null }
    }
    rename?.let { r ->
        NameDialog("Rinomina", r.name, "Rinomina", onDismiss = { rename = null }) { n ->
            invalidName(n) ?: run {
                rename = null; selected.clear()
                act { session.rename(r.path, RemoteFtp.join(RemoteFtp.parent(r.path), n)) }; null
            }
        }
    }
    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Elimina") },
            text = {
                Text("Eliminare ${if (list.size == 1) "«${list[0].name}»" else "${list.size} elementi"} da ${device.name}? Non si potrà recuperare.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null; selected.clear()
                    act { session.delete(list); FileOps.post(if (list.size == 1) "Eliminato «${list[0].name}»" else "Eliminati ${list.size} elementi") }
                }) { Text("Elimina", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annulla") } }
        )
    }
}

@Composable
private fun RemoteActionBar(canRename: Boolean, onCopy: () -> Unit, onDownload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(72.dp).shadow(8.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
            .background(cs.surfaceContainerHighest).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteBarAction("Copia", Icons.Outlined.ContentCopy, onCopy)
        RemoteBarAction("Scarica", Icons.Outlined.Download, onDownload)
        RemoteBarAction("Rinomina", Icons.Outlined.DriveFileRenameOutline, onRename, canRename)
        RemoteBarAction("Elimina", Icons.Outlined.Delete, onDelete)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.RemoteBarAction(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Barra "Incolla qui" nelle cartelle del telefono per i file copiati da un altro dispositivo. */
@Composable
fun RemotePasteBar(data: RemoteClipData, dest: File) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
            .background(cs.inverseSurface).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            val n = data.items.size
            Text("${if (n == 1) "1 elemento" else "$n elementi"} da ${data.device.name}", color = cs.inverseOnSurface,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Scegli la cartella e incolla", color = cs.inverseOnSurface.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { RemoteClip.value = null }) { Text("Annulla", color = cs.inverseOnSurface) }
        Button(onClick = {
            RemoteOps.download(data.device, data.login, data.items, dest)
            RemoteClip.value = null
        }) { Text("Incolla qui") }
    }
}
