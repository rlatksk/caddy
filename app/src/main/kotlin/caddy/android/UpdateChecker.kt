package caddy.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** What the update endpoint publishes. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String?,
)

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

/**
 * Self-update, the only way a sideloaded app can be fixed for its users.
 *
 * The load-bearing check is not a signature over versions.json: it is the APK's
 * own signing certificate. A swapped manifest can only point at an APK signed
 * with the same key, because the system installer refuses anything else. So this
 * verifies SHA-256 against the manifest and then verifies the downloaded APK's
 * certificate equals the installed app's, before offering it to the installer.
 */
class UpdateChecker(
    private val context: Context,
    private val manifestUrl: String = RepoStore(context).updateUrl,
) {

    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val body = fetch(manifestUrl)
            val root = json.parseToJsonElement(body).jsonObject
            val info = UpdateInfo(
                versionCode = root["versionCode"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: return@withContext UpdateResult.Failed("versions.json has no versionCode"),
                versionName = root["versionName"]?.jsonPrimitive?.content ?: "?",
                apkUrl = root["apkUrl"]?.jsonPrimitive?.content
                    ?: return@withContext UpdateResult.Failed("versions.json has no apkUrl"),
                sha256 = root["sha256"]?.jsonPrimitive?.content
                    ?: return@withContext UpdateResult.Failed("versions.json has no sha256"),
                notes = root["notes"]?.jsonPrimitive?.content,
            )
            if (info.versionCode <= BuildConfig.VERSION_CODE) return@withContext UpdateResult.UpToDate

            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apk = File(dir, "caddy-${info.versionName}.apk")
            download(info.apkUrl, apk)

            val actual = sha256(apk)
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                apk.delete()
                return@withContext UpdateResult.Failed("checksum mismatch: got $actual")
            }
            if (!signedLikeInstalled(apk)) {
                apk.delete()
                return@withContext UpdateResult.Failed("update is signed with a different key")
            }
            UpdateResult.Ready(info, apk)
        } catch (e: Exception) {
            UpdateResult.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    /** Hands the APK to the system installer. It re-checks the signature. */
    fun install(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for the update")
            target.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            return response.body.string()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Certificates as hex strings, not ByteArrays: arrays compare by identity, so
     * two equal signatures would never match.
     */
    private fun signedLikeInstalled(apk: File): Boolean {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        fun signers(info: android.content.pm.PackageInfo?): Set<String> =
            info?.signingInfo?.apkContentsSigners
                ?.map { it.toByteArray().joinToString("") { b -> "%02x".format(b) } }
                ?.toSet()
                .orEmpty()

        val installed = signers(context.packageManager.getPackageInfo(context.packageName, flags))
        if (installed.isEmpty()) return false
        val candidate = signers(context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags))
        return installed == candidate
    }

    private companion object {
        const val UA = "caddy/0.1"
    }
}
