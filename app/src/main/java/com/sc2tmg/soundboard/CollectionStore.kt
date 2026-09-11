package com.sc2tmg.soundboard

import android.content.Context

class CollectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("collection_v2", Context.MODE_PRIVATE)

    init {
        // v1.4.6: SC2-only structures are extras, not tabletop defaults. Preserve explicit
        // tabletop choices, but switch every non-TMG building off once for existing installs.
        if (!prefs.getBoolean("v146_building_defaults_applied", false)) {
            val editor = prefs.edit()
            Catalog.buildings.filterNot { it.tabletop }.forEach { editor.putBoolean("building_${it.id}", false) }
            editor.putBoolean("v146_building_defaults_applied", true).apply()
        }
    }

    fun isUnitSelected(unit: UnitEntry): Boolean = prefs.getBoolean("unit_${unit.id}", unit.defaultSelected)
    fun setUnitSelected(unit: UnitEntry, selected: Boolean) { prefs.edit().putBoolean("unit_${unit.id}", selected).apply() }

    fun isBuildingSelected(building: BuildingEntry): Boolean = prefs.getBoolean("building_${building.id}", building.tabletop || building.defaultSelected)
    fun setBuildingSelected(building: BuildingEntry, selected: Boolean) { prefs.edit().putBoolean("building_${building.id}", selected).apply() }

    var heroesEnabled: Boolean
        get() = prefs.getBoolean("heroes_enabled", true)
        set(value) { prefs.edit().putBoolean("heroes_enabled", value).apply() }

    var hybridEnabled: Boolean
        get() = prefs.getBoolean("hybrid_enabled", false)
        set(value) { prefs.edit().putBoolean("hybrid_enabled", value).apply() }

    fun applyTabletopPreset() {
        Catalog.units.forEach { setUnitSelected(it, it.tabletop) }
        Catalog.buildings.forEach { setBuildingSelected(it, it.tabletop) }
        heroesEnabled = true
        hybridEnabled = false
    }

    fun selectAll() {
        Catalog.units.forEach { setUnitSelected(it, true) }
        Catalog.buildings.forEach { setBuildingSelected(it, true) }
        heroesEnabled = true
    }
}
