package com.example.gpsjamlogger

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Одна строка лога. lat/lon == null, если валидного фикса ещё не было. */
data class LogRow(
    val timeMs: Long,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Float,        // -1 = нет фикса (деградация)
    val satsTotal: Int,
    val satsUsed: Int,
    val avgSnr: Float
) {
    fun toCsv(): String = String.format(
        Locale.US, "%s,%s,%s,%s,%d,%d,%.1f\n",
        TS.format(java.time.Instant.ofEpochMilli(timeMs)),
        lat?.let { String.format(Locale.US, "%.6f", it) } ?: "",
        lon?.let { String.format(Locale.US, "%.6f", it) } ?: "",
        if (accuracyM < 0) "-1" else String.format(Locale.US, "%.1f", accuracyM),
        satsTotal, satsUsed, avgSnr
    )

    companion object {
        private val TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
    }
}

/** Дописывает строки в ОДИН файл Download/gps_jam_log.csv (между запусками тоже). */
class CsvLogger(private val context: Context) {

    companion object {
        const val FILE_NAME = "gps_jam_log.csv"
        private const val HEADER =
            "Timestamp,Latitude,Longitude,Accuracy_m,Satellites_Total,Satellites_Used,Avg_SNR\n"
    }

    private var stream: OutputStream? = null

    @Synchronized
    fun append(row: LogRow): Boolean = try {
        if (stream == null) {
            stream = if (Build.VERSION.SDK_INT >= 29) openMediaStore() else openLegacy()
        }
        stream!!.write(row.toCsv().toByteArray())
        stream!!.flush()          // чтобы не потерять данные при убийстве процесса
        true
    } catch (e: Exception) {
        close()                   // следующая запись попробует открыть файл заново
        false
    }

    @Synchronized
    fun close() {
        try { stream?.close() } catch (_: Exception) {}
        stream = null
    }

    @RequiresApi(29)
    private fun openMediaStore(): OutputStream {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // Ищем уже созданный нами файл, чтобы дописывать в него, а не плодить копии.
        val cursor = resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf(FILE_NAME, Environment.DIRECTORY_DOWNLOADS + "%"),
            null
        )
        val existingId = cursor?.use { if (it.moveToFirst()) it.getLong(0) else null }

        if (existingId != null) {
            val uri = ContentUris.withAppendedId(collection, existingId)
            return resolver.openOutputStream(uri, "wa")!!
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(collection, values)!!
        val os = resolver.openOutputStream(uri, "wa")!!
        os.write(HEADER.toByteArray())
        return os
    }

    private fun openLegacy(): OutputStream {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val f = File(dir, FILE_NAME)
        val isNew = !f.exists() || f.length() == 0L
        val os = FileOutputStream(f, true)
        if (isNew) os.write(HEADER.toByteArray())
        return os
    }
}
