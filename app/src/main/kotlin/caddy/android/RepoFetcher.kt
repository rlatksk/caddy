package caddy.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import caddy.RepoIndex
import caddy.SCHEMA_VERSION
import caddy.parseIndex

/** What went wrong, in the words the user sees. */
sealed interface RepoError {
    data object NotAUrl : RepoError
    data object GitHubPageNotRaw : RepoError
    data class Http(val code: Int) : RepoError
    data class Network(val message: String) : RepoError
    data class NotJson(val message: String) : RepoError
    data class NoSources(val message: String) : RepoError
    data class SchemaTooNew(val version: Int) : RepoError
    data object AlreadyAdded : RepoError
    data class InsecureUrl(val host: String) : RepoError
    data object ProtobufIndex : RepoError
}

/**
 * Fetches and validates a repository index.
 *
 * Error taxonomy follows Mihon's: specific and human, because "failed to load"
 * tells the user nothing about whether their URL is wrong or their Wi-Fi is down.
 */
class RepoFetcher(private val store: RepoStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Accepts a full URL, a bare host/path, a GitHub blob URL (rewritten to raw),
     * or a caddy:// deep link. Rejects a GitHub *repo page* with a clear message:
     * that is a web page, not an index.
     */
    fun normalise(input: String): Result<String> {
        val text = input.trim()
        if (text.isEmpty()) return Result.failure(RepoException(RepoError.NotAUrl))

        val withScheme = when {
            text.startsWith("caddy://add-repo") -> {
                val url = text.substringAfter("url=", "")
                if (url.isEmpty()) return Result.failure(RepoException(RepoError.NotAUrl))
                url
            }
            text.startsWith("http://") || text.startsWith("https://") -> text
            // A bare LAN address is a self-hosted repo, and self-hosting with a
            // certificate is unreasonable to require. Anything else gets HTTPS.
            text.contains('/') && isPrivateHost(text.substringBefore('/').substringBefore(':')) ->
                "http://$text"
            text.contains('/') -> "https://$text"
            else -> return Result.failure(RepoException(RepoError.NotAUrl))
        }

        // github.com/<owner>/<repo>/blob/<ref>/<path> -> raw.githubusercontent.com/...
        if (withScheme.startsWith("https://github.com/")) {
            val parts = withScheme.removePrefix("https://github.com/").split('/')
            if (parts.size >= 5 && parts[2] == "blob") {
                val owner = parts[0]
                val repo = parts[1]
                val rest = parts.drop(3).joinToString("/")
                return Result.success("https://raw.githubusercontent.com/$owner/$repo/$rest")
            }
            return Result.failure(RepoException(RepoError.GitHubPageNotRaw))
        }

        // A bare host with no path is a web page, not an index.
        val afterHost = withScheme.substringAfter("://").substringAfter('/', "")
        if (afterHost.isEmpty()) return Result.failure(RepoException(RepoError.NotAUrl))

        if (!isAllowedTransport(withScheme)) {
            return Result.failure(RepoException(RepoError.InsecureUrl(withScheme.substringBefore('/').substringAfter("://"))))
        }

        return Result.success(withScheme)
    }

    suspend fun fetch(input: String): Result<String> = withContext(Dispatchers.IO) {
        val url = normalise(input).getOrElse { return@withContext Result.failure(it) }

        if (store.indexUrl == url && store.indexJson != null) {
            return@withContext Result.failure(RepoException(RepoError.AlreadyAdded))
        }

        val body = try {
            val response = http.newCall(
                Request.Builder().url(url).header("User-Agent", "caddy/0.1").build()
            ).execute()
            response.use {
                if (!it.isSuccessful) {
                    return@withContext Result.failure(RepoException(RepoError.Http(it.code)))
                }
                it.body.string()
            }
        } catch (e: Exception) {
            return@withContext Result.failure(
                RepoException(RepoError.Network(e.message ?: e::class.java.simpleName))
            )
        }

        // Mihon's official repo publishes protobuf only. Detecting it is cheap;
        // parsing it is not, and there is no protobuf schema for a retro repo, so
        // a generic decoder would be guessing at field numbers. Say so instead of
        // reporting "invalid JSON" for a file that is not JSON at all.
        if (body.isNotEmpty() && body[0].code !in 0x20..0x7E && !body.trimStart().startsWith("{")) {
            return@withContext Result.failure(RepoException(RepoError.ProtobufIndex))
        }

        // Validate before storing: an index that yields zero usable sources is
        // not kept, so the app never sits in a half-configured state.
        val parsed = try {
            parseIndexChecked(body)
        } catch (e: RepoException) {
            return@withContext Result.failure(e)
        } catch (e: Exception) {
            return@withContext Result.failure(
                RepoException(RepoError.NotJson(e.message ?: "unreadable JSON"))
            )
        }

        if (parsed.sources.isEmpty()) {
            return@withContext Result.failure(
                RepoException(RepoError.NoSources("no sources this app understands"))
            )
        }

        store.indexJson = body
        store.indexUrl = url
        store.lastFetch = System.currentTimeMillis()
        Result.success(body)
    }

    private fun parseIndexChecked(body: String): RepoIndex {
        val root = json.parseToJsonElement(body).jsonObject
        val version = root["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        if (version > SCHEMA_VERSION) {
            throw RepoException(RepoError.SchemaTooNew(version))
        }
        return parseIndex(body, RepoStore.KNOWN_ADAPTERS)
    }
}

class RepoException(val error: RepoError) : Exception()

/**
 * HTTPS everywhere, except private LAN addresses.
 *
 * A repository is untrusted input from a stranger, and a source's config decides
 * where bytes come from. Allowing plain HTTP to the open internet would let a
 * repo redirect a download through a host that can rewrite it in transit.
 * Self-hosting on a LAN is the one case where TLS is unreasonable to require.
 */
fun isAllowedTransport(url: String): Boolean {
    if (url.startsWith("https://")) return true
    if (!url.startsWith("http://")) return false
    val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
    return isPrivateHost(host)
}

fun isPrivateHost(host: String): Boolean {
    if (host == "localhost") return true
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
    val (a, b) = parts[0].toInt() to parts[1].toInt()
    return a == 10 ||
        a == 127 ||
        (a == 192 && b == 168) ||
        (a == 172 && b in 16..31) ||
        (a == 169 && b == 254)
}

/** Human wording for each failure. These strings are the whole UX of this screen. */
fun RepoError.message(): String = when (this) {
    RepoError.NotAUrl -> "That doesn't look like a repository address."
    RepoError.GitHubPageNotRaw -> "That is a GitHub page, not the repository itself."
    is RepoError.Http -> when (code) {
        404 -> "Not found (404). Check the URL points at the index file."
        403 -> "Access denied (403). The file may be private."
        in 500..599 -> "The server is having trouble ($code). Try again later."
        else -> "Request failed (HTTP $code)."
    }
    is RepoError.Network -> "Can't reach that address: $message"
    is RepoError.NotJson -> "That file isn't valid JSON: $message"
    is RepoError.NoSources -> "That address gave no sources, so it was not kept. $message"
    is RepoError.SchemaTooNew -> "That repository needs a newer version of Caddy (schema $version)."
    RepoError.AlreadyAdded -> "That repository is already added."
    is RepoError.InsecureUrl -> "That address uses plain HTTP. Use HTTPS, or a private LAN address."
    RepoError.ProtobufIndex ->
        "That is a protobuf index (index.pb). Caddy reads JSON indexes only: " +
            "ask the repo for its index.json."
}
