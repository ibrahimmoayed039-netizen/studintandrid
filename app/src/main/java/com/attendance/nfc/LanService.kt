package com.attendance.nfc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.attendance.nfc.data.LanPrefs
import com.attendance.nfc.data.LanServer
import com.attendance.nfc.data.LanState
import com.attendance.nfc.data.lanLocalIp

/** خدمة أمامية تُبقي خادم الشبكة المحلية شغّالاً حتى لو انتقل التطبيق إلى الخلفية. */
class LanService : Service() {
    private var server: LanServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = LanPrefs(this)
        val ip = lanLocalIp()
        val addr = (ip ?: "غير متصل بالشبكة") + ":" + prefs.port
        startAsForeground(addr)   // يجب استدعاؤه فوراً بعد startForegroundService
        if (!prefs.enabled) { stopSelf(); return START_NOT_STICKY }
        server?.stop(); server = null
        try {
            server = LanServer(applicationContext, prefs.port) { LanPrefs(applicationContext).token }.also { it.start() }
            LanState.running.value = true
            LanState.address.value = addr
            LanState.error.value = if (ip == null) "الهاتف غير متصل بشبكة Wi-Fi" else ""
            acquireWifiLock()
        } catch (e: Exception) {
            LanState.running.value = false
            LanState.error.value = "تعذّر تشغيل الخادم: ${e.message}"
            stopSelf()
        }
        return START_STICKY
    }

    private fun startAsForeground(addr: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "التحكم من الحاسوب", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("التحكم من الحاسوب مفعّل")
            .setContentText(addr)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
    }

    private fun acquireWifiLock() {
        try {
            if (wifiLock?.isHeld == true) return
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "attendance-lan").also { it.acquire() }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        server?.stop(); server = null
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        LanState.running.value = false
        LanState.address.value = ""
        super.onDestroy()
    }

    companion object {
        private const val CH = "lan"
        private const val ID = 4411

        /** يشغّل الخدمة إن كان الخيار مفعّلاً، ويوقفها إن كان معطّلاً. */
        fun sync(ctx: Context) {
            try {
                val i = Intent(ctx, LanService::class.java)
                if (LanPrefs(ctx).enabled) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
            } catch (_: Exception) {}
        }
    }
}
