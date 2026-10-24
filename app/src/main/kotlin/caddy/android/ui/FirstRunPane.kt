package caddy.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class FirstRunPane(context: Context) : FrameLayout(context) {

    private val urlInput: EditText
    private val fetchButton: TextView
    private val rawJsonInput: EditText
    private val applyJsonButton: TextView

    var onFetchRepo: ((url: String) -> Unit)? = null
    var onApplyJson: ((json: String) -> Unit)? = null

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(CaddyTheme.GROUND)

        // Centered card container (panel background #0b0c0d, 1 px border #1c1e1f, max width 500 dp, padding 24 dp)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val maxWidthPx = CaddyTheme.dp(context, 500f)
            val pad = CaddyTheme.dp(context, 24f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(CaddyTheme.PANEL)
                setStroke(1, CaddyTheme.LINE)
            }
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                val marginHorizontal = CaddyTheme.dp(context, 24f)
                leftMargin = marginHorizontal
                rightMargin = marginHorizontal
            }
        }

        // Title: "WELCOME TO CADDY" (14 dp bold monospace)
        val title = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaddyTheme.INK)
            text = "WELCOME TO CADDY"
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = CaddyTheme.dp(context, 8f)
            }
            layoutParams = lp
        }
        card.addView(title)

        // Description: "Add a repository index to browse systems and download games." (12 dp mute)
        val desc = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(CaddyTheme.MUTE)
            text = "Add a repository index to browse systems and download games."
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = CaddyTheme.dp(context, 20f)
            }
            layoutParams = lp
        }
        card.addView(desc)

        // URL input field (EditText, 12 dp monospace)
        urlInput = EditText(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(CaddyTheme.INK)
            setHintTextColor(CaddyTheme.MUTE)
            hint = "https://.../index.json"
            isSingleLine = true
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setPadding(
                CaddyTheme.dp(context, 12f),
                CaddyTheme.dp(context, 10f),
                CaddyTheme.dp(context, 12f),
                CaddyTheme.dp(context, 10f)
            )
            background = GradientDrawable().apply {
                setColor(CaddyTheme.FIELD)
                setStroke(1, 0xFF222526.toInt())
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = CaddyTheme.dp(context, 12f)
            }
            layoutParams = lp
        }
        card.addView(urlInput)

        // Submit button: "[FETCH REPOSITORY]" (full width, ink ground, ground text)
        fetchButton = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            text = "[FETCH REPOSITORY]"
            gravity = Gravity.CENTER
            setTextColor(CaddyTheme.GROUND)
            background = GradientDrawable().apply {
                setColor(CaddyTheme.INK)
            }
            setPadding(0, CaddyTheme.dp(context, 12f), 0, CaddyTheme.dp(context, 12f))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = CaddyTheme.dp(context, 24f)
            }
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onFetchRepo?.invoke(urlInput.text.toString().trim())
            }
        }
        card.addView(fetchButton)

        // Secondary prompt: "or paste index JSON below"
        val prompt = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.MUTE)
            text = "or paste index JSON below"
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = CaddyTheme.dp(context, 8f)
            }
            layoutParams = lp
        }
        card.addView(prompt)

        // Multiline EditText for JSON
        rawJsonInput = EditText(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.INK)
            setHintTextColor(CaddyTheme.MUTE)
            hint = "{\n  \"schema_version\": 1,\n  \"systems\": [...]\n}"
            minLines = 3
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(
                CaddyTheme.dp(context, 10f),
                CaddyTheme.dp(context, 8f),
                CaddyTheme.dp(context, 10f),
                CaddyTheme.dp(context, 8f)
            )
            background = GradientDrawable().apply {
                setColor(CaddyTheme.FIELD)
                setStroke(1, 0xFF222526.toInt())
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = CaddyTheme.dp(context, 10f)
            }
            layoutParams = lp
        }
        card.addView(rawJsonInput)

        // "[APPLY JSON]" button
        applyJsonButton = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            text = "[APPLY JSON]"
            gravity = Gravity.CENTER
            setTextColor(CaddyTheme.INK)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, CaddyTheme.LINE)
            }
            setPadding(0, CaddyTheme.dp(context, 10f), 0, CaddyTheme.dp(context, 10f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onApplyJson?.invoke(rawJsonInput.text.toString().trim())
            }
        }
        card.addView(applyJsonButton)

        addView(card)
    }

    fun setInitialUrl(url: String) {
        urlInput.setText(url)
    }

    fun submitRepo() {
        val url = urlInput.text.toString().trim()
        if (url.isNotEmpty()) {
            onFetchRepo?.invoke(url)
        } else {
            val json = rawJsonInput.text.toString().trim()
            if (json.isNotEmpty()) {
                onApplyJson?.invoke(json)
            }
        }
    }
}
