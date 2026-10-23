package caddy

import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * P1->P2 spike from the PRD: read an index.json, list one system, resolve one
 * game, download it into <roms>/<system>/, verify it.
 *
 *   caddy <index.json> <system> <roms-root> [--title=Metroid] [--list[=N]] [--page=N]
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: caddy <index.json> <system> <roms-root> [--title=NAME]")
        kotlin.system.exitProcess(2)
    }
    val (indexPath, system, romsRoot) = args
    val flags = args.drop(3)
    val title = flags.firstOrNull { it.startsWith("--title=") }?.removePrefix("--title=")
    // --list prints what the source offers and exits; --page picks a listing page.
    val list = flags.firstOrNull { it == "--list" || it.startsWith("--list=") }
    val limit = list?.substringAfter('=', "")?.toIntOrNull() ?: if (list != null) 50 else 0
    val page = flags.firstOrNull { it.startsWith("--page=") }?.removePrefix("--page=")?.toIntOrNull() ?: 0

    val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val adapters = builtInAdapters(http)

    val index = parseIndex(File(indexPath).readText(), adapters.keys)
    println("repo: ${index.name} (schemaVersion ${index.schemaVersion}, ${index.sources.size} sources)")

    val source = index.sources.firstOrNull { system in it.systems }
        ?: run {
            System.err.println("no source in this repo serves $system")
            kotlin.system.exitProcess(1)
        }
    val adapter = adapters.getValue(source.adapter)
    println("source: ${source.name} [${source.transport}/${source.adapter}]")

    // A title query must search the whole catalog, not just the first page: on a
    // 2,000-title source "not on page 0" is not "not there".
    val entries = if (title != null) runBlockingSearch(adapter, source, system, title)
                  else runBlockingList(adapter, source, system, page)
    if (list != null) {
        println("entries: ${entries.size}${if (title != null) " matching \"$title\"" else " on page $page"}")
        entries.take(limit).forEachIndexed { i, e ->
            val size = e.sizeBytes?.let { "%.1f MB".format(it / 1e6) } ?: "?"
            val region = e.region?.let { "[$it] " } ?: ""
            val extras = if (e.variants.isEmpty()) "" else
                "  (+${e.variants.count { it.kind == KIND_UPDATE }} update, " +
                    "${e.variants.count { it.kind == KIND_DLC }} dlc)"
            println("%4d  %-10s %s%s%s".format(i, size, region, e.title, extras))
        }
        if (entries.size > limit) println("... ${entries.size - limit} more (--list=N to show more)")
        return
    }
    val chosen = title?.let { t -> entries.firstOrNull { it.title.contains(t, ignoreCase = true) } }
        ?: entries.firstOrNull()
        ?: run {
            System.err.println("no entries listed for $system")
            kotlin.system.exitProcess(1)
        }

    // --part=update|dlc|0,1 picks a variant of the chosen game. Without it the
    // base game downloads, which is what "just get me the game" means.
    val parts = flags.filter { it.startsWith("--part=") }
        .flatMap { it.removePrefix("--part=").split(",") }
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
    println("entries: ${entries.size}; picking \"${chosen.title}\"")

    val directory = File(romsRoot, SystemRouter.relativePath(system))
    val wanted = if (parts.isEmpty()) listOf(KIND_BASE) else parts
    val selection = if (wanted.contains(chosen.kind) || chosen.variants.isEmpty()) listOf(chosen)
    else chosen.variants.filter { it.kind in wanted }.map { chosen.asVariant(it) }
    if (selection.isEmpty()) {
        System.err.println("${chosen.title} has no ${parts.joinToString("/")} file")
        kotlin.system.exitProcess(1)
    }
    for (entry in selection) downloadOne(adapter, source, http, entry, directory)

}

/** Resolve and fetch one entry, printing progress. */
private fun downloadOne(
    adapter: SourceAdapter,
    source: SourceConfig,
    http: OkHttpClient,
    entry: CatalogEntry,
    directory: File,
) {
    val resolved = runBlockingResolve(adapter, source, entry)
    println("${entry.kind}: ${entry.title}")
    println("target:  ${directory.resolve(entry.filename).path}")
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

private fun runBlockingList(
    adapter: SourceAdapter, source: SourceConfig, system: String, page: Int,
): List<CatalogEntry> = kotlinx.coroutines.runBlocking { adapter.list(source, system, page) }

private fun runBlockingSearch(
    adapter: SourceAdapter, source: SourceConfig, system: String, query: String,
): List<CatalogEntry> = kotlinx.coroutines.runBlocking { adapter.search(source, system, query) }

private fun runBlockingResolve(adapter: SourceAdapter, source: SourceConfig, entry: CatalogEntry): ResolvedDownload =
    kotlinx.coroutines.runBlocking { adapter.resolve(source, entry) }
