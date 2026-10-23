package caddy

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32

/** SHA-1 / CRC32 streaming verification. Both in one pass. */
class FileDigest(val sha1: String, val crc32: String, val size: Long)

fun digest(file: File): FileDigest {
    val sha1 = MessageDigest.getInstance("SHA-1")
    val crc = CRC32()
    val buffer = ByteArray(1 shl 16)
    var size = 0L
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            sha1.update(buffer, 0, read)
            crc.update(buffer, 0, read)
            size += read
        }
    }
    return FileDigest(sha1.digest().joinToString("") { "%02x".format(it) }, "%08x".format(crc.value), size)
}

sealed interface VerifyResult {
    data object Verified : VerifyResult
    data class Mismatch(val reason: String) : VerifyResult
    data class Unverified(val reason: String) : VerifyResult
}

fun verify(file: File, hashes: Hashes): VerifyResult {
    if (hashes.sha1 == null && hashes.crc32 == null) {
        return VerifyResult.Unverified("source published no hashes")
    }
    val actual = digest(file)
    hashes.sha1?.let {
        if (!it.equals(actual.sha1, ignoreCase = true)) {
            return VerifyResult.Mismatch("SHA-1 expected $it, got ${actual.sha1}")
        }
    }
    hashes.crc32?.let {
        if (!it.equals(actual.crc32, ignoreCase = true)) {
            return VerifyResult.Mismatch("CRC32 expected $it, got ${actual.crc32}")
        }
    }
    return VerifyResult.Verified
}

/**
 * HTTP download with resume. Writes <name>.part, verifies, then moves to the
 * final name. Never publishes unverified bytes at the final path: a truncated
 * ROM at ROMs/<system>/ looks fine to ES-DE and fails at launch.
 */
class HttpDownloader(private val http: OkHttpClient) {

    /**
     * Resumable download with stall recovery. Large files on these mirrors stall
     * mid-transfer (the server stops sending, OkHttp times out); each attempt
     * resumes from the bytes already on disk, so a stall costs one read timeout
     * instead of the whole download. Gives up after [MAX_STALLS] attempts that
     * made no progress at all.
     */
    fun download(direct: ResolvedDownload.Direct, destination: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        var stalls = 0
        while (true) {
            val before = if (part.exists()) part.length() else 0L
            try {
                return attempt(direct, destination, part, onProgress)
            } catch (e: Exception) {
                val retryable = when (e) {
                    is SourceUnavailable -> e.retryable
                    is IOException -> true
                    else -> false
                }
                if (!retryable) throw e
                val after = if (part.exists()) part.length() else 0L
                stalls = if (after > before) 0 else stalls + 1
                if (stalls >= MAX_STALLS) {
                    throw SourceUnavailable("download stalled at $after bytes: ${e.message}", retryable = true)
                }
                Thread.sleep(1000L * stalls)
            }
        }
    }

    private fun attempt(
        direct: ResolvedDownload.Direct,
        destination: File,
        part: File,
        onProgress: (Long, Long) -> Unit,
    ): File {
        var offset = if (part.exists()) part.length() else 0L

        val request = Request.Builder()
            .url(direct.url)
            .header("User-Agent", UA)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SourceUnavailable("HTTP ${response.code} for ${direct.url}", retryable = response.code >= 500 || response.code == 429)
            }
            // Server ignored the Range header: restart rather than append.
            if (offset > 0 && response.code == 200) {
                part.delete()
                offset = 0
            }
            val total = response.body.contentLength().let { if (it > 0) it + offset else direct.size ?: -1 }
            RandomAccessFile(part, "rw").use { out ->
                out.seek(offset)
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var done = offset
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                    out.fd.sync()
                }
            }
        }

        when (val result = verify(part, direct.hashes)) {
            is VerifyResult.Mismatch -> {
                part.delete()
                throw SourceUnavailable(result.reason, retryable = false)
            }
            else -> Unit
        }

        val target = noClobber(destination)
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return target
    }

    /** Identical file already present -> keep it; different -> keep both with a suffix. */
    private fun noClobber(destination: File): File {
        if (!destination.exists()) return destination
        val base = destination.nameWithoutExtension
        val ext = destination.extension.let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (true) {
            val candidate = File(destination.parentFile, "$base ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    private companion object {
        const val UA = "caddy/0.1"
        const val MAX_STALLS = 6
    }
}
