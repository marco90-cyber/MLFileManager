package com.marco90.mlfilemanager.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import com.marco90.mlfilemanager.data.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address

/** Un dispositivo trovato sulla rete (o salvato). */
data class RemoteDevice(
    /** Nome mostrato (nome del telefono). */
    val name: String,
    val host: String,
    val port: Int,
    /** true = File Manager+ (altra app), false = MLFileManager. */
    val fmPlus: Boolean = false,
    /** true se trovato ora sulla rete. */
    val online: Boolean = true,
) {
    val key: String get() = (if (fmPlus) "fm+:" else "ml:") + name
}

/**
 * Ricerca automatica in rete locale (DNS-SD / mDNS, come stampanti e Chromecast).
 * - MLFileManager si annuncia come `_mlfilemanager._tcp` quando il server FTP è acceso.
 * - Cerca gli altri MLFileManager e anche i telefoni con File Manager+ (`_fmtransferftp._tcp`).
 */
object Discovery {
    const val TYPE_ML = "_mlfilemanager._tcp"
    const val TYPE_FMPLUS = "_fmtransferftp._tcp"

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    /** Nome con cui questo telefono si è annunciato (per non mostrare sé stesso). */
    @Volatile var ownName: String? = null
        private set

    fun deviceName(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    // ---------- Annuncio (lato server) ----------

    private var regListener: NsdManager.RegistrationListener? = null

    fun register(ctx: Context, port: Int) {
        unregister(ctx)
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName(ctx)
            serviceType = TYPE_ML
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                ownName = s.serviceName
                AppLog.i("Rete", "Annunciato in rete come «${s.serviceName}»")
            }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { AppLog.w("Rete", "Annuncio in rete non riuscito ($code)") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            regListener = l
        } catch (e: Exception) { AppLog.w("Rete", "Annuncio in rete non riuscito: ${e.message}") }
    }

    fun unregister(ctx: Context) {
        val l = regListener ?: return
        regListener = null
        ownName = null
        try { ctx.getSystemService(NsdManager::class.java)?.unregisterService(l) } catch (_: Exception) { }
    }

    // ---------- Ricerca (lato client) ----------

    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    private val found = LinkedHashMap<String, RemoteDevice>()
    private val pending = ArrayDeque<Pair<NsdServiceInfo, Boolean>>()
    private var resolving = false
    private var nsdRef: NsdManager? = null
    private var users = 0

    @Synchronized
    fun start(ctx: Context) {
        users++
        if (listeners.isNotEmpty()) return
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
        nsdRef = nsd
        synchronized(found) { found.clear() }
        publish()
        _searching.value = true
        for ((type, fm) in listOf(TYPE_ML to false, TYPE_FMPLUS to true)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, code: Int) { AppLog.w("Rete", "Ricerca $t non avviata ($code)") }
                override fun onStopDiscoveryFailed(t: String, code: Int) {}
                override fun onServiceFound(s: NsdServiceInfo) {
                    if (!fm && s.serviceName == ownName) return
                    enqueue(s, fm)
                }
                override fun onServiceLost(s: NsdServiceInfo) {
                    val key = (if (fm) "fm+:" else "ml:") + s.serviceName
                    synchronized(found) { found.remove(key) }
                    publish()
                }
            }
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
                listeners += l
            } catch (e: Exception) { AppLog.w("Rete", "Ricerca non avviata: ${e.message}") }
        }
    }

    @Synchronized
    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        val nsd = nsdRef ?: return
        listeners.forEach { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) { } }
        listeners.clear()
        synchronized(pending) { pending.clear() }
        _searching.value = false
    }

    private fun publish() {
        _devices.value = synchronized(found) { found.values.sortedBy { it.name.lowercase() } }
    }

    // Android risolve un servizio alla volta: coda
    private fun enqueue(s: NsdServiceInfo, fm: Boolean) {
        synchronized(pending) {
            pending.addLast(s to fm)
            if (resolving) return
            resolving = true
        }
        next()
    }

    private fun next() {
        val (s, fm) = synchronized(pending) {
            val n = pending.removeFirstOrNull()
            if (n == null) { resolving = false; return }
            n
        }
        val nsd = nsdRef ?: run { synchronized(pending) { resolving = false }; return }
        @Suppress("DEPRECATION")
        try {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) { next() }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = hostOf(info)
                    if (host != null && !(fm.not() && info.serviceName == ownName)) {
                        val d = RemoteDevice(info.serviceName, host, info.port, fm)
                        synchronized(found) { found[d.key] = d }
                        AppLog.d("Rete", "Trovato ${if (fm) "File Manager+" else "MLFileManager"}: ${d.name} ($host:${d.port})")
                        publish()
                    }
                    next()
                }
            })
        } catch (_: Exception) { next() }
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress?.let { return it }
            info.hostAddresses.firstOrNull()?.hostAddress?.let { return it }
        }
        return info.host?.hostAddress
    }
}
