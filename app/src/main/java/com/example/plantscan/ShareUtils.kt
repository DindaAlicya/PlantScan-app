package com.example.plantscan

import android.content.Context
import android.content.Intent
import android.graphics.*
import androidx.core.content.FileProvider
import com.example.plantscan.ml.PredictionResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object ShareUtils {

    fun shareResult(context: Context, leafBitmap: Bitmap, result: PredictionResult) {
        val card      = generateShareCard(leafBitmap, result)
        val shareFile = saveToCacheDir(context, card)
        val uri       = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            shareFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "Hasil scan PlantScan: ${result.displayName} " +
                        "(${String.format("%.1f", result.confidence)}%)\n" +
                        "Bahaya: ${result.dangerLevel}"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Bagikan Hasil Scan"))
    }

    private fun saveToCacheDir(context: Context, bitmap: Bitmap): File {
        val shareDir = File(context.cacheDir, "share")
        shareDir.mkdirs()
        val file = File(shareDir, "plantscan_result.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return file
    }

    // ────────────────────────────────────────────────────────────────────────────
    // CARD GENERATOR  (1080 × 1350 px)
    // Layout: header 120px | leaf photo 380px | result body | footer
    // ────────────────────────────────────────────────────────────────────────────
    private fun generateShareCard(leafBitmap: Bitmap, result: PredictionResult): Bitmap {
        val W       = 1080
        val H       = 1350
        val PAD     = 56f
        val bitmap  = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas  = Canvas(bitmap)

        // ── Background ────────────────────────────────────────────────────────
        canvas.drawColor(0xFF0F261C.toInt())

        // ── Header bar ────────────────────────────────────────────────────────
        canvas.drawRect(0f, 0f, W.toFloat(), 120f, solidPaint(0xFF133126))
        canvas.drawCircle(PAD + 14f, 60f, 14f, solidPaint(0xFF1F6B4F))
        drawText(canvas, "PlantScan", PAD + 40f, 52f, 42f, 0xFF1F6B4F, bold = true)
        drawText(canvas, "Deteksi Penyakit Tanaman", PAD + 40f, 88f, 25f, 0xFF9BB8A9, mono = true)

        // ── Leaf photo ────────────────────────────────────────────────────────
        val photoH = 380
        val scaled = Bitmap.createScaledBitmap(leafBitmap, W, photoH, true)
        canvas.drawBitmap(scaled, 0f, 120f, null)
        // Subtle dark overlay on photo for better text contrast below
        canvas.drawRect(0f, 120f, W.toFloat(), 500f, alphaPaint(0x44000000))

        // ── Result body ───────────────────────────────────────────────────────
        val cardColor: Long = when (result.dangerLevel) {
            "Sangat Tinggi" -> 0xFF74070E
            "Tinggi"        -> 0xFFDC4B2A
            "Sedang"        -> 0xFF9E1B1F
            else            -> 0xFF1F6B4F
        }
        val bodyTop = 520f
        val bodyRect = RectF(PAD, bodyTop, W - PAD, 1270f)
        canvas.drawRoundRect(bodyRect, 28f, 28f, alphaPaint((cardColor shl 8 ushr 8 or 0x1A000000).toInt()))
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardColor.toInt(); alpha = 90
            style = Paint.Style.STROKE; strokeWidth = 2f
        }
        canvas.drawRoundRect(bodyRect, 28f, 28f, borderPaint)

        var y = bodyTop + 46f

        // TERDETEKSI label
        drawText(canvas, "TERDETEKSI", PAD + 32f, y, 22f, cardColor, mono = true)
        y += 52f

        // Disease name (may wrap – limit to 30 chars for the card)
        val dispName = if (result.displayName.length > 30) result.displayName.take(28) + "…" else result.displayName
        drawText(canvas, dispName, PAD + 32f, y, 52f, 0xFFE6F2EC, bold = true)
        y += 34f

        // Confidence
        drawText(canvas, "Confidence: ${String.format("%.1f", result.confidence)}%", PAD + 32f, y, 27f, 0xFF9BB8A9, mono = true)
        y += 32f

        // Progress bar
        val barL = PAD + 32f; val barR = W - PAD - 32f; val barW = barR - barL
        canvas.drawRoundRect(RectF(barL, y, barR, y + 16f), 8f, 8f, solidPaint(0xFF1F4A39))
        canvas.drawRoundRect(RectF(barL, y, barL + barW * (result.confidence / 100f), y + 16f), 8f, 8f, solidPaint(cardColor))
        y += 44f

        // Divider
        canvas.drawRect(PAD + 32f, y, W - PAD - 32f, y + 1f, solidPaint(0xFF1F4A39))
        y += 28f

        // Info rows
        y = infoRow(canvas, "Tingkat Bahaya", result.dangerLevel, PAD + 32f, y, W - PAD - 32f, cardColor) + 14f
        y = infoRow(canvas, "Penyebab", result.cause.take(100), PAD + 32f, y, W - PAD - 32f, 0xFFE6F2EC) + 14f
        infoRow(canvas, "Penanganan", result.solution.take(100), PAD + 32f, y, W - PAD - 32f, 0xFFE6F2EC)

        // ── Footer ────────────────────────────────────────────────────────────
        val date = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id")).format(Date())
        drawText(canvas, "Scanned: $date  •  PlantScan App", PAD, 1320f, 22f, 0xFF9BB8A9)

        return bitmap
    }

    // ────────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ────────────────────────────────────────────────────────────────────────────

    private fun solidPaint(argb: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb.toInt() }
    private fun alphaPaint(argb: Int)  = Paint().apply { color = argb }

    private fun drawText(
        canvas  : Canvas,
        text    : String,
        x       : Float,
        y       : Float,
        size    : Float,
        color   : Long,
        bold    : Boolean = false,
        mono    : Boolean = false
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color    = color.toInt()
            this.textSize = size
            this.typeface = when {
                bold -> Typeface.DEFAULT_BOLD
                mono -> Typeface.MONOSPACE
                else -> Typeface.DEFAULT
            }
        }
        canvas.drawText(text, x, y, p)
    }

    private fun infoRow(
        canvas   : Canvas,
        label    : String,
        value    : String,
        x        : Float,
        y        : Float,
        maxRight : Float,
        valueColor: Long
    ): Float {
        val labelW   = 220f
        val labelP   = buildPaint(0xFF9BB8A9, 24f)
        val valueP   = buildPaint(valueColor, 24f)
        canvas.drawText(label, x, y, labelP)
        return wrapText(canvas, value, x + labelW, y, maxRight - x - labelW, valueP)
    }

    private fun buildPaint(color: Long, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color    = color.toInt()
        this.textSize = size
    }

    private fun wrapText(canvas: Canvas, text: String, x: Float, y: Float, maxW: Float, p: Paint): Float {
        val lineH = p.textSize * 1.5f
        val words = text.split(" ")
        var line  = ""
        var curY  = y
        for (word in words) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(test) > maxW && line.isNotEmpty()) {
                canvas.drawText(line, x, curY, p); curY += lineH; line = word
            } else line = test
        }
        if (line.isNotEmpty()) { canvas.drawText(line, x, curY, p); curY += lineH }
        return curY
    }
}