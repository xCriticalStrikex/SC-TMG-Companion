package com.sc2tmg.soundboard

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import kotlin.random.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

data class AbilityAudioSpec(val id: String, val label: String)

data class SoundPoolItem(
    val path: String,
    val filename: String,
    val group: String,
    val displayLabel: String = "Audio clip",
    val secondaryLabel: String = group
)

private data class AudioDisplayLabel(
    val displayLabel: String,
    val source: String,
    val category: String
)

/** Conservative list of unit-specific ability buttons backed by bundled audio. */
object AbilityAudioRegistry {
    private val byAudioId: Map<String, List<AbilityAudioSpec>> = mapOf(
        "adept" to listOf(AbilityAudioSpec("psionic_transfer", "PSIONIC TRANSFER")),
        "stalker" to listOf(AbilityAudioSpec("blink", "BLINK")),
        "zealot" to listOf(AbilityAudioSpec("charge", "CHARGE")),
        "marine" to listOf(AbilityAudioSpec("stimpack", "STIMPACK")),
        "marauder" to listOf(AbilityAudioSpec("stimpack", "STIMPACK")),
        "sentry" to listOf(
            AbilityAudioSpec("force_field", "FORCE FIELD"),
            AbilityAudioSpec("guardian_shield", "GUARDIAN SHIELD")
        ),
        "ravager" to listOf(AbilityAudioSpec("corrosive_bile", "CORROSIVE BILE")),
        "ghost" to listOf(AbilityAudioSpec("emp", "EMP"), AbilityAudioSpec("snipe", "SNIPE")),
        "battlecruiser" to listOf(AbilityAudioSpec("yamato", "YAMATO CANNON")),
        "orbital_command" to listOf(AbilityAudioSpec("scanner_sweep", "SCANNER SWEEP")),
        "high_templar" to listOf(AbilityAudioSpec("psionic_storm", "PSIONIC STORM")),
        "viper" to listOf(AbilityAudioSpec("abduct", "ABDUCT"))
    )

    fun forAudioId(audioId: String): List<AbilityAudioSpec> = byAudioId[audioId].orEmpty()
}

class SoundPlayer(private val context: Context) {
    private var voicePlayer: MediaPlayer? = null
    private val sfxPlayers = linkedSetOf<MediaPlayer>()
    private var doorPlayer: MediaPlayer? = null
    private var doorBaseGain = 1f
    private var launchAmbiencePlayer: MediaPlayer? = null
    private var launchAmbienceGain = 0f
    private var launchAmbienceFadeMultiplier = 1f
    private var resultReverb: EnvironmentalReverb? = null
    // Keep ordinary attack cadence separate from composed ability timing. R11 previously used one
    // shared Handler and normal MOVE/ATTACK/BUILDING taps called removeCallbacksAndMessages(null),
    // which could silently cancel a pending Bile explosion, Stim voice layer, Yamato launch, etc.
    private val attackHandler = Handler(Looper.getMainLooper())
    private val abilityHandler = Handler(Looper.getMainLooper())
    private val tailFadeHandler = Handler(Looper.getMainLooper())
    private val previewFadeHandler = Handler(Looper.getMainLooper())
    private val launchAmbienceFadeHandler = Handler(Looper.getMainLooper())
    private var previewPlayer: MediaPlayer? = null
    private var previewPlayerBaseVolume = 1f
    private val fadingPreviewPlayers = linkedSetOf<MediaPlayer>()
    private val activeSustainedAbilities = mutableSetOf<String>()
    private val sfxCompletionActions = mutableMapOf<MediaPlayer, () -> Unit>()
    private val audioExtensions = setOf("ogg", "mp3", "wav", "m4a")
    private val folderCache = mutableMapOf<String, List<String>>()
    private val previous = mutableMapOf<String, String>()
    private val poolPrefs = context.getSharedPreferences("sc2tmg_sound_pool_controls", Context.MODE_PRIVATE)
    private val disabledPoolPaths = poolPrefs.getStringSet("disabled_paths_v1", emptySet()).orEmpty().toMutableSet()
    private val audioDisplayLabels: Map<String, AudioDisplayLabel> by lazy { loadAudioDisplayLabels() }
    private val audioDisplayLabelsByFilename: Map<String, AudioDisplayLabel> by lazy {
        // R10/R11 can be applied over several historical project layouts. The saved/runtime
        // asset path is authoritative for playback, but a few old trees keep the exact same
        // audio file under a slightly different category folder. A basename fallback lets the
        // player-facing label survive those harmless path differences without renaming assets.
        audioDisplayLabels.entries
            .groupBy { it.key.substringAfterLast('/').lowercase() }
            .mapNotNull { (filename, entries) ->
                val labels = entries.map { it.value }.distinct()
                if (labels.size == 1) filename to labels.first() else null
            }.toMap()
    }
    private val normalBankExclusions: Set<String> by lazy {
        runCatching {
            context.assets.open("audio/audio_normal_bank_exclusions.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .toSet()
            }
        }.getOrDefault(emptySet())
    }

