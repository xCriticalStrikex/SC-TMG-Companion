package com.sc2tmg.soundboard

import android.content.Context
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RuleHit(val source: String, val snippet: String, val score: Int)
data class RuleBrowseItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val badge: String = ""
)
data class RuleAnswer(
    val title: String,
    val summary: String,
    val bullets: List<String> = emptyList(),
    val source: String,
    val excerpt: String? = null,
    val confidence: String = "VERIFIED",
    val interpretedAs: String? = null,
    val suggestions: List<String> = emptyList(),
    val visualAsset: String? = null,
    val visualCaption: String? = null,
    val visualAspectRatio: Float = 1.46f
)

/**
 * FIELD MANUAL structured rules engine.
 *
 * Natural-language lookup is deliberately a ROUTER, not an answer generator. It ranks verified,
 * structured entries (curated table questions, the official glossary, clean FAQ Q/A pairs, and
 * printed card abilities) and then returns the stored source-backed answer. Raw PDF/OCR text is
 * never allowed to become an ASK answer; it exists only behind Exact Source Search.
 */
class RulesEngine(private val context: Context) {
    private data class SourceChunk(val raw: String, val normalized: String)
    private data class SourceDoc(val name: String, val chunks: List<SourceChunk>, val raw: String)
    private data class FaqEntry(val index: Int, val question: String, val answer: String, val normalized: String)
    private data class GlossaryEntry(
        val term: String,
        val text: String,
        val details: List<String>,
        val aliases: List<String>,
        val source: String
    )
    private data class AbilityVariant(
        val faction: String,
        val name: String,
        val type: String,
        val text: String,
        val source: String
    )
    private data class AbilityEntry(
        val faction: String,
        val name: String,
        val variants: List<AbilityVariant>
    )
    private data class AskTopic(
        val id: String,
        val title: String,
        val understoodAs: String,
        val aliases: List<String>,
        val requiredGroups: List<Set<String>>,
        val summary: String,
        val bullets: List<String>,
        val source: String,
        val visualAsset: String? = null,
        val visualCaption: String? = null,
        val visualAspectRatio: Float = 1.46f
    )
    private enum class CandidateKind { TOPIC, GLOSSARY, FAQ, ABILITY }
    private data class Candidate(
        val key: String,
        val kind: CandidateKind,
        val label: String,
        val subtitle: String,
        val searchTexts: List<String>,
        val faction: String? = null
    )
    private data class ScoredCandidate(val candidate: Candidate, val score: Int, val coverage: Double)

    private val docs: List<SourceDoc> by lazy {
        listOf(
            source("Core Rules", "rules/core_rules.txt"),
            source("FAQ", "rules/faq.txt"),
            source("Protoss Cards", "rules/protoss_cards.txt"),
            source("Terran Cards", "rules/terran_cards.txt"),
            source("Zerg Cards", "rules/zerg_cards.txt")
        )
    }

