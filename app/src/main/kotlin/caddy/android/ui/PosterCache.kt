package caddy.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

object LibretroPlatform {

    private val MAP = mapOf(
        "gba" to "Nintendo - Game Boy Advance",
        "gb" to "Nintendo - Game Boy",
        "gbc" to "Nintendo - Game Boy Color",
        "nes" to "Nintendo - Nintendo Entertainment System",
        "snes" to "Nintendo - Super Nintendo Entertainment System",
        "n64" to "Nintendo - Nintendo 64",
        "nds" to "Nintendo - Nintendo DS",
        "genesis" to "Sega - Mega Drive - Genesis",
        "gen" to "Sega - Mega Drive - Genesis",
        "megadrive" to "Sega - Mega Drive - Genesis",
        "mastersystem" to "Sega - Master System - Mark III",
        "gamegear" to "Sega - Game Gear",
        "psx" to "Sony - PlayStation",
        "ps2" to "Sony - PlayStation 2",
        "psp" to "Sony - PlayStation Portable",
        "saturn" to "Sega - Saturn",
        "dreamcast" to "Sega - Dreamcast",
        "neogeo" to "SNK - Neo Geo",
        "tg16" to "NEC - PC Engine - TurboGrafx 16",
        "pce" to "NEC - PC Engine - TurboGrafx 16",
    )

    fun folder(systemId: String): String {
        return MAP[systemId.lowercase()] ?: systemId
    }

    fun cleanName(filename: String): String {
        val dot = filename.lastIndexOf('.')
        return if (dot > 0) filename.substring(0, dot) else filename
    }

    fun thumbnailUrl(systemId: String, filename: String): String {
        val platform = folder(systemId)
        val name = cleanName(filename)
        val encPlatform = URLEncoder.encode(platform, "UTF-8").replace("+", "%20")
        val encName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "https://thumbnails.libretro.com/$encPlatform/Named_Boxarts/$encName.png"
    }
}

class PosterCache(context: Context, private val http: OkHttpClient) {
    private val diskDir = File(context.cacheDir, "posters").apply { mkdirs() }
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024)

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    suspend fun getPoster(
        systemId: String,
        filename: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val url = LibretroPlatform.thumbnailUrl(systemId, filename)
        val key = Integer.toHexString(url.hashCode())

        // 1. Check memory cache
        memoryCache.get(key)?.let { return@withContext it }

        // 2. Check disk cache
        val diskFile = File(diskDir, "$key.png")
        if (diskFile.exists()) {
            val bitmap = decodeSampledBitmap(diskFile.absolutePath, targetWidth, targetHeight)
            if (bitmap != null) {
                memoryCache.put(key, bitmap)
                return@withContext bitmap
            }
        }

        // 3. Network fetch
        try {
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null

                // Write to disk
                FileOutputStream(diskFile).use { it.write(bytes) }

                // Decode downsampled
                val bitmap = decodeSampledBitmap(diskFile.absolutePath, targetWidth, targetHeight)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                }
                return@withContext bitmap
            }
        } catch (_: Exception) {
            return@withContext null
        }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)

        var inSampleSize = 1
        if (reqHeight > 0 && reqWidth > 0 && (options.outHeight > reqHeight || options.outWidth > reqWidth)) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        return BitmapFactory.decodeFile(path, options)
    }
}
