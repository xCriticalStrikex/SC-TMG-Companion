package com.sc2tmg.soundboard

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Real StarCraft II portrait/image sources.
 *
 * Genuine StarCraft II portrait/image sources. Legacy generated/local unit and building
 * placeholders were removed in v1.3.5; these URLs are now the sole art source for cards.
 * Coil caches successful loads on-device after first use.
 */
object PortraitSources {
    private const val FILE_PATH = "https://starcraft.fandom.com/wiki/Special:FilePath/"

    private const val ICON_BASE = "https://raw.githubusercontent.com/MatthewMarinets/ap_sc2_icons/refs/heads/main/"
    private const val FAST_PORTRAIT_BASE = "https://dist.sc2arcade.com/star-assets/portraits-png/"

    // Direct URLs exposed by Blizzard's own SC2 multiplayer portrait guide. Prefer
    // these for core units because there is no wiki redirect or guessed asset name.
    private val officialPortraitUrls = mapOf(
        "marine" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_marine-large.jpg",
        "raynors_raider_marine" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_marine-large.jpg",
        "marauder" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_marauder-large.jpg",
        "medic" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_medic4.jpg",
        "goliath" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_goliath-large.jpg",
        "siege_tank" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_siegetank-large.jpg",
        "zealot" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_zealot-large.jpg",
        "praetor_guard" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_zealot-large.jpg",
        "stalker" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_stalker-large.jpg",
        "sentry" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_sentry-large.jpg",
        "immortal" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_immortal-large.jpg",
        "artanis" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_artanis-large.jpg",
        "zergling" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_zergling-large.jpg",
        "raptor_zergling" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_zergling-large.jpg",
        "swarmling_zergling" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_zergling-large.jpg",
        "kerrigan_swarm_raptor" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_zergling-large.jpg",
        "roach" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_roach-large.jpg",
        "corpser_roach" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_roach-large.jpg",
        "vile_roach" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_roach-large.jpg",
        "roachling" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_roach-large.jpg",
        "hydralisk" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_hydralisk-large.jpg",
        "queen" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_queen-large.jpg",
        "kerrigan" to "https://us.media.blizzard.com/sc2/media/screenshots/guide/portraits/portrait_kerrigan-large.jpg"
    )

