package caddy

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.CRC32
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Resume, atomic publish and no-clobber. These are the failure modes that ship a
 * truncated ROM to a final path where ES-DE will happily try to launch it.
 */
class HttpDownloaderTest {

    private lateinit var server: HttpServer
    private val payload = ByteArray(64 * 1024) { (it * 31 % 251).toByte() }
    private var rangeRequests = 0

    private val crc: String = CRC32().apply { update(payload) }.value.let { "%08x".format(it) }
    private val sha1: String = java.security.MessageDigest.getInstance("SHA-1")
        .digest(payload).joinToString("") { "%02x".format(it) }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rom.bin") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null) {
                rangeRequests++
                val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                val slice = payload.copyOfRange(start, payload.size)
                exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
            } else {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun direct(hashes: Hashes = Hashes(sha1 = sha1, crc32 = crc)) = ResolvedDownload.Direct(
        url = "http://127.0.0.1:${server.address.port}/rom.bin",
        filename = "rom.bin",
        size = payload.size.toLong(),
        hashes = hashes,
    )

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("caddy").toFile()

    @Test
    fun `downloads and verifies`() {
        val dir = tempDir()
        val file = HttpDownloader(OkHttpClient()).download(direct(), File(dir, "rom.bin"))
        assertEquals(payload.size.toLong(), file.length())
        assertEquals(VerifyResult.Verified, verify(file, direct().hashes))
        assertTrue(!File(dir, "rom.bin.part").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `resumes from an existing part file with a range request`() {
        val dir = tempDir()
        val half = payload.size / 2
        File(dir, "rom.bin.part").writeBytes(payload.copyOfRange(0, half))

        val file = HttpDownloader(OkHttpClient()).download(direct(), File(dir, "rom.bin"))

        assertEquals(1, rangeRequests)
        assertEquals(payload.size.toLong(), file.length())
        assertTrue(file.readBytes().contentEquals(payload))
        dir.deleteRecursively()
    }

    @Test
    fun `hash mismatch deletes the part and publishes nothing`() {
        val dir = tempDir()
        val bad = direct(Hashes(sha1 = "0".repeat(40)))
        val error = runCatching { HttpDownloader(OkHttpClient()).download(bad, File(dir, "rom.bin")) }
            .exceptionOrNull()
        assertTrue(error is SourceUnavailable)
        assertTrue(!File(dir, "rom.bin").exists())
        assertTrue(!File(dir, "rom.bin.part").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `existing file is never overwritten`() {
        val dir = tempDir()
        File(dir, "rom.bin").writeText("user's own copy")
        val file = HttpDownloader(OkHttpClient()).download(direct(), File(dir, "rom.bin"))
        assertEquals("rom (2).bin", file.name)
        assertEquals("user's own copy", File(dir, "rom.bin").readText())
        dir.deleteRecursively()
    }
}
