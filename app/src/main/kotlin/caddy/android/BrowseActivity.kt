package caddy.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import caddy.CatalogEntry
import caddy.KIND_DLC
import caddy.asVariant
import caddy.KIND_UPDATE
import caddy.HttpDownloader
import caddy.ResolvedDownload
import caddy.SourceAdapter
import caddy.SourceConfig
import caddy.SystemRouter
import caddy.builtInAdapters
import caddy.parseIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Browse one system and download into ROMs/<system>/.
 *
 * ListView, not a grid of covers: this is the engine's proof, not the product.
 * It virtualises, it takes D-pad focus for free, and it holds 2,000 titles.
 */
class BrowseActivity : AppCompatActivity() {

    private lateinit var store: RepoStore
    private lateinit var http: OkHttpClient
    private lateinit var adapter: SourceAdapter
    private lateinit var source: SourceConfig

    private lateinit var system: String
    private lateinit var status: TextView
    private lateinit var query: EditText
    private lateinit var list: ListView
    private lateinit var labels: ArrayAdapter<String>

    private var entries: List<CatalogEntry> = emptyList()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RepoStore(this)
        http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        system = intent.getStringExtra(EXTRA_SYSTEM) ?: run { finish(); return }
        val index = parseIndex(store.indexJson ?: "", RepoStore.KNOWN_ADAPTERS)
        val resolvedSource = index.sources.firstOrNull { system in it.systems }
        if (resolvedSource == null) {
            finish()
            return
        }
        source = resolvedSource
        adapter = builtInAdapters(http).getValue(source.adapter)

        status = TextView(this).apply {
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 8, 24, 8)
            text = "$system  ·  ${source.name}"
        }

        query = EditText(this).apply {
            hint = "Search $system"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            textSize = 15f
        }
        val searchButton = Button(this).apply {
            text = "Search"
            setOnClickListener { load(query.text.toString().trim()) }
        }
        val allButton = Button(this).apply {
            text = "All"
            setOnClickListener { query.setText(""); load("") }
        }

        labels = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list = ListView(this).apply {
            adapter = labels
            setOnItemClickListener { _, _, position, _ ->
                entries.getOrNull(position)?.let { showParts(it) }
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 12, 24, 12)
                addView(status)
                addView(LinearLayout(this@BrowseActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(query, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(searchButton)
                    addView(allButton)
                })
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        )

        load("")
    }

    private fun load(text: String) {
        if (busy) return
        busy = true
        status.text = "$system  ·  ${source.name}  ·  loading…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (text.isEmpty()) adapter.list(source, system, 0)
                    else adapter.search(source, system, text)
                }
            }
            busy = false
            result.fold(
                onSuccess = { found ->
                    entries = found
                    labels.clear()
                    labels.addAll(found.map { row(it) })
                    labels.notifyDataSetChanged()
                    status.text = "$system  ·  ${source.name}  ·  ${found.size} games"
                    Log.i(TAG, "listed $system: ${found.size} entries")
                },
                onFailure = { error ->
                    status.text = "$system  ·  ${source.name}  ·  ${error.message ?: "failed"}"
                },
            )
        }
    }

    /**
     * A game with an update or DLC opens a chooser: base game, update, each DLC.
     * Downloading the base alone is the common case, so it is listed first and a
     * plain tap on a game with no extras skips this screen entirely.
     */
    private fun showParts(entry: CatalogEntry) {
        if (entry.variants.isEmpty()) {
            download(entry)
            return
        }
        val options = buildList {
            add("Base game · ${mb(entry.sizeBytes)}" to entry)
            entry.variants.forEach { v ->
                val label = when (v.kind) {
                    KIND_UPDATE -> "Update" + if (v.revision > 0) " v${v.revision}" else ""
                    else -> v.filename.substringBeforeLast('.').substringAfterLast('—').trim()
                }
                add("$label · ${mb(v.sizeBytes)}" to entry.asVariant(v))
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                download(options[which].second)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun mb(bytes: Long?): String =
        if (bytes == null || bytes <= 0) "?" else "%.1f MB".format(bytes / 1e6)

    private fun row(entry: CatalogEntry): String {
        val size = entry.sizeBytes?.takeIf { it > 0 }?.let { " · %.1f MB".format(it / 1e6) } ?: ""
        val region = entry.region?.let { " · $it" } ?: ""
        val extras = if (entry.variants.isEmpty()) "" else {
            val u = entry.variants.count { it.kind == KIND_UPDATE }
            val d = entry.variants.count { it.kind == KIND_DLC }
            " · +$u upd, +$d dlc"
        }
        return entry.title + size + region + extras
    }

    /**
     * Hands the file to the foreground service instead of downloading inline:
     * a 15 GB game outlives the activity, and the queue is what makes resume
     * after process death possible.
     */
    private fun download(entry: CatalogEntry) {
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        val id = DownloadQueue(this).enqueue(
            system = system,
            title = entry.title,
            filename = entry.filename,
            kind = entry.kind,
            sourceId = source.id,
            sourceRef = entry.sourceRef,
            sizeBytes = entry.sizeBytes,
        )
        DownloadService.start(this)
        status.text = "Queued ${entry.title} (#$id)"
        Log.i(TAG, "queued ${entry.title} as #$id")
    }

    /** Real path, not SAF: HttpDownloader streams with RandomAccessFile. */
    private fun romsRoot(): File = File(store.romsRoot)

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        status.text = "Grant \"All files access\" so downloads can land in ROMs/"
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }
    }

    private companion object {
        const val EXTRA_SYSTEM = "system"
        const val TAG = "caddy"
    }
}
