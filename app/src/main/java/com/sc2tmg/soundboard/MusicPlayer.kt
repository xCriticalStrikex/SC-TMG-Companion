package com.sc2tmg.soundboard

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlin.random.Random

/**
 * Separate long-form music deck. It intentionally does not share a MediaPlayer with VO/SFX,
 * so voice lines can fire over the soundtrack and each bus has its own volume.
 *
 * Expected bundled asset layout:
 * audio/music/sc1/terran/<track>.(ogg|mp3|wav|m4a)
 * audio/music/sc1/protoss/<tracks>
 * audio/music/sc1/zerg/<tracks>
 * audio/music/sc2/terran/<tracks>
 * audio/music/sc2/protoss/<tracks>
 * audio/music/sc2/zerg/<tracks>
 */
class MusicPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var menuPlayer: MediaPlayer? = null
    private var fadingOutPlayer: MediaPlayer? = null
    private val audioExtensions = setOf("ogg", "mp3", "wav", "m4a")
    private var enabledFactions: Set<Faction> = setOf(Faction.TERRAN, Faction.PROTOSS, Faction.ZERG)
    private var remainingShuffle = mutableListOf<MusicTrack>()
    private var previousEligibilityKey = ""
    // Result music has its own per-faction shuffle bag. It deliberately survives endResult(),
    // so opening several victory screens does not keep rerolling the same first track.
    private val victoryRemaining = mutableMapOf<Faction, MutableList<MusicTrack>>()
    private val victoryEligibilityKey = mutableMapOf<Faction, String>()
    private val lastVictoryPath = mutableMapOf<Faction, String>()
    private val history = mutableListOf<MusicTrack>()
    private var historyIndex = -1
    private var current: MusicTrack? = null
    private var paused = false
    private var volume = 0.62f
    private var duckingPower = 0.55f
    private var duckMultiplier = 1.0f
    private var duckHoldCount = 0
    private var previewMuteHoldCount = 0
    private var timedDuckActive = false
    private var timedDuckMultiplier = 1.0f
    private val duckHandler = Handler(Looper.getMainLooper())
    private val fadeHandler = Handler(Looper.getMainLooper())
    private val previewDuckHandler = Handler(Looper.getMainLooper())
    private val menuFadeHandler = Handler(Looper.getMainLooper())
    private var appliedDuckMultiplier = 1.0f

    private var resultMode = false
    private var resultRestoreFactions: Set<Faction> = enabledFactions

    // R18 keeps front-end music completely separate from Jimmy's Jukebox. The match player is
    // paused (not destroyed) when Setup/Home borrows the bus, so RESUME GAME can return to the
    // exact same soundtrack position. Starting a new game deliberately discards that snapshot.
    private var menuCurrentPath: String? = null
    private var menuRemainingShuffle = mutableListOf<String>()
    private var menuPreviousPath: String? = null
    private var matchPausedForMenu = false

    private val allTracks: List<MusicTrack> by lazy { scanTracks() }
    private val menuTracks: List<String> by lazy { scanMenuTracks() }

    fun setEnabledFactions(factions: Set<Faction>, crossFadeMs: Int = 0) {
        val sanitized = factions.filterTo(linkedSetOf()) { it != Faction.HYBRID }
        if (resultMode) {
            // Victory/result playback temporarily owns the live filter, but remember
            // any match-faction change so it is restored when the result closes.
            resultRestoreFactions = sanitized
            return
        }
        if (enabledFactions != sanitized) {
            val wasPlaying = isPlaying()
            enabledFactions = sanitized
            remainingShuffle.clear()
            previousEligibilityKey = ""

            // If the currently playing faction is switched off, leave it immediately.
            // With no factions enabled there is deliberately no eligible music.
            val playingTrack = current
            if (playingTrack != null && playingTrack.faction !in enabledFactions) {
                if (enabledFactions.isEmpty()) {
                    stop()
                } else if (wasPlaying) {
                    playNewTrack(addToHistory = true, crossFadeMs = crossFadeMs)
                }
            }
        }
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        applyOutputVolume()
    }

    /** Set how much music is reduced under VO: 0 = no ducking, 0.90 = 90% reduction. */
    fun setDuckingPower(value: Float) {
        duckingPower = value.coerceIn(0f, 0.90f)
        refreshDuckMultiplier()
    }

    /** Held duck used by the phase announcer while its clip is actually active. */
    fun beginDuck() {
        duckHoldCount += 1
        refreshDuckMultiplier()
    }

    fun endDuck() {
        duckHoldCount = (duckHoldCount - 1).coerceAtLeast(0)
        refreshDuckMultiplier()
    }

    /** Audio-inspection dialogs need a true mute, independent of the normal ducking slider. */
    fun beginPreviewMute() {
        val wasAlreadyMuted = previewMuteHoldCount > 0
        previewMuteHoldCount += 1
        refreshDuckMultiplier(if (wasAlreadyMuted) 0 else 160)
    }

    fun endPreviewMute() {
        previewMuteHoldCount = (previewMuteHoldCount - 1).coerceAtLeast(0)
        refreshDuckMultiplier(if (previewMuteHoldCount == 0) 340 else 0)
    }

    /** Temporarily lower the soundtrack under a unit voice line or combat stinger. */
    fun duckFor(multiplier: Float = 1f - duckingPower, durationMs: Int) {
        if (durationMs <= 0) return
        timedDuckActive = true
        timedDuckMultiplier = multiplier.coerceIn(0.10f, 1.0f)
        duckHandler.removeCallbacksAndMessages(null)
        refreshDuckMultiplier()
        duckHandler.postDelayed({
            timedDuckActive = false
            timedDuckMultiplier = 1.0f
            refreshDuckMultiplier()
        }, durationMs.toLong())
    }

    private fun refreshDuckMultiplier(transitionMs: Int = 0) {
        val configured = (1f - duckingPower).coerceIn(0.10f, 1.0f)
        val target = when {
            previewMuteHoldCount > 0 -> 0.0f
            duckHoldCount > 0 && timedDuckActive -> minOf(configured, timedDuckMultiplier)
            duckHoldCount > 0 -> configured
            timedDuckActive -> timedDuckMultiplier
            else -> 1.0f
        }
        duckMultiplier = target
        if (transitionMs > 0 && (player != null || menuPlayer != null)) {
            animatePreviewDuckTo(target, transitionMs)
        } else {
            applyOutputVolume()
        }
    }

    private fun animatePreviewDuckTo(target: Float, durationMs: Int) {
        previewDuckHandler.removeCallbacksAndMessages(null)
        val from = appliedDuckMultiplier
        val duration = durationMs.coerceAtLeast(1)
        val startedAt = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
                val t = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)
                appliedDuckMultiplier = from + (target - from) * eased
                val out = (volume * appliedDuckMultiplier).coerceIn(0f, 1f)
                try { player?.setVolume(out, out) } catch (_: Exception) {}
                try { menuPlayer?.setVolume(out, out) } catch (_: Exception) {}
                if (t < 1f) {
                    previewDuckHandler.postDelayed(this, 16L)
                } else {
                    appliedDuckMultiplier = target
                }
            }
        }
        previewDuckHandler.post(step)
    }

    private fun applyOutputVolume() {
        previewDuckHandler.removeCallbacksAndMessages(null)
        appliedDuckMultiplier = duckMultiplier
        val out = (volume * appliedDuckMultiplier).coerceIn(0f, 1f)
        try { player?.setVolume(out, out) } catch (_: Exception) {}
        try { menuPlayer?.setVolume(out, out) } catch (_: Exception) {}
    }

    fun volume(): Float = volume
    fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (_: Exception) { false }
    fun isPaused(): Boolean = paused && current != null
    fun currentTrack(): MusicTrack? = current
    fun durationMs(): Int = try { player?.duration?.coerceAtLeast(0) ?: 0 } catch (_: Exception) { 0 }
    fun positionMs(): Int = try { player?.currentPosition?.coerceAtLeast(0) ?: 0 } catch (_: Exception) { 0 }

    fun totalTrackCount(): Int = allTracks.size
    fun trackCount(faction: Faction): Int = allTracks.count { it.faction == faction }
    fun trackCount(game: String, faction: Faction): Int = allTracks.count { it.game.equals(game, ignoreCase = true) && it.faction == faction }
    fun menuTrackCount(): Int = menuTracks.size
    fun isMenuPlaying(): Boolean = try { menuPlayer?.isPlaying == true } catch (_: Exception) { false }

    /**
     * Enter Welcome / Game Setup music without destroying an active match soundtrack. If a match
     * is currently playing it crossfades down and remains paused at its live position underneath
     * the menu deck. Navigating between Welcome and Setup simply keeps the same menu deck running.
     */
    fun playMenuOrResume(crossFadeMs: Int = 900, fadeInMs: Int = 0): PlayResult {
        val existingMenu = menuPlayer
        if (existingMenu != null && menuCurrentPath != null) {
            return try {
                if (!existingMenu.isPlaying) existingMenu.start()
                PlayResult(true, menuCurrentPath ?: "menu")
            } catch (_: Exception) {
                releaseMenuPlayer()
                playNextMenu(crossFadeMs, fadeInMs)
            }
        }
        return playNextMenu(crossFadeMs, fadeInMs)
    }

    /**
     * A confirmed new-game setup owns no old soundtrack. Fade the title deck away and discard any
     * paused previous-match player so Jimmy's Jukebox remains silent until START GAME completes.
     */
    fun stopMenuForNewGame(fadeMs: Int = 650) {
        matchPausedForMenu = false
        releasePlayerOnly()
        current = null
        paused = false
        remainingShuffle.clear()
        previousEligibilityKey = ""
        history.clear()
        historyIndex = -1
        fadeOutMenuAndRelease(fadeMs)
    }

    /** Stop only the front-end deck while preserving any paused active-match soundtrack. */
    fun stopMenuOnly(fadeMs: Int = 340) {
        fadeOutMenuAndRelease(fadeMs)
    }

    /** Return from Setup to the preserved active game soundtrack. */
    fun resumeMatchFromMenu(crossFadeMs: Int = 900): PlayResult {
        val mp = player
        val track = current
        if (mp != null && track != null && track.faction in enabledFactions) {
            return try {
                val menu = menuPlayer
                val target = (volume * duckMultiplier).coerceIn(0f, 1f)
                if (menu != null && crossFadeMs > 0) {
                    mp.setVolume(0f, 0f)
                    mp.start()
                    paused = false
                    matchPausedForMenu = false
                    crossFadeMenuToMatch(menu, mp, crossFadeMs, target)
                } else {
                    releaseMenuPlayer()
                    mp.setVolume(target, target)
                    mp.start()
                    paused = false
                    matchPausedForMenu = false
                }
                PlayResult(true, track.path)
            } catch (_: Exception) {
                releaseMenuPlayer()
                releasePlayerOnly()
                current = null
                paused = false
                matchPausedForMenu = false
                playNewTrack(addToHistory = true)
            }
        }
        releaseMenuPlayer()
        matchPausedForMenu = false
        return playNewTrack(addToHistory = true)
    }

    /** Begin a new committed match after the pre-match ceremony has finished. */
    fun startFreshMatch(): PlayResult {
        releaseMenuPlayer()
        matchPausedForMenu = false
        releasePlayerOnly()
        current = null
        paused = false
        remainingShuffle.clear()
        previousEligibilityKey = ""
        return playNewTrack(addToHistory = true)
    }

    fun playOrResume(): PlayResult {
        val existing = player
        val selectedTrack = current
        if (existing != null && selectedTrack != null && selectedTrack.faction in enabledFactions) {
            return try {
                existing.start()
                paused = false
                PlayResult(true, selectedTrack.path)
            } catch (_: Exception) {
                releasePlayerOnly()
                playNewTrack(addToHistory = true)
            }
        }
        if (selectedTrack != null && selectedTrack.faction !in enabledFactions) {
            releasePlayerOnly()
            current = null
            paused = false
        }
        return playNewTrack(addToHistory = true)
    }

    fun pause() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) mp.pause()
            paused = current != null
        } catch (_: Exception) {}
    }

    fun toggle(): PlayResult = if (isPlaying()) {
        pause()
        PlayResult(true, "paused")
    } else {
        playOrResume()
    }

    fun next(): PlayResult {
        // Next always respects the currently enabled faction pool. This avoids a disabled
        // faction reappearing through old forward-history entries.
        return playNewTrack(addToHistory = true)
    }

    fun previous(): PlayResult {
        if (history.isEmpty() || historyIndex <= 0) return PlayResult(false, "No earlier music track")
        var candidate = historyIndex - 1
        while (candidate >= 0 && history[candidate].faction !in enabledFactions) candidate--
        if (candidate < 0) return PlayResult(false, "No earlier track from an enabled faction")
        historyIndex = candidate
        return playTrack(history[historyIndex], addToHistory = false)
    }

    /**
     * Temporarily hand the music bus to a result screen without selecting ordinary soundtrack
     * music. R11 result audio uses dedicated UI victory/draw stingers instead of pretending a
     * random faction multiplayer track is a victory theme.
     */
    fun beginResult() {
        if (!resultMode) resultRestoreFactions = enabledFactions
        resultMode = true
        releaseMenuPlayer()
        matchPausedForMenu = false
        remainingShuffle.clear()
        previousEligibilityKey = ""
        releasePlayerOnly()
        current = null
        paused = false
    }

    /**
     * Play one audited long-form result asset on the MUSIC bus. This is deliberately separate
     * from SoundPlayer's concurrent UI/SFX bus, so a Victory Panel stinger can overlay the result
     * music instead of replacing it. Result assets do not auto-advance into the normal jukebox.
     */
    fun playResultAsset(path: String): PlayResult = try {
        if (!resultMode) beginResult()
        releasePlayerOnly()
        val afd = context.assets.openFd(path)
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        val out = (volume * duckMultiplier).coerceIn(0f, 1f)
        mp.setVolume(out, out)
        mp.setOnCompletionListener { completed ->
            if (player === completed) player = null
            try { completed.release() } catch (_: Exception) {}
            current = null
            paused = false
        }
        mp.setOnErrorListener { bad, _, _ ->
            if (player === bad) player = null
            try { bad.release() } catch (_: Exception) {}
            current = null
            paused = false
            true
        }
        mp.prepare()
        player = mp
        current = null
        paused = false
        mp.start()
        PlayResult(true, path)
    } catch (_: Exception) {
        releasePlayerOnly()
        current = null
        paused = false
        PlayResult(false, "Could not play this result music")
    }

    /**
     * Legacy compatibility: explicit callers may still ask for winner-faction soundtrack music.
     * The R11 result screen itself no longer uses this path; it uses beginResult() + audited
     * result stingers.
     */
    fun playVictory(winner: Faction): PlayResult {
        if (winner == Faction.HYBRID) return PlayResult(false, "No victory music for Hybrid")
        if (!resultMode) resultRestoreFactions = enabledFactions
        resultMode = true
        enabledFactions = setOf(winner)
        remainingShuffle.clear()
        previousEligibilityKey = ""
        return playVictoryTrack(winner)
    }

    private fun playVictoryTrack(winner: Faction): PlayResult {
        val eligible = eligibleTracks().filter { it.faction == winner }
        if (eligible.isEmpty()) return PlayResult(false, "No victory music for ${winner.label}")

        val signature = eligible.map { it.path }.sorted().joinToString("|")
        var bag = victoryRemaining[winner]
        if (bag == null || bag.isEmpty() || victoryEligibilityKey[winner] != signature) {
            bag = eligible.shuffled(Random).toMutableList()
            val last = lastVictoryPath[winner]
            if (bag.size > 1 && bag.firstOrNull()?.path == last) {
                val swap = bag.indexOfFirst { it.path != last }
                if (swap > 0) {
                    val tmp = bag[0]
                    bag[0] = bag[swap]
                    bag[swap] = tmp
                }
            }
            victoryRemaining[winner] = bag
            victoryEligibilityKey[winner] = signature
        }
        val next = bag.removeAt(0)
        lastVictoryPath[winner] = next.path
        return playTrack(next, addToHistory = false)
    }

    /** Leave temporary victory playback and optionally resume the normal soundtrack. */
    fun endResult(resumeSoundtrack: Boolean) {
        if (!resultMode) return
        resultMode = false
        enabledFactions = resultRestoreFactions
        remainingShuffle.clear()
        previousEligibilityKey = ""
        releasePlayerOnly()
        current = null
        paused = false
        if (resumeSoundtrack && enabledFactions.isNotEmpty()) playNewTrack(addToHistory = true)
    }

    fun stop() {
        duckHandler.removeCallbacksAndMessages(null)
        fadeHandler.removeCallbacksAndMessages(null)
        previewDuckHandler.removeCallbacksAndMessages(null)
        menuFadeHandler.removeCallbacksAndMessages(null)
        duckHoldCount = 0
        previewMuteHoldCount = 0
        timedDuckActive = false
        timedDuckMultiplier = 1.0f
        duckMultiplier = 1.0f
        appliedDuckMultiplier = 1.0f
        releasePlayerOnly()
        releaseMenuPlayer()
        current = null
        paused = false
        matchPausedForMenu = false
    }

    fun release() = stop()

    private fun playNewTrack(addToHistory: Boolean, crossFadeMs: Int = 0): PlayResult {
        val eligible = eligibleTracks()
        if (eligible.isEmpty()) {
            return PlayResult(false, if (allTracks.isEmpty()) "No SC1/SC2 music tracks are installed in this build" else "Enable at least one music faction")
        }

        val key = eligible.map { it.path }.sorted().joinToString("|")
        if (remainingShuffle.isEmpty() || key != previousEligibilityKey) {
            previousEligibilityKey = key
            remainingShuffle = eligible.shuffled(Random).toMutableList()
            val currentPath = current?.path
            if (remainingShuffle.size > 1 && remainingShuffle.firstOrNull()?.path == currentPath) {
                val swap = remainingShuffle.indexOfFirst { it.path != currentPath }
                if (swap > 0) {
                    val temp = remainingShuffle[0]
                    remainingShuffle[0] = remainingShuffle[swap]
                    remainingShuffle[swap] = temp
                }
            }
        }

        val next = remainingShuffle.removeAt(0)
        return playTrack(next, addToHistory, crossFadeMs)
    }

    private fun playTrack(track: MusicTrack, addToHistory: Boolean, crossFadeMs: Int = 0): PlayResult = try {
        val previousPlayer = player
        val shouldCrossFade = crossFadeMs > 0 && previousPlayer != null && try { previousPlayer.isPlaying } catch (_: Exception) { false }
        if (!shouldCrossFade) releasePlayerOnly()
        val afd = context.assets.openFd(track.path)
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        val targetVolume = (volume * duckMultiplier).coerceIn(0f, 1f)
        val initialVolume = if (shouldCrossFade) 0f else targetVolume
        mp.setVolume(initialVolume, initialVolume)
        mp.setOnCompletionListener { completed ->
            if (player === completed) {
                player = null
                try { completed.release() } catch (_: Exception) {}
                paused = false
                // Completion advances through the appropriate shuffle bag. Result mode keeps
                // using the persistent winner-specific bag rather than falling back to a fresh roll.
                val resultFaction = enabledFactions.singleOrNull()
                if (resultMode && resultFaction != null && resultFaction != Faction.HYBRID) {
                    playVictoryTrack(resultFaction)
                } else {
                    playNewTrack(addToHistory = true)
                }
            } else {
                try { completed.release() } catch (_: Exception) {}
            }
        }
        mp.setOnErrorListener { bad, _, _ ->
            if (player === bad) player = null
            try { bad.release() } catch (_: Exception) {}
            paused = false
            true
        }
        mp.prepare()
        player = mp
        current = track
        paused = false
        if (addToHistory) {
            if (historyIndex < history.lastIndex) history.subList(historyIndex + 1, history.size).clear()
            history += track
            if (history.size > 40) history.removeAt(0)
            historyIndex = history.lastIndex
        }
        mp.start()
        if (shouldCrossFade && previousPlayer != null) {
            beginCrossFade(previousPlayer, mp, crossFadeMs, targetVolume)
        }
        PlayResult(true, track.path)
    } catch (_: Exception) {
        releasePlayerOnly()
        PlayResult(false, "Could not play this music track")
    }

    private fun beginCrossFade(oldPlayer: MediaPlayer, newPlayer: MediaPlayer, durationMs: Int, targetVolume: Float) {
        val duration = durationMs.coerceIn(250, 5000)
        fadingOutPlayer?.let { stale ->
            if (stale !== oldPlayer) {
                try { stale.stop() } catch (_: Exception) {}
                try { stale.release() } catch (_: Exception) {}
            }
        }
        fadingOutPlayer = oldPlayer

        val startedAt = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (player !== newPlayer || fadingOutPlayer !== oldPlayer) {
                    try { oldPlayer.stop() } catch (_: Exception) {}
                    try { oldPlayer.release() } catch (_: Exception) {}
                    if (fadingOutPlayer === oldPlayer) fadingOutPlayer = null
                    return
                }

                val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
                val t = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val liveTarget = (volume * duckMultiplier).coerceIn(0f, 1f)
                try { newPlayer.setVolume(liveTarget * t, liveTarget * t) } catch (_: Exception) {}
                try { oldPlayer.setVolume(targetVolume * (1f - t), targetVolume * (1f - t)) } catch (_: Exception) {}

                if (t >= 1f) {
                    try { oldPlayer.stop() } catch (_: Exception) {}
                    try { oldPlayer.release() } catch (_: Exception) {}
                    if (fadingOutPlayer === oldPlayer) fadingOutPlayer = null
                    applyOutputVolume()
                } else {
                    fadeHandler.postDelayed(this, 40L)
                }
            }
        }
        fadeHandler.removeCallbacksAndMessages(null)
        fadeHandler.post(step)
    }

    private fun playNextMenu(crossFadeMs: Int, fadeInMs: Int = 0): PlayResult {
        if (menuTracks.isEmpty()) return PlayResult(false, "No title/menu music is installed in this build")
        if (menuRemainingShuffle.isEmpty()) {
            menuRemainingShuffle = menuTracks.shuffled(Random).toMutableList()
            if (menuRemainingShuffle.size > 1 && menuRemainingShuffle.firstOrNull() == menuPreviousPath) {
                val swap = menuRemainingShuffle.indexOfFirst { it != menuPreviousPath }
                if (swap > 0) {
                    val tmp = menuRemainingShuffle[0]
                    menuRemainingShuffle[0] = menuRemainingShuffle[swap]
                    menuRemainingShuffle[swap] = tmp
                }
            }
        }
        val path = menuRemainingShuffle.removeAt(0)
        return playMenuTrack(path, crossFadeMs, fadeInMs)
    }

    private fun playMenuTrack(path: String, crossFadeMs: Int, fadeInMs: Int = 0): PlayResult = try {
        val oldMenu = menuPlayer
        if (oldMenu != null) {
            try { oldMenu.stop() } catch (_: Exception) {}
            try { oldMenu.release() } catch (_: Exception) {}
            menuPlayer = null
        }

        val match = player
        val matchWasPlaying = try { match?.isPlaying == true } catch (_: Exception) { false }
        val shouldCrossFade = crossFadeMs > 0 && match != null && matchWasPlaying
        val shouldFadeInFromSilence = !shouldCrossFade && fadeInMs > 0
        if (shouldCrossFade) matchPausedForMenu = true

        val afd = context.assets.openFd(path)
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        val target = (volume * duckMultiplier).coerceIn(0f, 1f)
        val initial = if (shouldCrossFade || shouldFadeInFromSilence) 0f else target
        mp.setVolume(initial, initial)
        mp.setOnCompletionListener { completed ->
            if (menuPlayer === completed) menuPlayer = null
            try { completed.release() } catch (_: Exception) {}
            menuCurrentPath = null
            playNextMenu(0)
        }
        mp.setOnErrorListener { bad, _, _ ->
            if (menuPlayer === bad) menuPlayer = null
            try { bad.release() } catch (_: Exception) {}
            menuCurrentPath = null
            true
        }
        mp.prepare()
        menuPlayer = mp
        menuCurrentPath = path
        menuPreviousPath = path
        mp.start()
        if (shouldCrossFade && match != null) {
            crossFadeMatchToMenu(match, mp, crossFadeMs, target)
        } else if (shouldFadeInFromSilence) {
            fadeInMenuFromSilence(mp, fadeInMs)
        }
        PlayResult(true, path)
    } catch (_: Exception) {
        releaseMenuPlayer()
        PlayResult(false, "Could not play title/menu music")
    }

    private fun crossFadeMatchToMenu(match: MediaPlayer, menu: MediaPlayer, durationMs: Int, target: Float) {
        menuFadeHandler.removeCallbacksAndMessages(null)
        val duration = durationMs.coerceIn(250, 3000)
        val startedAt = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (menuPlayer !== menu || player !== match) return
                val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
                val t = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)
                val liveTarget = (volume * duckMultiplier).coerceIn(0f, 1f)
                try { menu.setVolume(liveTarget * eased, liveTarget * eased) } catch (_: Exception) {}
                try { match.setVolume(target * (1f - eased), target * (1f - eased)) } catch (_: Exception) {}
                if (t >= 1f) {
                    try { if (match.isPlaying) match.pause() } catch (_: Exception) {}
                    try { match.setVolume(liveTarget, liveTarget) } catch (_: Exception) {}
                    matchPausedForMenu = true
                } else menuFadeHandler.postDelayed(this, 24L)
            }
        }
        menuFadeHandler.post(step)
    }

    /** Cold-launch title music enters with the welcome composition instead of arriving at full
     * level on the first rendered frame. This does not alter later menu/match crossfades. */
    private fun fadeInMenuFromSilence(menu: MediaPlayer, durationMs: Int) {
        menuFadeHandler.removeCallbacksAndMessages(null)
        val duration = durationMs.coerceIn(250, 4000)
        val startedAt = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (menuPlayer !== menu) return
                val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
                val t = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)
                val liveTarget = (volume * duckMultiplier).coerceIn(0f, 1f)
                try { menu.setVolume(liveTarget * eased, liveTarget * eased) } catch (_: Exception) {}
                if (t < 1f) menuFadeHandler.postDelayed(this, 24L)
            }
        }
        menuFadeHandler.post(step)
    }

    private fun crossFadeMenuToMatch(menu: MediaPlayer, match: MediaPlayer, durationMs: Int, target: Float) {
        menuFadeHandler.removeCallbacksAndMessages(null)
        val duration = durationMs.coerceIn(250, 3000)
        val startedAt = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (player !== match || menuPlayer !== menu) return
                val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
                val t = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)
                val liveTarget = (volume * duckMultiplier).coerceIn(0f, 1f)
                try { match.setVolume(liveTarget * eased, liveTarget * eased) } catch (_: Exception) {}
                try { menu.setVolume(target * (1f - eased), target * (1f - eased)) } catch (_: Exception) {}
                if (t >= 1f) {
                    releaseMenuPlayer()
                    applyOutputVolume()
                } else menuFadeHandler.postDelayed(this, 24L)
            }
        }
        menuFadeHandler.post(step)
    }

    private fun fadeOutMenuAndRelease(durationMs: Int) {
        val menu = menuPlayer ?: return
        menuFadeHandler.removeCallbacksAndMessages(null)
        if (durationMs <= 0) {
            releaseMenuPlayer()
            return
        }
        val duration = durationMs.coerceIn(120, 3000)
        val startVolume = (volume * duckMultiplier).coerceIn(0f, 1f)
        val startedAt = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (menuPlayer !== menu) return
                val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
                val t = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)
                val out = startVolume * (1f - eased)
                try { menu.setVolume(out, out) } catch (_: Exception) {}
                if (t >= 1f) releaseMenuPlayer() else menuFadeHandler.postDelayed(this, 24L)
            }
        }
        menuFadeHandler.post(step)
    }

    private fun scanMenuTracks(): List<String> {
        val folder = "audio/music/menu"
        return try {
            context.assets.list(folder)
                ?.filter { it.substringAfterLast('.', "").lowercase() in audioExtensions }
                ?.sorted()
                ?.map { "$folder/$it" }
                .orEmpty()
        } catch (_: Exception) { emptyList() }
    }

    private fun releaseMenuPlayer() {
        menuFadeHandler.removeCallbacksAndMessages(null)
        menuPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        menuPlayer = null
        menuCurrentPath = null
    }

    private fun eligibleTracks(): List<MusicTrack> {
        // The toggles are literal: if every faction is off, nothing is eligible.
        return allTracks.filter { it.faction in enabledFactions }
    }

    private fun scanTracks(): List<MusicTrack> {
        val found = mutableListOf<MusicTrack>()
        val factions = listOf(Faction.TERRAN, Faction.PROTOSS, Faction.ZERG)
        for (game in listOf("sc1", "sc2")) {
            for (faction in factions) {
                val folder = "audio/music/$game/${faction.name.lowercase()}"
                val files = try {
                    context.assets.list(folder)
                        ?.filter { it.substringAfterLast('.', "").lowercase() in audioExtensions }
                        ?.sorted()
                        .orEmpty()
                } catch (_: Exception) { emptyList() }
                files.forEach { file ->
                    found += MusicTrack(
                        game = game.uppercase(),
                        faction = faction,
                        fileName = file,
                        path = "$folder/$file"
                    )
                }
            }
        }
        return found
    }

    private fun releasePlayerOnly() {
        fadingOutPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        fadingOutPlayer = null
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.reset() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
    }
}

data class MusicTrack(
    val game: String,
    val faction: Faction,
    val fileName: String,
    val path: String
) {
    val displayName: String
        get() = fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .split(Regex("\\s+"))
            .joinToString(" ") { word -> word.lowercase().replaceFirstChar { c -> c.titlecase() } }
}
