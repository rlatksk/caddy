package caddy.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SourcesPane(context: Context) : LinearLayout(context) {

    // Field section views
    private val repoUrlInput: EditText
    private val fetchButton: TextView
    private val rawJsonInput: EditText
    private val applyJsonButton: TextView
    private val romsDirInput: EditText
    private val browseButton: TextView

    // Grid section views
    private val gridLayout: GridLayout
    private val systemChips = mutableListOf<TextView>()

    // Meta section views
    private val metadataSwitch: Switch
    private val statusInfoText: TextView

    // State
    private var allSystems: List<String> = emptyList()
    private var enabledSystems: Set<String> = emptySet()
    private var currentFocus: FocusTarget = FocusTarget(Zone.FIELD, 0)

    // Callbacks
    var onFetchRepo: ((url: String) -> Unit)? = null
    var onApplyJson: ((json: String) -> Unit)? = null
    var onRomsDirChanged: ((dir: String) -> Unit)? = null
    var onToggleSystem: ((sys: String, enabled: Boolean) -> Unit)? = null
    var onToggleMetadata: ((enabled: Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(CaddyTheme.GROUND)

        // 1. Header Bar (46 dp)
        val headerBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, CaddyTheme.dp(context, 46f))
            layoutParams = lp
            setPadding(CaddyTheme.dp(context, 16f), 0, CaddyTheme.dp(context, 16f), 0)
            setBackgroundColor(CaddyTheme.PANEL)
        }

        val titleView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(CaddyTheme.INK)
            text = "SOURCES & SETTINGS"
            isSingleLine = true
        }
        headerBar.addView(titleView)
        addView(headerBar)

        val borderLine = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(CaddyTheme.LINE)
        }
        addView(borderLine)

        // Scrollable content
        val scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }

        val contentLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(
                CaddyTheme.dp(context, 16f),
                CaddyTheme.dp(context, 16f),
                CaddyTheme.dp(context, 16f),
                CaddyTheme.dp(context, 24f)
            )
        }

        // Section A: FIELD SECTION (Zone.FIELD)
        val fieldSectionLabel = createSectionLabel("REPOSITORY & STORAGE")
        contentLayout.addView(fieldSectionLabel)

        // Field 0: Repo URL
        val repoUrlRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = CaddyTheme.dp(context, 12f)
            }
        }
        repoUrlInput = createInput("https://.../index.json", singleLine = true).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        fetchButton = createActionButton("[FETCH]").apply {
            setOnClickListener { onFetchRepo?.invoke(repoUrlInput.text.toString().trim()) }
        }
        repoUrlRow.addView(repoUrlInput)
        repoUrlRow.addView(fetchButton)
        contentLayout.addView(repoUrlRow)

        // Field 1: Raw JSON
        val rawJsonRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = CaddyTheme.dp(context, 12f)
            }
        }
        rawJsonInput = createInput("...or paste raw index JSON here", singleLine = false).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            minLines = 2
            maxLines = 3
        }
        applyJsonButton = createActionButton("[APPLY JSON]").apply {
            setOnClickListener { onApplyJson?.invoke(rawJsonInput.text.toString().trim()) }
        }
        rawJsonRow.addView(rawJsonInput)
        rawJsonRow.addView(applyJsonButton)
        contentLayout.addView(rawJsonRow)

        // Field 2: ROMs Root directory
        val romsDirRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = CaddyTheme.dp(context, 16f)
            }
        }
        romsDirInput = createInput("/sdcard/ROMs", singleLine = true).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    onRomsDirChanged?.invoke(romsDirInput.text.toString().trim())
                }
            }
        }
        browseButton = createActionButton("[BROWSE]").apply {
            setOnClickListener { onRomsDirChanged?.invoke(romsDirInput.text.toString().trim()) }
        }
        romsDirRow.addView(romsDirInput)
        romsDirRow.addView(browseButton)
        contentLayout.addView(romsDirRow)

        // Section B: GRID SECTION (Zone.GRID)
        val gridSectionLabel = createSectionLabel("ENABLED SYSTEMS")
        contentLayout.addView(gridSectionLabel)

        gridLayout = GridLayout(context).apply {
            columnCount = 4
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = CaddyTheme.dp(context, 16f)
            }
        }
        contentLayout.addView(gridLayout)

        // Section C: META SECTION (Zone.META)
        val metaSectionLabel = createSectionLabel("METADATA & STATUS")
        contentLayout.addView(metaSectionLabel)

        val metaRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = CaddyTheme.dp(context, 12f)
            }
        }
        val metaLabel = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(CaddyTheme.INK)
            text = "Fetch Game Box Art / Posters"
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        metadataSwitch = Switch(context).apply {
            setOnCheckedChangeListener { _, isChecked ->
                onToggleMetadata?.invoke(isChecked)
            }
        }
        metaRow.addView(metaLabel)
        metaRow.addView(metadataSwitch)
        contentLayout.addView(metaRow)

        statusInfoText = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.MUTE)
            text = "No repository loaded"
        }
        contentLayout.addView(statusInfoText)

        scrollView.addView(contentLayout)
        addView(scrollView)

        renderFieldFocus()
    }

    private fun createSectionLabel(title: String): TextView {
        return TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(CaddyTheme.MUTE)
            text = title
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = CaddyTheme.dp(context, 8f)
                bottomMargin = CaddyTheme.dp(context, 6f)
            }
            layoutParams = lp
        }
    }

    private fun createInput(hintText: String, singleLine: Boolean): EditText {
        return EditText(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(CaddyTheme.INK)
            setHintTextColor(CaddyTheme.MUTE)
            hint = hintText
            isSingleLine = singleLine
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
        }
    }

    private fun createActionButton(label: String): TextView {
        return TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            text = label
            isSingleLine = true
            gravity = Gravity.CENTER
            setPadding(
                CaddyTheme.dp(context, 12f),
                CaddyTheme.dp(context, 8f),
                CaddyTheme.dp(context, 12f),
                CaddyTheme.dp(context, 8f)
            )
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = CaddyTheme.dp(context, 8f)
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, CaddyTheme.LINE)
            }
            setTextColor(CaddyTheme.INK)
        }
    }

    fun setConfig(
        repoUrl: String,
        json: String,
        romsDir: String,
        systems: List<String>,
        enabled: Set<String>,
        metadataEnabled: Boolean
    ) {
        if (repoUrlInput.text.toString() != repoUrl) {
            repoUrlInput.setText(repoUrl)
        }
        if (rawJsonInput.text.toString() != json) {
            rawJsonInput.setText(json)
        }
        if (romsDirInput.text.toString() != romsDir) {
            romsDirInput.setText(romsDir)
        }
        allSystems = systems
        enabledSystems = enabled
        metadataSwitch.isChecked = metadataEnabled

        val repoDisplay = if (repoUrl.isNotEmpty()) repoUrl else "(local / direct JSON)"
        statusInfoText.text = "Repo: $repoDisplay · ${allSystems.size} available systems (${enabledSystems.size} enabled)"

        renderGrid()
    }

    private fun renderGrid() {
        gridLayout.removeAllViews()
        systemChips.clear()

        val chipMargin = CaddyTheme.dp(context, 4f)
        for ((idx, sys) in allSystems.withIndex()) {
            val isEnabled = enabledSystems.contains(sys)
            val isFocused = currentFocus.zone == Zone.GRID && currentFocus.index == idx

            val chip = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                gravity = Gravity.CENTER
                text = if (isEnabled) "[X] $sys" else "[ ] $sys"
                setPadding(
                    CaddyTheme.dp(context, 8f),
                    CaddyTheme.dp(context, 6f),
                    CaddyTheme.dp(context, 8f),
                    CaddyTheme.dp(context, 6f)
                )

                val param = GridLayout.LayoutParams().apply {
                    width = 0
                    height = LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(chipMargin, chipMargin, chipMargin, chipMargin)
                }
                layoutParams = param

                setOnClickListener {
                    onToggleSystem?.invoke(sys, !enabledSystems.contains(sys))
                }
            }

            styleChip(chip, isEnabled, isFocused)
            systemChips.add(chip)
            gridLayout.addView(chip)
        }
    }

    private fun styleChip(chip: TextView, isEnabled: Boolean, isFocused: Boolean) {
        when {
            isFocused -> {
                chip.background = GradientDrawable().apply {
                    setColor(CaddyTheme.INK)
                    setStroke(1, CaddyTheme.INK)
                }
                chip.setTextColor(CaddyTheme.GROUND)
            }
            isEnabled -> {
                chip.background = GradientDrawable().apply {
                    setColor(CaddyTheme.PANEL)
                    setStroke(1, CaddyTheme.GREEN)
                }
                chip.setTextColor(CaddyTheme.INK)
            }
            else -> {
                chip.background = GradientDrawable().apply {
                    setColor(CaddyTheme.FIELD)
                    setStroke(1, CaddyTheme.LINE)
                }
                chip.setTextColor(CaddyTheme.MUTE)
            }
        }
    }

    fun applyFocus(target: FocusTarget) {
        currentFocus = target
        renderFieldFocus()
        renderGridFocus()
        renderMetaFocus()
    }

    private fun renderFieldFocus() {
        val isFieldZone = currentFocus.zone == Zone.FIELD
        // 0: URL input / fetch button, 1: JSON input / apply button, 2: ROMs input / browse button
        setInputFocus(repoUrlInput, fetchButton, isFieldZone && currentFocus.index == 0)
        setInputFocus(rawJsonInput, applyJsonButton, isFieldZone && currentFocus.index == 1)
        setInputFocus(romsDirInput, browseButton, isFieldZone && currentFocus.index == 2)
    }

    private fun setInputFocus(input: EditText, button: TextView, focused: Boolean) {
        if (focused) {
            input.background = GradientDrawable().apply {
                setColor(CaddyTheme.FIELD)
                setStroke(1, CaddyTheme.INK)
            }
            button.background = GradientDrawable().apply {
                setColor(CaddyTheme.INK)
                setStroke(1, CaddyTheme.INK)
            }
            button.setTextColor(CaddyTheme.GROUND)
        } else {
            input.background = GradientDrawable().apply {
                setColor(CaddyTheme.FIELD)
                setStroke(1, 0xFF222526.toInt())
            }
            button.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, CaddyTheme.LINE)
            }
            button.setTextColor(CaddyTheme.INK)
        }
    }

    private fun renderGridFocus() {
        for ((idx, chip) in systemChips.withIndex()) {
            val isEnabled = enabledSystems.contains(allSystems.getOrNull(idx))
            val isFocused = currentFocus.zone == Zone.GRID && currentFocus.index == idx
            styleChip(chip, isEnabled, isFocused)
        }
    }

    private fun renderMetaFocus() {
        val isMetaFocused = currentFocus.zone == Zone.META
        if (isMetaFocused) {
            metadataSwitch.isFocusableInTouchMode = true
            metadataSwitch.requestFocus()
        } else {
            metadataSwitch.clearFocus()
        }
    }

    fun triggerAction(): Boolean {
        when (currentFocus.zone) {
            Zone.FIELD -> {
                when (currentFocus.index) {
                    0 -> onFetchRepo?.invoke(repoUrlInput.text.toString().trim())
                    1 -> onApplyJson?.invoke(rawJsonInput.text.toString().trim())
                    2 -> onRomsDirChanged?.invoke(romsDirInput.text.toString().trim())
                }
                return true
            }
            Zone.GRID -> {
                val sys = allSystems.getOrNull(currentFocus.index)
                if (sys != null) {
                    onToggleSystem?.invoke(sys, !enabledSystems.contains(sys))
                    return true
                }
            }
            Zone.META -> {
                metadataSwitch.toggle()
                return true
            }
            else -> {}
        }
        return false
    }
}