    private val faqEntries: List<FaqEntry> by lazy {
        runCatching {
            context.assets.open("rules/faq_qa.tsv").bufferedReader().useLines { lines ->
                lines.mapIndexedNotNull { index, line ->
                    val split = line.split('\t', limit = 2)
                    if (split.size != 2) return@mapIndexedNotNull null
                    val q = split[0].trim()
                    val a = split[1].trim()
                    if (q.isBlank() || a.isBlank()) return@mapIndexedNotNull null
                    FaqEntry(index, q, a, semanticNormalize(q))
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private val officialGlossary: List<GlossaryEntry> by lazy {
        runCatching {
            context.assets.open("rules/glossary.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val split = line.split('\t', limit = 3)
                    if (split.size < 2) return@mapNotNull null
                    val term = split[0].trim()
                    val text = split[1].trim()
                    val source = split.getOrNull(2)?.trim().orEmpty().ifBlank { "Core Rules Part 11" }
                    if (term.isBlank() || text.isBlank()) return@mapNotNull null
                    GlossaryEntry(term, text, emptyList(), emptyList(), source)
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private val glossaryEntries: List<GlossaryEntry> by lazy {
        val curated = QuickRules.keywords.map {
            GlossaryEntry(it.term, it.text, it.details, it.aliases, it.source)
        }
        val curatedByKey = curated.associateBy { keywordKey(it.term) }
        val merged = officialGlossary.map { official -> curatedByKey[keywordKey(official.term)] ?: official }.toMutableList()
        val present = merged.map { keywordKey(it.term) }.toMutableSet()
        curated.forEach { if (present.add(keywordKey(it.term))) merged += it }
        merged.distinctBy { keywordKey(it.term) }.sortedBy { displayKeyword(it.term).lowercase() }
    }

    private val abilityEntries: List<AbilityEntry> by lazy {
        runCatching {
            val rows = context.assets.open("rules/abilities.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val split = line.split('\t', limit = 5)
                    if (split.size < 4) return@mapNotNull null
                    AbilityVariant(
                        faction = split[0].trim().uppercase(),
                        name = split[1].trim(),
                        type = split[2].trim().uppercase(),
                        text = split[3].trim(),
                        source = split.getOrNull(4)?.trim().orEmpty().ifBlank { "${split[0].trim().lowercase().replaceFirstChar { it.uppercase() }} Cards" }
                    )
                }.toList()
            }
            rows.groupBy { it.faction to it.name }
                .map { (key, values) ->
                    val unique = values.distinctBy { normalize(it.text) }
                    AbilityEntry(key.first, key.second, unique)
                }
                .sortedWith(compareBy<AbilityEntry> { it.faction }.thenBy { it.name })
        }.getOrDefault(emptyList())
    }

    private val askTopics: List<AskTopic> = listOf(
        AskTopic(
            id = "engagement_rules",
            title = "ENGAGEMENT RULES",
            understoodAs = "What restrictions apply while a Unit is Engaged?",
            aliases = listOf(
                "what restrictions apply while engaged", "engagement restrictions", "engaged restrictions",
                "can a flying unit be engaged", "can flying units be engaged", "flying engagement"
            ),
            requiredGroups = listOf(setOf("engaged", "engagement", "restriction", "flying")),
            summary = "A Ground Unit is Engaged when any of its models is within 1 inch of an Enemy Ground model and the terrain, elevation, and Combat Tag conditions allow engagement. Flying Units cannot be Engaged.",
            bullets = listOf(
                "If any model in a Unit is Engaged, the entire Unit is treated as Engaged.",
                "An Engaged Unit cannot make a standard Move; in the Movement Phase it must Disengage or Hold.",
                "Engaged Units are subject to Ranged Attack restrictions and participate in the Combat Phase under the close-combat rules.",
                "Flying Units cannot Charge or be Charged and cannot participate in the Combat Phase."
            ),
            source = "Core Rules §7.2–7.2.1 · Part 11, ENGAGED / FLYING"
        ),
        AskTopic(
            id = "summoned_units",
            title = "SUMMONED UNITS",
            understoodAs = "How do Summoned Units work?",
            aliases = listOf("how do summoned units work", "summoned units", "what is a summoned unit", "roaching summoned", "point defense drone summoned", "pylon summoned"),
            requiredGroups = listOf(setOf("summon", "summoned", "unit", "roachling", "pylon", "drone")),
            summary = "Summoned Units are zero-Mineral Units that are not included in the Army List or Reserves at the start of the game. They enter play only when a Special Ability specifically sets or SUMMONs them.",
            bullets = listOf(
                "They do not occupy Army Slots and cannot use normal deployment from Reserves.",
                "Once on the battlefield they are Friendly Units for normal rules purposes unless stated otherwise.",
                "Their Current Supply counts toward Total Current Supply while they are on the battlefield, but Summoned Units are not used for The Final Score.",
                "A SUMMON effect still requires sufficient Available Supply when the effect says so."
            ),
            source = "Core Rules §9.1.9 · Part 11, SUMMON"
        ),
        AskTopic(
            id = "priority_rule",
            title = "RULE PRIORITY",
            understoodAs = "How does rule priority work?",
            aliases = listOf("priority rule", "what is the priority rule", "rule priority", "which rule wins", "conflicting rules"),
            requiredGroups = listOf(setOf("priority", "rule", "conflict", "wins")),
            summary = "When rules conflict, apply the game's stated rule-priority system rather than trying to combine incompatible instructions. Specific exceptions and effects that explicitly override a core restriction take precedence over the general rule they override.",
            bullets = listOf(
                "Resolve the most specific applicable instruction for the situation.",
                "An effect that explicitly says it overrides another Part or restriction does so only for the scope it states.",
                "If an unresolved dispute remains, use the dispute-resolution procedure rather than inventing an interpretation."
            ),
            source = "Core Rules §2.6.2 · §12.9"
        ),
        AskTopic(
            id = "dice_and_rolling_basics",
            title = "DICE & ROLLING",
            understoodAs = "How do the basic dice rules work?",
            aliases = listOf(
                "what is a roll-off", "roll off", "how do rerolls work", "rerolls", "re-rolls",
                "what are fixed additions", "fixed additions", "what are automatic results", "automatic results",
                "what is a test roll", "what is a test", "tests", "what is a cocked die", "cocked dice"
            ),
            requiredGroups = listOf(setOf("roll", "reroll", "dice", "addition", "automatic", "test", "cocked")),
            summary = "StarCraft TMG uses D6s. Roll-Offs use 2D6 per player; re-rolls replace the original result; Tests compare a roll with a Target Number; and cocked or otherwise invalid dice are re-rolled.",
            bullets = listOf(
                "Roll-Off: both players roll 2D6; higher total wins, and ties are re-rolled.",
                "Re-roll: pick up the die and roll again; the new result replaces the old one even if worse.",
                "Fixed additions such as D3+1 generate a value and are not Target Number modifiers.",
                "Natural 6 always succeeds and natural 1 always fails on a Test.",
                "A die that does not land flat, goes off the table, or has an unclear result is invalid and is rolled again."
            ),
            source = "Core Rules §3.1–3.8"
        ),
        AskTopic(
            id = "measuring_distances",
            title = "MEASURING DISTANCES",
            understoodAs = "How are distances measured?",
            aliases = listOf("how do i measure distances", "measuring distances", "measure distance", "can i premeasure", "pre measuring"),
            requiredGroups = listOf(setOf("measure", "distance", "premeasure")),
            summary = "Distances are measured in inches. Players may pre-measure any distance at any time, and distances between models are measured from the nearest point of one base to the nearest point of the other base unless a rule says otherwise.",
            bullets = listOf(
                "Use a tape measure or ruler and measure base-to-base from the closest points.",
                "There is no restriction on pre-measuring, including before declaring actions.",
                "Where a rule specifies horizontal measurement, resolve it from the top-down perspective described by the movement and battlefield rules."
            ),
            source = "Core Rules §4.1"
        ),
        AskTopic(
            id = "cover_rules",
            title = "COVER",
            understoodAs = "How do Full Cover, Direct Cover, and Flying cover work?",
            aliases = listOf(
                "what is full cover", "full cover", "what is direct cover", "direct cover", "how does cover work",
                "how does cover work for flying units", "flying unit cover", "flying cover"
            ),
            requiredGroups = listOf(setOf("cover", "full", "direct", "flying")),
            summary = "Cover is resolved as part of Line of Sight. Blocking terrain can create Full Cover or Direct Cover depending on the relative Effective Sizes and the model's position; Flying models ignore Full Cover but Direct Cover and the Elevation Dead Zone can still apply.",
            bullets = listOf(
                "A terrain trace by itself does not automatically block Line of Sight; check the Cover conditions.",
                "Direct Cover requires the Line of Sight trace to pass through the terrain and the target model to be Within 1 inch of that same terrain piece.",
                "Flying models ignore Full Cover and do not gain High Ground Cover."
            ),
            source = "Core Rules §7.1.1–7.1.4 · Official FAQ, The Battlefield"
        ),
        AskTopic(
            id = "activation_actions",
            title = "ACTIVATIONS & BASIC ACTIONS",
            understoodAs = "How do Activations and basic actions work?",
            aliases = listOf(
                "how do activations work", "activation system", "how does hold work", "hold action", "how does a move action work", "move action",
                "how does run work", "run action"
            ),
            requiredGroups = listOf(setOf("activation", "hold", "move", "run", "action")),
            summary = "Players alternate Activating eligible Units during a Phase. An Activated Unit performs one standard action allowed in that Phase unless a rule grants something additional, then its Activation ends and it is marked Activated.",
            bullets = listOf(
                "Movement Phase actions are Deploy, Move, Disengage, or Hold.",
                "Assault Phase actions are Ranged Attack, Charge, Run, or Hold.",
                "Hold activates the Unit but deliberately does nothing.",
                "A Move uses the Unit's movement rules; a Run is the Assault Phase movement action."
            ),
            source = "Core Rules §8.2 · §8.4–8.7 · Quick Reference §12.3–12.4"
        ),
        AskTopic(
            id = "ranged_attack_resolution",
            title = "RANGED ATTACK RESOLUTION",
            understoodAs = "How is a Ranged Attack declared and resolved?",
            aliases = listOf(
                "how do ranged attacks work", "how do i choose a target for a ranged attack", "choose target ranged attack",
                "how do i build the attack pool", "build attack pool", "how do hit rolls work", "how does armour work",
                "how is damage applied", "ranged attack sequence"
            ),
            requiredGroups = listOf(setOf("ranged", "attack", "target", "pool", "hit", "armour", "damage")),
            summary = "A Ranged Attack is resolved weapon Batch by weapon Batch: declare a legal target, determine eligible firing models and build the Attack Pool, roll to Hit, resolve Surge and other attack effects, make Armour and any eligible Evade rolls, then assign Damage and remove casualties.",
            bullets = listOf(
                "Range and visibility are checked for the attacking models when establishing the Batch.",
                "Successful Hit dice move into the Armour Pool; Surge can move qualifying dice onward before the Armour Roll.",
                "Successful Armour and eligible Evade results discard dice; remaining dice generate Damage using the weapon's DMG value.",
                "Different weapon types are separate Batches, and the next target can be declared after the previous Batch resolves."
            ),
            source = "Core Rules §8.7.3–8.7.4 · Quick Reference §12.4"
        ),
        AskTopic(
            id = "casualty_removal",
            title = "CASUALTY REMOVAL",
            understoodAs = "How are casualties removed?",
            aliases = listOf(
                "how are casualties removed", "casualty removal", "remove casualties", "can i kill a model outside weapon range",
                "model outside weapon range casualty", "outside range casualty"
            ),
            requiredGroups = listOf(setOf("casualty", "casualties", "remove", "kill", "range")),
            summary = "After Damage is determined, models are removed according to the casualty rules. For a normal Ranged Attack, casualty eligibility is governed by visibility rather than whether each individual casualty model was within the weapon's Range.",
            bullets = listOf(
                "The FAQ confirms that a visible model in the target Unit can be removed even if that specific model is physically outside the firing weapon's Range.",
                "Engaged Units use the special engaged-casualty priority instead of normal Visibility rules.",
                "When several enemy Units are involved in an Engagement, casualty choices must preserve each Engagement where another valid casualty exists."
            ),
            source = "Core Rules §8.7.4–8.7.5 · Official FAQ, Attack Sequence"
        ),
        AskTopic(
            id = "template_spillover",
            title = "TEMPLATES & SPILLOVER",
            understoodAs = "How do template weapons and Spillover work?",
            aliases = listOf("how do template weapons work", "template weapons", "how does spillover work", "spillover", "blast template"),
            requiredGroups = listOf(setOf("template", "spillover", "blast")),
            summary = "Template attacks use the template rules to determine the Main Target and additional models caught by Spillover. Spillover attacks are resolved as separate attack Batches and therefore do not automatically inherit weapon-keyword effects from the Main Target's Batch.",
            bullets = listOf(
                "Spillover targets must satisfy the relevant elevation and Combat Tag requirements described by the template rules.",
                "The FAQ says PRECISION and CRITICAL HIT apply only to the Main Target's Attack Pool, not the separate Spillover Batches.",
                "Guardian Shield applies to each Spillover Batch individually because each is a separate Ranged Attack Batch."
            ),
            source = "Core Rules §8.7.6 · Official FAQ, Templates & Spillover"
        ),
        AskTopic(
            id = "charge_rules",
            title = "CHARGE",
            understoodAs = "How does a Charge work?",
            aliases = listOf("how does charging work", "how does charge work", "charge rules", "how far can i charge", "charge distance"),
            requiredGroups = listOf(setOf("charge", "charging", "distance")),
            summary = "A Charge is an Assault Phase action against Enemy Ground Units. It does not require Line of Sight. Determine Charge Distance as instructed by the Charge rules, move the Leading Model legally to engage all declared targets, then set the remaining models in Coherency.",
            bullets = listOf(
                "The standard Charge Distance uses the Unit's Speed plus the Charge roll.",
                "A successful Charge must end with the Leading Model Within Engagement Range of every declared target.",
                "After the Leading Model moves, place the remaining models according to Coherency and the Charge restrictions."
            ),
            source = "Core Rules §8.7.7 · Quick Reference §12.4"
        ),
        AskTopic(
            id = "army_building_basics",
            title = "ARMY BUILDING & UPGRADES",
            understoodAs = "How do I build an army and purchase upgrades?",
            aliases = listOf(
                "how do i build an army", "army building", "how do i buy upgrades", "buy upgrades", "how do specialist upgrades work",
                "specialist upgrades", "army roster upgrades"
            ),
            requiredGroups = listOf(setOf("army", "build", "upgrade", "specialist")),
            summary = "Army Building starts with a Race and Faction Card, then uses Vespene Gas to buy eligible Tactical Cards and their Army Slots, Minerals to muster Units, and Minerals to purchase listed Unit upgrades.",
            bullets = listOf(
                "Each Unit must fit the Army Slots you have available and you pay its Mineral cost for the chosen composition.",
                "A Unit may buy any number of different listed upgrades if you can pay for them.",
                "A SPECIALIST upgrade is carried by one nominated model in that Unit; different Specialist upgrades can be assigned to different models.",
                "Normal duplicate Units are legal when you have the resources and slots; UNIQUE restrictions still apply where printed."
            ),
            source = "Core Rules §9.1.1–9.1.7"
        ),
        AskTopic(
            id = "leading_model_movement",
            title = "LEADING MODEL & COHERENCY MOVEMENT",
            understoodAs = "Does Coherency let the rest of a Unit move farther than the Leading Model?",
            aliases = listOf(
                "does coherency make units move farther", "does coherency let units move farther", "coherency move farther",
                "only leading model moves", "do all models measure movement", "do all models move speed", "how does leading model movement work",
                "leading model coherency movement", "remaining models after movement", "models set in coherency after move"
            ),
            requiredGroups = listOf(setOf("coherency", "leading", "model", "move", "movement")),
            summary = "Only the nominated Leading Model measures and moves along a physical path. After it moves, the other models are set into legal Coherency around its new position; they do not each measure a separate movement distance.",
            bullets = listOf(
                "The Leading Model moves first and is limited by the movement being resolved, normally the Unit's SPEED for a standard Move.",
                "Each remaining model is then set, normally Wholly Within 3 inches of the Leading Model, with a legal Coherency Link.",
                "So a trailing model can change position substantially during the coherency placement, but the rule is placement in Coherency, not extra measured movement for that model.",
                "Coherency is checked at the end of the repositioning action. Casualties alone do not trigger a new Coherency check."
            ),
            source = "Core Rules §4.3–4.4 · Official FAQ, Measuring & Movement"
        ),
        AskTopic(
            id = "non_lethal_damage",
            title = "NON-LETHAL DAMAGE",
            understoodAs = "How does NON-LETHAL DAMAGE work?",
            aliases = listOf(
                "non lethal damage", "non-lethal damage", "how does non lethal damage work", "how does non-lethal damage work",
                "does non lethal damage kill", "can non lethal damage remove models", "stimpack damage", "stimpack non lethal damage"
            ,
                "can non-lethal damage kill a model", "can non lethal damage kill a model"),
            requiredGroups = listOf(setOf("non", "lethal", "damage", "stimpack")),
            summary = "NON-LETHAL DAMAGE (X) adds X Damage to the Unit's Total Damage, but it does not remove models when it is applied, even if that Total Damage exceeds a model's HP.",
            bullets = listOf(
                "Keep the non-lethal amount in the Unit's Total Damage Pool / Damage Marker.",
                "If the Unit later suffers standard Damage, combine it with the existing Total Damage and resolve casualties normally.",
                "The FAQ confirms that non-lethal Damage interacts with SHIELDED Status exactly like standard Damage and can cause SHIELDED to be lost."
            ),
            source = "Core Rules Part 11, NON-LETHAL DAMAGE · Official FAQ, Units & Characteristics"
        ),
        AskTopic(
            id = "anti_evade_scope",
            title = "ANTI-EVADE",
            understoodAs = "Does ANTI-EVADE apply to every shot?",
            aliases = listOf(
                "does anti evade apply to every shot", "anti evade every shot", "anti-evade every attack", "anti evade scope",
                "how does anti evade work", "how does anti-evade work", "anti evade weapon", "anti evade modifier"
            ),
            requiredGroups = listOf(setOf("anti", "evade")),
            summary = "ANTI-EVADE (X) is a weapon keyword. When resolving an attack with that weapon, the target suffers a -X Modifier to its Evade Roll for that attack.",
            bullets = listOf(
                "It applies to the attack made with the weapon that has ANTI-EVADE; it is not a global penalty to every attack the Unit makes.",
                "Because it is a Modifier, it cannot worsen a Target Number beyond 6+; a natural 6 still succeeds."
            ),
            source = "Core Rules Part 11, ANTI-EVADE · Official FAQ, Attack Sequence"
        ),
        AskTopic(
            id = "overwatch_reaction_fire",
            title = "OVERWATCH / REACTION FIRE",
            understoodAs = "Can I Overwatch or Reaction Fire a Charge?",
            aliases = listOf(
                "can i overwatch a charge", "overwatch charge", "reaction fire charge", "can i reaction fire a charge",
                "is there overwatch", "does overwatch exist", "shoot a charging unit reaction", "fire when charged"
            ),
            requiredGroups = listOf(setOf("overwatch", "reaction", "fire", "charge")),
            summary = "There is no universal Overwatch or Reaction Fire action in the core action list. You may interrupt a Charge only when a specific printed Reaction Ability has a trigger that is satisfied.",
            bullets = listOf(
                "Reaction Abilities resolve at their exact printed trigger; they are not a general permission to make a Ranged Attack.",
                "For example, Marauder Concussive Shells is a Reaction triggered when an Enemy declares a Charge against a Friendly Unit Within 8 inches, but it resolves its printed effect rather than a generic Overwatch shot.",
                "Each player is normally limited to one Reaction per Activation, and each named Reaction on a Unit is normally once per Round unless REPEATABLE."
            ),
            source = "Core Rules §10.4 · Quick Reference §12.3–12.4 · Official FAQ"
        ),
        AskTopic(
            id = "duplicate_units",
            title = "MULTIPLE COPIES OF A UNIT",
            understoodAs = "Can I take multiple Units of the same type?",
            aliases = listOf(
                "can i take multiple copies of the same unit", "multiple copies same unit", "duplicate units", "two of the same unit",
                "can i take two marine units", "can i take two zergling units", "repeat unit in army", "same unit twice"
            ),
            requiredGroups = listOf(setOf("multiple", "duplicate", "two", "same", "repeat"), setOf("unit", "marine", "zergling")),
            summary = "Yes, normal Units can be recruited more than once as separate Units, provided each one is eligible and you have the Minerals and Army Slots for it.",
            bullets = listOf(
                "The Learn to Play army lists explicitly contain two separate Marine Units and two separate Zergling Units.",
                "Mustering Units requires you to pay the Mineral Cost and occupy the required Army Slots for every Unit recruited.",
                "Explicit UNIQUE restrictions are exceptions where the relevant card/rule says only one copy is allowed."
            ),
            source = "Core Rules Part 1, Worked Example · §9.1.5–9.1.6"
        ),
        AskTopic(
            id = "engagement_scales",
            title = "ENGAGEMENT SCALES / GAME SIZES",
            understoodAs = "What game sizes are there?",
            aliases = listOf(
                "what game sizes are there", "game sizes", "game size", "engagement scales", "engagement scale",
                "skirmish standard grand offensive", "how many minerals game size", "table sizes", "battlefield sizes"
            ,
                "what is the mineral limit for skirmish", "what is the mineral limit for standard engagement", "skirmish mineral limit", "standard engagement mineral limit"),
            requiredGroups = listOf(setOf("game", "engagement", "table", "battlefield"), setOf("size", "scale", "skirmish", "standard", "grand")),
            summary = "The Core Rules define three Engagement Scales: Skirmish, Standard, and Grand Offensive.",
            bullets = listOf(
                "Skirmish: up to 1,000 Minerals, Vespene limit 10% of Minerals, 36 x 36 inch battlefield.",
                "Standard: up to 2,000 Minerals, Vespene limit 10% of Minerals, 36 x 54 inch battlefield.",
                "Grand Offensive: 2,001+ Minerals, Vespene limit 10% of Minerals, 36 x 72 inch battlefield."
            ),
            source = "Core Rules §9.1.1"
        ),
        AskTopic(
            id = "model_proxies",
            title = "MODEL REPRESENTATION / PROXIES",
            understoodAs = "Can I proxy a model?",
            aliases = listOf(
                "can i proxy a model", "can i proxy models", "are proxies allowed", "proxy model", "model proxy", "stand in miniature",
                "wrong weapon on model", "wysiwyg", "model does not match equipment", "non represented upgrade"
            ,
                "do i have to show my opponent my upgrades", "declare upgrades to opponent", "show opponent upgrades"),
            requiredGroups = listOf(setOf("proxy", "model", "miniature", "represent", "wysiwyg", "equipment")),
            summary = "The Core Rules do not give a blanket rule authorising arbitrary stand-in miniatures. They do explicitly allow for models whose equipment is not accurately represented, provided the differences are fully disclosed.",
            bullets = listOf(
                "Where possible, models should accurately depict their listed weapons and equipment.",
                "If equipment or upgrades are not represented, declare every difference when the Unit is deployed and remind the opponent before any action where it could matter.",
                "For a completely different miniature used as a proxy, the Core Rules do not specify permission; agree it with the opponent, and follow any tournament organiser's stricter requirements."
            ),
            source = "Core Rules §9.1.10–9.1.11"
        ),
        AskTopic(
            id = "omega_network_deployment",
            title = "OMEGA NETWORK DEPLOYMENT",
            understoodAs = "How does Omega Network deployment work?",
            aliases = listOf(
                "how does omega network deployment work", "omega network deployment", "deploy through omega worm", "omega worm deploy",
                "omega network entry edge", "omega worm entry edge", "how many supply through omega worm"
            ,
                "does omega network create a zone of influence", "omega network zone of influence"),
            requiredGroups = listOf(setOf("omega"), setOf("network", "worm", "deploy", "entry")),
            summary = "Omega Network can create an Omega Worm, and the Omega Worm's base then counts as a Friendly Entry Edge for its controller.",
            bullets = listOf(
                "Omega Network sets a Friendly Omega Worm on Ground Level more than 10 inches from Enemy models if no Friendly Omega Worm is already on the battlefield.",
                "The Round it is created, the Omega Worm cannot use its Special Abilities except Structure.",
                "The Omega Worm card allows Friendly Units with a combined Supply cost of 2 or less each Round to be Deployed via that Entry Edge.",
                "The FAQ confirms the Omega Worm is a Friendly Entry Edge, but it does not generate the 6-inch Zone of Influence created by primary Deployment Card Entry Edges."
            ),
            source = "Kerrigan's Swarm Faction Card, OMEGA NETWORK · Omega Worm Unit Card · Official FAQ"
        ),
        AskTopic(
            id = "shield_healing",
            title = "SHIELDS & HEALING",
            understoodAs = "Can Shields be healed?",
            aliases = listOf(
                "can shields be healed", "can shield be healed", "heal shields", "healing shields", "restore shields", "heal shield hp",
                "can healing restore shielded", "can heal restore shielded status"
            ),
            requiredGroups = listOf(setOf("shield", "shielded"), setOf("heal", "healing", "restore")),
            summary = "Yes: lost HP from the first model's combined HP plus Shield pool can be healed normally. But once SHIELDED Status itself has been lost, healing cannot restore that Status.",
            bullets = listOf(
                "A Unit's Shield value is added directly to the first model's HP while resolving its combined health pool.",
                "SHIELDED is lost when Total Damage exceeds the Shield value or when the first model is removed.",
                "Healing can remove lost Damage/restore HP in the combined pool, but cannot re-grant SHIELDED after the Status has been lost."
            ),
            source = "Official FAQ, Units & Characteristics · Core Rules §5.1"
        ),
        AskTopic(
            id = "disengage_rules",
            title = "DISENGAGING",
            understoodAs = "How does Disengage work?",
            aliases = listOf(
                "how do i disengage", "how does disengage work", "disengage rules", "disengage penalty", "what happens after disengage",
                "can i shoot after disengaging", "can i charge after disengaging", "leave combat movement"
            ),
            requiredGroups = listOf(setOf("disengage", "engaged", "combat")),
            summary = "An Engaged Unit may use Disengage in the Movement Phase to move out of combat using the standard Move rules.",
            bullets = listOf(
                "Every surviving model must finish strictly outside the Engagement Range of all Enemy Units; models that cannot clear it are removed under the Disengage rules.",
                "If the Leading Model cannot finish outside all Enemy Engagement Ranges, the Disengage fails, the Unit does not move, the Leading Model is removed, and the activation ends.",
                "After Disengaging, the Unit normally cannot make a Ranged Attack or Charge in the following Assault Phase unless its Current Supply is greater than the Combined Supply of all enemies it was Engaged with."
            ),
            source = "Core Rules §8.5.4 · Part 11 TACTICAL MASS"
        ),
        AskTopic(
            id = "real_time_duration",
            title = "HOW LONG DOES A GAME TAKE?",
            understoodAs = "How long does a game take in real-world time?",
            aliases = listOf("how long does a game take", "how long game take", "game duration", "play time", "match duration", "how long to play", "hours per game", "minutes per game"),
            requiredGroups = listOf(setOf("game", "match", "play"), setOf("long", "time", "duration", "hour", "minute", "take")),
            summary = "The Core Rules do not give a real-world play-time estimate in minutes or hours.",
            bullets = listOf(
                "In rules terms, Game Length means a number of Rounds set by the Mission Card, not a clock time.",
                "The final Round is usually Round 5, but some missions use a different Round limit.",
                "A game can also end earlier if a Special Winning Condition is fulfilled or a player has no models on the battlefield and no Units in Reserves."
            ),
            source = "Core Rules §8.9.3 · Mission Cards"
        ),
        AskTopic(
            id = "round_limit",
            title = "GAME LENGTH / ROUND LIMIT",
            understoodAs = "How many Rounds does a game last?",
            aliases = listOf("how many rounds", "game length rounds", "round limit", "how many rounds game", "final round", "game round count"),
            requiredGroups = listOf(setOf("round", "rounds"), setOf("game", "match", "length", "limit", "many", "final")),
            summary = "The Mission Card sets the game's Round limit. The Core Rules describe the final Round as usually Round 5.",
            bullets = listOf(
                "Check the Mission Card's GAME LENGTH value for the exact Round limit.",
                "The game ends after the final Phase of the final Round unless it ended earlier through another end condition."
            ),
            source = "Core Rules §8.9.3 · Mission Cards",
            visualAsset = "images/rules/mission_card_reference.webp",
            visualCaption = "GAME LENGTH is printed on the Mission Card as the number of Rounds to play (callout 5).",
            visualAspectRatio = 0.661f
        ),
        AskTopic(
            id = "same_faction",
            title = "SAME RACE / FACTION",
            understoodAs = "Can players use the same Race or Faction?",
            aliases = listOf(
                "players play same faction", "can players play same faction", "players same faction", "same faction allowed", "same race allowed",
                "both players same race", "both players same faction", "mirror match", "terran vs terran", "zerg vs zerg", "protoss vs protoss",
                "can both players play zerg", "can both players play terran", "can both players play protoss"
            ),
            requiredGroups = listOf(setOf("same", "both", "mirror", "vs"), setOf("faction", "race", "terran", "zerg", "protoss")),
            summary = "Yes. The Core Rules do not require opposing players to choose different Races or Factions; each player makes their own Race and Faction selection.",
            bullets = listOf(
                "Each player chooses one Race — Terran, Zerg, or Protoss — then selects one Faction Card for that army.",
                "Team Games explicitly state that teammates may choose the same or different Races.",
                "So mirror matches are legal unless a specific event or tournament pack adds a restriction."
            ),
            source = "Core Rules §9.1.2 · §9.1.8"
        ),
        AskTopic(
            id = "select_faction",
            title = "SELECTING RACE & FACTION",
            understoodAs = "How do I choose a Race and Faction?",
            aliases = listOf("choose faction", "choose race", "select faction", "select race", "race and faction", "how faction works army building"),
            requiredGroups = listOf(setOf("choose", "select"), setOf("race", "faction", "terran", "zerg", "protoss")),
            summary = "Choose one Race, then select a single Faction Card. That Faction Card determines which Unit and Tactical Card Faction Tags are legal in the army.",
            bullets = listOf(
                "Every tag on a Unit or Tactical Card must also appear on the chosen Faction Card.",
                "A card with even one Faction Tag that is not present on the Faction Card cannot be included."
            ),
            source = "Core Rules §9.1.2"
        ),
        AskTopic(
            id = "team_games",
            title = "TEAM GAMES",
            understoodAs = "How do team games build armies?",
            aliases = listOf("team games", "2v2", "3v3", "teams army budget", "team mineral budget", "multiplayer teams"),
            requiredGroups = listOf(setOf("team", "2v2", "3v3", "multiplayer")),
            summary = "In multiplayer team games, agree on a Total Mineral Value per team. The team divides that budget among its players, and each player builds their own army independently.",
            bullets = listOf(
                "The team's combined army must remain within the agreed Total Mineral Value.",
                "Each player selects their own Faction Card and Tactical Cards.",
                "Teammates may choose the same or different Races."
            ),
            source = "Core Rules §9.1.8"
        ),
        AskTopic(
            id = "round_sequence",
            title = "ROUND SEQUENCE",
            understoodAs = "What is the order of a Round?",
            aliases = listOf("round sequence", "phase order", "order of phases", "what phases", "round phases", "turn order phases"),
            requiredGroups = listOf(setOf("round", "phase", "phases"), setOf("order", "sequence", "what", "turn")),
            summary = "A Round has four Phases: Movement, Assault, Combat, then Scoring & Cleanup.",
            bullets = listOf(
                "Phase 1 — MOVEMENT: Deploy / Move / Disengage / Hold.",
                "Phase 2 — ASSAULT: Ranged Attack / Charge / Run / Hold.",
                "Phase 3 — COMBAT: Close Combat Attacks.",
                "Phase 4 — SCORING & CLEANUP: determine control, score VPs, end check, end-of-round effects, cleanup, then initiative."
            ),
            source = "Core Rules §8.1 · Quick Reference §12.2"
        ),
        AskTopic(
            id = "movement_phase",
            title = "PHASE 1 · MOVEMENT",
            understoodAs = "How does the Movement Phase work?",
            aliases = listOf("movement phase", "how movement phase works", "movement actions", "phase 1 movement", "what do i do in movement",
                "how does hold work", "hold action", "how does a move action work", "move action"),
            requiredGroups = listOf(setOf("movement", "move")),
            summary = "At the start of the Movement Phase, resolve Start of the Round effects and verify Available Supply. Players then alternate activations until both have Passed.",
            bullets = listOf(
                "On an activation, an on-table Unit without a Movement marker may Move, Hold or Disengage; a Unit in Reserves may Deploy.",
                "After its action, set a Movement-side Activation Marker beside the Unit.",
                "The first player to Pass takes the First Player Marker for the Assault Phase; Units that did not activate receive Movement markers when that player Passes."
            ),
            source = "Core Rules §8.4–8.5 · Quick Reference §12.3"
        ),
        AskTopic(
            id = "assault_phase",
            title = "PHASE 2 · ASSAULT",
            understoodAs = "How does the Assault Phase work?",
            aliases = listOf("assault phase", "how assault phase works", "assault actions", "phase 2 assault", "what do i do in assault"),
            requiredGroups = listOf(setOf("assault")),
            summary = "During the Assault Phase, players alternate activating Units that have Movement-side Activation Markers. Units still in Reserves cannot act.",
            bullets = listOf(
                "Each activated Unit chooses one action: Run, Hold, Charge, or Ranged Attack.",
                "After the action, turn/set its Activation Marker to the Assault side.",
                "The first player to Pass takes the First Player Marker for the Combat Phase; Units that did not activate receive Assault-side markers."
            ),
            source = "Core Rules §8.6–8.7 · Quick Reference §12.4"
        ),
        AskTopic(
            id = "combat_phase",
            title = "PHASE 3 · COMBAT",
            understoodAs = "How does the Combat Phase work?",
            aliases = listOf("combat phase", "how combat phase works", "phase 3 combat", "close combat phase", "what do i do in combat"),
            requiredGroups = listOf(setOf("combat", "melee")),
            summary = "Only Engaged Ground Units act in the Combat Phase, and every eligible Engaged Unit must fight. Flying Units never participate in this Phase.",
            bullets = listOf(
                "The holder of the First Player Marker chooses who activates first; players then alternate one Engaged Unit at a time.",
                "An activated Unit resolves the Close Combat Attack procedure against Enemy Units it is Engaged with.",
                "A player Passes only when they have no remaining Engaged Units to activate; the Phase ends when both players Pass."
            ),
            source = "Core Rules §8.8–8.8.1 · Quick Reference §12.5"
        ),
        AskTopic(
            id = "scoring_phase",
            title = "PHASE 4 · SCORING & CLEANUP",
            understoodAs = "How does the Scoring & Cleanup Phase work?",
            aliases = listOf("scoring phase", "cleanup phase", "scoring cleanup", "phase 4 scoring", "how scoring works", "what do i do in scoring",
                "what happens in cleanup", "cleanup phase", "cleanup and refresh"),
            requiredGroups = listOf(setOf("scoring", "score", "cleanup")),
            summary = "Resolve six steps in order: Mission Marker Control, Victory Points, End of Game Check, End of Round effects, Cleanup & Refresh, then Determine Initiative.",
            bullets = listOf(
                "Mission Marker control is determined before Victory Points are scored.",
                "Resolve the End of Game Check before End of Round effects and cleanup.",
                "After Cleanup & Refresh, determine initiative for the next Round if the game continues."
            ),
            source = "Core Rules §8.9 · Quick Reference §12.6"
        ),
        AskTopic(
            id = "game_end",
            title = "WHEN DOES THE GAME END?",
            understoodAs = "What ends the game?",
            aliases = listOf("when game ends", "how game ends", "end game conditions", "game over", "win condition", "winning condition", "when does match end"),
            requiredGroups = listOf(setOf("end", "over", "win", "winning"), setOf("game", "match", "condition")),
            summary = "The game ends immediately when an end condition is met.",
            bullets = listOf(
                "A Special Winning Condition is fulfilled.",
                "A player has no models on the battlefield and no Units in Reserves; the surviving player gains +10 VP.",
                "The final Phase of the final Round is completed, after which the Final Score is resolved."
            ),
            source = "Core Rules §8.9.3 · §8.10"
        ),
        AskTopic(
            id = "first_player",
            title = "FIRST PLAYER",
            understoodAs = "How is First Player determined?",
            aliases = listOf("first player", "who goes first", "initiative", "determine first player", "first player marker"),
            requiredGroups = listOf(setOf("first", "initiative"), setOf("player", "goes", "marker")),
            summary = "First Player changes during the game. At the start of the game a Roll-Off determines who assigns the First Player Marker for Round 1; later rules can move it between players.",
            bullets = listOf(
                "Passing can transfer the First Player Marker between Phases.",
                "At the end of a Round, the player with fewer Victory Points takes First Player for the next Round; ties use a Roll-Off."
            ),
            source = "Core Rules §8.2 · §8.9.6 · Quick Reference §12.1"
        ),
        AskTopic(
            id = "passing",
            title = "PASSING",
            understoodAs = "How does passing affect First Player?",
            aliases = listOf("passing", "first to pass", "who passed first", "pass first player", "passing initiative",
                "how does passing work", "how do i pass"),
            requiredGroups = listOf(setOf("pass", "passing")),
            summary = "During the Movement and Assault Phases, the first player to Pass takes the First Player Marker for the following Phase.",
            bullets = listOf(
                "Passing is part of the alternating activation system.",
                "The marker is therefore a fluid initiative resource rather than a fixed turn-order advantage."
            ),
            source = "Core Rules §8.2.1 · §8.4.2 · §8.6.2"
        ),
        AskTopic(
            id = "supply",
            title = "SUPPLY",
            understoodAs = "How does Supply work?",
            aliases = listOf("how supply works", "what is supply", "how does supply work", "supply system"),
            requiredGroups = listOf(setOf("supply")),
            summary = "Supply limits how much of your army can be on the battlefield at once and is also used for objective control and some scoring rules.",
            bullets = listOf(
                "Available Supply = current Supply Pool minus the Total Current Supply of Friendly Units already on the battlefield.",
                "A Unit can deploy only if its Current Supply fits within Available Supply.",
                "In the final Round, the Supply Pool is unlimited."
            ),
            source = "Core Rules Part 6 · §8.3"
        ),
        AskTopic(
            id = "reserves",
            title = "RESERVES",
            understoodAs = "What can Units do while in Reserves?",
            aliases = listOf("reserves", "unit in reserves", "abilities in reserves", "use ability before deploy", "before deploying ability",
                "can i use active abilities in reserves", "can i use abilities in reserves", "active abilities while in reserves", "passive abilities in reserves", "reaction abilities in reserves",
                "how do reserves work", "what happens in reserves"),
            requiredGroups = listOf(setOf("reserve", "reserves", "deploy", "deployment")),
            summary = "Units in Reserves normally cannot use abilities or benefit from them until they are on the battlefield.",
            bullets = listOf(
                "Only rules that explicitly function in Reserves, or abilities that actually deploy the Unit, can resolve before deployment.",
                "This also means a Unit cannot normally measure ranges or qualify for battlefield conditions such as being ON CREEP before it is placed."
            ),
            source = "Core Rules §8.3 · FAQ"
        ),
        AskTopic(
            id = "special_entry_edges",
            title = "PYLON / OMEGA WORM ENTRY EDGES",
            understoodAs = "Do a Pylon or Omega Worm count as an Entry Edge?",
            aliases = listOf("pylon entry edge", "omega worm entry edge", "omega network entry edge", "does pylon count as entry edge", "does omega worm count as entry edge", "pylon make entry edge"),
            requiredGroups = listOf(setOf("pylon", "omega"), setOf("entry"), setOf("edge")),
            summary = "Yes. The FAQ says both an Omega Worm and a Pylon's base are treated as Friendly Entry Edges owned by the player who placed them.",
            bullets = listOf(
                "They can therefore interact with rules that require a Friendly Entry Edge, such as the FAQ's Burrow Ambush example.",
                "The FAQ distinguishes these from forward-deployment cards and transports, which grant deployment exceptions but do not create Entry Edges.",
                "Omega Network does not create the normal 6-inch Zone of Influence; that is generated only by the primary Entry Edges from the Deployment Card."
            ),
            source = "Official FAQ · Deployment, Entry Edges & Zone of Influence"
        ),
        AskTopic(
            id = "roster_visibility",
            title = "ARMY ROSTER VISIBILITY",
            understoodAs = "Are army lists open or hidden?",
            aliases = listOf("open lists", "closed lists", "show army list", "roster visibility", "hidden army list", "see opponent upgrades"),
            requiredGroups = listOf(setOf("list", "roster"), setOf("open", "closed", "hidden", "show", "see", "visibility", "upgrade")),
            summary = "The default is Open Lists: both players share their full army rosters, including Unit selections, upgrades and weapon swaps, before the game starts.",
            bullets = listOf(
                "Players may mutually agree to use Closed Lists instead; otherwise Open Lists are the default.",
                "Even with Closed Lists, Tactical Cards and the Faction Card remain face-up, and upgrades/weapon swaps must be disclosed when a Unit is set on the battlefield.",
                "Tournament packs may override the default."
            ),
            source = "Core Rules §9.1.10"
        ),
        AskTopic(
            id = "mission_control",
            title = "MISSION MARKER CONTROL",
            understoodAs = "How do I control a Mission Marker?",
            aliases = listOf("control objective", "control mission marker", "contest mission marker", "who controls marker", "objective control"),
            requiredGroups = listOf(setOf("control", "contest", "objective", "marker"), setOf("marker", "objective", "mission")),
            summary = "Mission Marker control is normally determined by comparing the Current Supply of eligible Units contesting that marker.",
            bullets = listOf(
                "A Unit normally contests if at least one eligible model is Within 3\" of the Mission Marker.",
                "Phase 4 checks the eligible Friendly and Enemy Supply around each Mission Marker.",
                "The player with the higher total controls it; ties are Contested.",
                "Flying Units cannot normally Contest or Control Mission Markers."
            ),
            source = "Core Rules §6.2 · §8.9.1"
        ),
        AskTopic(
            id = "line_of_sight",
            title = "LINE OF SIGHT",
            understoodAs = "How does Line of Sight work?",
            aliases = listOf("line of sight", "los", "can i see target", "visibility", "visible target"),
            requiredGroups = listOf(setOf("los", "sight", "visible", "visibility", "see")),
            summary = "Line of Sight is checked from a top-down view by tracing a straight line from any part of the acting model's base to any part of the target model's base.",
            bullets = listOf(
                "If the trace does not pass through Blocking Terrain, the target is Visible.",
                "If it does, resolve the Cover rules to determine whether the terrain actually blocks Line of Sight.",
                "Terrain footprints and agreed openings matter; windows and gaps are not automatically open for Line of Sight."
            ),
            source = "Core Rules §7.1"
        ),
        AskTopic(
            id = "move_distance",
            title = "MOVE DISTANCE",
            understoodAs = "How far can a Unit move?",
            aliases = listOf(
                "how far can a unit move", "how far can unit move", "how far does a unit move", "how far does unit move",
                "unit move distance", "movement distance", "move distance", "how many inches can a unit move",
                "how many inches can unit move", "unit movement range", "how far move", "speed for movement", "speed characteristic movement"
            ),
            requiredGroups = listOf(setOf("move", "movement", "speed"), setOf("far", "distance", "inch", "range")),
            summary = "A standard Move does not have one universal distance. The Leading Model may move up to that Unit's current SPEED characteristic in inches, shown on its Unit Card.",
            bullets = listOf(
                "Read the SPEED box on the Unit Card. For a split value such as 5/8, use the first value normally and the second only when the Unit is reduced to one remaining model, or if it started with one model.",
                "Only the Leading Model measures and moves along that path. Afterward, set the remaining models in legal Unit Coherency around it.",
                "Rules, BUFFs, DEBUFFs and printed abilities can modify SPEED, so use the Unit's current modified SPEED when the movement is resolved."
            ),
            source = "Core Rules §5.1 · §4.3 · Quick Reference §12.3",
            visualAsset = "images/rules/unit_card_speed_reference.webp",
            visualCaption = "LOOK AT SPEED: the highlighted SPEED characteristic is the maximum distance for a standard Move. This official Adept example shows a split SPEED value of 5/8."
        ),
        AskTopic(
            id = "move_zero",
            title = "MOVE / RUN 0 INCHES",
            understoodAs = "Can a Unit perform Move or Run without actually moving?",
            aliases = listOf("move 0", "move zero", "run 0", "run zero", "can unit move 0", "can unit run 0", "move without moving"),
            requiredGroups = listOf(setOf("move", "run"), setOf("0", "zero", "without")),
            summary = "No. A Unit performing a Move or Run action must actually change its position on the battlefield.",
            bullets = listOf("If the Unit does not reposition, it is considered to have performed a Hold action instead."),
            source = "Official FAQ · Measuring & Movement"
        ),
        AskTopic(
            id = "tactical_resources",
            title = "CP / BM / PE COSTS",
            understoodAs = "How do I pay for Special Abilities?",
            aliases = listOf("pay ability cost", "ability resource cost", "pay cp cost", "pay bm cost", "pay pe cost", "pay special ability",
                "how do i pay for tactical card abilities", "pay tactical card ability", "pay faction card ability", "tactical card resources", "faction card resources"),
            requiredGroups = listOf(setOf("cp", "bm", "pe", "resource", "cost", "pay"), setOf("ability", "abilities", "cp", "bm", "pe")),
            summary = "Pay a Special Ability cost by exhausting one or more Ready cards that generate the required resource type.",
            bullets = listOf(
                "Any excess resource generated by the exhausted card is lost; it cannot be banked for another ability.",
                "An Active ability on a Tactical Card still requires an active Unit on the battlefield unless a rule says otherwise."
            ),
            source = "Core Rules §10.5.1 · FAQ"
        ),
        AskTopic(
            id = "engaged_ranged_attack",
            title = "SHOOTING WHILE ENGAGED",
            understoodAs = "Can an Engaged Unit make a Ranged Attack?",
            aliases = listOf("shoot while engaged", "can engaged unit shoot", "ranged attack while engaged", "shoot in combat", "shooting in melee", "engaged ranged attack"),
            requiredGroups = listOf(setOf("engaged", "engage", "combat", "melee"), setOf("shoot", "shooting", "ranged", "attack")),
            summary = "Yes, but an Engaged attacking Unit may only select Unit(s) it is currently Engaged with as Ranged Attack targets.",
            bullets = listOf(
                "An Unengaged attacker cannot select an Engaged Unit as a Ranged Attack target, except for Spillover from Template Weapons.",
                "An Engaged attacker can split its legal weapon Batches only among Units it is currently Engaged with.",
                "A weapon with BULKY cannot be used to make a Ranged Attack while the Unit is currently Engaged."
            ),
            source = "Core Rules §8.7.3 · Part 11 BULKY"
        ),
        AskTopic(
            id = "split_fire",
            title = "SPLITTING RANGED FIRE",
            understoodAs = "Can a Unit split its Ranged Attacks between different targets?",
            aliases = listOf("split fire", "split ranged attacks", "shoot different targets", "multiple targets ranged", "can i split fire", "weapons different targets"),
            requiredGroups = listOf(setOf("split", "different", "multiple"), setOf("fire", "shoot", "weapon", "target", "ranged")),
            summary = "Yes. Different Weapon Profiles may be assigned to different legal targets and are resolved as separate Batches.",
            bullets = listOf(
                "All models using the same Weapon Profile must fire that profile at the same target; a single profile cannot be split.",
                "Each SIDEARM profile is also its own Batch and may target differently from the Unit's other weapons.",
                "Declare and fully resolve one Batch before declaring the next."
            ),
            source = "Core Rules §8.7.3"
        ),
        AskTopic(
            id = "move_through_models",
            title = "MOVING THROUGH MODELS",
            understoodAs = "Which models can I move through?",
            aliases = listOf("move through models", "move through friendly units", "move through friendly models", "move through enemy models", "can i pass through models", "models block movement"),
            requiredGroups = listOf(setOf("move", "pass", "through"), setOf("model", "models", "unit", "friendly", "enemy")),
            summary = "For standard movement, the Leading Model may pass through Friendly models in its own Unit, but not Enemy models or Friendly models from other Units.",
            bullets = listOf(
                "Ground models may pass through a Flying model's base as if it were not there, and vice versa.",
                "After the Leading Model moves, the remaining models are set rather than moved and may ignore intervening obstacles, but must finish in legal positions.",
                "Special rules such as BURROWED or Flying can create additional exceptions."
            ),
            source = "Core Rules §8.5.3 · Part 4.4"
        ),
        AskTopic(
            id = "reaction_limit",
            title = "REACTION LIMITS",
            understoodAs = "How many Reactions can I use and when?",
            aliases = listOf("how many reactions", "reaction limit", "reactions per activation", "reaction outside activation", "one reaction", "when can i react",
                "how many reactions can i use in one activation", "number of reactions per activation"),
            requiredGroups = listOf(setOf("reaction", "react")),
            summary = "A Reaction is declared at its exact trigger. Each player may resolve only one Reaction per Activation, and a specific Unit may use a named Reaction only once per Round unless it is REPEATABLE.",
            bullets = listOf(
                "If the trigger window is missed, the Reaction cannot be used retroactively.",
                "If both players react to the same trigger, the Active Player resolves their Reaction first.",
                "Outside an Activation, the FAQ limits a player to one Reaction per trigger."
            ),
            source = "Core Rules §10.4 · Official FAQ"
        ),
        AskTopic(
            id = "active_ability_timing",
            title = "ACTIVE ABILITY TIMING",
            understoodAs = "When can an Active Ability be used?",
            aliases = listOf("when active ability", "active ability timing", "use active ability during action", "active before after action", "active ability in reserves",
                "can i use active abilities in reserves", "can active abilities be used in reserves",
                "can i use multiple active abilities in one activation", "multiple active abilities one activation"),
            requiredGroups = listOf(setOf("active", "ability")),
            summary = "A Unit may use an Active Ability only while it is currently Activated, immediately before declaring an action or immediately after fully resolving one.",
            bullets = listOf(
                "An Active Ability cannot be used during an action.",
                "It cannot normally be used while the Unit is in Reserves unless the ability explicitly says otherwise.",
                "A specific Unit may use each named Active Ability once per Round unless it has REPEATABLE."
            ),
            source = "Core Rules §10.2"
        ),
        AskTopic(
            id = "modifier_limits",
            title = "MODIFIERS & TARGET NUMBER LIMITS",
            understoodAs = "How do Modifiers stack and how far can a Target Number move?",
            aliases = listOf("modifier stacking", "modifiers stack", "target number limit", "minimum target number", "maximum target number", "2+ 6+ modifiers",
                "how do modifiers work", "modifier rules"),
            requiredGroups = listOf(setOf("modifier", "modifiers", "target"), setOf("stack", "limit", "minimum", "maximum", "2", "6", "number")),
            summary = "Modifiers from different named sources are cumulative unless stated otherwise, but a Target Number can never be modified below 2+ or above 6+.",
            bullets = listOf(
                "A +X Modifier makes a Target Number roll easier by reducing the Target Number by X.",
                "A -X Modifier makes it harder by increasing the Target Number by X.",
                "A Modifier is different from a Fixed Addition, which generates a value rather than changing a Target Number."
            ),
            source = "Core Rules §3.4 · Part 11 MODIFIER · Official FAQ"
        ),
        AskTopic(
            id = "sticky_control",
            title = "STICKY OBJECTIVE CONTROL",
            understoodAs = "Do I keep control of a Mission Marker after moving away?",
            aliases = listOf("sticky control", "keep objective after moving", "keep mission marker control", "objective stays controlled", "leave objective still control", "marker becomes neutral"),
            requiredGroups = listOf(setOf("control", "controlled", "objective", "marker"), setOf("sticky", "keep", "stay", "leave", "neutral", "move")),
            summary = "Yes. Mission Marker control is Sticky: once you control a Marker, it stays yours even if your Units move away, until the opponent actively reclaims it.",
            bullets = listOf(
                "A tied contest does not transfer control; the current controller keeps it.",
                "If a Marker has never been controlled, a tie leaves it neutral.",
                "Once a player has taken control, that Marker cannot become neutral again under the normal control rules."
            ),
            source = "Core Rules §8.9.1"
        ),
        AskTopic(
            id = "flying_objectives",
            title = "FLYING UNITS & OBJECTIVES",
            understoodAs = "Can Flying Units Contest or Control Mission Markers?",
            aliases = listOf("flying contest objective", "flying control objective", "flying mission marker", "can flyers score", "flying contest marker"),
            requiredGroups = listOf(setOf("flying", "flyer", "flyers"), setOf("contest", "control", "objective", "marker", "score")),
            summary = "No. Flying Units cannot Contest or Control Mission Markers under the normal Mission Marker control rules.",
            bullets = listOf("A mission-specific rule may use different eligibility; the FAQ, for example, distinguishes claiming a marker in Artefact Hunt from normal Control/Contest."),
            source = "Core Rules §8.9.1 · Part 11 MISSION MARKERS · Official FAQ"
        ),
        AskTopic(
            id = "coherency_check",
            title = "WHEN COHERENCY IS CHECKED",
            understoodAs = "When does a Unit become Out of Coherency?",
            aliases = listOf("when check coherency", "out of coherency casualties", "casualties break coherency", "coherency check", "unit out of coherency",
                "does coherency make units move farther", "coherency after movement", "leading model coherency",
                "what happens if i am out of coherency", "out of coherency consequences"),
            requiredGroups = listOf(setOf("coherency", "coherent")),
            summary = "Coherency is checked at the end of an action or ability that repositions models. Casualties by themselves do not trigger a new Coherency check.",
            bullets = listOf(
                "A Unit that passed its last repositioning check remains In Coherency until its next repositioning action.",
                "Out of Coherency Units cannot Control or Contest Mission Markers."
            ),
            source = "Core Rules §4.4 · Official FAQ"
        ),
        AskTopic(
            id = "destroyed_return",
            title = "DESTROYED UNITS RETURNING",
            understoodAs = "Can a Destroyed Unit return to the game?",
            aliases = listOf("destroyed unit come back", "destroyed unit return", "revive destroyed unit", "can dead unit return", "bring back destroyed unit"),
            requiredGroups = listOf(setOf("destroyed", "dead"), setOf("return", "back", "revive")),
            summary = "No. Once the last model is removed and the Unit is Destroyed, it cannot return to play unless a mission or another rule explicitly says otherwise.",
            bullets = emptyList(),
            source = "Official FAQ · Units & Characteristics"
        ),
        AskTopic(
            id = "gain_hidden",
            title = "GAINING HIDDEN",
            understoodAs = "How does a Unit gain HIDDEN?",
            aliases = listOf("how get hidden", "how gain hidden", "get hidden status", "become hidden", "what gives hidden"),
            requiredGroups = listOf(setOf("hidden", "hide"), setOf("get", "gain", "become", "give", "status")),
            summary = "A Unit gains HIDDEN only when a rule or ability grants that Status. BURROWED explicitly grants HIDDEN, and BURROWED Units gain HIDDEN again at the Start of each Round.",
            bullets = listOf(
                "Other Unit abilities, upgrades, Tactical Cards or Faction effects may also grant HIDDEN when their printed rule says so.",
                "HIDDEN itself then restricts visibility/targeting and grants the defensive effects described by the keyword."
            ),
            source = "Core Rules Part 11 · BURROWED · HIDDEN"
        ),
        AskTopic(
            id = "unit_card_anatomy",
            title = "HOW TO READ A UNIT CARD",
            understoodAs = "What do the numbers and boxes on a Unit Card mean?",
            aliases = listOf("unit card numbers", "read unit card", "unit card anatomy", "what are the numbers on a unit card", "what do unit card stats mean", "unit stats explained", "what is on a unit card"),
            requiredGroups = listOf(setOf("unit", "card"), setOf("number", "stat", "box", "read", "anatomy")),
            summary = "A Unit Card is the complete battlefield profile for that Unit. The numbered areas identify its movement, defences, durability, Size, Supply, phase abilities, weapons, Combat Tags and faction.",
            bullets = listOf(
                "Front: 1 Name · 2 SPEED · 3 ARMOUR · 4 EVADE · 5 HP · 6 SHIELD (if present) · 7 SIZE · 8 SUPPLY PROFILE · 9 PHASE BOXES · 10 WEAPON PROFILES · 11 COMBAT TAGS · 12 FACTION TAG.",
                "Reverse: 13 UPGRADES · 14 REPLACEMENTS · 15 COMBAT RANGE · 16 ARMY SLOT · 17 role/flavour information · 18 BASE DIAMETER.",
                "If you ask the Field Manual about any of those labels or abbreviations, it should explain that field and point back to the relevant card area."
            ),
            source = "Core Rules §5.1–5.2",
            visualAsset = "images/rules/unit_card_front_reference.webp",
            visualCaption = "UNIT CARD FRONT: the official numbered reference. Ask about SPEED, ARMOUR, EVADE, HP, SIZE, SUPPLY, RNG, RoA, HIT, DMG or the other labels individually.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "speed_characteristic",
            title = "SPEED CHARACTERISTIC",
            understoodAs = "What is SPEED on a Unit Card?",
            aliases = listOf("speed", "what is speed", "speed characteristic", "unit speed", "speed stat", "what does speed mean", "what is the speed number", "speed on unit card"),
            requiredGroups = listOf(setOf("speed")),
            summary = "SPEED is the maximum distance in inches the Leading Model may travel in a standard Move.",
            bullets = listOf(
                "A split value such as 5/8 uses the first value normally and the second only when the Unit is reduced to one model, or if it started with one model.",
                "A SPEED value of - means the Unit cannot move or be repositioned by any means, including PLACE and involuntary movement effects.",
                "Move, Run and Disengage use standard movement and therefore read this characteristic; Charge rolls add 1D6 to SPEED."
            ),
            source = "Core Rules §5.1 · §8.5.3 · §8.7.1 · §8.7.7",
            visualAsset = "images/rules/unit_card_speed_reference.webp",
            visualCaption = "SPEED is highlighted. Read this number for standard Move/Run distance; split values change when the Unit is down to one model.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "armour_characteristic",
            title = "ARMOUR CHARACTERISTIC",
            understoodAs = "What is ARMOUR on a Unit Card?",
            aliases = listOf("armour", "armor", "what is armour", "what is armor", "armour characteristic", "armor characteristic", "armour stat", "armor stat", "what does armour mean", "what does armor mean", "armour number"),
            requiredGroups = listOf(setOf("armour", "armor")),
            summary = "ARMOUR is the D6 Target Number the defender must meet or exceed to cancel a hit during an Armour Roll.",
            bullets = listOf(
                "After hits and Surge are resolved, the defender rolls the Armour Pool.",
                "Each die equal to or higher than the Unit’s ARMOUR is cancelled; lower results move to the Damage Pool."
            ),
            source = "Core Rules §5.1 · §8.7.4",
            visualAsset = "images/rules/unit_card_armour_reference.webp",
            visualCaption = "ARMOUR is highlighted on the Unit Card. A value such as 5+ means roll 5 or higher on a D6 to cancel a hit.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "evade_characteristic",
            title = "EVADE CHARACTERISTIC",
            understoodAs = "What is EVADE on a Unit Card?",
            aliases = listOf("evade characteristic", "evade stat", "what is evade characteristic", "what does evade number mean", "evade number", "evade on unit card"),
            requiredGroups = listOf(setOf("evade")),
            summary = "EVADE is a secondary defensive D6 Target Number. You use it only when a rule or condition specifically grants that Unit an Evade Roll.",
            bullets = listOf(
                "Having an EVADE value does not by itself grant an Evade Roll.",
                "A value of - means the Unit cannot make Evade Rolls, even if another rule would otherwise grant one.",
                "When eligible, each Evade success removes one die from the Damage Pool."
            ),
            source = "Core Rules §5.1 · §8.7.4",
            visualAsset = "images/rules/unit_card_evade_reference.webp",
            visualCaption = "EVADE is highlighted. The number tells you the Target Number only after a rule has made the Unit eligible to Evade.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "hit_points_characteristic",
            title = "HIT POINTS (HP)",
            understoodAs = "What is HP on a Unit Card?",
            aliases = listOf("hp", "hit points", "hit point", "what is hp", "what are hit points", "what does hp mean", "hp stat", "hp number", "health on unit card"),
            requiredGroups = listOf(setOf("hp", "hit", "health")),
            summary = "HP is the amount of Damage a single model must sustain before that model is removed as a casualty.",
            bullets = listOf(
                "Damage is tracked against models in the Unit during casualty removal.",
                "When Total Damage equals or exceeds a model’s HP, remove that model and reduce Total Damage by that HP amount."
            ),
            source = "Core Rules §5.1 · §8.7.5",
            visualAsset = "images/rules/unit_card_hp_reference.webp",
            visualCaption = "HIT POINTS is highlighted. This number is per model, not a shared Unit-wide health pool.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "shield_characteristic",
            title = "SHIELD",
            understoodAs = "What is the SHIELD number on a Unit Card?",
            aliases = listOf("shield", "shields", "what is shield", "shield stat", "shield number", "what does shield mean", "shield on unit card", "shielded status"),
            requiredGroups = listOf(setOf("shield")),
            summary = "SHIELD is an optional value added to the HP of the first model in the Unit. While it applies, the Unit has the SHIELDED Status.",
            bullets = listOf(
                "SHIELDED is lost when Total Damage assigned to the Unit exceeds the Shield value or when the first model is removed.",
                "Losing SHIELDED does not remove remaining HP; it ends effects that require SHIELDED.",
                "SHIELDED cannot be restored by HEAL."
            ),
            source = "Core Rules §5.1 · FAQ",
            visualAsset = "images/rules/unit_card_shield_reference.webp",
            visualCaption = "SHIELD is highlighted. If a card has no Shield box/value, the Unit simply does not use this optional characteristic.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "size_characteristic",
            title = "SIZE",
            understoodAs = "What is SIZE on a Unit Card?",
            aliases = listOf("size characteristic", "unit size", "what is size", "what does size mean", "size stat", "size number", "size on unit card"),
            requiredGroups = listOf(setOf("size")),
            summary = "SIZE is the model’s vertical height category. It is used for Line of Sight, terrain interaction, Gap Clearance and other rules that compare model or terrain height.",
            bullets = listOf(
                "Size 2 or lower can pass through gaps at least 1 inch wide; Size 3 or larger requires at least a 3 inch gap.",
                "Elevation and terrain can change Effective Size for some Line of Sight checks; the printed SIZE is the model’s base characteristic."
            ),
            source = "Core Rules §5.1 · §4.6 · Part 7",
            visualAsset = "images/rules/unit_card_size_reference.webp",
            visualCaption = "SIZE is highlighted. This is a category number for model/terrain interaction, not the physical base diameter.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "supply_profile_characteristic",
            title = "SUPPLY PROFILE",
            understoodAs = "What do the model-count / Supply boxes on a Unit Card mean?",
            aliases = listOf("supply profile", "models supply", "model supply", "what is supply profile", "what do supply boxes mean", "1-2 supply", "3-4 supply", "model count supply", "supply brackets", "supply on unit card"),
            requiredGroups = listOf(setOf("supply"), setOf("profile", "model", "bracket", "box")),
            summary = "The SUPPLY PROFILE maps the Unit’s current number of models to its Current Supply Value. When casualties move the Unit into a lower bracket, update Supply immediately.",
            bullets = listOf(
                "Read the model-count bracket that contains the Unit’s current number of models, then use the Supply value paired with that bracket.",
                "Current Supply is used for deployment limits, Mission Marker control, Disengage Tactical Mass and some scoring rules.",
                "A Unit with Supply 0 can still control a Marker if no Enemy Unit contests it, unless another rule says otherwise."
            ),
            source = "Core Rules §5.1 · Part 6",
            visualAsset = "images/rules/unit_card_supply_reference.webp",
            visualCaption = "SUPPLY PROFILE is highlighted. The top row is model-count brackets; the number beneath each bracket is the Current Supply for that many remaining models.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "phase_boxes",
            title = "PHASE BOXES",
            understoodAs = "What are the phase boxes on a Unit Card?",
            aliases = listOf("phase boxes", "phase box", "what are phase boxes", "movement phase box", "assault phase box", "combat phase box", "any phase box", "abilities by phase", "weapons by phase"),
            requiredGroups = listOf(setOf("phase"), setOf("box", "ability", "weapon")),
            summary = "PHASE BOXES group the Special Abilities and weapon profiles that are available during particular game Phases.",
            bullets = listOf(
                "A weapon profile belongs to either the Assault Phase or Combat Phase and may only be used in that phase.",
                "An ANY PHASE ability follows its own timing/type rules rather than being tied to one numbered Phase."
            ),
            source = "Core Rules §5.1 · Part 10",
            visualAsset = "images/rules/unit_card_front_reference.webp",
            visualCaption = "The Unit Card groups abilities and weapons under ANY PHASE, MOVEMENT PHASE, ASSAULT PHASE and COMBAT PHASE headers.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "weapon_profile",
            title = "WEAPON PROFILE",
            understoodAs = "How do I read a weapon profile?",
            aliases = listOf("weapon profile", "weapon stats", "read weapon", "read weapon profile", "what are weapon stats", "weapon numbers", "what do weapon columns mean"),
            requiredGroups = listOf(setOf("weapon"), setOf("profile", "stat", "column", "number")),
            summary = "A weapon profile tells you when and how that weapon attacks: RNG, Target, RoA, Hit, Surge Type, S Dice, DMG and any weapon Keyword.",
            bullets = listOf(
                "RNG = maximum effective distance. Target = Ground/Flying/All. RoA = dice per firing model. Hit = D6 Target Number.",
                "Surge Type checks the target’s Combat Tags; S Dice determines Surge results. DMG is Damage per die that reaches the Damage Pool.",
                "Keywords such as PIERCE, SIDEARM or ANTI-EVADE add special rules."
            ),
            source = "Core Rules §5.1",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "WEAPON PROFILE REFERENCE: the column headings are the exact fields the Field Manual can explain individually.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "weapon_range",
            title = "RNG / WEAPON RANGE",
            understoodAs = "What does RNG mean on a weapon?",
            aliases = listOf("rng", "weapon range", "range stat", "what is rng", "what does rng mean", "how far can weapon shoot", "how far can i shoot", "shooting range", "weapon distance"),
            requiredGroups = listOf(setOf("rng", "range"), setOf("weapon", "shoot", "distance")),
            summary = "RNG is the weapon’s maximum effective distance. At least one Visible model in the target Unit must be within that range for a normal Ranged Attack.",
            bullets = listOf(
                "Measure from the attacking model’s base to the target model’s base.",
                "E in the RNG field means Engagement Range for a melee weapon.",
                "FT means the weapon uses the Flamer Template instead of a normal numeric range."
            ),
            source = "Core Rules §5.1 · §8.7.3",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "Look under RNG in the weapon row. A number is inches; E means Engagement Range; FT means Flamer Template.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "weapon_target",
            title = "WEAPON TARGET",
            understoodAs = "What does Ground / Flying / All mean in a weapon Target field?",
            aliases = listOf("weapon target", "target field", "ground flying all", "what does ground mean on weapon", "what does flying mean on weapon", "what does all mean on weapon", "what can weapon target"),
            requiredGroups = listOf(setOf("target", "ground", "flying", "all"), setOf("weapon")),
            summary = "The Target field tells you which Combat Tag the weapon is allowed to attack: Ground only, Flying only, or All for either.",
            bullets = listOf(
                "Target eligibility still also checks Range, visibility/Line of Sight and engagement restrictions.",
                "This Target field is separate from Surge Type, which checks Combat Tags for Surge efficiency."
            ),
            source = "Core Rules §5.1 · §8.7.3",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "Look under Target in the weapon row: Ground, Flying or All.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "rate_of_attack",
            title = "RoA · RATE OF ATTACK",
            understoodAs = "What is RoA on a weapon profile?",
            aliases = listOf(
                "roa", "rate of attack", "what is roa", "what does roa mean",
                "attack dice per model", "how many attack dice", "how many dice weapon", "weapon dice",
                "attack dice amount", "attack dice number", "attack dice count",
                "combat dice amount", "combat dice number", "combat dice count",
                "unit combat dice amount", "unit combat dice number", "unit combat dice count",
                "unit attack dice", "number of attack dice", "number of combat dice",
                "how many combat dice", "how many dice do i roll for an attack",
                "how many dice does this unit roll", "dice rolled per firing model"
            ),
            requiredGroups = listOf(setOf("roa", "rate", "dice"), setOf("attack", "weapon")),
            summary = "RoA (Rate of Attack) is the number of attack dice rolled per firing model for that weapon Batch.",
            bullets = listOf(
                "Only models eligible for that Batch contribute their RoA dice.",
                "If several models fire the same weapon Batch, multiply the printed RoA by the number of firing models before other rules modify the Attack Pool."
            ),
            source = "Core Rules §5.1 · §8.7.3",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "Look under RoA. This number tells you how many attack dice each firing model contributes.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "hit_target_number",
            title = "HIT TARGET NUMBER",
            understoodAs = "What is the Hit number on a weapon profile?",
            aliases = listOf("hit number", "hit stat", "weapon hit", "what is hit", "what does hit mean", "3+ hit", "4+ hit", "hit target number", "roll to hit"),
            requiredGroups = listOf(setOf("hit")),
            summary = "Hit is the D6 Target Number required for an attack die to score a hit.",
            bullets = listOf(
                "A value such as 3+ succeeds on a natural/modified result of 3 or higher, subject to the game’s modifier rules.",
                "Successful hit dice proceed through the attack-resolution process; failed hit dice are discarded."
            ),
            source = "Core Rules §5.1 · §8.7.4",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "Look under Hit. Values such as 3+ or 4+ are D6 Target Numbers.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "surge_type",
            title = "SURGE TYPE",
            understoodAs = "What is Surge Type on a weapon profile?",
            aliases = listOf("surge type", "what is surge type", "surge tag", "light surge", "ground surge", "biological surge", "combat tag surge"),
            requiredGroups = listOf(setOf("surge"), setOf("type", "tag", "light", "ground", "biological")),
            summary = "Surge Type is the Combat Tag that makes that weapon’s Surge effect efficient against the target.",
            bullets = listOf(
                "Compare the printed Surge Type with the target Unit’s Combat Tags.",
                "When the relevant Surge condition is met, the weapon’s S Dice determines the Surge results according to the attack rules."
            ),
            source = "Core Rules §5.1 · §8.7.4",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "Look under Surge type, then compare that entry with the target Unit’s Combat Tags.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "surge_dice",
            title = "S DICE · SURGE DICE",
            understoodAs = "What is S Dice on a weapon profile?",
            aliases = listOf("s dice", "sdice", "surge dice", "surge die", "what is s dice", "what does sdice mean", "d3 surge", "d6 surge", "d3+1 s dice"),
            requiredGroups = listOf(setOf("dice", "sdice", "surge")),
            summary = "S Dice is the die expression used to determine Surge results for that weapon, normally based on a D3 or D6 expression printed in the profile.",
            bullets = listOf(
                "Read the entire printed expression, including any + or - adjustment.",
                "S Dice is not the weapon’s Rate of Attack; RoA determines attack dice, while S Dice is used specifically for Surge resolution."
            ),
            source = "Core Rules §5.1 · §8.7.4",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "Look under S Dice. Expressions such as D3, D6 or D3+1 belong to Surge resolution, not the initial Attack Pool.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "weapon_damage",
            title = "DMG · WEAPON DAMAGE",
            understoodAs = "What is DMG on a weapon profile?",
            aliases = listOf("dmg", "weapon damage", "damage stat", "what is dmg", "what does dmg mean", "damage number on weapon", "damage per die"),
            requiredGroups = listOf(setOf("dmg", "damage")),
            summary = "DMG is the Damage inflicted by each die that remains in the Damage Pool for that weapon.",
            bullets = listOf(
                "Armour and any eligible Evade Rolls can remove dice before Damage is applied.",
                "The printed DMG is per remaining Damage Pool die; then Total Damage is resolved against model HP."
            ),
            source = "Core Rules §5.1 · §8.7.4–8.7.5",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "Look under Dmg. This is damage per die that survives into the Damage Pool.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "weapon_keyword_field",
            title = "WEAPON KEYWORD",
            understoodAs = "What is the Keyword field on a weapon profile?",
            aliases = listOf("weapon keyword", "keyword column", "what is keyword on weapon", "pierce on weapon", "sidearm on weapon", "weapon special rule"),
            requiredGroups = listOf(setOf("keyword"), setOf("weapon")),
            summary = "The Keyword field lists special rules that modify how the weapon behaves, such as PIERCE, SIDEARM, ANTI-EVADE or other printed weapon keywords.",
            bullets = listOf(
                "Look up the printed keyword by name in the Field Manual’s keyword glossary for its exact effect.",
                "Numeric keywords such as ANTI-EVADE (1) include their value as part of the rule."
            ),
            source = "Core Rules §5.1 · Part 11",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "Look under Keyword, then search that printed keyword by name for the full rule.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "combat_tags",
            title = "COMBAT TAGS",
            understoodAs = "What are Combat Tags on a Unit Card?",
            aliases = listOf("combat tags", "combat tag", "what are combat tags", "biological light ground", "armored massive psionic", "unit tags", "what do combat tags mean"),
            requiredGroups = listOf(setOf("combat", "tag")),
            summary = "Combat Tags are keywords printed on the Unit Card that describe the Unit’s physical/tactical class. Other rules and weapon Surge Types refer to them.",
            bullets = listOf(
                "Examples include GROUND, FLYING, BIOLOGICAL, LIGHT and other tags printed on the card.",
                "A weapon’s Surge Type can care whether the target possesses a matching Combat Tag."
            ),
            source = "Core Rules §5.1 · Part 11",
            visualAsset = "images/rules/unit_card_combat_tags_reference.webp",
            visualCaption = "COMBAT TAGS are highlighted at the bottom of the Unit Card. These tags are referenced by other rules and weapon effects.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "unit_faction_tag",
            title = "UNIT FACTION TAG",
            understoodAs = "What is the faction tag on a Unit Card?",
            aliases = listOf("unit faction tag", "faction tag on unit", "what is faction tag", "terran tag", "protoss tag", "zerg tag", "unit race tag"),
            requiredGroups = listOf(setOf("faction", "race", "terran", "protoss", "zerg"), setOf("tag")),
            summary = "The Unit’s Faction Tag identifies which army/faction eligibility rules that Unit belongs to.",
            bullets = listOf(
                "During Army Building, every Faction Tag on a Unit or Tactical Card must also appear on the chosen Faction Card.",
                "A missing required tag makes that card ineligible for the army."
            ),
            source = "Core Rules §5.1 · §9.1.2",
            visualAsset = "images/rules/unit_card_faction_tag_reference.webp",
            visualCaption = "The Unit FACTION TAG is highlighted near the bottom-left of the Unit Card.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "unit_upgrade",
            title = "UNIT UPGRADE",
            understoodAs = "What is an Upgrade on the reverse of a Unit Card?",
            aliases = listOf("unit upgrade", "upgrade on card", "what is upgrade", "unit card reverse upgrade", "upgrade ability", "upgrade weapon",
                "how do i buy upgrades", "how do specialist upgrades work", "specialist upgrades"),
            requiredGroups = listOf(setOf("upgrade")),
            summary = "An UPGRADE is an optional new ability or weapon available to that Unit during Army Building.",
            bullets = listOf(
                "Upgrades are purchased according to the army-building rules and their listed costs.",
                "The reverse of the Unit Card shows the upgrade content and any replacement information."
            ),
            source = "Core Rules §5.2 · §9.1.7",
            visualAsset = "images/rules/unit_card_reverse_reference.webp",
            visualCaption = "UNIT CARD REVERSE: upgrades and replacement weapon profiles are shown here.",
            visualAspectRatio = 1.407f
        ),
        AskTopic(
            id = "weapon_replacement",
            title = "↑FOR · WEAPON REPLACEMENT",
            understoodAs = "What does “↑FOR [weapon]” mean on a Unit Card?",
            aliases = listOf("for weapon", "replacement weapon", "weapon replacement", "what does for mean on upgrade", "replaces weapon", "up arrow for", "↑for"),
            requiredGroups = listOf(setOf("replace", "replacement", "for"), setOf("weapon", "upgrade")),
            summary = "An entry marked “↑FOR [Weapon Name]” means the new weapon replaces the named original weapon entirely.",
            bullets = listOf(
                "Do not use both profiles as separate weapons after taking that replacement; use the replacement profile instead."
            ),
            source = "Core Rules §5.2",
            visualAsset = "images/rules/unit_card_reverse_reference.webp",
            visualCaption = "On the Unit Card reverse, replacement weapon profiles identify which original weapon they replace.",
            visualAspectRatio = 1.407f
        ),
        AskTopic(
            id = "combat_range",
            title = "COMBAT RANGE",
            understoodAs = "What is Combat Range on the reverse of a Unit Card?",
            aliases = listOf("combat range", "what is combat range", "close combat ranged combat bar", "preferred range", "unit combat range"),
            requiredGroups = listOf(setOf("combat", "range")),
            summary = "COMBAT RANGE is the preferred distance band the Unit seeks to maintain from Enemy Units, shown on the reverse of the Unit Card.",
            bullets = listOf(
                "It describes the Unit’s intended close/ranged combat role; it is not the same thing as a weapon’s RNG characteristic."
            ),
            source = "Core Rules §5.2",
            visualAsset = "images/rules/unit_card_reverse_reference.webp",
            visualCaption = "The numbered reverse reference marks COMBAT RANGE separately from the weapon RNG values.",
            visualAspectRatio = 1.407f
        ),
        AskTopic(
            id = "army_slot",
            title = "ARMY SLOT",
            understoodAs = "What is the Army Slot on a Unit Card?",
            aliases = listOf("army slot", "core elite support air hero", "what is army slot", "unit slot", "slot type", "how many slots unit", "core slot", "elite slot", "support slot", "air slot", "hero slot"),
            requiredGroups = listOf(setOf("slot"), setOf("army", "core", "elite", "support", "air", "hero")),
            summary = "ARMY SLOT is the organisation category required to field the Unit: Core, Elite, Support, Air or Hero.",
            bullets = listOf(
                "A Unit occupies a number of slots of that type equal to its starting Supply Value.",
                "Faction Cards provide starting slots and Tactical Cards can unlock additional slots."
            ),
            source = "Core Rules §5.2 · §9.1.5–9.1.6",
            visualAsset = "images/rules/unit_card_reverse_reference.webp",
            visualCaption = "The Unit Card reverse identifies its ARMY SLOT type. Starting Supply determines how many of those slots that Unit occupies.",
            visualAspectRatio = 1.407f
        ),
        AskTopic(
            id = "base_diameter",
            title = "BASE DIAMETER",
            understoodAs = "What does the mm value on a Unit Card mean?",
            aliases = listOf("base diameter", "base size", "40mm", "32mm", "50mm", "what does mm mean", "mm on unit card", "what base do i use", "model base size"),
            requiredGroups = listOf(setOf("base", "mm", "diameter", "size")),
            summary = "BASE DIAMETER is the physical base size the model must be mounted on, shown in millimetres on the Unit Card reverse.",
            bullets = listOf(
                "This is different from the Unit’s SIZE characteristic, which is a game height category used for Line of Sight and terrain."
            ),
            source = "Core Rules §5.2",
            visualAsset = "images/rules/unit_card_reverse_reference.webp",
            visualCaption = "The Unit Card reverse shows the required base diameter (for example Ø40MM).",
            visualAspectRatio = 1.407f
        ),
        AskTopic(
            id = "target_number_notation",
            title = "TARGET NUMBER · “5+”",
            understoodAs = "What does a value such as 5+ mean?",
            aliases = listOf("5+", "4+", "3+", "2+", "6+", "what does 5+ mean", "what is 5 plus", "what does plus mean on stat", "target number", "d6 target number", "roll 5 or higher"),
            requiredGroups = listOf(setOf("5+", "4+", "3+", "2+", "6+", "target")),
            summary = "A value written as N+ is a D6 Target Number: roll that number or higher to succeed.",
            bullets = listOf(
                "Examples: 3+ succeeds on 3, 4, 5 or 6; 5+ succeeds on 5 or 6.",
                "Modifiers can change a Target Number, but Target Numbers normally cannot be modified below 2+ or above 6+."
            ),
            source = "Core Rules Part 3 · §5.1 · FAQ",
            visualAsset = "images/rules/unit_card_front_reference.webp",
            visualCaption = "ARMOUR, EVADE and weapon Hit commonly use N+ Target Numbers on cards.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "split_value_notation",
            title = "SPLIT VALUE · “5/8”",
            understoodAs = "What does a split SPEED value such as 5/8 mean?",
            aliases = listOf("5/8", "4/7", "split value", "split speed", "what does 5/8 mean", "two speed numbers", "slash speed", "speed slash"),
            requiredGroups = listOf(setOf("speed", "split", "5", "8", "slash")),
            summary = "A split SPEED value uses the first number normally and the second number only when the Unit is reduced to one remaining model, or if the Unit started with one model.",
            bullets = listOf(
                "Example: SPEED 5/8 means SPEED 5 while multi-model, then SPEED 8 once only one model remains."
            ),
            source = "Core Rules §5.1",
            visualAsset = "images/rules/unit_card_speed_reference.webp",
            visualCaption = "The highlighted SPEED 5/8 is a split value: first number normally, second number when only one model remains.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "null_value_notation",
            title = "NULL VALUE · “-”",
            understoodAs = "What does a dash (-) mean in a characteristic?",
            aliases = listOf("null value", "dash value", "what does - mean", "what does dash mean", "minus on unit card", "no speed", "no evade", "stat is dash"),
            requiredGroups = listOf(setOf("null", "dash", "speed", "evade")),
            summary = "A dash (-) is a Null Value. The exact consequence depends on the characteristic where it appears.",
            bullets = listOf(
                "SPEED -: the Unit cannot move or be repositioned by any means, including PLACE and involuntary movement.",
                "EVADE -: the Unit cannot make Evade Rolls, even if another rule would otherwise grant one.",
                "In weapon/profile fields, a dash commonly means that field is not used for that profile."
            ),
            source = "Core Rules §5.1",
            visualAsset = "images/rules/unit_card_front_reference.webp",
            visualCaption = "A dash is not “zero”; it means the characteristic/field is unavailable or does not function, with the exact effect defined by that field.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "engagement_range_symbol",
            title = "RNG “E” · ENGAGEMENT RANGE",
            understoodAs = "What does E mean in a weapon’s RNG field?",
            aliases = listOf("e range", "rng e", "what does e mean", "e on weapon", "e in rng", "engagement range symbol", "melee range e"),
            requiredGroups = listOf(setOf("e", "rng", "range", "weapon", "melee")),
            summary = "E in a weapon’s RNG field means Engagement Range rather than a numeric inch distance.",
            bullets = listOf(
                "Engagement Range for Ground Units is 1 inch.",
                "Melee/Combat weapons with RNG E are used against eligible Engaged targets according to the Combat rules."
            ),
            source = "Core Rules §5.1 · §7.2",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "In the weapon RNG column, E means Engagement Range.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "flamer_template_symbol",
            title = "RNG “FT” · FLAMER TEMPLATE",
            understoodAs = "What does FT mean in a weapon’s RNG field?",
            aliases = listOf("ft", "rng ft", "what does ft mean", "ft on weapon", "flamer template", "flame template", "template range"),
            requiredGroups = listOf(setOf("ft", "template", "flamer")),
            summary = "FT in the RNG field means the weapon uses the Flamer Template instead of a normal numeric Range.",
            bullets = listOf(
                "Place and resolve the template according to the Template Weapon rules rather than measuring a normal maximum RNG value."
            ),
            source = "Core Rules §5.1 · §8.7.6",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "FT appears in the RNG column for Flamer Template weapons.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "dice_notation",
            title = "D3 / D6 DICE NOTATION",
            understoodAs = "What do D3, D6 and expressions like D3+1 mean?",
            aliases = listOf("d3", "d6", "d3+1", "dice notation", "what is d3", "what is d6", "what does d3+1 mean", "die notation"),
            requiredGroups = listOf(setOf("d3", "d6", "dice", "die")),
            summary = "D6 means roll a six-sided die. D3 means generate a result from 1 to 3 using the game’s D3 procedure; any + or - printed after it modifies that result as instructed.",
            bullets = listOf(
                "Always read the full expression where it appears, such as D3+1.",
                "The purpose of the roll depends on the field/rule using it; for example S Dice uses a printed die expression for Surge resolution."
            ),
            source = "Core Rules Part 3 · §5.1",
            visualAsset = "images/rules/weapon_profile_reference.webp",
            visualCaption = "Weapon S Dice may show a die expression such as D3, D6 or a modified expression.",
            visualAspectRatio = 2.953f
        ),
        AskTopic(
            id = "tactical_card_anatomy",
            title = "HOW TO READ A TACTICAL CARD",
            understoodAs = "What do the numbers and symbols on a Tactical Card mean?",
            aliases = listOf("tactical card anatomy", "read tactical card", "what is on tactical card", "tactical card numbers", "tactical card explained", "what do tactical card symbols mean"),
            requiredGroups = listOf(setOf("tactical", "card"), setOf("read", "number", "symbol", "anatomy")),
            summary = "A Tactical Card provides army-building slots, faction eligibility, a faction resource value and one or more Special Abilities.",
            bullets = listOf(
                "1 Name · 2 UNIQUE marking (if present) · 3 Faction Tags · 4 Army Slots unlocked · 5 Resource Type/Value · 6 Special Abilities.",
                "Tactical Cards are purchased with Vespene Gas during Army Building."
            ),
            source = "Core Rules §5.3 · §9.1.4–9.1.5 · §10.5",
            visualAsset = "images/rules/tactical_card_reference.webp",
            visualCaption = "TACTICAL CARD REFERENCE: front and reverse with the six official numbered fields.",
            visualAspectRatio = 1.447f
        ),
        AskTopic(
            id = "unique_marking",
            title = "UNIQUE",
            understoodAs = "What does UNIQUE mean on a Tactical Card?",
            aliases = listOf("unique", "unique card", "unique marking", "what does unique mean", "only one copy tactical card", "one copy card"),
            requiredGroups = listOf(setOf("unique")),
            summary = "If a Tactical Card is marked UNIQUE, only one copy of that card may be included in the army.",
            bullets = listOf(
                "UNIQUE is checked during Army Building; it is not a once-per-round ability limit."
            ),
            source = "Core Rules §5.3 · §9.1.5",
            visualAsset = "images/rules/tactical_card_reference.webp",
            visualCaption = "The UNIQUE marking is shown on the Tactical Card front when only one copy is allowed.",
            visualAspectRatio = 1.447f
        ),
        AskTopic(
            id = "tactical_army_slots",
            title = "TACTICAL CARD ARMY SLOTS",
            understoodAs = "What do entries such as 2× SUPPORT on a Tactical Card mean?",
            aliases = listOf("2x support", "2× support", "tactical slots", "tactical army slots", "slots on tactical card", "what does 2x core mean", "what does 2x support mean", "unlocks slots"),
            requiredGroups = listOf(setOf("slot", "core", "support", "elite", "air", "hero"), setOf("tactical", "2x", "2×")),
            summary = "The Army Slots printed on a Tactical Card are additional organisation slots unlocked when that card is included in the army.",
            bullets = listOf(
                "They are added to the starting slots supplied by the Faction Card.",
                "The “2×” notation means two slots of that listed type."
            ),
            source = "Core Rules §5.3 · §9.1.5",
            visualAsset = "images/rules/tactical_card_reference.webp",
            visualCaption = "On the Tactical Card front, the Supply/slot line shows the extra Army Slots this card unlocks.",
            visualAspectRatio = 1.447f
        ),
        AskTopic(
            id = "card_resource_value",
            title = "CARD RESOURCE TYPE / VALUE",
            understoodAs = "What does CP:1, BM:1 or PE:1 on a card mean?",
            aliases = listOf("cp:1", "bm:1", "pe:1", "card resource", "resource value", "resource on tactical card", "resource on faction card", "what does cp 1 mean", "what does bm 1 mean", "what does pe 1 mean"),
            requiredGroups = listOf(setOf("cp", "bm", "pe", "resource"), setOf("card", "value")),
            summary = "CP, BM and PE are faction resource codes. The number is how much of that resource the Ready card generates when you Exhaust it instead of using its printed Special Ability.",
            bullets = listOf(
                "Terran uses CP, Zerg uses BM and Protoss uses PE.",
                "Resources generated for one ability are spent immediately; excess cannot be saved for another ability.",
                "A card may also be Exhausted to use an Active/Reaction ability printed directly on that card."
            ),
            source = "Core Rules §5.3–5.4 · §10.5.1",
            visualAsset = "images/rules/tactical_card_reference.webp",
            visualCaption = "The Tactical Card reverse shows its faction resource code and value near the top-right (example: CP:1).",
            visualAspectRatio = 1.447f
        ),
        AskTopic(
            id = "special_ability_types",
            title = "ACTIVE / PASSIVE / REACTION",
            understoodAs = "What are the three Special Ability types?",
            aliases = listOf("active passive reaction", "ability types", "special ability types", "what is active ability", "what is passive ability", "what is reaction ability", "active passive reaction difference"),
            requiredGroups = listOf(setOf("active", "passive", "reaction", "ability")),
            summary = "Special Abilities are Active, Passive or Reaction. The type tells you when the ability functions and whether it needs a player decision/trigger.",
            bullets = listOf(
                "ACTIVE: used by a currently Activated Unit immediately before declaring an action or immediately after one fully resolves; normally once per Round per named ability unless REPEATABLE.",
                "PASSIVE: always active while the Unit is on the battlefield unless its rule says otherwise.",
                "REACTION: declared at the exact printed trigger; normally once per Round per named ability and subject to the Reaction limit."
            ),
            source = "Core Rules §10.2–10.4",
            visualAsset = "images/rules/tactical_card_reference.webp",
            visualCaption = "Card abilities print their type directly beside the ability name: ACTIVE, PASSIVE or REACTION.",
            visualAspectRatio = 1.447f
        ),
        AskTopic(
            id = "faction_card_anatomy",
            title = "HOW TO READ A FACTION CARD",
            understoodAs = "What do the numbers and symbols on a Faction Card mean?",
            aliases = listOf("faction card anatomy", "read faction card", "what is on faction card", "faction card numbers", "faction card explained", "what do faction card symbols mean"),
            requiredGroups = listOf(setOf("faction", "card"), setOf("read", "number", "symbol", "anatomy")),
            summary = "Every army includes exactly one Faction Card. It defines the army’s faction identity, legal tags, starting organisation slots, faction resource and Special Abilities.",
            bullets = listOf(
                "1 Faction Name · 2 Faction Tags · 3 Starting Army Slots · 4 Resource Type/Value · 5 Special Abilities.",
                "Unit and Tactical Card faction eligibility is checked against the tags on this card."
            ),
            source = "Core Rules §5.4 · §9.1.2 · §10.5",
            visualAsset = "images/rules/faction_card_reference.webp",
            visualCaption = "FACTION CARD REFERENCE: the five official numbered fields.",
            visualAspectRatio = 1.598f
        ),
        AskTopic(
            id = "starting_army_slots",
            title = "STARTING ARMY SLOTS",
            understoodAs = "What are Starting Army Slots on a Faction Card?",
            aliases = listOf("starting army slots", "faction card slots", "starting slots", "3x core 1x hero", "what are starting slots", "what does 3x core mean"),
            requiredGroups = listOf(setOf("slot"), setOf("starting", "faction", "core", "hero")),
            summary = "Starting Army Slots are the organisation slots supplied by the Faction Card before any Tactical Cards add more.",
            bullets = listOf(
                "Slots use the Core, Elite, Support, Air and Hero categories.",
                "Tactical Cards can unlock additional slots beyond these starting values."
            ),
            source = "Core Rules §5.4 · §9.1.2 · §9.1.5",
            visualAsset = "images/rules/faction_card_reference.webp",
            visualCaption = "The Faction Card front lists its starting Army Slots along the bottom.",
            visualAspectRatio = 1.598f
        ),
        AskTopic(
            id = "mission_card_anatomy",
            title = "HOW TO READ A MISSION CARD",
            understoodAs = "What do the numbers and boxes on a Mission Card mean?",
            aliases = listOf("mission card anatomy", "read mission card", "mission card numbers", "what is on mission card", "mission card explained", "mission card fields"),
            requiredGroups = listOf(setOf("mission", "card"), setOf("read", "number", "field", "anatomy")),
            summary = "A Mission Card defines the battle’s scale, Supply progression, Round limit, setup parameters, scoring and any special winning conditions.",
            bullets = listOf(
                "1 Name · 2 Engagement Scale · 3 Starting Supply · 4 Supply Escalation · 5 Game Length · 6 Mission Parameters · 7 Scoring Conditions · 8 Additional Conditions."
            ),
            source = "Core Rules §5.5",
            visualAsset = "images/rules/mission_card_reference.webp",
            visualCaption = "MISSION CARD REFERENCE: the numbered callouts identify the values used to set up, pace and score the game.",
            visualAspectRatio = 0.661f
        ),
        AskTopic(
            id = "engagement_scale",
            title = "ENGAGEMENT SCALE",
            understoodAs = "What is Engagement Scale?",
            aliases = listOf("engagement scale", "skirmish standard grand offensive", "what is skirmish scale", "what is standard scale", "what is grand offensive", "game scale"),
            requiredGroups = listOf(setOf("scale", "skirmish", "standard", "grand", "engagement")),
            summary = "Engagement Scale is the agreed game size. It sets the army Mineral limit and associated Vespene limit, and Mission/Deployment Cards identify which scale they are designed for.",
            bullets = listOf(
                "Skirmish: up to 1,000 Minerals. Standard: up to 2,000 Minerals. Grand Offensive: 2,001+ Minerals.",
                "Vespene Gas limit is 10% of the Mineral limit under the standard army-building table.",
                "The standard battlefield sizes are 36×36, 36×54 and 36×72 respectively."
            ),
            source = "Core Rules §9.1.1 · §5.5–5.6",
            visualAsset = "images/rules/mission_card_reference.webp",
            visualCaption = "Mission and Deployment Cards print their intended Engagement Scale.",
            visualAspectRatio = 0.661f
        ),
        AskTopic(
            id = "starting_supply",
            title = "STARTING SUPPLY",
            understoodAs = "What is Starting Supply on a Mission Card?",
            aliases = listOf("starting supply", "start supply", "round 1 supply", "what is starting supply", "mission starting supply", "supply at start"),
            requiredGroups = listOf(setOf("supply"), setOf("start", "starting", "round")),
            summary = "Starting Supply is the Supply Pool available to each player in Round 1, as printed on the Mission Card.",
            bullets = listOf(
                "It limits the Total Current Supply of Friendly Units that can be on the battlefield at once.",
                "Supply Escalation increases that pool at the start of later Rounds."
            ),
            source = "Core Rules §5.5 · Part 6",
            visualAsset = "images/rules/mission_card_reference.webp",
            visualCaption = "The Mission Card’s Starting Supply value is callout 3.",
            visualAspectRatio = 0.661f
        ),
        AskTopic(
            id = "supply_escalation",
            title = "SUPPLY ESCALATION",
            understoodAs = "What is Supply Escalation on a Mission Card?",
            aliases = listOf("supply escalation", "supply per round", "plus supply per round", "+2 per round", "what is supply escalation", "mission supply increase"),
            requiredGroups = listOf(setOf("supply"), setOf("escalation", "round", "increase")),
            summary = "Supply Escalation is the amount by which each player’s Supply Pool increases at the start of each subsequent Round.",
            bullets = listOf(
                "Add the printed escalation amount when the new Round begins, then use the new Supply Pool to calculate Available Supply."
            ),
            source = "Core Rules §5.5 · Part 6",
            visualAsset = "images/rules/mission_card_reference.webp",
            visualCaption = "The Mission Card’s Supply Escalation value is callout 4 (example: +2 per Round).",
            visualAspectRatio = 0.661f
        ),
        AskTopic(
            id = "mission_parameters",
            title = "MISSION PARAMETERS",
            understoodAs = "What are Mission Parameters?",
            aliases = listOf("mission parameters", "what are mission parameters", "mission setup instructions", "marker states", "special setup mission"),
            requiredGroups = listOf(setOf("mission"), setOf("parameter", "setup", "marker", "condition")),
            summary = "Mission Parameters are setup instructions, Mission Marker states and special conditions that apply from the start of that mission.",
            bullets = listOf(
                "Read and apply them during setup before normal play begins unless the card specifies different timing."
            ),
            source = "Core Rules §5.5 · §9.2",
            visualAsset = "images/rules/mission_card_reference.webp",
            visualCaption = "Mission Parameters are the first rules text box on the Mission Card (callout 6).",
            visualAspectRatio = 0.661f
        ),
        AskTopic(
            id = "scoring_conditions",
            title = "SCORING CONDITIONS",
            understoodAs = "What are Scoring Conditions on a Mission Card?",
            aliases = listOf("scoring conditions", "mission scoring", "how score mission", "what are scoring conditions", "how get vp mission", "mission vp"),
            requiredGroups = listOf(setOf("score", "scoring", "vp", "victory"), setOf("mission")),
            summary = "Scoring Conditions tell players exactly how that Mission awards Victory Points, normally checked during Phase 4 at the end of each Round.",
            bullets = listOf(
                "The printed Mission Card is authoritative for which achievements score and how many VPs they award."
            ),
            source = "Core Rules §5.5 · §8.9.2",
            visualAsset = "images/rules/mission_card_reference.webp",
            visualCaption = "Scoring Conditions are the Mission Card box marked with callout 7.",
            visualAspectRatio = 0.661f
        ),
        AskTopic(
            id = "additional_conditions",
            title = "ADDITIONAL CONDITIONS",
            understoodAs = "What are Additional Conditions on a Mission Card?",
            aliases = listOf("additional conditions", "mission special rules", "special winning condition", "instant victory", "mission additional condition", "what are additional conditions"),
            requiredGroups = listOf(setOf("condition", "winning", "victory", "special"), setOf("mission")),
            summary = "Additional Conditions are mission-specific special rules or winning conditions, including any instant-victory triggers.",
            bullets = listOf(
                "These can change or add to the normal end-of-game procedure, so read them before the battle begins."
            ),
            source = "Core Rules §5.5",
            visualAsset = "images/rules/mission_card_reference.webp",
            visualCaption = "Additional Conditions are the final Mission Card rules box (callout 8).",
            visualAspectRatio = 0.661f
        ),
        AskTopic(
            id = "deployment_card_anatomy",
            title = "HOW TO READ A DEPLOYMENT CARD",
            understoodAs = "What do the numbers and measurements on a Deployment Card mean?",
            aliases = listOf("deployment card anatomy", "read deployment card", "deployment card numbers", "what is on deployment card", "deployment measurements", "deployment card explained"),
            requiredGroups = listOf(setOf("deployment", "card"), setOf("read", "number", "measurement", "anatomy")),
            summary = "A Deployment Card defines the battlefield geometry: game scale, table size, Entry Edges, Zones of Influence and exact Mission Marker coordinates.",
            bullets = listOf(
                "1 Name · 2 Engagement Scale · 3 Battlefield Dimensions · 4 Entry Edges · 5 Zone of Influence · 6 Marker Coordinates.",
                "During setup, confirm dimensions, assign Entry Edges, place Zone of Influence markers where applicable, then place Mission Markers at the listed coordinates."
            ),
            source = "Core Rules §5.6 · §9.3",
            visualAsset = "images/rules/deployment_card_reference.webp",
            visualCaption = "DEPLOYMENT CARD REFERENCE: the numbered callouts identify table size, Entry Edges, Zone of Influence and Marker coordinates.",
            visualAspectRatio = 1.375f
        ),
        AskTopic(
            id = "battlefield_dimensions",
            title = "BATTLEFIELD DIMENSIONS",
            understoodAs = "What do the table-size numbers on a Deployment Card mean?",
            aliases = listOf("battlefield dimensions", "table dimensions", "table size", "36x36", "36 x 36", "36x54", "36x72", "what size table", "battlefield size"),
            requiredGroups = listOf(setOf("battlefield", "table", "36x36", "36x54", "36x72"), setOf("size", "dimension")),
            summary = "Battlefield Dimensions are the required physical table size for that Deployment layout.",
            bullets = listOf(
                "Use the exact dimensions printed on the selected Deployment Card.",
                "The standard scale table lists 36×36 for Skirmish, 36×54 for Standard and 36×72 for Grand Offensive."
            ),
            source = "Core Rules §5.6 · §9.1.1",
            visualAsset = "images/rules/deployment_card_reference.webp",
            visualCaption = "Battlefield Dimensions are printed at the top-right of the Deployment Card (callout 3).",
            visualAspectRatio = 1.375f
        ),
        AskTopic(
            id = "entry_edges",
            title = "ENTRY EDGES",
            understoodAs = "What are Entry Edges on a Deployment Card?",
            aliases = listOf("entry edges", "entry edge", "what is entry edge", "where do units deploy", "which edge deploy", "deployment edge"),
            requiredGroups = listOf(setOf("entry", "edge", "deploy")),
            summary = "Entry Edges are the table edges assigned to each player. Units normally enter the battlefield from their assigned Entry Edge when deploying from Reserves.",
            bullets = listOf(
                "The Deployment Card shows which physical edges belong to each player.",
                "Special rules can create additional Friendly Entry Edges; those exceptions say so explicitly."
            ),
            source = "Core Rules §5.6 · §8.3.3",
            visualAsset = "images/rules/deployment_card_reference.webp",
            visualCaption = "Entry Edges are the coloured table edges shown by callout 4.",
            visualAspectRatio = 1.375f
        ),
        AskTopic(
            id = "marker_coordinates",
            title = "MARKER COORDINATES",
            understoodAs = "What are the measurements on a Deployment Card for?",
            aliases = listOf("marker coordinates", "mission marker coordinates", "mission marker measurements", "6 inches marker", "12 inches marker", "where place objective", "where place mission markers"),
            requiredGroups = listOf(setOf("marker", "objective"), setOf("coordinate", "place", "measurement", "inch")),
            summary = "Marker Coordinates are the exact measurements used to position each numbered Mission Marker on the battlefield during setup.",
            bullets = listOf(
                "Measure from the edges/lines indicated by the selected Deployment Card rather than estimating marker positions."
            ),
            source = "Core Rules §5.6 · §9.3",
            visualAsset = "images/rules/deployment_card_reference.webp",
            visualCaption = "The measurement arrows and numbered marker positions on the Deployment Card are the Marker Coordinates (callout 6).",
            visualAspectRatio = 1.375f
        ),
        AskTopic(
            id = "minerals",
            title = "MINERALS",
            understoodAs = "What are Minerals used for?",
            aliases = listOf("minerals", "mineral cost", "what are minerals", "what is mineral cost", "buy units", "unit cost resource"),
            requiredGroups = listOf(setOf("mineral")),
            summary = "Minerals are the army-building resource used to recruit Units and purchase Upgrades.",
            bullets = listOf(
                "Your total Mineral cost cannot exceed the agreed Engagement Scale limit.",
                "Unspent Minerals are lost; they are not converted into Vespene Gas."
            ),
            source = "Core Rules §9.1.3"
        ),
        AskTopic(
            id = "vespene_gas",
            title = "VESPENE GAS",
            understoodAs = "What is Vespene Gas used for?",
            aliases = listOf("vespene gas", "vespene", "gas", "what is vespene", "what is gas for", "tactical card cost", "buy tactical cards",
                "how much vespene gas can i spend", "vespene gas limit", "gas spending limit"),
            requiredGroups = listOf(setOf("vespene", "gas")),
            summary = "Vespene Gas is the army-building resource used exclusively to purchase Tactical Cards.",
            bullets = listOf(
                "It cannot be converted into Minerals.",
                "Under the standard Engagement Scale table the Vespene limit is 10% of the Mineral limit.",
                "Unspent Vespene Gas is lost."
            ),
            source = "Core Rules §9.1.4"
        ),
        AskTopic(
            id = "faction_resources",
            title = "CP / BM / PE",
            understoodAs = "What are CP, BM and PE?",
            aliases = listOf("cp", "bm", "pe", "cp bm pe", "what is cp", "what is bm", "what is pe", "terran resource", "zerg resource", "protoss resource", "faction resource"),
            requiredGroups = listOf(setOf("cp", "bm", "pe", "resource")),
            summary = "CP, BM and PE are the three faction resource types spent on Special Ability costs: Terran uses CP, Zerg uses BM and Protoss uses PE.",
            bullets = listOf(
                "Ready Tactical/Faction Cards generate their printed resource value when Exhausted.",
                "The generated resource must be spent immediately on that ability cost; excess is lost and cannot be banked."
            ),
            source = "Core Rules §10.5.1",
            visualAsset = "images/rules/tactical_card_reference.webp",
            visualCaption = "Tactical and Faction Cards print their resource type/value near the top of the reverse side.",
            visualAspectRatio = 1.447f
        ),
        AskTopic(
            id = "victory_points",
            title = "VICTORY POINTS (VP)",
            understoodAs = "What are Victory Points?",
            aliases = listOf("vp", "victory points", "victory point", "what is vp", "what are victory points", "score points", "game score"),
            requiredGroups = listOf(setOf("vp", "victory", "score")),
            summary = "Victory Points are the game score. Mission Cards specify how VPs are earned, usually during Phase 4 Scoring & Cleanup.",
            bullets = listOf(
                "At the end of a Round, fewer Victory Points normally takes the First Player Marker for the next Round; ties use a Roll-Off.",
                "The final score and any mission-specific winning conditions determine the winner."
            ),
            source = "Core Rules §8.9.2 · §8.9.6 · Mission Cards",
            visualAsset = "images/rules/mission_card_reference.webp",
            visualCaption = "Mission Cards define exactly how Victory Points are scored for that battle.",
            visualAspectRatio = 0.661f
        ),
        AskTopic(
            id = "supply_pool",
            title = "SUPPLY POOL",
            understoodAs = "What is the Supply Pool?",
            aliases = listOf("supply pool", "what is supply pool", "max supply battlefield", "supply cap", "mission supply pool"),
            requiredGroups = listOf(setOf("supply"), setOf("pool", "cap", "maximum")),
            summary = "The Supply Pool is the maximum Total Current Supply a player may normally have on the battlefield at one time.",
            bullets = listOf(
                "It begins at the Mission Card’s Starting Supply and increases by Supply Escalation each Round.",
                "In the final Round, the Supply Pool becomes unlimited and Available Supply restrictions are lifted."
            ),
            source = "Core Rules Part 6 · §8.3.2",
            visualAsset = "images/rules/mission_card_reference.webp",
            visualCaption = "Starting Supply and Supply Escalation on the Mission Card determine the Supply Pool over the course of the game.",
            visualAspectRatio = 0.661f
        ),
        AskTopic(
            id = "current_supply",
            title = "CURRENT SUPPLY VALUE",
            understoodAs = "What is a Unit’s Current Supply?",
            aliases = listOf("current supply", "current supply value", "unit supply value", "what is current supply", "supply value", "how much supply unit uses"),
            requiredGroups = listOf(setOf("supply"), setOf("current", "value", "unit")),
            summary = "Current Supply Value is the Supply value shown by the Unit’s present model-count bracket on its Supply Profile.",
            bullets = listOf(
                "Update it immediately when casualties reduce the Unit into a different bracket.",
                "It counts against the Supply Pool, contributes to Mission Marker control and is compared for Disengage Tactical Mass."
            ),
            source = "Core Rules §6.1 · Part 11",
            visualAsset = "images/rules/unit_card_supply_reference.webp",
            visualCaption = "Read the Unit’s current model-count bracket in the Supply Profile, then use the Supply number beneath it.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "available_supply",
            title = "AVAILABLE SUPPLY",
            understoodAs = "What is Available Supply?",
            aliases = listOf("available supply", "what is available supply", "free supply", "remaining supply", "how much supply left", "deploy capacity"),
            requiredGroups = listOf(setOf("supply"), setOf("available", "free", "remaining", "left")),
            summary = "Available Supply is the remaining battlefield capacity: Supply Pool minus the Total Current Supply of all Friendly Units already on the battlefield.",
            bullets = listOf(
                "A Unit may deploy from Reserves only if its Current Supply Value is less than or equal to Available Supply.",
                "Destroyed Units or casualties can free Available Supply for later deployments."
            ),
            source = "Core Rules §8.3.2 · Part 11"
        ),
        AskTopic(
            id = "composition_option",
            title = "COMPOSITION OPTION",
            understoodAs = "What is a Unit Composition Option?",
            aliases = listOf("composition option", "unit composition", "model count cost", "unit model count", "how many models in unit", "muster unit size", "composition and minerals"),
            requiredGroups = listOf(setOf("composition", "model"), setOf("unit", "cost", "count")),
            summary = "A Composition Option is an allowed model count for a Unit together with its corresponding Mineral Cost.",
            bullets = listOf(
                "Choose one listed Composition Option when mustering that Unit; you cannot field a model count that is not listed.",
                "The chosen starting model count also determines the Unit’s starting Supply from its Supply Profile."
            ),
            source = "Core Rules §9.1.6"
        ),
        AskTopic(
            id = "run_distance",
            title = "RUN DISTANCE",
            understoodAs = "How far can a Unit Run?",
            aliases = listOf("how far can unit run", "run distance", "how far run", "how many inches run", "unit run speed",
                "how does run work", "run action"),
            requiredGroups = listOf(setOf("run"), setOf("far", "distance", "inch", "speed")),
            summary = "A Run uses the same standard movement procedure as a Move: move the Leading Model up to the Unit’s current SPEED, then set the remaining models In Coherency.",
            bullets = listOf(
                "Run is an Assault Phase action for an Unengaged Unit.",
                "All standard Move restrictions apply, including Gap Clearance and not ending Within Engagement Range of Enemy Units."
            ),
            source = "Core Rules §8.7.1 · §8.5.3",
            visualAsset = "images/rules/unit_card_speed_reference.webp",
            visualCaption = "RUN uses the Unit’s SPEED characteristic, highlighted here.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "disengage_distance",
            title = "DISENGAGE DISTANCE",
            understoodAs = "How far can a Unit Disengage?",
            aliases = listOf("how far disengage", "disengage distance", "how many inches disengage", "how far can engaged unit move away"),
            requiredGroups = listOf(setOf("disengage"), setOf("far", "distance", "inch", "speed")),
            summary = "Disengage follows the standard Move rules, so the Leading Model may move up to the Unit’s current SPEED.",
            bullets = listOf(
                "Models must finish strictly outside the Engagement Range of all Enemy Units; models that cannot clear can be removed under the Disengage rules.",
                "Disengaging normally prevents Ranged Attack and Charge in the following Assault Phase unless the Unit has greater Current Supply than the combined Supply of enemies it was Engaged with."
            ),
            source = "Core Rules §8.5.4",
            visualAsset = "images/rules/unit_card_speed_reference.webp",
            visualCaption = "DISENGAGE uses standard movement, so read the Unit’s SPEED characteristic for the maximum path distance.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "deploy_distance",
            title = "DEPLOY DISTANCE",
            understoodAs = "How far from an Entry Edge can a Unit deploy?",
            aliases = listOf("deploy distance", "how far deploy", "how far from entry edge", "deployment distance", "how many inches deploy", "deploy from edge speed"),
            requiredGroups = listOf(setOf("deploy", "entry"), setOf("far", "distance", "inch", "speed", "edge")),
            summary = "For a normal Deploy, treat the Entry Edge as the base of a Leading Model, then move the Leading Model up to the Unit’s SPEED using standard Move rules.",
            bullets = listOf(
                "The Leading Model enters first, then set the rest of the Unit In Coherency.",
                "The Unit cannot finish its normal deployment inside the Enemy Zone of Influence unless another rule creates an exception."
            ),
            source = "Core Rules §8.3.3 · §8.5.3",
            visualAsset = "images/rules/unit_card_speed_reference.webp",
            visualCaption = "Normal deployment distance is based on the Unit’s SPEED characteristic, measured inward from its Entry Edge.",
            visualAspectRatio = 1.474f
        ),
        AskTopic(
            id = "charge_distance",
            title = "CHARGE DISTANCE",
            understoodAs = "How far can a Unit Charge?",
            aliases = listOf("charge distance", "how far charge", "how far can unit charge", "charge range", "speed plus d6", "charge roll distance", "maximum charge"),
            requiredGroups = listOf(setOf("charge"), setOf("far", "distance", "range", "d6", "speed")),
            summary = "Charge distance is not just the SPEED value. Roll 1D6 and add the Unit’s SPEED; the total is the Charge Roll Distance.",
            bullets = listOf(
                "The Leading Model must be legally placeable Wholly Within that distance and Within 1 inch of every declared Charge target.",
                "If the Charge fails, the Unit does not move and its activation ends."
            ),
            source = "Core Rules §8.7.7"
        ),
        AskTopic(
            id = "close_ranks_distance",
            title = "CLOSE RANKS DISTANCE",
            understoodAs = "How far can a Unit move during Close Ranks?",
            aliases = listOf("close ranks distance", "how far close ranks", "close ranks 3 inches", "close ranks move", "how many inches close ranks"),
            requiredGroups = listOf(setOf("close", "rank"), setOf("far", "distance", "inch", "move")),
            summary = "Close Ranks is an optional move of up to 3 inches before a Close Combat Attack.",
            bullets = listOf(
                "The Leading Model must end closer to the Enemy Unit(s) it was already Engaged with.",
                "It cannot use Close Ranks to become Engaged with a new Enemy Unit that was not already Engaged at the start of the Phase."
            ),
            source = "Core Rules §8.8.1"
        ),
        AskTopic(
            id = "coherency_distance",
            title = "COHERENCY DISTANCE",
            understoodAs = "How close do models need to be for Unit Coherency?",
            aliases = listOf("coherency distance", "how close models coherency", "3 inch coherency", "how far apart models", "unit coherency distance", "within 3 leading model"),
            requiredGroups = listOf(setOf("coherency", "model"), setOf("distance", "close", "3", "apart")),
            summary = "When a Unit is set In Coherency, every model must be Wholly Within 3 inches of the Leading Model and have a valid Coherency Link to it, directly or through models from the same Unit.",
            bullets = listOf(
                "Coherency Links cannot pass through restricted terrain/models except where the rules specifically allow it.",
                "Out of Coherency Units cannot Control or Contest Mission Markers."
            ),
            source = "Core Rules §4.4"
        ),
        AskTopic(
            id = "engagement_range_distance",
            title = "ENGAGEMENT RANGE · 1 INCH",
            understoodAs = "How close does a Ground Unit need to be to count as Engaged?",
            aliases = listOf("engagement range distance", "how close engaged", "1 inch engagement", "when are units engaged", "engaged distance", "melee engagement range"),
            requiredGroups = listOf(setOf("engage", "engagement"), setOf("distance", "close", "inch", "1")),
            summary = "Ground Units are Engaged when they are within Engagement Range: 1 inch.",
            bullets = listOf(
                "Flying Units are never Engaged and do not participate in the Combat Phase as Engaged Units.",
                "Charge and Close Combat positioning repeatedly use this 1 inch Engagement Range."
            ),
            source = "Core Rules §7.2 · §8.7.7"
        ),
        AskTopic(
            id = "objective_control_distance",
            title = "MISSION MARKER RANGE · 3 INCHES",
            understoodAs = "How close must a Unit be to a Mission Marker to contest it?",
            aliases = listOf("objective range", "mission marker range", "how close objective", "how close mission marker", "3 inches objective", "contest distance", "control distance marker"),
            requiredGroups = listOf(setOf("objective", "marker", "contest", "control"), setOf("distance", "close", "inch", "3")),
            summary = "To be eligible to contest a Mission Marker under the normal control procedure, a Unit needs at least one eligible model Within 3 inches of that Marker.",
            bullets = listOf(
                "Players then sum the Current Supply Value of their eligible contesting Units; higher total controls and a tie is Contested.",
                "Flying Units cannot normally Contest or Control Mission Markers."
            ),
            source = "Core Rules §8.9.1 · Part 6"
        ),
        AskTopic(
            id = "zone_of_influence_distance",
            title = "ZONE OF INFLUENCE · 6 INCHES",
            understoodAs = "How large is a Zone of Influence?",
            aliases = listOf("zone of influence distance", "how big zone of influence", "6 inch zone of influence", "enemy deployment zone 6", "zoi distance"),
            requiredGroups = listOf(setOf("zone", "influence", "zoi"), setOf("distance", "6", "inch", "big")),
            summary = "A normal Zone of Influence extends 6 inches inward from each player’s Entry Edge.",
            bullets = listOf(
                "Enemy Units arriving from Reserves cannot normally end their deployment inside that area.",
                "The Zone of Influence does not affect Units already on the battlefield."
            ),
            source = "Core Rules §5.6 · Part 11",
            visualAsset = "images/rules/deployment_card_reference.webp",
            visualCaption = "The Deployment Card marks the 6-inch Zone of Influence associated with each Entry Edge.",
            visualAspectRatio = 1.375f
        ),
        AskTopic(
            id = "gap_clearance",
            title = "GAP CLEARANCE",
            understoodAs = "How wide does a gap need to be for a model to move through it?",
            aliases = listOf("gap clearance", "how wide gap", "move through gap", "1 inch gap", "3 inch gap", "doorway width", "can model fit through gap",
                "how do gap clearance rules work", "gap clearance rules"),
            requiredGroups = listOf(setOf("gap", "doorway", "clearance", "width")),
            summary = "Gap Clearance depends on Unit SIZE: Size 2 or lower can pass through gaps at least 1 inch wide; Size 3 or larger requires at least a 3 inch gap.",
            bullets = listOf(
                "The model’s base still has to physically fit at its final position.",
                "Flying Units ignore Gap Clearance while moving, but must still end in a legal position."
            ),
            source = "Core Rules §4.6",
            visualAsset = "images/rules/unit_card_size_reference.webp",
            visualCaption = "Use the Unit’s SIZE characteristic to determine whether it can pass through a narrow gap.",
            visualAspectRatio = 1.474f
        )
    )

    private val candidates: List<Candidate> by lazy {
        buildList {
            askTopics.forEach { topic ->
                add(Candidate(
                    key = "topic:${topic.id}",
                    kind = CandidateKind.TOPIC,
                    label = topic.title,
                    subtitle = topic.understoodAs,
                    searchTexts = listOf(topic.title, topic.understoodAs) + topic.aliases
                ))
            }
            glossaryEntries.forEach { entry ->
                val display = displayKeyword(entry.term)
                add(Candidate(
                    key = "keyword:${keywordKey(entry.term)}",
                    kind = CandidateKind.GLOSSARY,
                    label = display,
                    subtitle = "Official keyword / definition",
                    searchTexts = listOf(entry.term, display, "core $display", "$display core rule") + entry.aliases
                ))
            }
            faqEntries.forEach { faq ->
                add(Candidate(
                    key = "faq:${faq.index}",
                    kind = CandidateKind.FAQ,
                    label = faq.question,
                    subtitle = "Official FAQ ruling",
                    searchTexts = listOf(faq.question)
                ))
            }
            abilityEntries.forEach { ability ->
                val aliases = abilityAliases(ability)
                add(Candidate(
                    key = "ability:${ability.faction}:${normalizeAbilityKey(ability.name)}",
                    kind = CandidateKind.ABILITY,
                    label = ability.name,
                    subtitle = "${ability.faction} · printed ${ability.variants.firstOrNull()?.type ?: "ABILITY"}",
                    searchTexts = listOf(ability.name, "${ability.faction} ${ability.name}", "${ability.name} ${ability.faction} ability") + aliases,
                    faction = ability.faction
                ))
            }
        }
    }

    private val knownQueryVocabulary: Set<String> by lazy {
        candidates.asSequence()
            .flatMap { candidate -> candidate.searchTexts.asSequence() }
            .flatMap { text -> semanticTerms(semanticNormalize(text)).asSequence() }
            .toSet()
    }

    fun sourceNames(): List<String> = docs.map { it.name }

    fun browseCoreItems(): List<RuleBrowseItem> = askTopics
        .sortedBy { it.title.lowercase() }
        .map { RuleBrowseItem("topic:${it.id}", it.title, it.understoodAs, "CORE") }

    fun browseKeywordItems(): List<RuleBrowseItem> = glossaryEntries.map {
        RuleBrowseItem("keyword:${keywordKey(it.term)}", displayKeyword(it.term), firstSentence(it.text), "KEYWORD")
    }

    fun browseFaqItems(): List<RuleBrowseItem> = faqEntries.map {
        RuleBrowseItem("faq:${it.index}", it.question, firstSentence(it.answer), "FAQ")
    }

    fun browseAbilityItems(faction: String): List<RuleBrowseItem> = abilityEntries
        .filter { it.faction.equals(faction, ignoreCase = true) }
        .map {
            val variants = if (it.variants.size > 1) " · ${it.variants.size} printed variants" else ""
            RuleBrowseItem(
                "ability:${it.faction}:${normalizeAbilityKey(it.name)}",
                it.name,
                "${it.variants.firstOrNull()?.type ?: "ABILITY"}$variants",
                it.faction
            )
        }

    fun manualPartItems(): List<RuleBrowseItem> = QuickRules.manualParts.map { part ->
        RuleBrowseItem("manual:${part.number}", "PART ${part.number} · ${part.title}", part.summary, "p. ${part.page}")
    }

    fun suggest(query: String, limit: Int = 5): List<RuleBrowseItem> {
        if (semanticTerms(semanticNormalize(query)).isEmpty()) return emptyList()
        return rankCandidates(query, limit * 3)
            .filter { it.score >= 95 && it.coverage >= 0.45 }
            .take(limit)
            .map { scored ->
                val c = scored.candidate
                RuleBrowseItem(c.key, c.label, c.subtitle, kindLabel(c.kind))
            }
    }

    fun resolve(key: String): RuleAnswer {
        if (key.startsWith("topic:")) {
            val id = key.removePrefix("topic:")
            val topic = askTopics.firstOrNull { it.id == id }
            if (topic != null) return topicAnswer(topic)
        }
        if (key.startsWith("keyword:")) {
            val id = key.removePrefix("keyword:")
            val entry = glossaryEntries.firstOrNull { keywordKey(it.term) == id }
            if (entry != null) return glossaryAnswer(entry)
        }
        if (key.startsWith("faq:")) {
            val index = key.removePrefix("faq:").toIntOrNull()
            val faq = faqEntries.firstOrNull { it.index == index }
            if (faq != null) return faqAnswer(faq)
        }
        if (key.startsWith("ability:")) {
            val split = key.split(':', limit = 3)
            if (split.size == 3) {
                val faction = split[1]
                val nameKey = split[2]
                val ability = abilityEntries.firstOrNull {
                    it.faction == faction && normalizeAbilityKey(it.name) == nameKey
                }
                if (ability != null) return abilityAnswer(ability)
            }
        }
        if (key.startsWith("manual:")) {
            val number = key.removePrefix("manual:").toIntOrNull()
            val part = QuickRules.manualParts.firstOrNull { it.number == number }
            if (part != null) {
                return RuleAnswer(
                    title = "PART ${part.number} · ${part.title}",
                    summary = part.summary,
                    bullets = part.sections.map { "${it.title} — p. ${it.page}" },
                    source = "Core Rules · p. ${part.page}",
                    confidence = "OFFICIAL INDEX",
                    interpretedAs = "Browse the Core Rules by section"
                )
            }
        }
        return RuleAnswer(
            title = "NO VERIFIED ENTRY",
            summary = "That Field Manual entry could not be resolved.",
            source = "Structured local rules database",
            confidence = "NO MATCH"
        )
    }

    fun ask(query: String): RuleAnswer {
        val raw = query.trim()
        val normalized = semanticNormalize(raw)
        val qTerms = semanticTerms(normalized)

        // The replacement-weapon icon is meaningful but is intentionally stripped by
        // normal text normalization. Catch the printed ↑FOR notation before that step.
        if (raw.contains("↑") && raw.lowercase().contains("for")) {
            askTopics.firstOrNull { it.id == "weapon_replacement" }?.let { topic ->
                return topicAnswer(topic).copy(interpretedAs = "Printed ↑FOR weapon replacement notation")
            }
        }

        val directDefinitionProbe = normalized
            .replace(Regex("^(please\\s+)?explain\\s+"), "")
            .replace(Regex("^define\\s+"), "")
            .replace(Regex("^what\\s+(is|are)\\s+"), "")
            .replace(Regex("^what\\s+does\\s+"), "")
            .replace(Regex("\\s+(mean|means|do)$"), "")
            .trim()
        if (directDefinitionProbe == "rng" || directDefinitionProbe == "weapon range") {
            askTopics.firstOrNull { it.id == "weapon_range" }?.let { topic ->
                return topicAnswer(topic).copy(interpretedAs = "Weapon range (RNG)")
            }
        }

        // Players rarely remember the printed abbreviation "RoA" when they are learning.
        // Route plain-language "how many combat / attack dice?" wording directly to Rate of
        // Attack instead of letting generic Ranged Attack topics win on the words unit/combat.
        val directIntentTerms = qTerms.toSet()
        val asksForDiceQuantity = "dice" in directIntentTerms && "number" in directIntentTerms
        val attackDiceContext = directIntentTerms.any { it in setOf("attack", "combat", "weapon", "unit", "fire") }
        val surgeSpecific = "surge" in directIntentTerms || "s dice" in normalized
        if ((directDefinitionProbe == "roa" || directDefinitionProbe == "rate of attack" ||
                (asksForDiceQuantity && attackDiceContext && !surgeSpecific))) {
            askTopics.firstOrNull { it.id == "rate_of_attack" }?.let { topic ->
                return topicAnswer(topic).copy(interpretedAs = "Attack dice quantity (RoA / Rate of Attack)")
            }
        }

        val glossaryProbe = normalized
            .replace(Regex("^(please\\s+)?explain\\s+"), "")
            .replace(Regex("^define\\s+"), "")
            .replace(Regex("^what\\s+(is|are)\\s+"), "")
            .replace(Regex("^what\\s+does\\s+"), "")
            .replace(Regex("\\s+(mean|means|do)$"), "")
            .trim()
        if (glossaryProbe.isNotBlank() && semanticTerms(glossaryProbe).size <= 4) {
            glossaryEntries.firstOrNull { keywordKey(it.term) == keywordKey(glossaryProbe) }?.let { entry ->
                return glossaryAnswer(entry).copy(interpretedAs = "Official definition of ${displayKeyword(entry.term)}")
            }
        }

        if (qTerms.isEmpty()) {
            return RuleAnswer(
                title = "FIELD MANUAL",
                summary = "Ask naturally about a rule, keyword, FAQ interaction, army-building question, or printed Unit/Card ability.",
                source = "Structured local rules database",
                confidence = "READY"
            )
        }

        val ranked = rankCandidates(raw, 8)
        val best = ranked.firstOrNull()
        if (best == null || best.score < 118 || best.coverage < 0.52) {
            val near = ranked.filter { it.score >= 80 && it.coverage >= 0.38 }.take(4)
            return RuleAnswer(
                title = "NO VERIFIED MATCH",
                summary = "I couldn't confidently identify which rule you mean, so I won't guess from raw book text.",
                bullets = listOf("Try a distinctive rule name, Unit ability, interaction, or use SOURCE for literal text search."),
                source = "Structured local rules database",
                confidence = "NO GUESS",
                suggestions = near.map { candidateSuggestionLabel(it.candidate) }
            )
        }

        val second = ranked.getOrNull(1)
        if (second != null && shouldClarify(normalized, best, second)) {
            val options = ranked
                .filter { it.score >= best.score - 24 && it.coverage >= 0.58 }
                .take(4)
            return RuleAnswer(
                title = "WHICH RULE DO YOU MEAN?",
                summary = "Your wording fits more than one verified rule. Pick the intended one rather than letting the app invent a tie-breaker.",
                source = "Structured local rules database",
                confidence = "CLARIFY",
                interpretedAs = raw,
                suggestions = options.map { candidateSuggestionLabel(it.candidate) }
            )
        }

        val answer = resolve(best.candidate.key)
        return answer.copy(interpretedAs = best.candidate.subtitle)
    }

    /** Strict literal source lookup. This is intentionally separate from ASK. */
    fun search(query: String, limit: Int = 8): List<RuleHit> {
        val normalizedQuery = normalize(query)
        val terms = meaningfulTerms(normalizedQuery)
        if (terms.isEmpty()) return emptyList()

        val hits = mutableListOf<RuleHit>()
        for (doc in docs) {
            for (chunk in doc.chunks) {
                val n = chunk.normalized
                val exactWordMatches = terms.count { term -> Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(n) }
                val containsMatches = terms.count { term -> n.contains(term) }
                val phraseMatch = normalizedQuery.length >= 4 && n.contains(normalizedQuery)
                val allTerms = terms.all { term -> n.contains(term) }

                if (terms.size > 1 && !allTerms && !phraseMatch) continue
                if (terms.size == 1 && exactWordMatches == 0 && containsMatches == 0) continue

                var score = exactWordMatches * 12 + containsMatches * 3
                if (allTerms) score += 30
                if (phraseMatch) score += 65
                if (looksLikeHeadingMatch(chunk.raw, terms)) score += 24
                score += when (doc.name) { "Core Rules" -> 5; "FAQ" -> 4; else -> 2 }

                if (score > 0) hits += RuleHit(doc.name, focusSnippet(chunk.raw, terms, normalizedQuery, 620), score)
            }
        }

        return hits.sortedByDescending { it.score }
            .distinctBy { it.source + "\u0000" + it.snippet }
            .take(limit)
    }

    private fun rankCandidates(query: String, limit: Int): List<ScoredCandidate> {
        val normalized = semanticNormalize(query)
        val qTerms = semanticTerms(normalized)
        if (qTerms.isEmpty()) return emptyList()
        val qSet = qTerms.toSet()
        val unknownTerms = qTerms.filter { q ->
            knownQueryVocabulary.none { known -> tokenSimilarity(q, known) >= 0.84 }
        }
        val relational = Regex("\\b(against|through|during|before|after|while|with|versus|vs|stack|outside|inside|interact)\\b").containsMatchIn(normalized)
        val whatIs = normalized.startsWith("what is ") || normalized.startsWith("define ")
        val whatDoes = normalized.startsWith("what does ") || normalized.startsWith("what do ")
        val factionWord = when {
            "terran" in qSet -> "TERRAN"
            "protoss" in qSet -> "PROTOSS"
            "zerg" in qSet -> "ZERG"
            else -> null
        }

        return candidates.mapNotNull { c ->
            var bestScore = 0
            var bestCoverage = 0.0
            c.searchTexts.forEach { text ->
                val result = scoreText(normalized, qTerms, text)
                if (result.first > bestScore) {
                    bestScore = result.first
                    bestCoverage = result.second
                }
            }
            if (bestScore <= 0) return@mapNotNull null

            when (c.kind) {
                CandidateKind.TOPIC -> bestScore += 20
                CandidateKind.GLOSSARY -> if (whatIs) bestScore += 24
                CandidateKind.FAQ -> if (relational) bestScore += 34 else bestScore -= 4
                CandidateKind.ABILITY -> {
                    if (whatDoes) bestScore += 20
                    if (factionWord != null && c.faction == factionWord) bestScore += 48
                    if (factionWord != null && c.faction != factionWord) bestScore -= 30
                }
            }

            // Unknown-domain words are strong evidence that the user may be asking about
            // something else. This prevents a query such as "banana players faction" from
            // becoming a confident SAME FACTION answer just because two words happen to match.
            if (unknownTerms.isNotEmpty()) {
                bestScore -= unknownTerms.size * 125
                if (qTerms.size <= 4 && unknownTerms.size * 3 >= qTerms.size) bestScore -= 35
            }

            // Required groups are a high-precision bonus for curated topics, not a hard gate.
            if (c.kind == CandidateKind.TOPIC) {
                val topic = askTopics.firstOrNull { "topic:${it.id}" == c.key }
                if (topic != null && topic.requiredGroups.isNotEmpty()) {
                    val satisfied = topic.requiredGroups.count { group ->
                        group.any { wanted -> qTerms.any { term -> tokenSimilarity(stem(wanted), term) >= 0.84 } }
                    }
                    bestScore += satisfied * 18
                    if (satisfied == topic.requiredGroups.size) bestScore += 34
                }
            }

            ScoredCandidate(c, bestScore, bestCoverage)
        }.sortedWith(compareByDescending<ScoredCandidate> { it.score }.thenByDescending { it.coverage })
            .take(limit)
    }

    private fun scoreText(queryNorm: String, queryTerms: List<String>, target: String): Pair<Int, Double> {
        val targetNorm = semanticNormalize(target)
        val targetTerms = semanticTerms(targetNorm)
        if (targetTerms.isEmpty()) return 0 to 0.0

        var exactMatches = 0
        var fuzzySum = 0.0
        queryTerms.forEach { q ->
            val sim = targetTerms.maxOfOrNull { t -> tokenSimilarity(q, t) } ?: 0.0
            fuzzySum += sim
            if (sim >= 0.995) exactMatches++
        }
        val coverage = fuzzySum / queryTerms.size.coerceAtLeast(1)
        if (coverage < 0.28) return 0 to coverage

        var score = (coverage * 165.0).roundToInt() + exactMatches * 13
        val targetCoverage = targetTerms.count { t -> queryTerms.any { q -> tokenSimilarity(q, t) >= 0.84 } }.toDouble() / targetTerms.size.coerceAtLeast(1)
        score += (targetCoverage * 35.0).roundToInt()
        score += (trigramSimilarity(queryNorm, targetNorm) * 70.0).roundToInt()

        if (queryNorm == targetNorm) score += 260
        else {
            if (queryNorm.length >= 4 && targetNorm.contains(queryNorm)) score += 110
            if (targetNorm.length >= 4 && queryNorm.contains(targetNorm)) score += 90
        }
        if (queryTerms.size >= 2 && queryTerms.all { q -> targetTerms.any { t -> tokenSimilarity(q, t) >= 0.84 } }) score += 65

        return score to coverage
    }

    private fun shouldClarify(normalizedQuery: String, first: ScoredCandidate, second: ScoredCandidate): Boolean {
        if (first.score - second.score > 24) return false
        if (second.score < 145 || second.coverage < 0.58) return false
        if (first.candidate.key == second.candidate.key) return false

        // Exact curated natural-language questions should simply answer; ambiguity is mainly for
        // short names shared by a core rule and card ability, or by multiple factions.
        if (first.candidate.kind == CandidateKind.TOPIC && first.score >= 250) return false
        val explicitFaction = when {
            Regex("\\bterran\\b").containsMatchIn(normalizedQuery) -> "TERRAN"
            Regex("\\bprotoss\\b").containsMatchIn(normalizedQuery) -> "PROTOSS"
            Regex("\\bzerg\\b").containsMatchIn(normalizedQuery) -> "ZERG"
            else -> null
        }
        if (explicitFaction != null && first.candidate.kind == CandidateKind.ABILITY && first.candidate.faction == explicitFaction) return false
        val shortQuestion = semanticTerms(normalizedQuery).size <= 3
        val sameLabel = normalize(first.candidate.label) == normalize(second.candidate.label)
        val bothAbilities = first.candidate.kind == CandidateKind.ABILITY && second.candidate.kind == CandidateKind.ABILITY
        return sameLabel || bothAbilities || shortQuestion
    }

    private fun topicAnswer(topic: AskTopic): RuleAnswer = RuleAnswer(
        title = topic.title,
        summary = topic.summary,
        bullets = topic.bullets,
        source = topic.source,
        confidence = "VERIFIED",
        interpretedAs = topic.understoodAs,
        visualAsset = topic.visualAsset,
        visualCaption = topic.visualCaption,
        visualAspectRatio = topic.visualAspectRatio
    )

    private fun glossaryAnswer(entry: GlossaryEntry): RuleAnswer {
        if (entry.details.isNotEmpty()) {
            return RuleAnswer(
                title = displayKeyword(entry.term),
                summary = entry.text,
                bullets = entry.details,
                source = entry.source,
                confidence = "VERIFIED KEYWORD",
                interpretedAs = "What does ${displayKeyword(entry.term)} mean?"
            )
        }
        val sentences = splitSentences(entry.text)
        val summary = sentences.firstOrNull() ?: entry.text
        val details = sentences.drop(1)
        return RuleAnswer(
            title = displayKeyword(entry.term),
            summary = summary,
            bullets = details,
            source = entry.source,
            excerpt = if (entry.text.length > 500) entry.text else null,
            confidence = "OFFICIAL GLOSSARY",
            interpretedAs = "What does ${displayKeyword(entry.term)} mean?"
        )
    }

    private fun faqAnswer(faq: FaqEntry): RuleAnswer = RuleAnswer(
        title = "FAQ RULING",
        summary = faq.answer,
        source = "Official FAQ",
        confidence = "VERIFIED FAQ",
        interpretedAs = faq.question
    )

    private fun abilityAnswer(ability: AbilityEntry): RuleAnswer {
        val distinct = ability.variants.distinctBy { normalize(it.text) }
        val title = if (ability.faction.isBlank()) ability.name else "${ability.name} · ${ability.faction}"
        if (distinct.size <= 1) {
            val v = distinct.firstOrNull()
            return RuleAnswer(
                title = title,
                summary = v?.text ?: "No printed text was extracted for this ability.",
                source = v?.source ?: "${ability.faction} Cards",
                confidence = "VERIFIED CARD",
                interpretedAs = "What does ${ability.name} do?"
            )
        }
        return RuleAnswer(
            title = title,
            summary = "This ability name has ${distinct.size} different printed variants in the ${ability.faction.lowercase().replaceFirstChar { it.uppercase() }} card set. The exact effect depends on the card/unit using it.",
            bullets = distinct.mapIndexed { index, v -> "Variant ${index + 1}: ${v.text}" },
            source = distinct.joinToString(" · ") { it.source }.split(" · ").distinct().joinToString(" · "),
            confidence = "VERIFIED CARD VARIANTS",
            interpretedAs = "What does ${ability.name} do?"
        )
    }

    private fun abilityAliases(ability: AbilityEntry): List<String> {
        val name = normalize(ability.name)
        return buildList {
            if (name == "stimpack") addAll(listOf("stimpak", "stim", "marine stim", "marauder stim"))
            if (name == "blink") addAll(listOf("stalker blink", "blink stalker"))
            if (name == "charge" && ability.faction == "PROTOSS") addAll(listOf("zealot charge", "protoss charge", "charge zealot"))
            if (name == "hydriodic bile") addAll(listOf("corrosive bile", "bile attack", "ravager bile", "ravager corrosive bile"))
            if (name == "force field") addAll(listOf("sentry force field", "forcefield"))
            if (name == "guardian shield") addAll(listOf("sentry guardian shield"))
        }
    }

    private fun candidateSuggestionLabel(candidate: Candidate): String = when (candidate.kind) {
        CandidateKind.TOPIC -> candidate.label
        CandidateKind.GLOSSARY -> "${candidate.label} — CORE RULE"
        CandidateKind.FAQ -> "${candidate.label} — FAQ"
        CandidateKind.ABILITY -> "${candidate.label} — ${candidate.faction ?: "CARD"} ABILITY"
    }

    private fun kindLabel(kind: CandidateKind): String = when (kind) {
        CandidateKind.TOPIC -> "CORE"
        CandidateKind.GLOSSARY -> "KEYWORD"
        CandidateKind.FAQ -> "FAQ"
        CandidateKind.ABILITY -> "ABILITY"
    }

    private fun displayKeyword(term: String): String = term.trim()

    private fun keywordKey(term: String): String = semanticNormalize(term)
        .replace(Regex("\\s*\\([^)]*\\)"), "")
        .replace(Regex("\\s*\\[[^]]*]"), "")
        .replace(Regex("\\s+x$"), "")
        .trim()

    private fun normalizeAbilityKey(name: String): String = normalize(name).replace(' ', '_')

    private fun firstSentence(text: String): String = splitSentences(text).firstOrNull()?.let { shorten(it, 150) } ?: shorten(text, 150)

    private fun splitSentences(text: String): List<String> = text
        .replace(Regex("\\s+"), " ")
        .trim()
        .split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9•])"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private fun tokenSimilarity(aRaw: String, bRaw: String): Double {
        if (isAdjacentTransposition(aRaw, bRaw)) return 0.94
        // Candidate terms are often stemmed before they reach this scorer. Preserve
        // adjacent-letter typo tolerance across a stripped plural as well (tests -> tetss).
        if (aRaw.endsWith("s") && isAdjacentTransposition(aRaw.dropLast(1), bRaw)) return 0.94
        if (bRaw.endsWith("s") && isAdjacentTransposition(aRaw, bRaw.dropLast(1))) return 0.94
        val a = stem(aRaw)
        val b = stem(bRaw)
        if (a == b) return 1.0
        if (isAdjacentTransposition(a, b)) return 0.94
        if (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) return 0.90
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        val distance = levenshtein(a, b)
        val ratio = 1.0 - distance.toDouble() / maxLen
        return when {
            distance == 1 && maxLen >= 4 -> max(0.88, ratio)
            distance == 2 && maxLen >= 6 -> max(0.74, ratio)
            ratio >= 0.78 -> ratio
            else -> 0.0
        }
    }

    private fun isAdjacentTransposition(a: String, b: String): Boolean {
        if (a.length != b.length || a.length < 2) return false
        val diffs = a.indices.filter { a[it] != b[it] }
        if (diffs.size != 2) return false
        val i = diffs[0]
        val j = diffs[1]
        return j == i + 1 && a[i] == b[j] && a[j] == b[i]
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            val temp = previous
            previous = current
            current = temp
        }
        return previous[b.length]
    }

    private fun trigramSimilarity(aRaw: String, bRaw: String): Double {
        fun grams(s: String): Set<String> {
            val x = "  ${s.replace(" ", "_")}  "
            if (x.length < 3) return setOf(x)
            return (0..x.length - 3).map { x.substring(it, it + 3) }.toSet()
        }
        val a = grams(aRaw)
        val b = grams(bRaw)
        val union = a union b
        if (union.isEmpty()) return 0.0
        return (a intersect b).size.toDouble() / union.size
    }

    private fun meaningfulTerms(normalizedQuery: String): List<String> {
        val stop = setOf(
            "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "is", "are", "be", "can", "does", "do", "did",
            "how", "what", "when", "where", "why", "with", "my", "i", "you", "your", "we", "our", "they", "their", "it", "this", "that",
            "tell", "me", "about", "mean", "means", "please", "explain", "explanation", "would", "could", "should"
        )
        return normalizedQuery.split(" ").map { stem(it.trim()) }.filter { (it.length >= 2 || it == "e" || it == "-" || it.any { ch -> ch.isDigit() }) && it !in stop }.distinct()
    }

    private fun semanticTerms(normalizedQuery: String): List<String> = meaningfulTerms(normalizedQuery)

    private fun stem(token: String): String {
        var t = token.lowercase().trim()
        val direct = mapOf(
            "people" to "player", "players" to "player", "factions" to "faction", "races" to "race",
            "rounds" to "round", "games" to "game", "matches" to "match", "abilities" to "ability",
            "reserves" to "reserve", "markers" to "marker", "objectives" to "objective", "lists" to "list",
            "models" to "model", "units" to "unit", "reactions" to "reaction", "attacks" to "attack",
            "weapons" to "weapon", "shooting" to "shoot", "shot" to "shoot", "flyers" to "flying",
            "controlled" to "control", "controlling" to "control", "contested" to "contest", "contesting" to "contest",
            "amount" to "number", "amounts" to "number", "count" to "number", "counts" to "number",
            "quantity" to "number", "quantities" to "number", "many" to "number"
        )
        direct[t]?.let { return it }
        if (t.endsWith("ies") && t.length > 5) t = t.dropLast(3) + "y"
        else if (t.endsWith("ing") && t.length > 6) t = t.dropLast(3)
        else if (t.endsWith("ed") && t.length > 5) t = t.dropLast(2)
        else if (t.endsWith("s") && !t.endsWith("ss") && t.length > 4) t = t.dropLast(1)
        return t
    }

    private fun looksLikeHeadingMatch(chunk: String, terms: List<String>): Boolean {
        val first = clean(chunk).take(150).lowercase()
        return terms.any { term -> first.contains(term) } && (chunk.contains(Regex("\\b\\d+(?:\\.\\d+)+\\b")) || chunk.contains(":"))
    }

    private fun focusSnippet(raw: String, terms: List<String>, phrase: String, maxChars: Int): String {
        val cleanText = clean(raw)
        if (cleanText.length <= maxChars) return cleanText
        val sentences = cleanText.split(Regex("(?<=[.!?])\\s+"))
        if (sentences.size > 1) {
            val ranked = sentences.mapIndexed { index, sentence ->
                val n = normalize(sentence)
                var score = terms.count { n.contains(it) } * 8
                if (phrase.length >= 4 && n.contains(phrase)) score += 20
                index to score
            }.sortedByDescending { it.second }
            val bestIndex = ranked.firstOrNull { it.second > 0 }?.first
            if (bestIndex != null) {
                val from = (bestIndex - 1).coerceAtLeast(0)
                val to = (bestIndex + 2).coerceAtMost(sentences.lastIndex)
                return shorten(sentences.subList(from, to + 1).joinToString(" "), maxChars)
            }
        }
        val n = normalize(cleanText)
        val positions = terms.mapNotNull { term -> n.indexOf(term).takeIf { it >= 0 } }
        val hit = positions.minOrNull() ?: 0
        val start = (hit - maxChars / 3).coerceAtLeast(0)
        val end = (start + maxChars).coerceAtMost(cleanText.length)
        return (if (start > 0) "…" else "") + cleanText.substring(start, end).trim() + (if (end < cleanText.length) "…" else "")
    }

    private fun source(name: String, assetPath: String): SourceDoc {
        val raw = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        val chunks = raw.split(Regex("""\n\s*\n"""))
            .map { clean(it) }
            .filter { it.length >= 40 }
            .take(4000)
            .map { SourceChunk(it, normalize(it)) }
        return SourceDoc(name, chunks, raw)
    }

    private fun clean(text: String): String = text.replace('\u000c', ' ').replace(Regex("""\s+"""), " ").trim()

    private fun normalize(text: String): String = text.lowercase()
        .replace("↑", " up arrow ")
        .replace("’", "'")
        .replace("–", "-")
        .replace("—", "-")
        .replace(Regex("[^a-z0-9+ -]"), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun semanticNormalize(text: String): String = normalize(text)
        .replace(Regex("\\bplaytime\\b"), "play time")
        .replace(Regex("\\bhowlong\\b"), "how long")
        .replace(Regex("\\bversus\\b"), "vs")
        .replace(Regex("\\bcome back\\b"), "return")
        .replace(Regex("\\bcomes back\\b"), "return")
        .replace(Regex("\\bstimpak\\b"), "stimpack")
        .replace(Regex("\\bforcefield\\b"), "force field")

    private fun shorten(text: String, maxChars: Int = 520): String =
        if (text.length <= maxChars) text else text.take(min(maxChars, text.length)).trimEnd() + "…"
}
