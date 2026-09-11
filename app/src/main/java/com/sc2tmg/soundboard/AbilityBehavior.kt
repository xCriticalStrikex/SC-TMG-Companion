package com.sc2tmg.soundboard

/**
 * Tabletop timing/legality contract for ability buttons that have bundled audio.
 *
 * VERIFIED_TMG entries are grounded in the supplied TMG card sheets/rules.
 * PROJECT_DEFINED entries preserve an explicitly approved companion-app sequence where
 * the current supplied card sheets do not contain the corresponding unit card.
 * AUDIO_ONLY entries are deliberately not given invented tabletop timing/effects.
 */
enum class AbilityEvidence { VERIFIED_TMG, PROJECT_DEFINED, AUDIO_ONLY }
enum class AbilityPhase(val index: Int, val label: String) {
    MOVEMENT(0, "Movement Phase"),
    ASSAULT(1, "Assault Phase"),
    COMBAT(2, "Combat Phase"),
    SCORING(3, "Scoring & Cleanup Phase")
}

data class AbilityBehavior(
    val audioId: String,
    val abilityId: String,
    val evidence: AbilityEvidence,
    val allowedPhases: Set<AbilityPhase> = emptySet(),
    val trackedKinds: List<PendingEffectKind> = emptyList(),
    val reminder: String = "",
    val specialWorkflow: Boolean = false,
    val aggregateAtCleanup: Boolean = false
) {
    fun allowsPhase(phaseIndex: Int): Boolean = allowedPhases.isEmpty() || allowedPhases.any { it.index == phaseIndex }
    val phaseLabel: String
        get() = when {
            allowedPhases.isEmpty() -> "Timing not verified in current TMG sources"
            allowedPhases.size == 1 -> allowedPhases.first().label
            else -> allowedPhases.joinToString(" / ") { it.label }
        }
}

object AbilityBehaviorRegistry {
    private val entries = listOf(
        AbilityBehavior(
            "adept", "psionic_transfer", AbilityEvidence.VERIFIED_TMG,
            setOf(AbilityPhase.MOVEMENT), listOf(PendingEffectKind.ADEPT_SHADE),
            "Set the Shade now. At End of Round choose TRANSFER or NO TRANSFER. Teleport audio belongs only to TRANSFER."
        ),
        AbilityBehavior(
            "stalker", "blink", AbilityEvidence.VERIFIED_TMG,
            setOf(AbilityPhase.MOVEMENT), reminder = "Resolve PLACE (6); this is an immediate movement ability."
        ),
        AbilityBehavior(
            "zealot", "charge", AbilityEvidence.VERIFIED_TMG,
            setOf(AbilityPhase.ASSAULT), reminder = "Charge-distance ability; use during the Assault Phase."
        ),
        AbilityBehavior(
            "marine", "stimpack", AbilityEvidence.VERIFIED_TMG,
            setOf(AbilityPhase.MOVEMENT), listOf(PendingEffectKind.STIMPACK),
            "NON-LETHAL DAMAGE (2); BUFF Speed (3); ranged and close-combat weapons gain PRECISION (3) until cleanup.",
            aggregateAtCleanup = true
        ),
        AbilityBehavior(
            "marauder", "stimpack", AbilityEvidence.VERIFIED_TMG,
            setOf(AbilityPhase.MOVEMENT), listOf(PendingEffectKind.STIMPACK),
            "NON-LETHAL DAMAGE (2); BUFF Speed (3); Quad K12 and close-combat weapons gain PRECISION (2) until cleanup.",
            aggregateAtCleanup = true
        ),
        AbilityBehavior(
            "sentry", "force_field", AbilityEvidence.VERIFIED_TMG,
            setOf(AbilityPhase.MOVEMENT), listOf(PendingEffectKind.FORCE_FIELD),
            "Each placed Force Field is an independent terrain token. Remove one only on a real removal event or at round cleanup.",
            specialWorkflow = true
        ),
        AbilityBehavior(
            "sentry", "guardian_shield", AbilityEvidence.VERIFIED_TMG,
            setOf(AbilityPhase.ASSAULT), listOf(PendingEffectKind.GUARDIAN_SHIELD),
            "All Ranged Attacks targeting a Friendly Unit Within 4\" are made with 1 fewer die in the Attack Pool. Expires during round cleanup."
        ),
        AbilityBehavior(
            "ravager", "corrosive_bile", AbilityEvidence.PROJECT_DEFINED,
            setOf(AbilityPhase.MOVEMENT), listOf(PendingEffectKind.CORROSIVE_BILE),
            "Approved companion sequence: launch now; each Bile resolves independently at End of Assault as Incoming → Explosion.",
            specialWorkflow = true
        ),
        AbilityBehavior(
            "orbital_command", "scanner_sweep", AbilityEvidence.VERIFIED_TMG,
            setOf(AbilityPhase.MOVEMENT), listOf(PendingEffectKind.SCANNER_SWEEP),
            "Set a Faction Indicator. Enemy Units within 6\" lose HIDDEN until round cleanup."
        ),
        // These audio sequences are physically verified SC2 assets, but their tabletop timing is
        // not present in the current supplied TMG card sheets. They stay audio-only so the app
        // cannot silently invent rules or lifecycle effects.
        AbilityBehavior("ghost", "emp", AbilityEvidence.AUDIO_ONLY),
        AbilityBehavior("ghost", "snipe", AbilityEvidence.AUDIO_ONLY),
        AbilityBehavior("battlecruiser", "yamato", AbilityEvidence.AUDIO_ONLY),
        AbilityBehavior("high_templar", "psionic_storm", AbilityEvidence.AUDIO_ONLY),
        AbilityBehavior("viper", "abduct", AbilityEvidence.AUDIO_ONLY)
    )

    private val byKey = entries.associateBy { it.audioId to it.abilityId }

    fun get(audioId: String, abilityId: String): AbilityBehavior? = byKey[audioId to abilityId]
    fun all(): List<AbilityBehavior> = entries
}
