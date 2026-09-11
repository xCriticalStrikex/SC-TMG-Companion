package com.sc2tmg.soundboard

import android.content.Context

class GameStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("game_state_v2", Context.MODE_PRIVATE)

    var round: Int
        get() = prefs.getInt("round", 1)
        set(value) { prefs.edit().putInt("round", value.coerceIn(1, gameLength)).apply() }

    var gameLength: Int
        get() = prefs.getInt("gameLength", 5).coerceIn(4, 10)
        set(value) { prefs.edit().putInt("gameLength", value.coerceIn(4, 10)).apply() }

    var phase: Int
        get() = prefs.getInt("phase", 0)
        set(value) { prefs.edit().putInt("phase", value.coerceIn(0, 3)).apply() }

    /** False while the tracker is in its pre-game READY state. Existing saves migrate as started. */
    var gameStarted: Boolean
        get() = prefs.getBoolean("gameStarted", prefs.contains("round") || prefs.contains("phase"))
        set(value) { prefs.edit().putBoolean("gameStarted", value).apply() }

    /** A configured session exists and can be resumed, even if START GAME has not been pressed yet. */
    var hasSession: Boolean
        get() = prefs.getBoolean("hasSession", gameStarted)
        set(value) { prefs.edit().putBoolean("hasSession", value).apply() }

    /** 2-3 active tabletop players. Older saves migrate from the former Player C toggle. */
    var playerCount: Int
        get() = prefs.getInt(
            "playerCount",
            if (prefs.getBoolean("player3Enabled", false)) 3 else 2
        ).coerceIn(2, 3)
        set(value) {
            val count = value.coerceIn(2, 3)
            prefs.edit()
                .putInt("playerCount", count)
                .putBoolean("player3Enabled", count == 3)
                .apply()
        }

    /** Kept for source/backward compatibility with pre-1.4.11 saves. */
    var player3Enabled: Boolean
        get() = playerCount == 3
        set(value) { playerCount = if (value) 3 else 2 }

    private fun maxPlayerIndex(): Int = (playerCount - 1).coerceAtLeast(0)

    var firstPlayer: Int
        get() = prefs.getInt("firstPlayer", 0).coerceIn(0, maxPlayerIndex())
        set(value) { prefs.edit().putInt("firstPlayer", value.coerceIn(0, maxPlayerIndex())).apply() }

    /** Player who passed first in Movement/Assault and therefore owns the marker next phase. */
    var nextFirstPlayer: Int
        get() = prefs.getInt("nextFirstPlayer", -1).coerceIn(-1, maxPlayerIndex())
        set(value) { prefs.edit().putInt("nextFirstPlayer", value.coerceIn(-1, maxPlayerIndex())).apply() }

    /** 0 Terran, 1 Protoss, 2 Zerg. */
    var playerFactionA: Int
        get() = prefs.getInt("playerFactionA", 0).coerceIn(0, 2)
        set(value) { prefs.edit().putInt("playerFactionA", value.coerceIn(0, 2)).apply() }

    var playerFactionB: Int
        get() = prefs.getInt("playerFactionB", 2).coerceIn(0, 2)
        set(value) { prefs.edit().putInt("playerFactionB", value.coerceIn(0, 2)).apply() }

    var playerFactionC: Int
        get() = prefs.getInt("playerFactionC", 1).coerceIn(0, 2)
        set(value) { prefs.edit().putInt("playerFactionC", value.coerceIn(0, 2)).apply() }

    fun selectedFactionIndices(): List<Int> = buildList {
        add(playerFactionA)
        if (playerCount >= 2) add(playerFactionB)
        if (playerCount >= 3) add(playerFactionC)
    }

    var announcerMode: AnnouncerMode
        get() = runCatching { AnnouncerMode.valueOf(prefs.getString("announcerMode", AnnouncerMode.TERRAN.name) ?: AnnouncerMode.TERRAN.name) }
            .getOrDefault(AnnouncerMode.TERRAN)
        set(value) { prefs.edit().putString("announcerMode", value.name).apply() }

    var supplyA: Int
        get() = prefs.getInt("supplyA", 0)
        set(value) { prefs.edit().putInt("supplyA", value.coerceIn(0, 200)).apply() }
    var supplyB: Int
        get() = prefs.getInt("supplyB", 0)
        set(value) { prefs.edit().putInt("supplyB", value.coerceIn(0, 200)).apply() }
    var supplyC: Int
        get() = prefs.getInt("supplyC", 0)
        set(value) { prefs.edit().putInt("supplyC", value.coerceIn(0, 200)).apply() }

    var scoreA: Int
        get() = prefs.getInt("scoreA", 0)
        set(value) { prefs.edit().putInt("scoreA", value.coerceIn(0, 99)).apply() }
    var scoreB: Int
        get() = prefs.getInt("scoreB", 0)
        set(value) { prefs.edit().putInt("scoreB", value.coerceIn(0, 99)).apply() }
    var scoreC: Int
        get() = prefs.getInt("scoreC", 0)
        set(value) { prefs.edit().putInt("scoreC", value.coerceIn(0, 99)).apply() }

    fun save(
        round: Int,
        gameLength: Int,
        phase: Int,
        firstPlayer: Int,
        nextFirstPlayer: Int,
        playerFactionA: Int,
        playerFactionB: Int,
        playerFactionC: Int,
        playerCount: Int,
        scoreA: Int,
        scoreB: Int,
        scoreC: Int,
        supplyA: Int,
        supplyB: Int,
        supplyC: Int
    ) {
        val length = gameLength.coerceIn(4, 10)
        val count = playerCount.coerceIn(2, 3)
        val maxIndex = count - 1
        prefs.edit()
            .putInt("round", round.coerceIn(1, length))
            .putInt("gameLength", length)
            .putInt("phase", phase.coerceIn(0, 3))
            .putInt("playerCount", count)
            .putBoolean("player3Enabled", count == 3)
            .putInt("firstPlayer", firstPlayer.coerceIn(0, maxIndex))
            .putInt("nextFirstPlayer", nextFirstPlayer.coerceIn(-1, maxIndex))
            .putInt("playerFactionA", playerFactionA.coerceIn(0, 2))
            .putInt("playerFactionB", playerFactionB.coerceIn(0, 2))
            .putInt("playerFactionC", playerFactionC.coerceIn(0, 2))
            .putInt("scoreA", scoreA.coerceIn(0, 99))
            .putInt("scoreB", scoreB.coerceIn(0, 99))
            .putInt("scoreC", scoreC.coerceIn(0, 99))
            .putInt("supplyA", supplyA.coerceIn(0, 200))
            .putInt("supplyB", supplyB.coerceIn(0, 200))
            .putInt("supplyC", supplyC.coerceIn(0, 200))
            .commit()
    }

    fun configureNewGame(count: Int, factionA: Int, factionB: Int, factionC: Int) {
        val voice = announcerMode
        val activeCount = count.coerceIn(2, 3)
        save(
            round = 1,
            gameLength = 5,
            phase = 0,
            firstPlayer = 0,
            nextFirstPlayer = -1,
            playerFactionA = factionA,
            playerFactionB = factionB,
            playerFactionC = factionC,
            playerCount = activeCount,
            scoreA = 0,
            scoreB = 0,
            scoreC = 0,
            supplyA = 0,
            supplyB = 0,
            supplyC = 0
        )
        gameStarted = false
        hasSession = true
        announcerMode = voice
    }

    fun reset() {
        val voice = announcerMode
        save(
            round = 1,
            gameLength = 5,
            phase = 0,
            firstPlayer = 0,
            nextFirstPlayer = -1,
            playerFactionA = 0,
            playerFactionB = 2,
            playerFactionC = 1,
            playerCount = 2,
            scoreA = 0,
            scoreB = 0,
            scoreC = 0,
            supplyA = 0,
            supplyB = 0,
            supplyC = 0
        )
        gameStarted = false
        hasSession = false
        announcerMode = voice
    }
}
