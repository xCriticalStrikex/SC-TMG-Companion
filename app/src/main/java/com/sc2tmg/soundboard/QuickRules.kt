package com.sc2tmg.soundboard

data class RuleCard(val title: String, val lines: List<String>)
data class KeywordEntry(
    val term: String,
    val text: String,
    val details: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val source: String = "Core Rules"
)

data class ManualSection(val title: String, val page: Int)
data class ManualPart(
    val number: Int,
    val title: String,
    val page: Int,
    val summary: String,
    val sections: List<ManualSection>
)

object QuickRules {
    val roundSequence = listOf(
        "Start of Round effects",
        "1 · MOVEMENT — Deploy / Move / Disengage / Hold",
        "2 · ASSAULT — Ranged Attack / Charge / Run / Hold",
        "3 · COMBAT — Close Combat Attack",
        "4 · SCORING & CLEANUP — Control → VP → End check → End effects → Cleanup → Initiative"
    )

    val cards = listOf(
        RuleCard("PRE-GAME", listOf(
            "Select a Mission Card and a Deployment Card.",
            "Set Mission Markers and terrain.",
            "Roll-Off; winner assigns the First Player Marker for Round 1."
        )),
        RuleCard("PHASE 1 · MOVEMENT", listOf(
            "Each activated Unit chooses one: Move, Deploy, Disengage, or Hold.",
            "Deploy needs Available Supply and must end legally in Coherency.",
            "A Deploy cannot end inside the opponent's Zone of Influence.",
            "Ravager Corrosive Bile is launched in Movement; each launch is a separate pending Bile instance.",
            "Sentry Force Field is placed in Movement; each placed field is tracked independently until removed.",
            "First player to Pass takes First Player for Phase 2."
        )),
        RuleCard("PHASE 2 · ASSAULT", listOf(
            "Each activated Unit chooses one: Ranged Attack, Charge, Run, or Hold.",
            "Ranged Attack: declare target → build attack pool → hit → surge → armour → evade → damage & casualties.",
            "Charge: declare Enemy Ground targets → roll Speed + 1D6 → the Leading Model must be able to finish Within 1\" of every declared target.",
            "A failed Charge does not move the Unit and immediately ends its activation.",
            "END OF ASSAULT: resolve every pending Corrosive Bile separately before beginning Combat.",
            "First player to Pass takes First Player for Phase 3."
        )),
        RuleCard("PHASE 3 · COMBAT", listOf(
            "All Engaged Units must activate; players alternate. Pass only when no Engaged Units remain.",
            "A Close Combat Attack begins with an optional Close Ranks move up to 3\"; the Leading Model must end closer to the enemy.",
            "Eligible attackers are the Fighting Rank (Within 1\" of an Enemy model) plus the Supporting Rank (Base-to-Base with a Fighting Rank model)."
        )),
        RuleCard("PHASE 4 · SCORING & CLEANUP", listOf(
            "Control: sum Supply Within 3\" of each Mission Marker; higher total controls and ties are Contested.",
            "Score Victory Points according to the Mission Card, then check end-of-game conditions.",
            "Resolve End-of-Round decisions first (for example Adept Shade and delayed deployment indicators), then Cleanup & Refresh.",
            "Cleanup expires round-limited tracked effects; persistent STAY IN PLAY effects remain until their real tabletop removal event.",
            "Then determine Initiative: fewer VP takes First Player; ties use a Roll-Off."
        )),
        RuleCard("FAQ · HANDY REMINDERS", listOf(
            "Reactions can resolve outside an activation when their trigger is met; outside activations, only one Reaction per trigger.",
            "Abilities normally do not function while a Unit is in Reserves unless they explicitly say they do.",
            "Target numbers cannot be modified below 2+ or above 6+.",
            "'Select' and 'target' are mechanically synonymous.",
            "PLACE is not movement: it cannot remove a Force Field or trigger Creep removal by itself."
        ))
    )

