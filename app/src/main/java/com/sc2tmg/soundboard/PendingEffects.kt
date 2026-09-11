package com.sc2tmg.soundboard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class PendingEffectTiming {
    END_ASSAULT,
    END_ROUND_DECISION,
    END_ROUND_CLEANUP,
    END_PHASE,
    END_ACTIVATION,
    NEXT_ACTION,
    FIRST_USE,
    MANUAL,
    STAY_IN_PLAY
}

enum class PendingEffectKind(
    val label: String,
    val timing: PendingEffectTiming,
    val detail: String
) {
    CORROSIVE_BILE("CORROSIVE BILE", PendingEffectTiming.END_ASSAULT, "Resolve Incoming → Explosion before Combat."),
    FORCE_FIELD("FORCE FIELD", PendingEffectTiming.END_ROUND_CLEANUP, "Remove if crossed by Size 3+; otherwise clear at round cleanup."),
    GUARDIAN_SHIELD("GUARDIAN SHIELD", PendingEffectTiming.END_ROUND_CLEANUP, "Active until round cleanup."),
    ADEPT_SHADE("ADEPT SHADE", PendingEffectTiming.END_ROUND_DECISION, "At End of Round choose TRANSFER or NO TRANSFER."),
    WARP_CONDUIT("WARP CONDUIT", PendingEffectTiming.END_ROUND_DECISION, "At End of Round deploy from the indicator or remove it."),
    VENTRAL_SACS("VENTRAL SACS", PendingEffectTiming.END_ROUND_DECISION, "At End of Round deploy from the indicator or remove it."),
    READY_FOR_DUST_OFF("READY FOR DUST-OFF", PendingEffectTiming.END_ROUND_DECISION, "At End of Round deploy from the indicator or remove it."),
    SCANNER_SWEEP("SCANNER SWEEP", PendingEffectTiming.END_ROUND_CLEANUP, "Detection area remains until round cleanup."),
    SURVEILLANCE("SURVEILLANCE", PendingEffectTiming.END_ROUND_CLEANUP, "Detection area remains until round cleanup."),
    OVERSIGHT("OVERSIGHT", PendingEffectTiming.END_ROUND_CLEANUP, "Detection area remains until round cleanup."),
    COMSAT_STATION("COMSAT STATION", PendingEffectTiming.END_ROUND_CLEANUP, "Deployment restriction remains until End of Round."),
    POINT_DEFENSE_DRONE("POINT DEFENSE DRONE", PendingEffectTiming.END_ROUND_CLEANUP, "Remove when its event triggers or at End of Round."),
    CREEP_TUMOR("CREEP TUMOR", PendingEffectTiming.STAY_IN_PLAY, "Stays in play until a real tabletop removal event."),
    BURROWED("BURROWED", PendingEffectTiming.MANUAL, "Persistent status; clear when the tabletop status is lost."),
    PYLON("PYLON", PendingEffectTiming.STAY_IN_PLAY, "Summoned Pylon remains on the battlefield."),
    PYLON_LOCKOUT("PYLON ABILITY LOCKOUT", PendingEffectTiming.END_ROUND_CLEANUP, "This round the summoned Pylon cannot use Special Abilities except Structure."),
    OMEGA_WORM("OMEGA WORM", PendingEffectTiming.STAY_IN_PLAY, "Summoned Omega Worm remains on the battlefield."),
    OMEGA_LOCKOUT("OMEGA WORM ABILITY LOCKOUT", PendingEffectTiming.END_ROUND_CLEANUP, "This round the summoned Omega Worm cannot use Special Abilities except Structure."),
    ROACHLING_INFESTATION("ROACHLING UNIT", PendingEffectTiming.STAY_IN_PLAY, "Summoned Roachlings remain until removed on the tabletop."),
    OPTICAL_FLARE("OPTICAL FLARE", PendingEffectTiming.END_ROUND_CLEANUP, "Range debuff lasts until End of Round."),
    STIMPACK("STIMPACK", PendingEffectTiming.END_ROUND_CLEANUP, "Remember the active Stim modifiers until round cleanup."),
    TARGET_LOCK("TARGET LOCK", PendingEffectTiming.END_ROUND_CLEANUP, "Target Lock remains until round cleanup."),
    HIERARCHS_STAND("HIERARCH'S STAND", PendingEffectTiming.END_ACTIVATION, "Redirect remains active until End of the current Activation."),
    PATH_OF_SHADOWS("PATH OF SHADOWS", PendingEffectTiming.NEXT_ACTION, "HIDDEN lasts until this Unit performs another action."),
    TERRAN_TENACITY("TERRAN TENACITY", PendingEffectTiming.END_PHASE, "First Player lock lasts for the remainder of this Phase."),
    CRUSHING_GRIP("CRUSHING GRIP", PendingEffectTiming.END_PHASE, "Target counts as Activated for this Phase."),
    QUICK_STRIKES("QUICK STRIKES", PendingEffectTiming.FIRST_USE, "Applies to the first Close Combat Weapon used."),
    ANCIENT_PRIDE("ANCIENT PRIDE", PendingEffectTiming.FIRST_USE, "Applies to the first Weapon used."),
    GROUND_WEAPONS("GROUND WEAPONS", PendingEffectTiming.FIRST_USE, "Applies to the first Ranged Weapon used."),
    INFANTRY_WEAPONS("INFANTRY WEAPONS", PendingEffectTiming.FIRST_USE, "Applies to the first Ranged Weapon used."),
    VEHICLE_WEAPONS("VEHICLE WEAPONS", PendingEffectTiming.FIRST_USE, "Applies to the first Ranged Weapon used."),
    RAYNOR_CRITICAL_HIT_ORDER("RAYNOR CRITICAL-HIT ORDER", PendingEffectTiming.FIRST_USE, "Applies to the first Weapon used."),
    WILD_MUTATION_SPEED("WILD MUTATION • SPEED", PendingEffectTiming.END_ROUND_CLEANUP, "BUFF Speed (1) component lasts through the round."),
    WILD_MUTATION_PRECISION("WILD MUTATION • PRECISION", PendingEffectTiming.FIRST_USE, "PRECISION (1) applies to the first Weapon used.")
}

