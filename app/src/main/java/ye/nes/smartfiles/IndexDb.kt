package ye.nes.smartfiles

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.text.Normalizer

/** All searchable information stays in the application's private on-device database. */
class IndexDb(context: Context) : SQLiteOpenHelper(context, "nes_index.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    data class Hit(val path: String, val location: String, val excerpt: String, val score: Int)
    data class Stats(val files: Long, val parts: Long, val pendingMedia: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE files (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, name TEXT NOT NULL, ext TEXT, size INTEGER, modified INTEGER, category TEXT, coverage TEXT NOT NULL, seen_run INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX files_name_idx ON files(name)")
        db.execSQL("CREATE INDEX files_seen_idx ON files(seen_run)")
        db.execSQL("CREATE TABLE chunks (id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, location TEXT, body TEXT NOT NULL)")
        db.execSQL("CREATE INDEX chunks_file_idx ON chunks(file_id)")
        // FTS4 is part of the Android SQLite platform. Avoid a platform-dependent FTS5 assumption.
        db.execSQL("CREATE VIRTUAL TABLE chunk_fts USING fts4(body, tokenize=unicode61)")
        db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY, title TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, conversation_id INTEGER NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX messages_conversation_idx ON messages(conversation_id, id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, now: Int) { /* Incremental migrations in v0.2 */ }

    fun getStats(): Stats = readableDatabase.rawQuery("SELECT (SELECT COUNT(*) FROM files), (SELECT COUNT(*) FROM chunks), (SELECT COUNT(*) FROM files WHERE coverage='metadata')", null).use { c ->
        c.moveToFirst(); Stats(c.getLong(0), c.getLong(1), c.getLong(2))
    }

    fun unchanged(file: File): Boolean = readableDatabase.rawQuery(
        "SELECT size, modified FROM files WHERE path=?", arrayOf(file.absolutePath)
    ).use { c -> c.moveToFirst() && c.getLong(0) == file.length() && c.getLong(1) == file.lastModified() }

    fun markSeen(path: String, run: Long) {
        writableDatabase.execSQL("UPDATE files SET seen_run=? WHERE path=?", arrayOf<Any?>(run, path))
    }

    fun replace(file: File, run: Long, extraction: Extractors.Extraction) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val path = file.absolutePath
            db.rawQuery("SELECT id FROM files WHERE path=?", arrayOf(path)).use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    removeChunks(db, id)
                    db.execSQL("DELETE FROM files WHERE id=?", arrayOf(id))
                }
            }
            val f = android.content.ContentValues().apply {
                put("path", path); put("name", file.name)
                put("ext", file.extension.lowercase()); put("size", file.length())
                put("modified", file.lastModified()); put("category", extraction.category)
                put("coverage", extraction.coverage); put("seen_run", run)
            }
            val id = db.insertOrThrow("files", null, f)
            // Text fragments maintain source linkage for grounded chat answers.
            for ((location, text) in splitText(extraction.text)) {
                val values = android.content.ContentValues().apply {
                    put("file_id", id); put("location", location); put("body", text)
                }
                val chunkId = db.insertOrThrow("chunks", null, values)
                db.execSQL("INSERT INTO chunk_fts(rowid, body) VALUES (?, ?)", arrayOf<Any?>(chunkId, text))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun splitText(text: String): List<Pair<String, String>> {
        if (text.isBlank()) return emptyList()
        val output = mutableListOf<Pair<String, String>>()
        var pos = 0
        while (pos < text.length && output.size < 250) {
            var end = (pos + 1300).coerceAtMost(text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOf('\n', end)
                if (boundary > pos + 700) end = boundary + 1
            }
            if (end <= pos) break
            output.add("حرف ${pos + 1}–$end" to text.substring(pos, end))
            pos = if (end == text.length) end else (end - 100).coerceAtLeast(pos + 1)
        }
        return output
    }

    private fun removeChunks(db: SQLiteDatabase, id: Long) {
        db.execSQL("DELETE FROM chunk_fts WHERE rowid IN (SELECT id FROM chunks WHERE file_id=?)", arrayOf(id))
        db.execSQL("DELETE FROM chunks WHERE file_id=?", arrayOf(id))
    }

    fun removeMissing(run: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = mutableListOf<Long>()
            db.rawQuery("SELECT id FROM files WHERE seen_run!=?", arrayOf(run.toString())).use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
            for (id in ids) {
                removeChunks(db, id)
                db.execSQL("DELETE FROM files WHERE id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun search(query: String, limit: Int = 10): List<Hit> {
        val stopWords = setOf("هل", "هناك", "وين", "اين", "أين", "الذي", "التي", "ما", "هو", "هي", "عن", "في", "من", "على", "أريد", "اريد", "ابحث", "لي", "لدي", "ملف", "ملفات", "موجود", "توجد", "يمكن", "لما", "كذا")
        val tokens = sanitize(query).split(' ').filter { it.length >= 2 && it !in stopWords }.distinct().take(7)
        if (tokens.isEmpty()) return emptyList()
        val hits = linkedMapOf<String, Hit>()
        // FTS4 prefix search on the content index; MATCH args are never concatenated unescaped.
        val fts = tokens.joinToString(" OR ") { "${it}*" }
        try {
            readableDatabase.rawQuery("""
                SELECT f.path, c.location, substr(c.body, 1, 520), f.name
                  FROM chunk_fts q JOIN chunks c ON c.id=q.rowid JOIN files f ON f.id=c.file_id
                 WHERE q.body MATCH ? LIMIT ?
            """.trimIndent(), arrayOf(fts, (limit * 8).toString())).use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0); val loc = c.getString(1) ?: ""
                    val excerpt = c.getString(2) ?: ""; val name = c.getString(3) ?: ""
                    val key = "$path:$loc"
                    val nameScore = tokens.count { sanitize(name).contains(it) } * 5
                    val contentScore = tokens.count { sanitize(excerpt).contains(it) }
                    hits[key] = Hit(path, loc, excerpt, nameScore + contentScore)
                }
            }
        } catch (_: Exception) { /* Fall back to metadata search for punctuation-heavy queries. */ }
        val pattern = "%${tokens.first().replace("%", "\\%").replace("_", "\\_")}%"
        readableDatabase.rawQuery(
            "SELECT path, name FROM files WHERE name LIKE ? ESCAPE '\\' OR path LIKE ? ESCAPE '\\' LIMIT ?",
            arrayOf(pattern, pattern, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0); val name = c.getString(1)
                hits.putIfAbsent("$path:name", Hit(path, "اسم الملف", name, 20))
            }
        }
        return hits.values.sortedByDescending { it.score }.take(limit)
    }

    private fun sanitize(t: String): String = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFKC)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[^\\p{L}\\p{N}_]+"), " ").trim()

    fun newConversation(): Long {
        val data = android.content.ContentValues().apply { put("title", "محادثة جديدة"); put("updated_at", System.currentTimeMillis()) }
        return writableDatabase.insertOrThrow("conversations", null, data)
    }
    fun saveMessage(conversation: Long, role: String, text: String) {
        val db = writableDatabase
        val data = android.content.ContentValues().apply {
            put("conversation_id", conversation); put("role", role); put("body", text); put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("messages", null, data)
        if (role == "user") db.execSQL("UPDATE conversations SET title=CASE WHEN title='محادثة جديدة' THEN ? ELSE title END, updated_at=? WHERE id=?",
            arrayOf<Any?>(text.take(55), System.currentTimeMillis(), conversation))
    }
    fun getConversations(): List<Pair<Long, String>> = readableDatabase.rawQuery(
        "SELECT id,title FROM conversations ORDER BY updated_at DESC LIMIT 100", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
    fun getMessages(conversation: Long): List<Pair<String, String>> = readableDatabase.rawQuery(
        "SELECT role,body FROM messages WHERE conversation_id=? ORDER BY id", arrayOf(conversation.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
}