    val manualParts = listOf(
        ManualPart(1, "LEARN TO PLAY", 5, "Worked opening rounds showing deployment, movement, attacks, combat and scoring in context.", listOf(
            ManualSection("Worked Example: Starting Rounds", 5), ManualSection("Round 1: Movement Phase", 6), ManualSection("Round 1: Assault Phase", 10), ManualSection("Round 1: Combat Phase", 13), ManualSection("Round 1: Scoring Phase", 14), ManualSection("Round 2", 14)
        )),
        ManualPart(2, "CORE CONCEPTS", 28, "Models, Units, bases, tags, player roles, rule priority and the three ability types.", listOf(
            ManualSection("Models", 28), ManualSection("Units", 28), ManualSection("Bases", 28), ManualSection("Tags", 29), ManualSection("Player Roles", 29), ManualSection("Keywords and Rule Priority", 29), ManualSection("Ability Types", 29)
        )),
        ManualPart(3, "DICE AND ROLLING", 31, "Roll-offs, re-rolls, modifiers, fixed additions, automatic results, tests and cocked dice.", listOf(
            ManualSection("Dice", 31), ManualSection("Roll-Offs", 31), ManualSection("Re-Rolls", 31), ManualSection("Modifiers", 31), ManualSection("Fixed Additions", 31), ManualSection("Automatic Results", 31), ManualSection("Tests", 31)
        )),
        ManualPart(4, "MEASURING AND MOVEMENT", 33, "Distance measurement, Within vs Wholly Within, Leading Models, Coherency and movement geometry.", listOf(
            ManualSection("Measuring Distances", 33), ManualSection("Within vs. Wholly Within", 34), ManualSection("The Leading Model", 35), ManualSection("Unit Coherency", 35), ManualSection("Directly Towards / Away", 37), ManualSection("Gap Clearance and Model Size", 37)
        )),
        ManualPart(5, "CARDS AND CHARACTERISTICS", 38, "How Unit, Tactical, Faction, Mission and Deployment cards are read and used.", listOf(
            ManualSection("Unit Cards", 38), ManualSection("Special Rules and Upgrades", 39), ManualSection("Tactical Cards", 40), ManualSection("Faction Cards", 41), ManualSection("Mission Cards", 42), ManualSection("Deployment Cards", 43)
        )),
        ManualPart(6, "THE SUPPLY SYSTEM", 44, "Supply Profiles, Current Supply and how Available Supply limits deployment.", listOf(
            ManualSection("The Supply Profile", 44), ManualSection("How Supply Is Used", 44)
        )),
        ManualPart(7, "THE BATTLEFIELD", 45, "Line of Sight, cover, elevation, engagement, markers, tokens and destroyed Units.", listOf(
            ManualSection("Line of Sight", 45), ManualSection("Cover", 46), ManualSection("Verticality and Effective Size", 48), ManualSection("High Ground Cover", 49), ManualSection("Engagement and Engagement Range", 53), ManualSection("Markers and Tokens", 53), ManualSection("Destroyed Units", 54)
        )),
        ManualPart(8, "THE GAME SEQUENCE", 54, "Rounds, initiative, deployment and every action in all four game phases.", listOf(
            ManualSection("Rounds and Phases", 54), ManualSection("The Activation System", 54), ManualSection("Passing and Initiative", 55), ManualSection("Deployment and Reserves", 55), ManualSection("Movement Phase Actions", 56), ManualSection("Assault Phase Actions", 60), ManualSection("Charge", 65), ManualSection("Combat Phase", 67), ManualSection("Scoring & Cleanup", 69), ManualSection("Determine Initiative", 72), ManualSection("The Final Score", 72)
        )),
        ManualPart(9, "PREPARING FOR BATTLE", 73, "Army building, missions, deployment selection and battlefield setup.", listOf(
            ManualSection("Army Building", 73), ManualSection("Tactical Cards and Army Slots", 74), ManualSection("Mustering Units", 74), ManualSection("Purchasing Upgrades", 75), ManualSection("Team Games", 76), ManualSection("Mission Selection and Draft", 77), ManualSection("Battlefield Setup", 78)
        )),
        ManualPart(10, "ADVANCED RULES", 79, "Full timing and restrictions for abilities, reactions, Tactical Cards and Faction Cards.", listOf(
            ManualSection("Special Abilities", 79), ManualSection("Active Abilities", 79), ManualSection("Passive Abilities", 80), ManualSection("Reaction Abilities", 80), ManualSection("Tactical Cards & Faction Cards", 81), ManualSection("Resources and Costs", 81), ManualSection("Managing Card States", 81)
        )),
        ManualPart(11, "KEYWORD GLOSSARY & DEFINITIONS", 82, "Authoritative definitions for game keywords, statuses and common rules terms.", listOf(
            ManualSection("Keyword Glossary and Definitions", 82)
        )),
        ManualPart(12, "QUICK REFERENCE", 92, "Condensed pre-game, round, phase, casualty, template and dispute procedures plus army references.", listOf(
            ManualSection("Pre-Game Protocol", 92), ManualSection("Round Sequence", 92), ManualSection("Phase 1: Movement", 92), ManualSection("Phase 2: Assault", 92), ManualSection("Phase 3: Combat", 93), ManualSection("Phase 4: Scoring & Cleanup", 93), ManualSection("Casualty Removal", 93), ManualSection("Template Weapons", 94), ManualSection("Dispute Resolution", 94), ManualSection("Units and Upgrade Points", 94), ManualSection("Tactical Card Points", 107), ManualSection("Example Armies", 108)
        ))
    )

