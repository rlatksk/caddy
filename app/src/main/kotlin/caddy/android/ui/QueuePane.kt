package caddy.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import caddy.android.DownloadQueue
import caddy.android.DownloadQueueEntry

class QueuePane(context: Context) : LinearLayout(context) {

    // Header views
    private val titleView: TextView
    private val summaryView: TextView
    private val resumeButton: TextView
    private val clearButton: TextView

    // List
    private val recyclerView: RecyclerView
    private val queueAdapter: QueueAdapter

    // State
    private var items: List<DownloadQueueEntry> = emptyList()
    private var isQueuePaused: Boolean = false
    private var currentFocus: FocusTarget = FocusTarget(Zone.QLIST, 0)

    // Callbacks
    var onResumeQueue: (() -> Unit)? = null
    var onClearFinished: (() -> Unit)? = null
    var onItemAction: ((item: DownloadQueueEntry) -> Unit)? = null

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(CaddyTheme.GROUND)

        // 1. Header Bar (46 dp)
        val headerBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, CaddyTheme.dp(context, 46f))
            layoutParams = lp
            setPadding(CaddyTheme.dp(context, 16f), 0, CaddyTheme.dp(context, 16f), 0)
            setBackgroundColor(CaddyTheme.PANEL)
        }

        // Left title & summary
        val leftBox = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }

        titleView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(CaddyTheme.INK)
            text = "DOWNLOADS / QUEUE"
            isSingleLine = true
        }
        leftBox.addView(titleView)

        summaryView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.MUTE)
            setPadding(CaddyTheme.dp(context, 10f), 0, 0, 0)
            isSingleLine = true
        }
        leftBox.addView(summaryView)
        headerBar.addView(leftBox)

        // Right action buttons
        val actionsBox = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }

        resumeButton = createHeaderButton("[PAUSE QUEUE]")
        resumeButton.setOnClickListener { onResumeQueue?.invoke() }
        actionsBox.addView(resumeButton)

        clearButton = createHeaderButton("[CLEAR FINISHED]")
        val clearLp = clearButton.layoutParams as LayoutParams
        clearLp.leftMargin = CaddyTheme.dp(context, 8f)
        clearButton.layoutParams = clearLp
        clearButton.setOnClickListener { onClearFinished?.invoke() }
        actionsBox.addView(clearButton)

        headerBar.addView(actionsBox)
        addView(headerBar)

        // 1 px bottom border line #1c1e1f
        val borderLine = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(CaddyTheme.LINE)
        }
        addView(borderLine)

        // 2. Queue List (RecyclerView)
        recyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(context)
            setBackgroundColor(CaddyTheme.GROUND)
        }
        queueAdapter = QueueAdapter()
        recyclerView.adapter = queueAdapter
        addView(recyclerView)

        renderHeader()
        renderHeaderFocus()
    }

    private fun createHeaderButton(label: String): TextView {
        return TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            text = label
            isSingleLine = true
            gravity = Gravity.CENTER
            setPadding(
                CaddyTheme.dp(context, 10f),
                CaddyTheme.dp(context, 5f),
                CaddyTheme.dp(context, 10f),
                CaddyTheme.dp(context, 5f)
            )
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, CaddyTheme.LINE)
            }
            setTextColor(CaddyTheme.INK)
        }
    }

    fun setQueueItems(newItems: List<DownloadQueueEntry>, paused: Boolean) {
        items = newItems
        isQueuePaused = paused
        renderHeader()
        queueAdapter.notifyDataSetChanged()
    }

    fun getItemCount(): Int = items.size
    fun applyFocus(target: FocusTarget) {
        currentFocus = target
        renderHeaderFocus()
        queueAdapter.notifyDataSetChanged()
        if (target.zone == Zone.QLIST && items.isNotEmpty()) {
            val validIndex = target.index.coerceIn(0, items.size - 1)
            recyclerView.scrollToPosition(validIndex)
        }
    }

    fun triggerAction(): Boolean {
        if (currentFocus.zone == Zone.QHEAD) {
            if (currentFocus.index == 0) {
                onResumeQueue?.invoke()
                return true
            } else if (currentFocus.index == 1) {
                onClearFinished?.invoke()
                return true
            }
        } else if (currentFocus.zone == Zone.QLIST) {
            val item = items.getOrNull(currentFocus.index)
            if (item != null) {
                onItemAction?.invoke(item)
                return true
            }
        }
        return false
    }

    private fun renderHeader() {
        val activeCount = items.count { it.state == DownloadQueue.STATE_RUNNING }
        val queuedCount = items.count { it.state == DownloadQueue.STATE_QUEUED }
        val waitingOrOther = items.size - (activeCount + queuedCount)
        summaryView.text = if (items.isEmpty()) {
            "empty"
        } else {
            "$activeCount active, $queuedCount queued" + if (waitingOrOther > 0) ", $waitingOrOther finished/paused" else ""
        }

        resumeButton.text = if (isQueuePaused) "[RESUME QUEUE]" else "[PAUSE QUEUE]"
    }

    private fun renderHeaderFocus() {
        if (currentFocus.zone == Zone.QHEAD) {
            if (currentFocus.index == 0) {
                setButtonFocused(resumeButton, true)
                setButtonFocused(clearButton, false)
            } else {
                setButtonFocused(resumeButton, false)
                setButtonFocused(clearButton, true)
            }
        } else {
            setButtonFocused(resumeButton, false)
            setButtonFocused(clearButton, false)
        }
    }

    private fun setButtonFocused(view: TextView, focused: Boolean) {
        if (focused) {
            view.background = GradientDrawable().apply {
                setColor(CaddyTheme.INK)
                setStroke(1, CaddyTheme.INK)
            }
            view.setTextColor(CaddyTheme.GROUND)
        } else {
            view.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, CaddyTheme.LINE)
            }
            view.setTextColor(CaddyTheme.INK)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0.0 MB"
        val mb = bytes / (1024f * 1024f)
        if (mb < 1024) {
            return "%.1f MB".format(mb)
        }
        return "%.2f GB".format(mb / 1024f)
    }

    private fun buildProgressBar(bytesDone: Long, sizeBytes: Long): String {
        val totalBlocks = 10
        val fraction = if (sizeBytes > 0) {
            (bytesDone.toDouble() / sizeBytes.toDouble()).coerceIn(0.0, 1.0)
        } else 0.0
        val filledBlocks = (fraction * totalBlocks).toInt().coerceIn(0, totalBlocks)
        val percent = (fraction * 100).toInt()

        val filledChar = '▮'
        val unfilledChar = '▯'
        val bar = buildString {
            for (i in 0 until filledBlocks) append(filledChar)
            for (i in filledBlocks until totalBlocks) append(unfilledChar)
            append(' ')
            append(percent)
            append('%')
        }
        return "$bar (${formatBytes(bytesDone)} / ${formatBytes(sizeBytes)})"
    }

    private inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

        inner class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val root: LinearLayout = itemView as LinearLayout
            val titleText: TextView = itemView.findViewById(101)
            val progressText: TextView = itemView.findViewById(102)
            val statusBadge: TextView = itemView.findViewById(103)
            val actionButton: TextView = itemView.findViewById(104)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
            val rowHeight = CaddyTheme.dp(context, 38f)
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    rowHeight
                )
                setPadding(
                    CaddyTheme.dp(context, 16f),
                    0,
                    CaddyTheme.dp(context, 16f),
                    0
                )
            }

            // Title (monospace 12 dp)
            val titleView = TextView(context).apply {
                id = 101
                typeface = Typeface.MONOSPACE
                textSize = 12f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 3.2f)
            }
            row.addView(titleView)

            // Progress bar
            val progressView = TextView(context).apply {
                id = 102
                typeface = Typeface.MONOSPACE
                textSize = 11f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 3.8f)
                setPadding(CaddyTheme.dp(context, 8f), 0, CaddyTheme.dp(context, 8f), 0)
            }
            row.addView(progressView)

            // Status badge
            val badgeView = TextView(context).apply {
                id = 103
                typeface = Typeface.MONOSPACE
                textSize = 11f
                isSingleLine = true
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(CaddyTheme.dp(context, 90f), LayoutParams.WRAP_CONTENT)
            }
            row.addView(badgeView)

            // Row action button/indicator
            val actionView = TextView(context).apply {
                id = 104
                typeface = Typeface.MONOSPACE
                textSize = 11f
                isSingleLine = true
                gravity = Gravity.CENTER
                setPadding(
                    CaddyTheme.dp(context, 6f),
                    CaddyTheme.dp(context, 2f),
                    CaddyTheme.dp(context, 6f),
                    CaddyTheme.dp(context, 2f)
                )
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = CaddyTheme.dp(context, 8f)
                }
            }
            row.addView(actionView)

            return QueueViewHolder(row)
        }

        override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
            val item = items[position]
            val isSelected = currentFocus.zone == Zone.QLIST && currentFocus.index == position

            holder.titleText.text = item.title

            val progressStr = when (item.state) {
                DownloadQueue.STATE_DONE -> "▮▮▮▮▮▮▮▮▮▮ 100% (${formatBytes(item.sizeBytes)})"
                DownloadQueue.STATE_UNZIPPING -> "▮▮▮▮▮▮▮▮▮▮ unzipping archive…"
                DownloadQueue.STATE_FAILED -> "failed (${formatBytes(item.bytesDone)} / ${formatBytes(item.sizeBytes)})"
                else -> buildProgressBar(item.bytesDone, item.sizeBytes)
            }
            holder.progressText.text = progressStr
            val statusText: String
            val statusColor: Int
            val actionLabel: String

            when (item.state) {
                DownloadQueue.STATE_RUNNING -> {
                    statusText = "downloading"
                    statusColor = CaddyTheme.GREEN
                    actionLabel = "[PAUSE]"
                }
                DownloadQueue.STATE_UNZIPPING -> {
                    statusText = "unzipping"
                    statusColor = CaddyTheme.AMBER
                    actionLabel = "[WAIT]"
                }
                DownloadQueue.STATE_QUEUED -> {
                    statusText = "waiting"
                    statusColor = CaddyTheme.MUTE
                    actionLabel = "[PAUSE]"
                }
                DownloadQueue.STATE_PAUSED -> {
                    statusText = "paused"
                    statusColor = CaddyTheme.AMBER
                    actionLabel = "[RESUME]"
                }
                DownloadQueue.STATE_DONE -> {
                    statusText = "on disk"
                    statusColor = CaddyTheme.GREEN
                    actionLabel = "[DONE]"
                }
                DownloadQueue.STATE_FAILED -> {
                    statusText = "failed"
                    statusColor = CaddyTheme.RED
                    actionLabel = "[RETRY]"
                }
                else -> {
                    statusText = item.state
                    statusColor = CaddyTheme.MUTE
                    actionLabel = "[ACTION]"
                }
            }

            holder.statusBadge.text = statusText
            holder.actionButton.text = actionLabel

            if (isSelected) {
                // Inverted row: #d7d9d6 ground, #08090a text
                holder.root.setBackgroundColor(CaddyTheme.INK)
                holder.titleText.setTextColor(CaddyTheme.GROUND)
                holder.progressText.setTextColor(0xFF333333.toInt())
                holder.statusBadge.setTextColor(CaddyTheme.GROUND)

                holder.actionButton.background = GradientDrawable().apply {
                    setColor(CaddyTheme.GROUND)
                    setStroke(1, CaddyTheme.GROUND)
                }
                holder.actionButton.setTextColor(CaddyTheme.INK)
            } else {
                holder.root.setBackgroundColor(Color.TRANSPARENT)
                holder.titleText.setTextColor(CaddyTheme.INK)
                holder.progressText.setTextColor(CaddyTheme.MUTE)
                holder.statusBadge.setTextColor(statusColor)

                holder.actionButton.background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(1, CaddyTheme.LINE)
                }
                holder.actionButton.setTextColor(CaddyTheme.INK)
            }

            holder.root.setOnClickListener {
                onItemAction?.invoke(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
