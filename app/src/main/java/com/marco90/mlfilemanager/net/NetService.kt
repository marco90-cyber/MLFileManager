package com.marco90.mlfilemanager.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.marco90.mlfilemanager.MainActivity
import com.marco90.mlfilemanager.R
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.data.ROOT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetStatus(
    val running: Boolean = false,
    val ftpUrl: String? = null,
    val httpUrl: String? = null,
    val user: String = "",
    val password: String = "",
    val clients: Int = 0,
    val error: String? = null,
)

object Net {
    private val _status = MutableStateFlow(NetStatus())
    val status: StateFlow<NetStatus> = _status
    internal fun set(s: NetStatus) { _status.value = s }
    internal fun update(f: (NetStatus) -> NetStatus) { _status.value = f(_status.value) }

    private var appCtx: Context? = null
    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    /** Aggiorna la galleria dopo un file arrivato o tolto dalla rete. */
    fun scan(f: File) {
        val c = appCtx ?: return
        MediaScannerConnection.scanFile(c, arrayOf(f.absolutePath), null, null)
    }

    /** Indirizzi IPv4 della rete locale: (nome interfaccia, indirizzo). Prima il Wi-Fi, poi l'hotspot. */
    fun addresses(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach { a ->
                    if (a.isSiteLocalAddress) out += ni.name to (a.hostAddress ?: return@forEach)
                }
            }
        } catch (_: Exception) { }
        return out.sortedBy { (n, _) ->
            when {
                n.startsWith("wlan0") -> 0
                n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") -> 1
                n.startsWith("rndis") || n.startsWith("eth") -> 2
                else -> 3
            }
        }
    }

    /** Testo per lo stato della rete, come in File Manager+. */
    fun networkLabel(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val names = addresses().map { it.first }
        return when {
            wifi -> "Wi-Fi connesso"
            names.any { it.startsWith("ap") || it.startsWith("swlan") || (it.startsWith("wlan") && it != "wlan0") } -> "Hotspot attivo"
            names.any { it.startsWith("rndis") } -> "Tethering USB attivo"
            names.isNotEmpty() -> "Rete locale disponibile"
            else -> "Wi-Fi non connesso"
        }
    }

    fun start(ctx: Context) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, NetService::class.java).setAction(NetService.ACTION_START))
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, NetService::class.java).setAction(NetService.ACTION_STOP))
    }
}

