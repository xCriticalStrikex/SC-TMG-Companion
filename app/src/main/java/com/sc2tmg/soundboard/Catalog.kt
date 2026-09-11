package com.sc2tmg.soundboard

enum class Faction(val label: String) {
    TERRAN("TERRAN"), PROTOSS("PROTOSS"), ZERG("ZERG"), HYBRID("HYBRID")
}

data class UnitEntry(
    val id: String,
    val name: String,
    val faction: Faction,
    val audioId: String = id,
    val heroOrCommander: Boolean = false,
    val hybrid: Boolean = false,
    val tabletop: Boolean = false,
    val defaultSelected: Boolean = false
)

data class BuildingEntry(
    val id: String,
    val name: String,
    val faction: Faction,
    val audioId: String = id,
    val unitAudioId: String? = null,
    val tabletop: Boolean = false,
    val defaultSelected: Boolean = false
)

object Catalog {
    private val unitLibrary = listOf(
        // TERRAN — SC2 library
        UnitEntry("scv", "SCV", Faction.TERRAN),
        UnitEntry("marine", "Marine", Faction.TERRAN, tabletop = true, defaultSelected = true),
        UnitEntry("raynors_raider_marine", "Raynor's Raider Marine", Faction.TERRAN, audioId = "marine", tabletop = true, defaultSelected = true),
        UnitEntry("marauder", "Marauder", Faction.TERRAN, tabletop = true, defaultSelected = true),
        UnitEntry("medic", "Medic", Faction.TERRAN, tabletop = true, defaultSelected = true),
        UnitEntry("goliath", "Goliath", Faction.TERRAN, tabletop = true, defaultSelected = true),
        UnitEntry("point_defense_drone", "Point Defense Drone", Faction.TERRAN, audioId = "raven", tabletop = true),
        UnitEntry("reaper", "Reaper", Faction.TERRAN),
        UnitEntry("ghost", "Ghost", Faction.TERRAN),
        UnitEntry("hellion", "Hellion", Faction.TERRAN),
        UnitEntry("hellbat", "Hellbat", Faction.TERRAN),
        UnitEntry("widow_mine", "Widow Mine", Faction.TERRAN),
        UnitEntry("cyclone", "Cyclone", Faction.TERRAN),
        UnitEntry("siege_tank", "Siege Tank", Faction.TERRAN, tabletop = true, defaultSelected = true),
        UnitEntry("thor", "Thor", Faction.TERRAN),
        UnitEntry("viking", "Viking", Faction.TERRAN),
        UnitEntry("medivac", "Medivac", Faction.TERRAN),
        UnitEntry("liberator", "Liberator", Faction.TERRAN),
        UnitEntry("banshee", "Banshee", Faction.TERRAN),
        UnitEntry("raven", "Raven", Faction.TERRAN),
        UnitEntry("battlecruiser", "Battlecruiser", Faction.TERRAN),
        UnitEntry("firebat", "Firebat", Faction.TERRAN),
        UnitEntry("vulture", "Vulture", Faction.TERRAN),
        UnitEntry("wraith", "Wraith", Faction.TERRAN),
        UnitEntry("science_vessel", "Science Vessel", Faction.TERRAN),
        UnitEntry("spectre", "Spectre", Faction.TERRAN),
        UnitEntry("diamondback", "Diamondback", Faction.TERRAN),
        UnitEntry("warhound", "Warhound", Faction.TERRAN),
        UnitEntry("hercules", "Hercules", Faction.TERRAN),
        UnitEntry("warbot", "Warbot", Faction.TERRAN),

        // TERRAN heroes / commanders
        UnitEntry("jim_raynor", "Jim Raynor", Faction.TERRAN, heroOrCommander = true, tabletop = true, defaultSelected = true),
        UnitEntry("tychus", "Tychus", Faction.TERRAN, heroOrCommander = true),
        UnitEntry("nova", "Nova", Faction.TERRAN, heroOrCommander = true),
        UnitEntry("swann", "Rory Swann", Faction.TERRAN, heroOrCommander = true),
        UnitEntry("matt_horner", "Matt Horner", Faction.TERRAN, heroOrCommander = true),
        UnitEntry("mira_han", "Mira Han", Faction.TERRAN, heroOrCommander = true),
        UnitEntry("mengsk", "Arcturus Mengsk", Faction.TERRAN, heroOrCommander = true),
        UnitEntry("stukov", "Alexei Stukov", Faction.TERRAN, heroOrCommander = true),

        // PROTOSS
        UnitEntry("probe", "Probe", Faction.PROTOSS),
        UnitEntry("zealot", "Zealot", Faction.PROTOSS, tabletop = true, defaultSelected = true),
        UnitEntry("praetor_guard", "Praetor Guard (Zealot)", Faction.PROTOSS, audioId = "zealot", tabletop = true, defaultSelected = true),
        UnitEntry("stalker", "Stalker", Faction.PROTOSS, tabletop = true, defaultSelected = true),
        UnitEntry("sentry", "Sentry", Faction.PROTOSS, tabletop = true, defaultSelected = true),
        UnitEntry("adept", "Adept", Faction.PROTOSS, tabletop = true, defaultSelected = true),
        UnitEntry("high_templar", "High Templar", Faction.PROTOSS),
        UnitEntry("dark_templar", "Dark Templar", Faction.PROTOSS),
        UnitEntry("archon", "Archon", Faction.PROTOSS),
        UnitEntry("immortal", "Immortal", Faction.PROTOSS, tabletop = true, defaultSelected = true),
        UnitEntry("colossus", "Colossus", Faction.PROTOSS),
        UnitEntry("disruptor", "Disruptor", Faction.PROTOSS),
        UnitEntry("observer", "Observer", Faction.PROTOSS),
        UnitEntry("warp_prism", "Warp Prism", Faction.PROTOSS),
        UnitEntry("phoenix", "Phoenix", Faction.PROTOSS),
        UnitEntry("void_ray", "Void Ray", Faction.PROTOSS),
        UnitEntry("oracle", "Oracle", Faction.PROTOSS),
        UnitEntry("tempest", "Tempest", Faction.PROTOSS),
        UnitEntry("carrier", "Carrier", Faction.PROTOSS),
        UnitEntry("mothership", "Mothership", Faction.PROTOSS),
        UnitEntry("dragoon", "Dragoon", Faction.PROTOSS),
        UnitEntry("reaver", "Reaver", Faction.PROTOSS),
        UnitEntry("corsair", "Corsair", Faction.PROTOSS),
        UnitEntry("scout", "Scout", Faction.PROTOSS),
        UnitEntry("arbiter", "Arbiter", Faction.PROTOSS),

        // PROTOSS heroes / commanders
        UnitEntry("artanis", "Artanis", Faction.PROTOSS, heroOrCommander = true, tabletop = true, defaultSelected = true),
        UnitEntry("zeratul", "Zeratul", Faction.PROTOSS, heroOrCommander = true, tabletop = true, defaultSelected = true),
        UnitEntry("tassadar", "Tassadar", Faction.PROTOSS, heroOrCommander = true),
        UnitEntry("selendis", "Selendis", Faction.PROTOSS, heroOrCommander = true),
        UnitEntry("vorazun", "Vorazun", Faction.PROTOSS, heroOrCommander = true),
        UnitEntry("karax", "Karax", Faction.PROTOSS, heroOrCommander = true),
        UnitEntry("alarak", "Alarak", Faction.PROTOSS, heroOrCommander = true),
        UnitEntry("fenix", "Fenix", Faction.PROTOSS, heroOrCommander = true),
        UnitEntry("rohana", "Rohana", Faction.PROTOSS, heroOrCommander = true),
        UnitEntry("kaldalis", "Kaldalis", Faction.PROTOSS, heroOrCommander = true),
        UnitEntry("karass", "Karass", Faction.PROTOSS, heroOrCommander = true),

        // ZERG
        UnitEntry("drone", "Drone", Faction.ZERG),
        UnitEntry("zergling", "Zergling", Faction.ZERG, tabletop = true, defaultSelected = true),
        UnitEntry("raptor_zergling", "Raptor (Zergling)", Faction.ZERG, audioId = "zergling", tabletop = true, defaultSelected = true),
        UnitEntry("swarmling_zergling", "Swarmling (Zergling)", Faction.ZERG, audioId = "zergling", tabletop = true),
        UnitEntry("kerrigan_swarm_raptor", "Kerrigan Swarm Raptor", Faction.ZERG, audioId = "zergling", tabletop = true),
        UnitEntry("baneling", "Baneling", Faction.ZERG),
        UnitEntry("roach", "Roach", Faction.ZERG, tabletop = true, defaultSelected = true),
        UnitEntry("corpser_roach", "Corpser (Roach)", Faction.ZERG, audioId = "roach", tabletop = true),
        UnitEntry("vile_roach", "Vile (Roach)", Faction.ZERG, audioId = "roach", tabletop = true),
        UnitEntry("roachling", "Roachling", Faction.ZERG, audioId = "roach", tabletop = true),
        UnitEntry("ravager", "Ravager", Faction.ZERG, tabletop = true, defaultSelected = true),
        UnitEntry("hydralisk", "Hydralisk", Faction.ZERG, tabletop = true, defaultSelected = true),
        UnitEntry("lurker", "Lurker", Faction.ZERG),
        UnitEntry("infestor", "Infestor", Faction.ZERG),
        UnitEntry("swarm_host", "Swarm Host", Faction.ZERG),
        UnitEntry("ultralisk", "Ultralisk", Faction.ZERG),
        UnitEntry("mutalisk", "Mutalisk", Faction.ZERG),
        UnitEntry("corruptor", "Corruptor", Faction.ZERG),
        UnitEntry("brood_lord", "Brood Lord", Faction.ZERG),
        UnitEntry("overlord", "Overlord", Faction.ZERG),
        UnitEntry("overseer", "Overseer", Faction.ZERG),
        UnitEntry("queen", "Queen", Faction.ZERG, tabletop = true, defaultSelected = true),
        UnitEntry("viper", "Viper", Faction.ZERG),
        UnitEntry("scourge", "Scourge", Faction.ZERG),
        UnitEntry("defiler", "Defiler", Faction.ZERG),
        UnitEntry("guardian", "Guardian", Faction.ZERG),
        UnitEntry("devourer", "Devourer", Faction.ZERG),
        UnitEntry("aberration", "Aberration", Faction.ZERG),
        UnitEntry("brutalisk", "Brutalisk", Faction.ZERG),
        UnitEntry("leviathan", "Leviathan", Faction.ZERG),

        // ZERG heroes / commanders
        UnitEntry("kerrigan", "Kerrigan", Faction.ZERG, heroOrCommander = true, tabletop = true, defaultSelected = true),
        UnitEntry("zagara", "Zagara", Faction.ZERG, heroOrCommander = true),
        UnitEntry("abathur", "Abathur", Faction.ZERG, heroOrCommander = true),
        UnitEntry("dehaka", "Dehaka", Faction.ZERG, heroOrCommander = true),

        // HYBRID / AMON — optional fourth faction tab
        UnitEntry("hybrid_destroyer", "Hybrid Destroyer", Faction.HYBRID, hybrid = true),
        UnitEntry("hybrid_reaver", "Hybrid Reaver", Faction.HYBRID, hybrid = true),
        UnitEntry("hybrid_dominator", "Hybrid Dominator", Faction.HYBRID, hybrid = true),
        UnitEntry("hybrid_nemesis", "Hybrid Nemesis", Faction.HYBRID, hybrid = true),
        UnitEntry("hybrid_behemoth", "Hybrid Behemoth", Faction.HYBRID, hybrid = true),
        UnitEntry("hybrid_maar", "Maar", Faction.HYBRID, hybrid = true, heroOrCommander = true)
    )

