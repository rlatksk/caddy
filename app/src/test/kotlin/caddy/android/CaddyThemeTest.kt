package caddy.android

import caddy.android.ui.CaddyTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class CaddyThemeTest {
    @Test
    fun testColorTokens() {
        assertEquals(0xFF08090A.toInt(), CaddyTheme.GROUND)
        assertEquals(0xFF0B0C0D.toInt(), CaddyTheme.PANEL)
        assertEquals(0xFF0E1011.toInt(), CaddyTheme.FIELD)
        assertEquals(0xFF1C1E1F.toInt(), CaddyTheme.LINE)
        assertEquals(0xFF141617.toInt(), CaddyTheme.LINE_SOFT)
        assertEquals(0xFFD7D9D6.toInt(), CaddyTheme.INK)
        assertEquals(0xFF7D827C.toInt(), CaddyTheme.MUTE)
        assertEquals(0xFFF0B429.toInt(), CaddyTheme.AMBER)
        assertEquals(0xFF3ECF72.toInt(), CaddyTheme.GREEN)
        assertEquals(0xFFFF6B6E.toInt(), CaddyTheme.RED)
        assertEquals(0xFF8BB4E6.toInt(), CaddyTheme.HOLD)
    }
}
