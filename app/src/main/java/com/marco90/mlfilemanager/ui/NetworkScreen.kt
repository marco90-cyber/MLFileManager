package com.marco90.mlfilemanager.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marco.mltheme.LocalPrefEnabled
import com.marco.mltheme.MLGroup
import com.marco.mltheme.MLSwitchRow
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.net.Net
import kotlinx.coroutines.delay

@Composable
fun NetworkScreen(nav: Nav) {
    val ctx = LocalContext.current
    val s = AppSettings.get(ctx)
    val cs = MaterialTheme.colorScheme
    val st by Net.status.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val label = remember(tick, st.running) { Net.networkLabel(ctx) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Net.start(ctx) }
    fun start() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else Net.start(ctx)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            Text("Accesso da rete", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Stato
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(if (st.running) cs.primaryContainer else cs.surfaceContainer).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val fg = if (st.running) cs.onPrimaryContainer else cs.onSurface
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(if (st.running) cs.primary else cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        Icon(
                            if (label == "Wi-Fi non connesso") Icons.Outlined.WifiOff else Icons.Outlined.Wifi, null,
                            tint = if (st.running) cs.onPrimary else cs.onSurfaceVariant
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (st.running) "Servizio attivo" else "Servizio spento", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = fg)
                        Text(
                            if (st.running && st.clients > 0) "$label · ${st.clients} collegati" else label,
                            style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = .8f)
                        )
                    }
                    Button(
                        onClick = { if (st.running) Net.stop(ctx) else start() },
                        colors = if (st.running) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)
                        else androidx.compose.material3.ButtonDefaults.buttonColors(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(if (st.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Text(if (st.running) "Arresta" else "Avvia", modifier = Modifier.padding(start = 6.dp), fontWeight = FontWeight.SemiBold)
                    }
                }
                if (st.running) {
                    st.ftpUrl?.let { Address("FTP", it) }
                    st.httpUrl?.let { Address("BROWSER", it) }
                    Credential("Nome utente", st.user)
                    Credential("Password", st.password)
                    Text(
                        "Su Windows scrivi in Esplora file l'indirizzo FTP completo (con ftp:// e la porta) e accedi con nome utente e password. Nel browser usa l'indirizzo BROWSER.",
                        style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = .85f)
                    )
                } else {
                    Text(
                        "Avvia il servizio per accedere ai file da un altro dispositivo sulla stessa rete Wi-Fi o sull'hotspot del telefono.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
                    )
                }
                st.error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
            }

            TransfersCard(s.binaryUnits)

            // Con il servizio attivo le voci restano visibili (in grigio) ma non si cambiano;
            // la pagina si può sempre scorrere fino in fondo.
            val editable = !st.running
            CompositionLocalProvider(LocalPrefEnabled provides editable) {
                MLGroup("Server") {
                    MLSwitchRow("Server FTP", "Per Esplora file, FileZilla, VLC", s.netFtp, icon = Icons.Outlined.Devices) { if (editable) s.netFtp = it }
                    MLSwitchRow("Accesso da browser", "Scarica e carica file da Chrome · porta ${s.netPort + 1}", s.netHttp, icon = Icons.Outlined.Language) { if (editable) s.netHttp = it }
                    FieldRow("Porta", s.netPort.toString(), KeyboardType.Number, editable) { v ->
                        v.toIntOrNull()?.takeIf { it in 1024..65534 }?.let { s.netPort = it }
                    }
                    FieldRow("Nome utente", s.netUser, KeyboardType.Text, editable) { v -> if (v.isNotBlank()) s.netUser = v.trim() }
                    if (!s.netRandomPassword) FieldRow("Password", s.netPassword, KeyboardType.Text, editable) { v -> if (v.isNotEmpty()) s.netPassword = v }
                    MLSwitchRow("Password casuale", "Nuova password a ogni avvio", s.netRandomPassword, icon = Icons.Outlined.Lock) { if (editable) s.netRandomPassword = it }
                }
                MLGroup("Opzioni") {
                    MLSwitchRow("Mostra file nascosti", null, s.netShowHidden, icon = Icons.Outlined.Visibility) { if (editable) s.netShowHidden = it }
                    MLSwitchRow("Sola lettura", "Gli altri possono solo scaricare", s.netReadOnly, icon = Icons.Outlined.Lock) { if (editable) s.netReadOnly = it }
                }
            }
            if (st.running) Text(
                "Per cambiare le opzioni arresta prima il servizio",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

        }
    }
}

@Composable
private fun Address(label: String, url: String) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surface)
            .clickable {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, url))
                Toast.makeText(ctx, "Indirizzo copiato", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.width(64.dp))
        Text(url, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ContentCopy, "Copia", tint = cs.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FieldRow(label: String, value: String, type: KeyboardType, enabled: Boolean = true, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var text by remember(value) { mutableStateOf(value) }
    Row(
        Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = .38f), modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onChange(it) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = type),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.width(130.dp)
        )
    }
}

@Composable
private fun Credential(label: String, value: String) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surface)
            .clickable {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
                Toast.makeText(ctx, "$label copiato", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ContentCopy, "Copia", tint = cs.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TransfersCard(binary: Boolean) {
    val items by com.marco90.mlfilemanager.net.Transfers.items.collectAsStateWithLifecycle()
    if (items.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val active = items.filter { it.active }
    val finished = items.filter { !it.active }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (active.isNotEmpty()) "Trasferimenti in corso (${active.size})" else "Trasferimenti recenti",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.primary,
                modifier = Modifier.weight(1f)
            )
            if (finished.isNotEmpty()) androidx.compose.material3.TextButton(onClick = { com.marco90.mlfilemanager.net.Transfers.clearFinished() }) { Text("Pulisci") }
        }
        Column(Modifier.clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            (active + finished).forEach { t ->
                Column(
                    Modifier.fillMaxWidth().background(if (t.active) cs.primaryContainer else cs.surfaceContainer).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val fg = if (t.active) cs.onPrimaryContainer else cs.onSurface
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(
                            when {
                                !t.active && !t.ok -> Icons.Outlined.ErrorOutline
                                !t.active -> Icons.Outlined.CheckCircle
                                t.upload -> Icons.Outlined.Download
                                else -> Icons.Outlined.Upload
                            },
                            null,
                            tint = if (!t.active && !t.ok) cs.error else if (t.active) cs.primary else cs.onSurfaceVariant
                        )
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge, color = fg, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(
                                "${if (t.upload) "In arrivo" else "In uscita"} · ${t.proto} · ${t.client}",
                                style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = .75f)
                            )
                        }
                        if (t.active && t.fraction >= 0f) Text("${(t.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = cs.primary, fontWeight = FontWeight.SemiBold)
                    }
                    if (t.active) {
                        if (t.fraction >= 0f) androidx.compose.material3.LinearProgressIndicator(
                            progress = { t.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        ) else androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    }
                    Text(com.marco90.mlfilemanager.net.Transfers.summary(t, binary), style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = .85f))
                }
            }
        }
    }
}