    // Direct PNG exports indexed by SC2Mapster Asset Explorer. These skip the wiki
    // page/redirect layer entirely, which is much better suited to an in-game selector.
    private val fastPortraitFiles = mapOf(
        "scv" to "scvportrait_static.png",
        "marine" to "marine1portrait_static.png",
        "raynors_raider_marine" to "marine1portrait_static.png",
        "marauder" to "marauderportrait_static.png",
        "medic" to "medicportrait_static.png",
        "goliath" to "goliathportrait_static.png",
        "point_defense_drone" to "ravenportrait_static.png",
        "siege_tank" to "siegetankportrait_static.png",
        "ghost" to "ghostportrait_static.png",
        "reaper" to "reaperportrait_static.png",
        "jim_raynor" to "raynorportrait_static.png",
        "zealot" to "zealotportrait_static.png",
        "praetor_guard" to "zealotportrait_static.png",
        "stalker" to "stalkerportrait_static.png",
        "sentry" to "sentryportrait_static.png",
        "immortal" to "immortalportrait_static.png",
        "artanis" to "artanisportrait_static.png",
        "selendis" to "selendisportrait_static.png",
        "tassadar" to "tassadarportrait_static.png",
        "vorazun" to "vorazunportrait_static.png",
        "karax" to "karaxportrait_static.png",
        "alarak" to "alarakportrait_static.png",
        "fenix" to "fenixportrait_static.png",
        "karass" to "karassportrait_static.png",
        "zeratul" to "zeratulportrait_static.png",
        "zergling" to "zerglingportrait_static.png",
        "raptor_zergling" to "zerglingportrait_static.png",
        "swarmling_zergling" to "zerglingportrait_static.png",
        "kerrigan_swarm_raptor" to "zerglingportrait_static.png",
        "roach" to "roachportrait_static.png",
        "corpser_roach" to "roachportrait_static.png",
        "vile_roach" to "roachportrait_static.png",
        "roachling" to "roachportrait_static.png",
        "ravager" to "ravagerportrait_static.png",
        "hydralisk" to "hydraliskportrait_static.png",
        "queen" to "queenportrait_static.png",
        "kerrigan" to "kerriganportrait_static.png",
        "zagara" to "zagaraportrait_static.png",
        "dehaka" to "dehakaportrait_static.png",
        "leviathan" to "leviathanportrait_static.png",
        "hellion" to "hellionportrait_static.png",
        "hellbat" to "portrait-ued-hellbat-static.png",
        "widow_mine" to "widowmineportrait_static.png",
        "cyclone" to "cycloneportrait_static.png",
        "thor" to "thorportrait_static.png",
        "viking" to "vikingfighterportrait_static.png",
        "medivac" to "medivacportrait_static.png",
        "liberator" to "liberatorportrait_static.png",
        "banshee" to "bansheeportrait_static.png",
        "raven" to "ravenportrait_static.png",
        "battlecruiser" to "battlecruiserportrait_static.png",
        "firebat" to "firebatportrait_static.png",
        "vulture" to "vultureportrait_static.png",
        "wraith" to "wraithportrait_static.png",
        "science_vessel" to "sciencevesselportrait_static.png",
        "spectre" to "spectreportrait_static.png",
        "diamondback" to "diamondbackportrait_static.png",
        "warhound" to "warhoundportrait_static.png",
        "hercules" to "herculesportrait_static.png",
        "warbot" to "warbotportrait_static.png",
        "probe" to "probeportrait_static.png",
        "adept" to "adeptportrait_static.png",
        "high_templar" to "hightemplarportrait_static.png",
        "dark_templar" to "darktemplarportrait_static.png",
        "archon" to "archonportrait_static.png",
        "colossus" to "colossusportrait_static.png",
        "disruptor" to "disruptorportrait_static.png",
        "observer" to "observerportrait_static.png",
        "warp_prism" to "warpprismportrait_static.png",
        "phoenix" to "phoenixportrait_static.png",
        "void_ray" to "voidrayportrait_static.png",
        "oracle" to "oracleportrait_static.png",
        "tempest" to "tempestportrait_static.png",
        "carrier" to "carrierportrait_static.png",
        "mothership" to "mothershipportrait_static.png",
        "dragoon" to "dragoonportrait_static.png",
        "reaver" to "reaverportrait_static.png",
        "corsair" to "corsairportrait_static.png",
        "scout" to "scoutportrait_static.png",
        "arbiter" to "arbiterportrait_static.png",
        "drone" to "droneportrait_static.png",
        "baneling" to "banelingportrait_static.png",
        "lurker" to "lurkerportrait_static.png",
        "infestor" to "infestorportrait_static.png",
        "swarm_host" to "swarmhostportrait_static.png",
        "ultralisk" to "ultraliskportrait_static.png",
        "mutalisk" to "mutaliskportrait_static.png",
        "corruptor" to "corruptorportrait_static.png",
        "brood_lord" to "broodlordportrait_static.png",
        "overlord" to "overlordportrait_static.png",
        "overseer" to "overseerportrait_static.png",
        "viper" to "viperportrait_static.png",
        "scourge" to "scourgeportrait_static.png",
        "defiler" to "defilerportrait_static.png",
        "guardian" to "guardianportrait_static.png",
        "devourer" to "devourerportrait_static.png",
        "aberration" to "aberrationportrait_static.png",
        "brutalisk" to "brutaliskportrait_static.png",


    )

