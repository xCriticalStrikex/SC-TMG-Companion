package com.sc2tmg.soundboard

import android.content.Context

/**
 * Determines how the unit/building soundboard chooses between the SC2 library and
 * the bundled StarCraft / Brood War library.
 */
enum class SoundBankMode {
    /** SC2 whenever that exact bank exists; classic fills genuinely missing banks. */
    SC2_PREFERRED,

    /** StarCraft / Brood War whenever that exact bank exists; SC2 fills gaps. */
    CLASSIC_PREFERRED,

    /** Pool both exact banks together. */
    MIXED
}

/** Persistent mixer + faction shuffle preferences for the music deck. */
class MusicStore(context: Context) {
    private val prefs = context.getSharedPreferences("music_state_v1", Context.MODE_PRIVATE)

    fun isFactionEnabled(faction: Faction): Boolean = when (faction) {
        Faction.TERRAN, Faction.PROTOSS, Faction.ZERG ->
            prefs.getBoolean("music_${faction.name.lowercase()}", true)
        Faction.HYBRID -> false
    }

    fun setFactionEnabled(faction: Faction, enabled: Boolean) {
        if (faction == Faction.HYBRID) return
        prefs.edit().putBoolean("music_${faction.name.lowercase()}", enabled).apply()
    }

    fun setEnabledFactions(factions: Set<Faction>) {
        val allowed = factions.filterTo(linkedSetOf()) { it != Faction.HYBRID }
        val edit = prefs.edit()
        listOf(Faction.TERRAN, Faction.PROTOSS, Faction.ZERG).forEach { faction ->
            edit.putBoolean("music_${faction.name.lowercase()}", faction in allowed)
        }
        edit.apply()
    }

    fun toggleFaction(faction: Faction): Boolean {
        if (faction == Faction.HYBRID) return false
        val next = !isFactionEnabled(faction)
        setFactionEnabled(faction, next)
        return next
    }

    fun enabledFactions(): Set<Faction> = listOf(Faction.TERRAN, Faction.PROTOSS, Faction.ZERG)
        .filterTo(linkedSetOf()) { isFactionEnabled(it) }

    /** Whether the user currently wants the game soundtrack running. */
    var playbackEnabled: Boolean
        // First install / an untouched preference defaults ON. An explicit user mute writes
        // false to this same key and therefore remains authoritative across every cold boot.
        get() = prefs.getBoolean("music_playback_enabled", true)
        set(value) { prefs.edit().putBoolean("music_playback_enabled", value).apply() }

    /** Keep the soundtrack's faction pool synchronized with the factions in the match. */
    var followMatchFactions: Boolean
        get() = prefs.getBoolean("follow_match_music_factions_v1458", true)
        set(value) { prefs.edit().putBoolean("follow_match_music_factions_v1458", value).apply() }

    /** Default ON: hide faction pages that are not represented in the current game. */
    var skipUnusedFactionPages: Boolean
        get() = prefs.getBoolean("skip_unused_faction_pages", true)
        set(value) { prefs.edit().putBoolean("skip_unused_faction_pages", value).apply() }

    var musicVolume: Float
        get() = prefs.getFloat("music_volume", 0.62f).coerceIn(0f, 1f)
        set(value) { prefs.edit().putFloat("music_volume", value.coerceIn(0f, 1f)).apply() }

    /** Fraction by which music is reduced under VO / announcer playback. */
    var duckingPower: Float
        get() = prefs.getFloat("music_ducking_power_v1460", 0.55f).coerceIn(0f, 0.90f)
        set(value) { prefs.edit().putFloat("music_ducking_power_v1460", value.coerceIn(0f, 0.90f)).apply() }

    var soundboardVolume: Float
        get() = prefs.getFloat("soundboard_volume", 1.0f).coerceIn(0f, 1f)
        set(value) { prefs.edit().putFloat("soundboard_volume", value.coerceIn(0f, 1f)).apply() }

    /** Building-response gain. 100% matches the soundboard master; up to 120% adds
     * a small building-only LoudnessEnhancer boost on supported Android devices. */
    var buildingVolume: Float
        get() = prefs.getFloat("building_volume_v1412", 1.0f).coerceIn(0f, 1.2f)
        set(value) { prefs.edit().putFloat("building_volume_v1412", value.coerceIn(0f, 1.2f)).apply() }

    /**
     * Three-way classic/SC2 sound selection. The old boolean preference is migrated
     * once if this key has never been written: old ON becomes MIXED; old OFF remains
     * the original SC2-preferred behaviour.
     */
    var soundBankMode: SoundBankMode
        get() {
            prefs.getString("sound_bank_mode_v1460", null)?.let { saved ->
                runCatching { SoundBankMode.valueOf(saved) }.getOrNull()?.let { return it }
            }
            return if (prefs.getBoolean("classic_sc_bw_sounds_v1416", false)) {
                SoundBankMode.MIXED
            } else {
                SoundBankMode.SC2_PREFERRED
            }
        }
        set(value) {
            prefs.edit().putString("sound_bank_mode_v1460", value.name).apply()
        }

    /** Compatibility bridge for any older call site outside the v1.4.60 UI. */
    var classicSoundsEnabled: Boolean
        get() = soundBankMode == SoundBankMode.MIXED
        set(value) { soundBankMode = if (value) SoundBankMode.MIXED else SoundBankMode.SC2_PREFERRED }

    var weaponSfxVolume: Float
        get() = prefs.getFloat("weapon_sfx_volume_v142", 0.30f).coerceIn(0f, 1f)
        set(value) { prefs.edit().putFloat("weapon_sfx_volume_v142", value.coerceIn(0f, 1f)).apply() }
}