data class PendingEffectInstance(
    val id: String,
    val kind: PendingEffectKind,
    val createdRound: Int,
    val createdPhase: Int,
    val sourceLabel: String = "",
    val audioId: String = "",
    val abilityId: String = "",
    val count: Int = 1,
    val detailOverride: String = ""
) {
    val label: String get() = kind.label
    val detail: String get() = detailOverride.ifBlank { kind.detail }
    val timing: PendingEffectTiming get() = kind.timing
    val statusLabel: String
        get() = when (timing) {
            PendingEffectTiming.END_ASSAULT -> "DUE • END OF ASSAULT"
            PendingEffectTiming.END_ROUND_DECISION -> "DUE • END OF ROUND DECISION"
            PendingEffectTiming.END_ROUND_CLEANUP -> "ACTIVE • UNTIL ROUND CLEANUP"
            PendingEffectTiming.END_PHASE -> "ACTIVE • UNTIL END OF PHASE"
            PendingEffectTiming.END_ACTIVATION -> "ACTIVE • UNTIL END OF ACTIVATION"
            PendingEffectTiming.NEXT_ACTION -> "ACTIVE • UNTIL NEXT ACTION"
            PendingEffectTiming.FIRST_USE -> "ACTIVE • FIRST USE"
            PendingEffectTiming.MANUAL -> "ACTIVE • MANUAL STATUS"
            PendingEffectTiming.STAY_IN_PLAY -> "STAY IN PLAY"
        }
}

/**
 * Match-persistent tabletop memory aid. It tracks only state the physical table cannot be trusted
 * to resolve for the player automatically: delayed resolutions, markers/tokens, temporary rules,
 * first-use effects, statuses and persistent summons. Instantaneous abilities never enter here.
 *
 * Every use is an independent instance. Three Corrosive Biles therefore remain three separate
 * resolutions. The store survives process recreation but is deliberately cleared by New Game and
 * Reset Game.
 */
class PendingEffectsStore(context: Context) {
    private val prefs = context.getSharedPreferences("sc2tmg_pending_effects", Context.MODE_PRIVATE)
    // v3 exists specifically to heal tracker state created by the broken early-R11 builds.
    // In particular, button-mashed STIMPACK rows/counts are collapsed into one reminder for
    // that source/ability/round while physical tokens (Bile, Force Fields, Shades, etc.) remain
    // independent.
    private val key = "instances_v3"
    private val legacyKeys = listOf("instances_v2", "instances_v1")

    var effects by mutableStateOf(load())
        private set

    init {
        // Rewrite any v1/v2 state immediately in the repaired format so an upgrade from a bad
        // R11 test build heals itself without requiring Reset Game.
        persist()
    }

    fun add(
        kind: PendingEffectKind,
        round: Int,
        phase: Int,
        sourceLabel: String = "",
        audioId: String = "",
        abilityId: String = "",
        detailOverride: String = ""
    ): PendingEffectInstance {
        val instance = PendingEffectInstance(
            id = UUID.randomUUID().toString(),
            kind = kind,
            createdRound = round.coerceAtLeast(1),
            createdPhase = phase.coerceIn(0, 3),
            sourceLabel = sourceLabel,
            audioId = audioId,
            abilityId = abilityId,
            count = 1,
            detailOverride = detailOverride
        )
        effects = effects + instance
        persist()
        return instance
    }