    // Genuine Blizzard command-card icons. These are tiny compared with wiki portrait
    // pages and make the scrolling selector feel instant. The selected unit may still
    // use the larger portrait/head asset when available.
    private val fastUnitIcons = mapOf(
        // Terran tabletop / common
        "scv" to "icons/blizzard/btn-unit-terran-scv.png",
        "marine" to "icons/blizzard/btn-unit-terran-marine.png",
        "raynors_raider_marine" to "icons/blizzard/btn-unit-terran-marine.png",
        "marauder" to "icons/blizzard/btn-unit-terran-marauder.png",
        "medic" to "icons/blizzard/btn-unit-terran-medic.png",
        "goliath" to "icons/blizzard/btn-unit-terran-goliath.png",
        "point_defense_drone" to "icons/blizzard/btn-unit-terran-raven.png",
        "reaper" to "icons/blizzard/btn-unit-terran-reaper.png",
        "ghost" to "icons/blizzard/btn-unit-terran-ghost.png",
        "hellion" to "icons/blizzard/btn-unit-terran-hellion.png",
        "hellbat" to "icons/blizzard/btn-unit-terran-hellbat.png",
        "widow_mine" to "icons/blizzard/btn-unit-terran-widowmine.png",
        "cyclone" to "icons/blizzard/btn-unit-terran-cyclone.png",
        "siege_tank" to "icons/blizzard/btn-unit-terran-siegetank.png",
        "thor" to "icons/blizzard/btn-unit-terran-thor.png",
        "viking" to "icons/blizzard/btn-unit-terran-vikingfighter.png",
        "medivac" to "icons/blizzard/btn-unit-terran-medivac.png",
        "liberator" to "icons/blizzard/btn-unit-terran-liberator.png",
        "banshee" to "icons/blizzard/btn-unit-terran-banshee.png",
        "raven" to "icons/blizzard/btn-unit-terran-raven.png",
        "battlecruiser" to "icons/blizzard/btn-unit-terran-battlecruiser.png",
        "firebat" to "icons/blizzard/btn-unit-terran-firebat.png",
        "vulture" to "icons/blizzard/btn-unit-terran-vulture.png",
        "wraith" to "icons/blizzard/btn-unit-terran-wraith.png",
        "science_vessel" to "icons/blizzard/btn-unit-terran-sciencevessel.png",
        "spectre" to "icons/original/btn-unit-terran-spectre.png",
        "warhound" to "icons/blizzard/btn-unit-terran-warhound.png",
        "diamondback" to "icons/blizzard/btn-unit-terran-diamondback.png",
        "hercules" to "icons/blizzard/btn-unit-terran-herc.png",

        // Protoss tabletop / common
        "probe" to "icons/blizzard/btn-unit-protoss-probe.png",
        "zealot" to "icons/blizzard/btn-unit-protoss-zealot.png",
        "praetor_guard" to "icons/blizzard/btn-unit-protoss-zealot.png",
        "stalker" to "icons/blizzard/btn-unit-protoss-stalker.png",
        "sentry" to "icons/blizzard/btn-unit-protoss-sentry.png",
        "adept" to "icons/blizzard/btn-unit-protoss-adept-purifier.png",
        "high_templar" to "icons/blizzard/btn-unit-protoss-hightemplar.png",
        "dark_templar" to "icons/blizzard/btn-unit-protoss-darktemplar.png",
        "archon" to "icons/blizzard/btn-unit-protoss-archon.png",
        "immortal" to "icons/blizzard/btn-unit-protoss-immortal.png",
        "colossus" to "icons/blizzard/btn-unit-protoss-colossus.png",
        "disruptor" to "icons/blizzard/btn-unit-collection-purifier-disruptor.png",
        "observer" to "icons/blizzard/btn-unit-protoss-observer.png",
        "warp_prism" to "icons/blizzard/btn-unit-protoss-warpprism.png",
        "phoenix" to "icons/blizzard/btn-unit-protoss-phoenix.png",
        "void_ray" to "icons/blizzard/btn-unit-protoss-voidray.png",
        "oracle" to "icons/blizzard/btn-unit-protoss-oracle.png",
        "tempest" to "icons/blizzard/btn-unit-protoss-tempest.png",
        "carrier" to "icons/blizzard/btn-unit-protoss-carrier.png",
        "mothership" to "icons/blizzard/btn-unit-protoss-mothership.png",
        "dragoon" to "icons/blizzard/btn-unit-protoss-dragoon.png",
        "reaver" to "icons/blizzard/btn-unit-protoss-reaver.png",
        "scout" to "icons/original/btn-unit-protoss-scout.png",
        "corsair" to "icons/blizzard/btn-unit-protoss-corsair.png",
        "fenix" to "icons/blizzard/btn-unit-protoss-fenix.png",
        "arbiter" to "icons/blizzard/btn-unit-protoss-arbiter.png",

        // Zerg tabletop / common
        "drone" to "icons/blizzard/btn-unit-zerg-drone.png",
        "zergling" to "icons/blizzard/btn-unit-zerg-zergling.png",
        "raptor_zergling" to "icons/blizzard/btn-unit-zerg-zergling-raptor.png",
        "swarmling_zergling" to "icons/blizzard/btn-unit-zerg-zergling-swarmling.png",
        "kerrigan_swarm_raptor" to "icons/blizzard/btn-unit-zerg-zergling-raptor.png",
        "baneling" to "icons/blizzard/btn-unit-zerg-baneling.png",
        "roach" to "icons/blizzard/btn-unit-zerg-roach.png",
        "corpser_roach" to "icons/blizzard/btn-unit-zerg-roach-corpser.png",
        "vile_roach" to "icons/blizzard/btn-unit-zerg-roach-vile.png",
        "roachling" to "icons/blizzard/btn-unit-zerg-roach.png",
        "ravager" to "icons/blizzard/btn-unit-zerg-ravager.png",
        "hydralisk" to "icons/blizzard/btn-unit-zerg-hydralisk.png",
        "lurker" to "icons/blizzard/btn-unit-zerg-lurker.png",
        "infestor" to "icons/blizzard/btn-unit-zerg-infestor.png",
        "swarm_host" to "icons/blizzard/btn-unit-zerg-swarmhost.png",
        "ultralisk" to "icons/blizzard/btn-unit-zerg-ultralisk.png",
        "mutalisk" to "icons/blizzard/btn-unit-zerg-mutalisk.png",
        "corruptor" to "icons/blizzard/btn-unit-zerg-corruptor.png",
        "brood_lord" to "icons/blizzard/btn-unit-zerg-broodlord.png",
        "overlord" to "icons/blizzard/btn-unit-zerg-overlord.png",
        "overseer" to "icons/blizzard/btn-unit-zerg-overseer.png",
        "queen" to "icons/blizzard/btn-unit-zerg-queen.png",
        "viper" to "icons/blizzard/btn-unit-zerg-viper.png",
        "scourge" to "icons/blizzard/btn-unit-zerg-scourge.png",
        "defiler" to "icons/original/btn-unit-zerg-defiler@scbw.png",
        "guardian" to "icons/blizzard/btn-unit-zerg-primalguardian.png",
        "devourer" to "icons/blizzard/btn-unit-zerg-devourerex3.png",
        "leviathan" to "icons/blizzard/btn-unit-zerg-leviathan.png",
        "kerrigan" to "icons/blizzard/btn-unit-zerg-kerriganinfested.png",
        "aberration" to "icons/blizzard/btn-unit-zerg-aberration.png"
    )

