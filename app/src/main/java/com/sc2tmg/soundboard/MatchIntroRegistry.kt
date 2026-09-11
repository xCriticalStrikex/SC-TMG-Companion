package com.sc2tmg.soundboard

import android.content.Context
import kotlin.random.Random

enum class MatchIntroMode(val label: String, val shortLabel: String) {
    OPENING_AND_GLHF("FACTION VS FACTION + GLHF", "OPENING + GLHF"),
    OPENING_ONLY("FACTION VS FACTION ONLY", "OPENING"),
    GLHF_ONLY("GLHF ONLY", "GLHF"),
    OFF("NONE", "NONE");

    companion object {
        fun fromStored(value: String?): MatchIntroMode =
            entries.firstOrNull { it.name == value } ?: OPENING_AND_GLHF
    }
}

enum class MatchIntroSemanticRole {
    MATCH_OPENING,
    GLHF_LITERAL
}

data class MatchIntroEntry(
    val id: String,
    val assetPath: String,
    val speaker: String,
    val transcript: String,
    val semanticRole: MatchIntroSemanticRole,
    val spokenFaction: Faction?,
    val sha256: String,
    val sourceFileName: String
) {
    val sectionLabel: String
        get() = when (semanticRole) {
            MatchIntroSemanticRole.MATCH_OPENING -> "${spokenFaction?.label ?: "NEUTRAL"} MATCH OPENING"
            MatchIntroSemanticRole.GLHF_LITERAL -> "LITERAL GLHF"
        }
}

/**
 * R18 transcript-first pre-match registry.
 *
 * MATCH_OPENING is deliberately strict: the speaker belongs to the player's actual faction and
 * no neutral/caster fallback is used. GLHF_LITERAL is a separate sportsmanship bank whose spoken
 * English explicitly says good luck / have fun / best of luck (or the same literal sentiment).
 * This keeps faction-vs-faction flavour separate from the neutral GLHF ceremony beat.
 */
class MatchIntroRegistry(private val context: Context) {
    val entries: List<MatchIntroEntry> by lazy { loadEntries() }

    private val previousSpeakerByStage = mutableMapOf<String, String>()
    private val previousPathByStage = mutableMapOf<String, String>()

    fun chooseFactionOpening(
        targetFaction: Faction,
        enabled: (String) -> Boolean,
        excludedSha256: Set<String> = emptySet(),
        excludedSpeakers: Set<String> = emptySet()
    ): MatchIntroEntry? {
        var pool = entries.filter {
            it.semanticRole == MatchIntroSemanticRole.MATCH_OPENING &&
                it.spokenFaction == targetFaction &&
                it.sha256 !in excludedSha256 &&
                enabled(it.assetPath)
        }
        if (pool.isEmpty()) return null

        // Same-faction matches should sound like two sides answering each other rather than one
        // unit talking twice. Only relax the speaker exclusion if it would empty the faction pool.
        val alternateSpeakers = pool.filterNot { it.speaker in excludedSpeakers }
        if (alternateSpeakers.isNotEmpty()) pool = alternateSpeakers

        return chooseFair("opening:${targetFaction.name}", pool)
    }

    fun chooseLiteralGlhf(enabled: (String) -> Boolean): MatchIntroEntry? {
        val pool = entries.filter {
            it.semanticRole == MatchIntroSemanticRole.GLHF_LITERAL && enabled(it.assetPath)
        }
        return chooseFair("glhf:literal", pool)
    }

    private fun chooseFair(stage: String, pool: List<MatchIntroEntry>): MatchIntroEntry? {
        if (pool.isEmpty()) return null
        val bySpeaker = pool.groupBy { it.speaker }
        val priorSpeaker = previousSpeakerByStage[stage]
        val speakerChoices = if (bySpeaker.size > 1 && priorSpeaker != null) {
            bySpeaker.keys.filterNot { it == priorSpeaker }
        } else bySpeaker.keys.toList()
        val speaker = speakerChoices.random(Random)
        val speakerPool = bySpeaker[speaker].orEmpty()
        val priorPath = previousPathByStage[stage]
        val clipChoices = if (speakerPool.size > 1 && priorPath != null) {
            speakerPool.filterNot { it.assetPath == priorPath }
        } else speakerPool
        val chosen = clipChoices.random(Random)
        previousSpeakerByStage[stage] = chosen.speaker
        previousPathByStage[stage] = chosen.assetPath
        return chosen
    }

    private fun loadEntries(): List<MatchIntroEntry> = runCatching {
        context.assets.open("audio/ui/match_intro/match_intro_registry.csv")
            .bufferedReader()
            .useLines { lines ->
                lines.drop(1).mapNotNull { line ->
                    val p = parseCsvRow(line)
                    if (p.size < 8) return@mapNotNull null
                    val path = p[1].trim()
                    if (path.isBlank()) return@mapNotNull null
                    val role = runCatching { MatchIntroSemanticRole.valueOf(p[4].trim()) }.getOrNull()
                        ?: return@mapNotNull null
                    val faction = when (p[5].trim().uppercase()) {
                        "TERRAN" -> Faction.TERRAN
                        "PROTOSS" -> Faction.PROTOSS
                        "ZERG" -> Faction.ZERG
                        else -> null
                    }
                    MatchIntroEntry(
                        id = p[0].trim(),
                        assetPath = path,
                        speaker = p[2].trim(),
                        transcript = p[3].trim(),
                        semanticRole = role,
                        spokenFaction = faction,
                        sha256 = p[6].trim(),
                        sourceFileName = p[7].trim()
                    )
                }.toList()
            }
    }.getOrDefault(emptyList())

    private fun parseCsvRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    out += field.toString()
                    field.clear()
                }
                else -> field.append(ch)
            }
            i++
        }
        out += field.toString()
        return out
    }
}
