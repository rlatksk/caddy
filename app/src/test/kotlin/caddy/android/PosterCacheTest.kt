package caddy.android

import caddy.android.ui.LibretroPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterCacheTest {

    @Test
    fun testPlatformMapping() {
        assertEquals("Nintendo - Game Boy Advance", LibretroPlatform.folder("gba"))
        assertEquals("Sega - Mega Drive - Genesis", LibretroPlatform.folder("genesis"))
        assertEquals("Sega - Mega Drive - Genesis", LibretroPlatform.folder("gen"))
        assertEquals("Sega - Mega Drive - Genesis", LibretroPlatform.folder("megadrive"))
        assertEquals("Nintendo - Super Nintendo Entertainment System", LibretroPlatform.folder("snes"))
        assertEquals("Sony - PlayStation", LibretroPlatform.folder("psx"))
        assertEquals("Nintendo - Nintendo 64", LibretroPlatform.folder("n64"))

        // Other requirements check
        assertEquals("Nintendo - Game Boy", LibretroPlatform.folder("gb"))
        assertEquals("Nintendo - Game Boy Color", LibretroPlatform.folder("gbc"))
        assertEquals("Nintendo - Nintendo Entertainment System", LibretroPlatform.folder("nes"))
        assertEquals("Nintendo - Nintendo DS", LibretroPlatform.folder("nds"))
        assertEquals("Sega - Master System - Mark III", LibretroPlatform.folder("mastersystem"))
        assertEquals("Sega - Game Gear", LibretroPlatform.folder("gamegear"))
        assertEquals("Sony - PlayStation 2", LibretroPlatform.folder("ps2"))
        assertEquals("Sony - PlayStation Portable", LibretroPlatform.folder("psp"))
        assertEquals("Sega - Saturn", LibretroPlatform.folder("saturn"))
        assertEquals("Sega - Dreamcast", LibretroPlatform.folder("dreamcast"))
        assertEquals("SNK - Neo Geo", LibretroPlatform.folder("neogeo"))
        assertEquals("NEC - PC Engine - TurboGrafx 16", LibretroPlatform.folder("tg16"))
        assertEquals("NEC - PC Engine - TurboGrafx 16", LibretroPlatform.folder("pce"))
        assertEquals("unknown_sys", LibretroPlatform.folder("unknown_sys"))
    }

    @Test
    fun testThumbnailUrlResolution() {
        val url = LibretroPlatform.thumbnailUrl(
            "gba",
            "Pokemon - Emerald Version (USA, Europe).zip"
        )
        val expected = "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Advance/Named_Boxarts/Pokemon%20-%20Emerald%20Version%20%28USA%2C%20Europe%29.png"
        assertEquals(expected, url)
    }

    @Test
    fun testCleanName() {
        assertEquals("Super Mario World", LibretroPlatform.cleanName("Super Mario World.sfc"))
        assertEquals("Pokemon - Emerald Version (USA, Europe)", LibretroPlatform.cleanName("Pokemon - Emerald Version (USA, Europe).zip"))
    }
}
