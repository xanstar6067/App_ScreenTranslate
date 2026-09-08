package com.adam.app_screentranslate.ocr

import android.content.Context
import android.graphics.*
import com.adam.app_screentranslate.model.*
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/** Offline Russian recognizer. Only bundled model files are extracted, never screenshots. */
class CyrillicRecognizer(private val context: Context) {
    fun recognize(bitmap: Bitmap): List<ScreenTextBlock> {
        val root = File(context.noBackupFilesDir, "ocr-v1")
        synchronized(modelLock) {
            val data = File(root, "tessdata").apply { mkdirs() }
            for (name in listOf("rus", "eng")) {
                val dest = File(data, "$name.traineddata")
                if (!dest.exists()) {
                    val temporary = File(data, "$name.tmp")
                    context.assets.open("tessdata/$name.traineddata").use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(temporary.renameTo(dest)) { "Cannot initialize OCR model" }
                }
            }
        }
        val tess = TessBaseAPI()
        var prepared: Bitmap? = null
        try {
            check(tess.init(root.absolutePath, "rus+eng", TessBaseAPI.OEM_LSTM_ONLY)) { "Cannot load Russian OCR" }
            tess.setVariable("debug_file", "/dev/null")
            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            prepared = prepare(bitmap)
            tess.setImage(prepared)
            tess.utF8Text // Execute native recognition before accessing its iterator.
            val iterator = tess.resultIterator ?: return emptyList()
            val result = mutableListOf<ScreenTextBlock>()
            try {
                iterator.begin()
                do {
                    val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                    val text = TextNormalizer.normalize(iterator.getUTF8Text(level) ?: "")
                    val confidence = iterator.confidence(level) / 100f
                    val cyrillic = text.count { it in '\u0400'..'\u052F' }
                    // This engine complements ML Kit. Latin/CJK hypotheses remain with their own models.
                    if (cyrillic >= 2 && confidence >= .65f) {
                        val r = iterator.getBoundingBox(level)
                        val box = Box(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat())
                        result += ScreenTextBlock(0, text, box, listOf(OcrLine(text, box, confidence=confidence)),
                            detectedLanguage="ru", script=ScriptDetector.detect(text), confidence=confidence)
                    }
                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            } finally { iterator.delete() }
            return result
        } finally { tess.recycle(); prepared?.recycle() }
    }
    private fun prepare(bitmap: Bitmap): Bitmap {
        var sum = 0L; var n = 0
        for (y in 0 until bitmap.height step 64) for (x in 0 until bitmap.width step 64) {
            val p = bitmap.getPixel(x, y)
            sum += (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)); n++
        }
        val dark = sum.toFloat()/n.coerceAtLeast(1)/3 < 128
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        if (dark) matrix.postConcat(ColorMatrix(floatArrayOf(-1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f)))
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter=ColorMatrixColorFilter(matrix) })
        }
    }
    companion object { private val modelLock = Any() }
}

