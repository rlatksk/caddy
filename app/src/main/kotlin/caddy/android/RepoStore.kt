package caddy.android

import android.content.Context
import caddy.RepoIndex
import caddy.SourceConfig
import caddy.parseIndex

/**
 * Persistence for P1: one index document plus the enabled system set.
 *
 * SharedPreferences, not Room: there is one row of state. Room earns its place
 * at P4 when downloads need a queue that survives process death.
 */
class RepoStore(context: Context) {

    private val prefs = context.getSharedPreferences("caddy", Context.MODE_PRIVATE)

    var indexJson: String?
        get() = prefs.getString(KEY_INDEX, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_INDEX) else putString(KEY_INDEX, value)
        }.apply()

    var indexUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_URL) else putString(KEY_URL, value)
        }.apply()

    var lastFetch: Long
        get() = prefs.getLong(KEY_FETCHED, 0L)
        set(value) = prefs.edit().putLong(KEY_FETCHED, value).apply()

    var enabledSystems: Set<String>
        get() = prefs.getStringSet(KEY_SYSTEMS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SYSTEMS, value).apply()

    /**
     * Where update checks read versions.json. Defaults to this build's channel;
     * a fork ships its own default, and it can be pointed elsewhere for testing.
     */
    var updateUrl: String
        get() = prefs.getString(KEY_UPDATE, null) ?: BuildConfig.UPDATE_URL
        set(value) = prefs.edit().putString(KEY_UPDATE, value).apply()

    /** Real filesystem path: ES-DE reads ROMs/<system>/, and streaming writes need a File. */
    var romsRoot: String
        get() = prefs.getString(KEY_ROMS, null) ?: "/storage/emulated/0/ROMs"
        set(value) = prefs.edit().putString(KEY_ROMS, value).apply()

    fun toggleSystem(system: String): Set<String> {
        val next = enabledSystems.toMutableSet()
        if (!next.add(system)) next.remove(system)
        enabledSystems = next
        return next
    }

    /**
     * Parses the stored index. Unknown adapter types are dropped by the parser,
     * so a repo written for a newer app still loads here.
     */
    fun parse(): Result<RepoIndex> = runCatching {
        val text = indexJson ?: error("no repository added")
        parseIndex(text, KNOWN_ADAPTERS)
    }

    fun sources(): List<SourceConfig> = parse().getOrNull()?.sources ?: emptyList()

    /** Systems offered by the added sources, sorted. Disabled systems never appear. */
    fun availableSystems(): List<String> =
        sources().flatMap { it.systems }.distinct().sorted()

    companion object {
        private const val KEY_INDEX = "indexJson"
        private const val KEY_URL = "indexUrl"
        private const val KEY_FETCHED = "lastFetch"
        private const val KEY_SYSTEMS = "enabledSystems"
        private const val KEY_ROMS = "romsRoot"
        private const val KEY_UPDATE = "updateUrl"

        /** Adapter types this build understands. Must match the app's registry. */
        val KNOWN_ADAPTERS = caddy.BUILT_IN_ADAPTERS
    }
}
