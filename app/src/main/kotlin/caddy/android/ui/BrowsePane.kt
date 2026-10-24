package caddy.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import caddy.CatalogEntry
import caddy.android.DownloadQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class BrowsePane(
    context: Context,
    private val posterCache: PosterCache,
    private val scope: CoroutineScope,
) : LinearLayout(context) {

    // Center pane views
    private val systemTitleView: TextView
    private val systemMetaView: TextView
    private val focusBreadcrumbView: TextView
    private val searchInput: EditText

    private val filterChips = mutableListOf<TextView>()
    private val recyclerView: RecyclerView
    private val gameAdapter: GameListAdapter
    // Detail pane views
    private val posterBox: FrameLayout
    private val posterImageView: ImageView
    private val posterPlaceholder: TextView
    private val detailTitleView: TextView
    private val detailSubtitleView: TextView
    private val sizeRowView: TextView
    private val regionRowView: TextView
    private val hashRowView: TextView
    private val pathRowView: TextView
    private val synopsisView: TextView
    private val stateLineView: TextView
    private val primaryActionButton: TextView

    // State data
    private var systemId: String = ""
    private var sourceName: String = ""
    private var romsDir: File? = null
    private var allEntries: List<CatalogEntry> = emptyList()
    private var queueMap = mutableMapOf<String, DownloadQueue.Row>()
    private var filteredEntries: List<CatalogEntry> = emptyList()

    private var currentFilter: FilterMode = FilterMode.ALL
    private var currentSearchQuery: String = ""
    private var selectedIndex: Int = 0
    private var currentFocus: FocusTarget = FocusTarget(Zone.LIST, 0)

    private var posterJob: Job? = null

    // Callbacks
    var onDownloadRequested: ((entry: CatalogEntry) -> Unit)? = null
    var onPauseRequested: ((id: Long) -> Unit)? = null
    var onResumeRequested: ((id: Long) -> Unit)? = null
    var onGameSelected: ((index: Int, entry: CatalogEntry) -> Unit)? = null
    var onFilterChanged: ((mode: FilterMode) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(CaddyTheme.GROUND)

        // ==========================================
        // 1. Center Pane (Weight 1f)
        // ==========================================
        val centerPane = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }

        // Header Bar (46 dp)
        val headerHeight = CaddyTheme.dp(context, 46f)
        val headerBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(CaddyTheme.dp(context, 14f), 0, CaddyTheme.dp(context, 14f), 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, headerHeight)
            background = bottomBorder(CaddyTheme.LINE, CaddyTheme.GROUND)
        }

        val headerTextContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        systemTitleView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaddyTheme.INK)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

        systemMetaView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.MUTE)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

        headerTextContainer.addView(systemTitleView)
        headerTextContainer.addView(systemMetaView)
        headerBar.addView(headerTextContainer)

        focusBreadcrumbView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaddyTheme.INK)
            val padH = CaddyTheme.dp(context, 8f)
            val padV = CaddyTheme.dp(context, 3f)
            setPadding(padH, padV, padH, padV)
            isSingleLine = true
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = CaddyTheme.dp(context, 12f)
            }
            layoutParams = lp
            text = "GAMES"
        }
        headerBar.addView(focusBreadcrumbView)
        searchInput = EditText(context).apply {
            val widthPx = CaddyTheme.dp(context, 196f)
            val padH = CaddyTheme.dp(context, 8f)
            val padV = CaddyTheme.dp(context, 5f)
            layoutParams = LayoutParams(widthPx, LayoutParams.WRAP_CONTENT)
            setPadding(padH, padV, padH, padV)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(CaddyTheme.INK)
            setHintTextColor(0xFF8B908A.toInt())
            hint = "Search..."
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(CaddyTheme.FIELD)
                setStroke(1, 0xFF222526.toInt())
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString() ?: ""
                    if (query != currentSearchQuery) {
                        currentSearchQuery = query
                        applyFilterAndSearch()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)
                    clearFocus()
                    onSearchExit?.invoke()
                    true
                } else {
                    false
                }
            }
        }
        headerBar.addView(searchInput)
        centerPane.addView(headerBar)

        // Filter Row (23 dp)
        val filterRowHeight = CaddyTheme.dp(context, 23f)
        val filterRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(CaddyTheme.dp(context, 14f), 0, CaddyTheme.dp(context, 14f), 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, filterRowHeight)
            background = bottomBorder(CaddyTheme.LINE, CaddyTheme.PANEL)
        }

        FilterMode.entries.forEachIndexed { index, mode ->
            val chip = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setPadding(CaddyTheme.dp(context, 4f), CaddyTheme.dp(context, 1f), CaddyTheme.dp(context, 4f), CaddyTheme.dp(context, 1f))
                val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = CaddyTheme.dp(context, 10f)
                }
                layoutParams = lp
                text = mode.label
                setOnClickListener {
                    setFilter(mode)
                }
            }
            filterChips.add(chip)
            filterRow.addView(chip)
        }
        centerPane.addView(filterRow)

        // Column Header Row (22 dp)
        val colHeaderHeight = CaddyTheme.dp(context, 22f)
        val colHeader = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(CaddyTheme.dp(context, 14f), 0, CaddyTheme.dp(context, 14f), 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, colHeaderHeight)
            background = bottomBorder(CaddyTheme.LINE, CaddyTheme.PANEL)
        }

        val colTitle = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.MUTE)
            text = "TITLE"
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val colSize = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.MUTE)
            text = "SIZE"
            gravity = Gravity.END
            layoutParams = LayoutParams(CaddyTheme.dp(context, 65f), LayoutParams.WRAP_CONTENT)
        }
        val colState = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.MUTE)
            text = "STATE"
            gravity = Gravity.END
            layoutParams = LayoutParams(CaddyTheme.dp(context, 45f), LayoutParams.WRAP_CONTENT)
        }
        colHeader.addView(colTitle)
        colHeader.addView(colSize)
        colHeader.addView(colState)
        centerPane.addView(colHeader)

        // Game List (RecyclerView, fixed 34 dp rows)
        recyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            overScrollMode = OVER_SCROLL_NEVER
        }
        gameAdapter = GameListAdapter()
        recyclerView.adapter = gameAdapter
        centerPane.addView(recyclerView)

        addView(centerPane)

        // ==========================================
        // 2. Detail Pane (Fixed 300 dp)
        // ==========================================
        val detailWidthPx = CaddyTheme.dp(context, 300f)
        val detailPane = LinearLayout(context).apply {
            orientation = VERTICAL
            val pad14 = CaddyTheme.dp(context, 14f)
            setPadding(pad14, pad14, pad14, pad14)
            layoutParams = LayoutParams(detailWidthPx, LayoutParams.MATCH_PARENT)
            background = leftBorder(CaddyTheme.LINE, CaddyTheme.GROUND)
        }

        // Poster Box (capped at 140 dp width / 110 dp height, centered)
        val posterContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        posterBox = FrameLayout(context).apply {
            val maxWPx = CaddyTheme.dp(context, 140f)
            val maxHPx = CaddyTheme.dp(context, 110f)
            layoutParams = LayoutParams(maxWPx, maxHPx)
            background = GradientDrawable().apply {
                setColor(CaddyTheme.FIELD)
                setStroke(1, 0xFF222526.toInt())
            }
        }
        posterPlaceholder = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFF4D524C.toInt())
            text = "NO ART"
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        posterImageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        posterBox.addView(posterPlaceholder)
        posterBox.addView(posterImageView)
        posterContainer.addView(posterBox)
        detailPane.addView(posterContainer)

        // Title & Subtitle
        detailTitleView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaddyTheme.INK)
            setPadding(0, CaddyTheme.dp(context, 6f), 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        detailSubtitleView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.MUTE)
            setPadding(0, CaddyTheme.dp(context, 2f), 0, CaddyTheme.dp(context, 6f))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        detailPane.addView(detailTitleView)
        detailPane.addView(detailSubtitleView)

        detailPane.addView(createHairlineDivider())

        // Dotted-leader metadata rows
        val dottedContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, CaddyTheme.dp(context, 4f), 0, CaddyTheme.dp(context, 4f))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        sizeRowView = createDottedRow("size")
        regionRowView = createDottedRow("region")
        hashRowView = createDottedRow("hash")
        pathRowView = createDottedRow("path")
        dottedContainer.addView(sizeRowView)
        dottedContainer.addView(regionRowView)
        dottedContainer.addView(hashRowView)
        dottedContainer.addView(pathRowView)
        detailPane.addView(dottedContainer)

        detailPane.addView(createHairlineDivider())

        // Synopsis
        synopsisView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFF9AA09A.toInt())
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = CaddyTheme.dp(context, 6f)
                bottomMargin = CaddyTheme.dp(context, 6f)
            }
            layoutParams = lp
        }
        detailPane.addView(synopsisView)

        // Actions Container
        val actionsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        stateLineView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.MUTE)
            setPadding(0, 0, 0, CaddyTheme.dp(context, 6f))
        }
        actionsContainer.addView(stateLineView)

        primaryActionButton = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, CaddyTheme.dp(context, 8f), 0, CaddyTheme.dp(context, 8f))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            text = "DOWNLOAD"
            setOnClickListener {
                triggerPrimaryAction()
            }
        }
        actionsContainer.addView(primaryActionButton)
        detailPane.addView(actionsContainer)

        addView(detailPane)

        renderFilters()
        renderDetail()
    }

    // ==========================================
    // Public API
    // ==========================================

    fun setCatalog(
        systemId: String,
        sourceName: String,
        entries: List<CatalogEntry>,
        queueItems: List<DownloadQueue.Row>,
        romsDir: File,
    ) {
        this.systemId = systemId
        this.sourceName = sourceName
        this.romsDir = romsDir
        this.allEntries = entries

        queueMap.clear()
        for (item in queueItems) {
            queueMap[item.filename] = item
        }

        applyFilterAndSearch()
    }
    fun updateQueueItems(queueItems: List<DownloadQueue.Row>) {
        queueMap.clear()
        for (item in queueItems) {
            queueMap[item.filename] = item
        }
        gameAdapter.notifyDataSetChanged()
        updateDetailStatusAndAction()
    }


    fun setFilter(mode: FilterMode) {
        if (currentFilter != mode) {
            currentFilter = mode
            applyFilterAndSearch()
            onFilterChanged?.invoke(mode)
        }
    }

    fun setSearchQuery(query: String) {
        if (currentSearchQuery != query) {
            currentSearchQuery = query
            if (searchInput.text.toString() != query) {
                searchInput.setText(query)
            } else {
                applyFilterAndSearch()
            }
        }
    }

    fun getFilteredCount(): Int = filteredEntries.size
    var onSearchExit: (() -> Unit)? = null

    fun focusSearch() {
        searchInput.isFocusableInTouchMode = true
        searchInput.requestFocus()
        searchInput.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchInput, InputMethodManager.SHOW_FORCED)
        }
    }

    fun setSelectedGameIndex(index: Int) {
        if (filteredEntries.isEmpty()) {
            selectedIndex = 0
        } else {
            selectedIndex = index.coerceIn(0, filteredEntries.size - 1)
        }
        gameAdapter.notifyDataSetChanged()
        recyclerView.scrollToPosition(selectedIndex)
        renderDetail()
        onGameSelected?.let { cb ->
            filteredEntries.getOrNull(selectedIndex)?.let { cb(selectedIndex, it) }
        }
    }

    fun applyFocus(target: FocusTarget) {
        currentFocus = target
        renderHeaderFocus()
        renderFilters()
        renderDetailFocus()

        focusBreadcrumbView.text = when (target.zone) {
            Zone.RAIL -> "< SYSTEM RAIL"
            Zone.LIST -> "GAMES LIST"
            Zone.DETAIL -> "ACTION >"
            Zone.FILTERS -> "FILTERS"
            Zone.SEARCH -> "SEARCH"
            else -> ""
        }
        val breadcrumbColor = when (target.zone) {
            Zone.RAIL, Zone.FILTERS -> CaddyTheme.AMBER
            Zone.LIST -> CaddyTheme.INK
            Zone.DETAIL -> CaddyTheme.GREEN
            Zone.SEARCH -> CaddyTheme.HOLD
            else -> CaddyTheme.MUTE
        }
        focusBreadcrumbView.setTextColor(breadcrumbColor)
        focusBreadcrumbView.background = GradientDrawable().apply {
            setColor(0xFF141617.toInt())
            setStroke(1, breadcrumbColor)
        }

        gameAdapter.notifyDataSetChanged()
        if (target.zone == Zone.LIST) {
            if (selectedIndex != target.index && target.index in filteredEntries.indices) {
                setSelectedGameIndex(target.index)
            }
        }
    }

    fun triggerPrimaryAction() {
        val entry = filteredEntries.getOrNull(selectedIndex) ?: return
        val status = getEntryStatus(entry)
        val qItem = queueMap[entry.filename]

        when (status.stateCode) {
            "ok" -> onDownloadRequested?.invoke(entry)
            "run" -> qItem?.let { onPauseRequested?.invoke(it.id) }
            "que" -> qItem?.let { onPauseRequested?.invoke(it.id) }
            "pau" -> qItem?.let { onResumeRequested?.invoke(it.id) }
            "err" -> onDownloadRequested?.invoke(entry)
            else -> onDownloadRequested?.invoke(entry)
        }
    }

    // ==========================================
    // Internal Logic & Filtering
    // ==========================================

    private fun applyFilterAndSearch() {
        val query = currentSearchQuery.trim().lowercase()

        filteredEntries = allEntries.filter { entry ->
            val matchesQuery = query.isEmpty() || entry.title.lowercase().contains(query) || entry.filename.lowercase().contains(query)
            if (!matchesQuery) return@filter false

            val status = getEntryStatus(entry)
            when (currentFilter) {
                FilterMode.ALL -> true
                FilterMode.ON_DISK -> status.isOnDisk
                FilterMode.IN_QUEUE -> status.isInQueue
                FilterMode.FAILED -> status.isFailed
            }
        }

        selectedIndex = if (filteredEntries.isEmpty()) 0 else selectedIndex.coerceIn(0, filteredEntries.size - 1)

        renderHeader()
        renderFilters()
        gameAdapter.notifyDataSetChanged()
        renderDetail()
    }

    private fun renderHeader() {
        systemTitleView.text = systemId.uppercase()
        val countText = "${allEntries.size} titles"
        val srcText = if (sourceName.isNotEmpty()) " · $sourceName" else ""
        systemMetaView.text = "$countText$srcText"
    }

    private fun renderHeaderFocus() {
        if (currentFocus.zone == Zone.SEARCH) {
            searchInput.background = GradientDrawable().apply {
                setColor(CaddyTheme.FIELD)
                setStroke(1, CaddyTheme.INK)
            }
            searchInput.requestFocus()
        } else {
            searchInput.background = GradientDrawable().apply {
                setColor(CaddyTheme.FIELD)
                setStroke(1, 0xFF222526.toInt())
            }
            searchInput.clearFocus()
        }
    }

    private fun renderFilters() {
        val allCount = allEntries.size
        var diskCount = 0
        var queueCount = 0
        var failCount = 0

        for (e in allEntries) {
            val s = getEntryStatus(e)
            if (s.isOnDisk) diskCount++
            if (s.isInQueue) queueCount++
            if (s.isFailed) failCount++
        }

        val counts = listOf(allCount, diskCount, queueCount, failCount)

        FilterMode.entries.forEachIndexed { i, mode ->
            val chip = filterChips.getOrNull(i) ?: return@forEachIndexed
            val isCurrent = mode == currentFilter
            val isFocused = currentFocus.zone == Zone.FILTERS && currentFocus.index == i
            val count = counts[i]

            chip.text = "${mode.label} ($count)"

            if (isCurrent) {
                // Inverted pill background (#d7d9d6 ground, #08090a text)
                chip.setTextColor(CaddyTheme.GROUND)
                val bg = GradientDrawable().apply {
                    setColor(CaddyTheme.INK)
                    if (isFocused) {
                        setStroke(CaddyTheme.dp(context, 2f), CaddyTheme.AMBER)
                    }
                }
                chip.background = bg
            } else {
                chip.setTextColor(CaddyTheme.MUTE)
                if (isFocused) {
                    val bg = GradientDrawable().apply {
                        setColor(0xFF222526.toInt())
                        setStroke(1, CaddyTheme.INK)
                    }
                    chip.background = bg
                    chip.setTextColor(CaddyTheme.INK)
                } else {
                    chip.background = null
                }
            }
        }
    }

    private var rawActionLabel: String = "Download"

    private fun renderDetailFocus() {
        val isButtonFocused = currentFocus.zone == Zone.DETAIL
        if (isButtonFocused) {
            primaryActionButton.background = GradientDrawable().apply {
                setColor(CaddyTheme.INK)
                setStroke(CaddyTheme.dp(context, 3f), CaddyTheme.AMBER)
            }
            primaryActionButton.setTextColor(CaddyTheme.GROUND)
            primaryActionButton.text = "> $rawActionLabel <"
        } else {
            primaryActionButton.background = GradientDrawable().apply {
                setColor(0xFF141617.toInt())
                setStroke(1, CaddyTheme.LINE)
            }
            primaryActionButton.setTextColor(CaddyTheme.INK)
            primaryActionButton.text = rawActionLabel
        }
    }
    private fun renderDetail() {
        val entry = filteredEntries.getOrNull(selectedIndex)
        if (entry == null) {
            posterImageView.visibility = View.GONE
            posterPlaceholder.visibility = View.VISIBLE
            posterPlaceholder.text = "NO GAME"
            detailTitleView.text = "—"
            detailSubtitleView.text = ""
            setDottedRowText(sizeRowView, "size", "—")
            setDottedRowText(regionRowView, "region", "—")
            setDottedRowText(hashRowView, "hash/crc32", "—")
            setDottedRowText(pathRowView, "path", "—")
            synopsisView.text = "No game selected"
            stateLineView.text = ""
            primaryActionButton.text = "DOWNLOAD"
            primaryActionButton.isEnabled = false
            return
        }

        primaryActionButton.isEnabled = true
        detailTitleView.text = entry.title

        val tags = mutableListOf<String>()
        entry.region?.let { tags.add(it) }
        if (entry.revision > 0) tags.add("Rev ${entry.revision}")
        if (entry.junkTags.isNotEmpty()) tags.addAll(entry.junkTags)
        detailSubtitleView.text = if (tags.isNotEmpty()) tags.joinToString(" · ") else systemId.uppercase()

        val sizeFormatted = formatBytes(entry.sizeBytes)
        setDottedRowText(sizeRowView, "size", sizeFormatted)
        setDottedRowText(regionRowView, "region", entry.region ?: "—")

        // Hash / CRC32
        val crc = if (entry.filename.contains("(")) "" else ""
        setDottedRowText(hashRowView, "hash/crc32", if (crc.isNotEmpty()) crc else "—")

        val sysDir = romsDir?.absolutePath ?: "/ROMs/${systemId.uppercase()}"
        setDottedRowText(pathRowView, "path", "$sysDir/${entry.filename}")

        // Synopsis
        synopsisView.text = "${entry.title} for ${systemId.uppercase()}."

        updateDetailStatusAndAction()

        // Debounced poster fetch (110 ms)
        posterJob?.cancel()
        posterImageView.visibility = View.GONE
        posterPlaceholder.visibility = View.VISIBLE
        posterPlaceholder.text = "LOADING..."

        val filename = entry.filename
        val curSys = systemId
        val targetW = CaddyTheme.dp(context, 140f)
        val targetH = CaddyTheme.dp(context, 110f)

        posterJob = scope.launch {
            delay(110)
            val bitmap = posterCache.getPoster(curSys, filename, targetW, targetH)
            if (bitmap != null) {
                posterImageView.setImageBitmap(bitmap)
                posterImageView.visibility = View.VISIBLE
                posterPlaceholder.visibility = View.GONE
            } else {
                posterImageView.visibility = View.GONE
                posterPlaceholder.visibility = View.VISIBLE
                posterPlaceholder.text = "NO ART"
            }
        }
    }

    private fun updateDetailStatusAndAction() {
        val entry = filteredEntries.getOrNull(selectedIndex) ?: return
        val status = getEntryStatus(entry)
        val qItem = queueMap[entry.filename]

        when (status.stateCode) {
            "ok" -> {
                stateLineView.text = "On disk: verified"
                rawActionLabel = "Re-download"
            }
            "run" -> {
                val pct = if ((qItem?.sizeBytes ?: 0) > 0) ((qItem?.bytesDone ?: 0) * 100 / qItem!!.sizeBytes).toInt() else 0
                stateLineView.text = "Downloading · $pct%"
                rawActionLabel = "Pause"
            }
            "que" -> {
                stateLineView.text = "Queued: waiting for slot"
                rawActionLabel = "Pause"
            }
            "pau" -> {
                val pct = if ((qItem?.sizeBytes ?: 0) > 0) ((qItem?.bytesDone ?: 0) * 100 / qItem!!.sizeBytes).toInt() else 0
                stateLineView.text = "Paused · $pct%"
                rawActionLabel = "Resume"
            }
            "err" -> {
                stateLineView.text = "Failed: ${qItem?.error ?: "checksum or download error"}"
                rawActionLabel = "Retry"
            }
            else -> {
                stateLineView.text = "Ready to download"
                rawActionLabel = "Download"
            }
        }
        renderDetailFocus()
    }

    // ==========================================
    // Entry Status Resolution
    // ==========================================

    data class EntryStatus(
        val stateCode: String, // "ok", "run", "que", "pau", "err", ""
        val isOnDisk: Boolean,
        val isInQueue: Boolean,
        val isFailed: Boolean,
    )

    private fun getEntryStatus(entry: CatalogEntry): EntryStatus {
        val q = queueMap[entry.filename]
        if (q != null) {
            return when (q.state) {
                DownloadQueue.STATE_RUNNING -> EntryStatus("run", isOnDisk = false, isInQueue = true, isFailed = false)
                DownloadQueue.STATE_QUEUED -> EntryStatus("que", isOnDisk = false, isInQueue = true, isFailed = false)
                DownloadQueue.STATE_PAUSED -> EntryStatus("pau", isOnDisk = false, isInQueue = true, isFailed = false)
                DownloadQueue.STATE_FAILED -> EntryStatus("err", isOnDisk = false, isInQueue = false, isFailed = true)
                DownloadQueue.STATE_DONE -> EntryStatus("ok", isOnDisk = true, isInQueue = false, isFailed = false)
                else -> EntryStatus("", isOnDisk = false, isInQueue = false, isFailed = false)
            }
        }

        // Check local disk if romsDir is provided
        val diskFile = romsDir?.let { File(it, entry.filename) }
        val onDisk = diskFile?.exists() == true && diskFile.length() > 0
        return if (onDisk) {
            EntryStatus("ok", isOnDisk = true, isInQueue = false, isFailed = false)
        } else {
            EntryStatus("", isOnDisk = false, isInQueue = false, isFailed = false)
        }
    }

    private fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "—"
        if (bytes < 1024 * 1024) {
            return "%.1f KB".format(bytes / 1024f)
        }
        val mb = bytes / (1024f * 1024f)
        if (mb < 1024) {
            return "%.1f MB".format(mb)
        }
        return "%.2f GB".format(mb / 1024f)
    }

    // ==========================================
    // Helper Layout Utilities
    // ==========================================

    private fun createHairlineDivider(): View {
        return View(context).apply {
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = CaddyTheme.dp(context, 7f)
                bottomMargin = CaddyTheme.dp(context, 7f)
            }
            layoutParams = lp
            setBackgroundColor(CaddyTheme.LINE)
        }
    }

    private fun createDottedRow(key: String): TextView {
        return TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.INK)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = CaddyTheme.dp(context, 2f)
            }
            layoutParams = lp
            text = "$key .......... —"
        }
    }

    private fun setDottedRowText(textView: TextView, key: String, value: String) {
        val dots = "...................."
        val keyPart = key.padEnd(12, '.')
        textView.text = "$keyPart $value"
    }

    private fun bottomBorder(lineColor: Int, bgColor: Int): LayerDrawable {
        val bg = GradientDrawable().apply { setColor(bgColor) }
        val line = GradientDrawable().apply { setColor(lineColor) }
        return LayerDrawable(arrayOf(bg, line)).apply {
            setLayerInset(0, 0, 0, 0, 0)
            setLayerInset(1, 0, -1, 0, 0)
        }
    }

    private fun leftBorder(lineColor: Int, bgColor: Int): LayerDrawable {
        val bg = GradientDrawable().apply { setColor(bgColor) }
        val line = GradientDrawable().apply { setColor(lineColor) }
        return LayerDrawable(arrayOf(bg, line)).apply {
            setLayerInset(0, 0, 0, 0, 0)
            setLayerInset(1, 0, 0, -1, 0)
        }
    }

    // ==========================================
    // RecyclerView Adapter
    // ==========================================

    private inner class GameListAdapter : RecyclerView.Adapter<GameViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
            val rowHeightPx = CaddyTheme.dp(context, 34f)
            val container = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, rowHeightPx)
                setPadding(CaddyTheme.dp(context, 14f), 0, CaddyTheme.dp(context, 14f), 0)
            }

            val titleView = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }

            val sizeView = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                gravity = Gravity.END
                layoutParams = LayoutParams(CaddyTheme.dp(context, 65f), LayoutParams.WRAP_CONTENT)
            }

            val stateView = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                gravity = Gravity.END
                layoutParams = LayoutParams(CaddyTheme.dp(context, 45f), LayoutParams.WRAP_CONTENT)
            }

            container.addView(titleView)
            container.addView(sizeView)
            container.addView(stateView)

            return GameViewHolder(container, titleView, sizeView, stateView)
        }

        override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
            val entry = filteredEntries[position]
            val isSelected = position == selectedIndex
            val isCursorActive = isSelected && currentFocus.zone == Zone.LIST
            val isRestingSelection = isSelected && currentFocus.zone != Zone.LIST
            val status = getEntryStatus(entry)

            holder.titleView.text = entry.title
            holder.sizeView.text = formatBytes(entry.sizeBytes)
            holder.stateView.text = if (status.stateCode.isNotEmpty()) status.stateCode else "—"

            if (isCursorActive) {
                // ACTIVE CURSOR: full-contrast invert (ink ground, ground text)
                holder.container.setBackgroundColor(CaddyTheme.INK)
                holder.titleView.setTextColor(CaddyTheme.GROUND)
                holder.sizeView.setTextColor(0xFF4A4D48.toInt())

                val darkColor = when (status.stateCode) {
                    "run" -> 0xFF8A5F00.toInt()
                    "ok" -> 0xFF1F6B41.toInt()
                    "err" -> 0xFFA32A2A.toInt()
                    "pau" -> 0xFF2C5C8F.toInt()
                    else -> CaddyTheme.GROUND
                }
                holder.stateView.setTextColor(darkColor)
            } else if (isRestingSelection) {
                // RESTING SELECTION: subtle dark panel with 1px border so user knows it's the selected game without stealing cursor focus
                val bg = GradientDrawable().apply {
                    setColor(0xFF1E2124.toInt())
                    setStroke(1, 0xFF3E4347.toInt())
                }
                holder.container.background = bg
                holder.titleView.setTextColor(CaddyTheme.INK)
                holder.sizeView.setTextColor(CaddyTheme.MUTE)

                val stateColor = when (status.stateCode) {
                    "run" -> CaddyTheme.AMBER
                    "ok" -> CaddyTheme.GREEN
                    "err" -> CaddyTheme.RED
                    "pau" -> CaddyTheme.HOLD
                    "que" -> CaddyTheme.MUTE
                    else -> CaddyTheme.MUTE
                }
                holder.stateView.setTextColor(stateColor)
            } else {
                val bg = LayerDrawable(arrayOf(
                    GradientDrawable().apply { setColor(CaddyTheme.GROUND) },
                    GradientDrawable().apply { setColor(CaddyTheme.LINE_SOFT) }
                )).apply {
                    setLayerInset(0, 0, 0, 0, 0)
                    setLayerInset(1, 0, -1, 0, 0)
                }
                holder.container.background = bg
                holder.titleView.setTextColor(CaddyTheme.INK)
                holder.sizeView.setTextColor(CaddyTheme.MUTE)

                val stateColor = when (status.stateCode) {
                    "run" -> CaddyTheme.AMBER
                    "ok" -> CaddyTheme.GREEN
                    "err" -> CaddyTheme.RED
                    "pau" -> CaddyTheme.HOLD
                    "que" -> CaddyTheme.MUTE
                    else -> CaddyTheme.MUTE
                }
                holder.stateView.setTextColor(stateColor)
            }

            holder.container.setOnClickListener {
                setSelectedGameIndex(position)
            }
        }

        override fun getItemCount(): Int = filteredEntries.size
    }

    private class GameViewHolder(
        val container: LinearLayout,
        val titleView: TextView,
        val sizeView: TextView,
        val stateView: TextView,
    ) : RecyclerView.ViewHolder(container)
}
