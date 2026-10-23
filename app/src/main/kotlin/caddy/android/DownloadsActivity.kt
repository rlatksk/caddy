package caddy.android

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The queue, with a refresh tick.
 *
 * Polling, not LiveData: the queue lives in SQLite owned by a service in another
 * component, and a 1 s re-read of a handful of rows costs less than wiring a
 * ContentObserver for a screen the user leaves open for a minute.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var queue: DownloadQueue
    private lateinit var status: TextView
    private lateinit var labels: ArrayAdapter<String>
    private val handler = Handler(Looper.getMainLooper())
    private var lastRows: List<String> = emptyList()
    private var lastStatus: String = ""

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = DownloadQueue(this)

        status = TextView(this).apply {
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 8, 24, 8)
        }

        labels = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(this).apply { adapter = labels }

        val resume = Button(this).apply {
            text = "Resume queue"
            setOnClickListener { DownloadService.start(this@DownloadsActivity) }
        }
        val clear = Button(this).apply {
            text = "Clear finished"
            setOnClickListener {
                queue.all().filter { it.state == DownloadQueue.STATE_DONE || it.state == DownloadQueue.STATE_FAILED }
                    .forEach { queue.remove(it.id) }
                render()
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 12, 24, 12)
                addView(status)
                addView(LinearLayout(this@DownloadsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(resume)
                    addView(clear)
                })
                addView(list, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        )
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    /**
     * Re-renders only when something changed. Posting a full
     * notifyDataSetChanged every second fires accessibility events forever,
     * which keeps the window from ever going idle.
     */
    private fun render() {
        val rows = queue.all()
        val lines = rows.map { row ->
            val size = if (row.sizeBytes > 0) {
                "%.1f / %.1f MB".format(row.bytesDone / 1e6, row.sizeBytes / 1e6)
            } else {
                "%.1f MB".format(row.bytesDone / 1e6)
            }
            val pct = if (row.sizeBytes > 0) " (${row.bytesDone * 100 / row.sizeBytes}%)" else ""
            val err = row.error?.let { " — $it" } ?: ""
            "${row.state.uppercase()}  ${row.title}\n    ${row.system} · $size$pct · ${row.kind}$err"
        }
        if (lines != lastRows) {
            lastRows = lines
            labels.clear()
            labels.addAll(lines)
            labels.notifyDataSetChanged()
        }
        val active = rows.count { it.state == DownloadQueue.STATE_QUEUED || it.state == DownloadQueue.STATE_RUNNING }
        val summary = "${rows.size} item(s), $active active"
        if (summary != lastStatus) {
            lastStatus = summary
            status.text = summary
        }
    }
}
