package caddy.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class RailView(context: Context) : LinearLayout(context) {

    private val systemsContainer: LinearLayout
    private val scrollView: ScrollView
    private val footerLayout: LinearLayout
    private val queueButton: FrameLayout
    private val queueLabel: TextView
    private val queueDot: View
    private val sourcesButton: FrameLayout
    private val sourcesLabel: TextView

    private val systems = mutableListOf<String>()
    private val systemViews = mutableListOf<TextView>()

    private var selectedSystemIndex: Int = 0
    private var currentScreen: ScreenMode = ScreenMode.BROWSE
    private var isQueueActive: Boolean = false
    private var railFocused: Boolean = false
    private var railFocusedIndex: Int = 0

    var onSystemClickListener: ((index: Int, system: String) -> Unit)? = null
    var onQueueClickListener: (() -> Unit)? = null
    var onSourcesClickListener: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        val widthPx = CaddyTheme.dp(context, 88f)
        layoutParams = LayoutParams(widthPx, LayoutParams.MATCH_PARENT)

        val bgDrawable = GradientDrawable().apply {
            setColor(CaddyTheme.GROUND)
            setStroke(1, CaddyTheme.LINE)
        }
        val rightBorderLayer = LayerDrawable(arrayOf(bgDrawable)).apply {
            setLayerInset(0, -1, -1, 0, -1)
        }
        background = rightBorderLayer

        setPadding(0, CaddyTheme.dp(context, 5f), 0, 0)

        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }
        systemsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        scrollView.addView(
            systemsContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        addView(
            scrollView,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val footerDivider = View(context).apply {
            setBackgroundColor(CaddyTheme.LINE)
        }
        addView(footerDivider, LayoutParams(LayoutParams.MATCH_PARENT, 1))

        footerLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(
                CaddyTheme.dp(context, 6f),
                CaddyTheme.dp(context, 6f),
                CaddyTheme.dp(context, 6f),
                0
            )
        }
        val footerHeightPx = CaddyTheme.dp(context, 34f)
        val btnWidthPx = CaddyTheme.dp(context, 36f)
        val btnHeightPx = CaddyTheme.dp(context, 34f)

        queueButton = FrameLayout(context).apply {
            isFocusable = true
            isClickable = true
            setOnClickListener { onQueueClickListener?.invoke() }
        }
        queueLabel = TextView(context).apply {
            text = "Q"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        queueButton.addView(
            queueLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val dotSizePx = CaddyTheme.dp(context, 5f)
        queueDot = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = if (currentScreen == ScreenMode.QUEUE) CaddyTheme.GROUND else CaddyTheme.AMBER
                val radius = width.toFloat() / 2f
                canvas.drawCircle(radius, radius, radius, paint)
            }
        }.apply {
            visibility = View.GONE
        }
        val dotParams = FrameLayout.LayoutParams(dotSizePx, dotSizePx).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = CaddyTheme.dp(context, 4f)
            rightMargin = CaddyTheme.dp(context, 4f)
        }
        queueButton.addView(queueDot, dotParams)

        sourcesButton = FrameLayout(context).apply {
            isFocusable = true
            isClickable = true
            setOnClickListener { onSourcesClickListener?.invoke() }
        }
        sourcesLabel = TextView(context).apply {
            text = "CFG"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        sourcesButton.addView(
            sourcesLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        footerLayout.addView(queueButton, LayoutParams(btnWidthPx, btnHeightPx))
        val space = View(context)
        footerLayout.addView(space, LayoutParams(CaddyTheme.dp(context, 4f), btnHeightPx))
        footerLayout.addView(sourcesButton, LayoutParams(btnWidthPx, btnHeightPx))

        addView(
            footerLayout,
            LayoutParams(LayoutParams.MATCH_PARENT, footerHeightPx)
        )

        updateFooterState()
    }

    fun setSystems(systems: List<String>) {
        this.systems.clear()
        this.systems.addAll(systems)
        systemsContainer.removeAllViews()
        systemViews.clear()

        val padVPx = CaddyTheme.dp(context, 8f)
        val padHPx = CaddyTheme.dp(context, 10f)

        for ((index, sys) in systems.withIndex()) {
            val tv = TextView(context).apply {
                text = sys.uppercase()
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(padHPx, padVPx, padHPx, padVPx)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    onSystemClickListener?.invoke(index, sys)
                }
            }
            systemViews.add(tv)
            systemsContainer.addView(
                tv,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
        }

        render()
    }

    fun setSelectedSystemIndex(index: Int) {
        selectedSystemIndex = index
        render()
    }

    fun setScreen(screen: ScreenMode) {
        currentScreen = screen
        render()
    }

    fun setQueueActive(hasActiveTransfers: Boolean) {
        isQueueActive = hasActiveTransfers
        updateFooterState()
    }

    fun setRailFocus(isFocused: Boolean, focusedIndex: Int) {
        railFocused = isFocused
        railFocusedIndex = focusedIndex
        if (isFocused && focusedIndex in systemViews.indices) {
            val targetView = systemViews[focusedIndex]
            val rect = android.graphics.Rect(0, 0, targetView.width, targetView.height)
            targetView.requestRectangleOnScreen(rect)
        }
        render()
    }

    private fun render() {
        for (i in systemViews.indices) {
            val tv = systemViews[i]
            val isSelected = (currentScreen == ScreenMode.BROWSE && i == selectedSystemIndex)
            val isFocusedItem = (railFocused && i == railFocusedIndex)

            if (isSelected) {
                tv.setBackgroundColor(CaddyTheme.INK)
                tv.setTextColor(CaddyTheme.GROUND)
            } else if (isFocusedItem) {
                val focusDrawable = GradientDrawable().apply {
                    setColor(CaddyTheme.GROUND)
                    setStroke(1, CaddyTheme.INK)
                }
                tv.background = focusDrawable
                tv.setTextColor(CaddyTheme.INK)
            } else {
                tv.setBackgroundColor(CaddyTheme.GROUND)
                tv.setTextColor(CaddyTheme.INK)
            }
        }

        updateFooterState()
    }

    private fun updateFooterState() {
        val isQueueScreen = (currentScreen == ScreenMode.QUEUE)
        if (isQueueScreen) {
            queueButton.setBackgroundColor(CaddyTheme.INK)
            queueLabel.setTextColor(CaddyTheme.GROUND)
        } else {
            queueButton.setBackgroundColor(CaddyTheme.GROUND)
            queueLabel.setTextColor(CaddyTheme.INK)
        }

        val isSourcesScreen = (currentScreen == ScreenMode.SOURCES)
        if (isSourcesScreen) {
            sourcesButton.setBackgroundColor(CaddyTheme.INK)
            sourcesLabel.setTextColor(CaddyTheme.GROUND)
        } else {
            sourcesButton.setBackgroundColor(CaddyTheme.GROUND)
            sourcesLabel.setTextColor(CaddyTheme.INK)
        }

        queueDot.visibility = if (isQueueActive) View.VISIBLE else View.GONE
        queueDot.invalidate()
    }
}
