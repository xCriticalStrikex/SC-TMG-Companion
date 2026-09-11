package com.sc2tmg.soundboard

import android.content.Context

/** Persistent visual sub-faction/theme selections. Audio/gameplay are unaffected. */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("faction_theme_state_v1", Context.MODE_PRIVATE)

    fun index(faction: Faction): Int = prefs.getInt("theme_${faction.name.lowercase()}", 0).coerceAtLeast(0)

    fun setIndex(faction: Faction, value: Int) {
        if (faction == Faction.HYBRID) return
        prefs.edit().putInt("theme_${faction.name.lowercase()}", value.coerceAtLeast(0)).apply()
    }
}