    private fun file(name: String): String = FILE_PATH + URLEncoder.encode(name, StandardCharsets.UTF_8.toString()).replace("+", "%20")

    private val unitFiles = mapOf(
        // Terran
        "scv" to "SCV SC2 Head1.jpg",
        "marine" to "Marine SC2 Head2.jpg",
        "raynors_raider_marine" to "Marine SC2 Head2.jpg",
        "marauder" to "Marauder SC2 Head1.jpg",
        "medic" to "Medic SC2 Head1.jpg",
        "goliath" to "Goliath SC2 Head1.jpg",
        "point_defense_drone" to "Raven SC2 Head1.jpg",
        "reaper" to "Reaper SC2 Head1.jpg",
        "ghost" to "Ghost SC2 Head2.jpg",
        "hellion" to "Hellion SC2 head1.jpg",
        "hellbat" to "Hellbat SC2-HotS Head1.jpg",
        "widow_mine" to "WidowMine SC2-HotS Head1.jpg",
        "cyclone" to "Cyclone SC2-LotV Head1.jpg",
        "siege_tank" to "SiegeTank SC2 Head1.jpg",
        "thor" to "Thor SC2 Head1.jpg",
        "viking" to "Viking SC2 Head1.jpg",
        "medivac" to "Medivac SC2 Head1.jpg",
        "liberator" to "Liberator SC2-LotV Portrait.jpg",
        "banshee" to "Banshee SC2 Head1.jpg",
        "raven" to "Raven SC2 Head1.jpg",
        "battlecruiser" to "Battlecruiser SC2 Head1.jpg",
        "firebat" to "Firebat SC2 Head1.jpg",
        "vulture" to "Vulture SC2 Head1.jpg",
        "wraith" to "Wraith SC2 Head1.jpg",
        "science_vessel" to "ScienceVessel SC2 Head1.jpg",
        "spectre" to "GabrielTosh SC2 Head2.jpg",
        "diamondback" to "Diamondback SC2 Head1.jpg",
        "warhound" to "Warhound SC2-HotS Head1.jpg",
        "hercules" to "HERC SC2-LotV Head1.jpg",
        "warbot" to "ARES SC2 Head1.jpg",
        "jim_raynor" to "JimRaynor SC2 Head2.jpg",
        "tychus" to "TychusFindlay SC2 Head1.jpg",
        "nova" to "Nova SC2 Head1.jpg",
        "swann" to "RorySwann SC2 Head1.jpg",
        "matt_horner" to "MattHorner SC2 Head1.jpg",
        "mira_han" to "MiraHan SC2 Head1.jpg",
        "mengsk" to "ArcturusMengsk SC2 Head3.jpg",
        "stukov" to "Infested Stukov SC2-HotS Portrait.jpg",

        // Protoss
        "probe" to "Probe SC2 Head1.jpg",
        "zealot" to "Zealot SC2 Head1.jpg",
        "praetor_guard" to "Zealot SC2 Head1.jpg",
        "stalker" to "Stalker SC2 Head1.jpg",
        "sentry" to "Sentry SC2 Head1.jpg",
        "adept" to "Adept SC2-LotV Head3.jpg",
        "high_templar" to "HighTemplar SC2 Head1.jpg",
        "dark_templar" to "DarkTemplar SC2 Head1.jpg",
        "archon" to "Archon SC2 Head1.jpg",
        "immortal" to "Immortal SC2 Head2.jpg",
        "colossus" to "Colossus SC2 Head1.jpg",
        "disruptor" to "Disruptor SC2 Portrait.jpg",
        "observer" to "Observer SC2 Head1.jpg",
        "warp_prism" to "WarpPrism SC2 Head1.jpg",
        "phoenix" to "Phoenix SC2 Head1.jpg",
        "void_ray" to "VoidRay SC2 Head1.jpg",
        "oracle" to "Oracle SC2-LotV Head1.jpg",
        "tempest" to "Tempest SC2-HotS Head1.jpg",
        "carrier" to "Carrier SC2 Head1.jpg",
        "mothership" to "Mothership SC2 Head1.jpg",
        "dragoon" to "Dragoon SC2 Portrait.jpg",
        "reaver" to "Reaver SC2-LotV Portrait.jpg",
        "corsair" to "Corsair SC2-LotV Portrait.jpg",
        "scout" to "Scout SC2-LotV Portrait.jpg",
        "arbiter" to "Arbiter SC2-LotV Portrait.jpg",
        "artanis" to "Artanis SC2 Head1.jpg",
        "zeratul" to "Zeratul SC2 Head1.jpg",
        "tassadar" to "Tassadar SC2 Head1.jpg",
        "selendis" to "Selendis SC2 Head1.jpg",
        "vorazun" to "Vorazun SC2-LotV Portrait.jpg",
        "karax" to "Karax SC2-LotV Portrait.jpg",
        "alarak" to "Alarak SC2-LotV Portrait.jpg",
        "fenix" to "Fenix SC2-LotV Portrait.jpg",
        "rohana" to "Rohana SC2-LotV Portrait.jpg",
        "kaldalis" to "Kaldalis SC2-LotV Head1.jpg",
        "karass" to "Karass SC2 Head1.jpg",

        // Zerg
        "drone" to "Drone SC2 Head1.jpg",
        "zergling" to "Zergling SC2 Head1.jpg",
        "raptor_zergling" to "Zergling SC2 Head1.jpg",
        "swarmling_zergling" to "Zergling SC2 Head1.jpg",
        "kerrigan_swarm_raptor" to "Zergling SC2 Head1.jpg",
        "baneling" to "Baneling SC2 Head1.jpg",
        "roach" to "Roach SC2 Head1.jpg",
        "corpser_roach" to "Corpser SC2-HotS Portrait.jpg",
        "vile_roach" to "EvolvedRoach LotV Head 1.jpg",
        "roachling" to "Roach SC2 Head1.jpg",
        "ravager" to "Ravager SC2-LotV Head1.jpg",
        "hydralisk" to "Hydralisk SC2 Head1.jpg",
        "lurker" to "Lurker HotS Head2.jpg",
        "infestor" to "Infestor SC2 DevHead1.jpg",
        "swarm_host" to "SwarmHost SC2-HotS Head1.jpg",
        "ultralisk" to "Ultralisk SC2 Head1.jpg",
        "mutalisk" to "Mutalisk SC2 Head1.jpg",
        "corruptor" to "Corruptor SC2 Head1.jpg",
        "brood_lord" to "BroodLord SC2 Head1.jpg",
        "overlord" to "Overlord SC2 Head1.jpg",
        "overseer" to "Overseer SC2 Head1.jpg",
        "queen" to "Queen SC2 Head1.jpg",
        "viper" to "Viper SC2-HotS Head1.jpg",
        "scourge" to "Scourge SC2-LotV Portrait.jpg",
        "defiler" to "Defiler SC2 Head1.jpg",
        "guardian" to "Guardian SCR Head1.jpg",
        "devourer" to "Devourer SC2-LotV Portrait.jpg",
        "aberration" to "Aberration SC2 Head1.jpg",
        "brutalisk" to "Brutalisk SC2 Head1.jpg",
        "leviathan" to "Leviathan SC2 Head1.jpg",
        "kerrigan" to "InfestedKerrigan SC2 Head2.jpg",
        "zagara" to "Zagara SC2-HotS Portrait.jpg",
        "abathur" to "Abathur SC2 Portrait.jpg",
        "dehaka" to "Dehaka SC2-LotV Portrait.jpg",

        // Hybrid / Amon
        "hybrid_destroyer" to "HybridDestroyer SC2 Head1.jpg",
        "hybrid_reaver" to "HybridReaver SC2 Head 1.JPEG",
        "hybrid_dominator" to "HybridDominator SC2-LotV Portrait.jpg",
        "hybrid_nemesis" to "HybridNemesis SC2-LotV Portrait.jpg",
        "hybrid_behemoth" to "HybridBehemoth SC2-LotV Portrait.jpg",
        "hybrid_maar" to "Maar SC2 Head1.jpg"
    )

