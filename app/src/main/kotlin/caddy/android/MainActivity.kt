package caddy.android

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * P1: add a repository, then toggle systems.
 *
 * Three ways in, because the device must not depend on the user publishing
 * anything: a URL, pasted JSON, or a LAN address.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: RepoStore
    private lateinit var fetcher: RepoFetcher

    private lateinit var status: TextView
    private lateinit var urlInput: EditText
    private lateinit var jsonInput: EditText
    private lateinit var systemsContainer: LinearLayout
    private lateinit var repoSummary: TextView
    private lateinit var romsInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RepoStore(this)
        fetcher = RepoFetcher(store)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(error) }
            previous?.uncaughtException(thread, error)
        }

        status = TextView(this).apply {
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 16, 24, 16)
        }

        urlInput = EditText(this).apply {
            hint = "https://…/index.json  or  user/repo/blob/main/index.json"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            textSize = 15f
        }

        jsonInput = EditText(this).apply {
            hint = "…or paste the index JSON here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            textSize = 13f
        }

        repoSummary = TextView(this).apply {
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 16, 0, 8)
        }

        romsInput = EditText(this).apply {
            hint = "ROMs root, e.g. /storage/emulated/0/ROMs"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            textSize = 13f
            setText(store.romsRoot)
            setOnFocusChangeListener { _, focused ->
                if (!focused && text.isNotBlank()) store.romsRoot = text.toString().trim()
            }
        }

        systemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val addButton = Button(this).apply {
            text = "Add repository"
            setOnClickListener { addFromUrl() }
        }
        val pasteButton = Button(this).apply {
            text = "Use pasted JSON"
            setOnClickListener { addFromPaste() }
        }
        val removeButton = Button(this).apply {
            text = "Remove"
            setOnClickListener { removeRepo() }
        }
        val updateButton = Button(this).apply {
            text = "Check update"
            setOnClickListener { checkUpdate(this) }
        }
        val downloadsButton = Button(this).apply {
            text = "Downloads"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
            }
        }
        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener { refresh() }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(addButton)
            addView(pasteButton)
            addView(refreshButton)
            addView(removeButton)
            addView(downloadsButton)
            addView(updateButton)
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 16, 24, 16)
                addView(urlInput)
                addView(jsonInput)
                addView(row)
                addView(status)
                addView(ScrollView(this@MainActivity).apply {
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(repoSummary)
                        addView(romsInput)
                        addView(systemsContainer)
                    })
                })
            }
        )

        // D-pad: enter on the URL field adds, so the screen is usable with a pad.
        urlInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_ENTER) {
                addFromUrl(); true
            } else false
        }

        render()
    }

    private fun say(text: String) {
        status.text = text
    }

    private fun addFromUrl() {
        val raw = urlInput.text.toString()
        if (raw.isBlank()) {
            say("Enter a repository URL first.")
            return
        }
        say("Fetching…")
        lifecycleScope.launch {
            fetcher.fetch(raw).fold(
                onSuccess = {
                    urlInput.setText("")
                    jsonInput.setText("")
                    say("Repository added.")
                    render()
                },
                onFailure = { error ->
                    say((error as? RepoException)?.error?.message() ?: "Failed: ${error.message}")
                },
            )
        }
    }

    /** Pasted JSON is validated by the same path as a fetched document. */
    private fun addFromPaste() {
        val text = jsonInput.text.toString().trim()
        if (text.isEmpty()) {
            say("Paste the index JSON first.")
            return
        }
        val result = runCatching { caddy.parseIndex(text, RepoStore.KNOWN_ADAPTERS) }
        result.fold(
            onSuccess = { index ->
                if (index.sources.isEmpty()) {
                    say("That JSON has no sources this app understands, so it was not kept.")
                    return
                }
                store.indexJson = text
                store.indexUrl = "pasted"
                store.lastFetch = System.currentTimeMillis()
                jsonInput.setText("")
                say("Repository added from pasted JSON.")
                render()
            },
            onFailure = { error ->
                say("That isn't a valid index: ${error.message}")
            },
        )
    }

    private fun removeRepo() {
        store.indexJson = null
        store.indexUrl = null
        store.enabledSystems = emptySet()
        say("Repository removed.")
        render()
    }

    private fun refresh() {
        val url = store.indexUrl
        if (url == null || url == "pasted") {
            say("Pasted repositories can't be refreshed; paste again.")
            return
        }
        // Clearing first so the already-added guard does not block the refetch.
        store.indexJson = null
        store.indexUrl = null
        urlInput.setText(url)
        say("Refreshing…")
        lifecycleScope.launch {
            fetcher.fetch(url).fold(
                onSuccess = { say("Repository refreshed."); render() },
                onFailure = { say((it as? RepoException)?.error?.message() ?: "Failed: ${it.message}"); render() },
            )
        }
    }

    private fun render() {
        systemsContainer.removeAllViews()
        val sources = store.sources()

        if (sources.isEmpty()) {
            repoSummary.text = "No repository.\n\nCaddy doesn't include any game sources.\nAdd a repository to get started."
            return
        }

        val systems = store.availableSystems()
        val enabled = store.enabledSystems
        repoSummary.text = buildString {
            append("${store.indexUrl ?: "pasted"}\n")
            append("${sources.size} source(s), ${systems.size} system(s)")
            if (enabled.isNotEmpty()) append(", ${enabled.size} enabled")
            append("\n")
        }

        systems.forEach { system ->
            val label = TextView(this).apply {
                text = "${if (system in enabled) "[x]" else "[ ]"}  $system"
                textSize = 18f
                typeface = android.graphics.Typeface.MONOSPACE
                isFocusable = true
                isClickable = true
                setPadding(8, 12, 8, 12)
                setOnClickListener {
                    store.toggleSystem(system)
                    render()
                }
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (system in enabled) {
                row.addView(Button(this).apply {
                    text = "Browse"
                    setOnClickListener {
                        if (romsInput.text.isNotBlank()) store.romsRoot = romsInput.text.toString().trim()
                        startActivity(Intent(this@MainActivity, BrowseActivity::class.java).putExtra("system", system))
                    }
                })
            }
            systemsContainer.addView(row)
        }

        if (enabled.isEmpty()) {
            systemsContainer.addView(TextView(this).apply {
                text = "\nNothing enabled yet. Pick the systems you want; only those appear in Browse."
                textSize = 13f
            })
        }
    }

    /**
     * Update check is manual: no background polling, because this app makes no
     * network calls the user did not ask for.
     */
    private fun checkUpdate(button: Button) {
        button.isEnabled = false
        say("Checking for updates…")
        lifecycleScope.launch {
            when (val result = UpdateChecker(this@MainActivity).check()) {
                is UpdateResult.UpToDate -> say("Caddy is up to date (${BuildConfig.VERSION_NAME}).")
                is UpdateResult.Failed -> say("Update check failed: ${result.message}")
                is UpdateResult.Ready -> {
                    say("Update ${result.info.versionName} ready — installing.")
                    UpdateChecker(this@MainActivity).install(result.apk)
                }
            }
            button.isEnabled = true
        }
    }

    private fun writeCrash(error: Throwable) {
        val dir = java.io.File(getExternalFilesDir(null), "logs").apply { mkdirs() }
        java.io.File(dir, "crash-${System.currentTimeMillis()}.log").writeText(
            "device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / API ${android.os.Build.VERSION.SDK_INT}\n" +
                error.stackTraceToString()
        )
    }
}