    /**
     * The soundboard is audio-bank driven. Tabletop variants that point at the same
     * audioId are intentionally collapsed to one visible entry so the unit list does
     * not show duplicate buttons that play the same voice/SFX bank.
     *
     * Prefer the true canonical entry (id == audioId) even when an alias appears
     * earlier in the source list, e.g. Raven over Point Defense Drone.
     */
    val units: List<UnitEntry> = unitLibrary
        .groupBy { it.faction to it.audioId }
        .values
        .map { group -> group.firstOrNull { it.id == it.audioId } ?: group.first() }

    // Structures shown by the companion are sourced only from actual TMG cards.
    // No generic SC2 structure art / future-unit placeholders are exposed here.
    // Official TMG TACTICAL CARD library. Card-art IDs stay independent from
    // audio routing so a tactic can reuse an existing unit/building sound bank
    // without duplicating or moving that bank.
    val buildings = listOf(
        // TERRAN - 10 tactical cards
        BuildingEntry("barracks", "Barracks", Faction.TERRAN, tabletop = true, defaultSelected = true),
        BuildingEntry("engineering_bay", "Engineering Bay", Faction.TERRAN, tabletop = true, defaultSelected = true),
        BuildingEntry("academy", "Academy", Faction.TERRAN, tabletop = true, defaultSelected = true),
        BuildingEntry("supply_depot", "Supply Depot", Faction.TERRAN, tabletop = true, defaultSelected = true),
        BuildingEntry("orbital_command", "Orbital Command", Faction.TERRAN, tabletop = true, defaultSelected = true),
        BuildingEntry("dropship", "Dropship", Faction.TERRAN, unitAudioId = "dropship", tabletop = true, defaultSelected = true),
        BuildingEntry("factory", "Factory", Faction.TERRAN, tabletop = true, defaultSelected = true),
        BuildingEntry("armory", "Armory", Faction.TERRAN, tabletop = true, defaultSelected = true),
        BuildingEntry("tech_lab", "Barracks (Tech Lab)", Faction.TERRAN, tabletop = true, defaultSelected = true),
        BuildingEntry("barracks_proxy", "Barracks (Proxy)", Faction.TERRAN, audioId = "barracks", tabletop = true, defaultSelected = true),

        // PROTOSS - 10 tactical cards
        BuildingEntry("gate_chronoboosted", "Gate Chronoboosted", Faction.PROTOSS, audioId = "gateway", tabletop = true, defaultSelected = true),
        BuildingEntry("gateway", "Gateway", Faction.PROTOSS, tabletop = true, defaultSelected = true),
        BuildingEntry("warp_prism", "Warp Prism", Faction.PROTOSS, unitAudioId = "warp_prism", tabletop = true, defaultSelected = true),
        BuildingEntry("overcharged_nexus", "Overcharged Nexus", Faction.PROTOSS, audioId = "nexus", tabletop = true, defaultSelected = true),
        BuildingEntry("twilight_council", "Twilight Council", Faction.PROTOSS, tabletop = true, defaultSelected = true),
        BuildingEntry("warp_gate", "Warp Gate", Faction.PROTOSS, tabletop = true, defaultSelected = true),
        BuildingEntry("nexus", "Nexus", Faction.PROTOSS, tabletop = true, defaultSelected = true),
        BuildingEntry("observer", "Observer", Faction.PROTOSS, unitAudioId = "observer", tabletop = true, defaultSelected = true),
        BuildingEntry("forge", "Forge", Faction.PROTOSS, tabletop = true, defaultSelected = true),
        BuildingEntry("power_field", "Power Field", Faction.PROTOSS, audioId = "pylon", tabletop = true, defaultSelected = true),

        // ZERG - 9 tactical cards
        BuildingEntry("overseer", "Overseer", Faction.ZERG, unitAudioId = "overseer", tabletop = true, defaultSelected = true),
        BuildingEntry("hatchery", "Hatchery", Faction.ZERG, tabletop = true, defaultSelected = true),
        BuildingEntry("evolution_chamber", "Evolution Chamber", Faction.ZERG, tabletop = true, defaultSelected = true),
        BuildingEntry("hydralisk_den", "Hydralisk Den", Faction.ZERG, tabletop = true, defaultSelected = true),
        BuildingEntry("overlord", "Overlord", Faction.ZERG, unitAudioId = "overlord", tabletop = true, defaultSelected = true),
        BuildingEntry("lair", "Lair", Faction.ZERG, tabletop = true, defaultSelected = true),
        BuildingEntry("roach_warren", "Roach Warren", Faction.ZERG, tabletop = true, defaultSelected = true),
        BuildingEntry("spawning_pool_six_pool", "Spawning Pool (Six Pool)", Faction.ZERG, audioId = "spawning_pool", tabletop = true, defaultSelected = true),
        BuildingEntry("spawning_pool", "Spawning Pool", Faction.ZERG, tabletop = true, defaultSelected = true)
    )
}
