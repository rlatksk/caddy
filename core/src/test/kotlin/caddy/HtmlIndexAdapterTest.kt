package caddy

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The declarative adapter is the whole point of the repo model: a new site is a
 * JSON object. These tests drive it with fixture HTML, no network, because what
 * matters is that the selectors are honoured and the file link is found.
 */
class HtmlIndexAdapterTest {

    private val source = SourceConfig(
        id = "example",
        name = "Example",
        transport = Transport.HTTP,
        adapter = "html_index",
        systems = listOf("gba"),
        config = SourceParams(
            baseUrl = "https://example.test",
            listings = mapOf(
                "gba" to ListingSpec(
                    url = "https://example.test/gba/",
                    itemSelector = "div.rom",
                    titleSelector = "h3",
                    downloadSelector = "a.get",
                    sizeSelector = "span.size",
                ),
            ),
        ),
    )

    private class Fixture(private val listing: String, private val detail: String = "") :
        HtmlIndexAdapter(OkHttpClient()) {
        override suspend fun fetch(url: String): String =
            if (url.endsWith("/gba/")) listing else detail
    }

    private val listingHtml = """
        <html><body>
          <div class="rom">
            <h3>Metroid Fusion (USA)</h3>
            <span class="size">4.7 MB</span>
            <a class="get" href="/download/metroid-fusion-usa-123">Download</a>
          </div>
          <div class="rom">
            <h3>Golden Sun (USA)</h3>
            <span class="size">8 MB</span>
            <a class="get" href="/download/golden-sun-456">Download</a>
          </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
          <a href="/files/Metroid%20Fusion%20(USA).zip">click here to download</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `listing yields one entry per item with title size and link`() = runBlocking {
        val adapter = Fixture(listingHtml)
        val entries = adapter.list(source, "gba", 0)
        assertEquals(listOf("Metroid Fusion (USA)", "Golden Sun (USA)"), entries.map { it.title })
        assertTrue(entries[0].sizeBytes!! > 4_000_000L)
        assertTrue(entries[0].sourceRef!!.endsWith("/download/metroid-fusion-usa-123"))
    }

    @Test
    fun `search filters the listing by title`() = runBlocking {
        val adapter = Fixture(listingHtml)
        val hits = adapter.search(source, "gba", "golden")
        assertEquals(listOf("Golden Sun (USA)"), hits.map { it.title })
    }

    @Test
    fun `resolve follows the item link to the file`() = runBlocking {
        val adapter = Fixture(listingHtml, detailHtml)
        val entry = adapter.list(source, "gba", 0).first()
        val resolved = adapter.resolve(source, entry)
        assertTrue(resolved is ResolvedDownload.Direct)
        assertEquals("https://example.test/files/Metroid%20Fusion%20(USA).zip", resolved.url)
        assertEquals("Metroid Fusion (USA).zip", resolved.filename)
    }
}