    /**
     * Collapse reminder-only effects that all expire at the same cleanup gate. The companion does
     * not know which physical squad used a repeated named ability, so a tap count is not reliable
     * tabletop state. Keep one reminder per source/ability/round instead of creating or incrementing
     * rows when a button is mashed. Physical tokens/delayed resolutions must continue to use add()
     * and remain independent.
     */
    fun addOrIncrement(
        kind: PendingEffectKind,
        round: Int,
        phase: Int,
        sourceLabel: String = "",
        audioId: String = "",
        abilityId: String = "",
        detailOverride: String = ""
    ): PendingEffectInstance {
        val existing = effects.firstOrNull {
            it.kind == kind &&
                it.createdRound == round.coerceAtLeast(1) &&
                it.sourceLabel == sourceLabel &&
                it.audioId == audioId &&
                it.abilityId == abilityId
        }
        if (existing != null) {
            val updated = existing.copy(
                count = 1,
                detailOverride = detailOverride.ifBlank { existing.detailOverride }
            )
            effects = effects.map { if (it.id == existing.id) updated else it }
            persist()
            return updated
        }
        return add(kind, round, phase, sourceLabel, audioId, abilityId, detailOverride)
    }

    fun remove(id: String): PendingEffectInstance? {
        val removed = effects.firstOrNull { it.id == id } ?: return null
        effects = effects.filterNot { it.id == id }
        persist()
        return removed
    }

    fun removeOldest(kind: PendingEffectKind): PendingEffectInstance? =
        effects.firstOrNull { it.kind == kind }?.let { remove(it.id) }

    fun removeAll(kinds: Set<PendingEffectKind>): List<PendingEffectInstance> {
        val removed = effects.filter { it.kind in kinds }
        if (removed.isNotEmpty()) {
            effects = effects.filterNot { it.kind in kinds }
            persist()
        }
        return removed
    }

    fun count(kind: PendingEffectKind): Int = effects.count { it.kind == kind }

    fun dueCorrosiveBile(round: Int): List<PendingEffectInstance> =
        // <= deliberately heals overdue Biles left behind by the broken early-R11 tracker.
        // A missed timing gate must become visible again rather than turning into an immortal
        // inspector-only row that can never be resolved.
        effects.filter { it.kind == PendingEffectKind.CORROSIVE_BILE && it.createdRound <= round }

    fun dueEndRoundDecisions(round: Int): List<PendingEffectInstance> =
        effects.filter { it.timing == PendingEffectTiming.END_ROUND_DECISION && it.createdRound <= round }

    fun dueRoundCleanup(round: Int): List<PendingEffectInstance> =
        effects.filter { it.timing == PendingEffectTiming.END_ROUND_CLEANUP && it.createdRound <= round }

    fun dueEndPhase(round: Int, phase: Int): List<PendingEffectInstance> =
        effects.filter {
            it.timing == PendingEffectTiming.END_PHASE &&
                it.createdRound == round && it.createdPhase == phase
        }

    fun clear() {
        effects = emptyList()
        val editor = prefs.edit().remove(key)
        legacyKeys.forEach(editor::remove)
        editor.apply()
    }

    private fun persist() {
        val array = JSONArray()
        effects.forEach { effect ->
            array.put(
                JSONObject()
                    .put("id", effect.id)
                    .put("kind", effect.kind.name)
                    .put("createdRound", effect.createdRound)
                    .put("createdPhase", effect.createdPhase)
                    .put("sourceLabel", effect.sourceLabel)
                    .put("audioId", effect.audioId)
                    .put("abilityId", effect.abilityId)
                    .put("count", effect.count)
                    .put("detailOverride", effect.detailOverride)
            )
        }
        val editor = prefs.edit().putString(key, array.toString())
        legacyKeys.forEach(editor::remove)
        editor.apply()
    }

    private fun load(): List<PendingEffectInstance> {
        val raw = prefs.getString(key, null)
            ?: legacyKeys.firstNotNullOfOrNull { prefs.getString(it, null) }
            ?: return emptyList()
        val loaded = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val kind = runCatching { PendingEffectKind.valueOf(item.getString("kind")) }.getOrNull() ?: continue
                    add(
                        PendingEffectInstance(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            kind = kind,
                            createdRound = item.optInt("createdRound", 1).coerceAtLeast(1),
                            createdPhase = item.optInt("createdPhase", 0).coerceIn(0, 3),
                            sourceLabel = item.optString("sourceLabel"),
                            audioId = item.optString("audioId"),
                            abilityId = item.optString("abilityId"),
                            // Old R11 builds incremented this count on every tap. A tap count is
                            // not reliable tabletop state, so migration normalizes it.
                            count = 1,
                            detailOverride = item.optString("detailOverride")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

        // The known broken build could create dozens of identical Stim reminders. Collapse only
        // the reminder-only Stim rows here. Do NOT deduplicate physical tokens/delayed choices.
        val seenStim = mutableSetOf<String>()
        return loaded.filter { effect ->
            if (effect.kind != PendingEffectKind.STIMPACK) true
            else seenStim.add(
                listOf(effect.createdRound, effect.sourceLabel, effect.audioId, effect.abilityId).joinToString("|")
            )
        }
    }
}
