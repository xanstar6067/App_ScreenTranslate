package com.adam.app_screentranslate.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.adam.app_screentranslate.model.GameOrigin
import com.adam.app_screentranslate.model.GameProfile
import com.adam.app_screentranslate.model.GlossaryEntry
import com.adam.app_screentranslate.model.TermKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Game profiles and their glossaries. This is the player's own knowledge about their games and
 * nothing else: no usage history, no recognized text. Like the rest of the app's data it stays out
 * of Android backup.
 */
class GameStore(context: Context) : SQLiteOpenHelper(context, "games.db", null, 1) {
    private val mutableGames = MutableStateFlow<List<GameProfile>>(emptyList())
    /** Every profile, the most recently translated first. */
    val games = mutableGames.asStateFlow()

    private val mutableTerms = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Glossary size per package, for the list. */
    val terms = mutableTerms.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE games(pkg TEXT PRIMARY KEY, label TEXT NOT NULL, custom_name TEXT NOT NULL,
            origin TEXT NOT NULL, enabled INTEGER NOT NULL, first_seen INTEGER NOT NULL, last_used INTEGER NOT NULL,
            source TEXT, target TEXT, prompt TEXT, notes TEXT NOT NULL)""")
        // NOCASE on the term: "Rapture" and "rapture" are one entry, not two that disagree.
        db.execSQL("""CREATE TABLE glossary(id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL,
            term TEXT NOT NULL COLLATE NOCASE, translation TEXT NOT NULL, kind TEXT NOT NULL,
            keep INTEGER NOT NULL, UNIQUE(pkg, term))""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    suspend fun refresh() = withContext(Dispatchers.IO) { reload() }

    /**
     * The game on screen right now. A known one only moves up the list; an unknown one gets an
     * automatic profile. The label is refreshed because a game can rename itself in an update.
     */
    suspend fun touch(pkg: String, label: String): GameProfile = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (read(pkg) == null) {
            writableDatabase.insertWithOnConflict("games", null,
                values(GameProfile(pkg, label, firstSeen = now, lastUsed = now)), SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            writableDatabase.update("games", ContentValues().apply {
                put("last_used", now)
                if (label.isNotBlank()) put("label", label)
            }, "pkg=?", arrayOf(pkg))
        }
        reload()
        read(pkg) ?: GameProfile(pkg, label, firstSeen = now, lastUsed = now)
    }

    /** False when the package already has a profile: a manual add never creates a duplicate. */
    suspend fun add(pkg: String, label: String): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val row = writableDatabase.insertWithOnConflict("games", null,
            values(GameProfile(pkg, label, origin = GameOrigin.MANUAL, firstSeen = now, lastUsed = 0)),
            SQLiteDatabase.CONFLICT_IGNORE)
        reload()
        row != -1L
    }

    suspend fun update(profile: GameProfile) = withContext(Dispatchers.IO) {
        writableDatabase.update("games", values(profile), "pkg=?", arrayOf(profile.packageName))
        reload()
    }

    suspend fun delete(pkg: String) = withContext(Dispatchers.IO) {
        writableDatabase.transaction {
            delete("glossary", "pkg=?", arrayOf(pkg))
            delete("games", "pkg=?", arrayOf(pkg))
        }
        reload()
    }

    suspend fun glossary(pkg: String): List<GlossaryEntry> = withContext(Dispatchers.IO) {
        readableDatabase.query("glossary", arrayOf("id", "term", "translation", "kind", "keep"),
            "pkg=?", arrayOf(pkg), null, null, "term COLLATE NOCASE").use { c ->
            buildList {
                while (c.moveToNext()) add(GlossaryEntry(c.getString(1), c.getString(2),
                    TermKind.entries.firstOrNull { it.name == c.getString(3) } ?: TermKind.TERM,
                    c.getInt(4) != 0, c.getLong(0)))
            }
        }
    }

    /** Adds a new entry or edits an existing one. False when another entry already has this term. */
    suspend fun save(pkg: String, entry: GlossaryEntry): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("pkg", pkg); put("term", entry.term.trim()); put("translation", entry.rendering.trim())
            put("kind", entry.kind.name); put("keep", if (entry.keep) 1 else 0)
        }
        val ok = try {
            if (entry.id == 0L) writableDatabase.insertOrThrow("glossary", null, values) != -1L
            else writableDatabase.update("glossary", values, "id=?", arrayOf(entry.id.toString())) > 0
        } catch (_: android.database.sqlite.SQLiteConstraintException) { false }
        reload()
        ok
    }

    /**
     * Several entries at once, as the AI fill offers them. An entry with an id replaces that entry —
     * the user chose to overwrite it; one without is added unless the term is already there.
     * Returns how many were written.
     */
    suspend fun saveAll(pkg: String, entries: List<GlossaryEntry>): Int = withContext(Dispatchers.IO) {
        var written = 0
        writableDatabase.transaction {
            for (entry in entries) {
                val values = ContentValues().apply {
                    put("pkg", pkg); put("term", entry.term.trim()); put("translation", entry.rendering.trim())
                    put("kind", entry.kind.name); put("keep", if (entry.keep) 1 else 0)
                }
                val ok = try {
                    if (entry.id == 0L) insertWithOnConflict("glossary", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
                    else update("glossary", values, "id=?", arrayOf(entry.id.toString())) > 0
                } catch (_: android.database.sqlite.SQLiteConstraintException) { false }
                if (ok) written++
            }
        }
        reload()
        written
    }

    suspend fun remove(entryId: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete("glossary", "id=?", arrayOf(entryId.toString()))
        reload()
    }

    private fun reload() {
        mutableGames.value = readableDatabase.query("games", null, null, null, null, null,
            "last_used DESC, first_seen DESC").use { c -> buildList { while (c.moveToNext()) add(profile(c)) } }
        mutableTerms.value = readableDatabase.rawQuery("SELECT pkg, COUNT(*) FROM glossary GROUP BY pkg", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }
    }

    private fun read(pkg: String): GameProfile? =
        readableDatabase.query("games", null, "pkg=?", arrayOf(pkg), null, null, null).use { c ->
            if (c.moveToFirst()) profile(c) else null
        }

    private fun profile(c: Cursor) = GameProfile(
        packageName = c.getString(c.getColumnIndexOrThrow("pkg")),
        label = c.getString(c.getColumnIndexOrThrow("label")),
        customName = c.getString(c.getColumnIndexOrThrow("custom_name")),
        origin = GameOrigin.entries.firstOrNull { it.name == c.getString(c.getColumnIndexOrThrow("origin")) } ?: GameOrigin.AUTO,
        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0,
        firstSeen = c.getLong(c.getColumnIndexOrThrow("first_seen")),
        lastUsed = c.getLong(c.getColumnIndexOrThrow("last_used")),
        source = c.optString("source"), target = c.optString("target"), prompt = c.optString("prompt"),
        notes = c.getString(c.getColumnIndexOrThrow("notes")))

    private fun Cursor.optString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun values(profile: GameProfile) = ContentValues().apply {
        put("pkg", profile.packageName); put("label", profile.label); put("custom_name", profile.customName)
        put("origin", profile.origin.name); put("enabled", if (profile.enabled) 1 else 0)
        put("first_seen", profile.firstSeen); put("last_used", profile.lastUsed)
        put("source", profile.source); put("target", profile.target); put("prompt", profile.prompt)
        put("notes", profile.notes)
    }
}
