package caddy.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import caddy.KIND_BASE

/**
 * The download queue, on disk.
 *
 * SQLite directly rather than Room: this is one table with six queries, and Room
 * would add a code generator plus a KSP version that has to track Kotlin. The
 * queue must survive process death and reboot, which is a file, not a feature.
 *
 * Resolved URLs are deliberately **not** stored. dlarchive links expire in 24h
 * and Minerva magnets are re-read from the page, so a row stores what it takes to
 * resolve again (source id + source ref + filename) and resolution happens at
 * download time.
 */
class DownloadQueue(context: Context) {
    data class Row(
        val id: Long,
        val system: String,
        val title: String,
        val filename: String,
        val kind: String,
        val sourceId: String,
        val sourceRef: String?,
        val torrentFileIndex: Int?,
        val sizeBytes: Long,
        val state: String,
        val bytesDone: Long,
        val attempts: Int,
        val error: String?,
    )

    private val helper = object : SQLiteOpenHelper(context, NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE downloads (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    system TEXT NOT NULL,
                    title TEXT NOT NULL,
                    filename TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    sourceId TEXT NOT NULL,
                    sourceRef TEXT,
                    torrentFileIndex INTEGER,
                    sizeBytes INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL,
                    bytesDone INTEGER NOT NULL DEFAULT 0,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    error TEXT,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX downloads_state ON downloads(state)")
        }

        /** The queue is a work list, not user data: rebuilding it is cheaper than
         *  a migration per column, and a lost row is re-downloadable. */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS downloads")
            onCreate(db)
        }
    }

    fun enqueue(
        system: String,
        title: String,
        filename: String,
        kind: String,
        sourceId: String,
        sourceRef: String?,
        sizeBytes: Long?,
        torrentFileIndex: Int? = null,
    ): Long {
        // Same file already queued or running: don't add a second copy.
        readable().query(
            "downloads", arrayOf("id"), "filename = ? AND state IN (?, ?, ?)",
            arrayOf(filename, STATE_QUEUED, STATE_RUNNING, STATE_UNZIPPING), null, null, null,
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        val values = ContentValues().apply {
            put("system", system)
            put("title", title)
            put("filename", filename)
            put("kind", kind)
            put("sourceId", sourceId)
            put("sourceRef", sourceRef)
            put("torrentFileIndex", torrentFileIndex)
            put("sizeBytes", sizeBytes ?: 0L)
            put("state", STATE_QUEUED)
            put("createdAt", System.currentTimeMillis())
        }
        return writable().insert("downloads", null, values)
    }

    /**
     * A URL the user captured in the WebView. There is no source to resolve
     * again, so the URL is stored and handed straight to the HTTP executor.
     */
    fun enqueueCaptured(system: String, title: String, filename: String, url: String): Long {
        val values = ContentValues().apply {
            put("system", system)
            put("title", title)
            put("filename", filename)
            put("kind", KIND_BASE)
            put("sourceId", CAPTURED_SOURCE)
            put("sourceRef", url)
            put("sizeBytes", 0L)
            put("state", STATE_QUEUED)
            put("createdAt", System.currentTimeMillis())
        }
        return writable().insert("downloads", null, values)
    }

    fun next(limit: Int): List<Row> = readable().query(
        "downloads", null, "state = ?", arrayOf(STATE_QUEUED), null, null, "createdAt", limit.toString(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRow()) } }

    fun all(): List<Row> = readable().query(
        "downloads", null, null, null, null, null, "createdAt DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRow()) } }

    fun pendingCount(): Int = readable().rawQuery(
        "SELECT COUNT(*) FROM downloads WHERE state IN (?, ?, ?)",
        arrayOf(STATE_QUEUED, STATE_RUNNING, STATE_UNZIPPING),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun updateState(id: Long, state: String, error: String? = null) {
        writable().update("downloads", ContentValues().apply {
            put("state", state)
            put("error", error)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun updateProgress(id: Long, done: Long, total: Long) {
        writable().update("downloads", ContentValues().apply {
            put("bytesDone", done)
            if (total > 0) put("sizeBytes", total)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun bumpAttempts(id: Long) {
        writable().execSQL("UPDATE downloads SET attempts = attempts + 1 WHERE id = ?", arrayOf(id))
    }

    /**
     * Anything still RUNNING at service start belongs to a process that died, so
     * it goes back in line. Rows this process is actively downloading are
     * excluded: requeueing a live row would start a second writer on the same
     * `.part` file.
     */
    fun requeueInterrupted(active: Set<Long> = emptySet()): Int {
        val clause = if (active.isEmpty()) "state IN (?, ?)" else
            "state IN (?, ?) AND id NOT IN (${active.joinToString(",")})"
        return writable().update(
            "downloads",
            ContentValues().apply { put("state", STATE_QUEUED) },
            clause,
            arrayOf(STATE_RUNNING, STATE_UNZIPPING),
        )
    }

    fun remove(id: Long) {
        writable().delete("downloads", "id = ?", arrayOf(id.toString()))
    }

    private fun readable() = helper.readableDatabase
    private fun writable() = helper.writableDatabase

    private fun android.database.Cursor.toRow() = Row(
        id = getLong(getColumnIndexOrThrow("id")),
        system = getString(getColumnIndexOrThrow("system")),
        title = getString(getColumnIndexOrThrow("title")),
        filename = getString(getColumnIndexOrThrow("filename")),
        kind = getString(getColumnIndexOrThrow("kind")),
        sourceId = getString(getColumnIndexOrThrow("sourceId")),
        sourceRef = getString(getColumnIndexOrThrow("sourceRef")),
        torrentFileIndex = getInt(getColumnIndexOrThrow("torrentFileIndex")).takeIf {
            !isNull(getColumnIndexOrThrow("torrentFileIndex"))
        },
        sizeBytes = getLong(getColumnIndexOrThrow("sizeBytes")),
        state = getString(getColumnIndexOrThrow("state")),
        bytesDone = getLong(getColumnIndexOrThrow("bytesDone")),
        attempts = getInt(getColumnIndexOrThrow("attempts")),
        error = getString(getColumnIndexOrThrow("error")),
    )

    companion object {
        private const val NAME = "downloads.db"
        private const val VERSION = 2

        const val STATE_QUEUED = "queued"
        const val STATE_RUNNING = "running"
        const val STATE_UNZIPPING = "unzipping"
        const val STATE_PAUSED = "paused"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"
        /** Marks a row whose URL came from the browser-capture WebView. */
        const val CAPTURED_SOURCE = "__capture"
    }
}
