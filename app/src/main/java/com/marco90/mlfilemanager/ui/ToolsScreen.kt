package com.marco90.mlfilemanager.ui

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marco.mltheme.MLSwitch
import com.marco90.mlfilemanager.net.Net

/** Strumenti/Rete: "Accesso da rete" e "Cerca dispositivi" (con i dispositivi trovati) in un'unica schermata. */
@Composable
fun ToolsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val net by Net.status.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            Text("Strumenti/Rete", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            SectionTitle("Accesso da rete")
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer)
                    .clickable { nav.push(Screen.Network) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ToneIcon(Icons.Outlined.Devices, Tones.orange, 44.dp, 16.dp, 24.dp)
                Column(Modifier.weight(1f)) {
                    Text("Accesso da rete", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        if (net.running) listOfNotNull(net.ftpUrl, net.httpUrl).firstOrNull() ?: "Attivo" else "FTP e browser · spento",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Box(Modifier.width(1.dp).height(32.dp).background(cs.outlineVariant))
                MLSwitch(checked = net.running, onCheckedChange = { on -> if (on) Net.start(ctx) else Net.stop(ctx) })
            }

            DevicesSection(nav)
        }
    }
}
