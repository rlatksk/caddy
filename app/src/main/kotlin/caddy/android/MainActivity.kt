package caddy.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import caddy.CatalogEntry
import caddy.KIND_DLC
import caddy.KIND_UPDATE
import caddy.asVariant
import caddy.SourceAdapter
import caddy.SourceConfig
import caddy.builtInAdapters
import caddy.parseIndex
import caddy.android.ui.BrowsePane
import caddy.android.ui.CaddyTheme
import caddy.android.ui.Direction
import caddy.android.ui.DpadNavigator
import caddy.android.ui.FilterMode
import caddy.android.ui.FirstRunPane
import caddy.android.ui.FocusTarget
import caddy.android.ui.PosterCache
import caddy.android.ui.QueuePane
import caddy.android.ui.RailView
import caddy.android.ui.ScreenMode
import caddy.android.ui.SourcesPane
import caddy.android.ui.Zone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var store: RepoStore
    private lateinit var fetcher: RepoFetcher
    private lateinit var queue: DownloadQueue
    private lateinit var posterCache: PosterCache
    private lateinit var nav: DpadNavigator
    private lateinit var http: OkHttpClient

    // Layout
    private lateinit var rootContainer: LinearLayout
    private lateinit var railView: RailView
    private lateinit var paneContainer: FrameLayout

    // Panes
    private lateinit var browsePane: BrowsePane
    private lateinit var queuePane: QueuePane
    private lateinit var sourcesPane: SourcesPane
    private lateinit var firstRunPane: FirstRunPane

    // State
    private var currentSystem: String = ""
    private var currentCatalog: List<CatalogEntry> = emptyList()
    private var queueItems: List<DownloadQueue.Row> = emptyList()
    private var filter: FilterMode = FilterMode.ALL
    private var searchQuery: String = ""
    private var catalogLoadJob: Job? = null

    // 1-second queue tick
    private val handler = Handler(Looper.getMainLooper())
    private val queueTickRunnable = object : Runnable {
        override fun run() {
            refreshQueueState()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Services & stores
        store = RepoStore(this)
        fetcher = RepoFetcher(store)
        queue = DownloadQueue(this)
        http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        posterCache = PosterCache(this, http)
        nav = DpadNavigator()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(error) }
            previousHandler?.uncaughtException(thread, error)
        }

        buildUi()
        initState()
    }

    override fun onResume() {
        super.onResume()
        handler.post(queueTickRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(queueTickRunnable)
    }

    private fun buildUi() {
        // Outer container: horizontal LinearLayout, exact 832x444 dp app frame padding 12 dp
        val pad = CaddyTheme.dp(this, 12f)
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CaddyTheme.GROUND)
            setPadding(pad, pad, pad, pad)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // Left child: RailView (88 dp width)
        railView = RailView(this)
        rootContainer.addView(railView)

        // Right child: FrameLayout containing active pane
        paneContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        rootContainer.addView(paneContainer)

        setContentView(rootContainer)

        // Initialize child panes
        browsePane = BrowsePane(this, posterCache, lifecycleScope).apply {
            onDownloadRequested = { entry -> requestDownload(entry) }
            onPauseRequested = { id ->
                queue.updateState(id, DownloadQueue.STATE_PAUSED)
                refreshQueueState()
            }
            onResumeRequested = { id ->
                queue.updateState(id, DownloadQueue.STATE_QUEUED)
                DownloadService.start(this@MainActivity)
                refreshQueueState()
            }
            onFilterChanged = { mode ->
                filter = mode
                nav.filter = mode
            }
        }

        queuePane = QueuePane(this).apply {
            onResumeQueue = {
                DownloadService.start(this@MainActivity)
                refreshQueueState()
            }
            onClearFinished = {
                queue.all().filter { it.state == DownloadQueue.STATE_DONE || it.state == DownloadQueue.STATE_FAILED }
                    .forEach { queue.remove(it.id) }
                refreshQueueState()
            }
            onItemAction = { item ->
                when (item.state) {
                    DownloadQueue.STATE_RUNNING, DownloadQueue.STATE_QUEUED -> {
                        queue.updateState(item.id, DownloadQueue.STATE_PAUSED)
                    }
                    DownloadQueue.STATE_PAUSED -> {
                        queue.updateState(item.id, DownloadQueue.STATE_QUEUED)
                        DownloadService.start(this@MainActivity)
                    }
                    DownloadQueue.STATE_FAILED -> {
                        queue.updateState(item.id, DownloadQueue.STATE_QUEUED)
                        DownloadService.start(this@MainActivity)
                    }
                    DownloadQueue.STATE_DONE -> {}
                }
                refreshQueueState()
            }
        }

        sourcesPane = SourcesPane(this).apply {
            onFetchRepo = { url -> fetchRepository(url) }
            onApplyJson = { json -> applyRepositoryJson(json) }
            onRomsDirChanged = { dir ->
                store.romsRoot = dir
                loadCatalogForSystem(currentSystem)
            }
            onToggleSystem = { sys, _ ->
                store.toggleSystem(sys)
                updateRailSystems()
                updateSourcesPaneConfig()
            }
        }

        firstRunPane = FirstRunPane(this).apply {
            onFetchRepo = { url -> fetchRepository(url) }
            onApplyJson = { json -> applyRepositoryJson(json) }
        }

        // Rail callbacks
        railView.onSystemClickListener = { _, sys ->
            switchToSystem(sys)
        }
        railView.onQueueClickListener = {
            switchScreen(ScreenMode.QUEUE)
        }
        railView.onSourcesClickListener = {
            switchScreen(ScreenMode.SOURCES)
        }
    }

    private fun initState() {
        val enabled = store.enabledSystems
        val available = store.availableSystems()

        if (store.indexJson.isNullOrBlank()) {
            switchScreen(ScreenMode.FIRST_RUN)
        } else {
            currentSystem = enabled.firstOrNull() ?: available.firstOrNull() ?: ""
            updateRailSystems()
            if (currentSystem.isNotEmpty()) {
                switchScreen(ScreenMode.BROWSE)
                loadCatalogForSystem(currentSystem)
            } else {
                switchScreen(ScreenMode.SOURCES)
            }
        }
        refreshQueueState()
    }

    private fun updateRailSystems() {
        val systems = store.enabledSystems.toList().sorted()
        railView.setSystems(systems)
        val selectedIdx = systems.indexOf(currentSystem).coerceAtLeast(0)
        railView.setSelectedSystemIndex(selectedIdx)
    }

    private fun switchToSystem(sys: String) {
        currentSystem = sys
        val systems = store.enabledSystems.toList().sorted()
        val idx = systems.indexOf(sys).coerceAtLeast(0)
        railView.setSelectedSystemIndex(idx)
        switchScreen(ScreenMode.BROWSE)
        loadCatalogForSystem(sys)
    }

    private fun switchScreen(mode: ScreenMode) {
        nav.screen = mode
        railView.setScreen(mode)
        paneContainer.removeAllViews()

        when (mode) {
            ScreenMode.BROWSE -> {
                paneContainer.addView(browsePane)
                nav.focus = FocusTarget(Zone.LIST, 0)
                applyFocus()
            }
            ScreenMode.QUEUE -> {
                paneContainer.addView(queuePane)
                updateQueuePaneItems()
                nav.focus = FocusTarget(Zone.QLIST, 0)
                applyFocus()
            }
            ScreenMode.SOURCES -> {
                paneContainer.addView(sourcesPane)
                updateSourcesPaneConfig()
                nav.focus = FocusTarget(Zone.FIELD, 0)
                applyFocus()
            }
            ScreenMode.FIRST_RUN -> {
                paneContainer.addView(firstRunPane)
                nav.focus = FocusTarget(Zone.CENTER, 0)
                applyFocus()
            }
        }
    }

    private fun updateSourcesPaneConfig() {
        val allSys = store.availableSystems()
        val enabledSys = store.enabledSystems
        sourcesPane.setConfig(
            repoUrl = store.indexUrl ?: "",
            json = store.indexJson ?: "",
            romsDir = store.romsRoot,
            systems = allSys,
            enabled = enabledSys,
            metadataEnabled = true,
        )
    }

    private fun updateQueuePaneItems() {
        queueItems = queue.all()
        val isPaused = false // DownloadService handles paused states on rows
        queuePane.setQueueItems(queueItems, isPaused)
    }

    private fun refreshQueueState() {
        queueItems = queue.all()
        val hasActive = queueItems.any { it.state == DownloadQueue.STATE_QUEUED || it.state == DownloadQueue.STATE_RUNNING }
        railView.setQueueActive(hasActive)

        if (nav.screen == ScreenMode.QUEUE) {
            updateQueuePaneItems()
        }
        if (nav.screen == ScreenMode.BROWSE && currentSystem.isNotEmpty()) {
            browsePane.updateQueueItems(queueItems)
        }
    }

    private fun loadCatalogForSystem(sys: String) {
        catalogLoadJob?.cancel()
        catalogLoadJob = lifecycleScope.launch {
            val source = store.sources().firstOrNull { sys in it.systems } ?: return@launch
            val adapters = builtInAdapters(http)
            val adapter = adapters[source.adapter] ?: return@launch

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    adapter.list(source, sys, 0)
                }
            }

            result.fold(
                onSuccess = { entries ->
                    currentCatalog = entries
                    val romsDir = File(store.romsRoot, sys)
                    browsePane.setCatalog(
                        systemId = sys,
                        sourceName = source.name,
                        entries = currentCatalog,
                        queueItems = queueItems,
                        romsDir = romsDir,
                    )
                },
                onFailure = { err ->
                    Log.w("caddy", "failed loading catalog for $sys: ${err.message}")
                }
            )
        }
    }

    private fun fetchRepository(url: String) {
        if (url.isBlank()) return
        lifecycleScope.launch {
            fetcher.fetch(url).fold(
                onSuccess = {
                    val available = store.availableSystems()
                    if (store.enabledSystems.isEmpty() && available.isNotEmpty()) {
                        store.enabledSystems = available.toSet()
                    }
                    initState()
                    Toast.makeText(this@MainActivity, "Repository added", Toast.LENGTH_SHORT).show()
                },
                onFailure = { error ->
                    val msg = (error as? RepoException)?.error?.message() ?: "Failed: ${error.message}"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun applyRepositoryJson(json: String) {
        if (json.isBlank()) return
        val result = runCatching { parseIndex(json, RepoStore.KNOWN_ADAPTERS) }
        result.fold(
            onSuccess = { index ->
                if (index.sources.isEmpty()) {
                    Toast.makeText(this, "JSON has no recognized sources", Toast.LENGTH_LONG).show()
                    return
                }
                store.indexJson = json
                store.indexUrl = "pasted"
                store.lastFetch = System.currentTimeMillis()
                val available = store.availableSystems()
                if (store.enabledSystems.isEmpty() && available.isNotEmpty()) {
                    store.enabledSystems = available.toSet()
                }
                initState()
                Toast.makeText(this, "Pasted JSON applied", Toast.LENGTH_SHORT).show()
            },
            onFailure = { error ->
                Toast.makeText(this, "Invalid index: ${error.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun requestDownload(entry: CatalogEntry) {
        if (entry.variants.isNotEmpty()) {
            val options = buildList {
                val baseMb = entry.sizeBytes?.takeIf { it > 0 }?.let { " · %.1f MB".format(it / 1e6) } ?: ""
                add("Base game$baseMb" to entry)
                entry.variants.forEach { v ->
                    val label = when (v.kind) {
                        KIND_UPDATE -> "Update" + if (v.revision > 0) " v${v.revision}" else ""
                        else -> v.filename.substringBeforeLast('.').substringAfterLast('—').trim()
                    }
                    val size = v.sizeBytes?.takeIf { it > 0 }?.let { " · %.1f MB".format(it / 1e6) } ?: ""
                    add("$label$size" to entry.asVariant(v))
                }
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(entry.title)
                .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                    executeDownload(options[which].second)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            executeDownload(entry)
        }
    }

    private fun executeDownload(entry: CatalogEntry) {
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        val source = store.sources().firstOrNull { currentSystem in it.systems }
        if (source?.transport == caddy.Transport.BROWSER) {
            Toast.makeText(this, "${source.name} requires browser session", Toast.LENGTH_SHORT).show()
            return
        }
        val id = queue.enqueue(
            system = currentSystem,
            title = entry.title,
            filename = entry.filename,
            kind = entry.kind,
            sourceId = source?.id ?: "",
            sourceRef = entry.sourceRef,
            sizeBytes = entry.sizeBytes,
            torrentFileIndex = entry.torrentFileIndex,
        )
        DownloadService.start(this)
        refreshQueueState()
        Toast.makeText(this, "Queued ${entry.title} (#$id)", Toast.LENGTH_SHORT).show()
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        Toast.makeText(this, "Grant \"All files access\" for ROM downloads", Toast.LENGTH_LONG).show()
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        val systems = store.enabledSystems.toList().sorted()
        val listCount = when (nav.screen) {
            ScreenMode.BROWSE -> browsePane.getFilteredCount()
            ScreenMode.QUEUE -> queuePane.getItemCount()
            else -> 0
        }
        val gridCount = sourcesPane.getSystemCount()
        val railCount = systems.size

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                nav.move(Direction.UP, listCount = listCount, gridCols = 4, gridCount = gridCount, railCount = railCount)
                applyFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                nav.move(Direction.DOWN, listCount = listCount, gridCols = 4, gridCount = gridCount, railCount = railCount)
                applyFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                nav.move(Direction.LEFT, listCount = listCount, gridCols = 4, gridCount = gridCount, railCount = railCount)
                applyFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                nav.move(Direction.RIGHT, listCount = listCount, gridCols = 4, gridCount = gridCount, railCount = railCount)
                applyFocus()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> {
                handlePrimaryAction(systems)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                val prevScreen = nav.screen
                val exit = nav.handleBack(System.currentTimeMillis())
                if (exit) {
                    finish()
                } else {
                    if (prevScreen != nav.screen) {
                        switchScreen(nav.screen)
                    } else {
                        applyFocus()
                    }
                    if (nav.focus.zone == Zone.RAIL && nav.screen == ScreenMode.BROWSE) {
                        Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (nav.screen == ScreenMode.BROWSE) {
                    filter = nav.filter.prev()
                    nav.filter = filter
                    browsePane.setFilter(filter)
                    return true
                }
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (nav.screen == ScreenMode.BROWSE) {
                    filter = nav.filter.next()
                    nav.filter = filter
                    browsePane.setFilter(filter)
                    return true
                }
            }
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_X -> {
                switchScreen(ScreenMode.QUEUE)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_Y -> {
                if (nav.screen == ScreenMode.BROWSE) {
                    nav.focus = FocusTarget(Zone.SEARCH, 0)
                    applyFocus()
                    browsePane.focusSearch()
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun handlePrimaryAction(systems: List<String>) {
        when (nav.focus.zone) {
            Zone.RAIL -> {
                val sys = systems.getOrNull(nav.focus.index)
                if (sys != null) {
                    switchToSystem(sys)
                    nav.focus = FocusTarget(Zone.LIST, 0)
                    applyFocus()
                }
            }
            Zone.FILTERS -> {
                val mode = FilterMode.entries.getOrNull(nav.focus.index) ?: FilterMode.ALL
                filter = mode
                nav.filter = mode
                browsePane.setFilter(mode)
            }
            Zone.LIST -> {
                nav.focus = FocusTarget(Zone.DETAIL, nav.focus.index)
                applyFocus()
            }
            Zone.DETAIL -> {
                browsePane.triggerPrimaryAction()
            }
            Zone.QLIST, Zone.QHEAD -> {
                queuePane.triggerAction()
            }
            Zone.GRID, Zone.FIELD, Zone.META -> {
                sourcesPane.triggerAction()
            }
            Zone.CENTER -> {
                if (nav.screen == ScreenMode.FIRST_RUN) {
                    firstRunPane.submitRepo()
                }
            }
            else -> {}
        }
    }

    private fun applyFocus() {
        val target = nav.focus
        val isRail = target.zone == Zone.RAIL
        railView.setRailFocus(isRail, target.index)

        when (nav.screen) {
            ScreenMode.BROWSE -> browsePane.applyFocus(target)
            ScreenMode.QUEUE -> queuePane.applyFocus(target)
            ScreenMode.SOURCES -> sourcesPane.applyFocus(target)
            ScreenMode.FIRST_RUN -> {}
        }
    }

    private fun writeCrash(error: Throwable) {
        val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
        File(dir, "crash-${System.currentTimeMillis()}.log").writeText(
            "device ${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}\n" +
                error.stackTraceToString()
        )
    }
}