    // R13: authoritative semantic roles for recovered canonical VO whose internal SC2
    // filenames do not reliably contain Ready/What/Yes/Pissed/Attack tokens. One asset
    // can legitimately serve more than one game-data event, so the CSV stores a
    // pipe-separated role set and the bytes are bundled only once.
    private val recoveredRuntimeRoles: Map<String, Set<String>> by lazy {
        runCatching {
            context.assets.open("audio/audio_runtime_roles.csv").bufferedReader().useLines { lines ->
                lines.drop(1).mapNotNull { line ->
                    val parts = parseCsvRow(line)
                    if (parts.size < 2) null else {
                        val path = parts[0].trim()
                        val roles = parts[1].split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
                        if (path.isBlank() || roles.isEmpty()) null else path to roles
                    }
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }


    // Release audit: some physically duplicated files resolve into the same runtime pool and
    // would otherwise receive extra random-selection weight. Only duplicate payload paths are
    // listed in this tiny registry; deduplication occurs AFTER per-path enable/disable filtering
    // so saved user preferences continue to behave predictably.
    private val unitVoiceDuplicateSha: Map<String, String> by lazy {
        runCatching {
            context.assets.open("audio/unit_voice_sha_dedupe.csv").bufferedReader().useLines { lines ->
                lines.drop(1).mapNotNull { line ->
                    val parts = parseCsvRow(line)
                    val path = parts.getOrNull(0)?.trim().orEmpty()
                    val hash = parts.getOrNull(1)?.trim()?.lowercase().orEmpty()
                    if (path.isBlank() || hash.isBlank()) null else path to hash
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun dedupeUnitVoicePayloads(paths: List<String>): List<String> =
        paths.distinctBy { path -> unitVoiceDuplicateSha[path] ?: path }

    private var volume = 1.0f
    private var buildingGain = 1.0f
    private var soundBankMode = SoundBankMode.SC2_PREFERRED
    private var voiceEnhancer: LoudnessEnhancer? = null
    private var currentVoiceIsBuilding = false
    // Weapon/combat effects sit underneath VO by default. 1.0 was far too loud
    // for extracted launch/impact samples, many of which are mastered aggressively.
    private var weaponSfxGain = 0.30f

    private fun voiceFolder(audioId: String, category: String) = "audio/units/$audioId/$category"
    private fun deployMoveFolder(audioId: String) = voiceFolder(audioId, "deploy_move")
    private fun extraChatterFolder(audioId: String) = voiceFolder(audioId, "chatter")
    private fun recoveredCanonicalFolder(audioId: String) = voiceFolder(audioId, "canonical")

    // SC2 game-data voice-family aliases. Keep the unit's own Catalog identity so it
    // remains a distinct soundboard/tabletop entry; only the SC2 VO bank is shared.
    private fun sc2VoiceSourceAudioId(audioId: String): String = when (audioId) {
        "scourge" -> "mutalisk"
        "tassadar" -> "high_templar"
        else -> audioId
    }

    private fun recoveredCanonicalPaths(audioId: String, category: String): List<String> {
        val folder = recoveredCanonicalFolder(audioId)
        return listAudioFiles(folder).map { "$folder/$it" }.filter { path ->
            category.lowercase() in recoveredRuntimeRoles[path].orEmpty()
        }
    }

    // Some tabletop heroes have their own VO bank but share a weapon family with a
    // normal SC2 unit. Route only the combat-SFX side through the closest canonical
    // weapon bank; their hero VO remains untouched.
    private fun attackSfxAudioId(audioId: String): String = when (audioId) {
        "jim_raynor" -> "marine"          // C-14/commando rifle family
        "zeratul" -> "dark_templar"      // psi-blade strike
        "kerrigan" -> "queen"            // includes QueenOfBlades ranged attack FX
        else -> audioId
    }

    private fun attackSfxFolder(audioId: String) = "audio/sfx/units/${attackSfxAudioId(audioId)}/attack"
    private fun abilityFolder(audioId: String, abilityId: String) = "audio/abilities/$audioId/$abilityId"
    private fun classicUnitFolder(audioId: String, category: String) = "audio/classic/units/$audioId/$category"
    private fun classicBuildingFolder(buildingId: String) = "audio/classic/buildings/$buildingId"

    /**
     * Player-facing long-press labels are keyed by the immutable asset path. The CSV deliberately
     * contains conservative descriptions where dialogue has not been human-verified; saved pool
     * exclusions therefore remain compatible even when labels improve later.
     */
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

    private fun loadAudioDisplayLabels(): Map<String, AudioDisplayLabel> = runCatching {
        context.assets.open("audio/audio_display_labels.csv").bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val parts = parseCsvRow(line)
                if (parts.size < 4) null else {
                    val path = parts[0].trim()
                    if (path.isBlank()) null else path to AudioDisplayLabel(
                        // Never surface an internal asset filename to the player. Blank/unknown
                        // labels fall through to friendlyFallbackLabel() when the pool item is built.
                        displayLabel = parts[1].trim(),
                        source = parts[2].trim(),
                        category = parts[3].trim()
                    )
                }
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    private fun friendlyFallbackLabel(filename: String, group: String): String {
        val stem = filename.substringBeforeLast('.')
        val lower = stem.lowercase()
        val number = Regex("(\\d+)$").find(stem)?.groupValues?.getOrNull(1)?.toIntOrNull()?.plus(1)
        val suffix = number?.let { " ${it.toString().padStart(2, '0')}" }.orEmpty()
        return when {
            lower.contains("pissed") || lower.contains("annoy") || lower.contains("pss") -> "Annoyed / repeated selection$suffix"
            lower.contains("taunt") -> "Taunt$suffix"
            lower.contains("joke") || lower.contains("funny") -> "Joke / chatter$suffix"
            lower.contains("death") || group.contains("DEATH", true) -> "Death voice$suffix"
            group.contains("WEAPON", true) && (lower.contains("impact") || lower.contains("hit")) -> "Weapon impact$suffix"
            group.contains("WEAPON", true) && (lower.contains("launch") || lower.contains("attack") || lower.contains("fire")) -> "Weapon fire$suffix"
            group.contains("WEAPON", true) -> "Weapon effect$suffix"
            group.contains("ATTACK", true) -> "Attack voice$suffix"
            lower.contains("ready") || lower.contains("rdy") -> "Ready / selection$suffix"
            lower.contains("what") || lower.contains("wht") || lower.contains("select") -> "Selection response$suffix"
            lower.contains("yes") || lower.contains("_ok") || Regex("(^|_)ok\\d").containsMatchIn(lower) -> "Move / order response$suffix"
            lower.contains("move") -> "Movement response$suffix"
            group.contains("PORTRAIT", true) || group.contains("VOICE", true) -> "Portrait chatter$suffix"
            group.contains("MOVE", true) -> "Move / order response$suffix"
            else -> "Audio clip$suffix"
        }
    }

    private fun soundPoolItem(path: String, filename: String, group: String): SoundPoolItem {
        val info = audioDisplayLabels[path] ?: audioDisplayLabelsByFilename[filename.lowercase()]
        val secondary = listOfNotNull(
            info?.source?.takeIf { it.isNotBlank() },
            info?.category?.takeIf { it.isNotBlank() }
        ).distinct().joinToString(" • ").ifBlank { group }
        return SoundPoolItem(
            path = path,
            filename = filename,
            group = group,
            displayLabel = info?.displayLabel?.takeIf { it.isNotBlank() } ?: friendlyFallbackLabel(filename, group),
            secondaryLabel = secondary
        )
    }

    private fun isActivateLine(filename: String): Boolean {
        val n = filename.lowercase()
        // MOVE / ACTIVATE intentionally combines normal order acknowledgements
        // with selected/ready lines. These are all ordinary tabletop activation VO.
        return n.contains("yes") || n.contains("_ok") || n.matches(Regex(".*ok\\d+.*")) ||
            n.contains("move") || n.contains("what") || n.contains("ready") ||
            n.contains("select") || n.contains("rdy") || n.contains("wht")
    }

    private fun isFunVoiceLine(filename: String): Boolean {
        val n = filename.lowercase()
        // The secondary VOICE button is ONLY repeated-selection / annoyed / silly VO.
        // What/Ready/Select belong to MOVE / ACTIVATE, not here.
        return n.contains("pissed") || n.contains("annoy") || n.contains("pss") ||
            n.contains("taunt") || n.contains("joke") || n.contains("funny")
    }

    private fun listAudioPaths(folder: String): List<String> = listAudioFiles(folder).map { "$folder/$it" }

    /**
     * Exact-bank selection only. No fuzzy cross-unit substitution is performed here.
     * SC2 Preferred and Classic Preferred both fall back to the other library only
     * when the requested exact bank is absent; Mixed pools both exact banks.
     */
    private fun resolveSoundBank(primary: List<String>, classic: List<String>): List<String> = when (soundBankMode) {
        SoundBankMode.SC2_PREFERRED -> if (primary.isNotEmpty()) primary else classic
        SoundBankMode.CLASSIC_PREFERRED -> if (classic.isNotEmpty()) classic else primary
        SoundBankMode.MIXED -> (primary + classic).distinct()
    }

    fun setSoundBankMode(mode: SoundBankMode) { soundBankMode = mode }
    fun soundBankMode(): SoundBankMode = soundBankMode

    /** Compatibility bridge for any old call site. */
    fun setClassicSoundsEnabled(enabled: Boolean) {
        soundBankMode = if (enabled) SoundBankMode.MIXED else SoundBankMode.SC2_PREFERRED
    }
    fun classicSoundsEnabled(): Boolean = soundBankMode == SoundBankMode.MIXED

    private fun sc2VoicePaths(audioId: String, category: String): List<String> {
        val sourceAudioId = sc2VoiceSourceAudioId(audioId)
        val recovered = recoveredCanonicalPaths(sourceAudioId, category)
        val paths = when (category) {
            "move" -> {
                val trusted = listAudioFiles(deployMoveFolder(sourceAudioId)).filter(::isActivateLine).map { "${deployMoveFolder(sourceAudioId)}/$it" }
                val curated = listAudioFiles(extraChatterFolder(sourceAudioId)).filter(::isActivateLine).map { "${extraChatterFolder(sourceAudioId)}/$it" }
                // Game-data audit: Raynor_Attack04 is physically stored in an old ATTACK folder,
                // but its SoundData event is Raynor_Yes. Preserve the immutable asset path/preference
                // key and route it to MOVE / ACTIVATE instead of deleting or mislabelling it.
                val raynorYes = if (sourceAudioId == "jim_raynor" &&
                    "Raynor_Attack04.ogg" in listAudioFiles(voiceFolder(sourceAudioId, "attack"))) {
                    listOf("audio/units/jim_raynor/attack/Raynor_Attack04.ogg")
                } else emptyList()
                (trusted + curated + raynorYes + recovered).distinct()
            }
            "chatter" -> {
                val trusted = listAudioFiles(deployMoveFolder(sourceAudioId)).filter(::isFunVoiceLine).map { "${deployMoveFolder(sourceAudioId)}/$it" }
                val curated = listAudioFiles(extraChatterFolder(sourceAudioId)).filter(::isFunVoiceLine).map { "${extraChatterFolder(sourceAudioId)}/$it" }
                (trusted + curated + recovered).distinct()
            }
            "attack" -> (listAudioPaths(voiceFolder(sourceAudioId, category)).filterNot {
                sourceAudioId == "jim_raynor" && it.endsWith("/Raynor_Attack04.ogg")
            } + recovered).distinct()
            "death" -> (listAudioPaths(voiceFolder(sourceAudioId, category)) + recovered).distinct()
            // Backward-compatible alias for any old call sites; UI no longer exposes it.
            "deploy_move" -> (listAudioPaths(deployMoveFolder(sourceAudioId)) + recoveredCanonicalPaths(sourceAudioId, "move")).distinct()
            else -> emptyList()
        }
        // The installer physically removes confirmed contamination in R13, but this runtime
        // denylist remains deliberate defense in depth for stale historical checkouts.
        return paths.filterNot { it in normalBankExclusions }
    }

    private fun rawUnitVoicePaths(audioId: String, category: String): List<String> {
        val canonical = if (category == "deploy_move") "move" else category
        return resolveSoundBank(sc2VoicePaths(audioId, canonical), listAudioPaths(classicUnitFolder(audioId, canonical)))
    }

    private fun poolPathEnabled(path: String): Boolean = path !in disabledPoolPaths

    private fun unitVoicePaths(audioId: String, category: String): List<String> =
        dedupeUnitVoicePayloads(rawUnitVoicePaths(audioId, category).filter(::poolPathEnabled))

    fun hasUnitAudio(audioId: String, category: String): Boolean {
        val canonical = if (category == "deploy_move") "move" else category
        val hasVoice = rawUnitVoicePaths(audioId, canonical).isNotEmpty()
        return if (canonical == "attack") hasVoice || listAudioFiles(attackSfxFolder(audioId)).isNotEmpty() else hasVoice
    }

    fun unitPoolItems(audioId: String, category: String): List<SoundPoolItem> {
        val canonical = if (category == "deploy_move") "move" else category
        val voiceGroup = when (canonical) {
            "chatter" -> "PORTRAIT / VOICE"
            "move" -> "MOVE / ACTIVATE VO"
            "attack" -> "ATTACK VO"
            "death" -> "DEATH VO"
            else -> canonical.uppercase()
        }
        val voice = rawUnitVoicePaths(audioId, canonical).map { path ->
            soundPoolItem(path, path.substringAfterLast('/'), voiceGroup)
        }
        val weapon = if (canonical == "attack") {
            val folder = attackSfxFolder(audioId)
            listAudioFiles(folder).map { file -> soundPoolItem("$folder/$file", file, "WEAPON SFX") }
        } else emptyList()
        return (voice + weapon).distinctBy { it.path }
    }

    fun isPoolClipEnabled(path: String): Boolean = poolPathEnabled(path)

    /** Match-intro clips use the same persistent per-file enable/disable controls as other pools. */
    fun playMatchIntro(entry: MatchIntroEntry): PlayResult =
        playAsset(entry.assetPath, AudioBus.VOICE)

    fun setPoolClipEnabled(path: String, enabled: Boolean) {
        if (enabled) disabledPoolPaths.remove(path) else disabledPoolPaths.add(path)
        poolPrefs.edit().putStringSet("disabled_paths_v1", disabledPoolPaths.toSet()).apply()
    }

    fun resetUnitPool(audioId: String, category: String) {
        unitPoolItems(audioId, category).forEach { disabledPoolPaths.remove(it.path) }
        poolPrefs.edit().putStringSet("disabled_paths_v1", disabledPoolPaths.toSet()).apply()
    }

    fun enableAllUnitPool(audioId: String, category: String) = resetUnitPool(audioId, category)

    /**
     * Audio-manager previews are deliberately isolated from the live VO/SFX buses. Starting a
     * second preview always stops the first, so long result songs and sustained samples can never
     * stack on top of one another. Dialog disposal calls stopPreview() as well.
     */
    fun previewPoolClip(path: String): PlayResult = try {
        // A new preview crossfades away the previous one instead of stacking or hard-cutting.
        val previousPreview = previewPlayer
        previewPlayer = null
        releaseFadingPreviewPlayers()
        previousPreview?.let { fadePreviewOut(it, previewPlayerBaseVolume, 180) }

        val isSfx = path.contains("/sfx/") || path.contains("/abilities/") || path.contains("/result_music/")
        val afd = context.assets.openFd(path)
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(if (isSfx) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        mp.prepare()
        val base = (volume * if (isSfx) 0.72f else 1.0f).coerceIn(0f, 1f)
        previewPlayerBaseVolume = base
        mp.setVolume(0f, 0f)

        fun finish(completed: MediaPlayer) {
            if (previewPlayer === completed) previewPlayer = null
            fadingPreviewPlayers.remove(completed)
            runCatching { completed.release() }
        }
        mp.setOnCompletionListener(::finish)
        mp.setOnErrorListener { bad, _, _ -> finish(bad); true }
        previewPlayer = mp
        mp.start()
        fadePreviewIn(mp, base, 120)
        scheduleTailFade(mp, base, sustainedTailFadeMs(path), preview = true)
        PlayResult(true, path)
    } catch (_: Exception) {
        stopPreview(immediate = true)
        PlayResult(false, "Could not preview this audio file")
    }

    /**
     * Dialog exits use a short fade rather than a hard stop. Global SoundPlayer.stop() can request
     * immediate cleanup so no MediaPlayer survives app/session teardown.
     */
    fun stopPreview(immediate: Boolean = false) {
        val active = previewPlayer
        previewPlayer = null
        if (immediate) {
            previewFadeHandler.removeCallbacksAndMessages(null)
            active?.let { releasePreviewImmediately(it) }
            releaseFadingPreviewPlayers()
        } else if (active != null) {
            releaseFadingPreviewPlayers()
            fadePreviewOut(active, previewPlayerBaseVolume, 240)
        }
    }

    private fun fadePreviewIn(mp: MediaPlayer, target: Float, durationMs: Int) {
        val duration = durationMs.coerceAtLeast(1)
        val startedAt = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (previewPlayer !== mp) return
                val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
                val t = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                // Smoothstep avoids the obvious staircase of a linear 30–40 ms volume ramp.
                val eased = t * t * (3f - 2f * t)
                val out = (target * eased).coerceIn(0f, 1f)
                runCatching { mp.setVolume(out, out) }
                if (t < 1f) previewFadeHandler.postDelayed(this, 16L)
            }
        }
        previewFadeHandler.post(step)
    }

    private fun fadePreviewOut(mp: MediaPlayer, fromVolume: Float, durationMs: Int) {
        fadingPreviewPlayers += mp
        val duration = durationMs.coerceAtLeast(1)
        val startedAt = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (mp !in fadingPreviewPlayers) return
                val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
                val t = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val eased = cos(t.toDouble() * PI / 2.0).toFloat().coerceIn(0f, 1f)
                val out = (fromVolume * eased).coerceIn(0f, 1f)
                runCatching { mp.setVolume(out, out) }
                if (t >= 1f) {
                    fadingPreviewPlayers.remove(mp)
                    releasePreviewImmediately(mp)
                } else {
                    previewFadeHandler.postDelayed(this, 16L)
                }
            }
        }
        previewFadeHandler.post(step)
    }

    private fun releasePreviewImmediately(mp: MediaPlayer) {
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.release() }
    }

    private fun releaseFadingPreviewPlayers() {
        val stale = fadingPreviewPlayers.toList()
        fadingPreviewPlayers.clear()
        stale.forEach(::releasePreviewImmediately)
    }

    /** True when the selected sound-bank mode exposes at least one VO line for this unit. */
    fun hasAnyUnitVoice(audioId: String): Boolean =
        listOf("move", "chatter", "attack", "death").any { category ->
            unitVoicePaths(audioId, category).isNotEmpty()
        }

    /**
     * Compatibility helper used by tactic/building tiles that intentionally borrow a
     * unit's voice bank. This chooses across the unit's available VO categories only;
     * combat SFX are not mixed in here.
     */
    fun playRandomUnitVoice(audioId: String): PlayResult {
        attackHandler.removeCallbacksAndMessages(null)
        val choices = dedupeUnitVoicePayloads(
            listOf("move", "chatter", "attack", "death")
                .flatMap { category -> unitVoicePaths(audioId, category) }
                .distinct()
        )
        val chosen = chooseNoRepeat("unit-any:$audioId", choices)
            ?: return PlayResult(false, "No unit voice audio in the installed library")
        return playAsset(chosen, AudioBus.VOICE, isBuildingVoice = true)
    }

    fun hasUnitAttackSfx(audioId: String): Boolean = listAudioFiles(attackSfxFolder(audioId)).isNotEmpty()
    fun hasBuildingAudio(buildingId: String): Boolean =
        resolveSoundBank(listAudioPaths("audio/buildings/$buildingId"), listAudioPaths(classicBuildingFolder(buildingId))).isNotEmpty()

    /** Enabled VO count only; used by the UI's VO CLIPS label. */
    fun unitClipCount(audioId: String, category: String): Int = unitVoicePaths(audioId, category).size
    fun unitAttackSfxCount(audioId: String): Int = listAudioFiles(attackSfxFolder(audioId)).count { file ->
        poolPathEnabled("${attackSfxFolder(audioId)}/$file")
    }
    private fun abilityRuntimeFiles(audioId: String, abilityId: String): List<String> {
        val files = listAudioFiles(abilityFolder(audioId, abilityId))
        // R15: Stim is a mechanical ability cue only. The old Marine StimPackVO overlay contained
        // ordinary acknowledgement/attack dialogue (including the reported "That's fine" line),
        // which is not an acceptable tabletop Stimpack sound. Whitelist only the eight canonical
        // Marine_Stimpack mechanical variants verified by the R11 ability truth table. This also
        // prevents any stale SuperStim/campaign file from re-entering the pool in an old checkout.
        return if (abilityId == "stimpack" && (audioId == "marine" || audioId == "marauder")) {
            files.filter { it.matches(Regex("Marine_Stimpack[0-7]\\.wav", RegexOption.IGNORE_CASE)) }
        } else files
    }

    fun hasUnitAbilityAudio(audioId: String, abilityId: String): Boolean =
        abilityRuntimeFiles(audioId, abilityId).isNotEmpty()
    fun unitAbilityClipCount(audioId: String, abilityId: String): Int =
        abilityRuntimeFiles(audioId, abilityId).size

    fun abilityPoolItems(audioId: String, abilityId: String): List<SoundPoolItem> {
        val folder = abilityFolder(audioId, abilityId)
        val label = AbilityAudioRegistry.forAudioId(audioId).firstOrNull { it.id == abilityId }?.label
            ?: abilityId.replace('_', ' ').uppercase()
        return abilityRuntimeFiles(audioId, abilityId).mapIndexed { index, file ->
            val path = "$folder/$file"
            val item = soundPoolItem(path, file, "ABILITY SFX")
            item.copy(
                displayLabel = if (item.displayLabel.startsWith("Audio clip")) "$label EFFECT ${(index + 1).toString().padStart(2, '0')}" else item.displayLabel,
                secondaryLabel = "SC2 • ABILITY SFX"
            )
        }
    }

    fun playUnitAbility(audioId: String, abilityId: String): PlayResult {
        val folder = abilityFolder(audioId, abilityId)
        val files = abilityRuntimeFiles(audioId, abilityId)
        if (files.isEmpty()) return PlayResult(false, "No $abilityId ability audio in the installed library")

        // R15: Stimpack deliberately plays ONLY the canonical mechanical Stimpack bank. Do not
        // layer Marine acknowledgement/attack VO over the activation and never fall back to a
        // general unit/Infested-Terran voice bank.
        if (abilityId == "stimpack" && (audioId == "marine" || audioId == "marauder")) {
            val stimSfx = chooseNoRepeat("ability:$audioId:stimpack:sfx", files)
                ?: return PlayResult(false, "No canonical Stimpack effect is installed")
            return playAsset("$folder/$stimSfx", AudioBus.SFX, gainOverride = 0.72f)
        }

        if (audioId == "ravager" && abilityId == "corrosive_bile") {
            val launches = files.filter { it.contains("AttackLaunch", ignoreCase = true) || it.contains("launch", ignoreCase = true) }
            val launch = chooseNoRepeat("ability:ravager:corrosive_bile:launch", launches)
                ?: return PlayResult(false, "No canonical Corrosive Bile launch audio is installed")
            return playAsset("$folder/$launch", AudioBus.SFX, gainOverride = 0.72f)
        }

        if (audioId == "adept" && abilityId == "psionic_transfer") {
            val launches = files.filter { it.contains("launch", ignoreCase = true) }
            val launch = chooseNoRepeat("ability:adept:psionic_transfer:launch", launches)
                ?: return PlayResult(false, "No canonical Psionic Transfer launch audio is installed")
            return playAsset("$folder/$launch", AudioBus.SFX, gainOverride = 0.68f)
        }

        // Iconic layered abilities are composed from their real phases rather than treated as a
        // random bag. Generic abilities still use no-repeat random selection for interchangeable
        // variations.
        if (audioId == "battlecruiser" && abilityId == "yamato") {
            val charge = files.firstOrNull { it.contains("charge", ignoreCase = true) }
            val launch = files.firstOrNull { it.contains("launch", ignoreCase = true) }
            if (charge != null && launch != null) {
                val first = playAsset("$folder/$charge", AudioBus.SFX, gainOverride = 0.72f)
                if (first.success) abilityHandler.postDelayed({ playAsset("$folder/$launch", AudioBus.SFX, gainOverride = 0.72f) }, 1850L)
                return first
            }
        }

        if ((audioId == "ghost" || audioId == "nova") && abilityId == "snipe") {
            val launch = files.firstOrNull { it.contains("launch", ignoreCase = true) }
            val impacts = files.filter { it.contains("impact", ignoreCase = true) }
            if (launch != null) {
                val first = playAsset("$folder/$launch", AudioBus.SFX, gainOverride = 0.72f)
                if (first.success && impacts.isNotEmpty()) {
                    abilityHandler.postDelayed({
                        chooseNoRepeat("ability:$audioId:$abilityId:impact", impacts)?.let { impact ->
                            playAsset("$folder/$impact", AudioBus.SFX, gainOverride = 0.72f)
                        }
                    }, 620L)
                }
                return first
            }
        }

        if (audioId == "sentry" && abilityId == "guardian_shield") {
            val launch = files.firstOrNull { it.contains("launch", true) }
            val loop = files.firstOrNull { it.contains("loop", true) }
            if (launch != null) {
                val sustainedKey = "$audioId:$abilityId"
                if (!beginSustainedAbility(sustainedKey)) {
                    // Treat a duplicate tap as handled: callers should not show an error toast or
                    // add a second audio layer while the existing shield cue is still active.
                    return PlayResult(true, "Guardian Shield audio is already active")
                }
                val first = playAsset("$folder/$launch", AudioBus.SFX, gainOverride = 0.68f)
                if (!first.success) {
                    endSustainedAbility(sustainedKey)
                    return first
                }
                if (loop != null) {
                    // One short representative loop is enough to communicate that the shield is active.
                    // The semantic ability remains locked until that sustained layer has fully completed,
                    // including its R17 tail fade.
                    abilityHandler.postDelayed({
                        val loopResult = playAsset(
                            "$folder/$loop",
                            AudioBus.SFX,
                            gainOverride = 0.43f,
                            onFinished = { endSustainedAbility(sustainedKey) }
                        )
                        if (!loopResult.success) endSustainedAbility(sustainedKey)
                    }, 620L)
                } else {
                    endSustainedAbility(sustainedKey)
                }
                return first
            }
        }

        if (audioId == "high_templar" && abilityId == "psionic_storm") {
            val launch = files.firstOrNull { it.contains("launch", true) }
            val voice = files.firstOrNull { it.endsWith(".ogg", true) }
            val impacts = files.filter { it.contains("impact", true) }
            if (launch != null) {
                val first = playAsset("$folder/$launch", AudioBus.SFX, gainOverride = 0.64f)
                if (first.success && voice != null) abilityHandler.postDelayed({ playAsset("$folder/$voice", AudioBus.VOICE) }, 70L)
                if (first.success && impacts.isNotEmpty()) abilityHandler.postDelayed({
                    chooseNoRepeat("ability:$audioId:$abilityId:impact", impacts)?.let { impact ->
                        playAsset("$folder/$impact", AudioBus.SFX, gainOverride = 0.68f)
                    }
                }, 520L)
                return first
            }
        }

        if (audioId == "viper" && abilityId == "abduct") {
            val launches = files.filter { it.contains("launch", true) }
            val launch = chooseNoRepeat("ability:$audioId:$abilityId:launch", launches)
            if (launch != null) {
                val pair = Regex("(\\d+)(?=\\.[^.]+$)").find(launch)?.groupValues?.getOrNull(1)
                val impact = pair?.let { suffix -> files.firstOrNull { it.contains("impact", true) && it.substringBeforeLast('.').endsWith(suffix) } }
                    ?: chooseNoRepeat("ability:$audioId:$abilityId:impact", files.filter { it.contains("impact", true) })
                val first = playAsset("$folder/$launch", AudioBus.SFX, gainOverride = 0.68f)
                if (first.success && impact != null) abilityHandler.postDelayed({ playAsset("$folder/$impact", AudioBus.SFX, gainOverride = 0.68f) }, 420L)
                return first
            }
        }

        val chosen = chooseNoRepeat("ability:$audioId:$abilityId", files)
            ?: return PlayResult(false, "No $abilityId ability audio in the installed library")
        val chosenPath = "$folder/$chosen"
        if (isSustainedPath(chosenPath)) {
            val sustainedKey = "$audioId:$abilityId"
            if (!beginSustainedAbility(sustainedKey)) {
                return PlayResult(true, "$abilityId audio is already active")
            }
            val result = playAsset(
                chosenPath,
                AudioBus.SFX,
                gainOverride = 0.72f,
                onFinished = { endSustainedAbility(sustainedKey) }
            )
            if (!result.success) endSustainedAbility(sustainedKey)
            return result
        }
        return playAsset(chosenPath, AudioBus.SFX, gainOverride = 0.72f)
    }

    /**
     * Plays a named phase of a tracked battlefield ability. Missing exact phases are reported rather
     * than substituted with merely similar campaign/co-op audio.
     */
    fun playAbilityPhase(audioId: String, abilityId: String, phase: String): PlayResult {
        val folder = abilityFolder(audioId, abilityId)
        val files = listAudioFiles(folder)
        val match = when (phase.lowercase()) {
            "launch", "place" -> files.filter { it.contains("launch", true) || it.contains("place", true) }
            "incoming" -> files.filter { it.contains("incoming", true) }
            "impact", "explode", "explosion" -> files.filter { it.contains("impact", true) || it.contains("explosion", true) }
            "remove", "expire", "death" -> files.filter { it.contains("death", true) || it.contains("expire", true) || it.contains("remove", true) }
            "loop" -> files.filter { it.contains("loop", true) }
            "teleport", "transfer" -> files.filter { it.contains("teleport", true) }
            else -> emptyList()
        }
        val chosen = chooseNoRepeat("ability:$audioId:$abilityId:$phase", match)
            ?: return PlayResult(false, "Exact $phase audio is not installed for $abilityId")
        return playAsset("$folder/$chosen", AudioBus.SFX, gainOverride = 0.72f)
    }

    fun playCorrosiveBileResolution(): PlayResult {
        val folder = abilityFolder("ravager", "corrosive_bile")
        val files = listAudioFiles(folder)
        val incoming = files.filter { it.contains("incoming", true) }
        val impact = files.filter { it.contains("impact", true) || it.contains("explosion", true) }
        val firstName = chooseNoRepeat("ability:ravager:corrosive_bile:incoming", incoming)
        val impactName = chooseNoRepeat("ability:ravager:corrosive_bile:impact", impact)
        if (firstName == null && impactName == null) return PlayResult(false, "Exact Corrosive Bile incoming/impact audio is not installed")
        val firstResult = firstName?.let { playAsset("$folder/$it", AudioBus.SFX, gainOverride = 0.72f) }
            ?: PlayResult(true, "impact only")
        if (impactName != null) {
            abilityHandler.postDelayed({ playAsset("$folder/$impactName", AudioBus.SFX, gainOverride = 0.78f) }, if (firstName != null) 820L else 0L)
        }
        return firstResult
    }

    /** Play a one-shot UI cue without stealing the VO bus. */
    fun playUi(path: String, gain: Float = 1.0f): PlayResult =
        playAsset(path, AudioBus.SFX, gainOverride = gain.coerceIn(0f, 1.2f))

    /**
     * Dedicated cold-launch ambience layer. It stays independent from the transient SFX pool so
     * the door and spark remain dominant, then performs a smooth timed release instead of being
     * cut off when the corridor clears the frame.
     */
    fun playLaunchAmbience(
        path: String,
        gain: Float = 0.065f,
        fadeOutStartMs: Int = 4000,
        fadeOutDurationMs: Int = 2000
    ): PlayResult = try {
        stopLaunchAmbience()
        val afd = context.assets.openFd(path)
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        mp.setOnCompletionListener { completed ->
            if (launchAmbiencePlayer === completed) {
                launchAmbiencePlayer = null
                launchAmbienceFadeHandler.removeCallbacksAndMessages(null)
            }
            try { completed.release() } catch (_: Exception) {}
        }
        mp.setOnErrorListener { bad, _, _ ->
            if (launchAmbiencePlayer === bad) {
                launchAmbiencePlayer = null
                launchAmbienceFadeHandler.removeCallbacksAndMessages(null)
            }
            try { bad.release() } catch (_: Exception) {}
            true
        }
        mp.prepare()
        launchAmbiencePlayer = mp
        launchAmbienceGain = gain.coerceIn(0f, 0.20f)
        launchAmbienceFadeMultiplier = 1f
        applyLaunchAmbienceVolume()
        mp.start()

        val fadeDuration = fadeOutDurationMs.coerceIn(250, 4000)
        val fadeStart = fadeOutStartMs.coerceAtLeast(0)
        launchAmbienceFadeHandler.postDelayed(object : Runnable {
            private val startedAt = android.os.SystemClock.uptimeMillis() + fadeStart

            override fun run() {
                if (launchAmbiencePlayer !== mp) return
                val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
                val t = (elapsed.toFloat() / fadeDuration.toFloat()).coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)
                launchAmbienceFadeMultiplier = 1f - eased
                applyLaunchAmbienceVolume()
                if (t < 1f) {
                    launchAmbienceFadeHandler.postDelayed(this, 24L)
                } else {
                    stopLaunchAmbience()
                }
            }
        }, fadeStart.toLong())
        PlayResult(true, path)
    } catch (_: Exception) {
        stopLaunchAmbience()
        PlayResult(false, "Could not play launch ambience")
    }

    private fun applyLaunchAmbienceVolume() {
        val out = (volume * launchAmbienceGain * launchAmbienceFadeMultiplier).coerceIn(0f, 1f)
        try { launchAmbiencePlayer?.setVolume(out, out) } catch (_: Exception) {}
    }

    private fun stopLaunchAmbience() {
        launchAmbienceFadeHandler.removeCallbacksAndMessages(null)
        launchAmbiencePlayer?.let { active ->
            try { if (active.isPlaying) active.stop() } catch (_: Exception) {}
            try { active.release() } catch (_: Exception) {}
        }
        launchAmbiencePlayer = null
        launchAmbienceGain = 0f
        launchAmbienceFadeMultiplier = 1f
    }

    /** Mechanical result door tagged separately so speech can duck only this layer. */
    fun playDoor(path: String, gain: Float = 0.82f): PlayResult =
        playAsset(path, AudioBus.SFX, gainOverride = gain.coerceIn(0f, 1f), isDoor = true)

    /**
     * R14 winner/GG playback. Per-file gain and reverb come from the semantic registry.
     * The active mechanical door alone is ducked by 3.5 dB, then restored on completion.
     */
    fun playResultVoice(entry: ResultAudioEntry, duckDoorDb: Float = 3.5f): PlayResult = try {
        releaseBus(AudioBus.VOICE)
        releaseResultReverb()
        val afd = context.assets.openFd(entry.assetPath)
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        mp.prepare()
        voicePlayer = mp
        currentVoiceIsBuilding = false

        val attenuation = 10.0.pow((minOf(0f, entry.gainDb) / 20f).toDouble()).toFloat()
        val base = (volume * attenuation).coerceIn(0f, 1f)
        mp.setVolume(base, base)
        if (entry.gainDb > 0f) {
            runCatching {
                voiceEnhancer = LoudnessEnhancer(mp.audioSessionId).apply {
                    setTargetGain((entry.gainDb * 100f).roundToInt())
                    enabled = true
                }
            }
        }
        if (entry.reverbMs > 0) {
            runCatching {
                resultReverb = EnvironmentalReverb(0, 0).apply {
                    decayTime = entry.reverbMs.coerceIn(450, 700)
                    reflectionsDelay = 18
                    reverbDelay = 34
                    roomLevel = (-1200).toShort()
                    reverbLevel = (-650).toShort()
                    enabled = true
                }
                mp.attachAuxEffect(resultReverb!!.id)
                mp.setAuxEffectSendLevel(0.42f)
            }
        }
        val duck = 10.0.pow((-duckDoorDb / 20f).toDouble()).toFloat()
        doorPlayer?.let { active -> runCatching { active.setVolume(volume * doorBaseGain * duck, volume * doorBaseGain * duck) } }
        fun finish(completed: MediaPlayer) {
            if (voicePlayer === completed) {
                voicePlayer = null
                currentVoiceIsBuilding = false
                releaseVoiceEnhancer()
                releaseResultReverb()
            }
            doorPlayer?.let { active -> runCatching { active.setVolume(volume * doorBaseGain, volume * doorBaseGain) } }
            runCatching { completed.release() }
        }
        mp.setOnCompletionListener(::finish)
        mp.setOnErrorListener { bad, _, _ -> finish(bad); true }
        mp.start()
        PlayResult(true, entry.assetPath)
    } catch (_: Exception) {
        releaseResultReverb()
        PlayResult(false, "Could not play this result audio file")
    }

    fun playUnit(audioId: String, category: String): PlayResult {
        val canonical = if (category == "deploy_move") "move" else category
        attackHandler.removeCallbacksAndMessages(null)

        val voicePaths = unitVoicePaths(audioId, canonical)
        val sfxFolder = attackSfxFolder(audioId)
        val sfxFiles = if (canonical == "attack") {
            listAudioFiles(sfxFolder).filter { file -> poolPathEnabled("$sfxFolder/$file") }
        } else emptyList()

        if (voicePaths.isEmpty() && sfxFiles.isEmpty()) {
            val rawVoice = rawUnitVoicePaths(audioId, canonical)
            val rawSfx = if (canonical == "attack") listAudioFiles(sfxFolder) else emptyList()
            return if (rawVoice.isNotEmpty() || rawSfx.isNotEmpty()) {
                PlayResult(false, "This sound pool is muted — press and hold to manage it")
            } else {
                PlayResult(false, "No $canonical audio in the installed library")
            }
        }

        val voiceChosen = chooseNoRepeat("unit:$audioId:$canonical", voicePaths)
        val sfxChosen = chooseNoRepeat(sfxFolder, sfxFiles)

        var voiceResult: PlayResult? = null
        if (voiceChosen != null) voiceResult = playAsset(voiceChosen, AudioBus.VOICE)

        if (sfxChosen != null) {
            // Weapon effects are intentionally quieter than VO and form a short
            // cadence underneath the unit's attack order.
            val voiceDuration = try { if (voiceResult?.success == true) voicePlayer?.duration ?: 0 else 0 } catch (_: Exception) { 0 }
            if (voiceResult?.success == true && voiceDuration > 0) {
                scheduleAttackSfxChain(audioId, sfxFolder, sfxFiles, voiceDuration)
            } else {
                playAsset("$sfxFolder/$sfxChosen", AudioBus.SFX)
            }
        }

        return when {
            voiceResult?.success == true && sfxChosen != null -> PlayResult(true, "voice + combat SFX")
            voiceResult?.success == true -> voiceResult
            sfxChosen != null -> PlayResult(true, "combat SFX")
            else -> voiceResult ?: PlayResult(false, "Could not play this audio")
        }
    }

    /**
     * Soundboard combat timing follows the character of the in-game weapon rather than
     * giving every unit Marine-speed fire. Zealot is special: one attack cycle is two
     * near-simultaneous arm strikes.
     */
    private data class AttackPattern(val cadenceMs: Long, val burstOffsetsMs: LongArray = longArrayOf(0L))

    private fun attackPattern(audioId: String): AttackPattern = when (audioId) {
        // Rapid / sustained fire
        "marine" -> AttackPattern(560L)
        "jim_raynor" -> AttackPattern(720L)
        "zergling" -> AttackPattern(620L)
        "reaper" -> AttackPattern(760L)
        "hydralisk" -> AttackPattern(820L)
        "viking" -> AttackPattern(850L)
        "phoenix" -> AttackPattern(800L)
        "mutalisk" -> AttackPattern(900L)

        // Zealot's actual attack animation is a two-arm double strike.
        "zealot" -> AttackPattern(1180L, longArrayOf(0L, 88L))
        "zeratul" -> AttackPattern(1220L)

        // Medium weapon periods
        "queen" -> AttackPattern(1040L)
        "kerrigan" -> AttackPattern(1040L)
        "marauder" -> AttackPattern(1120L)
        "ghost" -> AttackPattern(1120L)
        "adept" -> AttackPattern(1200L)
        "sentry" -> AttackPattern(1150L)
        "hellion" -> AttackPattern(1180L)
        "ultralisk" -> AttackPattern(1220L)
        "battlecruiser" -> AttackPattern(1280L)
        "thor" -> AttackPattern(1380L)

        // Slower deliberate fire
        "stalker" -> AttackPattern(1380L)
        "goliath" -> AttackPattern(1450L)
        "roach" -> AttackPattern(1450L)
        "dragoon" -> AttackPattern(1450L)
        "ravager" -> AttackPattern(1580L)
        "immortal" -> AttackPattern(1600L)
        "siege_tank" -> AttackPattern(1750L)
        "colossus" -> AttackPattern(1680L)
        "tempest" -> AttackPattern(1850L)
        "reaver" -> AttackPattern(2050L)
        else -> AttackPattern(1000L)
    }

    private fun scheduleAttackSfxChain(audioId: String, folder: String, files: List<String>, voiceDurationMs: Int) {
        if (files.isEmpty()) return
        val pattern = attackPattern(audioId)
        val cadence = pattern.cadenceMs
        val firstDelay = (cadence / 5L).coerceIn(130L, 340L)
        val usableEnd = (voiceDurationMs - 130).coerceAtLeast(0).toLong()
        var cycleDelay = firstDelay
        var cycles = 0
        while (cycleDelay < usableEnd && cycles < 7) {
            pattern.burstOffsetsMs.forEach { burstOffset ->
                val scheduledAt = cycleDelay + burstOffset
                if (scheduledAt < usableEnd) {
                    attackHandler.postDelayed({
                        val chosen = chooseNoRepeat(folder, files)
                        if (chosen != null) playAsset("$folder/$chosen", AudioBus.SFX)
                    }, scheduledAt)
                }
            }
            val jitter = (cadence * 0.06).toLong().coerceAtLeast(20L)
            cycleDelay += cadence + Random.nextLong(-jitter, jitter + 1L)
            cycles++
        }
        if (cycles == 0 && usableEnd > 0L) {
            pattern.burstOffsetsMs.forEach { burstOffset ->
                val scheduledAt = minOf(firstDelay + burstOffset, usableEnd)
                attackHandler.postDelayed({
                    val chosen = chooseNoRepeat(folder, files)
                    if (chosen != null) playAsset("$folder/$chosen", AudioBus.SFX)
                }, scheduledAt)
            }
        }
    }

    fun playBuilding(buildingId: String): PlayResult {
        attackHandler.removeCallbacksAndMessages(null)
        val primary = listAudioPaths("audio/buildings/$buildingId")
        val classic = listAudioPaths(classicBuildingFolder(buildingId))
        val choices = resolveSoundBank(primary, classic)
        if (choices.isEmpty()) return PlayResult(false, "No building response in the installed library")
        val chosen = chooseNoRepeat("building:$buildingId", choices)
            ?: return PlayResult(false, "No building response in the installed library")
        return playAsset(chosen, AudioBus.VOICE, isBuildingVoice = true)
    }

    private fun chooseNoRepeat(folder: String, files: List<String>): String? {
        if (files.isEmpty()) return null
        val previousName = previous[folder]
        val choices = if (files.size > 1 && previousName != null) files.filterNot { it == previousName } else files
        val chosen = choices.random(Random)
        previous[folder] = chosen
        return chosen
    }

    private fun listAudioFiles(folder: String): List<String> = folderCache.getOrPut(folder) {
        try {
            context.assets.list(folder)
                ?.filter { it.substringAfterLast('.', "").lowercase() in audioExtensions }
                ?.sorted()
                .orEmpty()
        } catch (_: Exception) { emptyList() }
    }

    private fun isSustainedPath(path: String): Boolean =
        path.contains("loop", ignoreCase = true) ||
            path.contains("sustain", ignoreCase = true) ||
            path.contains("ambient", ignoreCase = true)

    private fun beginSustainedAbility(key: String): Boolean {
        if (key in activeSustainedAbilities) return false
        activeSustainedAbilities += key
        return true
    }

    private fun endSustainedAbility(key: String) {
        activeSustainedAbilities.remove(key)
    }

    private fun sustainedTailFadeMs(path: String): Int = when {
        path.contains("guardian_shield", ignoreCase = true) && isSustainedPath(path) -> 720
        isSustainedPath(path) -> 650
        else -> 0
    }

    private fun runSfxCompletionAction(player: MediaPlayer) {
        sfxCompletionActions.remove(player)?.let { action -> runCatching { action() } }
    }

    private fun scheduleTailFade(
        mp: MediaPlayer,
        baseVolume: Float,
        fadeMs: Int,
        preview: Boolean = false
    ) {
        if (fadeMs <= 0) return
        val duration = runCatching { mp.duration }.getOrDefault(0)
        if (duration <= 0) return
        val fade = fadeMs.coerceAtMost((duration * 0.70f).roundToInt().coerceAtLeast(160))
        val startDelay = (duration - fade).coerceAtLeast(0)
        val startedAt = android.os.SystemClock.uptimeMillis() + startDelay
        val step = object : Runnable {
            override fun run() {
                val stillOwned = if (preview) previewPlayer === mp else mp in sfxPlayers
                if (!stillOwned) return
                val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
                val t = (elapsed.toFloat() / fade.toFloat()).coerceIn(0f, 1f)
                // Equal-power-ish cosine release: much smoother than the old stepped linear tail.
                val curve = cos(t.toDouble() * PI / 2.0).toFloat().coerceIn(0f, 1f)
                val out = (baseVolume * curve).coerceIn(0f, 1f)
                runCatching { mp.setVolume(out, out) }
                if (t < 1f) tailFadeHandler.postDelayed(this, 16L)
            }
        }
        tailFadeHandler.postDelayed(step, startDelay.toLong())
    }

    private fun playAsset(
        path: String,
        bus: AudioBus,
        isBuildingVoice: Boolean = false,
        gainOverride: Float? = null,
        isDoor: Boolean = false,
        onFinished: (() -> Unit)? = null
    ): PlayResult = try {
        if (bus == AudioBus.VOICE) releaseBus(AudioBus.VOICE)
        val afd = context.assets.openFd(path)
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(if (bus == AudioBus.VOICE) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        mp.setOnCompletionListener { completed ->
            if (bus == AudioBus.VOICE && voicePlayer === completed) {
                voicePlayer = null
                currentVoiceIsBuilding = false
                releaseVoiceEnhancer()
            }
            if (bus == AudioBus.SFX) {
                sfxPlayers.remove(completed)
                runSfxCompletionAction(completed)
            }
            if (doorPlayer === completed) doorPlayer = null
            try { completed.release() } catch (_: Exception) {}
        }
        mp.setOnErrorListener { bad, _, _ ->
            if (bus == AudioBus.VOICE && voicePlayer === bad) {
                voicePlayer = null
                currentVoiceIsBuilding = false
                releaseVoiceEnhancer()
            }
            if (bus == AudioBus.SFX) {
                sfxPlayers.remove(bad)
                runSfxCompletionAction(bad)
            }
            if (doorPlayer === bad) doorPlayer = null
            try { bad.release() } catch (_: Exception) {}
            true
        }
        mp.prepare()
        if (bus == AudioBus.VOICE) {
            voicePlayer = mp
            currentVoiceIsBuilding = isBuildingVoice
            applyCurrentVoiceVolume()
        } else {
            val sfxVolume = (volume * (gainOverride ?: weaponSfxGain)).coerceIn(0f, 1f)
            mp.setVolume(sfxVolume, sfxVolume)
            // Keep short attack transients concurrent so double-strikes and overlapping
            // weapon tails do not cut each other off.
            if (sfxPlayers.size >= 10) {
                val oldest = sfxPlayers.firstOrNull()
                oldest?.let { old ->
                    sfxPlayers.remove(old)
                    runSfxCompletionAction(old)
                    try { old.stop() } catch (_: Exception) {}
                    try { old.release() } catch (_: Exception) {}
                }
            }
            sfxPlayers.add(mp)
            if (onFinished != null) sfxCompletionActions[mp] = onFinished
            if (isDoor) {
                doorPlayer = mp
                doorBaseGain = gainOverride ?: weaponSfxGain
            }
        }
        mp.start()
        if (bus == AudioBus.SFX) {
            val base = (volume * (gainOverride ?: weaponSfxGain)).coerceIn(0f, 1f)
            scheduleTailFade(mp, base, sustainedTailFadeMs(path))
        }
        PlayResult(true, path)
    } catch (_: Exception) {
        PlayResult(false, "Could not play this audio file")
    }

    private fun releaseVoiceEnhancer() {
        voiceEnhancer?.let { effect ->
            try { effect.enabled = false } catch (_: Exception) {}
            try { effect.release() } catch (_: Exception) {}
        }
        voiceEnhancer = null
    }

    private fun releaseResultReverb() {
        resultReverb?.let { effect ->
            try { effect.enabled = false } catch (_: Exception) {}
            try { effect.release() } catch (_: Exception) {}
        }
        resultReverb = null
    }

    private fun applyCurrentVoiceVolume() {
        val active = voicePlayer ?: return
        releaseVoiceEnhancer()
        val desired = if (currentVoiceIsBuilding) volume * buildingGain else volume
        val base = desired.coerceIn(0f, 1f)
        try { active.setVolume(base, base) } catch (_: Exception) {}

        // MediaPlayer itself caps volume at 1.0. For the small 100-120% building-only
        // headroom, use Android's session LoudnessEnhancer. If a device/engine does not
        // expose the effect, playback safely falls back to 100% rather than distorting.
        if (currentVoiceIsBuilding && desired > 1f) {
            try {
                val gainMb = (2000.0 * log10(desired.toDouble())).roundToInt().coerceAtLeast(0)
                voiceEnhancer = LoudnessEnhancer(active.audioSessionId).apply {
                    setTargetGain(gainMb)
                    enabled = true
                }
            } catch (_: Exception) {
                voiceEnhancer = null
            }
        }
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        applyCurrentVoiceVolume()
        applyLaunchAmbienceVolume()
        val sfxVolume = (volume * weaponSfxGain).coerceIn(0f, 1f)
        sfxPlayers.toList().forEach { active -> try { active.setVolume(sfxVolume, sfxVolume) } catch (_: Exception) {} }
    }

    fun setBuildingGain(value: Float) {
        buildingGain = value.coerceIn(0f, 1.2f)
        if (currentVoiceIsBuilding) applyCurrentVoiceVolume()
    }

    fun setWeaponSfxGain(value: Float) {
        weaponSfxGain = value.coerceIn(0f, 1f)
        val sfxVolume = (volume * weaponSfxGain).coerceIn(0f, 1f)
        sfxPlayers.toList().forEach { active -> try { active.setVolume(sfxVolume, sfxVolume) } catch (_: Exception) {} }
    }

    fun volume(): Float = volume
    fun weaponSfxGain(): Float = weaponSfxGain
    fun buildingGain(): Float = buildingGain
    fun currentVoiceDurationMs(): Int = try { voicePlayer?.duration?.coerceAtLeast(0) ?: 0 } catch (_: Exception) { 0 }

    fun stop() {
        attackHandler.removeCallbacksAndMessages(null)
        abilityHandler.removeCallbacksAndMessages(null)
        tailFadeHandler.removeCallbacksAndMessages(null)
        previewFadeHandler.removeCallbacksAndMessages(null)
        launchAmbienceFadeHandler.removeCallbacksAndMessages(null)
        stopPreview(immediate = true)
        stopLaunchAmbience()
        releaseBus(AudioBus.VOICE)
        releaseBus(AudioBus.SFX)
        activeSustainedAbilities.clear()
    }

    private fun releaseBus(bus: AudioBus) {
        if (bus == AudioBus.VOICE) {
            voicePlayer?.let {
                try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            voicePlayer = null
            currentVoiceIsBuilding = false
            releaseVoiceEnhancer()
            releaseResultReverb()
        } else {
            sfxPlayers.toList().forEach { active ->
                runSfxCompletionAction(active)
                try { if (active.isPlaying) active.stop() } catch (_: Exception) {}
                try { active.release() } catch (_: Exception) {}
            }
            sfxPlayers.clear()
            sfxCompletionActions.clear()
            doorPlayer = null
        }
    }

    private enum class AudioBus { VOICE, SFX }
}

data class PlayResult(val success: Boolean, val message: String)
