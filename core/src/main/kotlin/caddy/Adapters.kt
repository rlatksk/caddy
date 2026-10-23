package caddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Adapter types this build implements. A repo naming anything else is skipped. */
val BUILT_IN_ADAPTERS: Set<String> = setOf("archive_org", "minerva", "dlarchive")

/** One registry for every caller (CLI, app), so the list cannot drift. */
fun builtInAdapters(http: OkHttpClient): Map<String, SourceAdapter> = mapOf(
    "archive_org" to ArchiveOrgAdapter(http),
    "minerva" to MinervaAdapter(http),
    "dlarchive" to DlArchiveAdapter(http),
)

/** archive.org adapter: advancedsearch.php for listings, metadata/<id> for hashes. */
class ArchiveOrgAdapter(private val http: OkHttpClient) : SourceAdapter {

    override fun supports(system: String) = true

    private fun collection(source: SourceConfig, system: String): String =
        source.config.collections[system]
            ?: throw SourceUnavailable("${source.id} does not serve $system", retryable = false)

    override suspend fun list(source: SourceConfig, system: String, page: Int): List<CatalogEntry> {
        val collection = collection(source, system)
        val url = "https://archive.org/advancedsearch.php?q=" +
            URLEncoder.encode("collection:$collection", "UTF-8") +
            "&fl[]=identifier&fl[]=title&rows=100&page=$page&output=json"
        val body = fetch(url)
        val docs = Json.parseToJsonElement(body).jsonObject["response"]!!.jsonObject["docs"]!!.jsonArray
        val entries = docs.mapNotNull { doc ->
            val obj = doc.jsonObject
            val identifier = obj["identifier"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.content ?: identifier
            CatalogEntry(system = system, title = title, filename = identifier)
        }
        return buildCatalog(system, entries)
    }

    override suspend fun search(source: SourceConfig, system: String, query: String): List<CatalogEntry> {
        val collection = collection(source, system)
        val q = "collection:$collection AND title:($query)"
        val url = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(q, "UTF-8") +
            "&fl[]=identifier&fl[]=title&rows=100&output=json"
        val docs = Json.parseToJsonElement(fetch(url)).jsonObject["response"]!!.jsonObject["docs"]!!.jsonArray
        return docs.mapNotNull { doc ->
            val obj = doc.jsonObject
            val identifier = obj["identifier"]?.jsonPrimitive?.content ?: return@mapNotNull null
            CatalogEntry(
                system = system,
                title = obj["title"]?.jsonPrimitive?.content ?: identifier,
                filename = identifier,
            )
        }
    }

    override suspend fun resolve(source: SourceConfig, entry: CatalogEntry): ResolvedDownload {
        val meta = Json.parseToJsonElement(fetch("https://archive.org/metadata/${entry.filename}"))
            .jsonObject["files"]!!.jsonArray

        // Prefer a ROM-ish file over screenshots, metadata and history snapshots.
        val rom = meta.map { it.jsonObject }
            .filter { file ->
                val name = file["name"]?.jsonPrimitive?.content ?: return@filter false
                !name.startsWith("history/") && !name.startsWith("__") &&
                    !name.endsWith(".xml") && !name.endsWith(".sqlite") &&
                    !name.endsWith(".torrent") && !name.endsWith(".gif") &&
                    !name.endsWith(".png") && !name.endsWith(".jpg") &&
                    !name.contains("_meta.txt")
            }
            .maxByOrNull { it["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }
            ?: throw SourceUnavailable("${entry.filename} has no downloadable file", retryable = false)

        val name = rom["name"]!!.jsonPrimitive.content
        return ResolvedDownload.Direct(
            url = "https://archive.org/download/${entry.filename}/${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}",
            filename = name,
            size = rom["size"]?.jsonPrimitive?.content?.toLongOrNull(),
            hashes = Hashes(
                sha1 = rom["sha1"]?.jsonPrimitive?.content,
                crc32 = rom["crc32"]?.jsonPrimitive?.content,
            ),
        )
    }

    private fun fetch(url: String): String {
        val response = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute()
        response.use {
            if (!it.isSuccessful) throw SourceUnavailable("HTTP ${it.code} for $url", retryable = it.code >= 500 || it.code == 429)
            return it.body.string()
        }
    }

    private companion object { const val UA = "caddy/0.1" }
}

/**
 * Minerva adapter. Browsing is HTML (jsoup); delivery is torrent-only — every
 * direct path 404s, verified 2026-10-22. The magnet comes from the same page.
 */
open class MinervaAdapter(private val http: OkHttpClient) : SourceAdapter {

    override fun supports(system: String) = true

    private fun pageUrl(source: SourceConfig, system: String): String {
        val base = source.config.baseUrl?.trimEnd('/')
            ?: throw SourceUnavailable("${source.id} has no baseUrl", retryable = false)
        val path = source.config.paths[system]
            ?: throw SourceUnavailable("${source.id} does not serve $system", retryable = false)
        return "$base/browse/" + path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        } + "/"
    }

    override suspend fun list(source: SourceConfig, system: String, page: Int): List<CatalogEntry> =
        buildCatalog(system, listRaw(source, system))

    /**
     * Undeduped listing, one entry per torrent file. This is what the browse grid
     * shows before catalog dedupe, and what the torrent engine needs: each row's
     * data-m attribute is the file index inside the collection torrent.
     */
    fun listRaw(source: SourceConfig, system: String): List<CatalogEntry> {
        val doc = Jsoup.parse(fetch(pageUrl(source, system)))
        // A missing magnet means the collection is not torrent-delivered; fail
        // here rather than handing the engine an entry it cannot fetch.
        if (doc.selectFirst("a[onclick*=downloadMagnet]") == null) {
            throw SourceUnavailable("no magnet on ${system} page", retryable = false)
        }

        return doc.select("div.entry[data-name]").mapNotNull { row ->
            val name = row.attr("data-name").ifEmpty { return@mapNotNull null }
            // The page shows the display name; the torrent uses the same string.
            val display = row.selectFirst("a[href^=/rom?id=]")?.text()?.trim().orEmpty().ifEmpty { name }
            val index = row.selectFirst("[data-m]")?.attr("data-m")?.toIntOrNull()
                ?: return@mapNotNull null
            CatalogEntry(
                system = system,
                title = display,
                filename = display,
                sizeBytes = row.selectFirst("span")?.text()?.let(::parseSize),
                torrentFileIndex = index,
            )
        }
    }

    override suspend fun search(source: SourceConfig, system: String, query: String): List<CatalogEntry> =
        list(source, system, 0).filter { it.title.contains(query, ignoreCase = true) }

    override suspend fun resolve(source: SourceConfig, entry: CatalogEntry): ResolvedDownload {
        val doc = Jsoup.parse(fetch(pageUrl(source, entry.system)))
        val magnet = doc.select("a[onclick*=downloadMagnet]").firstOrNull()
            ?.attr("onclick")?.let { MAGNET.find(it)?.value }
            ?: throw SourceUnavailable("no magnet on ${entry.system} page", retryable = false)
        val index = entry.torrentFileIndex
            ?: throw SourceUnavailable("${entry.filename} has no torrent file index", retryable = false)
        return ResolvedDownload.Torrent(
            magnet = magnet,
            fileIndex = index,
            filename = entry.filename,
            size = entry.sizeBytes,
        )
    }

    /** Overridden in tests to serve fixture HTML without the network. */
    internal open fun fetch(url: String): String {
        val response = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute()
        response.use {
            if (!it.isSuccessful) throw SourceUnavailable("HTTP ${it.code} for $url", retryable = it.code >= 500)
            return it.body.string()
        }
    }

    private companion object {
        const val UA = "caddy/0.1"
        val MAGNET = Regex("magnet:\\?xt=urn:btih:[0-9a-fA-F]{40}[^'\"]*")
        val SIZE = Regex("([0-9.]+)\\s*(B|KB|MB|GB|TB)")
        fun parseSize(text: String): Long? {
            val m = SIZE.find(text) ?: return null
            val value = m.groupValues[1].toDoubleOrNull() ?: return null
            val unit = when (m.groupValues[2]) {
                "KB" -> 1024.0
                "MB" -> 1024.0 * 1024
                "GB" -> 1024.0 * 1024 * 1024
                "TB" -> 1024.0 * 1024 * 1024 * 1024
                else -> 1.0
            }
            return (value * unit).toLong()
        }
    }
}

/**
 * dlarchive adapter. One JSON file index covers everything the host mirrors:
 *
 *   files_api.php?action=list&offset=N&limit=200  -> {ok,total,items:[{id,filename,size,folder}]}
 *   files_api.php?action=file-url&id=<id>         -> {ok,url,filename,expires_s}
 *
 * The index carries no platform field, so a system is identified by its file
 * extensions (`.nsp/.xci/.nsz` is unambiguously Switch). There is no server-side
 * search: the whole index is fetched once and cached, because filtering 15k rows
 * locally is cheaper than one request per keystroke.
 *
 * Resolved URLs are token-free and expire after 24h, so they are never cached.
 */
class DlArchiveAdapter(
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : SourceAdapter {

    override fun supports(system: String) = true

    private data class Row(val id: String, val filename: String, val size: Long)
    private class Cached(val fetchedAt: Long, val rows: List<Row>)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val catalogCache = ConcurrentHashMap<String, List<CatalogEntry>>()

    private fun baseUrl(source: SourceConfig): String =
        source.config.baseUrl?.trimEnd('/')
            ?: throw SourceUnavailable("${source.id} has no baseUrl", retryable = false)

    private fun extensions(source: SourceConfig, system: String): Set<String> =
        source.config.extensions[system]?.map { it.lowercase() }?.toSet()
            ?: throw SourceUnavailable("${source.id} does not serve $system", retryable = false)

    private fun extOf(filename: String): String =
        filename.substringAfterLast('.', "").lowercase()

    /** Whole index, cached for [INDEX_TTL_MS]. */
    private suspend fun rows(source: SourceConfig): List<Row> {
        val base = baseUrl(source)
        cache[base]?.let { if (clock() - it.fetchedAt < INDEX_TTL_MS) return it.rows }

        val rows = withContext(Dispatchers.IO) {
            val total = json(fetch("$base?action=list&limit=1")).getValue("total").jsonPrimitive.int
            (0 until total step FETCH_PAGE).map { offset ->
                async {
                    json(fetch("$base?action=list&offset=$offset&limit=$FETCH_PAGE"))["items"]
                        ?.jsonArray ?: JsonArray(emptyList())
                }
            }.awaitAll().flatMap { page ->
                page.mapNotNull { item ->
                    val obj = item.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val filename = obj["filename"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    Row(id, filename, obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
                }
            }
        }
        cache[base] = Cached(clock(), rows)
        // Derived catalogs are stale the moment the index is refetched.
        catalogCache.clear()
        return rows
    }

    /**
     * One row per base game. Its update and its DLC ride along in
     * [CatalogEntry.variants] instead of becoming rows of their own: a library
     * that lists "Witcher 3" five times is unreadable, and the detail screen can
     * offer the parts as choices.
     *
     * Files are grouped by title id, which is what actually ties them together:
     * the base ends in `000`, DLC in `001`-`7FF`, and an update is the base id +
     * `800`.
     */
    private suspend fun catalog(source: SourceConfig, system: String): List<CatalogEntry> {
        val key = "${source.id}:$system"
        catalogCache[key]?.let { return it }
        val wanted = extensions(source, system)

        val groups = LinkedHashMap<String, MutableList<Row>>()
        for (row in rows(source)) {
            if (extOf(row.filename) !in wanted) continue
            val low = row.filename.lowercase()
            if (PORT.containsMatchIn(low) || "homebrew" in low) continue
            groups.getOrPut(groupKeyOf(row.filename) ?: row.filename) { mutableListOf() } += row
        }

        val out = mutableListOf<CatalogEntry>()
        for ((groupKey, members) in groups) {
            val tid = groupKey.takeIf { TID_FULL.matches(it) }
            val parsed = members.map { it to parseSwitchFile(it.filename) }
            val baseTitle = parsed.firstOrNull { it.second.kind == KIND_BASE }?.second?.title
                ?: parsed.minByOrNull { it.second.title.length }?.second?.title
                ?: continue

            // One update per title id: the newest supersedes the rest.
            val update = parsed.filter { it.second.kind == KIND_UPDATE }
                .maxByOrNull { it.second.version }
                ?.let { (row, p) -> CatalogVariant(KIND_UPDATE, row.filename, row.size, p.version, row.id) }

            // Every DLC is a distinct add-on; none supersedes another. The same
            // payload is uploaded under several names, so dedupe by size.
            val dlcs = parsed.filter { it.second.kind == KIND_DLC }
                .distinctBy { it.first.size }
                .map { (row, p) -> CatalogVariant(KIND_DLC, row.filename, row.size, 0, row.id) }

            val bases = parsed.filter { it.second.kind == KIND_BASE }
            if (bases.isNotEmpty()) {
                bases.forEach { (row, p) ->
                    out += CatalogEntry(
                        system = system,
                        title = if (p.region == null) p.title else "${p.title} [${p.region}]",
                        filename = row.filename,
                        sizeBytes = row.size,
                        sourceRef = row.id,
                        kind = KIND_BASE,
                        titleId = tid,
                        variants = listOfNotNull(update) + dlcs,
                    )
                }
            } else {
                // No base game mirrored here, so the update/DLC must stand alone
                // rather than vanish with their group.
                listOfNotNull(update).forEach { v ->
                    out += CatalogEntry(
                        system = system,
                        title = "$baseTitle (update)",
                        filename = v.filename,
                        sizeBytes = v.sizeBytes,
                        revision = v.revision,
                        sourceRef = v.sourceRef,
                        kind = v.kind,
                        titleId = tid,
                    )
                }
                dlcs.forEach { v ->
                    out += CatalogEntry(
                        system = system,
                        title = "$baseTitle (dlc)",
                        filename = v.filename,
                        sizeBytes = v.sizeBytes,
                        sourceRef = v.sourceRef,
                        kind = v.kind,
                        titleId = tid,
                    )
                }
            }
        }

        // Base games: dedupe by name across title ids (a 29 GB dump and an 8 MB
        // stub both call themselves "Batman Arkham Knight"); the big one is real.
        val built = out
            .groupBy { normaliseTitle(TRAILING_REGION.replace(it.title, "")) to it.kind }
            .map { (_, same) -> same.maxBy { it.sizeBytes ?: 0L } }
            .map { it.copy(title = TRAILING_REGION.replace(it.title, "")) }
            .sortedWith(compareBy({ it.title.lowercase() }, { KIND_RANK[it.kind] ?: 0 }))
        catalogCache[key] = built
        return built
    }

    override suspend fun list(source: SourceConfig, system: String, page: Int): List<CatalogEntry> =
        catalog(source, system).drop(page * LIST_PAGE).take(LIST_PAGE)

    override suspend fun search(source: SourceConfig, system: String, query: String): List<CatalogEntry> {
        val tokens = query.lowercase().split(WHITESPACE).filter { it.isNotBlank() }
        return catalog(source, system)
            .filter { entry -> tokens.all { it in entry.title.lowercase() } }
            .take(SEARCH_LIMIT)
    }

    override suspend fun resolve(source: SourceConfig, entry: CatalogEntry): ResolvedDownload {
        val id = entry.sourceRef
            ?: throw SourceUnavailable("${entry.filename} has no dlarchive id", retryable = false)
        val body = withContext(Dispatchers.IO) { fetch("${baseUrl(source)}?action=file-url&id=$id") }
        val obj = json(body)
        if (obj["ok"]?.jsonPrimitive?.content != "true") {
            throw SourceUnavailable(obj["error"]?.jsonPrimitive?.content ?: "link unavailable")
        }
        val url = obj["url"]?.jsonPrimitive?.content
            ?: throw SourceUnavailable("link unavailable", retryable = false)
        return ResolvedDownload.Direct(
            url = url,
            // The server's filename is authoritative: it is what lands on disk.
            filename = obj["filename"]?.jsonPrimitive?.content ?: entry.filename,
            size = entry.sizeBytes,
            hashes = Hashes(), // dlarchive publishes no checksums
        )
    }

    internal data class SwitchFile(
        val kind: String,
        val title: String,
        val version: Int,
        val region: String?,
    )

    /**
     * A Switch file's role comes from its title id: the base ends in `000`, DLC
     * uses `001`-`7FF`, and an update is the base id + `800`. The last three hex
     * digits of the `[0100...]` tag decide it. Untagged files fall back to the
     * uploader's text markers.
     */
    internal fun parseSwitchFile(filename: String): SwitchFile {
        val low = filename.lowercase()
        val tid = titleIdOf(filename)?.uppercase()
        val version = VERSION_TAG.find(filename)?.groupValues?.get(1)?.toIntOrNull()
            ?: RAW_VERSION.find(low)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val kind = when {
            tid != null && tid.endsWith("000") -> KIND_BASE
            tid != null && tid.endsWith("800") -> KIND_UPDATE
            tid != null -> KIND_DLC
            "dlc" in low -> KIND_DLC
            "update" in low || UPDATE_TAG.containsMatchIn(low) -> KIND_UPDATE
            else -> KIND_BASE
        }
        return SwitchFile(kind, cleanTitle(filename) ?: filename, version, regionOf(filename))
    }

    internal fun titleIdOf(filename: String): String? =
        TID.find(filename)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /**
     * The 12 hex digits a base game shares with its update and DLC. Only the last
     * four differ: base ends `?000`, an update is base + 0x800 (`?800`), and DLC
     * ids sit one nibble up (`(base+0x1000) | index`). So 01007EF00011E000,
     * ...11E800 and ...11F001 are one game.
     */
    internal fun groupKeyOf(filename: String): String? =
        titleIdOf(filename)?.take(12)?.uppercase()

    /** Filename minus tags, extension and size/version noise. */
    internal fun cleanTitle(filename: String): String? {
        val base = filename.substringBeforeLast('.', filename)
        val title = BRACKET.replace(base, " ")
            .replace(SIZE_PAREN, " ")
            .replace(VERSION_PAREN, " ")
            .replace(WHITESPACE, " ")
            .trim().trimEnd('-', '_', ' ').trim()
        return title.ifBlank { null }
    }

    internal fun regionOf(filename: String): String? =
        BRACKET.findAll(filename).firstNotNullOfOrNull { m ->
            REGION_ALIASES[m.groupValues[1].trim().uppercase()]
        }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val response = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute()
        response.use {
            if (!it.isSuccessful) {
                throw SourceUnavailable("HTTP ${it.code} for $url", retryable = it.code >= 500 || it.code == 429)
            }
            it.body.string()
        }
    }

    private companion object {
        const val UA = "caddy/0.1"
        const val FETCH_PAGE = 200
        const val LIST_PAGE = 300
        const val SEARCH_LIMIT = 200
        const val INDEX_TTL_MS = 12 * 60 * 60 * 1000L
        val TID = Regex("\\[(0100[0-9A-Fa-f]{12})\\]")
        val TID_FULL = Regex("^0100[0-9A-Fa-f]{12}$")
        val BRACKET = Regex("\\[([^\\]]*)\\]")
        val TRAILING_REGION = Regex("\\s*\\[[A-Za-z ]+\\]$")
        val SIZE_PAREN = Regex("\\(\\s*[0-9.]+\\s*(?:GB|MB)\\s*\\)", RegexOption.IGNORE_CASE)
        val VERSION_PAREN = Regex("\\(\\s*(?:[Uu]?[vV]?\\d+(?:\\.\\d+)*)\\s*\\)")
        val VERSION_TAG = Regex("\\[v(\\d+)\\]", RegexOption.IGNORE_CASE)
        val RAW_VERSION = Regex("\\bv(\\d{2,})\\b")
        val PORT = Regex("\\bports?\\b")
        val UPDATE_TAG = Regex("\\[up[ .]")
        val WHITESPACE = Regex("\\s+")
        val KIND_RANK = mapOf(KIND_BASE to 0, KIND_UPDATE to 1, KIND_DLC to 2)
        /** Uploader region tags -> the spellings the catalog ranks. */
        val REGION_ALIASES = mapOf(
            "US" to "USA", "USA" to "USA", "EUR" to "Europe", "EU" to "Europe",
            "JPN" to "Japan", "JP" to "Japan", "JAP" to "Japan", "ASIA" to "Asia",
            "KOR" to "Korea", "CHN" to "China", "TWN" to "Taiwan", "HK" to "Hong Kong",
            "UK" to "UK", "GBR" to "UK", "FRA" to "France", "GER" to "Germany",
            "DEU" to "Germany", "ESP" to "Spain", "ITA" to "Italy", "NLD" to "Netherlands",
            "RUS" to "Russia", "BRA" to "Brazil", "AUS" to "Australia", "CAN" to "Canada",
            "POL" to "Poland", "POR" to "Portugal", "MEX" to "Mexico", "WORLD" to "World",
        )
    }
}
