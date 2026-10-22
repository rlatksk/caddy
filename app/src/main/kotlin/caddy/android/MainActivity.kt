package caddy.android

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import caddy.Hashes
import caddy.SourceConfig
import caddy.SourceParams
import caddy.SystemRouter
import caddy.Transport
import caddy.buildCatalog
import caddy.parseIndex
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P0 shell. Not the real UI — this exists to prove four things on the device:
 *
 *  1. the APK installs and runs on arm64,
 *  2. the :core module executes on Android, not just the JVM,
 *  3. ES-DE routing produces the right folder names,
 *  4. we can get a log off the device without a cable.
 */
class MainActivity : AppCompatActivity() {

    private val log = StringBuilder()
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
        }

        val run = Button(this).apply {
            text = "Run core self-test"
            setOnClickListener { runSelfTest() }
        }
        val share = Button(this).apply {
            text = "Export log"
            setOnClickListener { exportLog() }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(run)
            addView(share)
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(buttons)
                addView(ScrollView(this@MainActivity).apply { addView(output) })
            }
        )

        header()
        runSelfTest()
    }

    private fun line(text: String) {
        log.append(text).append('\n')
        output.text = log.toString()
    }

    private fun header() {
        line("Caddy ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        line("device   ${Build.MANUFACTURER} ${Build.MODEL}")
        line("android  ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("abi      ${Build.SUPPORTED_ABIS.joinToString()}")
        line("")
    }

    private fun runSelfTest() {
        line("── core self-test ──")
        var passed = 0
        var failed = 0
        fun check(name: String, block: () -> Boolean) {
            val ok = runCatching(block).getOrElse { false }
            if (ok) passed++ else failed++
            line("${if (ok) "PASS" else "FAIL"}  $name")
        }

        // 1. ES-DE routing: the system name IS the folder name.
        check("router gba -> gba") { SystemRouter.relativePath("gba") == "gba" }
        check("router psx -> psx") { SystemRouter.relativePath("psx") == "psx" }
        check("router rejects traversal") {
            runCatching { SystemRouter.relativePath("../etc") }.isFailure
        }

        // 2. Index parsing, including the forward-compat rules.
        val indexText = """
            {"schemaVersion":1,"name":"selftest","sources":[
              {"id":"a","name":"A","transport":"http","adapter":"archive_org",
               "systems":["genesis"],"config":{"collections":{"genesis":"example"}}},
              {"id":"b","name":"B","transport":"http","adapter":"from_the_future",
               "systems":["gba"],"config":{}}
            ]}
        """.trimIndent()
        val known = setOf("archive_org", "minerva", "html_index", "browser_capture")
        val parsed = runCatching { parseIndex(indexText, known) }.getOrNull()
        check("index parses") { parsed != null }
        check("unknown adapter dropped, not fatal") { parsed?.sources?.size == 1 }
        check("transport parsed") { parsed?.sources?.first()?.transport == Transport.HTTP }
        check("newer schemaVersion rejected") {
            runCatching { parseIndex("""{"schemaVersion":99,"name":"x","sources":[]}""", known) }
                .isFailure
        }

        // 3. Catalog dedupe, the hard part of the product.
        val files = listOf(
            "Metroid Fusion (USA).zip",
            "Metroid Fusion (Europe) (En,Fr,De).zip",
            "Metroid Fusion (Japan).zip",
            "[BIOS] Game Boy Advance (World).zip",
            "Advance Wars (USA).zip",
        ).map { caddy.CatalogEntry(system = "gba", title = it, filename = it) }
        val catalog = runCatching { buildCatalog("gba", files) }.getOrNull()
        check("catalog dedupes 5 files -> 2 games") { catalog?.size == 2 }
        check("catalog drops BIOS") { catalog?.none { it.filename.startsWith("[BIOS]") } == true }
        check("catalog prefers USA") {
            catalog?.firstOrNull { it.filename.startsWith("Metroid") }?.region == "USA"
        }

        // 4. Minerva source config round-trip (no network in P0).
        val minerva = SourceConfig(
            id = "m", name = "M", transport = Transport.TORRENT, adapter = "minerva",
            systems = listOf("gba"),
            config = SourceParams(baseUrl = "https://example.invalid", paths = mapOf("gba" to "x")),
        )
        check("source config holds torrent transport") { minerva.transport == Transport.TORRENT }
        check("hashes default empty") { Hashes().sha1 == null }

        line("")
        line("$passed passed, $failed failed")
        line(if (failed == 0) "CORE OK on ${Build.SUPPORTED_ABIS.first()}" else "CORE BROKEN")
    }

    private fun exportLog() {
        val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "caddy-$stamp.log")
        file.writeText(log.toString())
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, android.net.Uri.fromFile(file))
                    putExtra(Intent.EXTRA_SUBJECT, "Caddy log $stamp")
                },
                "Share log"
            )
        )
    }
}
