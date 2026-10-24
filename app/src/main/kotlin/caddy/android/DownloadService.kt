package caddy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import caddy.CatalogEntry
import caddy.HttpDownloader
import caddy.KIND_BASE
import caddy.ResolvedDownload
import caddy.SourceAdapter
import caddy.SourceConfig
import caddy.SourceUnavailable
import caddy.SystemRouter
import caddy.VerifyResult
import caddy.builtInAdapters
import caddy.parseIndex
import caddy.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the download queue as a foreground service, so a 15 GB game keeps going
 * when the screen locks or the activity is destroyed.
 *
 * Concurrency is two: these sources throttle, and a handheld on Wi-Fi gains
 * nothing from eight parallel streams while the radio cooks.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(MAX_PARALLEL)
    private val active = AtomicInteger(0)
    private val activeIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val pumping = java.util.concurrent.atomic.AtomicBoolean(false)

    private lateinit var queue: DownloadQueue
    private lateinit var store: RepoStore
    private lateinit var http: OkHttpClient
    private lateinit var adapters: Map<String, SourceAdapter>

    override fun onCreate() {
        super.onCreate()
        queue = DownloadQueue(this)
        store = RepoStore(this)
        http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        adapters = builtInAdapters(http)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting…", 0, 0))
        // A previous process died mid-download; those rows are RUNNING with no
        // owner. Live rows are spared, and a second start must not spawn a
        // second pump over the same rows.
        queue.requeueInterrupted(activeIds.toSet())
        if (pumping.compareAndSet(false, true)) {
            android.util.Log.i("caddy", "service start, pumping")
            scope.launch { pump() }
        } else {
            android.util.Log.i("caddy", "service start, already pumping")
        }
        return START_STICKY
    }

    private suspend fun pump() {
        while (true) {
            val batch = withContext(Dispatchers.IO) { queue.next(MAX_PARALLEL) }
            android.util.Log.i("caddy", "pump batch=${batch.map { it.id }}")
            if (batch.isEmpty()) break
            // Claim the rows before launching. Otherwise the coroutines below
            // have not incremented `active` yet when this loop asks for more
            // work, and `next()` hands back the same QUEUED rows.
            withContext(Dispatchers.IO) {
                batch.forEach { queue.updateState(it.id, DownloadQueue.STATE_RUNNING) }
            }
            batch.forEach { row ->
                // Counted here, not inside the coroutine: `run` must be assumed
                // live the moment it is scheduled, or the wait below passes
                // instantly and the service stops with a download in flight.
                active.incrementAndGet()
                scope.launch {
                    permits.withPermit { run(row) }
                }
            }
            // Wait for this batch before asking for more, so `next` cannot hand
            // out the same rows twice.
            while (active.get() > 0) kotlinx.coroutines.delay(200)
        }
        pumping.set(false)
        stopSelf()
    }

    private suspend fun run(row: DownloadQueue.Row) {
        activeIds += row.id
        android.util.Log.i("caddy", "run start #${row.id} ${row.filename} active=${active.get()}")
        withContext(Dispatchers.IO) { queue.updateState(row.id, DownloadQueue.STATE_RUNNING) }
        try {
            val plan = withContext(Dispatchers.IO) { resolve(row) }
            val directory = File(store.romsRoot, SystemRouter.relativePath(row.system))
            when (val resolved = plan.second) {
                is ResolvedDownload.Direct -> {
                    val file = HttpDownloader(http).download(
                        resolved, File(directory, resolved.filename),
                    ) { done, total ->
                        // Already on Dispatchers.IO: SQLite is cheap here, and the
                        // callback is not a suspend context.
                        queue.updateProgress(row.id, done, total)
                        notifyProgress(row, done, total)
                    }
                    val result = verify(file, resolved.hashes)
                    if (result is VerifyResult.Mismatch) {
                        file.delete()
                        throw SourceUnavailable(result.reason, retryable = false)
                    }
                    postProcessFile(row, file, directory)
                    withContext(Dispatchers.IO) { queue.updateState(row.id, DownloadQueue.STATE_DONE) }
                }
                is ResolvedDownload.Torrent -> {
                    TorrentDownloader(http, this).fetch(
                        resolved = resolved,
                        destination = File(directory, resolved.filename),
                        onProgress = { done, total ->
                            queue.updateProgress(row.id, done, total)
                            notifyProgress(row, done, total)
                        },
                    )
                    val file = File(directory, resolved.filename)
                    postProcessFile(row, file, directory)
                    withContext(Dispatchers.IO) { queue.updateState(row.id, DownloadQueue.STATE_DONE) }
                }
                is ResolvedDownload.NeedsCapture ->
                    throw SourceUnavailable("browser capture required: ${resolved.pageUrl}", retryable = false)
            }
        } catch (error: Exception) {
            android.util.Log.w("caddy", "run failed #${row.id}: ${error.message}", error)
            val retryable = (error as? SourceUnavailable)?.retryable ?: true
            withContext(Dispatchers.IO) {
                queue.bumpAttempts(row.id)
                val attempts = queue.all().firstOrNull { it.id == row.id }?.attempts ?: 1
                if (retryable && attempts < MAX_ATTEMPTS) {
                    // Backoff: 1, 5, 15 minutes. The row stays queued, so a later
                    // pump picks it up again.
                    queue.updateState(row.id, DownloadQueue.STATE_QUEUED, error.message)
                } else {
                    queue.updateState(row.id, DownloadQueue.STATE_FAILED, error.message)
                }
            }
        } finally {
            activeIds -= row.id
            active.decrementAndGet()
            android.util.Log.i("caddy", "run end #${row.id} active=${active.get()}")
        }
    }

    /**
     * Resolve through the row's own source first, then any other source in the
     * repo that serves the system. Source rot is the top risk in this app, so a
     * dead source must cost one attempt, not the download.
     */
    private suspend fun resolve(row: DownloadQueue.Row): Pair<SourceConfig?, ResolvedDownload> {
        // Captured rows already hold the final URL: the WebView did the work.
        if (row.sourceId == DownloadQueue.CAPTURED_SOURCE) {
            val url = row.sourceRef
                ?: throw SourceUnavailable("captured row has no URL", retryable = false)
            return null to ResolvedDownload.Direct(
                url = url,
                filename = row.filename,
                size = row.sizeBytes.takeIf { it > 0 },
                hashes = caddy.Hashes(),
            )
        }
        val index = parseIndex(store.indexJson ?: error("no repository"), RepoStore.KNOWN_ADAPTERS)
        val entry = CatalogEntry(
            system = row.system,
            title = row.title,
            filename = row.filename,
            sizeBytes = row.sizeBytes.takeIf { it > 0 },
            sourceRef = row.sourceRef,
            torrentFileIndex = row.torrentFileIndex,
            kind = row.kind.ifBlank { KIND_BASE },
        )
        val ordered = index.sources
            .filter { row.system in it.systems }
            .sortedByDescending { it.id == row.sourceId }
        var last: Exception? = null
        for (source in ordered) {
            val adapter = adapters[source.adapter] ?: continue
            try {
                return source to adapter.resolve(source, entry)
            } catch (error: Exception) {
                last = error
            }
        }
        throw last ?: SourceUnavailable("no source serves ${row.system}", retryable = false)
    }

    private fun notifyProgress(row: DownloadQueue.Row, done: Long, total: Long) {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val label = if (total > 0) {
            "%.1f / %.1f MB".format(done / 1e6, total / 1e6)
        } else {
            "%.1f MB".format(done / 1e6)
        }
        manager().notify(NOTIFICATION_ID, buildNotification("${row.title} — $label", done, total, pct))
    }

    private fun notifyUnzipping(row: DownloadQueue.Row) {
        manager().notify(NOTIFICATION_ID, buildNotification("${row.title} — Unzipping archive…", 0, 0, 100))
    }

    private fun postProcessFile(row: DownloadQueue.Row, file: File, directory: File) {
        if (!file.exists()) return
        if (file.name.endsWith(".zip", ignoreCase = true)) {
            queue.updateState(row.id, DownloadQueue.STATE_UNZIPPING)
            notifyUnzipping(row)
            try {
                unzip(file, directory)
                file.delete()
            } catch (e: Exception) {
                android.util.Log.e("caddy", "unzip failed for ${file.name}", e)
                throw e
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        val canonicalDest = targetDir.canonicalPath
        java.io.FileInputStream(zipFile).use { fis ->
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(fis)).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(32768)
                while (entry != null) {
                    val entryFile = File(targetDir, entry.name)
                    if (!entryFile.canonicalPath.startsWith(canonicalDest)) {
                        throw SecurityException("Zip entry path traversal detected: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        java.io.FileOutputStream(entryFile).use { fos ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun buildNotification(
        text: String,
        done: Long,
        total: Long,
        pct: Int = if (total > 0) (done * 100 / total).toInt() else 0,
    ): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle("Caddy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) builder.setProgress(100, pct, false)
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager().createNotificationChannel(channel)
    }

    private fun manager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 1
        private const val MAX_PARALLEL = 2
        private const val MAX_ATTEMPTS = 3

        /** Starts the service if it is not already running; safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
