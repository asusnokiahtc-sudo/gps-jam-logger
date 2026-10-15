package com.example.gpsjamlogger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

class GpsLoggerService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.gpsjamlogger.STOP"
        @Volatile var isRunning = false

        // ---- Настройки ----
        const val LOG_DISTANCE_M = 100f              // шаг записи
        const val NO_FIX_TIMEOUT_MS = 15_000L        // сколько молчания = «деградация»
        const val DEGRADED_LOG_INTERVAL_MS = 30_000L // как часто писать строки без фикса (0 = не писать)
        const val RESTART_AFTER_MS = 60_000L         // перерегистрация слушателей при «зависании»
        const val SAT_STALE_MS = 5_000L              // кэш спутников старше этого считается устаревшим
        private const val WATCHDOG_PERIOD_MS = 5_000L
        private const val CHANNEL_ID = "gps_logger"
        private const val NOTIF_ID = 1
    }

    /** Неизменяемый срез по спутникам — безопасно читается из любого потока. */
    private data class SatSnapshot(
        val total: Int = 0,
        val used: Int = 0,
        val avgSnr: Float = 0f,
        val at: Long = 0L
    )

    private lateinit var lm: LocationManager
    private lateinit var csv: CsvLogger
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var sat = SatSnapshot()

    // Всё ниже трогается только из потока handler
    private var started = false
    private var startedAt = 0L
    private var lastFix: Location? = null
    private var lastFixAt = 0L
    private var lastLogged: Location? = null
    private var lastDegradedLogAt = 0L
    private var lastRestartAt = 0L
    private var rows = 0
    private var degraded = false
    private var writeError = false

    // ---------- GNSS: фоновое обновление кэша спутников ----------
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var sum = 0f
            var n = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
                val cn0 = status.getCn0DbHz(i)
                if (cn0 > 0f) { sum += cn0; n++ }       // только реально отслеживаемые
            }
            sat = SatSnapshot(
                total = status.satelliteCount,
                used = used,
                avgSnr = if (n > 0) sum / n else 0f,
                at = SystemClock.elapsedRealtime()
            )
        }

        override fun onStopped() { sat = SatSnapshot() }
    }

    // ---------- Координаты ----------
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)

        // Нужны для устройств с API < 29, где эти методы не имеют реализации по умолчанию
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val silence = now - (if (lastFixAt == 0L) startedAt else lastFixAt)

            if (silence >= NO_FIX_TIMEOUT_MS) {
                degraded = true

                // Фиксируем деградацию: последние валидные координаты, accuracy = -1,
                // плюс актуальные данные по спутникам (видно, что спутники есть, но решения нет).
                if (DEGRADED_LOG_INTERVAL_MS > 0 && now - lastDegradedLogAt >= DEGRADED_LOG_INTERVAL_MS) {
                    val f = lastFix
                    writeRow(System.currentTimeMillis(), f?.latitude, f?.longitude, -1f)
                    lastDegradedLogAt = now
                }

                // Возможное зависание GPS-модуля — переподписываемся
                if (silence >= RESTART_AFTER_MS && now - lastRestartAt >= RESTART_AFTER_MS) {
                    lastRestartAt = now
                    unregisterListeners()
                    registerListeners()
                }
                updateNotification()
            }
            handler.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        csv = CsvLogger(applicationContext)
        thread = HandlerThread("gps-logger").also { it.start() }
        handler = Handler(thread.looper)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GPS мониторинг", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true

        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Запуск…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        isRunning = true

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsJamLogger:wl").apply {
            setReferenceCounted(false)
            acquire()
        }

        startedAt = SystemClock.elapsedRealtime()
        handler.post {
            registerListeners()
            handler.postDelayed(watchdog, WATCHDOG_PERIOD_MS)
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun registerListeners() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            // Именно GPS_PROVIDER: Fused подмешивает Wi-Fi/сотовые и маскирует глушение.
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, thread.looper
            )
            if (Build.VERSION.SDK_INT >= 30) {
                lm.registerGnssStatusCallback(Executor { handler.post(it) }, gnssCallback)
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(gnssCallback, handler)
            }
        } catch (e: SecurityException) {
            stopSelf()
        } catch (e: Exception) {
            // GPS-провайдер недоступен — watchdog повторит попытку
        }
    }

    private fun unregisterListeners() {
        try { lm.removeUpdates(locationListener) } catch (_: Exception) {}
        try { lm.unregisterGnssStatusCallback(gnssCallback) } catch (_: Exception) {}
    }

    // ---------- Логика 100-метрового триггера ----------
    private fun onFix(loc: Location) {
        lastFix = loc
        lastFixAt = SystemClock.elapsedRealtime()
        degraded = false

        val prev = lastLogged
        val due = if (prev == null) {
            true
        } else {
            val r = FloatArray(1)
            Location.distanceBetween(prev.latitude, prev.longitude, loc.latitude, loc.longitude, r)
            r[0] >= LOG_DISTANCE_M
        }

        if (due) {
            val t = if (loc.time > 0) loc.time else System.currentTimeMillis()
            writeRow(t, loc.latitude, loc.longitude, loc.accuracy)
            lastLogged = loc     // новая опорная точка для следующих 100 м
        }
        updateNotification()
    }

    private fun currentSat(): SatSnapshot {
        val s = sat
        return if (SystemClock.elapsedRealtime() - s.at > SAT_STALE_MS) SatSnapshot() else s
    }

    private fun writeRow(timeMs: Long, lat: Double?, lon: Double?, acc: Float) {
        val s = currentSat()
        val ok = csv.append(LogRow(timeMs, lat, lon, acc, s.total, s.used, s.avgSnr))
        writeError = !ok
        if (ok) rows++
    }

    // ---------- Уведомление ----------
    private fun updateNotification() {
        val s = currentSat()
        val text = buildString {
            append("Записей: $rows · спутники ${s.used}/${s.total} · SNR ${"%.0f".format(s.avgSnr)}")
            if (degraded) append(" · НЕТ ФИКСА")
            if (writeError) append(" · ошибка записи")
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GpsLoggerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GPS мониторинг")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Стоп", stopIntent)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        unregisterListeners()
        thread.quitSafely()
        csv.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
