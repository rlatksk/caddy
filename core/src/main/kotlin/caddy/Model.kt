package caddy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val KIND_BASE = "base"
const val KIND_UPDATE = "update"
const val KIND_DLC = "dlc"

/** Bump when the contract changes. Older apps must skip, never fail. */
const val SCHEMA_VERSION = 1

@Serializable
data class RepoIndex(
    val schemaVersion: Int,
    val name: String,
    val homepage: String? = null,
    val sources: List<SourceConfig>,
)

@Serializable
data class SourceConfig(
    val id: String,
    val name: String,
    val version: String? = null,
    val appMinVerReq: String? = null,
    val transport: Transport,
    val adapter: String,
    val systems: List<String>,
    val config: SourceParams,
)

@Serializable
enum class Transport { @SerialName("http") HTTP, @SerialName("torrent") TORRENT, @SerialName("browser") BROWSER }

/**
 * Union of every adapter's config block. One class, not a polymorphic hierarchy:
 * an unknown adapter type is skipped at registry time, so there is nothing to
 * deserialize polymorphically.
 */
@Serializable
data class SourceParams(
    val collections: Map<String, String> = emptyMap(),
    val baseUrl: String? = null,
    val paths: Map<String, String> = emptyMap(),
    val captureHost: String? = null,
    val pages: Map<String, String> = emptyMap(),
    /** system -> file extensions that identify it on sources with no platform field. */
    val extensions: Map<String, List<String>> = emptyMap(),
    /** system -> selectors, for the declarative html_index adapter. */
    val listings: Map<String, ListingSpec> = emptyMap(),
    /** Optional selectors for browser_capture, so its listing is not site chrome. */
    val itemSelector: String? = null,
    val titleSelector: String? = null,
)

/**
 * One listing page and the selectors that read it. A repo author writes these;
 * no per-site code exists, which is the whole point of the declarative model.
 */
@Serializable
data class ListingSpec(
    val url: String,
    val itemSelector: String,
    val titleSelector: String,
    val downloadSelector: String,
    val sizeSelector: String? = null,
    val nextPageSelector: String? = null,
)

@Serializable
data class CatalogEntry(
    val system: String,
    val title: String,
    val filename: String,
    val sizeBytes: Long? = null,
    val region: String? = null,
    val revision: Int = 0,
    val disc: Int = 0,
    val junkTags: List<String> = emptyList(),
    /** Index of this file inside its torrent, when the source delivers by torrent. */
    val torrentFileIndex: Int? = null,
    /**
     * Source-native handle used to resolve the download, when the source's own id
     * is not the filename (dlarchive). Null means "the filename is the handle".
     */
    val sourceRef: String? = null,
    /** `base`, `update` or `dlc`. Switch ships all three as separate files. */
    val kind: String = KIND_BASE,
    /** Platform title id, so an update/DLC can be tied to its base game. */
    val titleId: String? = null,
    /**
     * Extra files belonging to this game: its update and its DLC. Not separate
     * catalog rows, because a library that lists "Witcher 3" five times is
     * unreadable; the detail screen offers them as choices.
     */
    val variants: List<CatalogVariant> = emptyList(),
)

/** One downloadable file belonging to a base game. */
@Serializable
data class CatalogVariant(
    val kind: String,
    val filename: String,
    val sizeBytes: Long? = null,
    val revision: Int = 0,
    val sourceRef: String? = null,
)

@Serializable
data class Hashes(val sha1: String? = null, val sha256: String? = null, val crc32: String? = null)

/** Transport is a property of the resolved candidate, never discovered later. */
sealed interface ResolvedDownload {
    data class Direct(val url: String, val filename: String, val size: Long?, val hashes: Hashes) : ResolvedDownload
    /**
     * One file out of a collection torrent. [fileIndex] is what the torrent
     * engine sets to priority; downloading a whole 14.86 GB collection to get a
     * 5 MB ROM is not an option.
     */
    data class Torrent(
        val magnet: String,
        val fileIndex: Int,
        val filename: String,
        val size: Long?,
    ) : ResolvedDownload
    data class NeedsCapture(val pageUrl: String, val captureHost: String) : ResolvedDownload
}

class SourceUnavailable(message: String, val retryable: Boolean = true) : Exception(message)

/** A variant as a standalone entry, so adapters resolve it exactly like a base game. */
fun CatalogEntry.asVariant(variant: CatalogVariant): CatalogEntry = copy(
    title = title,
    filename = variant.filename,
    sizeBytes = variant.sizeBytes,
    revision = variant.revision,
    sourceRef = variant.sourceRef,
    kind = variant.kind,
    variants = emptyList(),
)

interface SourceAdapter {
    fun supports(system: String): Boolean
    suspend fun list(source: SourceConfig, system: String, page: Int): List<CatalogEntry>
    suspend fun search(source: SourceConfig, system: String, query: String): List<CatalogEntry>
    suspend fun resolve(source: SourceConfig, entry: CatalogEntry): ResolvedDownload
}

val indexJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/**
 * Parses an index. Unknown adapter types are dropped, not fatal (FR-2.4), so a
 * repo written for a newer app still loads here.
 */
fun parseIndex(text: String, knownAdapters: Set<String>): RepoIndex {
    val parsed = indexJson.decodeFromString<RepoIndex>(text)
    if (parsed.schemaVersion > SCHEMA_VERSION) {
        throw IllegalArgumentException(
            "repo requires schemaVersion ${parsed.schemaVersion}, this app supports $SCHEMA_VERSION")
    }
    return parsed.copy(sources = parsed.sources.filter { it.adapter in knownAdapters })
}

/**
 * ES-DE routing. Verified against es_systems.xml: <name> equals <path> for all
 * 195 systems, so the system name IS the folder name. No mapping table.
 */
object SystemRouter {
    fun relativePath(system: String): String {
        require(SYSTEM_NAME.matches(system)) { "not an ES-DE system name: $system" }
        return system
    }

    private val SYSTEM_NAME = Regex("^[a-z0-9]+$")
}
