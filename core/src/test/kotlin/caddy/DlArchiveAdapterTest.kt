package caddy

import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The dlarchive index has no platform field, so the filename is the only
 * classification signal. These are the rules that decide what the Switch
 * catalog shows, checked against real filenames from the live index.
 */
class DlArchiveAdapterTest {

    private val adapter = DlArchiveAdapter(OkHttpClient())

    @Test
    fun `switch title id decides base update dlc`() {
        assertEquals(KIND_BASE, adapter.parseSwitchFile("Batman Arkham City [01003F00163CE000].nsp").kind)
        assertEquals(KIND_UPDATE, adapter.parseSwitchFile("Batman Arkham City [01003F00163CE800][v65536].nsp").kind)
        assertEquals(KIND_DLC, adapter.parseSwitchFile("Touhou Pack [01005C601EE65001][v0].nsp").kind)
    }

    @Test
    fun `untagged files fall back to the uploader markers`() {
        assertEquals(KIND_BASE, adapter.parseSwitchFile("Matsurika no kEi Tenmei Kashokuden.xci").kind)
        assertEquals(KIND_UPDATE, adapter.parseSwitchFile("DYSWANTLE Update v1.2.nsp").kind)
        assertEquals(KIND_UPDATE, adapter.parseSwitchFile("Some Game [Up v1.0.1].nsp").kind)
        assertEquals(KIND_DLC, adapter.parseSwitchFile("Some Game DLC Pack.nsp").kind)
    }

    @Test
    fun `update keeps its version so the newest wins`() {
        assertEquals(65536, adapter.parseSwitchFile("Game [01003F00163CE800][v65536].nsp").version)
        assertEquals(131072, adapter.parseSwitchFile("Game [01003F00163CE800][v131072].nsp").version)
    }

    @Test
    fun `title id groups an update with its base game`() {
        val base = adapter.titleIdOf("Batman Arkham City [01003F00163CE000].nsp")!!
        val update = adapter.titleIdOf("Batman Arkham City [01003F00163CE800].nsp")!!
        assertEquals(base.dropLast(3), update.dropLast(3))
        assertEquals("01003F00163CE000", base)
    }

    @Test
    fun `title strips tags size and version noise`() {
        assertEquals(
            "Arcade Archives GALAGA",
            adapter.cleanTitle("Arcade Archives GALAGA [0100566015AD2000][USA][v0].nsp"),
        )
        assertEquals("A Tale for Anna", adapter.cleanTitle("A Tale for Anna (1.05 GB).nsp"))
        assertEquals("Mario vs. Donkey Kong", adapter.cleanTitle("Mario vs. Donkey Kong (U1.0.1).nsp"))
    }

    @Test
    fun `region tags map to the spellings the catalog ranks`() {
        assertEquals("USA", adapter.regionOf("Game [0100BA9014A02000][US][v0].nsp"))
        assertEquals("Japan", adapter.regionOf("Game [0100BA9014A02000][JPN][v0].nsp"))
        assertEquals("Europe", adapter.regionOf("Game [0100BA9014A02000][EUR][v0].nsp"))
        assertNull(adapter.regionOf("Pinball FX v1.5.0.0.nsp"))
    }
}
