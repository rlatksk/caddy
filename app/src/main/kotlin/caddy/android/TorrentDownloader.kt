package caddy.android

import caddy.ResolvedDownload
import caddy.SourceUnavailable
import okhttp3.OkHttpClient
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.TorrentFlags
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Selective torrent download, in process.
 *
 * A Minerva collection is one torrent holding thousands of files — the GBA set is
 * 3,688 files and 14.86 GB — so the only viable fetch is "give me file #N". File
 * priorities do that: everything is IGNORE except the requested index, which
 * means the client never asks peers for the other pieces.
 *
 * Sequential mode is on because a single file arriving in order is what makes
 * partial progress useful and the disk access sane on a handheld.
 */
class TorrentDownloader(private val http: OkHttpClient, private val context: android.content.Context) {

    fun fetch(
        resolved: ResolvedDownload.Torrent,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): File {
        destination.parentFile?.mkdirs()
        // The torrent writes the file at its final name inside the save path, so
        // it must not be the ROMs folder: a half-written ROM there looks valid to
        // ES-DE and fails at launch. Stage in a sibling temp dir and move once
        // the file is complete.
        val staging = File(destination.parentFile, STAGING_DIR).apply { mkdirs() }
        android.util.Log.i(TAG, "torrent start file=#${resolved.fileIndex} ${resolved.filename}")
        val session = SessionManager()
        session.start()
        android.util.Log.i(TAG, "session started")
        session.addListener(object : AlertListener {
            // Metadata failures are otherwise silent: the handle exists, peers
            // connect, and nothing is downloaded.
            // Only what matters for a stalled fetch; the full mask floods logcat
            // with session stats every second.
            override fun types(): IntArray = intArrayOf(
                AlertType.ADD_TORRENT.swig(),
                AlertType.METADATA_RECEIVED.swig(),
                AlertType.METADATA_FAILED.swig(),
                AlertType.TORRENT_ERROR.swig(),
                AlertType.TORRENT_FINISHED.swig(),
                AlertType.FILE_ERROR.swig(),
                AlertType.TRACKER_ERROR.swig(),
                AlertType.TRACKER_REPLY.swig(),
            )

            override fun alert(alert: Alert<*>) {
                android.util.Log.w(TAG, "alert ${alert.type()} ${alert.message()}")
            }
        })
        try {
            session.download(resolved.magnet, staging, TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val handle = awaitHandle(session, resolved)
            android.util.Log.i(TAG, "handle found")
            awaitMetadata(handle, resolved)
            android.util.Log.i(TAG, "metadata ok files=${handle.torrentFile()?.numFiles()}")

            val info = handle.torrentFile()
                ?: throw SourceUnavailable("torrent metadata missing", retryable = true)
            // The page's data-m is a row index, not the torrent's file index: the
            // two orders differ (index 2128 was "Minicraft" in the torrent). Match
            // on the filename the catalog showed, and keep the index only as a
            // fallback for a torrent that renamed the file.
            val files = info.files()
            val wantedIndex = (0 until files.numFiles()).firstOrNull { i ->
                files.filePath(i).substringAfterLast('/').equals(resolved.filename, ignoreCase = true)
            } ?: resolved.fileIndex.takeIf { it in 0 until files.numFiles() }
            ?: throw SourceUnavailable(
                "${resolved.filename} is not in this torrent (${files.numFiles()} files)", retryable = false)
            android.util.Log.i(TAG, "file #$wantedIndex = ${files.filePath(wantedIndex)}")

            // Everything ignored but the one file we want.
            val priorities = Array(files.numFiles()) { Priority.IGNORE }
            priorities[wantedIndex] = Priority.TOP_PRIORITY
            handle.prioritizeFiles(priorities)
            handle.setSequentialRange(wantedIndex)

            // The file's own size, not the torrent's: priorities were only just
            // applied, so status.totalWanted() still reports the whole collection.
            val wanted = files.fileSize(wantedIndex)
            android.util.Log.i(TAG, "wanting $wanted bytes of file #$wantedIndex")
            var last = 0L
            while (true) {
                val status = handle.status(true)
                val done = status.totalWantedDone()
                if (done != last) {
                    last = done
                    onProgress(done, wanted)
                }
                if (done >= wanted && wanted > 0) break
                if (status.isSeeding || status.isFinished) break
                if (status.errorCode().isError) {
                    throw SourceUnavailable("torrent failed: ${status.errorCode().getValue()}", retryable = true)
                }
                if (System.currentTimeMillis() % 5000 < POLL_MS) {
                    android.util.Log.i(TAG, "progress $done/$wanted peers=${status.numPeers()} rate=${status.downloadPayloadRate()}")
                }
                Thread.sleep(POLL_MS)
            }

            val file = File(handle.savePath(), files.filePath(wantedIndex))
            if (!file.exists() || file.length() != wanted) {
                throw SourceUnavailable(
                    "torrent produced ${file.length()} of $wanted bytes for ${resolved.filename}", retryable = true)
            }
            // The torrent writes into its own layout; the app wants the flat
            // ROMs/<system>/ name the catalog advertised.
            file.copyTo(destination, overwrite = true)
            staging.deleteRecursively()
            return destination
        } finally {
            runCatching { session.stop() }
        }
    }

    /** The magnet is added by info hash, so wait until the session knows it. */
    private fun awaitHandle(session: SessionManager, resolved: ResolvedDownload.Torrent): TorrentHandle {
        val hash = resolved.magnet
            .substringAfter("btih:", "")
            .substringBefore('&')
            .uppercase()
        if (hash.length != 40) throw SourceUnavailable("magnet has no infohash", retryable = false)
        val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            session.find(org.libtorrent4j.Sha1Hash.parseHex(hash))?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw SourceUnavailable("session never registered the torrent", retryable = true)
    }

    private fun awaitMetadata(handle: TorrentHandle, resolved: ResolvedDownload.Torrent) {
        val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val status = handle.status()
            if (status.hasMetadata()) return
            android.util.Log.i(TAG, "waiting metadata: peers=${status.numPeers()} state=${status.state()}")
            if (status.errorCode().isError) {
                throw SourceUnavailable("torrent error: ${status.errorCode().getValue()}", retryable = true)
            }
            Thread.sleep(POLL_MS)
        }
        throw SourceUnavailable("no metadata for ${resolved.filename} (no peers?)", retryable = true)
    }

    private companion object {
        const val TAG = "caddy-torrent"
        const val STAGING_DIR = ".caddy-torrent"
        const val POLL_MS = 500L
        const val METADATA_TIMEOUT_MS = 240_000L
    }
}
