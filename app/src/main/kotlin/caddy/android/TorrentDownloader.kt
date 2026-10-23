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
        val session = SessionManager()
        session.start()
        try {
            session.download(resolved.magnet, destination.parentFile, TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val handle = awaitHandle(session, resolved)
            awaitMetadata(handle, resolved)

            val info = handle.torrentFile()
                ?: throw SourceUnavailable("torrent metadata missing", retryable = true)
            if (resolved.fileIndex !in 0 until info.numFiles()) {
                throw SourceUnavailable(
                    "torrent has ${info.numFiles()} files, no index ${resolved.fileIndex}", retryable = false)
            }

            // Everything ignored but the one file we want.
            val priorities = Array(info.numFiles()) { Priority.IGNORE }
            priorities[resolved.fileIndex] = Priority.TOP_PRIORITY
            handle.prioritizeFiles(priorities)
            handle.setSequentialRange(resolved.fileIndex)

            val wanted = handle.status().totalWanted()
            var last = 0L
            while (true) {
                val status = handle.status()
                val done = status.totalWantedDone()
                if (done != last) {
                    last = done
                    onProgress(done, wanted)
                }
                if (status.isSeeding || status.isFinished) break
                if (status.state() == org.libtorrent4j.TorrentStatus.State.DOWNLOADING &&
                    status.downloadPayloadRate() == 0 && done >= wanted && wanted > 0
                ) break
                if (status.errorCode().isError) {
                    throw SourceUnavailable("torrent failed: ${status.errorCode().getValue()}", retryable = true)
                }
                Thread.sleep(POLL_MS)
            }

            val file = File(handle.savePath(), handle.torrentFile()!!.files().filePath(resolved.fileIndex))
            if (!file.exists() || file.length() == 0L) {
                throw SourceUnavailable("torrent produced no file for ${resolved.filename}", retryable = true)
            }
            // The torrent writes into its own layout; the app wants the flat
            // ROMs/<system>/ name the catalog advertised.
            if (file.canonicalPath != destination.canonicalPath) {
                destination.parentFile?.mkdirs()
                file.copyTo(destination, overwrite = true)
                file.delete()
            }
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
            if (status.errorCode().isError) {
                throw SourceUnavailable("torrent error: ${status.errorCode().getValue()}", retryable = true)
            }
            Thread.sleep(POLL_MS)
        }
        throw SourceUnavailable("no metadata for ${resolved.filename} (no peers?)", retryable = true)
    }

    private companion object {
        const val POLL_MS = 500L
        const val METADATA_TIMEOUT_MS = 120_000L
    }
}
