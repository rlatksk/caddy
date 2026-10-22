package caddy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Test helper: build entries from bare filenames the way adapters would. */
private fun catalogOf(
    system: String,
    names: List<String>,
    regionPriority: List<String> = emptyList(),
): List<CatalogEntry> {
    val entries = names.map { CatalogEntry(system = system, title = it, filename = it) }
    return if (regionPriority.isEmpty()) buildCatalog(system, entries)
    else buildCatalog(system, entries, regionPriority)
}

class CatalogTest {

    @Test
    fun `splits title regions revision and disc`() {
        val p = parseNoIntroName("Sonic Classics (Revision 1) (USA).zip")
        assertEquals("sonic classics", p.title)
        assertEquals(listOf("USA"), p.regions)
        assertEquals(1, p.revision)
        assertFalse(p.excluded)
    }

    @Test
    fun `multi-region tag is not mistaken for one region`() {
        val p = parseNoIntroName("Streets of Rage 2 (USA, Europe).md")
        assertEquals(listOf("USA", "Europe"), p.regions)
    }

    @Test
    fun `disc number and title articles are normalised`() {
        val p = parseNoIntroName("Final Fantasy VII (USA) (Disc 2).chd")
        assertEquals(2, p.disc)
        assertEquals("final fantasy vii", p.title)
        assertEquals("legend of zelda", parseNoIntroName("The Legend of Zelda (USA).nes").title)
        assertEquals("legend of zelda", parseNoIntroName("Legend of Zelda, The (USA).nes").title)
    }

    @Test
    fun `bios and betas are excluded`() {
        assertTrue(parseNoIntroName("[BIOS] Game Boy Advance (GBC Mode) (World).zip").excluded)
        assertTrue(parseNoIntroName("Metroid Fusion (USA) (Beta).gba").excluded)
        assertTrue(parseNoIntroName("Some Game (USA) (Proto).gba").excluded)
        assertFalse(parseNoIntroName("Metroid Fusion (USA).gba").excluded)
    }

    @Test
    fun `dedupe keeps the preferred region and highest revision`() {
        val entries = catalogOf(
            "gba",
            listOf(
                "Metroid Fusion (Europe).gba",
                "Metroid Fusion (USA).gba",
                "Metroid Fusion (USA) (Rev 1).gba",
                "Metroid Fusion (Japan).gba",
            ),
        )
        assertEquals(1, entries.size)
        assertEquals("Metroid Fusion (USA) (Rev 1).gba", entries.single().filename)
        assertEquals("USA", entries.single().region)
        assertEquals(1, entries.single().revision)
    }

    @Test
    fun `distinct games are not collapsed`() {
        val entries = catalogOf(
            "nes",
            listOf("Super Mario Bros. (World).nes", "Super Mario Bros. 3 (USA).nes"),
        )
        assertEquals(2, entries.size)
        assertEquals(
            listOf("Super Mario Bros. (World).nes", "Super Mario Bros. 3 (USA).nes"),
            entries.map { it.filename },
        )
    }

    @Test
    fun `multi-disc set survives dedupe as one entry`() {
        val entries = catalogOf(
            "psx",
            listOf(
                "Final Fantasy VII (USA) (Disc 1).chd",
                "Final Fantasy VII (USA) (Disc 2).chd",
                "Final Fantasy VII (USA) (Disc 3).chd",
            ),
        )
        assertEquals(1, entries.size)
        assertEquals("Final Fantasy VII (USA) (Disc 1).chd", entries.single().filename)
    }

    @Test
    fun `region priority is honoured`() {
        val entries = catalogOf(
            "gba",
            listOf("Metroid Fusion (USA).gba", "Metroid Fusion (Japan).gba"),
            regionPriority = listOf("Japan", "USA"),
        )
        assertEquals("Metroid Fusion (Japan).gba", entries.single().filename)
    }

