package com.example.gpsjamlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) startLogger()
            else status.text = "Без доступа к геолокации работа невозможна"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f }

        val start = Button(this).apply {
            text = "Старт"
            setOnClickListener { ensurePermissionsAndStart() }
        }
        val stop = Button(this).apply {
            text = "Стоп"
            setOnClickListener {
                stopService(Intent(this@MainActivity, GpsLoggerService::class.java))
                refresh()
            }
        }
        val battery = Button(this).apply {
            text = "Отключить оптимизацию батареи"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        listOf(status, start, stop, battery).forEach { root.addView(it) }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.text = (if (GpsLoggerService.isRunning) "Сервис работает" else "Сервис остановлен") +
            "\nФайл: Download/${CsvLogger.FILE_NAME}"
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION   // Android 12+: запрашивается вместе с FINE
        )
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 28) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE

        val missing = needed.filterNot { granted(it) }
        if (missing.isEmpty()) startLogger() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startLogger() {
        ContextCompat.startForegroundService(this, Intent(this, GpsLoggerService::class.java))
        refresh()
    }
}
