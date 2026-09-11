package com.sc2tmg.soundboard

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import java.util.Locale

enum class AnnouncerMode(val label: String) {
    FEMALE("VOICE F"),
    MALE("VOICE M"),
    TERRAN("ADJUTANT"),
    OFF("VOICE OFF")
}

/** Optional table-side announcer with generic TTS voices plus the bundled Terran-style voice set. */
class PhaseAnnouncer(context: Context, private val onActiveChanged: (Boolean) -> Unit = {}) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var defaultTtsVoiceName: String? = null
    private var pendingText: Pair<String, AnnouncerMode>? = null
    private var customPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var active = false

    private fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        mainHandler.post { onActiveChanged(value) }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.ENGLISH
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { setActive(true) }
                    override fun onDone(utteranceId: String?) { setActive(false) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { setActive(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) { setActive(false) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { setActive(false) }
                })
                defaultTtsVoiceName = try { tts?.voice?.name } catch (_: Exception) { null }
                pendingText?.let { (text, mode) -> speakGeneric(text, mode) }
                pendingText = null
            }
        }
    }

    fun isActive(): Boolean = active

    fun announceRoundPhase(round: Int, phase: Int, mode: AnnouncerMode) {
        if (mode == AnnouncerMode.OFF) return
        val phaseText = when (phase) {
            0 -> "Movement phase"
            1 -> "Assault phase"
            2 -> "Combat phase"
            else -> "Scoring and cleanup phase"
        }
        if (mode == AnnouncerMode.TERRAN) {
            val phaseFile = when (phase) {
                0 -> "Movement.ogg"
                1 -> "Assault.ogg"
                2 -> "Combat.ogg"
                else -> "Scoring clean.ogg"
            }
            // Round number belongs to the start-of-round Movement announcement only.
            // Later phases announce just their phase name, matching the generic voices.
            val files = buildList {
                if (phase == 0 && round in 1..10) add("Round $round.ogg")
                add(phaseFile)
            }
            playTerranSequence(files)
        } else {
            val text = if (phase == 0) "Round $round. $phaseText." else "$phaseText."
            speakGeneric(text, mode)
        }
    }

    fun announceVictory(faction: Faction?, mode: AnnouncerMode) {
        if (mode == AnnouncerMode.OFF || faction == null) return
        if (mode == AnnouncerMode.TERRAN) {
            val file = when (faction) {
                Faction.TERRAN -> "Terran vic.ogg"
                Faction.PROTOSS -> "Protoss vic.ogg"
                Faction.ZERG, Faction.HYBRID -> "Zerg Victory.ogg"
            }
            playTerranSequence(listOf(file))
        } else {
            speakGeneric("${faction.label} victory.", mode)
        }
    }

    fun preview(mode: AnnouncerMode, round: Int, phase: Int) {
        stopCurrent()
        announceRoundPhase(round, phase, mode)
    }

    fun silence() { stopCurrent() }

    private fun speakGeneric(text: String, mode: AnnouncerMode) {
        if (mode == AnnouncerMode.OFF || mode == AnnouncerMode.TERRAN) return
        stopCustom()
        if (!ready) {
            pendingText = text to mode
            return
        }
        configureGenericVoice(mode)
        val pitch = if (mode == AnnouncerMode.MALE) 0.91f else 1.05f
        val rate = if (mode == AnnouncerMode.MALE) 0.91f else 0.92f
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
        setActive(true)
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sc2_tmg_phase")
        if (result == TextToSpeech.ERROR) setActive(false)
    }


    /**
     * Android exposes installed TTS voices but no portable gender flag. Prefer an explicitly
     * male-labelled local English voice when the engine supplies one. If the engine uses opaque
     * voice names, choose a distinct local English voice rather than merely pitch-shifting the
     * female default. Pitch remains only a small finishing adjustment.
     */
    private fun configureGenericVoice(mode: AnnouncerMode) {
        val engine = tts ?: return
        if (mode != AnnouncerMode.FEMALE && mode != AnnouncerMode.MALE) return
        val voices = try {
            engine.voices?.filter { it.locale.language.equals("en", ignoreCase = true) } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        if (voices.isEmpty()) return

        fun voiceText(v: android.speech.tts.Voice): String =
            (v.name + " " + v.features.joinToString(" ")).lowercase(Locale.ROOT)

        fun maleScore(v: android.speech.tts.Voice): Int {
            val raw = voiceText(v)
            val text = raw.replace("female", "")
            var score = 0
            if ("male" in text) score += 300
            if ("masculine" in text) score += 300
            if (Regex("(^|[^a-z])m(ale)?[_# -]?[0-9]*([^a-z]|$)").containsMatchIn(text)) score += 120
            if (!v.isNetworkConnectionRequired) score += 30
            score += v.quality
            return score
        }

        fun femaleScore(v: android.speech.tts.Voice): Int {
            val text = voiceText(v)
            var score = 0
            if ("female" in text || "feminine" in text) score += 300
            if (!v.isNetworkConnectionRequired) score += 30
            if (v.name == defaultTtsVoiceName) score += 180 // restore the known-good default female voice
            score += v.quality
            return score
        }

        val current = engine.voice
        val defaultVoice = voices.firstOrNull { it.name == defaultTtsVoiceName } ?: current
        val chosen = if (mode == AnnouncerMode.MALE) {
            val explicit = voices.maxByOrNull(::maleScore)
            val explicitText = explicit?.let(::voiceText).orEmpty().replace("female", "")
            if (explicit != null && ("male" in explicitText || "masculine" in explicitText)) {
                explicit
            } else {
                // Opaque engine names: deliberately choose another local English voice if available.
                voices.filter { it.name != defaultTtsVoiceName && !it.isNetworkConnectionRequired }
                    .maxByOrNull { it.quality }
                    ?: voices.filter { it.name != defaultTtsVoiceName }.maxByOrNull { it.quality }
                    ?: current
            }
        } else {
            defaultVoice ?: voices.maxByOrNull(::femaleScore) ?: current
        }
        if (chosen != null) {
            try { engine.voice = chosen } catch (_: Exception) {}
        }
    }

    private fun playTerranSequence(files: List<String>) {
        stopCurrent()
        if (files.isEmpty()) return
        setActive(true)
        playTerranAt(files, 0)
    }

    private fun playTerranAt(files: List<String>, index: Int) {
        if (index !in files.indices) {
            setActive(false)
            return
        }
        val path = "audio/announcer/terran/${files[index]}"
        try {
            val afd = appContext.assets.openFd(path)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnCompletionListener { done ->
                if (customPlayer === done) customPlayer = null
                try { done.release() } catch (_: Exception) {}
                playTerranAt(files, index + 1)
            }
            mp.setOnErrorListener { bad, _, _ ->
                if (customPlayer === bad) customPlayer = null
                try { bad.release() } catch (_: Exception) {}
                playTerranAt(files, index + 1)
                true
            }
            mp.prepare()
            customPlayer = mp
            mp.start()
        } catch (_: Exception) {
            playTerranAt(files, index + 1)
        }
    }

    private fun stopCustom() {
        customPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        customPlayer = null
    }

    private fun stopCurrent() {
        try { tts?.stop() } catch (_: Exception) {}
        stopCustom()
        setActive(false)
    }

    fun release() {
        stopCurrent()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