/** Servizio in primo piano che tiene accesi i server FTP e HTTP. */
class NetService : Service() {
    private var ftp: FtpServer? = null
    private var http: HttpServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var baseText = ""
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastShown = ""
    /** Ogni secondo aggiorna la notifica con i trasferimenti in corso. */
    private val ticker = object : Runnable {
        override fun run() {
            if (ftp == null && http == null) return
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Net.init(this)
        if (intent?.action == ACTION_STOP) {
            stopAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (ftp != null || http != null) return START_STICKY
        startAll()
        return START_STICKY
    }

    private fun startAll() {
        val s = AppSettings.get(this)
        val pwd = if (s.netRandomPassword) (100000..999999).random().toString() else s.netPassword
        val user = s.netUser.trim().ifEmpty { "mlapps" }
        val ip = Net.addresses().firstOrNull()?.second
        var ftpUrl: String? = null
        var httpUrl: String? = null
        var error: String? = null

        foreground("Avvio…")
        if (s.netFtp) try {
            ftp = FtpServer(ROOT, s.netPort, user, pwd, s.netShowHidden, s.netReadOnly) { n -> Net.update { it.copy(clients = n) } }.also { it.start() }
            ftpUrl = "ftp://${ip ?: "?"}:${s.netPort}"
            Discovery.register(this, s.netPort)
        } catch (e: Exception) { error = "FTP: porta ${s.netPort} non disponibile (${e.message})" }
        if (s.netHttp) try {
            http = HttpServer(s.netPort + 1, ROOT, user, pwd, s.netShowHidden, s.netReadOnly).also { it.startServer() }
            httpUrl = "http://${ip ?: "?"}:${s.netPort + 1}"
        } catch (e: Exception) { error = (error?.plus("\n") ?: "") + "Browser: porta ${s.netPort + 1} non disponibile (${e.message})" }

        if (ftp == null && http == null) {
            com.marco90.mlfilemanager.data.AppLog.e("Rete", error ?: "Nessun server scelto")
            Net.set(NetStatus(running = false, error = error ?: "Nessun server scelto"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MLFileManager:net").apply { acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MLFileManager:net").apply { acquire(6 * 60 * 60 * 1000L) }

        com.marco90.mlfilemanager.data.AppLog.i("Rete", "Servizio avviato: ${ftpUrl ?: "FTP spento"} · ${httpUrl ?: "browser spento"}")
        if (error != null) com.marco90.mlfilemanager.data.AppLog.w("Rete", error)
        Net.set(NetStatus(running = true, ftpUrl = ftpUrl, httpUrl = httpUrl, user = user, password = pwd, error = error))
        baseText = listOfNotNull(ftpUrl, httpUrl).joinToString("  ·  ")
        foreground(baseText)
        handler.postDelayed(ticker, 1000)
    }

    private fun stopAll() {
        Discovery.unregister(this)
        handler.removeCallbacks(ticker)
        ftp?.stop(); ftp = null
        http?.stop(); http = null
        try { wifiLock?.release() } catch (_: Exception) { }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wifiLock = null; wakeLock = null
        Net.set(NetStatus(running = false))
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun notification(title: String, text: String, progress: Int? = null, indeterminate: Boolean = false): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Accesso da rete", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, NetService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_net)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Arresta", stop).build())
        if (progress != null || indeterminate) b.setProgress(100, progress ?: 0, indeterminate)
        return b.build()
    }

    private fun foreground(text: String) {
        val n = notification("Accesso da rete attivo", text)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
    }

    private fun updateNotification() {
        val binary = AppSettings.get(this).binaryUnits
        val act = Transfers.items.value.filter { it.active }
        val (title, text, pct, indet) = when {
            act.isEmpty() -> Quad("Accesso da rete attivo", baseText, null, false)
            act.size == 1 -> {
                val t = act[0]
                Quad(
                    "${if (t.upload) "In arrivo" else "In uscita"}: ${t.name}",
                    "${t.proto} · ${t.client}\n${Transfers.summary(t, binary)}",
                    if (t.fraction >= 0f) (t.fraction * 100).toInt() else null,
                    t.fraction < 0f
                )
            }
            else -> {
                val known = act.filter { it.total > 0 }
                val total = known.sumOf { it.total }
                val done = known.sumOf { it.done }
                val speed = act.sumOf { it.speed }
                val eta = if (speed > 0 && total > 0) (total - done).coerceAtLeast(0) / speed else -1L
                val line = buildString {
                    append(com.marco90.mlfilemanager.data.formatSize(act.sumOf { it.done }, binary))
                    if (total > 0) append(" di ").append(com.marco90.mlfilemanager.data.formatSize(total, binary))
                    if (speed > 0) append(" · ").append(com.marco90.mlfilemanager.data.formatSize(speed, binary)).append("/s")
                    if (eta >= 0) append(" · ").append(Transfers.etaText(eta)).append(" rimanenti")
                }
                Quad(
                    "${act.size} trasferimenti in corso",
                    act.joinToString("\n") { (if (it.upload) "⬇ " else "⬆ ") + it.name } + "\n" + line,
                    if (total > 0) (done * 100 / total).toInt() else null,
                    total <= 0
                )
            }
        }
        val key = "$title|$text|$pct|$indet"
        if (key == lastShown) return
        lastShown = key
        getSystemService(NotificationManager::class.java).notify(ID, notification(title, text, pct, indet))
    }

    private data class Quad(val a: String, val b: String, val c: Int?, val d: Boolean)

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "net"
        private const val ID = 42
    }
}
