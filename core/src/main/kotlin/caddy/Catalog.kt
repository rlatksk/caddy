package caddy

import java.util.regex.Pattern

/**
 * Port of retrofetch's catalog.py: turn a flat list of No-Intro filenames into
 * one entry per game.
 *
 * Pure functions, no I/O. The rules, in order:
 *   - parse "Title (Region) (Rev 1) (Disc 2) (Beta).zip"
 *   - drop BIOS / beta / proto / demo / sample / kiosk / trade-demo / unlicensed
 *   - group by normalised title (leading and trailing "The"/"A"/"An" stripped)
 *   - pick one release per group: best region rank, then highest revision,
 *     then multi-disc set, then fewest junk tags
 */

private val REGIONS = setOf(
    "USA", "Europe", "Japan", "World", "Asia", "Korea", "Brazil", "Australia",
    "China", "Taiwan", "Hong Kong", "France", "Germany", "Spain", "Italy",
    "Netherlands", "Sweden", "Russia", "Canada", "UK", "Denmark", "Finland",
    "Norway", "Poland", "Portugal", "Greece", "India", "Ireland", "Mexico",
    "New Zealand", "South Africa", "Argentina", "Belgium", "Switzerland",
)

private val REGION_RANK = listOf(
    "USA", "World", "Europe", "Japan", "UK", "Asia", "Korea", "Brazil",
    "Australia", "Canada", "China", "Taiwan", "Hong Kong", "France", "Germany",
    "Spain", "Italy", "Netherlands", "Sweden", "Russia", "Denmark", "Finland",
    "Norway", "Poland", "Portugal", "Greece", "India", "Ireland", "Mexico",
    "New Zealand", "South Africa", "Argentina", "Belgium", "Switzerland",
)

private val EXCLUDE_TAGS = setOf(
    "BIOS", "Beta", "Proto", "Demo", "Sample", "Kiosk", "Trade Demo", "Unl",
    "Pirate", "Program", "Tool", "Utilities",
)

private val JUNK_TAGS = setOf(
    "Aftermarket", "Virtual Console", "WiiWare", "PSN", "XBLA", "DLC",
    "Update", "En", "Ja", "Fr", "De", "Es", "It", "Nl", "Pt", "Sv", "No",
    "Da", "Fi", "Ru", "Ko", "Zh", "Pl", "Tr", "Cs", "Hu",
)

private val ROM_EXT = Pattern.compile(
    "\\.(zip|7z|rar|gz|bin|gen|md|smd|sfc|smc|nes|unf|unif|fds|n64|z64|v64|" +
        "gb|gbc|gba|nds|dsi|3ds|cia|lnx|lyx|o|vec|pce|sgx|ngc|col|int|a26|a52|a78|" +
        "chd|cue|iso|gdi|cdi|gcm|rvz|wbfs|wad|rom|zipx)$",
    Pattern.CASE_INSENSITIVE,
)

private val TAG = Pattern.compile("\\(([^()]*)\\)|\\[([^\\[\\]]*)\\]")
private val REVISION = Pattern.compile("(?i)^(?:rev(?:ision)?|v)\\s*([0-9]+)(?:\\.([0-9]+))?$")
private val DISC = Pattern.compile("(?i)^(?:disc|disk|cd)\\s*([0-9]+)")
private val LEADING_ARTICLE = Pattern.compile("(?i)^(the|a|an)\\s+")
private val TRAILING_ARTICLE = Pattern.compile("(?i),\\s*(the|a|an)$")

/** One parsed No-Intro filename. */
data class ParsedName(
    val title: String,
    val regions: List<String>,
    val revision: Int,
    val revisionMinor: Int,
    val disc: Int,
    val tags: List<String>,
    val excluded: Boolean,
    val junkTags: List<String>,
)

fun parseNoIntroName(filename: String): ParsedName {
    val bare = ROM_EXT.matcher(filename).replaceFirst("")
    val tags = mutableListOf<String>()
    val matcher = TAG.matcher(bare)
    while (matcher.find()) {
        val value = (matcher.group(1) ?: matcher.group(2)).trim()
        if (value.isNotEmpty()) tags += value
    }
    val title = TAG.matcher(bare).replaceAll("").replace(Regex("\\s{2,}"), " ").trim().trimEnd('-', ' ').trim()

    val regions = mutableListOf<String>()
    var revision = 0
    var revisionMinor = 0
    var disc = 0
    var excluded = false
    val junk = mutableListOf<String>()

    for (tag in tags) {
        val parts = tag.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val rev = REVISION.matcher(tag)
        val discMatcher = DISC.matcher(tag)
        when {
            // "(USA, Europe)" arrives as one tag
            parts.isNotEmpty() && parts.all { it in REGIONS } -> regions += parts
            rev.matches() -> {
                revision = rev.group(1).toInt()
                revisionMinor = rev.group(2)?.toInt() ?: 0
            }
            discMatcher.find() -> disc = discMatcher.group(1).toInt()
            tag in EXCLUDE_TAGS -> excluded = true
            else -> junk += tag
        }
    }

    return ParsedName(
        title = normaliseTitle(title),
        regions = regions.distinct(),
        revision = revision,
        revisionMinor = revisionMinor,
        disc = disc,
        tags = tags,
        excluded = excluded,
        junkTags = junk,
    )
}

fun normaliseTitle(title: String): String {
    var t = title.trim()
    t = LEADING_ARTICLE.matcher(t).replaceFirst("")
    t = TRAILING_ARTICLE.matcher(t).replaceFirst("")
    return t.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}

/**
 * Dedupe a flat listing into one entry per game. Parsing runs on [CatalogEntry.title],
 * which is the No-Intro name for Minerva and the item title for archive.org; the
 * filename stays as the transport payload.
 */
fun buildCatalog(
    system: String,
    items: List<CatalogEntry>,
    regionPriority: List<String> = REGION_RANK,
): List<CatalogEntry> {
    val parsed = items.map { it to parseNoIntroName(it.title) }

    // multi-disc sets: a group is multi-disc if any non-excluded member has disc > 0
    val multiDisc = parsed.filter { !it.second.excluded && it.second.disc > 0 }
        .groupBy { it.second.title }
        .keys

    val rank = regionPriority.ifEmpty { REGION_RANK }
    fun rankOf(regions: List<String>): Int =
        regions.minOfOrNull { region -> rank.indexOf(region).let { if (it < 0) rank.size else it } } ?: rank.size

    return parsed
        .filter { !it.second.excluded }
        .groupBy { it.second.title }
        .map { (_, members) ->
            val best = members.sortedWith(
                compareBy(
                    { rankOf(it.second.regions) },
                    { -it.second.revision },
                    { -it.second.revisionMinor },
                    { if (it.second.title in multiDisc) it.second.disc else 0 },
                    { it.second.junkTags.size },
                    { it.first.filename },
                )
            ).first()
            best.first.copy(
                region = best.second.regions.firstOrNull(),
                revision = best.second.revision,
                disc = best.second.disc,
                junkTags = best.second.junkTags,
            )
        }
        .sortedBy { it.title }
}
