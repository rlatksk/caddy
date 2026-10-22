package caddy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

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