    val keywords = listOf(
        KeywordEntry(
            "Charge",
            "Phase 2 Assault action: an Unengaged Ground Unit attempts to move into Engagement Range of one or more Enemy Ground Units.",
            details = listOf(
                "Declare every intended Enemy Ground target before rolling. A Charge does not require Line of Sight.",
                "Choose the Leading Model and one model in each target Unit. Roll 1D6 + the Unit's Speed; this is the Charge Roll Distance.",
                "The Charge succeeds only if the Leading Model can be legally placed Wholly Within that distance, Within Engagement Range (1\") of every declared target, not overlapping bases/impassable terrain, and not Within Engagement Range of any undeclared Enemy Unit.",
                "If it fails, the Unit does not move and its activation ends immediately.",
                "If it succeeds, move the Leading Model and set the rest In Coherency: Base-to-Base with declared targets if possible, then Within Engagement Range, then as close as possible to the Leading Model."
            ),
            aliases = listOf("charging", "charge action", "charge distance"),
            source = "Core Rules §8.7.7 · Quick Reference §12.4"
        ),
        KeywordEntry("Activation", "During Phases 1, 2 and 3 players alternate activations. An activated Unit performs one action available in that phase.", aliases = listOf("activate")),
        KeywordEntry("Close Ranks", "Optional first step of a Close Combat Attack. Move the Leading Model up to 3 inches and end closer to the enemy, then reposition the Unit."),
        KeywordEntry("Coherency", "Units must be placed and finish movement in legal Unit Coherency. The Leading Model moves; remaining models are set according to coherency rules."),
        KeywordEntry("Deploy", "Movement Phase action that brings a Unit from Reserves via an Entry Edge. It requires Available Supply and normally cannot end in the Enemy Zone of Influence.", aliases = listOf("deployment")),
        KeywordEntry("Disengage", "Movement Phase action for an Engaged Unit to move out of combat. It can impose restrictions on Ranged Attack or Charge in the following phase."),
        KeywordEntry("Engagement Range", "The range used to determine whether Units are Engaged. Charge success and close combat positioning depend on it.", aliases = listOf("engaged")),
        KeywordEntry("Fighting Rank", "Models Within 1 inch of an Enemy model. Fighting Rank models are eligible to attack in close combat."),
        KeywordEntry("Supporting Rank", "Models Base-to-Base with a Fighting Rank model. Supporting Rank models are also eligible to attack in close combat."),
        KeywordEntry("First Player", "The marker tracks initiative. The holder decides which player activates first at the beginning of each phase."),
        KeywordEntry("Hold", "A legal action in Movement or Assault in which the Unit takes no other action."),
        KeywordEntry("Mission Marker", "Objective marker used for control and scoring. Phase 4 control normally compares Supply Within 3 inches."),
        KeywordEntry("Reaction", "A triggered Special Ability. FAQ clarifies Reactions can resolve outside an activation when their trigger is met; outside activations only one Reaction may resolve per trigger.", aliases = listOf("reactions"), source = "Core Rules §10.4 · FAQ"),
        KeywordEntry("Reserves", "Units in Reserves normally cannot use abilities or benefit from them unless a rule explicitly says it functions in Reserves."),
        KeywordEntry("Run", "Assault Phase action that moves a Unit instead of making a Ranged Attack, Charge or Hold."),
        KeywordEntry(
            "Ranged Attack",
            "Assault Phase action used by an eligible Unit to resolve one or more Ranged Weapon batches against legal targets.",
            details = listOf(
                "Resolve each weapon Batch separately: declare target, build the Attack Pool, roll to hit, resolve Surge, Armour, Evade if eligible, then Damage and casualties.",
                "Targets and attacks must obey the weapon's Range, Target characteristic, Line of Sight and any keyword restrictions or exceptions."
            ),
            aliases = listOf("ranged attacks", "shoot", "shooting"),
            source = "Core Rules §8.7"
        ),
        KeywordEntry("Supply", "Available Supply equals the Supply Pool minus the Total Supply of Friendly Units on the battlefield."),
        KeywordEntry("Zone of Influence", "A deployment/movement restriction area associated with a player's primary Entry Edges. Some special rules create exceptions."),
        KeywordEntry(
            "Armour Roll",
            "The Armour characteristic is the D6 Target Number used to cancel hits during an Armour Roll.",
            details = listOf(
                "After hits and Surge are resolved, roll every die in the Armour Pool.",
                "Each result equal to or higher than the Unit's Armour characteristic is a success and that die is discarded; failures move to the Damage Pool."
            ),
            aliases = listOf("armour", "armor", "armor roll"),
            source = "Core Rules §5.1 · §8.7.4"
        ),
        KeywordEntry(
            "Evade Roll",
            "A Unit does not automatically make an Evade Roll just because it has an Evade value. Its Evade characteristic is used only when a rule, condition, keyword, or ability specifically grants an Evade Roll.",
            details = listOf(
                "Core grants include: HIGH GROUND Cover against Ranged Attacks from a lower elevation; HIDDEN and BURROWED against attacks targeting the Unit; INDIRECT FIRE when the target is not within Line of Sight; and an Engaged target suffering Damage from a Ranged Attack.",
                "Unit abilities, upgrades, Tactical Cards, and Faction effects can also explicitly make a Unit eligible to make an Evade Roll.",
                "When eligible, roll the Damage Pool after Armour. Each result equal to or higher than the Unit's Evade characteristic removes one die from the Damage Pool.",
                "If the Unit's Evade characteristic is '-' it cannot make Evade Rolls, even if an ability would otherwise grant one."
            ),
            aliases = listOf("evade", "evading", "evade rolls", "get evade", "gain evade", "evade eligibility"),
            source = "Core Rules §5.1 · §7.1.3 · §8.7.4 · Part 11"
        ),
        KeywordEntry(
            "ANTI-EVADE",
            "When resolving an attack with a weapon that has ANTI-EVADE (X), the target Unit suffers a -X Modifier to its Evade Roll for that attack.",
            details = listOf(
                "ANTI-EVADE modifies an Evade Roll; it does not itself grant or remove eligibility to make one.",
                "The FAQ confirms modifiers cannot push a Target Number beyond the normal 2+ to 6+ limits, so ANTI-EVADE cannot erase an Evade Roll entirely."
            ),
            aliases = listOf("anti evade", "antie evade", "anti-evade"),
            source = "Core Rules Part 11 · FAQ"
        ),
        KeywordEntry("Surge", "If the target Combat Tag matches the weapon's Surge Type, dice equal to the Surge Die are moved past the Armour Roll into the Damage Pool."),
        KeywordEntry(
            "HIDDEN",
            "A HIDDEN Unit cannot be selected as the target of a Ranged Attack or LoS-requiring Special Ability unless the acting model is Within 4\" of it.",
            details = listOf(
                "A HIDDEN Unit is immune to IMPACT.",
                "A HIDDEN Unit may make an Evade Roll against every attack targeting it.",
                "HIDDEN is a Status; detection-style effects can remove it when their stated conditions are met."
            ),
            aliases = listOf("hidden status", "hide"),
            source = "Core Rules Part 11 · FAQ"
        ),
        KeywordEntry(
            "BURROWED",
            "BURROWED is a Status that restricts a Unit's available actions but grants defensive and movement interactions.",
            details = listOf(
                "A BURROWED Unit may make an Evade Roll against every attack targeting it.",
                "While BURROWED it may only perform the actions listed by the BURROWED rule; performing most of those movement actions removes the Status.",
                "Other models may move through BURROWED models provided they do not finish within that Unit's Engagement Range."
            ),
            aliases = listOf("burrow", "burrowed status"),
            source = "Core Rules Part 11"
        ),
        KeywordEntry(
            "HIGH GROUND",
            "A model is on HIGH GROUND when standing on horizontal terrain of Size 3 or larger.",
            details = listOf(
                "If all models of a defending Unit are on HIGH GROUND, that Unit becomes eligible to make an Evade Roll against Ranged Attacks originating from a lower elevation.",
                "Flying Units never benefit from HIGH GROUND Cover."
            ),
            aliases = listOf("high ground cover", "highground"),
            source = "Core Rules §7.1.3 · Part 11"
        ),
        KeywordEntry(
            "INDIRECT FIRE",
            "A Ranged Attack with INDIRECT FIRE may ignore Line of Sight when selecting a target and resolving Damage, but the target must still be Within Range.",
            details = listOf(
                "If the target is not within Line of Sight, it may make an Evade Roll against the attack.",
                "If at least one model in the target Unit is visible, the FAQ says the Unit is considered within Line of Sight and does not gain the out-of-sight Evade Roll."
            ),
            aliases = listOf("indirect", "indirect-fire"),
            source = "Core Rules Part 11 · FAQ"
        ),
        KeywordEntry("SHIELDED", "FAQ: SHIELDED is lost if Total Damage exceeds the Shield value or the first model is removed. Once lost it cannot be restored by healing.", source = "FAQ"),
        KeywordEntry(
            "PLACE",
            "PLACE (X) is a reposition effect: choose a Leading Model, remove it, and set it Wholly Within X\" of its starting position. Then remove and replace the remaining models in the Unit in legal Coherency. The models are set directly; they do not travel along a movement path.",
            details = listOf(
                "PLACE ignores Gap Clearance and elevation requirements.",
                "The Unit must finish in a legal position and normally cannot finish Within the Engagement Range of an Enemy model.",
                "During the Assault Phase, a PLACE effect may set models Within Enemy Engagement Range; the Unit then becomes Engaged.",
                "PLACE is not Move, Deploy, Run, Charge or Disengage. Effects that trigger specifically from those movement actions do not trigger from PLACE (for example Creep removal or Force Field removal)."
            ),
            aliases = listOf("place effect", "placed", "placing"),
            source = "Core Rules Part 11 · PLACE (X) · FAQ"
        )
    )
}