    @Test
    fun `real minerva gba listing dedupes 3688 files to a plausible library`() {
        // Fixture trimmed from the live Minerva GBA listing; shapes are the real ones.
        val files = listOf(
            "[BIOS] Game Boy Advance (GBC Mode) (World).zip",
            "[BIOS] Game Boy Advance (World).zip",
            "007 - Everything or Nothing (USA).zip",
            "007 - Everything or Nothing (Europe).zip",
            "Advance Wars (USA).zip",
            "Advance Wars (Europe) (En,Fr,De,Es,It).zip",
            "Advance Wars 2 - Black Hole Rising (USA).zip",
            "Metroid Fusion (USA).zip",
            "Metroid Fusion (Europe) (En,Fr,De,Es,It).zip",
            "Metroid Fusion (Japan).zip",
            "Pokemon - Emerald Version (USA, Europe).zip",
            "Pokemon - Emerald Version (Japan).zip",
            "Golden Sun (USA, Europe).zip",
        )
        val entries = catalogOf("gba", files)
        assertEquals(
            listOf(
                "007 - Everything or Nothing (USA).zip",
                "Advance Wars (USA).zip",
                "Advance Wars 2 - Black Hole Rising (USA).zip",
                "Golden Sun (USA, Europe).zip",
                "Metroid Fusion (USA).zip",
                "Pokemon - Emerald Version (USA, Europe).zip",
            ),
            entries.map { it.filename },
        )
        assertTrue(entries.none { it.filename.startsWith("[BIOS]") })
    }
}

class RepoTest {

    private val adapters = setOf("archive_org", "minerva", "html_index", "browser_capture")

    @Test
    fun `unknown adapter types are dropped not fatal`() {
        val text = """
            {"schemaVersion":1,"name":"r","sources":[
              {"id":"a","name":"A","transport":"http","adapter":"archive_org","systems":["genesis"],
               "config":{"collections":{"genesis":"example_collection"}}},
              {"id":"b","name":"B","transport":"http","adapter":"from_the_future","systems":["gba"],"config":{}}
            ]}
        """.trimIndent()
        val index = parseIndex(text, adapters)
        assertEquals(listOf("a"), index.sources.map { it.id })
    }

    @Test
    fun `newer schemaVersion is rejected`() {
        val text = """{"schemaVersion":99,"name":"r","sources":[]}"""
        val error = runCatching { parseIndex(text, adapters) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `unknown fields are ignored so a newer repo still loads`() {
        val text = """
            {"schemaVersion":1,"name":"r","futureField":true,"sources":[
              {"id":"a","name":"A","transport":"http","adapter":"archive_org","systems":["genesis"],
               "config":{"collections":{"genesis":"x"}},"somethingNew":{"deep":1}}
            ]}
        """.trimIndent()
        assertEquals(1, parseIndex(text, adapters).sources.size)
    }

    @Test
    fun `system name is the folder name`() {
        assertEquals("gba", SystemRouter.relativePath("gba"))
        assertTrue(runCatching { SystemRouter.relativePath("../etc") }.isFailure)
    }

    @Test
    fun `a full two-source index parses`() {
        // Shape only. Real collection ids and paths are deliberately not in this
        // repository: the engine ships no sources.
        val text = """
            {"schemaVersion":1,"name":"r","sources":[
              {"id":"a","name":"A","transport":"http","adapter":"archive_org",
               "systems":["genesis"],"config":{"collections":{"genesis":"example_collection"}}},
              {"id":"m","name":"M","transport":"torrent","adapter":"minerva",
               "systems":["gba"],"config":{"baseUrl":"https://example.invalid",
               "paths":{"gba":"No-Intro/Example System"}}}
            ]}
        """.trimIndent()
        val index = parseIndex(text, adapters)
        assertEquals(listOf("a", "m"), index.sources.map { it.id })
        assertEquals(Transport.TORRENT, index.sources[1].transport)
        assertTrue(index.sources.all { it.systems.isNotEmpty() })
    }
}

class VerifyTest {

    @Test
    fun `matching hashes verify`() {
        val tmp = kotlin.io.path.createTempFile("caddy").toFile()
        try {
            tmp.writeText("rom bytes")
            val d = digest(tmp)
            assertEquals(VerifyResult.Verified, verify(tmp, Hashes(sha1 = d.sha1, crc32 = d.crc32)))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `wrong hash is a mismatch and no hashes is unverified`() {
        val tmp = kotlin.io.path.createTempFile("caddy").toFile()
        try {
            tmp.writeText("rom bytes")
            assertTrue(verify(tmp, Hashes(sha1 = "00")) is VerifyResult.Mismatch)
            assertTrue(verify(tmp, Hashes()) is VerifyResult.Unverified)
        } finally {
            tmp.delete()
        }
    }
}
