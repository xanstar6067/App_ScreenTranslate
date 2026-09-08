package com.adam.app_screentranslate.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.ocr.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CacheStats(val entries: Long = 0, val bytes: Long = 0)
interface TranslationStore {
    suspend fun get(provider: String, request: TranslationRequest): TranslationResult?
    suspend fun put(request: TranslationRequest, result: TranslationResult)
}
class TranslationCache(context: Context) : SQLiteOpenHelper(context, "translations.db", null, 1), TranslationStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE translations(provider TEXT NOT NULL, source TEXT NOT NULL, target TEXT NOT NULL,
            original TEXT NOT NULL, translated TEXT NOT NULL, detected TEXT, timestamp INTEGER NOT NULL,
            PRIMARY KEY(provider, source, target, original))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    override suspend fun get(provider: String, request: TranslationRequest): TranslationResult? = withContext(Dispatchers.IO) {
        readableDatabase.query("translations", arrayOf("translated", "detected"),
            "provider=? AND source=? AND target=? AND original=?",
            arrayOf(provider, request.source, request.target, TextNormalizer.normalize(request.text)),
            null, null, null).use { c ->
            if (c.moveToFirst()) TranslationResult(request.id, c.getString(0), c.getString(1), provider) else null
        }
    }
    override suspend fun put(request: TranslationRequest, result: TranslationResult) = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict("translations", null, ContentValues().apply {
            put("provider", result.provider); put("source", request.source); put("target", request.target)
            put("original", TextNormalizer.normalize(request.text)); put("translated", result.text)
            put("detected", result.detectedLanguage); put("timestamp", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        // Bound persistent growth without recording a screen history.
        writableDatabase.execSQL("DELETE FROM translations WHERE rowid IN (SELECT rowid FROM translations ORDER BY timestamp DESC LIMIT -1 OFFSET 20000)")
    }
    suspend fun clear() = withContext(Dispatchers.IO) { writableDatabase.delete("translations", null, null); Unit }
    suspend fun stats(): CacheStats = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("""SELECT COUNT(*), COALESCE(SUM(LENGTH(CAST(original AS BLOB)) +
            LENGTH(CAST(translated AS BLOB)) + 64),0) FROM translations""", null).use {
            it.moveToFirst(); CacheStats(it.getLong(0), it.getLong(1))
        }
    }
}
