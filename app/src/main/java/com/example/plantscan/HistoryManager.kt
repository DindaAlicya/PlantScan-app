package com.example.plantscan

import android.content.Context
import android.graphics.Bitmap
import com.example.plantscan.ml.PredictionResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

// ================================================================
// DATA MODEL
// ================================================================
data class ScanRecord(
    val id          : String,
    val timestamp   : Long,
    val imagePath   : String,
    val diseaseName : String,
    val className   : String,
    val confidence  : Float,
    val dangerLevel : String,
    val cause       : String,
    val symptom     : String,
    val solution    : String
) {
    val dateFormatted: String
        get() = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id")).format(Date(timestamp))

    fun toPredictionResult() = PredictionResult(
        className   = className,
        displayName = diseaseName,
        confidence  = confidence,
        dangerLevel = dangerLevel,
        cause       = cause,
        symptom     = symptom,
        solution    = solution
    )
}

// ================================================================
// HISTORY MANAGER
// ================================================================
object HistoryManager {

    private const val PREFS_NAME  = "plantscan_history"
    private const val KEY_RECORDS = "scan_records"
    private const val MAX_RECORDS = 50

    fun saveRecord(context: Context, bitmap: Bitmap, result: PredictionResult): ScanRecord {
        val id        = UUID.randomUUID().toString().take(8)
        val imagePath = saveImage(context, bitmap, id)
        val record    = ScanRecord(
            id          = id,
            timestamp   = System.currentTimeMillis(),
            imagePath   = imagePath,
            diseaseName = result.displayName,
            className   = result.className,
            confidence  = result.confidence,
            dangerLevel = result.dangerLevel,
            cause       = result.cause,
            symptom     = result.symptom,
            solution    = result.solution
        )
        persistRecord(context, record)
        return record
    }

    private fun saveImage(context: Context, bitmap: Bitmap, id: String): String {
        val dir = File(context.filesDir, "scan_history")
        dir.mkdirs()
        val file = File(dir, "$id.jpg")
        // Scale down to max 600px for storage efficiency
        val maxDim = 600
        val scale  = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1f)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return file.absolutePath
    }

    private fun persistRecord(context: Context, record: ScanRecord) {
        val prefs    = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_RECORDS, "[]") ?: "[]"
        val arr      = JSONArray(existing)
        val newArr   = JSONArray()
        newArr.put(toJson(record))
        for (i in 0 until minOf(arr.length(), MAX_RECORDS - 1)) newArr.put(arr.getJSONObject(i))
        prefs.edit().putString(KEY_RECORDS, newArr.toString()).apply()
    }

    fun getRecords(context: Context): List<ScanRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_RECORDS, "[]") ?: "[]"
        val arr   = JSONArray(json)
        return (0 until arr.length()).mapNotNull {
            runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull()
        }
    }

    fun deleteRecord(context: Context, id: String) {
        val prefs    = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_RECORDS, "[]") ?: "[]"
        val arr      = JSONArray(existing)
        val newArr   = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("id") == id) {
                runCatching { File(obj.optString("imagePath")).delete() }
            } else {
                newArr.put(obj)
            }
        }
        prefs.edit().putString(KEY_RECORDS, newArr.toString()).apply()
    }

    private fun toJson(r: ScanRecord) = JSONObject().apply {
        put("id",          r.id)
        put("timestamp",   r.timestamp)
        put("imagePath",   r.imagePath)
        put("diseaseName", r.diseaseName)
        put("className",   r.className)
        put("confidence",  r.confidence.toDouble())
        put("dangerLevel", r.dangerLevel)
        put("cause",       r.cause)
        put("symptom",     r.symptom)
        put("solution",    r.solution)
    }

    private fun fromJson(o: JSONObject) = ScanRecord(
        id          = o.getString("id"),
        timestamp   = o.getLong("timestamp"),
        imagePath   = o.getString("imagePath"),
        diseaseName = o.getString("diseaseName"),
        className   = o.optString("className", ""),
        confidence  = o.getDouble("confidence").toFloat(),
        dangerLevel = o.getString("dangerLevel"),
        cause       = o.getString("cause"),
        symptom     = o.getString("symptom"),
        solution    = o.getString("solution")
    )
}