    // Known animated portrait when available. Static direct assets remain the normal path.
    private val animatedUnitFiles = mapOf(
        "marine" to "Marine SC2 HeadAnim1.gif",
        "raynors_raider_marine" to "Marine SC2 HeadAnim1.gif"
    )

    // v1.4.38 uses the complete official TMG tactical-card library. The isolated artwork
    // is extracted directly from the editable image objects embedded in the official P2P PDFs.
    // No card-front crop or external portrait substitute is used.
    private const val BUNDLED_TACTIC_BASE = "file:///android_asset/images/tactics_hd/"
    private val bundledTabletopBuildingIds = setOf(
        "barracks", "engineering_bay", "academy", "supply_depot", "orbital_command",
        "dropship", "factory", "armory", "tech_lab", "barracks_proxy",
        "gate_chronoboosted", "gateway", "warp_prism", "overcharged_nexus",
        "twilight_council", "warp_gate", "nexus", "observer", "forge", "power_field",
        "overseer", "hatchery", "evolution_chamber", "hydralisk_den", "overlord",
        "lair", "roach_warren", "spawning_pool_six_pool", "spawning_pool"
    )

    // Tactics are card-art only. If a tactical card is not bundled from a TMG sheet, it is hidden.


    // High-quality local tabletop art used where the previous remote portrait/icon was
    // weak or unreliable. Keeping these in assets also means the selector never shows
    // an empty Queen card when a CDN asset fails.
    private val bundledUnitArt = mapOf(
        "jim_raynor" to "file:///android_asset/images/units_hd/jim_raynor.jpg",
        "kerrigan" to "file:///android_asset/images/units_hd/kerrigan.jpg",
        "queen" to "file:///android_asset/images/units_hd/queen.jpg"
    )

    fun unitUrl(id: String, preferAnimation: Boolean = true): String? {
        bundledUnitArt[id]?.let { return it }
        // Performance-first: direct static SC2 portrait PNGs win when indexed. Wiki
        // sources remain available for the long tail. Animated wiki GIFs are deliberately
        // not preferred here because they were the worst source of table-side stalls.
        // The asset explorer CDN serves compact direct PNGs and avoids the heavier
        // Blizzard gallery JPEGs / wiki redirects. Use it first where indexed.
        fastPortraitFiles[id]?.let { return FAST_PORTRAIT_BASE + it }
        officialPortraitUrls[id]?.let { return it }
        val source = unitFiles[id] ?: if (preferAnimation) animatedUnitFiles[id] else null
        return source?.let(::file)
    }

    fun unitIconUrl(id: String): String? = bundledUnitArt[id] ?: fastUnitIcons[id]?.let { ICON_BASE + it }

    fun buildingUrl(id: String): String? =
        if (id in bundledTabletopBuildingIds) BUNDLED_TACTIC_BASE + "$id.png" else null
}
