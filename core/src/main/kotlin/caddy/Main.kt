package caddy

import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * P1->P2 spike from the PRD: read an index.json, list one system, resolve one
 * game, download it into <roms>/<system>/, verify it.
 *
 *   caddy <index.json> <system> <roms-root> [--title=Metroid]
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: caddy <index.json> <system> <roms-root> [--title=NAME]")
        kotlin.system.exitProcess(2)
    }
    val (indexPath, system, romsRoot) = args
    val title = args.drop(3).firstOrNull { it.startsWith("--title=") }?.removePrefix("--title=")

    val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val adapters = mapOf<String, SourceAdapter>(
        "archive_org" to ArchiveOrgAdapter(http),
        "minerva" to MinervaAdapter(http),
    )

    val index = parseIndex(File(indexPath).readText(), adapters.keys)
    println("repo: ${index.name} (schemaVersion ${index.schemaVersion}, ${index.sources.size} sources)")

    val source = index.sources.firstOrNull { system in it.systems }
        ?: run {
            System.err.println("no source in this repo serves $system")
            kotlin.system.exitProcess(1)
        }
    val adapter = adapters.getValue(source.adapter)
    println("source: ${source.name} [${source.transport}/${source.adapter}]")

    val entries = runBlockingList(adapter, source, system)
    val chosen = title?.let { t -> entries.firstOrNull { it.title.contains(t, ignoreCase = true) } }
        ?: entries.firstOrNull()
        ?: run {
            System.err.println("no entries listed for $system")
            kotlin.system.exitProcess(1)
        }
    println("entries: ${entries.size}; picking \"${chosen.title}\"")

    val resolved = runBlockingResolve(adapter, source, chosen)
    val directory = File(romsRoot, SystemRouter.relativePath(system))
    println("target:  ${directory.resolve(chosen.filename).path}")

    when (resolved) {
        is ResolvedDownload.Direct -> {
            var lastPct = -1
            val file = HttpDownloader(http).download(resolved, File(directory, resolved.filename)) { done, total ->
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        print("\r  $pct%")
                    }
                }
            }
            println()
            println("verified: ${verify(file, resolved.hashes)}")
            println("landed:  ${file.path}")
        }
        is ResolvedDownload.Torrent -> {
            println("torrent:  ${resolved.magnet.substringBefore('&')}")
            println("file:     #${resolved.fileIndex} ${resolved.filename} (${resolved.size ?: "?"} bytes)")
            println("selective download: set file priority on index ${resolved.fileIndex} only")
        }
        is ResolvedDownload.NeedsCapture -> println("capture required at ${resolved.pageUrl}")
    }
}

private fun runBlockingList(adapter: SourceAdapter, source: SourceConfig, system: String): List<CatalogEntry> =
    kotlinx.coroutines.runBlocking { adapter.list(source, system, 0) }

private fun runBlockingResolve(adapter: SourceAdapter, source: SourceConfig, entry: CatalogEntry): ResolvedDownload =
    kotlinx.coroutines.runBlocking { adapter.resolve(source, entry) }
