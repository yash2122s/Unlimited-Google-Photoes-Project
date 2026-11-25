package dev.lantern.android

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import dev.lantern.core.Engine

object Session {
    @Volatile private var instance: Engine? = null
    fun engine(context: Context): Engine = instance ?: synchronized(this) {
        instance ?: context.applicationContext.let { app ->
            Engine(app.filesDir,Build.MODEL,{ SafTree(app,app.contentResolver,it) },{
                val mgr = app.getSystemService(ConnectivityManager::class.java)
                @Suppress("DEPRECATION")
                val lan = mgr.allNetworks.firstOrNull { n -> mgr.getNetworkCapabilities(n)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } == true }
                // An offline router can lose Android's default route to mobile data.
                // Bind Lantern to the actual Wi-Fi network. A local hotspot can also
                // host the Hub without any upstream network.
                val hotspot = dev.lantern.core.addresses {
                    it.name.startsWith("ap") || it.name.startsWith("swlan") || it.name.startsWith("softap")
                }.isNotEmpty()
                val bindTarget = if (hotspot) null else lan
                if (mgr.boundNetworkForProcess != bindTarget) mgr.bindProcessToNetwork(bindTarget)
                lan != null || hotspot || dev.lantern.core.addresses {
                    it.name.startsWith("wlan")
                }.isNotEmpty()
            }).also { instance = it }
        }
    }
}

class LinkService: Service() {
    private lateinit var engine: Engine
    private val handler = Handler(Looper.getMainLooper())
    private var multicast: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var last = ""
    private fun note(): Notification {
        val launchIntent = Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val launch = PendingIntent.getActivity(this,0,launchIntent,PendingIntent.FLAG_IMMUTABLE)
        val p = engine.transfer
        val req = if (engine.receiving) engine.pairRequests.firstOrNull() else null
        val text = when {
            req != null -> "Connection request from ${req.device} · Code ${req.code}"
            p.active && p.fileTotal > 0L -> "${p.file} · ${dev.lantern.core.percent(p.fileBytes, p.fileTotal)}% · ${dev.lantern.core.formatSpeed(p.bytesPerSec)}"
            else -> engine.message
        }
        return Notification.Builder(this,"link").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (engine.receiving) "Lantern · Receiving Hub" else "Lantern · Sender")
            .setContentText(text).setContentIntent(launch).setOnlyAlertOnce(true).setOngoing(true).build()
    }
    private val refresh = object: Runnable {
        override fun run() {
            val p = engine.transfer
            val req = if (engine.receiving) engine.pairRequests.firstOrNull() else null
            val sig = when {
                req != null -> "REQ|${req.id}|${engine.requestTick}"
                p.active -> "${p.file}|${dev.lantern.core.percent(p.fileBytes,p.fileTotal)}|${dev.lantern.core.formatSpeed(p.bytesPerSec)}"
                else -> engine.message
            }
            if (sig != last) { last = sig; getSystemService(NotificationManager::class.java).notify(72,note()) }
            handler.postDelayed(this,1200)
        }
    }
    override fun onCreate() {
        super.onCreate()
        engine = Session.engine(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("link","Local transfers",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(72,note())
        if (!engine.enabled) { stopSelf(); return START_NOT_STICKY }
        if (multicast == null) multicast = applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("Lantern:discovery").apply { acquire() }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java).createWifiLock(mode,"Lantern:wifi").apply { acquire() }
        }
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Lantern:local-transfer").apply { acquire() }
        engine.start()
        handler.removeCallbacks(refresh); handler.post(refresh)
        return START_STICKY
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        handler.removeCallbacks(refresh); engine.stop()
        multicast?.let { if(it.isHeld) it.release() }; wifiLock?.let { if(it.isHeld) it.release() }; wake?.let { if(it.isHeld) it.release() }
        super.onDestroy()
    }
}
class WakeReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action == Intent.ACTION_BOOT_COMPLETED && Session.engine(context).enabled)
            context.startForegroundService(Intent(context,LinkService::class.java))
    }
}

