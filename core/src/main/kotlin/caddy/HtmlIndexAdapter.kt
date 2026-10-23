package caddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * Declarative HTML adapter: a repo supplies the page and the selectors, and this
 * class is the only code involved. That is Mangayomi's `typeSource` idea — a new
 * scraped site is a JSON object, not a release.
 *
 * The listing page yields items; each item's download link is followed on
 * resolve. A source with no server-side search gets a client-side filter, which
 * is honest about the cost: the listing is fetched once and cached briefly.
 */
open class HtmlIndexAdapter(
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : SourceAdapter {

    override fun supports(system: String) = true

    private class Cached(val fetchedAt: Long, val entries: List<CatalogEntry>)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    private fun spec(source: SourceConfig, system: String): ListingSpec =
        source.config.listings[system]
            ?: throw SourceUnavailable("${source.id} does not serve $system", retryable = false)

    override suspend fun list(source: SourceConfig, system: String, page: Int): List<CatalogEntry> {
        val all = entries(source, system)
        return all.drop(page * PAGE_SIZE).take(PAGE_SIZE)
    }

    override suspend fun search(source: SourceConfig, system: String, query: String): List<CatalogEntry> {
        val tokens = query.lowercase().split(WHITESPACE).filter { it.isNotBlank() }
        return entries(source, system).filter { entry ->
            tokens.all { it in entry.title.lowercase() }
        }.take(SEARCH_LIMIT)
    }

    private suspend fun entries(source: SourceConfig, system: String): List<CatalogEntry> {
        val key = "${source.id}:$system"
        cache[key]?.let { if (clock() - it.fetchedAt < TTL_MS) return it.entries }

        val spec = spec(source, system)
        val doc = parse(fetch(spec.url), spec.url)
        val rows = doc.select(spec.itemSelector)
        val entries = rows.mapNotNull { row -> entryFrom(row, spec, system) }
        cache[key] = Cached(clock(), entries)
        return entries
    }

    private fun entryFrom(row: Element, spec: ListingSpec, system: String): CatalogEntry? {
        val titleEl = row.selectFirst(spec.titleSelector) ?: return null
        // An image-only card carries its name in alt/title, not text.
        val title = titleEl.text().trim()
            .ifBlank { titleEl.attr("alt").trim() }
            .ifBlank { titleEl.attr("title").trim() }
            .ifBlank { null }
            ?: return null
        // The download selector is either the row's own link or a link inside it.
        // The selector may point at the link, at something inside it, or at the
        // card itself; a repo should not have to know which shape it chose.
        val picked = row.selectFirst(spec.downloadSelector)
        val linkEl = when {
            picked != null && picked.tagName() == "a" -> picked
            row.tagName() == "a" -> row
            else -> picked?.selectFirst("a") ?: row.selectFirst("a")
        } ?: return null
        val link = linkEl.absUrl("href").ifBlank { linkEl.attr("href") }
        if (link.isBlank()) return null
        val size = spec.sizeSelector?.let { sel ->
            row.selectFirst(sel)?.text()?.let(::parseSize)
        }
        return CatalogEntry(
            system = system,
            title = title,
            // The filename is only known once the link is followed, so the URL
            // doubles as the transport payload and the handle.
            filename = fileNameOf(link, title),
            sizeBytes = size,
            sourceRef = link,
        )
    }

    override suspend fun resolve(source: SourceConfig, entry: CatalogEntry): ResolvedDownload {
        val pageUrl = entry.sourceRef
            ?: throw SourceUnavailable("${entry.filename} has no link", retryable = false)

        // A link that is already a file needs no second request.
        if (looksLikeFile(pageUrl)) {
            return ResolvedDownload.Direct(
                url = pageUrl,
                filename = fileNameOf(pageUrl, entry.title),
                size = entry.sizeBytes,
                hashes = Hashes(),
            )
        }

        val spec = spec(source, entry.system)
        val doc = parse(fetch(pageUrl), pageUrl)
        fun hrefOf(anchor: Element): String =
            anchor.absUrl("href").ifBlank { anchor.attr("href") }

        val candidate = doc.select(spec.downloadSelector)
            .map(::hrefOf)
            .firstOrNull(::looksLikeFile)
            ?: doc.select("a[href]").map(::hrefOf).firstOrNull(::looksLikeFile)
            ?: throw SourceUnavailable("no file link on $pageUrl", retryable = false)

        return ResolvedDownload.Direct(
            url = candidate,
            filename = fileNameOf(candidate, entry.title),
            size = entry.sizeBytes,
            hashes = Hashes(),
        )
    }

    /** The page URL is the base: repos write relative hrefs, and absUrl needs one. */
    private fun parse(html: String, baseUrl: String): Document = Jsoup.parse(html, baseUrl)

    /** Overridden in tests to serve fixture HTML without the network. */
    internal open suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
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
        const val PAGE_SIZE = 300
        const val SEARCH_LIMIT = 200
        const val TTL_MS = 10 * 60 * 1000L
        val WHITESPACE = Regex("\\s+")
        val FILE_EXT = Regex(
            "\\.(zip|7z|rar|nsp|xci|nsz|iso|cia|gba|gbc|gb|nds|nes|sfc|smc|n64|z64|" +
                "md|gen|sms|gg|bin|cue|chd|rvz|wbfs|pbp|cso|pkg|rom)$",
            RegexOption.IGNORE_CASE,
        )
        val SIZE = Regex("([0-9.]+)\\s*(B|KB|MB|GB|TB)", RegexOption.IGNORE_CASE)

        fun looksLikeFile(url: String): Boolean = FILE_EXT.containsMatchIn(url.substringBefore('?'))

        fun fileNameOf(url: String, fallback: String): String {
            val raw = url.substringBefore('?').trimEnd('/')
            val name = URI(raw).path.substringAfterLast('/')
            if (name.isBlank() || !name.contains('.')) return fallback
            return try {
                java.net.URLDecoder.decode(name, "UTF-8")
            } catch (_: Exception) {
                name
            }
        }

        fun parseSize(text: String): Long? {
            val m = SIZE.find(text) ?: return null
            val value = m.groupValues[1].toDoubleOrNull() ?: return null
            val unit = when (m.groupValues[2].uppercase()) {
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
