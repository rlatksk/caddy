package caddy

import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parses a real Minerva GBA page (trimmed to 23 entries, indices preserved) and
 * checks the two things the torrent engine depends on: the magnet, and the
 * per-row file index.
 */
class MinervaAdapterTest {

    private val html: String by lazy {
        javaClass.getResourceAsStream("/minerva-gba.html")!!.bufferedReader().readText()
    }

    private fun source() = SourceConfig(
        id = "minerva",
        name = "Minerva",
        transport = Transport.TORRENT,
        adapter = "minerva",
        systems = listOf("gba"),
        config = SourceParams(
            baseUrl = "https://minerva-archive.org",
            paths = mapOf("gba" to "No-Intro/Example System"),
        ),
    )

    /** Feeds fixture HTML instead of hitting the network. */
    private fun adapter() = object : MinervaAdapter(OkHttpClient()) {
        override fun fetch(url: String) = html
    }

    @Test
    fun `every row carries a torrent file index`() {
        val entries = adapter().listRaw(source(), "gba")
        assertEquals(23, entries.size)
        assertTrue(entries.all { it.torrentFileIndex != null })
        assertEquals(entries.map { it.torrentFileIndex }.distinct().size, entries.size)
        assertTrue(entries.all { it.sizeBytes != null && it.sizeBytes!! > 0 })
    }

    @Test
    fun `indices match the real torrent positions`() {
        val entries = adapter().listRaw(source(), "gba")
        // The fixture keeps rows 0-5, 900-905, 2400-2405 and the last six, so the
        // index must come from data-m, not from list position.
        assertEquals(listOf(0, 1, 2, 3, 4, 5), entries.take(6).map { it.torrentFileIndex })
        assertTrue(entries.any { it.torrentFileIndex == 900 })
        assertTrue(entries.last().torrentFileIndex!! > 3000)
    }

    @Test
    fun `resolve yields a selective torrent download`() = kotlinx.coroutines.runBlocking {
        val entry = adapter().listRaw(source(), "gba").first()
        val resolved = adapter().resolve(source(), entry)
        assertTrue(resolved is ResolvedDownload.Torrent)
        resolved as ResolvedDownload.Torrent
        assertTrue(resolved.magnet.startsWith("magnet:?xt=urn:btih:"))
        assertEquals("00000000deadbeef000000000000000000000000", resolved.magnet.substringAfter("btih:").substringBefore('&'))
        assertEquals(entry.torrentFileIndex, resolved.fileIndex)
    }

    @Test
    fun `deduped listing collapses region variants but keeps the file index`() = kotlinx.coroutines.runBlocking {
        val entries = adapter().list(source(), "gba", 0)
        assertTrue(entries.size < 23, "expected dedupe to collapse some of 23 entries")
        assertTrue(entries.all { it.torrentFileIndex != null })
        assertNotNull(entries.firstOrNull())
    }
}
