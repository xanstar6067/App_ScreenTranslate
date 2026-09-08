package com.adam.app_screentranslate

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adam.app_screentranslate.ocr.OCRManager
import com.adam.app_screentranslate.model.MergeMode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrDeviceTest {
    @Test fun syntheticBaseline() = runBlocking {
        val bitmap = Bitmap.createBitmap(1080, 1500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(25, 29, 34))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=48f; typeface=Typeface.DEFAULT }
        val samples = listOf("Mission Complete", "New Game", "Telegram для Android", "Показаны результаты по запросу", "Мессенджер Telegram", "ゲームを続ける", "로그인", "Player 01")
        samples.forEachIndexed { i, text -> canvas.drawText(text, 50f, 100f+i*150, paint) }
        try {
            OCRManager(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).use { ocr ->
                val blocks = ocr.recognize(bitmap, "auto", MergeMode.NORMAL)
                println("SYNTHETIC OCR: " + blocks.joinToString(" | ") { "${it.originalText} [${it.detectedLanguage}]" })
            }
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    for (lang in listOf("rus", "rus+eng", "eng+rus")) {
        val tess = com.googlecode.tesseract.android.TessBaseAPI()
        try {
            tess.init(java.io.File(context.noBackupFilesDir, "ocr-v1").absolutePath, lang, com.googlecode.tesseract.android.TessBaseAPI.OEM_LSTM_ONLY)
            tess.pageSegMode = com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            tess.setImage(bitmap)
            println("SYNTHETIC TESS $lang: ${tess.utF8Text}")
        } finally { tess.recycle() }
    }
} finally { bitmap.recycle() }
    }
}

