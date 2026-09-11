package com.sc2tmg.soundboard

import android.content.Context

enum class ResultRole { GG_LITERAL, GG_CEREMONY, VICTORY_VO, TIE_VO, VICTORY_MUSIC, DRAW_MUSIC, REJECTED }
enum class ResultWinnerScope { ANY, TERRAN, PROTOSS, ZERG, TIE_ONLY, NEVER }

data class ResultAudioEntry(
    val assetPath: String,
    val sha256: String,
    val sourcePath: String,
    val sourceEvent: String,
    val speaker: String,
    val speakerFaction: String,
    val transcript: String,
    val role: ResultRole,
    val winnerScope: ResultWinnerScope,
    val literalGg: Boolean,
    val doorEligible: Boolean,
    val ceremonyEligible: Boolean,
    val victoryEligible: Boolean,
    val tieEligible: Boolean,
    val gainDb: Float,
    val reverbMs: Int,
    val confidence: String,
    val notes: String,
) {
    val primaryLabel: String get() = transcript.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: speaker
    val secondaryLabel: String get() = listOf(speaker, winnerScope.name, role.name).filter { it.isNotBlank() }.joinToString(" • ")
}

/** R14 semantic result-audio source of truth. Runtime meaning never comes from filenames. */
class ResultAudioRegistry(context: Context) {
    val entries: List<ResultAudioEntry> = context.assets.open("audio/result_audio_registry.csv")
        .bufferedReader().useLines { lines ->
            val all = lines.filter { it.isNotBlank() }.toList()
            if (all.isEmpty()) emptyList() else {
                val header = parseCsvRow(all.first()).mapIndexed { i, s -> s.removePrefix("\uFEFF").trim() to i }.toMap()
                all.drop(1).mapNotNull { line -> parseEntry(parseCsvRow(line), header) }
            }
        }

    fun victoryVo(faction: Faction): List<ResultAudioEntry> = entries.filter {
        it.victoryEligible && it.winnerScope in allowedWinnerScopes(faction)
    }

    fun ggLiteral(faction: Faction?): List<ResultAudioEntry> = entries.filter {
        it.literalGg && it.doorEligible && (faction == null || it.winnerScope in allowedWinnerScopes(faction))
    }

    fun ggCeremony(faction: Faction): List<ResultAudioEntry> = entries.filter {
        it.ceremonyEligible && it.winnerScope in allowedWinnerScopes(faction)
    }

    fun tieVo(): List<ResultAudioEntry> = entries.filter { it.tieEligible && it.winnerScope == ResultWinnerScope.TIE_ONLY }

    fun victoryMusic(scope: ResultWinnerScope): List<ResultAudioEntry> = entries.filter {
        it.role == ResultRole.VICTORY_MUSIC && it.winnerScope == scope
    }

    fun drawMusic(): List<ResultAudioEntry> = entries.filter {
        it.role == ResultRole.DRAW_MUSIC && it.winnerScope == ResultWinnerScope.TIE_ONLY
    }

    fun exactGroup(role: ResultRole, scope: ResultWinnerScope): List<ResultAudioEntry> = entries.filter {
        it.role == role && it.winnerScope == scope
    }

    fun literalGroup(scope: ResultWinnerScope): List<ResultAudioEntry> = entries.filter {
        it.literalGg && it.doorEligible && it.winnerScope == scope
    }

    fun ceremonyGroup(scope: ResultWinnerScope): List<ResultAudioEntry> = entries.filter {
        it.ceremonyEligible && it.winnerScope == scope
    }

    private fun allowedWinnerScopes(faction: Faction): Set<ResultWinnerScope> = setOfNotNull(
        ResultWinnerScope.ANY,
        when (faction) {
            Faction.TERRAN -> ResultWinnerScope.TERRAN
            Faction.PROTOSS -> ResultWinnerScope.PROTOSS
            Faction.ZERG -> ResultWinnerScope.ZERG
            Faction.HYBRID -> null
        }
    )

    private fun parseEntry(row: List<String>, header: Map<String, Int>): ResultAudioEntry? {
        fun get(name: String) = header[name]?.let { row.getOrNull(it) }.orEmpty().trim()
        val path = get("asset_path")
        val role = runCatching { ResultRole.valueOf(get("result_role")) }.getOrNull()
        val scope = runCatching { ResultWinnerScope.valueOf(get("winner_scope")) }.getOrNull()
        if (path.isBlank() || role == null || scope == null) return null
        return ResultAudioEntry(
            assetPath = path, sha256 = get("sha256"), sourcePath = get("source_path"),
            sourceEvent = get("source_event"), speaker = get("speaker"),
            speakerFaction = get("speaker_faction"), transcript = get("verified_transcript"),
            role = role, winnerScope = scope, literalGg = get("literal_gg").toBoolean(),
            doorEligible = get("door_eligible").toBoolean(), ceremonyEligible = get("ceremony_eligible").toBoolean(),
            victoryEligible = get("victory_eligible").toBoolean(), tieEligible = get("tie_eligible").toBoolean(),
            gainDb = get("gain_db").toFloatOrNull() ?: 0f, reverbMs = get("reverb_ms").toIntOrNull() ?: 0,
            confidence = get("confidence"), notes = get("notes")
        )
    }

    private fun parseCsvRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += field.toString(); field.clear() }
                else -> field.append(ch)
            }
            i++
        }
        out += field.toString()
        return out
    }
}
