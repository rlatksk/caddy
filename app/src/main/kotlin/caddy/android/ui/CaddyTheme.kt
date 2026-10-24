package caddy.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue

object CaddyTheme {
    const val GROUND = 0xFF08090A.toInt()
    const val PANEL = 0xFF0B0C0D.toInt()
    const val FIELD = 0xFF0E1011.toInt()
    const val LINE = 0xFF1C1E1F.toInt()
    const val LINE_SOFT = 0xFF141617.toInt()
    const val INK = 0xFFD7D9D6.toInt()
    const val MUTE = 0xFF7D827C.toInt()
    const val AMBER = 0xFFF0B429.toInt()
    const val GREEN = 0xFF3ECF72.toInt()
    const val RED = 0xFFFF6B6E.toInt()
    const val HOLD = 0xFF8BB4E6.toInt()

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics,
        ).toInt()

    fun border(
        color: Int,
        borderWidth: Int = 1,
        backgroundColor: Int = Color.TRANSPARENT,
    ): Drawable = GradientDrawable().apply {
        setColor(backgroundColor)
        setStroke(borderWidth, color)
    }

    fun hairline(color: Int): Drawable = ColorDrawable(color)

    fun invertedSelection(
        groundColor: Int = INK,
        textColor: Int = GROUND,
    ): StateListDrawable = StateListDrawable().apply {
        val selectedDrawable = ColorDrawable(groundColor)
        val defaultDrawable = ColorDrawable(Color.TRANSPARENT)
        addState(intArrayOf(android.R.attr.state_selected), selectedDrawable)
        addState(intArrayOf(android.R.attr.state_focused), selectedDrawable)
        addState(intArrayOf(), defaultDrawable)
    }
}
