package ye.nes.smartfiles

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.text.Normalizer

/** Persistent on-device search index. Version 2 is non-destructive: existing FTS and chats remain. */
class IndexDb(context: Context) : SQLiteOpenHelper(context, "nes_index.db", null, 2) {
    init { setWriteAheadLoggingEnabled(true) }
    data class Hit(val path: String, val location: String, val excerpt: String, val score: Int)
    data class Stats(val files: Long, val parts: Long, val pendingMedia: Long, val ready: Long = 0L,
                     val queued: Long = 0L, val failed: Long = 0L, val textReady: Long = 0L)
    data class Failure(val path: String, val reason: String)
    data class TypeCount(val category: String, val total: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE files (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, name TEXT NOT NULL, ext TEXT, size INTEGER, modified INTEGER, category TEXT, coverage TEXT NOT NULL, seen_run INTEGER NOT NULL, index_state TEXT NOT NULL DEFAULT 'ready', failure TEXT)")
        db.execSQL("CREATE INDEX files_name_idx ON files(name)")
        db.execSQL("CREATE INDEX files_seen_idx ON files(seen_run)")
        db.execSQL("CREATE INDEX files_state_idx ON files(index_state, seen_run)")
        db.execSQL("CREATE INDEX files_category_idx ON files(category)")
        db.execSQL("CREATE TABLE chunks (id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, location TEXT, body TEXT NOT NULL)")
        db.execSQL("CREATE INDEX chunks_file_idx ON chunks(file_id)")
        db.execSQL("CREATE VIRTUAL TABLE chunk_fts USING fts4(body, tokenize=unicode61)")
        db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY, title TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, conversation_id INTEGER NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX messages_conversation_idx ON messages(conversation_id, id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, now: Int) {
        if (old < 2) {
            db.execSQL("ALTER TABLE files ADD COLUMN index_state TEXT NOT NULL DEFAULT 'ready'")
            db.execSQL("ALTER TABLE files ADD COLUMN failure TEXT")
            db.execSQL("UPDATE files SET index_state=CASE WHEN coverage LIKE 'error:%' THEN 'failed' ELSE 'ready' END")
            db.execSQL("UPDATE files SET failure=coverage WHERE coverage LIKE 'error:%'")
            db.execSQL("CREATE INDEX IF NOT EXISTS files_state_idx ON files(index_state, seen_run)")
            db.execSQL("CREATE INDEX IF NOT EXISTS files_category_idx ON files(category)")
        }
    }
    fun getStats(): Stats = readableDatabase.rawQuery("""
        SELECT COUNT(*),
          (SELECT COUNT(*) FROM chunks),
          SUM(CASE WHEN coverage IN ('metadata','needs_ocr') THEN 1 ELSE 0 END),
          SUM(CASE WHEN index_state='ready' THEN 1 ELSE 0 END),
          SUM(CASE WHEN index_state IN ('queued','processing') THEN 1 ELSE 0 END),
          SUM(CASE WHEN index_state='failed' THEN 1 ELSE 0 END),
          SUM(CASE WHEN coverage IN ('text','partial') THEN 1 ELSE 0 END)
        FROM files
    """.trimIndent(), null).use { c ->
        c.moveToFirst()
        Stats(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5), c.getLong(6))
    }
    fun getTypes(): List<TypeCount> = readableDatabase.rawQuery("""
        SELECT CASE
          WHEN lower(ext) IN ('jpg','jpeg','png','webp','gif','bmp','heic','avif','tif','tiff','svg','ico') THEN 'image'
          WHEN lower(ext) IN ('mp4','mov','mkv','avi','webm','3gp','mpeg','mpg','flv','wmv','m4v') THEN 'video'
          WHEN lower(ext) IN ('mp3','wav','aac','m4a','ogg','opus','amr','flac','wma','aiff','mid','midi') THEN 'audio'
          WHEN lower(ext)='pdf' THEN 'pdf'
          WHEN lower(ext) IN ('doc','docx','odt') THEN 'document'
          WHEN lower(ext) IN ('xls','xlsx','ods') THEN 'spreadsheet'
          WHEN lower(ext) IN ('ppt','pptx','odp') THEN 'presentation'
          WHEN lower(ext) IN ('zip','rar','7z','tar','gz','tgz','bz2','xz','apk','jar','aab','iso') THEN 'archive'
          WHEN lower(ext) IN ('js','jsx','ts','tsx','php','py','kt','kts','java','c','h','cpp','hpp','cs','rs','go','rb','swift','dart','sql','sh','bash','ps1','bat','vue','svelte','gradle','json','yml','yaml','toml','xml','html','htm','css','scss') THEN 'code'
          WHEN lower(ext) IN ('txt','md','markdown','csv','tsv','log','ini','conf','cfg','properties','env','rtf') THEN 'text'
          ELSE 'other' END AS kind, COUNT(*) FROM files GROUP BY kind ORDER BY COUNT(*) DESC
    """.trimIndent(), null).use { c -> buildList { while (c.moveToNext()) add(TypeCount(c.getString(0), c.getLong(1))) } }
    fun getFailures(limit: Int = 50): List<Failure> = readableDatabase.rawQuery(
        "SELECT path,COALESCE(failure,'غير معروف') FROM files WHERE index_state='failed' ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(Failure(c.getString(0), c.getString(1))) } }

    /** Caller can wrap many calls in a single discovery transaction (major speed gain). */
    fun beginDiscoveryBatch() = writableDatabase.beginTransactionNonExclusive()
    fun finishDiscoveryBatch() { writableDatabase.setTransactionSuccessful(); writableDatabase.endTransaction() }
    fun abortDiscoveryBatch() { if (writableDatabase.inTransaction()) writableDatabase.endTransaction() }

    fun discover(file: File, run: Long, kind: String) {
        // UPSERT avoids a SELECT per file. Unchanged files keep their extracted text and index state.
        writableDatabase.execSQL("""
          INSERT INTO files(path,name,ext,size,modified,category,coverage,seen_run,index_state,failure)
            VALUES(?,?,?,?,?,?,'pending',?,'queued',NULL)
          ON CONFLICT(path) DO UPDATE SET
            name=excluded.name, ext=excluded.ext, category=excluded.category,
            index_state=CASE WHEN files.size=excluded.size AND files.modified=excluded.modified AND files.index_state!='failed'
                            THEN files.index_state ELSE 'queued' END,
            coverage=CASE WHEN files.size=excluded.size AND files.modified=excluded.modified
                          THEN files.coverage ELSE 'pending' END,
            failure=CASE WHEN files.size=excluded.size AND files.modified=excluded.modified
                         THEN files.failure ELSE NULL END,
            size=excluded.size,modified=excluded.modified,seen_run=excluded.seen_run
        """.trimIndent(), arrayOf<Any?>(file.absolutePath,file.name,file.extension.lowercase(),file.length(),file.lastModified(),kind,run))
    }
    fun pendingPaths(run: Long, limit: Int = 150): List<String> = readableDatabase.rawQuery("""
        SELECT path FROM files WHERE seen_run=? AND index_state IN ('queued','processing')
        ORDER BY CASE category
           WHEN 'text' THEN 0 WHEN 'code' THEN 1 WHEN 'pdf' THEN 2
           WHEN 'document' THEN 3 WHEN 'spreadsheet' THEN 4
           WHEN 'presentation' THEN 5 WHEN 'archive' THEN 6 ELSE 9 END, id LIMIT ?
    """.trimIndent(), arrayOf(run.toString(),limit.toString())).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }
    fun countQueued(run: Long): Long = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM files WHERE seen_run=? AND index_state IN ('queued','processing')",arrayOf(run.toString())
    ).use { c -> c.moveToFirst(); c.getLong(0) }

    fun replace(file: File, run: Long, extraction: Extractors.Extraction) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val path = file.absolutePath
            var id = -1L
            db.rawQuery("SELECT id FROM files WHERE path=?",arrayOf(path)).use { c -> if (c.moveToFirst()) id=c.getLong(0) }
            if (id >= 0) removeChunks(db,id)
            val state = if (extraction.coverage.startsWith("error:")) "failed" else "ready"
            val cv = ContentValues().apply {
                put("path", path); put("name",file.name); put("ext",file.extension.lowercase())
                put("size",file.length()); put("modified",file.lastModified())
                put("category",FileClassifier.category(file)); put("coverage",extraction.coverage)
                put("seen_run",run); put("index_state",state)
                if (state == "failed") put("failure",extraction.coverage) else putNull("failure")
            }
            if (id >= 0) db.update("files",cv,"id=?",arrayOf(id.toString()))
            else id = db.insertOrThrow("files",null,cv)
            if (state != "failed") for ((location,body) in splitText(extraction.text)) {
                val values = ContentValues().apply { put("file_id",id);put("location",location);put("body",body) }
                val chunkId=db.insertOrThrow("chunks",null,values)
                db.execSQL("INSERT INTO chunk_fts(rowid,body) VALUES(?,?)",arrayOf<Any?>(chunkId,body))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun markFailed(path: String, message: String) {
        writableDatabase.execSQL("UPDATE files SET index_state='failed',failure=?,coverage='error:exception' WHERE path=?",
            arrayOf<Any?>(message.take(500),path))
    }
    private fun splitText(text: String): List<Pair<String,String>> {
        if (text.isBlank()) return emptyList()
        val out=mutableListOf<Pair<String,String>>()
        var pos=0
        while(pos<text.length && out.size<250) {
            var end=(pos+1300).coerceAtMost(text.length)
            if(end<text.length) { val b=text.lastIndexOf('\n',end);if(b>pos+700)end=b+1 }
            if(end<=pos) break
            out.add("حرف ${pos+1}–$end" to text.substring(pos,end))
            pos=if(end==text.length)end else (end-100).coerceAtLeast(pos+1)
        }
        return out
    }
    private fun removeChunks(db: SQLiteDatabase, id: Long) {
        db.execSQL("DELETE FROM chunk_fts WHERE rowid IN (SELECT id FROM chunks WHERE file_id=?)",arrayOf<Any?>(id))
        db.execSQL("DELETE FROM chunks WHERE file_id=?",arrayOf<Any?>(id))
    }
    fun removeMissing(run: Long) {
        val db=writableDatabase;db.beginTransactionNonExclusive()
        try {
            val ids=mutableListOf<Long>()
            db.rawQuery("SELECT id FROM files WHERE seen_run!=?",arrayOf(run.toString())).use{c->while(c.moveToNext()) ids.add(c.getLong(0))}
            ids.forEach { id -> removeChunks(db,id); db.execSQL("DELETE FROM files WHERE id=?",arrayOf<Any?>(id)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun search(query: String, limit: Int=10): List<Hit> {
        val stop=setOf("هل","هناك","وين","اين","أين","الذي","التي","ما","هو","هي","عن","في","من","على","أريد","اريد","ابحث","لي","لدي","ملف","ملفات","موجود","توجد","يمكن","كذا")
        val tokens=sanitize(query).split(' ').filter{it.length>=2 && it !in stop}.distinct().take(7)
        if(tokens.isEmpty())return emptyList()
        val hits=linkedMapOf<String,Hit>()
        val fts=tokens.joinToString(" OR "){"${it}*"}
        try {
            readableDatabase.rawQuery("""
                SELECT f.path,c.location,substr(c.body,1,520),f.name
                FROM chunk_fts q JOIN chunks c ON c.id=q.rowid JOIN files f ON f.id=c.file_id
                WHERE q.body MATCH ? LIMIT ?
            """.trimIndent(),arrayOf(fts,(limit*8).toString())).use{c->while(c.moveToNext()){
                val path=c.getString(0);val loc=c.getString(1)?:"";val excerpt=c.getString(2)?:"";val name=c.getString(3)?:""
                val score=tokens.count{sanitize(name).contains(it)}*5+tokens.count{sanitize(excerpt).contains(it)}
                hits["$path:$loc"]=Hit(path,loc,excerpt,score)
            }}
        } catch (_: Exception) { }
        val pattern="%${tokens.first().replace("%","\\%").replace("_","\\_")}%"
        readableDatabase.rawQuery("SELECT path,name FROM files WHERE name LIKE ? ESCAPE '\\' OR path LIKE ? ESCAPE '\\' LIMIT ?",
            arrayOf(pattern,pattern,limit.toString())).use{c->while(c.moveToNext()){
                val path=c.getString(0);val name=c.getString(1)
                hits.putIfAbsent("$path:name",Hit(path,"اسم الملف",name,20))
            }}
        return hits.values.sortedByDescending{it.score}.take(limit)
    }
    private fun sanitize(s:String):String=Normalizer.normalize(s.lowercase(),Normalizer.Form.NFKC)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"),"")
        .replace(Regex("[^\\p{L}\\p{N}_]+")," ").trim()
    fun newConversation():Long {
        val data=ContentValues().apply{put("title","محادثة جديدة");put("updated_at",System.currentTimeMillis())}
        return writableDatabase.insertOrThrow("conversations",null,data)
    }
    fun saveMessage(conversation:Long,role:String,text:String){
        val db=writableDatabase
        db.insertOrThrow("messages",null,ContentValues().apply{
            put("conversation_id",conversation);put("role",role);put("body",text);put("created_at",System.currentTimeMillis())
        })
        if(role=="user")db.execSQL("UPDATE conversations SET title=CASE WHEN title='محادثة جديدة' THEN ? ELSE title END,updated_at=? WHERE id=?",
            arrayOf<Any?>(text.take(55),System.currentTimeMillis(),conversation))
    }
    fun getConversations():List<Pair<Long,String>> =readableDatabase.rawQuery(
        "SELECT id,title FROM conversations ORDER BY updated_at DESC LIMIT 100",null
    ).use{c->buildList{while(c.moveToNext())add(c.getLong(0) to c.getString(1))}}
    fun getMessages(conversation:Long):List<Pair<String,String>> =readableDatabase.rawQuery(
        "SELECT role,body FROM messages WHERE conversation_id=? ORDER BY id",arrayOf(conversation.toString())
    ).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
}
