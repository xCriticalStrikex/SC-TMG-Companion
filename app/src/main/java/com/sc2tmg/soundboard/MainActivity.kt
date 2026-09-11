@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.sc2tmg.soundboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.graphics.ImageDecoder
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.imageLoader
import coil.request.ImageRequest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.roundToInt
import java.nio.ByteBuffer
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private enum class Screen { WELCOME, SETUP, MAIN, COLLECTION, RULES, LEGAL }
private enum class VictoryExitTarget { HOME, NEW_GAME }
private data class SpecialAbilityRequest(val unit: UnitEntry, val abilityId: String)
private data class AbilityNotice(val title: String, val detail: String, val timing: String)

/**
 * Session-persistent shuffle bags for ceremonial victory audio. A normal `random()` call is
 * allowed to pick the same item repeatedly; that felt especially obvious on the short result
 * screen pools. Each keyed pool now exhausts every eligible clip once before reshuffling, and
 * the first clip of a fresh cycle is forced away from the previous cycle's last clip.
 */
private object VictoryClipBag {
    private val remaining = mutableMapOf<String, MutableList<String>>()
    private val lastPlayed = mutableMapOf<String, String>()
    private val signatures = mutableMapOf<String, String>()

    @Synchronized
    fun next(key: String, clips: List<String>): String {
        require(clips.isNotEmpty()) { "Victory audio pool must not be empty" }
        val signature = clips.sorted().joinToString("|")
        var bag = remaining[key]
        if (bag == null || bag.isEmpty() || signatures[key] != signature) {
            bag = clips.shuffled().toMutableList()
            val last = lastPlayed[key]
            if (bag.size > 1 && bag.firstOrNull() == last) {
                val swap = bag.indexOfFirst { it != last }
                if (swap > 0) {
                    val tmp = bag[0]
                    bag[0] = bag[swap]
                    bag[swap] = tmp
                }
            }
            remaining[key] = bag
            signatures[key] = signature
        }
        return bag.removeAt(0).also { lastPlayed[key] = it }
    }
}

/** Pick from independent neutral/faction shuffle bags; never cross-faction fallback. */
private fun nextScopedResultEntry(
    keyPrefix: String,
    faction: Faction?,
    entries: List<ResultAudioEntry>
): ResultAudioEntry? {
    val factionScope = when (faction) {
        Faction.TERRAN -> ResultWinnerScope.TERRAN
        Faction.PROTOSS -> ResultWinnerScope.PROTOSS
        Faction.ZERG -> ResultWinnerScope.ZERG
        else -> null
    }
    val groups = listOfNotNull(
        entries.filter { it.winnerScope == ResultWinnerScope.ANY }.takeIf { it.isNotEmpty() },
        factionScope?.let { scope -> entries.filter { it.winnerScope == scope }.takeIf { it.isNotEmpty() } }
    )
    val selected = groups.randomOrNull() ?: return null
    val scope = selected.first().winnerScope
    val path = VictoryClipBag.next("$keyPrefix-${scope.name}", selected.map { it.assetPath })
    return selected.first { it.assetPath == path }
}

/** Session-persistent shuffle bags for victory artwork. */
private object VictoryArtBag {
    private val remaining = mutableMapOf<String, MutableList<String>>()
    private val lastShown = mutableMapOf<String, String>()
    private val signatures = mutableMapOf<String, String>()

    @Synchronized
    fun next(key: String, art: List<String>): String {
        require(art.isNotEmpty()) { "Victory artwork pool must not be empty" }
        val signature = art.sorted().joinToString("|")
        var bag = remaining[key]
        if (bag == null || bag.isEmpty() || signatures[key] != signature) {
            bag = art.shuffled().toMutableList()
            val last = lastShown[key]
            if (bag.size > 1 && bag.firstOrNull() == last) {
                val swap = bag.indexOfFirst { it != last }
                if (swap > 0) {
                    val tmp = bag[0]
                    bag[0] = bag[swap]
                    bag[swap] = tmp
                }
            }
            remaining[key] = bag
            signatures[key] = signature
        }
        return bag.removeAt(0).also { lastShown[key] = it }
    }
}

private fun victoryAssetImages(context: android.content.Context, directory: String): List<String> =
    runCatching {
        context.assets.list(directory).orEmpty()
            .filter { name ->
                val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                ext in setOf("png", "jpg", "jpeg", "webp")
            }
            .sorted()
            .map { "$directory/$it" }
    }.getOrDefault(emptyList())

/**
 * Victory art is driven by the checked folder structure in assets rather than a hand-maintained
 * filename list. Generic faction art is always eligible; subfaction art is added only when that
 * exact theme is selected in Game Setup. This makes future art drops a folder-copy job instead of
 * another Kotlin edit and prevents faction/subfaction leakage.
 */
private fun victoryArtPool(context: android.content.Context, faction: Faction, themeLabel: String?): List<String> {
    val root = "images/ui/victory/library"
    val factionDir = when (faction) {
        Faction.TERRAN -> "terran"
        Faction.PROTOSS -> "protoss"
        Faction.ZERG, Faction.HYBRID -> "zerg"
    }
    val generic = victoryAssetImages(context, "$root/$factionDir/generic")
    val specificDir = when (faction) {
        Faction.TERRAN -> when (themeLabel) {
            "DOMINION" -> "dominion"
            "CONFEDERACY" -> "confederacy"
            "RAYNOR'S RAIDERS" -> "raynors_raiders"
            else -> null
        }
        Faction.PROTOSS -> when (themeLabel) {
            "KHALAI" -> "khalai"
            "NERAZIM" -> "nerazim"
            "TAL'DARIM" -> "taldarim"
            "PURIFIER" -> "purifier"
            "GOLDEN ARMADA" -> "golden_armada"
            else -> null
        }
        Faction.ZERG -> when (themeLabel) {
            "SWARM" -> "swarm"
            "PRIMAL" -> "primal"
            else -> null
        }
        Faction.HYBRID -> null
    }
    val specific = specificDir?.let { victoryAssetImages(context, "$root/$factionDir/$it") }.orEmpty()
    return (generic + specific).distinct()
}

/** Neutral draw art is isolated from every faction pool. Empty is a supported state. */
private fun tieArtPool(context: android.content.Context): List<String> =
    victoryAssetImages(context, "images/ui/victory/library/tie")

private data class ReleaseUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val notes: String
)

private suspend fun fetchReleaseUpdate(): ReleaseUpdateInfo? = withContext(Dispatchers.IO) {
    if (BuildConfig.UPDATE_MANIFEST_URL.isBlank()) return@withContext null
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(BuildConfig.UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 3500
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SC2TMG-Companion/${BuildConfig.VERSION_NAME}")
        }
        if (connection.responseCode !in 200..299) return@withContext null
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        if (!json.optBoolean("enabled", true)) return@withContext null
        val remoteCode = json.optInt("versionCode", 0)
        val apkUrl = json.optString("apkUrl", "").trim()
        if (remoteCode <= BuildConfig.VERSION_CODE || apkUrl.isBlank()) return@withContext null
        ReleaseUpdateInfo(
            versionName = json.optString("versionName", remoteCode.toString()),
            versionCode = remoteCode,
            apkUrl = apkUrl,
            notes = json.optString("notes", "A newer SC2 TMG Companion build is available.").trim()
        )
    } catch (_: Exception) {
        null // Never block or nag the user because Drive/network is unavailable.
    } finally {
        connection?.disconnect()
    }
}

private enum class CollectionMode { UNITS, BUILDINGS }
private enum class RulesTab { FIELD, BROWSE, SOURCE, UPDATES }
private enum class RulesBrowseTab { CORE, KEYWORDS, FAQ, TERRAN, PROTOSS, ZERG }

private data class FactionThemeOption(
    val label: String,
    val asset: String,
    val titleAsset: String,
    val accent: Color,
    val backgroundAsset: String,
    val squareFrameAsset: String,
    val rectFrameAsset: String
)

private fun themeAssetRoot(faction: String, theme: String): String = "images/ui/themes/$faction/$theme"

private fun factionThemeOptions(faction: Faction): List<FactionThemeOption> = when (faction) {
    Faction.TERRAN -> listOf(
        FactionThemeOption(
            "DOMINION",
            "${themeAssetRoot("terran", "dominion")}/icon.png",
            "${themeAssetRoot("terran", "dominion")}/title.webp",
            Color(0xFFE84C4C),
            "${themeAssetRoot("terran", "dominion")}/background.png",
            "${themeAssetRoot("terran", "dominion")}/frame_square.png",
            "${themeAssetRoot("terran", "dominion")}/frame_rect.png"
        ),
        FactionThemeOption(
            "CONFEDERACY",
            "${themeAssetRoot("terran", "confederacy")}/icon.png",
            "${themeAssetRoot("terran", "confederacy")}/title.webp",
            Color(0xFF35AEEF),
            "${themeAssetRoot("terran", "confederacy")}/background.png",
            "${themeAssetRoot("terran", "confederacy")}/frame_square.png",
            "${themeAssetRoot("terran", "confederacy")}/frame_rect.png"
        ),
        // Appended so existing Dominion/Confederacy saved indices remain stable.
        FactionThemeOption(
            "RAYNOR'S RAIDERS",
            "${themeAssetRoot("terran", "raynors_raiders")}/icon.png",
            "${themeAssetRoot("terran", "raynors_raiders")}/title.webp",
            Color(0xFFD9E5EF),
            "${themeAssetRoot("terran", "raynors_raiders")}/background.png",
            "${themeAssetRoot("terran", "raynors_raiders")}/frame_square.png",
            "${themeAssetRoot("terran", "raynors_raiders")}/frame_rect.png"
        )
    )
    Faction.PROTOSS -> listOf(
        FactionThemeOption(
            "KHALAI",
            "${themeAssetRoot("protoss", "khalai")}/icon.png",
            "${themeAssetRoot("protoss", "khalai")}/title.webp",
            Color(0xFFE8B842),
            "${themeAssetRoot("protoss", "khalai")}/background.png",
            "${themeAssetRoot("protoss", "khalai")}/frame_square.png",
            "${themeAssetRoot("protoss", "khalai")}/frame_rect.png"
        ),
        FactionThemeOption(
            "NERAZIM",
            "${themeAssetRoot("protoss", "nerazim")}/icon.png",
            "${themeAssetRoot("protoss", "nerazim")}/title.webp",
            Color(0xFF53D98D),
            "${themeAssetRoot("protoss", "nerazim")}/background.png",
            "${themeAssetRoot("protoss", "nerazim")}/frame_square.png",
            "${themeAssetRoot("protoss", "nerazim")}/frame_rect.png"
        ),
        FactionThemeOption(
            "TAL'DARIM",
            "${themeAssetRoot("protoss", "taldarim")}/icon.png",
            "${themeAssetRoot("protoss", "taldarim")}/title.webp",
            Color(0xFFE34850),
            "${themeAssetRoot("protoss", "taldarim")}/background.png",
            "${themeAssetRoot("protoss", "taldarim")}/frame_square.png",
            "${themeAssetRoot("protoss", "taldarim")}/frame_rect.png"
        ),
        FactionThemeOption(
            "PURIFIER",
            "${themeAssetRoot("protoss", "purifier")}/icon.png",
            "${themeAssetRoot("protoss", "purifier")}/title.webp",
            Color(0xFFFFA13A),
            "${themeAssetRoot("protoss", "purifier")}/background.png",
            "${themeAssetRoot("protoss", "purifier")}/frame_square.png",
            "${themeAssetRoot("protoss", "purifier")}/frame_rect.png"
        ),
        // Appended to preserve the persisted indices used by 1.4.25 and earlier.
        FactionThemeOption(
            "GOLDEN ARMADA",
            "${themeAssetRoot("protoss", "golden_armada")}/icon.png",
            "${themeAssetRoot("protoss", "golden_armada")}/title.webp",
            Color(0xFF4DB9FF),
            "${themeAssetRoot("protoss", "golden_armada")}/background.png",
            "${themeAssetRoot("protoss", "golden_armada")}/frame_square.png",
            "${themeAssetRoot("protoss", "golden_armada")}/frame_rect.png"
        )
    )
    Faction.ZERG -> listOf(
        FactionThemeOption(
            "SWARM",
            "${themeAssetRoot("zerg", "swarm")}/icon.png",
            "${themeAssetRoot("zerg", "swarm")}/title.webp",
            Color(0xFFB857D4),
            "${themeAssetRoot("zerg", "swarm")}/background.png",
            "${themeAssetRoot("zerg", "swarm")}/frame_square.png",
            "${themeAssetRoot("zerg", "swarm")}/frame_rect.png"
        ),
        FactionThemeOption(
            "PRIMAL",
            "${themeAssetRoot("zerg", "primal")}/icon.png",
            "${themeAssetRoot("zerg", "primal")}/title.webp",
            Color(0xFF91D934),
            "${themeAssetRoot("zerg", "primal")}/background.png",
            "${themeAssetRoot("zerg", "primal")}/frame_square.png",
            "${themeAssetRoot("zerg", "primal")}/frame_rect.png"
        )
    )
    Faction.HYBRID -> listOf(
        FactionThemeOption(
            "HYBRID",
            "images/ui/themes/hybrid/hybrid_emblem.png",
            "images/ui/themes/hybrid/hybrid_title_plaque.png",
            Color(0xFFD74F7D),
            "images/ui/themes/hybrid/hybrid_page_background.png",
            "images/ui/themes/hybrid/hybrid_square_frame.png",
            "images/ui/themes/hybrid/hybrid_horizontal_frame.png"
        )
    )
}

private fun factionThemeOption(faction: Faction, index: Int): FactionThemeOption {
    val options = factionThemeOptions(faction)
    return options[index.mod(options.size)]
}

private val AppColors = darkColorScheme(
    background = Color(0xFF05080D),
    surface = Color(0xE6101721),
    surfaceVariant = Color(0xFF172232),
    primary = Color(0xFF77C7FF),
    onPrimary = Color(0xFF04101A),
    onBackground = Color(0xFFF2F7FC),
    onSurface = Color(0xFFF2F7FC),
    outline = Color(0xFF3C4B5E)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Let Android/Samsung own the actual cutout policy. The app compensates its content padding
        // for windows that are physically shifted down by a device-level "hide camera cutout" mode,
        // instead of fighting that setting with a forced layoutInDisplayCutoutMode override.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme(colorScheme = AppColors) { SoundboardApp() } }
    }
}

@Composable
private fun SoundboardApp() {
    val context = LocalContext.current
    val store = remember { CollectionStore(context) }
    val gameState = remember { GameStateStore(context) }
    val pendingEffects = remember { PendingEffectsStore(context) }
    val player = remember { SoundPlayer(context) }
    val resultAudioRegistry = remember { ResultAudioRegistry(context) }
    val musicStore = remember { MusicStore(context) }
    val themeStore = remember { ThemeStore(context) }
    val musicPlayer = remember { MusicPlayer(context) }
    val rulesEngine = remember { RulesEngine(context) }
    var screen by remember { mutableStateOf(Screen.WELCOME) }
    var returnScreen by remember { mutableStateOf(Screen.WELCOME) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // Cold-launch-only Terran blast-door reveal. rememberSaveable prevents a rotation or
    // activity recreation from replaying it after the user has already entered the app.
    var showLaunchIntro by rememberSaveable { mutableStateOf(true) }
    var welcomeEntranceFinished by rememberSaveable { mutableStateOf(false) }
    var victoryExitTarget by remember { mutableStateOf<VictoryExitTarget?>(null) }
    var victoryExitFaction by remember { mutableStateOf<Faction?>(null) }
    val releasePrefs = remember { context.getSharedPreferences("sc2tmg_release", android.content.Context.MODE_PRIVATE) }
    var showFirstRunWelcome by rememberSaveable { mutableStateOf(false) }
    var releaseUpdate by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        player.setVolume(musicStore.soundboardVolume)
        player.setBuildingGain(musicStore.buildingVolume)
        player.setWeaponSfxGain(musicStore.weaponSfxVolume)
        player.setSoundBankMode(musicStore.soundBankMode)
        musicPlayer.setVolume(musicStore.musicVolume)
        musicPlayer.setDuckingPower(musicStore.duckingPower)
        musicPlayer.setEnabledFactions(musicStore.enabledFactions())

        // Existing Classic/Mixed users have clearly already made this decision. Historical SC2
        // Preferred was also the old default, so only that ambiguous case gets the one-time V1 prompt.
        if (!releasePrefs.getBoolean("sound_bank_choice_confirmed", false)) {
            if (musicStore.soundBankMode == SoundBankMode.SC2_PREFERRED) {
                showFirstRunWelcome = true
            } else {
                releasePrefs.edit().putBoolean("sound_bank_choice_confirmed", true).apply()
            }
        }
        // R18: the front end owns its own title/menu music bank. Jimmy's Jukebox is silent
        // until a match is actually committed and the pre-match ceremony reaches Round 1.
        if (musicStore.playbackEnabled) {
            // Let the title cue emerge with the four-second corridor reveal instead of arriving
            // at full volume on frame zero. A stored explicit mute still suppresses it entirely.
            musicPlayer.playMenuOrResume(crossFadeMs = 0, fadeInMs = 2600)
        }
        releaseUpdate = fetchReleaseUpdate()
    }
    DisposableEffect(Unit) { onDispose { player.stop(); musicPlayer.release() } }

    // Keep launch light. Only warm the first Terran card + selected portrait that the
    // user is actually likely to see immediately. Earlier builds started dozens of
    // network requests on launch, which competed with the visible image loads.
    LaunchedEffect(Unit) {
        listOfNotNull(
            PortraitSources.unitUrl("marine", preferAnimation = false),
            PortraitSources.unitIconUrl("marine"),
            PortraitSources.unitIconUrl("raynors_raider_marine"),
            PortraitSources.unitIconUrl("marauder"),
            PortraitSources.unitIconUrl("medic"),
            PortraitSources.unitIconUrl("goliath")
        ).distinct().forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKey("art_v5:$url")
                    .diskCacheKey("art_v5:$url")
                    .size(256, 256)
                    .build()
            )
        }
    }

    // Samsung / Android system back should navigate inside the app first. Only the
    // welcome screen falls through to Android's normal back behavior (exit/minimize).
    // R18 also treats Setup as a draft when Back is pressed physically: restore the
    // committed music state rather than leaving a temporary Setup preview behind.
    BackHandler(enabled = screen != Screen.WELCOME) {
        player.stop()
        when (screen) {
            Screen.SETUP -> {
                if (musicStore.playbackEnabled) musicPlayer.playMenuOrResume(crossFadeMs = 360)
                else musicPlayer.stopMenuOnly(fadeMs = 260)
                screen = Screen.WELCOME
            }
            Screen.MAIN -> {
                // Hardware Back mirrors HOME. If a result screen owns the music bus, release it
                // first; otherwise playMenuOrResume preserves the live match track underneath.
                musicPlayer.endResult(resumeSoundtrack = false)
                if (musicStore.playbackEnabled) musicPlayer.playMenuOrResume(crossFadeMs = 900)
                else musicPlayer.stop()
                screen = Screen.WELCOME
            }
            Screen.COLLECTION, Screen.RULES, Screen.LEGAL -> screen = returnScreen
            Screen.WELCOME -> Unit
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.WELCOME -> WelcomeScreen(
                    player = player,
                    launchIntroActive = showLaunchIntro,
                    entranceFinished = welcomeEntranceFinished,
                    onEntranceFinished = { welcomeEntranceFinished = true },
                    releaseUpdate = releaseUpdate,
                    onUpdate = { info -> launchUri(context, info.apkUrl) },
                    onStart = { screen = Screen.SETUP },
                    onCollection = { returnScreen = Screen.WELCOME; screen = Screen.COLLECTION },
                    onRules = { returnScreen = Screen.WELCOME; screen = Screen.RULES },
                    onLegal = { returnScreen = Screen.WELCOME; screen = Screen.LEGAL }
                )
                Screen.SETUP -> GameSetupScreen(
                    gameState = gameState,
                    pendingEffects = pendingEffects,
                    musicStore = musicStore,
                    themeStore = themeStore,
                    musicPlayer = musicPlayer,
                    onBack = { screen = Screen.WELCOME },
                    onResume = {
                        if (gameState.gameStarted && musicStore.playbackEnabled) {
                            musicPlayer.resumeMatchFromMenu(crossFadeMs = 900)
                        } else {
                            // A saved READY screen is still pre-match: no Jukebox until START GAME.
                            musicPlayer.stopMenuForNewGame(fadeMs = 520)
                        }
                        screen = Screen.MAIN
                    },
                    onStartNewGame = { screen = Screen.MAIN }
                )
                Screen.MAIN -> MainSoundboard(
                    store = store,
                    gameState = gameState,
                    pendingEffects = pendingEffects,
                    player = player,
                    musicStore = musicStore,
                    themeStore = themeStore,
                    musicPlayer = musicPlayer,
                    refreshTick = refreshTick,
                    onNewGameSetup = {
                        player.stop()
                        if (musicStore.playbackEnabled) musicPlayer.playMenuOrResume(crossFadeMs = 900)
                        else musicPlayer.stop()
                        screen = Screen.SETUP
                    },
                    onHome = {
                        player.stop()
                        if (musicStore.playbackEnabled) musicPlayer.playMenuOrResume(crossFadeMs = 900)
                        else musicPlayer.stop()
                        screen = Screen.WELCOME
                    },
                    onVictoryNewGame = { faction -> player.stop(); victoryExitFaction = faction; victoryExitTarget = VictoryExitTarget.NEW_GAME },
                    onVictoryHome = { faction -> player.stop(); victoryExitFaction = faction; victoryExitTarget = VictoryExitTarget.HOME },
                    onCollection = { player.stop(); returnScreen = Screen.MAIN; screen = Screen.COLLECTION },
                    onRules = { player.stop(); returnScreen = Screen.MAIN; screen = Screen.RULES }
                )
                Screen.COLLECTION -> CollectionScreen(
                    store = store,
                    onChanged = { refreshTick++ },
                    onBack = { screen = returnScreen }
                )
                Screen.RULES -> RulesScreen(rulesEngine = rulesEngine, onBack = { screen = returnScreen })
                Screen.LEGAL -> LegalNoticeScreen(onBack = { screen = returnScreen })
            }

            if (showLaunchIntro) {
                LaunchDoorIntro(
                    player = player,
                    onFinished = { showLaunchIntro = false }
                )
            }

            if (!showLaunchIntro && screen == Screen.WELCOME && showFirstRunWelcome) {
                FirstRunReleaseWelcomeDialog(
                    onConfirm = { mode ->
                        musicStore.soundBankMode = mode
                        player.setSoundBankMode(mode)
                        releasePrefs.edit().putBoolean("sound_bank_choice_confirmed", true).apply()
                        showFirstRunWelcome = false
                    }
                )
            }

            victoryExitTarget?.let { target ->
                VictoryExitDoorTransition(
                    player = player,
                    registry = resultAudioRegistry,
                    winnerFaction = victoryExitFaction,
                    onCovered = {
                        when (target) {
                            VictoryExitTarget.NEW_GAME -> {
                                musicPlayer.endResult(resumeSoundtrack = false)
                                if (musicStore.playbackEnabled) musicPlayer.playMenuOrResume(crossFadeMs = 700)
                                screen = Screen.SETUP
                            }
                            VictoryExitTarget.HOME -> {
                                musicPlayer.endResult(resumeSoundtrack = false)
                                if (musicStore.playbackEnabled) musicPlayer.playMenuOrResume(crossFadeMs = 700)
                                screen = Screen.WELCOME
                            }
                        }
                    },
                    onFinished = { victoryExitTarget = null; victoryExitFaction = null }
                )
            }
        }
    }
}

/**
 * Cold-launch welcome-art push into the Terran corridor + blast-door reveal.
 *
 * The approved welcome composition is the live background from frame zero. The closed doors
 * and surrounding corridor are one plate during the push-in. That plate is already moving while
 * it fades up, passes through the exact legacy door-open transform without stopping, and keeps
 * drifting forward until the corridor has naturally cleared the viewport. The approved welcome
 * art then emerges through the opening as a slow centre-out radial reveal.
 */
@Composable
private fun LaunchDoorIntro(
    player: SoundPlayer,
    onFinished: () -> Unit
) {
    val plateFadeDurationMs = 4000f
    val doorOpenAtMs = 4000f
    val plateExitAtMs = 6000f
    val welcomeRevealDurationMs = 4000f
    val totalDurationMs = 8100
    val progress = remember { Animatable(0f) }
    val blocker = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        progress.snapTo(0f)
        // Authentic Hyperion machinery at a deliberately tiny level. It is audible for the
        // corridor push, then fades smoothly from door-open through the plate's final exit.
        player.playLaunchAmbience(
            path = "audio/ui/intro/welcome_corridor_ambience.ogg",
            gain = 0.065f,
            fadeOutStartMs = doorOpenAtMs.toInt(),
            fadeOutDurationMs = (plateExitAtMs - doorOpenAtMs).toInt()
        )
        launch {
            delay(doorOpenAtMs.toLong())
            // The single restrained crackle shares the exact existing door-motion timestamp.
            player.playUi("audio/ui/intro/welcome_spark_crackle.wav", gain = 0.12f)
            player.playUi("audio/ui/intro/door_open.wav", gain = 0.92f)
        }
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = totalDurationMs, easing = LinearEasing)
        )
        onFinished()
    }

    val elapsedMs = progress.value.coerceIn(0f, 1f) * totalDurationMs.toFloat()

    // The transparent doorway occupies 329 / 1254 of the authored wall image's width.
    // Scaling the whole square plate by its reciprocal makes that doorway exactly screen-wide,
    // which is the legacy full-screen door transform. The portal's aspect is already within
    // roughly one percent of the approved portrait canvas, so uniform scaling is sufficient.
    val portalWidthFraction = 329f / 1254f
    val handoffScale = 1f / portalWidthFraction
    // The source WebPs contain up to roughly 78 authored pixels of centre padding across the
    // long straight rails. A 3% inward extension on each half makes those rails themselves meet,
    // rather than leaving only the projecting teeth and centre lock touching across black space.
    val doorHalfWidthFraction = 0.53f
    val showCorridorFrame = elapsedMs < plateExitAtMs
    val plateFadeT = (elapsedMs / plateFadeDurationMs).coerceIn(0f, 1f)
    val corridorPlateAlpha = plateFadeT * plateFadeT * (3f - 2f * plateFadeT)

    // Door travel is the existing weighted motion shifted after the new establishing shot.
    val doorElapsedMs = (elapsedMs - doorOpenAtMs).coerceAtLeast(0f)
    // Nothing behind the closed machinery is visible before the legacy doors begin to move.
    // Starting on the exact opening frame, reveal the approved welcome screen radially from the
    // centre over four unhurried seconds instead of applying a flat whole-screen alpha dissolve.
    val welcomeRevealT = (doorElapsedMs / welcomeRevealDurationMs).coerceIn(0f, 1f)
    val welcomeReveal = welcomeRevealT * welcomeRevealT * (3f - 2f * welcomeRevealT)
    val faceTravel = when {
        doorElapsedMs <= 0f -> 0f
        doorElapsedMs < 570f -> {
            val t = (doorElapsedMs / 570f).coerceIn(0f, 1f)
            0.075f * FastOutSlowInEasing.transform(t)
        }
        doorElapsedMs < 1470f -> {
            val t = ((doorElapsedMs - 570f) / 900f).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            0.075f + 0.805f * smooth
        }
        doorElapsedMs < 1710f -> {
            val t = ((doorElapsedMs - 1470f) / 240f).coerceIn(0f, 1f)
            0.880f + 0.220f * FastOutSlowInEasing.transform(t)
        }
        else -> 1.100f
    }

    val laggedUnder = faceTravel * 0.64f
    val underTravel = if (doorElapsedMs < 1270f) {
        laggedUnder
    } else {
        val catchT = ((doorElapsedMs - 1270f) / 440f).coerceIn(0f, 1f)
        laggedUnder + (1.120f - laggedUnder) * FastOutSlowInEasing.transform(catchT)
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .clickable(interactionSource = blocker, indication = null) { /* consume launch taps */ }
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val fullHeightPx = with(density) { maxHeight.toPx() }
        // Begin with the square corridor fitted to the portrait height (sides cropped), not
        // fitted across the screen width. From there it pushes further into the portal.
        val initialPlateScale = fullHeightPx / fullWidthPx
        // Keep one continuous linear velocity across the door-open handoff. The post-handoff
        // distance is derived from the pre-handoff velocity, so there is no pause, easing reset,
        // or speed jump while the doors begin to part.
        val preHandoffScaleDistance = handoffScale - initialPlateScale
        val postHandoffDurationMs = plateExitAtMs - doorOpenAtMs
        val plateExitScale = handoffScale +
            preHandoffScaleDistance * (postHandoffDurationMs / doorOpenAtMs)
        val pushScale = when {
            elapsedMs < doorOpenAtMs -> {
                val t = (elapsedMs / doorOpenAtMs).coerceIn(0f, 1f)
                initialPlateScale + preHandoffScaleDistance * t
            }
            elapsedMs < plateExitAtMs -> {
                val t = ((elapsedMs - doorOpenAtMs) / postHandoffDurationMs).coerceIn(0f, 1f)
                handoffScale + (plateExitScale - handoffScale) * t
            }
            else -> plateExitScale
        }
        val facePx = fullWidthPx * faceTravel
        val underPx = fullWidthPx * underTravel
        // Door travel stays stable in screen space while the parent plate continues to scale.
        val localFacePx = facePx / pushScale
        val localUnderPx = underPx / pushScale

        Canvas(Modifier.fillMaxSize()) {
            if (welcomeReveal <= 0f) {
                drawRect(Color.Black)
            } else if (welcomeReveal < 1f) {
                val maxRadius = sqrt(
                    (size.width * 0.5f) * (size.width * 0.5f) +
                        (size.height * 0.5f) * (size.height * 0.5f)
                )
                // The centre itself fades gently instead of popping into existence, while a wide
                // feather travels outward. The last 18% also releases the far corners smoothly.
                val centreAlpha = (1f - welcomeReveal / 0.16f).coerceIn(0f, 1f)
                val edgeAlpha = if (welcomeReveal < 0.82f) {
                    1f
                } else {
                    (1f - (welcomeReveal - 0.82f) / 0.18f).coerceIn(0f, 1f)
                }
                val innerStop = (welcomeReveal * 0.82f).coerceIn(0.001f, 0.82f)
                val outerStop = (innerStop + 0.18f).coerceIn(innerStop + 0.001f, 1f)
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = centreAlpha),
                            innerStop to Color.Black.copy(alpha = centreAlpha),
                            outerStop to Color.Black.copy(alpha = edgeAlpha),
                            1f to Color.Black.copy(alpha = edgeAlpha)
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.5f),
                        radius = maxRadius
                    )
                )
            }
        }

        // Exact authored aperture: alpha-hole bounds are x=462..790 and y=243..966 in the
        // 1254-square wall PNG. The same four door image instances remain composed throughout
        // both the push and opening motion; only the surrounding wall is removed at handoff.
        // This prevents the old asynchronous layer swap from blinking the doors out and back in.
        val portalWidth = maxWidth * portalWidthFraction
        val portalHeight = maxWidth * (724f / 1254f)
        val portalOffsetY = maxWidth * (-22f / 1254f)
        // Introduce the authored portal correction continuously with the same linear camera move,
        // reaching exact centre at the handoff and remaining centred as the corridor clears.
        val centreCorrectionT = (elapsedMs / doorOpenAtMs).coerceIn(0f, 1f)
        val centreCorrectionPx = fullWidthPx * (22f / 1254f) * pushScale * centreCorrectionT

        Box(
            Modifier.align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = pushScale
                    scaleY = pushScale
                    translationY = centreCorrectionPx
                    alpha = corridorPlateAlpha
                }
        ) {
            Box(
                Modifier.align(Alignment.Center)
                    .offset(y = portalOffsetY)
                    .width(portalWidth)
                    .height(portalHeight)
            ) {
                AssetFrame(
                    "images/ui/intro/door_left_underlay.webp",
                    Modifier.align(Alignment.CenterStart).fillMaxHeight()
                        .fillMaxWidth(doorHalfWidthFraction)
                        .graphicsLayer { translationX = -localUnderPx },
                    contentScale = ContentScale.FillBounds
                )
                AssetFrame(
                    "images/ui/intro/door_right_underlay.webp",
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        .fillMaxWidth(doorHalfWidthFraction)
                        .graphicsLayer { translationX = localUnderPx },
                    contentScale = ContentScale.FillBounds
                )
                AssetFrame(
                    "images/ui/intro/door_left.webp",
                    Modifier.align(Alignment.CenterStart).fillMaxHeight()
                        .fillMaxWidth(doorHalfWidthFraction)
                        .graphicsLayer { translationX = -localFacePx },
                    contentScale = ContentScale.FillBounds
                )
                AssetFrame(
                    "images/ui/intro/door_right.webp",
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        .fillMaxWidth(doorHalfWidthFraction)
                        .graphicsLayer { translationX = localFacePx },
                    contentScale = ContentScale.FillBounds
                )
            }
            if (showCorridorFrame) {
                AssetFrame(
                    "images/ui/intro/corridor_frame.png",
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
            }
        }
    }
}


/**
 * Result-screen exit transition using the authored GG/WP blast-door pair.
 *
 * The old screen stays live while the doors rush inward. Once the seam is fully closed we
 * swap the route directly underneath the shut doors, then reuse the same weighted
 * crack/crawl/release/coast rhythm as the launch doors to reveal the destination.
 */
@Composable
private fun VictoryExitDoorTransition(
    player: SoundPlayer,
    registry: ResultAudioRegistry,
    winnerFaction: Faction?,
    onCovered: () -> Unit,
    onFinished: () -> Unit
) {
    // Travel is expressed in half-screen widths: 0 = shut, >1 = fully beyond the side edge.
    // The exit transition intentionally keeps the routed screen LIVE underneath at all times.
    // There is no blackout layer: HOME / GAME SETUP is swapped only after the GG/WP faces
    // have completely bitten together at the centre seam.
    val closeTravel = remember { Animatable(1.26f) }
    val openProgress = remember { Animatable(0f) }
    val impactBurst = remember { Animatable(1f) }
    var opening by remember { mutableStateOf(false) }
    val blocker = remember { MutableInteractionSource() }
    BackHandler(enabled = true) { /* transition owns navigation until the doors clear */ }

    LaunchedEffect(Unit) {
        closeTravel.snapTo(1.26f)
        openProgress.snapTo(0f)
        impactBurst.snapTo(1f)
        opening = false

        delay(45L)
        // The mechanical slam is intentionally subordinate to the spoken GG. The source door
        // asset is naturally much hotter than the announcer VO, so run it around half level.
        player.playDoor("audio/ui/intro/gg_door_close.wav", gain = 0.82f)

        // Start the verified literal GG while the heavy mechanical close is still moving.
        // SoundPlayer ducks only the tagged door layer by 3.5 dB for speech, then restores it.
        launch {
            delay(720L)
            val scoped = registry.ggLiteral(winnerFaction).filter { player.isPoolClipEnabled(it.assetPath) }
            val eligible = scoped.ifEmpty { registry.ggLiteral(winnerFaction) }
            if (eligible.isNotEmpty()) {
                val scopeKey = winnerFaction?.name ?: "ANY"
                val path = VictoryClipBag.next("gg-literal-$scopeKey", eligible.map { it.assetPath })
                eligible.firstOrNull { it.assetPath == path }?.let { player.playResultVoice(it) }
            }
        }

        // Smooth heavy close, then a short final bite. The destination is NOT changed
        // until this second movement is finished and the seam is fully shut.
        closeTravel.animateTo(0.11f, tween(1300, easing = FastOutSlowInEasing))
        closeTravel.animateTo(0f, tween(260, easing = FastOutLinearInEasing))

        // Replace the old vertical orange seam flash with a short sideways shower of impact
        // embers. It is allowed to continue over the shut doors while the route underneath swaps.
        launch {
            impactBurst.snapTo(0f)
            impactBurst.animateTo(1f, tween(760, easing = LinearEasing))
        }

        // Quality-control rule: the door uses the registry's human-verified spoken GG / Good
        // game lines, not filename/event-name guesses. R15 moves obvious GG wording out of the
        // redundant ceremony bucket and allows a winner-faction GG only for that faction.
        onCovered()

        // Give the dry GG phrase enough clean space before the opening machinery starts. The
        // new longer room tail is intentionally allowed to trail through the opening.
        delay(900L)
        opening = true
        player.playUi("audio/ui/intro/gg_door_open.wav", gain = 0.90f)

        // One continuous timebase instead of chained animateTo calls. The visual curve below
        // is the same crack -> crawl -> release -> off-screen coast used by LaunchDoorIntro,
        // which removes the visible speed changes at segment boundaries.
        openProgress.snapTo(0f)
        openProgress.animateTo(1f, tween(2140, easing = LinearEasing))
        delay(110L)
        onFinished()
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .clickable(interactionSource = blocker, indication = null) { /* consume taps */ }
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }

        // Closing uses the authored GG/WP motion. Opening reuses the launch-door movement curve
        // on a single continuous clock, so there is no jerk between "crack", "crawl" and "release".
        val faceTravel = if (!opening) {
            closeTravel.value
        } else {
            val elapsedMs = 610f + openProgress.value.coerceIn(0f, 1f) * 1770f
            when {
                elapsedMs < 1180f -> {
                    val t = ((elapsedMs - 610f) / 570f).coerceIn(0f, 1f)
                    0.075f * FastOutSlowInEasing.transform(t)
                }
                elapsedMs < 2080f -> {
                    val t = ((elapsedMs - 1180f) / 900f).coerceIn(0f, 1f)
                    val smooth = t * t * (3f - 2f * t)
                    0.075f + 0.805f * smooth
                }
                elapsedMs < 2320f -> {
                    val t = ((elapsedMs - 2080f) / 240f).coerceIn(0f, 1f)
                    0.880f + 0.360f * FastOutSlowInEasing.transform(t)
                }
                else -> 1.240f
            }
        }

        val underTravel = if (!opening) {
            // Keep the rear leaf just a touch closer to the seam than the authored GG face.
            // That gives a faint parallax hint during the close without ever becoming a second
            // competing door motion.
            closeTravel.value * 0.95f
        } else {
            // IMPORTANT: the rear leaf used to crawl at 64% of the face travel and then perform
            // a late 440 ms catch-up to 1.26. That catch-up was the visible jerk/rush near the end.
            // Instead, sample the SAME continuous launch-door curve as the face, only 55 ms late
            // and with 95% travel. The result is one coherent motion from crack to clear-screen:
            // subtle depth, no independent acceleration and no final snap.
            val elapsedMs = 610f + openProgress.value.coerceIn(0f, 1f) * 1770f
            val rearElapsedMs = (elapsedMs - 55f).coerceAtLeast(610f)
            val rearBaseTravel = when {
                rearElapsedMs < 1180f -> {
                    val t = ((rearElapsedMs - 610f) / 570f).coerceIn(0f, 1f)
                    0.075f * FastOutSlowInEasing.transform(t)
                }
                rearElapsedMs < 2080f -> {
                    val t = ((rearElapsedMs - 1180f) / 900f).coerceIn(0f, 1f)
                    val smooth = t * t * (3f - 2f * t)
                    0.075f + 0.805f * smooth
                }
                rearElapsedMs < 2320f -> {
                    val t = ((rearElapsedMs - 2080f) / 240f).coerceIn(0f, 1f)
                    0.880f + 0.360f * FastOutSlowInEasing.transform(t)
                }
                else -> 1.240f
            }
            rearBaseTravel * 0.95f
        }

        val facePx = fullWidthPx * 0.505f * faceTravel
        val underPx = fullWidthPx * 0.505f * underTravel

        // The authored faces include transparent bevels at their outer corners and centre seam.
        // Overscale the faces so they cover beyond every screen edge and naturally interlock at
        // the centre; a small extra seam bite remains to guarantee there is never a visible slit.
        val seamT = ((0.18f - faceTravel) / 0.18f).coerceIn(0f, 1f)
        val seamOverlapPx = fullWidthPx * 0.012f * FastOutSlowInEasing.transform(seamT)

        // IMPORTANT: intentionally NO black/scrim here. The live old/new destination remains
        // visible through every transparent bevel/detail in the door art for the entire sequence.

        // Secondary launch-style layer creates the same deep double-door reveal as cold launch.
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.5f)
                .graphicsLayer { translationX = -underPx }
        ) {
            AssetFrame(
                "images/ui/intro/door_left_underlay.webp",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.5f)
                .graphicsLayer { translationX = underPx }
        ) {
            AssetFrame(
                "images/ui/intro/door_right_underlay.webp",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }

        // Authored GG / WP faces.
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.5f)
                .graphicsLayer {
                    translationX = -facePx + seamOverlapPx
                    scaleX = 1.16f
                    scaleY = 1.22f
                }
        ) {
            AssetFrame(
                "images/ui/victory/exit_door_left.png",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.5f)
                .graphicsLayer {
                    translationX = facePx - seamOverlapPx
                    scaleX = 1.16f
                    scaleY = 1.22f
                }
        ) {
            AssetFrame(
                "images/ui/victory/exit_door_right.png",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }

        // Centre-impact spark shower: fast orange/white embers shoot LEFT and RIGHT from the
        // seam, fan vertically, curl a little, and fall under light gravity. This replaces the
        // old static vertical orange stripe.
        val impactP = impactBurst.value.coerceIn(0f, 1f)
        if (impactP < 0.999f) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val seconds = impactP * 0.76f

                val hot = (1f - (seconds / 0.18f).coerceIn(0f, 1f))
                if (hot > 0f) {
                    val centre = Offset(w * 0.5f, h * 0.52f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = hot * 0.34f),
                                Color(0xFFFFA13B).copy(alpha = hot * 0.28f),
                                Color.Transparent
                            ),
                            center = centre,
                            radius = w * 0.13f
                        ),
                        center = centre,
                        radius = w * 0.13f
                    )
                }

                repeat(108) { i ->
                    val a = ((i * 73 + 17) % 257) / 256f
                    val b = ((i * 131 + 41) % 263) / 262f
                    val c = ((i * 47 + 9) % 251) / 250f
                    val delayS = (((i * 29) % 37) / 36f) * 0.10f
                    val age = seconds - delayS
                    if (age <= 0f || age >= 0.70f) return@repeat

                    val dir = if (i % 2 == 0) -1f else 1f
                    val startX = w * (0.5f + (a - 0.5f) * 0.016f)
                    val startY = h * (0.52f + (b - 0.5f) * 0.12f)
                    val vx = dir * w * (0.52f + a * 0.88f)
                    val vy = h * ((b - 0.5f) * 0.62f - 0.12f)
                    val gravity = h * (0.34f + c * 0.30f)
                    val curl = sin(age * 18.0f + i * 0.71f) * w * (0.004f + c * 0.006f)

                    val x = startX + vx * age + curl
                    val y = startY + vy * age + 0.5f * gravity * age * age
                    if (x < -w * 0.08f || x > w * 1.08f || y < -h * 0.08f || y > h * 1.08f) {
                        return@repeat
                    }

                    val fade = (1f - age / 0.70f).coerceIn(0f, 1f)
                    val sparkColor = when (i % 6) {
                        0 -> Color.White
                        1, 2 -> Color(0xFFFFC46A)
                        else -> Color(0xFFFF8A2A)
                    }
                    val tailSeconds = 0.030f + c * 0.020f
                    val tx = x - vx * tailSeconds
                    val ty = y - (vy + gravity * age) * tailSeconds
                    drawLine(
                        color = sparkColor.copy(alpha = fade * 0.86f),
                        start = Offset(tx, ty),
                        end = Offset(x, y),
                        strokeWidth = 1.2f + a * 2.1f
                    )
                    if (i % 5 == 0) {
                        drawCircle(
                            color = sparkColor.copy(alpha = fade * 0.30f),
                            radius = 2.4f + b * 2.8f,
                            center = Offset(x, y)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(
    player: SoundPlayer,
    launchIntroActive: Boolean,
    entranceFinished: Boolean,
    onEntranceFinished: () -> Unit,
    releaseUpdate: ReleaseUpdateInfo?,
    onUpdate: (ReleaseUpdateInfo) -> Unit,
    onStart: () -> Unit,
    onCollection: () -> Unit,
    onRules: () -> Unit,
    onLegal: () -> Unit
) {
    val context = LocalContext.current
    var revealStep by rememberSaveable { mutableIntStateOf(if (entranceFinished) 8 else 0) }
    var showTipOptions by rememberSaveable { mutableStateOf(false) }
    var showFeedbackOptions by rememberSaveable { mutableStateOf(false) }

    // The live wallpaper/VFX are behind the blast door from frame zero, but controls stay hidden
    // until the door has completely cleared AND the wallpaper fade has finished. Then the console
    // comes online in a deliberate top-to-bottom, left-to-right sequence.
    LaunchedEffect(launchIntroActive, entranceFinished) {
        when {
            entranceFinished -> revealStep = 8
            launchIntroActive -> revealStep = 0
            else -> {
                delay(75L)
                for (step in 1..7) {
                    revealStep = step
                    player.playUi("audio/ui/intro/button_beep.wav", gain = 0.56f)
                    delay(if (step == 1) 135L else 105L)
                }
                delay(105L)
                revealStep = 8
                delay(280L)
                onEntranceFinished()
            }
        }
    }
    if (showTipOptions) {
        AlertDialog(
            onDismissRequest = { showTipOptions = false },
            title = { Text("TIP JAR") },
            text = {
                Text(
                    "Choose whichever payment method is easiest. Ko-fi can offer card or wallet options available on your device, " +
                        "and PayPal Direct remains available separately."
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            showTipOptions = false
                            launchUri(context, "https://ko-fi.com/xCriticalStrikex")
                        }
                    ) { Text("KO-FI / CARD & WALLET") }
                    TextButton(
                        onClick = {
                            showTipOptions = false
                            launchUri(
                                context,
                                "https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=PsychoG13%40hotmail.com&currency_code=USD"
                            )
                        }
                    ) { Text("PAYPAL DIRECT") }
                    TextButton(onClick = { showTipOptions = false }) { Text("CANCEL") }
                }
            }
        )
    }

    if (showFeedbackOptions) {
        AlertDialog(
            onDismissRequest = { showFeedbackOptions = false },
            title = { Text("BUG REPORT / FEEDBACK") },
            text = {
                Text(
                    "Use the feedback form for bugs, audio issues, rules/content problems, or feature suggestions. " +
                        "You can also email the developer directly if you prefer."
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            showFeedbackOptions = false
                            launchUri(
                                context,
                                "https://docs.google.com/forms/d/e/1FAIpQLSf2e6eNuDfbqbnFLW6jFKLpJmb9ybVETlefjyh8Kjn-st_T0w/viewform"
                            )
                        }
                    ) { Text("OPEN FEEDBACK FORM") }
                    TextButton(
                        onClick = {
                            showFeedbackOptions = false
                            launchEmail(context, "xCriticalStrikex@gmail.com", "SC2 TMG Companion feedback / contact")
                        }
                    ) { Text("EMAIL DEVELOPER") }
                    TextButton(onClick = { showFeedbackOptions = false }) { Text("CANCEL") }
                }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF02060B))) {
        StablePortraitBackground("images/ui/welcome_final.png")
        WelcomeVfxLayer()

        Column(
            modifier = Modifier.fillMaxSize().padding(top = adaptiveTopSystemInset()).navigationBarsPadding()
                .padding(horizontal = 34.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(if (releaseUpdate == null) 352.dp else 300.dp))

            if (revealStep >= 1) {
                releaseUpdate?.let { update ->
                    WelcomeUpdateBanner(update, onUpdate = { onUpdate(update) })
                    Spacer(Modifier.height(8.dp))
                }
            }

            WelcomeTextButton(
                label = "ENTER",
                asset = "images/ui/push_buttons/terran_off.png",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 13.sp,
                visible = revealStep >= 1,
                onClick = onStart
            )
            Spacer(Modifier.height(9.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WelcomeTextButton(
                    label = "COLLECTION",
                    asset = "images/ui/push_buttons/terran_off.png",
                    modifier = Modifier.weight(1f),
                    fontSize = 9.2.sp,
                    visible = revealStep >= 2,
                    onClick = onCollection
                )
                WelcomeTextButton(
                    label = "RULES",
                    asset = "images/ui/push_buttons/terran_off.png",
                    modifier = Modifier.weight(1f),
                    fontSize = 9.8.sp,
                    visible = revealStep >= 3,
                    onClick = onRules
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WelcomeTextButton(
                    label = "TIP JAR",
                    asset = "images/ui/push_buttons/protoss_off.png",
                    modifier = Modifier.weight(1f),
                    fontSize = 9.2.sp,
                    visible = revealStep >= 4
                ) { showTipOptions = true }
                WelcomeTextButton(
                    label = "BUG REPORT\nFEEDBACK",
                    asset = "images/ui/push_buttons/zerg_off.png",
                    modifier = Modifier.weight(1f),
                    fontSize = 7.1.sp,
                    maxLines = 2,
                    visible = revealStep >= 5
                ) { showFeedbackOptions = true }
            }

            Spacer(Modifier.height(9.dp))
            WelcomeNameCredit(
                visible = revealStep >= 8,
                animate = !entranceFinished
            )
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WelcomeTextButton(
                    label = "OFFICIAL WEBSITE",
                    asset = "images/ui/push_buttons/terran_off.png",
                    modifier = Modifier.weight(1f),
                    fontSize = 7.8.sp,
                    visible = revealStep >= 6
                ) { launchUri(context, "https://starcraft-tmg.com/") }
                WelcomeTextButton(
                    label = "LEGAL NOTICE",
                    asset = "images/ui/push_buttons/terran_off.png",
                    modifier = Modifier.weight(1f),
                    fontSize = 8.2.sp,
                    visible = revealStep >= 7,
                    onClick = onLegal
                )
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun WelcomeUpdateBanner(info: ReleaseUpdateInfo, onUpdate: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onUpdate),
        color = Color(0xE20B1E2B),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.78f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.SystemUpdate, null, Modifier.size(18.dp), tint = Color(0xFF8FD6FF))
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text("UPDATE AVAILABLE • v${info.versionName}", fontSize = 8.2.sp, fontWeight = FontWeight.Black, letterSpacing = 0.35.sp)
                Text(info.notes.ifBlank { "New SC2 TMG content is ready." }, fontSize = 6.5.sp, lineHeight = 8.sp, color = Color.White.copy(alpha = 0.62f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(5.dp))
            Text("UPDATE", fontSize = 7.3.sp, fontWeight = FontWeight.Black, color = Color(0xFF8FD6FF))
        }
    }
}

@Composable
private fun FirstRunReleaseWelcomeDialog(
    onConfirm: (SoundBankMode) -> Unit
) {
    var pendingMode by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf(0) }
    val selectedMode = pendingMode?.let { runCatching { SoundBankMode.valueOf(it) }.getOrNull() }
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.80f),
            color = Color(0xFA071019),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, Color(0xFF77C7FF).copy(alpha = 0.72f)),
            shadowElevation = 20.dp
        ) {
            if (page == 0) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Text(
                        "WELCOME, GENERAL",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.15.sp,
                        color = Color(0xFFBCE8FF)
                    )
                    Text(
                        buildAnnotatedString {
                            append("Welcome to ")
                            withStyle(SpanStyle(color = Color(0xFFFF63C7), fontWeight = FontWeight.Black)) {
                                append("x Critical Strike x")
                            }
                            append("’s SC TMG Companion.")
                        },
                        fontSize = 14.2.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 18.6.sp,
                        color = Color.White.copy(alpha = 0.94f)
                    )
                    Text(
                        "This companion is a labour of love, built to make StarCraft TMG feel even more alive at the table and to keep the practical parts of the game close at hand.",
                        fontSize = 12.2.sp,
                        lineHeight = 16.7.sp,
                        color = Color.White.copy(alpha = 0.78f)
                    )

                    HorizontalDivider(color = Color(0xFF77C7FF).copy(alpha = 0.18f))
                    Text(
                        "Here’s what the app can do for you:",
                        fontSize = 13.6.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFFFD18A)
                    )
                    Text("• Guide your match through rounds, phases, scoring, supply and victory", fontSize = 11.6.sp, lineHeight = 15.8.sp, color = Color.White.copy(alpha = 0.88f))
                    Text("• Bring units, buildings, weapons and tactical cards to life with StarCraft audio", fontSize = 11.6.sp, lineHeight = 15.8.sp, color = Color.White.copy(alpha = 0.88f))
                    Text("• Play faction-aware music through Jimmy’s Jukebox", fontSize = 11.6.sp, lineHeight = 15.8.sp, color = Color.White.copy(alpha = 0.88f))
                    Text("• Give you quick access to the rules, plus a growing collection of StarCraft units you can add to or remove from the app whenever you like", fontSize = 11.6.sp, lineHeight = 15.8.sp, color = Color.White.copy(alpha = 0.88f))
                    Text("• Let you choose your favourite Terran, Protoss or Zerg cosmetic theme for the app", fontSize = 11.6.sp, lineHeight = 15.8.sp, color = Color.White.copy(alpha = 0.88f))

                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { page = 1 },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1C5C7D),
                            contentColor = Color.White
                        )
                    ) {
                        Text("NEXT", fontSize = 12.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
                    }
                }
            } else {
                val setupScrollState = rememberScrollState()
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(setupScrollState)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    TextButton(
                        onClick = { page = 0 },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) {
                        Text("‹ BACK", fontSize = 9.2.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8FD6FF))
                    }
                    Text(
                        "ONE LAST THING",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.9.sp,
                        color = Color(0xFFBCE8FF)
                    )
                    Text(
                        "Before you begin, choose which StarCraft audio library you’d like the app to favour:",
                        fontSize = 12.sp,
                        lineHeight = 15.8.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFFFD18A)
                    )

                    ReleaseSoundChoiceButton(
                        title = "SC2 PREFERRED",
                        subtitle = "SC2 first, with SC1/Brood War used where no SC2 equivalent exists.",
                        selected = selectedMode == SoundBankMode.SC2_PREFERRED,
                        accent = Color(0xFF57C7FF),
                        onClick = { pendingMode = SoundBankMode.SC2_PREFERRED.name }
                    )
                    ReleaseSoundChoiceButton(
                        title = "SC1 / BROOD WAR PREFERRED",
                        subtitle = "Classic audio first, with SC2 used where no classic equivalent exists.",
                        selected = selectedMode == SoundBankMode.CLASSIC_PREFERRED,
                        accent = Color(0xFFFFB347),
                        onClick = { pendingMode = SoundBankMode.CLASSIC_PREFERRED.name }
                    )
                    ReleaseSoundChoiceButton(
                        title = "MIXED",
                        subtitle = "Use both libraries together.",
                        selected = selectedMode == SoundBankMode.MIXED,
                        accent = Color(0xFFE56BFF),
                        onClick = { pendingMode = SoundBankMode.MIXED.name }
                    )

                    Text(
                        "You can change this at any time in Jimmy’s Jukebox.",
                        fontSize = 9.2.sp,
                        lineHeight = 12.2.sp,
                        color = Color.White.copy(alpha = 0.58f)
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    Text(
                        "I hope you enjoy the app and that it brings a little more StarCraft to your table. GLHF, General.",
                        fontSize = 10.4.sp,
                        lineHeight = 14.2.sp,
                        color = Color.White.copy(alpha = 0.86f)
                    )
                    Text(
                        "If you enjoy the app, consider transferring some spare minerals using the Tip Jar.",
                        fontSize = 9.8.sp,
                        lineHeight = 13.4.sp,
                        color = Color.White.copy(alpha = 0.74f)
                    )
                    Text("With love,", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.82f))
                    Text(
                        "x Critical Strike x",
                        fontSize = 11.2.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFFF63C7)
                    )

                    Spacer(Modifier.height(3.dp))
                    Button(
                        onClick = { selectedMode?.let(onConfirm) },
                        enabled = selectedMode != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1C5C7D),
                            contentColor = Color.White,
                            disabledContainerColor = Color.White.copy(alpha = 0.08f),
                            disabledContentColor = Color.White.copy(alpha = 0.30f)
                        )
                    ) {
                        Text(
                            "CONFIRM & CONTINUE",
                            fontSize = 10.8.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.65.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseSoundChoiceButton(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) accent.copy(alpha = 0.20f) else accent.copy(alpha = 0.075f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            if (selected) 1.8.dp else 1.dp,
            if (selected) accent else accent.copy(alpha = 0.42f)
        ),
        shadowElevation = if (selected) 6.dp else 0.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = accent,
                    unselectedColor = accent.copy(alpha = 0.65f)
                )
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 10.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.42.sp, color = accent)
                Text(subtitle, fontSize = 9.1.sp, lineHeight = 12.3.sp, color = Color.White.copy(alpha = 0.72f))
            }
        }
    }
}

@Composable
private fun WelcomeTextButton(
    label: String,
    asset: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 9.5.sp,
    maxLines: Int = 1,
    visible: Boolean = true,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedAsset = remember(asset) { asset.replace("_off.png", "_on.png") }
    val revealAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 155, easing = FastOutSlowInEasing),
        label = "welcomeButtonAlpha"
    )
    val revealScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.90f,
        animationSpec = tween(durationMillis = 175, easing = FastOutSlowInEasing),
        label = "welcomeButtonScale"
    )
    Box(
        modifier = modifier.aspectRatio(1448f / 416f)
            .graphicsLayer {
                val pressScale = if (pressed) 0.975f else 1f
                scaleX = revealScale * pressScale
                scaleY = revealScale * pressScale
                alpha = revealAlpha * if (pressed) 1f else 0.985f
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = visible,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AssetFrame(asset, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        AssetFrame(
            pressedAsset,
            Modifier.fillMaxSize().alpha(if (pressed) 1f else 0f),
            contentScale = ContentScale.Fit
        )
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(0.68f),
            fontSize = fontSize,
            lineHeight = (fontSize.value + 1.1f).sp,
            fontWeight = FontWeight.Black,
            letterSpacing = if (fontSize.value < 8f) 0.28.sp else 0.58.sp,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = if (pressed) 1f else 0.94f),
            maxLines = maxLines,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun WelcomeNameCredit(
    visible: Boolean,
    animate: Boolean
) {
    val alpha = remember { Animatable(if (visible && !animate) 1f else 0f) }
    val xJitter = remember { Animatable(0f) }

    LaunchedEffect(visible, animate) {
        if (!visible) {
            alpha.snapTo(0f)
            xJitter.snapTo(0f)
        } else if (!animate) {
            alpha.snapTo(1f)
            xJitter.snapTo(0f)
        } else {
            alpha.snapTo(0f)
            xJitter.snapTo(-7f)
            launch { alpha.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
            delay(42L); xJitter.snapTo(4f)
            delay(36L); xJitter.snapTo(-2.5f)
            delay(32L); xJitter.snapTo(1.2f)
            delay(28L); xJitter.animateTo(0f, tween(95))
        }
    }

    Column(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha.value
            translationX = xJitter.value
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "APP BY GRAEME COOPER-VOLKHEIMER",
            fontSize = 8.4.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.62.sp,
            color = Color(0xFFE7F5FF).copy(alpha = 0.92f),
            maxLines = 1
        )
    }
}

@Composable
private fun GameSetupScreen(
    gameState: GameStateStore,
    pendingEffects: PendingEffectsStore,
    musicStore: MusicStore,
    themeStore: ThemeStore,
    musicPlayer: MusicPlayer,
    onBack: () -> Unit,
    onResume: () -> Unit,
    onStartNewGame: () -> Unit
) {
    val context = LocalContext.current
    // Setup is always an editable draft. An active game's committed identity remains untouched
    // in GameStateStore until START NEW GAME is confirmed; RESUME GAME simply ignores this draft.
    val identityLocked = false
    var playerCount by rememberSaveable { mutableIntStateOf(if (gameState.hasSession) gameState.playerCount.coerceIn(2, 3) else 2) }
    var factionA by rememberSaveable { mutableIntStateOf(gameState.playerFactionA) }
    var factionB by rememberSaveable { mutableIntStateOf(gameState.playerFactionB) }
    var factionC by rememberSaveable { mutableIntStateOf(gameState.playerFactionC) }
    // Music is part of the setup draft too. Toggling it here may preview/stop menu music, but it
    // does not mutate the preserved active match preference unless START NEW GAME is committed.
    val committedMusicAtEntry = remember { musicStore.playbackEnabled }
    var musicOn by rememberSaveable { mutableStateOf(committedMusicAtEntry) }
    var skipUnused by rememberSaveable { mutableStateOf(musicStore.skipUnusedFactionPages) }
    var confirmOverwrite by rememberSaveable { mutableStateOf(false) }
    var terranTheme by rememberSaveable { mutableIntStateOf(themeStore.index(Faction.TERRAN) % factionThemeOptions(Faction.TERRAN).size) }
    var protossTheme by rememberSaveable { mutableIntStateOf(themeStore.index(Faction.PROTOSS) % factionThemeOptions(Faction.PROTOSS).size) }
    var zergTheme by rememberSaveable { mutableIntStateOf(themeStore.index(Faction.ZERG) % factionThemeOptions(Faction.ZERG).size) }

    fun themeIndex(faction: Faction): Int = when (faction) {
        Faction.TERRAN -> terranTheme
        Faction.PROTOSS -> protossTheme
        Faction.ZERG -> zergTheme
        Faction.HYBRID -> 0
    }

    fun cycleTheme(faction: Faction) {
        val size = factionThemeOptions(faction).size
        when (faction) {
            Faction.TERRAN -> { terranTheme = (terranTheme + 1) % size; themeStore.setIndex(faction, terranTheme) }
            Faction.PROTOSS -> { protossTheme = (protossTheme + 1) % size; themeStore.setIndex(faction, protossTheme) }
            Faction.ZERG -> { zergTheme = (zergTheme + 1) % size; themeStore.setIndex(faction, zergTheme) }
            Faction.HYBRID -> Unit
        }
    }

    fun faction(index: Int): Faction = when (index) {
        1 -> Faction.PROTOSS
        2 -> Faction.ZERG
        else -> Faction.TERRAN
    }

    fun beginNewGame() {
        pendingEffects.clear()
        gameState.configureNewGame(playerCount, factionA, factionB, factionC)
        musicStore.skipUnusedFactionPages = skipUnused
        musicStore.playbackEnabled = musicOn
        if (musicOn) {
            val selected = buildSet {
                add(faction(factionA))
                if (playerCount >= 2) add(faction(factionB))
                if (playerCount >= 3) add(faction(factionC))
            }
            listOf(Faction.TERRAN, Faction.PROTOSS, Faction.ZERG).forEach { f ->
                musicStore.setFactionEnabled(f, f in selected)
            }
            musicPlayer.setEnabledFactions(selected)
            // Confirming a new setup discards the old match soundtrack and fades title music
            // away. The multiplayer Jukebox starts only after START GAME -> GLHF -> Round 1.
            musicPlayer.stopMenuForNewGame(fadeMs = 650)
        } else {
            musicPlayer.stop()
        }
        onStartNewGame()
    }

    fun resumePreservedGame() {
        // Discard all uncommitted setup choices, including the draft music toggle. The callback
        // then restores the active match (and its paused Jukebox position when music was enabled).
        musicStore.playbackEnabled = committedMusicAtEntry
        if (!committedMusicAtEntry) musicPlayer.stopMenuOnly(fadeMs = 260)
        onResume()
    }

    fun backToWelcomeWithoutCommittingDraft() {
        // Going back is not START NEW GAME. Restore the previously committed music preference so
        // a temporary draft toggle cannot silently mutate the saved match or the welcome screen.
        musicStore.playbackEnabled = committedMusicAtEntry
        if (committedMusicAtEntry) musicPlayer.playMenuOrResume(crossFadeMs = 360)
        else musicPlayer.stopMenuOnly(fadeMs = 260)
        onBack()
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("Start a new game?") },
            text = { Text("This replaces the saved tracker state and clears all pending battlefield effects. Your collection, audio volumes and voice choice are kept.") },
            confirmButton = {
                TextButton(onClick = { confirmOverwrite = false; beginNewGame() }) {
                    Text("START NEW GAME", fontWeight = FontWeight.Black)
                }
            },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("CANCEL") } }
        )
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF02060B))) {
        TrackerBackdrop()

        Column(
            Modifier.fillMaxSize().padding(top = adaptiveTopSystemInset()).navigationBarsPadding()
                .padding(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GameSetupTitleArt()
            Spacer(Modifier.height(4.dp))
            GameSetupHeaderBar(onBack = ::backToWelcomeWithoutCommittingDraft)
            Spacer(Modifier.height(7.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xD9080E15),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.2.dp, Color(0xFF77C7FF).copy(alpha = 0.42f))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("PLAYERS", fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.15.sp, color = Color.White.copy(alpha = 0.56f))
                            Text(
                                "2 OR 3 PLAYERS • ACTIVE MATCH IS PRESERVED UNTIL YOU START THIS DRAFT",
                                fontSize = 7.2.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.42.sp,
                                color = Color.White.copy(alpha = 0.38f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            (2..3).forEach { count ->
                                SetupChoiceButton(
                                    text = count.toString(),
                                    selected = playerCount == count,
                                    modifier = Modifier.width(42.dp),
                                    accent = Color(0xFF77C7FF),
                                    enabled = !identityLocked
                                ) { playerCount = count }
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(Faction.TERRAN, Faction.PROTOSS, Faction.ZERG).forEachIndexed { index, f ->
                            FactionAssignmentCard(
                                faction = f,
                                theme = factionThemeOption(f, themeIndex(f)),
                                selectedPlayers = buildSet {
                                    if (factionA == index) add(0)
                                    if (playerCount >= 2 && factionB == index) add(1)
                                    if (playerCount >= 3 && factionC == index) add(2)
                                },
                                playerCount = playerCount,
                                modifier = Modifier.weight(1f),
                                assignmentEnabled = !identityLocked,
                                onCycleTheme = { cycleTheme(f) },
                                onAssign = { playerIndex ->
                                    when (playerIndex) {
                                        0 -> factionA = index
                                        1 -> factionB = index
                                        2 -> factionC = index
                                    }
                                }
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SetupTogglePanel(
                            title = "MUSIC",
                            checked = musicOn,
                            modifier = Modifier.weight(1f),
                            accent = Color(0xFF77C7FF),
                            onCheckedChange = { enabled ->
                                musicOn = enabled
                                if (enabled) {
                                    val result = musicPlayer.playMenuOrResume(crossFadeMs = 420)
                                    if (!result.success) Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                } else {
                                    // Silence only the setup/title deck. A running game's paused
                                    // Jukebox snapshot must survive in case RESUME GAME is pressed.
                                    musicPlayer.stopMenuOnly(fadeMs = 300)
                                }
                            }
                        )
                        SetupTogglePanel(
                            title = "SKIP UNUSED",
                            checked = skipUnused,
                            modifier = Modifier.weight(1f),
                            accent = Color(0xFF77C7FF),
                            onCheckedChange = { skipUnused = it }
                        )
                    }
                }
            }

            Spacer(Modifier.height(9.dp))
            if (gameState.hasSession) {
                val phaseNames = listOf("Movement", "Assault", "Combat", "Scoring & Cleanup")
                val resumeLine = if (gameState.gameStarted) {
                    "Round ${gameState.round} • ${phaseNames.getOrElse(gameState.phase) { "Movement" }} • ${gameState.playerCount}P"
                } else "Ready • ${gameState.playerCount}P"
                SetupGlowButton(
                    label = "RESUME GAME",
                    subtitle = resumeLine,
                    accent = Color(0xFF72D59C),
                    icon = Icons.Default.Restore,
                    onClick = ::resumePreservedGame
                )
                Spacer(Modifier.height(7.dp))
            }
            SetupGlowButton(
                label = "START NEW GAME",
                subtitle = if (skipUnused) "Unused faction pages hidden" else "All faction pages enabled",
                accent = Color(0xFF77C7FF),
                icon = Icons.Default.PlayArrow,
                onClick = { if (gameState.hasSession) confirmOverwrite = true else beginNewGame() }
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun GameSetupHeaderBar(onBack: () -> Unit) {
    val accent = Color(0xFF77C7FF)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xF0060B11),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Same compact square Back control used by the rest of the app.
            CompactHudIconButton(Icons.Default.ArrowBack, "Back", accent, onBack)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    "ASSIGN PLAYERS A / B / C TO FACTIONS",
                    fontSize = 10.0.sp,
                    lineHeight = 10.8.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.48.sp,
                    color = Color.White.copy(alpha = 0.90f),
                    maxLines = 1
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Tap a faction emblem to change its sub-faction theme",
                    fontSize = 8.6.sp,
                    lineHeight = 9.4.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.58f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun GameSetupTitleArt() {
    // User-supplied plaque is now trimmed to the artwork itself. Keep the same page hierarchy:
    // title -> back/info bar -> setup body.
    Box(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        contentAlignment = Alignment.Center
    ) {
        AssetFrame(
            "images/ui/screen_titles/game_setup.png",
            Modifier.fillMaxWidth(0.985f).height(56.dp).offset(y = (-10).dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun HudTitlePlate(title: String, accent: Color, onBack: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        color = Color(0xEB071019),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            HudIconButton(Icons.Default.ArrowBack, "Back", accent, onBack)
            Text(title, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp, color = Color.White)
            Spacer(Modifier.width(44.dp))
        }
    }
}

@Composable
private fun FactionAssignmentCard(
    faction: Faction,
    theme: FactionThemeOption,
    selectedPlayers: Set<Int>,
    playerCount: Int,
    modifier: Modifier = Modifier,
    assignmentEnabled: Boolean = true,
    onCycleTheme: () -> Unit,
    onAssign: (Int) -> Unit
) {
    val accent = theme.accent
    val selected = selectedPlayers.isNotEmpty()
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier.height(214.dp),
        color = Color(0xF1091018),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            if (selected) 1.8.dp else 1.dp,
            if (selected) accent.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.12f)
        )
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(76.dp)
                    .clip(CircleShape)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCycleTheme()
                    }
                    .border(1.4.dp, if (selected) accent.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                AssetFrame(theme.asset, Modifier.fillMaxSize(0.86f), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.height(1.dp))
            Text(faction.label, fontSize = 8.4.sp, fontWeight = FontWeight.Black, letterSpacing = 0.62.sp, color = Color.White)
            Text(
                theme.label,
                fontSize = 6.2.sp,
                lineHeight = 6.8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.36.sp,
                color = accent.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            Text(
                "TAP EMBLEM TO CHANGE",
                fontSize = 4.9.sp,
                lineHeight = 5.4.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.26.sp,
                color = Color.White.copy(alpha = 0.48f),
                maxLines = 1
            )
            Spacer(Modifier.height(5.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("A", "B", "C").forEachIndexed { playerIndex, label ->
                    val enabled = playerIndex < playerCount
                    val canAssign = enabled && assignmentEnabled
                    val active = playerIndex in selectedPlayers
                    Surface(
                        onClick = { if (canAssign) onAssign(playerIndex) },
                        modifier = Modifier.fillMaxWidth().height(29.dp),
                        enabled = canAssign,
                        color = if (active) Color(0xFF17202A) else Color(0xFF0D141C),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(
                            if (active) 1.5.dp else 1.dp,
                            if (active) accent.copy(alpha = 0.84f) else Color.White.copy(alpha = if (enabled) 0.12f else 0.05f)
                        )
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "PLAYER $label",
                                fontSize = 7.6.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.35.sp,
                                color = if (enabled) Color.White.copy(alpha = if (active) 1f else 0.70f) else Color.White.copy(alpha = 0.18f)
                            )
                            Spacer(Modifier.weight(1f))
                            if (active) {
                                Text(if (assignmentEnabled) "✓" else "LOCK", fontSize = if (assignmentEnabled) 9.sp else 6.2.sp, fontWeight = FontWeight.Black, color = accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupTogglePanel(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = modifier.height(52.dp),
        color = Color(0xE70A1119),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (checked) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f))
    ) {
        Row(Modifier.fillMaxSize().padding(start = 9.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontSize = 8.1.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp, color = Color.White.copy(alpha = 0.88f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SetupGlowButton(
    label: String,
    subtitle: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        color = Color(0xE90A1119),
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.4.dp, accent.copy(alpha = 0.68f))
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(23.dp), tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, fontSize = 14.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp, color = Color.White)
                Text(subtitle, fontSize = 7.4.sp, color = Color.White.copy(alpha = 0.62f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(33.dp))
        }
    }
}

@Composable
private fun SetupChoiceButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(36.dp),
        color = if (selected) accent.copy(alpha = 0.22f) else Color(0xE80B121B),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) accent.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.11f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.55.sp, color = if (selected) Color.White else Color.White.copy(alpha = 0.58f))
        }
    }
}

@Composable
private fun GameLengthSelector(
    gameLength: Int,
    onGameLengthChange: (Int) -> Unit
) {
    val accent = Color(0xFF77C7FF)
    val customSelected = gameLength >= 6
    val customValue = if (customSelected) gameLength else 6
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xE8091119),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "GAME LENGTH",
                    fontSize = 10.2.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.0.sp,
                    color = Color(0xFF8FD6FF)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "SET TO YOUR MISSION CARD",
                    fontSize = 6.8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.34.sp,
                    color = Color.White.copy(alpha = 0.42f)
                )
            }
            Text(
                "Rules: the mission sets the round limit; it is usually 5 rounds.",
                fontSize = 7.5.sp,
                lineHeight = 8.2.sp,
                color = Color.White.copy(alpha = 0.56f)
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GameLengthQuickChoice(
                    value = 4,
                    caption = "SHORT / MISSION",
                    selected = gameLength == 4,
                    modifier = Modifier.weight(1f),
                    onClick = { onGameLengthChange(4) }
                )
                GameLengthQuickChoice(
                    value = 5,
                    caption = "USUAL",
                    selected = gameLength == 5,
                    modifier = Modifier.weight(1f),
                    onClick = { onGameLengthChange(5) }
                )
                Surface(
                    modifier = Modifier.weight(1.36f).height(50.dp),
                    color = if (customSelected) accent.copy(alpha = 0.16f) else Color(0xFF0D151D),
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(
                        if (customSelected) 1.4.dp else 1.dp,
                        if (customSelected) accent.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.10f)
                    )
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CompactCounterButton(Icons.Default.Remove, compact = true) {
                            onGameLengthChange(if (!customSelected) 6 else (gameLength - 1).coerceAtLeast(6))
                        }
                        Column(
                            Modifier.width(43.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("CUSTOM", fontSize = 5.9.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.52f))
                            Text("$customValue", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color.White)
                            Text("6-10", fontSize = 5.4.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.34f))
                        }
                        CompactCounterButton(Icons.Default.Add, compact = true) {
                            onGameLengthChange(if (!customSelected) 6 else (gameLength + 1).coerceAtMost(10))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameLengthQuickChoice(
    value: Int,
    caption: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = Color(0xFF77C7FF)
    Surface(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        color = if (selected) accent.copy(alpha = 0.16f) else Color(0xFF0D151D),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(
            if (selected) 1.4.dp else 1.dp,
            if (selected) accent.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.10f)
        )
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("$value", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text(caption, fontSize = 5.7.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.50f), maxLines = 1)
        }
    }
}

@Composable
private fun SplitStartGameButton(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onLaunch: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(78.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLaunch()
            },
        contentAlignment = Alignment.Center
    ) {
        if (!visible) return@Box
        // The source art is already designed as two mating halves. Give START only a hair of
        // overlap so filtering at the seam cannot create a gap, but never enough to cover GAME.
        AssetFrame(
            "images/ui/game_start/game_right.png",
            Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.506f).height(72.dp),
            contentScale = ContentScale.Fit
        )
        AssetFrame(
            "images/ui/game_start/start_left.png",
            Modifier.align(Alignment.CenterStart).fillMaxWidth(0.506f).height(72.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun StartGameDoorRevealOverlay(
    startBounds: androidx.compose.ui.geometry.Rect,
    modifier: Modifier = Modifier,
    onCovered: () -> Unit,
    onOpenSound: () -> Unit,
    onRevealComplete: () -> Unit
) {
    val timeline = remember { Animatable(0f) }
    val blocker = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        // Keep the whole transition on one uninterrupted clock. The callbacks only change what is
        // hidden behind the full-screen shutter; they never pause or restart the door motion.
        val motion = launch {
            timeline.animateTo(1f, tween(2440, easing = LinearEasing))
        }
        delay(500L)
        onOpenSound()
        delay(200L)
        onCovered()
        motion.join()
        onRevealComplete()
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
            .clickable(interactionSource = blocker, indication = null) { }
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val fullHeightPx = with(density) { maxHeight.toPx() }

        val startWidthPx = startBounds.width.coerceAtLeast(1f)
        val startHeightPx = startBounds.height.coerceAtLeast(1f)
        val startCenterX = startBounds.left + startWidthPx * 0.5f
        val startCenterY = startBounds.top + startHeightPx * 0.5f
        val targetCenterX = fullWidthPx * 0.5f
        val targetCenterY = fullHeightPx * 0.5f

        val elapsedMs = timeline.value * 2440f
        fun smoothStep(value: Float): Float {
            val clamped = value.coerceIn(0f, 1f)
            return clamped * clamped * (3f - 2f * clamped)
        }

        // Hold the plate on its exact button centre while it draws back slightly. Only after that
        // anticipation beat does it move forward. Quadratic progress keeps adding momentum instead
        // of launching at full speed or easing into a visible stop.
        val drawBack = smoothStep(elapsedMs / 260f)
        val forwardLinear = ((elapsedMs - 260f) / 2180f).coerceIn(0f, 1f)
        val forward = forwardLinear * forwardLinear
        val centerX = startCenterX + (targetCenterX - startCenterX) * forward
        val centerY = startCenterY + (targetCenterY - startCenterY) * forward

        // The first target is physical screen cover, not the old command-panel bounds. The final
        // overscan deliberately continues far beyond it so no door edge can die at a UI boundary.
        val coverScale = max(fullWidthPx / startWidthPx, fullHeightPx / startHeightPx) * 1.08f
        val passThroughScale = coverScale * 1.62f
        val drawBackScale = 1f - 0.035f * drawBack
        val scale = drawBackScale + (passThroughScale - 0.965f) * forward

        // Slow mechanical opening begins while the camera is still moving forward: tiny initial
        // crack, deliberate crawl, then a confident release once the structure already owns screen.
        val split = when {
            elapsedMs < 500f -> 0f
            elapsedMs < 760f -> 0.038f * smoothStep((elapsedMs - 500f) / 260f)
            elapsedMs < 1380f -> 0.038f + 0.112f * ((elapsedMs - 760f) / 620f)
            else -> 0.15f + 1.03f * smoothStep((elapsedMs - 1380f) / 1060f)
        }

        val startWidthDp = with(density) { startWidthPx.toDp() }
        val startHeightDp = with(density) { startHeightPx.toDp() }
        val leftPx = centerX - startWidthPx * 0.5f
        val topPx = centerY - startHeightPx * 0.5f

        // Compute the split distance from the actual scaled half-door width. This guarantees both
        // halves clear the physical viewport even after the very large portrait-screen overscan.
        val halfDoorVisibleWidth = startWidthPx * 0.506f * scale
        val clearTravelVisible = fullWidthPx * 0.5f + halfDoorVisibleWidth * 0.5f + with(density) { 24.dp.toPx() }
        val childTravel = if (scale <= 0f) 0f else (clearTravelVisible * split) / scale

        // Fade every surrounding detail to black during the initial draw-back. Once the hidden branch has
        // switched, let the blackout recede through the opening while the physical doors remain solid.
        val blackoutIn = smoothStep(elapsedMs / 700f)
        val blackoutOut = smoothStep((elapsedMs - 900f) / 800f)
        val blackoutAlpha = blackoutIn * (1f - blackoutOut)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blackoutAlpha)))

        Box(
            Modifier
                .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
                .size(startWidthDp, startHeightDp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin.Center
                    clip = false
                }
        ) {
            AssetFrame(
                "images/ui/game_start/game_right.png",
                Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.506f).fillMaxHeight()
                    .graphicsLayer { translationX = childTravel },
                contentScale = ContentScale.Fit
            )
            AssetFrame(
                "images/ui/game_start/start_left.png",
                Modifier.align(Alignment.CenterStart).fillMaxWidth(0.506f).fillMaxHeight()
                    .graphicsLayer { translationX = -childTravel },
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun LegalNoticeScreen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF02060B))) {
        AssetBackground("images/ui/bg_generic.jpg")
        Column(Modifier.fillMaxSize().padding(top = adaptiveTopSystemInset()).navigationBarsPadding().padding(horizontal = 28.dp, vertical = 12.dp)) {
            Surface(color = Color(0xF2050A10), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HudIconButton(Icons.Default.ArrowBack, "Back", Color(0xFF77C7FF), onBack)
                    Text(
                        "LEGAL NOTICE",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.width(44.dp))
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 30.dp)
            ) {
                item {
                    Surface(
                        color = Color(0xED071019),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.28f))
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("UNOFFICIAL FAN-MADE COMPANION", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF8FD6FF))
                            Text(
                                "This application is an independent, unofficial companion made for tabletop play. It is not affiliated with, sponsored by, approved by, or endorsed by Blizzard Entertainment or Archon Studio.",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = Color.White.copy(alpha = 0.82f)
                            )
                            Text(
                                "StarCraft, Blizzard Entertainment, their names, logos, characters, artwork, audio, music, and other associated intellectual property are owned by their respective rights holders. StarCraft tabletop game materials and associated marks are owned and licensed by their respective rights holders, including Blizzard Entertainment and Archon Studio where applicable.",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = Color.White.copy(alpha = 0.82f)
                            )
                            Text(
                                "Graeme Cooper-Volkheimer claims no ownership of third-party StarCraft intellectual property. This notice does not grant any licence or permission to use third-party intellectual property.",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = Color.White.copy(alpha = 0.82f)
                            )
                            Text(
                                "Companion application code, original interface arrangement, and original non-third-party material are separate from the underlying StarCraft intellectual property.",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = Color.White.copy(alpha = 0.82f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainSoundboard(
    store: CollectionStore,
    gameState: GameStateStore,
    pendingEffects: PendingEffectsStore,
    player: SoundPlayer,
    musicStore: MusicStore,
    themeStore: ThemeStore,
    musicPlayer: MusicPlayer,
    refreshTick: Int,
    onNewGameSetup: () -> Unit,
    onHome: () -> Unit,
    onVictoryNewGame: (Faction?) -> Unit,
    onVictoryHome: (Faction?) -> Unit,
    onCollection: () -> Unit,
    onRules: () -> Unit
) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val refresh = refreshTick
    val selectedIds = remember { mutableStateMapOf<Faction, String>() }
    val scope = rememberCoroutineScope()
    var showBuildings by rememberSaveable { mutableStateOf(false) }
    var victoryOverlayActive by rememberSaveable { mutableStateOf(false) }
    var activeVictoryFaction by remember { mutableStateOf<Faction?>(null) }
    var musicUiTick by remember { mutableIntStateOf(0) }
    var jukeboxScreen by rememberSaveable { mutableIntStateOf(0) }
    var enabledMusicFactions by remember { mutableStateOf(musicStore.enabledFactions()) }
    var followMatchMusic by rememberSaveable { mutableStateOf(musicStore.followMatchFactions) }
    var announcerMode by rememberSaveable { mutableStateOf(gameState.announcerMode) }
    val matchIntroPrefs = remember { context.getSharedPreferences("sc2tmg_match_intro", android.content.Context.MODE_PRIVATE) }
    var matchIntroMode by rememberSaveable {
        mutableStateOf(MatchIntroMode.fromStored(matchIntroPrefs.getString("mode", null)))
    }
    var skipUnusedPages by rememberSaveable { mutableStateOf(musicStore.skipUnusedFactionPages) }
    var matchConfigRevision by remember { mutableIntStateOf(0) }
    var lastActualPage by rememberSaveable { mutableIntStateOf(0) }
    var terranThemeIndex by rememberSaveable { mutableIntStateOf(themeStore.index(Faction.TERRAN) % factionThemeOptions(Faction.TERRAN).size) }
    var protossThemeIndex by rememberSaveable { mutableIntStateOf(themeStore.index(Faction.PROTOSS) % factionThemeOptions(Faction.PROTOSS).size) }
    var zergThemeIndex by rememberSaveable { mutableIntStateOf(themeStore.index(Faction.ZERG) % factionThemeOptions(Faction.ZERG).size) }
    var specialAbilityRequest by remember { mutableStateOf<SpecialAbilityRequest?>(null) }
    var abilityNotice by remember { mutableStateOf<AbilityNotice?>(null) }
    var effectBannerText by remember { mutableStateOf<String?>(null) }
    val lastAbilityTapMs = remember { mutableMapOf<String, Long>() }

    LaunchedEffect(effectBannerText) {
        if (effectBannerText != null) {
            delay(1450L)
            effectBannerText = null
        }
    }

    fun themeIndexFor(faction: Faction): Int = when (faction) {
        Faction.TERRAN -> terranThemeIndex
        Faction.PROTOSS -> protossThemeIndex
        Faction.ZERG -> zergThemeIndex
        Faction.HYBRID -> 0
    }

    fun themeFor(faction: Faction): FactionThemeOption =
        factionThemeOption(faction, themeIndexFor(faction))

    fun cycleMainTheme(faction: Faction) {
        val next = (themeIndexFor(faction) + 1) % factionThemeOptions(faction).size
        when (faction) {
            Faction.TERRAN -> terranThemeIndex = next
            Faction.PROTOSS -> protossThemeIndex = next
            Faction.ZERG -> zergThemeIndex = next
            Faction.HYBRID -> return
        }
        themeStore.setIndex(faction, next)
    }

    fun factionFromIndex(index: Int): Faction = when (index) {
        1 -> Faction.PROTOSS
        2 -> Faction.ZERG
        else -> Faction.TERRAN
    }

    // Page skipping is driven only by the factions assigned to active players.
    // Do not gate this behind gameStarted/hasSession; if the tracker is open, the
    // configured player/faction state is authoritative. This also avoids stale old-save
    // flags making the toggle look enabled while all three pages remain visible.
    @Suppress("UNUSED_VARIABLE") val liveMatchConfigRevision = matchConfigRevision
    val selectedMatchFactions = buildSet {
        add(factionFromIndex(gameState.playerFactionA))
        if (gameState.playerCount >= 2) add(factionFromIndex(gameState.playerFactionB))
        if (gameState.playerCount >= 3) add(factionFromIndex(gameState.playerFactionC))
    }
    val selectedMatchMusicKey = selectedMatchFactions.map { it.name }.sorted().joinToString(",")
    val visibleActualPages = if (skipUnusedPages) {
        buildList {
            add(0)
            if (Faction.TERRAN in selectedMatchFactions) add(1)
            if (Faction.PROTOSS in selectedMatchFactions) add(2)
            if (Faction.ZERG in selectedMatchFactions) add(3)
            if (store.hybridEnabled) add(4)
            add(5)
        }
    } else buildList {
        addAll(listOf(0, 1, 2, 3))
        if (store.hybridEnabled) add(4)
        add(5)
    }
    val visiblePagesKey = visibleActualPages.joinToString(",")
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { visibleActualPages.size })

    // System back is route-aware inside the pager: faction/music pages return to GAME;
    // only GAME returns Home. This prevents Samsung Back from feeling like an app-minimize key.
    BackHandler {
        player.stop()
        val gamePage = visibleActualPages.indexOf(0)
        if (gamePage >= 0 && pagerState.currentPage != gamePage) {
            scope.launch { pagerState.animateScrollToPage(gamePage) }
        } else {
            if (victoryOverlayActive) onVictoryHome(activeVictoryFaction) else onHome()
        }
    }

    fun setAnnouncerMode(mode: AnnouncerMode) {
        announcerMode = mode
        gameState.announcerMode = mode
    }

    fun setMatchIntroMode(mode: MatchIntroMode) {
        matchIntroMode = mode
        matchIntroPrefs.edit().putString("mode", mode.name).apply()
    }

    fun applyMusicFactions(enabled: Set<Faction>, crossFadeMs: Int = 3000) {
        val sanitized = enabled.filterTo(linkedSetOf()) { it != Faction.HYBRID }
        musicStore.setEnabledFactions(sanitized)
        enabledMusicFactions = sanitized
        musicPlayer.setEnabledFactions(sanitized, crossFadeMs = crossFadeMs)
        if (gameState.gameStarted && sanitized.isNotEmpty() && musicStore.playbackEnabled && !musicPlayer.isPlaying()) {
            musicPlayer.playOrResume()
        }
        musicUiTick = (musicUiTick + 1) % 100000
    }

    fun syncMusicFactions() {
        val enabled = if (followMatchMusic) selectedMatchFactions else musicStore.enabledFactions()
        applyMusicFactions(enabled, crossFadeMs = 3000)
    }

    fun setMusicFaction(faction: Faction, enabled: Boolean) {
        if (followMatchMusic) {
            followMatchMusic = false
            musicStore.followMatchFactions = false
        }
        val next = if (enabled) enabledMusicFactions + faction else enabledMusicFactions - faction
        applyMusicFactions(next, crossFadeMs = 3000)
    }

    fun setFollowMatchMusic(enabled: Boolean) {
        followMatchMusic = enabled
        musicStore.followMatchFactions = enabled
        if (enabled) applyMusicFactions(selectedMatchFactions, crossFadeMs = 3000)
        musicUiTick = (musicUiTick + 1) % 100000
    }

    fun setSkipUnusedPages(enabled: Boolean) {
        lastActualPage = visibleActualPages.getOrNull(pagerState.currentPage) ?: lastActualPage
        skipUnusedPages = enabled
        musicStore.skipUnusedFactionPages = enabled
    }

    LaunchedEffect(Unit) {
        musicPlayer.setDuckingPower(musicStore.duckingPower)
        syncMusicFactions()
        if (gameState.gameStarted && musicStore.playbackEnabled && !musicPlayer.isPlaying()) musicPlayer.playOrResume()
    }
    LaunchedEffect(selectedMatchMusicKey, followMatchMusic, matchConfigRevision) {
        if (followMatchMusic) applyMusicFactions(selectedMatchFactions, crossFadeMs = 3000)
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(350)
            musicUiTick = (musicUiTick + 1) % 100000
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        visibleActualPages.getOrNull(pagerState.currentPage)?.let { lastActualPage = it }
    }
    LaunchedEffect(visiblePagesKey) {
        val requestedActual = lastActualPage
        val target = visibleActualPages.indexOf(requestedActual).takeIf { it >= 0 } ?: 0
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        lastActualPage = visibleActualPages.getOrElse(target) { 0 }
    }

    val musicRefresh = musicUiTick
    val musicPlayingNow = if (musicRefresh >= 0) musicPlayer.isPlaying() else false

    fun toggleMusic() {
        if (!gameState.gameStarted) {
            // Pre-match this button is only the user's preference; Jimmy waits for START GAME.
            val enabled = !musicStore.playbackEnabled
            musicStore.playbackEnabled = enabled
            if (!enabled) musicPlayer.stop()
            Toast.makeText(context, if (enabled) "Music armed — starts after Round 1" else "Music off", Toast.LENGTH_SHORT).show()
            musicUiTick = (musicUiTick + 1) % 100000
            return
        }
        val result = musicPlayer.toggle()
        if (!result.success) Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        musicStore.playbackEnabled = musicPlayer.isPlaying()
        musicUiTick = (musicUiTick + 1) % 100000
    }

    fun skipMusic() {
        if (!gameState.gameStarted) {
            Toast.makeText(context, "Jimmy's Jukebox starts after START GAME", Toast.LENGTH_SHORT).show()
            return
        }
        val result = musicPlayer.next()
        if (!result.success) Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        if (result.success) musicStore.playbackEnabled = true
        musicUiTick = (musicUiTick + 1) % 100000
    }

    fun pageFaction(actualPage: Int): Faction? = when (actualPage) {
        1 -> Faction.TERRAN
        2 -> Faction.PROTOSS
        3 -> Faction.ZERG
        4 -> Faction.HYBRID
        else -> null
    }

    val musicAccentFaction = musicPlayer.currentTrack()?.faction ?: Faction.TERRAN

    specialAbilityRequest?.let { request ->
        when (request.abilityId) {
            "corrosive_bile" -> AlertDialog(
                onDismissRequest = { specialAbilityRequest = null },
                title = { Text("LAUNCH CORROSIVE BILE?", fontWeight = FontWeight.Black) },
                text = {
                    Text(
                        if (gameState.gameStarted) "This creates one independent Bile instance. Resolve it at the end of the Assault Phase."
                        else "Launch the sound now? No active match is running, so this use will not be added to the pending-effects list."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val result = player.playUnitAbility(request.unit.audioId, request.abilityId)
                        if (result.success) {
                            musicPlayer.duckFor(durationMs = 1250)
                            if (gameState.gameStarted) {
                                val behavior = AbilityBehaviorRegistry.get(request.unit.audioId, request.abilityId)
                                pendingEffects.add(
                                    PendingEffectKind.CORROSIVE_BILE,
                                    gameState.round,
                                    gameState.phase,
                                    request.unit.name,
                                    request.unit.audioId,
                                    request.abilityId,
                                    behavior?.reminder.orEmpty()
                                )
                            }
                            effectBannerText = "BILE LAUNCHED!"
                            specialAbilityRequest = null
                        } else {
                            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("YES", fontWeight = FontWeight.Black) }
                },
                dismissButton = { TextButton(onClick = { specialAbilityRequest = null }) { Text("NO") } }
            )
            "force_field" -> {
                val activeFields = pendingEffects.count(PendingEffectKind.FORCE_FIELD)
                AlertDialog(
                    onDismissRequest = { specialAbilityRequest = null },
                    title = { Text(if (activeFields == 0) "PLACE FORCE FIELD?" else "FORCE FIELD", fontWeight = FontWeight.Black) },
                    text = {
                        Text(
                            if (activeFields == 0)
                                "Set one Force Field token Within 8\" in an unoccupied space. Size 2 or smaller Units cannot cross it. If a Size 3+ model moves over it, remove that field; otherwise it remains until round cleanup."
                            else
                                "$activeFields Force Field${if (activeFields == 1) "" else "s"} currently tracked. PLACE creates another independent field. REMOVE is only for a real tabletop removal event (for example Size 3+ crossing); remaining fields clear visibly at round cleanup."
                        )
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = {
                                val result = player.playAbilityPhase("sentry", "force_field", "place")
                                if (result.success) {
                                    musicPlayer.duckFor(durationMs = 1000)
                                    if (gameState.gameStarted) {
                                        val behavior = AbilityBehaviorRegistry.get(request.unit.audioId, request.abilityId)
                                        pendingEffects.add(
                                            PendingEffectKind.FORCE_FIELD,
                                            gameState.round,
                                            gameState.phase,
                                            request.unit.name,
                                            request.unit.audioId,
                                            request.abilityId,
                                            behavior?.reminder.orEmpty()
                                        )
                                    }
                                    effectBannerText = "FORCE FIELD DEPLOYED!"
                                    specialAbilityRequest = null
                                } else {
                                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                }
                            }) { Text(if (activeFields == 0) "YES" else "PLACE", fontWeight = FontWeight.Black) }
                            if (activeFields > 0) {
                                TextButton(onClick = {
                                    val candidate = pendingEffects.effects.firstOrNull { it.kind == PendingEffectKind.FORCE_FIELD }
                                    if (candidate != null) {
                                        val result = player.playAbilityPhase("sentry", "force_field", "remove")
                                        if (result.success) {
                                            musicPlayer.duckFor(durationMs = 1000)
                                            pendingEffects.remove(candidate.id)
                                            effectBannerText = "FORCE FIELD REMOVED!"
                                            specialAbilityRequest = null
                                        } else {
                                            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        specialAbilityRequest = null
                                    }
                                }) { Text("REMOVE", fontWeight = FontWeight.Black) }
                                TextButton(onClick = { specialAbilityRequest = null }) { Text("BACK") }
                            }
                        }
                    },
                    dismissButton = {
                        if (activeFields == 0) TextButton(onClick = { specialAbilityRequest = null }) { Text("NO") }
                    }
                )
            }
            else -> specialAbilityRequest = null
        }
    }


    abilityNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { abilityNotice = null },
            title = { Text(notice.title, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(notice.timing, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.55.sp, color = Color(0xFFFFC65C))
                    Text(notice.detail, fontSize = 11.sp, lineHeight = 15.sp, color = Color.White.copy(alpha = 0.78f))
                    Text(
                        "The effect is now in ACTIVE / PENDING EFFECTS and will be cleared only at its real timing gate.",
                        fontSize = 9.2.sp, lineHeight = 12.5.sp, color = Color.White.copy(alpha = 0.48f)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { abilityNotice = null }) { Text("OK", fontWeight = FontWeight.Black) } }
        )
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Top
    ) { pagerIndex ->
        val actualPage = visibleActualPages.getOrElse(pagerIndex) { 0 }
        val faction = pageFaction(actualPage)
        val accent = when (actualPage) {
            0 -> Color(0xFF77C7FF)
            5 -> themeFor(musicAccentFaction).accent
            else -> themeFor(faction ?: Faction.TERRAN).accent
        }

        Box(Modifier.fillMaxSize()) {
            when (actualPage) {
                0 -> TrackerBackdrop()
                5 -> MusicBackdrop(musicAccentFaction, themeFor(musicAccentFaction).accent)
                else -> FactionBackdrop(themeFor(faction ?: Faction.TERRAN))
            }

            Column(
                Modifier.fillMaxSize().padding(top = adaptiveTopSystemInset()).navigationBarsPadding()
                    .padding(
                        start = 40.dp,
                        end = 40.dp,
                        top = 18.dp,
                        bottom = if (actualPage == 0) 8.dp else 24.dp
                    )
            ) {
                when (actualPage) {
                    0 -> {
                        ScreenTitleArt("images/ui/screen_titles/command_console.webp", accent)
                        Spacer(Modifier.height(3.dp))
                        CompanionTopBar(
                        accent = accent,
                        musicPlaying = musicPlayingNow,
                        announcerMode = announcerMode,
                        onAnnouncerModeChange = { setAnnouncerMode(it) },
                        onMusicToggle = { toggleMusic() },
                        onMusicSkip = { skipMusic() },
                        onHome = { if (victoryOverlayActive) onVictoryHome(activeVictoryFaction) else onHome() },
                        onRules = onRules
                        )
                    }
                    5 -> {
                        ScreenTitleArt(
                            "images/ui/screen_titles/jimmy_jukebox.webp",
                            accent,
                            playingOverlayAsset = "images/ui/screen_titles/jimmy_jukebox_playing.webp",
                            overlayActive = musicPlayingNow
                        )
                        Spacer(Modifier.height(3.dp))
                        MusicTopBar(
                            accent = accent,
                            screen = jukeboxScreen,
                            onScreenChange = { jukeboxScreen = it.coerceIn(0, 2) },
                            onHome = onHome,
                            onRules = onRules
                        )
                    }
                    else -> {
                        val activeFaction = faction ?: Faction.TERRAN
                        val activeTheme = themeFor(activeFaction)
                        if (activeFaction == Faction.HYBRID) {
                            HybridThemeTitle(activeTheme, accent)
                        } else {
                            FactionThemeTitle(activeTheme, accent) { cycleMainTheme(activeFaction) }
                        }
                        Spacer(Modifier.height(4.dp))
                        FactionContentTopBar(
                            faction = activeFaction,
                            theme = activeTheme,
                            accent = accent,
                            showBuildings = showBuildings && activeFaction != Faction.HYBRID,
                            musicPlaying = musicPlayingNow,
                            onModeChange = { if (activeFaction != Faction.HYBRID) showBuildings = it },
                            onMusicToggle = { toggleMusic() },
                            onMusicSkip = { skipMusic() },
                            onHome = onHome,
                            onRules = onRules
                        )
                    }
                }

                Spacer(Modifier.height(5.dp))
                SwipeRouteBar(actualPage, visibleActualPages, accent) { targetActual ->
                    val targetIndex = visibleActualPages.indexOf(targetActual)
                    if (targetIndex >= 0) scope.launch { pagerState.animateScrollToPage(targetIndex) }
                }
                Spacer(Modifier.height(6.dp))

                when (actualPage) {
                    0 -> GlowingContentPanel(accent, Modifier.fillMaxWidth().weight(1f)) {
                        CompanionPage(
                            store = gameState,
                            pendingEffects = pendingEffects,
                            soundPlayer = player,
                            announcerMode = announcerMode,
                            matchIntroMode = matchIntroMode,
                            musicPlayer = musicPlayer,
                            musicStore = musicStore,
                            themeAssetFor = { faction -> themeFor(faction).asset },
                            themeLabelFor = { faction -> themeFor(faction).label },
                            onNewGameSetup = onNewGameSetup,
                            onVictoryNewGame = onVictoryNewGame,
                            onVictoryVisibilityChanged = { visible, faction ->
                                victoryOverlayActive = visible
                                activeVictoryFaction = if (visible) faction else null
                            },
                            onMatchConfigChanged = { matchConfigRevision++ }
                        )
                    }
                    1, 2, 3, 4 -> {
                        val activeFaction = faction ?: Faction.TERRAN
                        val activeTheme = themeFor(activeFaction)
                        if (showBuildings && activeFaction != Faction.HYBRID) {
                            val buildings = Catalog.buildings.filter { it.faction == activeFaction && store.isBuildingSelected(it) }
                            GlowingContentPanel(accent, Modifier.fillMaxWidth().weight(1f)) {
                                BuildingsPage(
                                    buildings = buildings,
                                    player = player,
                                    accent = accent,
                                    themeSquareFrameAsset = activeTheme.squareFrameAsset,
                                    onPlay = { b ->
                                        val result = b.unitAudioId?.let(player::playRandomUnitVoice)
                                            ?: player.playBuilding(b.audioId)
                                        if (result.success) {
                                            val voiceMs = player.currentVoiceDurationMs().coerceAtLeast(650)
                                            musicPlayer.duckFor(durationMs = voiceMs + 220)
                                        } else {
                                            Toast.makeText(context, "${b.name}: ${result.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onAbility = { b, abilityId ->
                                        val behavior = AbilityBehaviorRegistry.get(b.audioId, abilityId)
                                        val label = AbilityAudioRegistry.forAudioId(b.audioId)
                                            .firstOrNull { it.id == abilityId }?.label
                                            ?: abilityId.replace('_', ' ').uppercase()

                                        if (gameState.gameStarted && b.faction !in selectedMatchFactions) {
                                            Toast.makeText(context, "${b.faction.label} is not in this match.", Toast.LENGTH_SHORT).show()
                                        } else if (gameState.gameStarted && behavior != null && behavior.evidence != AbilityEvidence.AUDIO_ONLY && !behavior.allowsPhase(gameState.phase)) {
                                            Toast.makeText(context, "$label is used during the ${behavior.phaseLabel}.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val tapKey = "building:${b.id}:$abilityId"
                                            val now = System.currentTimeMillis()
                                            val previous = lastAbilityTapMs[tapKey] ?: 0L
                                            if (now - previous >= 650L) {
                                                lastAbilityTapMs[tapKey] = now
                                                val result = player.playUnitAbility(b.audioId, abilityId)
                                                if (result.success) {
                                                    musicPlayer.duckFor(durationMs = 1500)
                                                    if (gameState.gameStarted && behavior != null) {
                                                        behavior.trackedKinds.forEach { kind ->
                                                            pendingEffects.add(
                                                                kind = kind,
                                                                round = gameState.round,
                                                                phase = gameState.phase,
                                                                sourceLabel = b.name,
                                                                audioId = b.audioId,
                                                                abilityId = abilityId,
                                                                detailOverride = behavior.reminder
                                                            )
                                                        }
                                                        if (behavior.trackedKinds.isNotEmpty()) {
                                                            effectBannerText = "${behavior.trackedKinds.first().label} TRACKED"
                                                            abilityNotice = AbilityNotice(
                                                                label,
                                                                behavior.reminder,
                                                                "ACTIVE UNTIL ROUND CLEANUP"
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    Toast.makeText(context, "${b.name}: ${result.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        } else {
                            val visible = Catalog.units.filter { u ->
                                u.faction == activeFaction && store.isUnitSelected(u) && (!u.heroOrCommander || store.heroesEnabled)
                            }
                            val chosen = selectedIds[activeFaction]
                            val selected = visible.firstOrNull { it.id == chosen } ?: visible.firstOrNull()
                            LaunchedEffect(activeFaction, visible.size) { if (selected != null) selectedIds[activeFaction] = selected.id }
                            UnitsPage(
                                units = visible,
                                selected = selected,
                                player = player,
                                musicPlayer = musicPlayer,
                                accent = accent,
                                themeIconAsset = activeTheme.asset,
                                themeSquareFrameAsset = activeTheme.squareFrameAsset,
                                themeRectFrameAsset = activeTheme.rectFrameAsset,
                                onCycleTheme = { cycleMainTheme(activeFaction) },
                                onSelect = { selectedIds[activeFaction] = it.id },
                                onAction = { category ->
                                    if (selected != null) {
                                        val result = player.playUnit(selected.audioId, category)
                                        if (result.success) {
                                            val voiceMs = player.currentVoiceDurationMs().coerceAtLeast(650)
                                            musicPlayer.duckFor(durationMs = voiceMs + 220)
                                        } else {
                                            Toast.makeText(context, "${selected.name}: ${result.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onAbility = { abilityId ->
                                    if (selected != null) {
                                        val behavior = AbilityBehaviorRegistry.get(selected.audioId, abilityId)
                                        val label = AbilityAudioRegistry.forAudioId(selected.audioId).firstOrNull { it.id == abilityId }?.label
                                            ?: abilityId.replace('_', ' ').uppercase()

                                        // An ability button belongs to the unit/faction shown on this page. During a
                                        // live match it must never become a cross-faction sound/effect shortcut.
                                        if (gameState.gameStarted && selected.faction !in selectedMatchFactions) {
                                            Toast.makeText(context, "${selected.faction.label} is not in this match.", Toast.LENGTH_SHORT).show()
                                            return@UnitsPage
                                        }

                                        // Only enforce tabletop phase timing that is actually grounded in the supplied
                                        // TMG cards (or the explicitly locked Bile companion sequence). AUDIO_ONLY cues
                                        // deliberately do not invent a tabletop phase/effect lifecycle.
                                        if (gameState.gameStarted && behavior != null && behavior.evidence != AbilityEvidence.AUDIO_ONLY && !behavior.allowsPhase(gameState.phase)) {
                                            Toast.makeText(context, "$label is used during the ${behavior.phaseLabel}.", Toast.LENGTH_SHORT).show()
                                            return@UnitsPage
                                        }

                                        // Hardware/user double taps must not create a forest of duplicate tracker rows.
                                        // Do not turn this into a global once-per-round rule: two squads may legally use
                                        // the same named Active ability in the same round.
                                        val tapKey = "${selected.id}:$abilityId"
                                        val now = System.currentTimeMillis()
                                        val previous = lastAbilityTapMs[tapKey] ?: 0L
                                        if (now - previous < 650L) return@UnitsPage
                                        lastAbilityTapMs[tapKey] = now

                                        if (behavior?.specialWorkflow == true) {
                                            specialAbilityRequest = SpecialAbilityRequest(selected, abilityId)
                                        } else {
                                            val result = player.playUnitAbility(selected.audioId, abilityId)
                                            if (result.success) {
                                                musicPlayer.duckFor(durationMs = when (abilityId) {
                                                    "yamato" -> 4300
                                                    "guardian_shield" -> 2100
                                                    "psionic_storm" -> 1800
                                                    else -> 1350
                                                })
                                                if (gameState.gameStarted && behavior != null) {
                                                    behavior.trackedKinds.forEach { kind ->
                                                        if (behavior.aggregateAtCleanup) {
                                                            pendingEffects.addOrIncrement(
                                                                kind = kind,
                                                                round = gameState.round,
                                                                phase = gameState.phase,
                                                                sourceLabel = selected.name,
                                                                audioId = selected.audioId,
                                                                abilityId = abilityId,
                                                                detailOverride = behavior.reminder
                                                            )
                                                        } else {
                                                            pendingEffects.add(
                                                                kind = kind,
                                                                round = gameState.round,
                                                                phase = gameState.phase,
                                                                sourceLabel = selected.name,
                                                                audioId = selected.audioId,
                                                                abilityId = abilityId,
                                                                detailOverride = behavior.reminder
                                                            )
                                                        }
                                                    }
                                                    if (behavior.trackedKinds.isNotEmpty()) {
                                                        effectBannerText = "${behavior.trackedKinds.first().label} TRACKED"
                                                        val firstTiming = behavior.trackedKinds.first().timing
                                                        val timingText = when (firstTiming) {
                                                            PendingEffectTiming.END_ASSAULT -> "RESOLVE AT END OF ASSAULT"
                                                            PendingEffectTiming.END_ROUND_DECISION -> "DECISION AT END OF ROUND"
                                                            PendingEffectTiming.END_ROUND_CLEANUP -> "ACTIVE UNTIL ROUND CLEANUP"
                                                            PendingEffectTiming.END_PHASE -> "ACTIVE UNTIL END OF PHASE"
                                                            PendingEffectTiming.END_ACTIVATION -> "ACTIVE UNTIL END OF ACTIVATION"
                                                            PendingEffectTiming.NEXT_ACTION -> "ACTIVE UNTIL NEXT ACTION"
                                                            PendingEffectTiming.FIRST_USE -> "APPLIES TO FIRST USE"
                                                            PendingEffectTiming.MANUAL -> "MANUAL STATUS"
                                                            PendingEffectTiming.STAY_IN_PLAY -> "STAYS IN PLAY"
                                                        }
                                                        abilityNotice = AbilityNotice(label, behavior.reminder, timingText)
                                                    }
                                                }
                                                if (behavior?.evidence == AbilityEvidence.AUDIO_ONLY) {
                                                    effectBannerText = "$label • AUDIO CUE ONLY"
                                                }
                                            } else {
                                                Toast.makeText(context, "${selected.name}: ${result.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                    5 -> Box(
                        Modifier.fillMaxWidth().weight(1f)
                            // Keep the live display fully above the raised speaker / lower jukebox hardware.
                            .padding(start = 4.dp, top = 1.dp, end = 4.dp, bottom = 82.dp)
                    ) {
                        MusicPage(
                            jukeboxScreen = jukeboxScreen,
                            musicStore = musicStore,
                            musicPlayer = musicPlayer,
                            soundPlayer = player,
                            enabledFactions = enabledMusicFactions,
                            followMatchFactions = followMatchMusic,
                            skipUnusedFactionPages = skipUnusedPages,
                            matchIntroMode = matchIntroMode,
                            refreshTick = musicRefresh,
                            onFactionToggle = { which, enabled -> setMusicFaction(which, enabled) },
                            onFollowMatchFactionsChange = { setFollowMatchMusic(it) },
                            onSkipUnusedFactionPagesChange = { setSkipUnusedPages(it) },
                            onMatchIntroModeChange = { setMatchIntroMode(it) },
                            onPlaybackChanged = { musicUiTick = (musicUiTick + 1) % 100000 }
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
        AnimatedVisibility(
            visible = effectBannerText != null,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(420)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = adaptiveTopSystemInset() + 92.dp)
        ) {
            Surface(
                color = Color(0xE6111B24),
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.72f)),
                shadowElevation = 8.dp
            ) {
                Text(
                    effectBannerText.orEmpty(),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    color = Color.White
                )
            }
        }
    }

}

@Composable
private fun GlowingContentPanel(
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        color = Color(0xB8070C12),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun CompanionTopBar(
    accent: Color,
    musicPlaying: Boolean,
    announcerMode: AnnouncerMode,
    onAnnouncerModeChange: (AnnouncerMode) -> Unit,
    onMusicToggle: () -> Unit,
    onMusicSkip: () -> Unit,
    onHome: () -> Unit,
    onRules: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(8.dp)),
        color = Color(0xE9060B11),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f))
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                HudIconButton(Icons.Default.Home, "Home", accent, onHome)
                Spacer(Modifier.width(3.dp))
                HudIconButton(if (musicPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (musicPlaying) "Pause music" else "Play music", accent, onMusicToggle)
                Spacer(Modifier.width(3.dp))
                HudIconButton(Icons.Default.SkipNext, "Skip song", accent, onMusicSkip)
                Spacer(Modifier.width(6.dp))
                VoiceModeSelector(
                    mode = announcerMode,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onModeChange = onAnnouncerModeChange
                )
                Spacer(Modifier.width(6.dp))
                HudIconButton(Icons.Default.MenuBook, "Rules", accent, onRules)
            }
        }
    }
}

@Composable
private fun VoiceModeSelector(
    mode: AnnouncerMode,
    accent: Color,
    modifier: Modifier = Modifier,
    onModeChange: (AnnouncerMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(34.dp),
            color = Color(0xFF0E1822),
            shape = RoundedCornerShape(7.dp),
            border = BorderStroke(1.2.dp, accent.copy(alpha = 0.42f))
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(if (mode == AnnouncerMode.OFF) Icons.Default.VolumeOff else Icons.Default.RecordVoiceOver, null, Modifier.size(16.dp), tint = if (mode == AnnouncerMode.OFF) Color.White.copy(alpha = 0.38f) else accent)
                Spacer(Modifier.width(5.dp))
                Text(mode.label, fontSize = 8.4.sp, fontWeight = FontWeight.Black, letterSpacing = 0.55.sp, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AnnouncerMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, fontWeight = if (option == mode) FontWeight.Black else FontWeight.Medium) },
                    leadingIcon = {
                        Icon(if (option == AnnouncerMode.OFF) Icons.Default.VolumeOff else Icons.Default.RecordVoiceOver, null)
                    },
                    onClick = {
                        expanded = false
                        onModeChange(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun MusicTopBar(
    accent: Color,
    screen: Int,
    onScreenChange: (Int) -> Unit,
    onHome: () -> Unit,
    onRules: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(5.dp, RoundedCornerShape(8.dp)),
        color = Color(0xE9060B11),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f))
    ) {
        Row(
            Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HudIconButton(Icons.Default.Home, "Home", accent, onHome)
            Spacer(Modifier.width(7.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                (0..2).forEach { index ->
                    val selected = screen == index
                    Surface(
                        onClick = { onScreenChange(index) },
                        modifier = Modifier.weight(1f).height(34.dp),
                        color = if (selected) Color(0xFF2B190D) else Color(0xFF0E141A),
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) Color(0xFFFFB354).copy(alpha = 0.86f) else Color.White.copy(alpha = 0.11f)
                        )
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "${index + 1}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                color = if (selected) Color(0xFFFFD18A) else Color.White.copy(alpha = 0.58f)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(7.dp))
            HudIconButton(Icons.Default.MenuBook, "Rules", accent, onRules)
        }
    }
}

@Composable
private fun UnifiedTopBar(
    title: String,
    accent: Color,
    musicPlaying: Boolean,
    onMusicToggle: () -> Unit,
    onMusicSkip: () -> Unit,
    onHome: () -> Unit,
    onRules: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(5.dp, RoundedCornerShape(8.dp)),
        color = Color(0xE9060B11),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f))
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                HudIconButton(Icons.Default.Home, "Home", accent, onHome)
                Spacer(Modifier.width(3.dp))
                HudIconButton(if (musicPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (musicPlaying) "Pause music" else "Play music", accent, onMusicToggle)
                Spacer(Modifier.width(3.dp))
                HudIconButton(Icons.Default.SkipNext, "Skip song", accent, onMusicSkip)
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.7.sp)
                }
                HudIconButton(Icons.Default.MenuBook, "Rules", accent, onRules)
            }
        }
    }
}

@Composable
private fun ScreenTitleArt(
    asset: String,
    accent: Color,
    playingOverlayAsset: String? = null,
    overlayActive: Boolean = false
) {
    // The plaque/bar position is already correct. Only tune the transparent word-art
    // independently so the title can be raised/enlarged without dragging the bar around.
    val isCommand = asset.contains("command_console")
    val titleWidth = if (isCommand) 0.99f else 0.985f
    val titleHeight = if (isCommand) 67.dp else 65.dp
    val titleYOffset = if (isCommand) (-6).dp else (-8).dp
    Box(
        modifier = Modifier.fillMaxWidth().height(62.dp),
        contentAlignment = Alignment.Center
    ) {
        // Screen-title artwork is already authored to stand on its own. Do not add a generic
        // black glass backing behind it: the extra rectangle reads as a forgotten asset edge.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf((if (playingOverlayAsset.isNullOrBlank()) accent else Color(0xFFFF6A00)).copy(alpha = if (overlayActive) 0.30f else 0.21f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.48f),
                    radius = size.width * 0.38f
                ),
                radius = size.width * 0.38f,
                center = Offset(size.width * 0.5f, size.height * 0.48f)
            )
        }
        if (playingOverlayAsset.isNullOrBlank()) {
            AssetFrame(
                asset,
                Modifier.fillMaxWidth(titleWidth).height(titleHeight).offset(y = titleYOffset)
                    .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f },
                contentScale = ContentScale.Fit
            )
        } else {
            // Keep one animated drawable mounted permanently. Playback only starts/stops the frames;
            // the visual power state eases separately so pause/play never jumps between two titles.
            val targetScale = if (overlayActive) 1.126f else 1.110f
            // The replacement animation is already colour-balanced and brightened by the user.
            // Keep its gold, cyan, blue and violet detail intact instead of recolouring the whole thing orange.
            val targetSat = if (overlayActive) 1.08f else 0.92f
            val targetGain = if (overlayActive) 1.05f else 0.79f
            val targetAlpha = if (overlayActive) 1f else 0.88f
            val scale by animateFloatAsState(targetValue = targetScale, animationSpec = tween(760, easing = FastOutSlowInEasing), label = "jukebox-title-scale")
            val satBase by animateFloatAsState(targetValue = targetSat, animationSpec = tween(780, easing = FastOutSlowInEasing), label = "jukebox-title-saturation")
            val gainBase by animateFloatAsState(targetValue = targetGain, animationSpec = tween(780, easing = FastOutSlowInEasing), label = "jukebox-title-brightness")
            val titleAlpha by animateFloatAsState(targetValue = targetAlpha, animationSpec = tween(720, easing = FastOutSlowInEasing), label = "jukebox-title-alpha")

            // A brief emissive bloom every few seconds while playing: colour/brightness only,
            // deliberately no scale wobble or constant breathing motion.
            val emissiveTransition = rememberInfiniteTransition(label = "jukebox-emissive")
            val emissiveBoost by emissiveTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 6400
                        0f at 0
                        0f at 4200
                        1f at 4720
                        0f at 5350
                        0f at 6400
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "jukebox-occasional-bloom"
            )
            val activeBoost = if (overlayActive) emissiveBoost else 0f
            AnimatedAssetFrame(
                playingOverlayAsset,
                Modifier.fillMaxWidth(titleWidth).height(titleHeight).offset(y = titleYOffset)
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = titleAlpha },
                playing = overlayActive,
                saturation = satBase + (0.08f * activeBoost),
                brightness = gainBase + (0.09f * activeBoost),
                warmth = 0f
            )
        }
    }
}

private data class ThemeTitleLayout(
    val widthFraction: Float,
    val imageHeightDp: Float,
    val yOffsetDp: Float = 0f,
    val backdropAlpha: Float = 0.12f,
    val backdropWidth: Float = 0.82f,
    val backdropHeightDp: Float = 38f,
    val shadowDp: Float = 12f
)

private fun themeTitleLayout(label: String): ThemeTitleLayout = when (label) {
    // Keep the individual anchors, but give every faction wordmark a little more presence.
    // The generic black backing is removed below; only the two Zerg themes retain it.
    "DOMINION" -> ThemeTitleLayout(0.66f, 45f, yOffsetDp = -3f, backdropAlpha = 0.10f, backdropWidth = 0.68f)
    "CONFEDERACY" -> ThemeTitleLayout(0.77f, 45f, yOffsetDp = -3f, backdropAlpha = 0.10f, backdropWidth = 0.78f)
    "RAYNOR'S RAIDERS" -> ThemeTitleLayout(0.91f, 48f, yOffsetDp = -5f, backdropAlpha = 0.14f, backdropWidth = 0.90f)

    "KHALAI" -> ThemeTitleLayout(0.58f, 47f, yOffsetDp = 1f, backdropAlpha = 0.16f, backdropWidth = 0.61f)
    "PURIFIER" -> ThemeTitleLayout(0.64f, 42f, yOffsetDp = 6f, backdropAlpha = 0.40f, backdropWidth = 0.69f, backdropHeightDp = 40f)
    "GOLDEN ARMADA" -> ThemeTitleLayout(0.83f, 44f, yOffsetDp = 1f, backdropAlpha = 0.19f, backdropWidth = 0.84f)
    "TAL'DARIM" -> ThemeTitleLayout(0.87f, 55f, yOffsetDp = -1f, backdropAlpha = 0.22f, backdropWidth = 0.88f)
    "NERAZIM" -> ThemeTitleLayout(0.64f, 44f, yOffsetDp = 13f, backdropAlpha = 0.34f, backdropWidth = 0.68f)

    "SWARM" -> ThemeTitleLayout(0.55f, 44f, yOffsetDp = 1f, backdropAlpha = 0.50f, backdropWidth = 0.62f)
    // Primal and Swarm are the deliberate exceptions: their organic backgrounds still benefit
    // from the dark quiet-zone behind the wordmark.
    "PRIMAL" -> ThemeTitleLayout(0.57f, 45f, yOffsetDp = 5f, backdropAlpha = 0.76f, backdropWidth = 0.72f, backdropHeightDp = 54f, shadowDp = 20f)
    else -> ThemeTitleLayout(0.64f, 41f, backdropAlpha = 0.20f, backdropWidth = 0.70f)
}

@Composable
private fun HybridThemeTitle(
    theme: FactionThemeOption,
    accent: Color
) {
    // The supplied plaque already contains the title. Keep its authored aspect ratio and
    // reserve the same header height as the other faction pages so the controls below do not
    // move into the status-bar safe area or consume additional portrait content height.
    Box(
        modifier = Modifier.fillMaxWidth().height(62.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.25f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.50f),
                    radius = size.width * 0.34f
                ),
                radius = size.width * 0.34f,
                center = Offset(size.width * 0.5f, size.height * 0.50f)
            )
        }
        AssetFrame(
            theme.titleAsset,
            modifier = Modifier.fillMaxWidth(0.98f).height(60.dp),
            contentScale = ContentScale.Fit
        )
    }
}

private fun ornamentalFrameScale(path: String): ContentScale =
    if (path.endsWith("/hybrid_horizontal_frame.png")) ContentScale.Fit else ContentScale.FillBounds

@Composable
private fun FactionThemeTitle(
    theme: FactionThemeOption,
    accent: Color,
    onCycleTheme: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val spec = remember(theme.label) { themeTitleLayout(theme.label) }
    val transition = rememberInfiniteTransition(label = "theme-title")
    val glow by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.46f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "theme-title-glow"
    )
    Box(
        modifier = Modifier.fillMaxWidth().height(62.dp)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCycleTheme()
            },
        contentAlignment = Alignment.Center
    ) {
        // Only the two Zerg wordmarks keep the black quiet-zone. Terran and Protoss title art
        // reads cleaner directly over the authored faction background.
        if (theme.label == "SWARM" || theme.label == "PRIMAL") {
            Box(
                Modifier.fillMaxWidth(spec.backdropWidth).height(spec.backdropHeightDp.dp)
                    .offset(y = spec.yOffsetDp.dp)
                    .shadow(spec.shadowDp.dp, RoundedCornerShape(16.dp), clip = false)
                    .background(Color.Black.copy(alpha = spec.backdropAlpha), RoundedCornerShape(16.dp))
                    .border(1.dp, accent.copy(alpha = 0.16f + glow * 0.18f), RoundedCornerShape(16.dp))
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = glow * 0.23f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.52f),
                    radius = size.width * 0.30f
                ),
                radius = size.width * 0.30f,
                center = Offset(size.width * 0.5f, size.height * 0.52f)
            )
        }
        AssetFrame(
            theme.titleAsset,
            Modifier.fillMaxWidth((spec.widthFraction * 1.35f).coerceAtMost(0.995f))
                .height((spec.imageHeightDp * 1.35f).dp)
                .offset(y = spec.yOffsetDp.dp)
                // Preserve each authored anchor but let the wordmarks read more confidently.
                .graphicsLayer { scaleX = 1.22f; scaleY = 1.22f },
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun FactionContentTopBar(
    faction: Faction,
    theme: FactionThemeOption,
    accent: Color,
    showBuildings: Boolean,
    musicPlaying: Boolean,
    onModeChange: (Boolean) -> Unit,
    onMusicToggle: () -> Unit,
    onMusicSkip: () -> Unit,
    onHome: () -> Unit,
    onRules: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xF0060B11),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactHudIconButton(Icons.Default.Home, "Home", accent, onHome)
            Spacer(Modifier.width(2.dp))
            CompactHudIconButton(if (musicPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (musicPlaying) "Pause music" else "Play music", accent, onMusicToggle)
            Spacer(Modifier.width(2.dp))
            CompactHudIconButton(Icons.Default.SkipNext, "Skip song", accent, onMusicSkip)
            Spacer(Modifier.width(4.dp))
            Row(
                modifier = Modifier.weight(1f).height(40.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FactionPushSegment("UNITS", frameAsset = theme.rectFrameAsset, selected = !showBuildings, modifier = Modifier.weight(1f)) { onModeChange(false) }
                FactionPushSegment("TACTICS", frameAsset = theme.rectFrameAsset, selected = showBuildings, modifier = Modifier.weight(1f)) { onModeChange(true) }
            }
            Spacer(Modifier.width(4.dp))
            CompactHudIconButton(Icons.Default.MenuBook, "Rules", accent, onRules)
        }
    }
}

@Composable
private fun CompactHudIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        color = Color(0xFF0B121A),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(21.dp), tint = Color.White.copy(alpha = 0.88f))
        }
    }
}

private fun factionPushButtonAsset(faction: Faction, selected: Boolean): String {
    val name = when (faction) {
        Faction.TERRAN -> "terran"
        Faction.PROTOSS -> "protoss"
        Faction.ZERG, Faction.HYBRID -> "zerg"
    }
    return "images/ui/push_buttons/${name}_${if (selected) "on" else "off"}.png"
}

@Composable
private fun FactionPushSegment(label: String, frameAsset: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.fillMaxHeight().clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AssetFrame(
            frameAsset,
            modifier = Modifier.fillMaxSize().alpha(if (selected) 1f else 0.58f),
            contentScale = ornamentalFrameScale(frameAsset)
        )
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            fontSize = if (label == "TACTICS") 8.4.sp else 8.8.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = if (label == "TACTICS") 0.18.sp else 0.28.sp,
            textAlign = TextAlign.Center,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun SwipeRouteBar(currentPage: Int, visiblePages: List<Int>, activeAccent: Color, onNavigate: (Int) -> Unit) {
    val labels = mapOf(0 to "GAME", 1 to "TERRAN", 2 to "PROTOSS", 3 to "ZERG", 4 to "HYBRID", 5 to "MUSIC")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xD9070C12),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            visiblePages.forEach { page ->
                val active = page == currentPage
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { onNavigate(page) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        labels[page].orEmpty(),
                        fontSize = 7.2.sp,
                        fontWeight = if (active) FontWeight.Black else FontWeight.Bold,
                        letterSpacing = 0.38.sp,
                        color = if (active) Color.White else Color.White.copy(alpha = 0.43f)
                    )
                    if (active) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.54f).height(2.dp)
                                .background(activeAccent.copy(alpha = 0.78f), RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageHintArrows(currentPage: Int, pageCount: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    val prevLabel = when (currentPage) {
        1 -> "TRACKERS"
        2 -> "UNITS"
        3 -> "TACTICS"
        else -> ""
    }
    val nextLabel = when (currentPage) {
        0 -> "UNITS"
        1 -> "TACTICS"
        2 -> "MUSIC"
        else -> ""
    }

    Box(Modifier.fillMaxSize().padding(vertical = 130.dp)) {
        if (currentPage > 0) {
            Surface(
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(width = 46.dp, height = 74.dp).clickable { onPrev() },
                color = Color(0x66060A10),
                shape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.KeyboardArrowLeft, null, Modifier.size(24.dp), tint = Color.White.copy(alpha = 0.82f))
                    Text(prevLabel, fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, color = Color.White.copy(alpha = 0.70f))
                }
            }
        }
        if (currentPage < pageCount - 1) {
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).size(width = 46.dp, height = 74.dp).clickable { onNext() },
                color = Color(0x66060A10),
                shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(24.dp), tint = Color.White.copy(alpha = 0.82f))
                    Text(nextLabel, fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, color = Color.White.copy(alpha = 0.70f))
                }
            }
        }
    }
}

@Composable
private fun BenchmarkBuildingsTopBar(
    accent: Color,
    musicPlaying: Boolean,
    onMusicToggle: () -> Unit,
    onHome: () -> Unit,
    onRules: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xF2050A10),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        shadowElevation = 4.dp
    ) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            HudIconButton(Icons.Default.Home, "Home", accent, onHome)
            Spacer(Modifier.width(4.dp))
            HudIconButton(if (musicPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (musicPlaying) "Pause music" else "Play music", accent, onMusicToggle)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TACTICS", fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Text("Tactical cards & audio", fontSize = 8.sp, color = Color.White.copy(alpha = 0.46f))
            }
            HudIconButton(Icons.Default.MenuBook, "Rules", accent, onRules)
        }
    }
}

@Composable
private fun MainTopBar(
    title: String,
    subtitle: String,
    accent: Color,
    musicPlaying: Boolean,
    onMusicToggle: () -> Unit,
    onHome: () -> Unit,
    onCollection: () -> Unit,
    onRules: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(Color(0xF0060A10))) {
        Row(
            Modifier.fillMaxWidth().height(61.dp).padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HudIconButton(Icons.Default.Home, "Home", accent, onHome)
            Spacer(Modifier.width(3.dp))
            HudIconButton(if (musicPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (musicPlaying) "Pause music" else "Play music", accent, onMusicToggle)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Text(subtitle, fontSize = 8.2.sp, color = Color.White.copy(alpha = 0.44f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HudIconButton(Icons.Default.MenuBook, "Rules", accent, onRules)
            Spacer(Modifier.width(3.dp))
            HudIconButton(Icons.Default.CollectionsBookmark, "Collection", accent, onCollection)
        }
    }
}

@Composable
private fun HudIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        color = Color(0xFF0B121A),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.32f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(22.dp), tint = Color.White.copy(alpha = 0.88f))
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FactionTabs(
    factions: List<Faction>,
    selected: Faction,
    musicEnabled: ((Faction) -> Boolean?)? = null,
    onDoubleTap: ((Faction) -> Unit)? = null,
    onSelect: (Faction) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xD9080D14)).padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        factions.forEach { f ->
            val active = f == selected
            val c = factionColor(f)
            val inMusic = musicEnabled?.invoke(f)
            Surface(
                color = if (active) Color(0xE90A1119) else Color(0xFF111821),
                shape = RoundedCornerShape(7.dp),
                border = BorderStroke(if (active) 2.dp else 1.dp, if (active) c else Color(0xFF2A3544)),
                modifier = Modifier.weight(1f).height(42.dp).combinedClickable(
                    onClick = { onSelect(f) },
                    onDoubleClick = if (onDoubleTap != null && f != Faction.HYBRID) ({ onDoubleTap(f) }) else null
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(f.label, fontSize = if (factions.size == 4) 10.sp else 12.sp, fontWeight = FontWeight.Black, color = if (active) Color.White else Color.White.copy(alpha = 0.67f))
                    if (inMusic != null) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp),
                            shape = CircleShape,
                            color = if (inMusic) c.copy(alpha = 0.88f) else Color.Black.copy(alpha = 0.42f),
                            border = BorderStroke(1.dp, if (inMusic) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.18f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("♫", fontSize = 9.sp, fontWeight = FontWeight.Black, color = if (inMusic) Color.White else Color.White.copy(alpha = 0.28f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanionPage(
    store: GameStateStore,
    pendingEffects: PendingEffectsStore,
    soundPlayer: SoundPlayer,
    announcerMode: AnnouncerMode,
    matchIntroMode: MatchIntroMode,
    musicPlayer: MusicPlayer,
    musicStore: MusicStore,
    themeAssetFor: (Faction) -> String,
    themeLabelFor: (Faction) -> String,
    onNewGameSetup: () -> Unit,
    onVictoryNewGame: (Faction?) -> Unit,
    onVictoryVisibilityChanged: (Boolean, Faction?) -> Unit = { _, _ -> },
    onMatchConfigChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val gameScope = rememberCoroutineScope()
    val uiPlayer = remember { SoundPlayer(context) }
    val resultRegistry = remember { ResultAudioRegistry(context) }
    val matchIntroRegistry = remember { MatchIntroRegistry(context) }
    val announcer = remember {
        PhaseAnnouncer(context) { active ->
            if (active) musicPlayer.beginDuck() else musicPlayer.endDuck()
        }
    }
    DisposableEffect(announcer) {
        onDispose { announcer.release() }
    }
    val phases = listOf("MOVEMENT", "ASSAULT", "COMBAT", "SCORING & CLEANUP")
    var round by rememberSaveable { mutableIntStateOf(store.round) }
    var gameLength by rememberSaveable { mutableIntStateOf(store.gameLength) }
    var phase by rememberSaveable { mutableIntStateOf(store.phase) }
    var gameStarted by rememberSaveable { mutableStateOf(store.gameStarted) }
    var playerCount by rememberSaveable { mutableIntStateOf(store.playerCount) }
    var firstPlayer by rememberSaveable { mutableIntStateOf(store.firstPlayer) }
    var nextFirstPlayer by rememberSaveable { mutableIntStateOf(store.nextFirstPlayer) }
    var factionA by rememberSaveable { mutableIntStateOf(store.playerFactionA) }
    var factionB by rememberSaveable { mutableIntStateOf(store.playerFactionB) }
    var factionC by rememberSaveable { mutableIntStateOf(store.playerFactionC) }
    var scoreA by rememberSaveable { mutableIntStateOf(store.scoreA) }
    var scoreB by rememberSaveable { mutableIntStateOf(store.scoreB) }
    var scoreC by rememberSaveable { mutableIntStateOf(store.scoreC) }
    var supplyA by rememberSaveable { mutableIntStateOf(store.supplyA) }
    var supplyB by rememberSaveable { mutableIntStateOf(store.supplyB) }
    var supplyC by rememberSaveable { mutableIntStateOf(store.supplyC) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var confirmFinalScores by rememberSaveable { mutableStateOf(false) }
    var tieInitiativeDialog by remember { mutableStateOf(false) }
    var showResolveEffects by remember { mutableStateOf(false) }
    var showEndRoundEffects by remember { mutableStateOf(false) }
    var showCleanupEffects by remember { mutableStateOf(false) }
    var showActiveEffects by remember { mutableStateOf(false) }
    var bileResolutionBusy by remember { mutableStateOf(false) }
    var victoryVisible by rememberSaveable { mutableStateOf(false) }
    var startRevealActive by rememberSaveable { mutableStateOf(false) }
    var gameUiRevealReady by rememberSaveable { mutableStateOf(store.gameStarted) }
    var matchIntroVisualStage by remember { mutableIntStateOf(0) }

    val liveTrackerAlpha by animateFloatAsState(
        targetValue = if (gameUiRevealReady) 1f else 0f,
        animationSpec = tween(360, delayMillis = 70, easing = FastOutSlowInEasing),
        label = "liveTrackerReveal"
    )
    val identityHeaderAlpha by animateFloatAsState(
        targetValue = if (!gameStarted || gameUiRevealReady) 1f else 0f,
        animationSpec = tween(300, delayMillis = 70, easing = FastOutSlowInEasing),
        label = "matchIdentityReveal"
    )

    DisposableEffect(Unit) {
        onDispose { onVictoryVisibilityChanged(false, null) }
    }
    var startButtonBoundsInRoot by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var startOverlayOriginInRoot by remember { mutableStateOf(Offset.Zero) }

    fun factionFromIndex(index: Int): Faction = when (index) {
        1 -> Faction.PROTOSS
        2 -> Faction.ZERG
        else -> Faction.TERRAN
    }

    fun activeScores(): List<Int> = when (playerCount) {
        3 -> listOf(scoreA, scoreB, scoreC)
        else -> listOf(scoreA, scoreB)
    }
    fun activeCount(): Int = playerCount.coerceIn(2, 3)

    fun saveNow() {
        store.gameStarted = gameStarted
        store.save(
            round = round,
            gameLength = gameLength,
            phase = phase,
            firstPlayer = firstPlayer,
            nextFirstPlayer = nextFirstPlayer,
            playerFactionA = factionA,
            playerFactionB = factionB,
            playerFactionC = factionC,
            playerCount = playerCount,
            scoreA = scoreA,
            scoreB = scoreB,
            scoreC = scoreC,
            supplyA = supplyA,
            supplyB = supplyB,
            supplyC = supplyC
        )
    }

    fun announce(r: Int, p: Int) {
        announcer.announceRoundPhase(r, p, announcerMode)
    }

    fun prepareStartedGameBehindShutter() {
        round = 1
        phase = 0
        nextFirstPlayer = -1
        victoryVisible = false
        matchIntroVisualStage = 0
        gameUiRevealReady = false
        // START GAME commits the match identity. Ensure any front-end/title deck is gone before
        // the faction confrontation begins; Jimmy's Jukebox will not start until Round 1 finishes.
        musicPlayer.stopMenuForNewGame(fadeMs = 520)
        gameStarted = true
    }

    fun finishStartReveal() {
        // Keep the transparent shutter as an input blocker for the complete ceremony:
        // A faction -> VS -> B faction -> optional C faction -> literal GLHF -> ROUND 1.
        gameScope.launch {
            delay(90L)

            val activeFactions = buildList {
                add(factionFromIndex(factionA))
                if (playerCount >= 2) add(factionFromIndex(factionB))
                if (playerCount >= 3) add(factionFromIndex(factionC))
            }
            val wantsOpening = matchIntroMode == MatchIntroMode.OPENING_AND_GLHF ||
                matchIntroMode == MatchIntroMode.OPENING_ONLY
            val wantsGlhf = matchIntroMode == MatchIntroMode.OPENING_AND_GLHF ||
                matchIntroMode == MatchIntroMode.GLHF_ONLY
            val introEnabled = matchIntroMode != MatchIntroMode.OFF

            suspend fun playOpeningFor(index: Int, stage: Int, usedSha: MutableSet<String>, usedSpeakers: MutableSet<String>) {
                if (index !in activeFactions.indices) return
                matchIntroVisualStage = stage
                if (!wantsOpening || !introEnabled) {
                    delay(260L)
                    return
                }
                val entry = matchIntroRegistry.chooseFactionOpening(
                    targetFaction = activeFactions[index],
                    enabled = soundPlayer::isPoolClipEnabled,
                    excludedSha256 = usedSha,
                    excludedSpeakers = usedSpeakers
                )
                if (entry == null) {
                    delay(320L)
                    return
                }
                val spoken = soundPlayer.playMatchIntro(entry)
                if (spoken.success) {
                    usedSha += entry.sha256
                    usedSpeakers += entry.speaker
                    delay(soundPlayer.currentVoiceDurationMs().toLong().coerceAtLeast(450L) + 360L)
                } else {
                    delay(320L)
                }
            }

            if (introEnabled) {
                musicPlayer.beginDuck()
                try {
                    val usedSha = mutableSetOf<String>()
                    val usedSpeakers = mutableSetOf<String>()

                    if (wantsOpening) {
                        // The confrontation is made from actual faction characters/units only.
                        // Neutral/caster material never substitutes for either side.
                        playOpeningFor(0, 1, usedSha, usedSpeakers)

                        if (playerCount >= 2) {
                            matchIntroVisualStage = 2
                            delay(190L)
                            playOpeningFor(1, 3, usedSha, usedSpeakers)
                        }

                        if (playerCount >= 3) {
                            matchIntroVisualStage = 4
                            delay(190L)
                            playOpeningFor(2, 5, usedSha, usedSpeakers)
                        }
                    }

                    if (wantsGlhf) {
                        // GLHF is an independent, strict literal bank. A dramatic faction line can
                        // never consume/skip this beat merely because it sounds encouraging.
                        matchIntroVisualStage = 6
                        delay(if (wantsOpening) 220L else 80L)
                        matchIntroRegistry.chooseLiteralGlhf(soundPlayer::isPoolClipEnabled)?.let { entry ->
                            val spoken = soundPlayer.playMatchIntro(entry)
                            if (spoken.success) {
                                delay(soundPlayer.currentVoiceDurationMs().toLong().coerceAtLeast(450L) + 420L)
                            } else delay(360L)
                        } ?: delay(360L)
                    }
                } finally {
                    musicPlayer.endDuck()
                }
            }

            // Round 1 is its own final ceremony beat. Keep VP / supply / phase controls hidden
            // until the announcement has actually completed (or a short fail-safe expires).
            matchIntroVisualStage = 7
            delay(150L)
            announce(1, 0)
            if (announcerMode != AnnouncerMode.OFF) {
                var waitedForStart = 0L
                while (!announcer.isActive() && waitedForStart < 1500L) {
                    delay(50L)
                    waitedForStart += 50L
                }
                var waitedForFinish = 0L
                while (announcer.isActive() && waitedForFinish < 5500L) {
                    delay(50L)
                    waitedForFinish += 50L
                }
            } else {
                delay(420L)
            }

            startRevealActive = false
            gameUiRevealReady = true
            matchIntroVisualStage = 0
            if (musicStore.playbackEnabled) {
                musicPlayer.startFreshMatch()
            }
        }
    }

    fun beginNextRound(winner: Int) {
        firstPlayer = winner.coerceIn(0, activeCount() - 1)
        nextFirstPlayer = -1
        round = (round + 1).coerceAtMost(gameLength)
        phase = 0
        tieInitiativeDialog = false
        announce(round, phase)
    }

    fun finishAfterRoundCleanup() {
        if (round >= gameLength) {
            confirmFinalScores = true
            return
        }
        val scores = activeScores()
        val low = scores.minOrNull() ?: 0
        val tied = scores.indices.filter { scores[it] == low }
        if (tied.size > 1) tieInitiativeDialog = true else beginNextRound(tied.first())
    }

    fun requestRoundCleanup() {
        if (pendingEffects.dueRoundCleanup(round).isNotEmpty()) {
            showCleanupEffects = true
        } else {
            finishAfterRoundCleanup()
        }
    }

    fun nextPhase() {
        if (!gameStarted) return

        // Corrosive Bile is launched in Movement and resolves at the end of Assault. The Active
        // Effects inspector never exposes an early-resolution shortcut.
        if (phase >= 1 && pendingEffects.dueCorrosiveBile(round).isNotEmpty()) {
            // phase >= 1 also recovers a Bile that an earlier broken R11 build somehow carried
            // beyond its End-of-Assault gate. Never allow an overdue Bile to become unreachable.
            showResolveEffects = true
            return
        }

        if (phase < 3) {
            // END_PHASE effects end automatically when the real phase boundary is crossed.
            pendingEffects.dueEndPhase(round, phase).forEach { pendingEffects.remove(it.id) }
            if (nextFirstPlayer >= 0) {
                firstPlayer = nextFirstPlayer
                nextFirstPlayer = -1
            }
            phase += 1
            announce(round, phase)
            return
        }

        // End-of-Round decisions happen before Cleanup & Refresh. Cleanup is now an explicit
        // gate: the app never silently drops tokens/effects or fires expiry audio just because
        // the phase button was pressed.
        val decisions = pendingEffects.dueEndRoundDecisions(round)
        if (decisions.isNotEmpty()) {
            showEndRoundEffects = true
            return
        }
        requestRoundCleanup()
    }

    fun previousPhase() {
        if (!gameStarted) return
        val oldRound = round
        val oldPhase = phase
        nextFirstPlayer = -1
        if (phase > 0) {
            phase -= 1
        } else if (round > 1) {
            round -= 1
            phase = 3
        }
        if (round != oldRound || phase != oldPhase) announce(round, phase)
    }

    LaunchedEffect(gameStarted, round, gameLength, phase, firstPlayer, nextFirstPlayer, factionA, factionB, factionC, playerCount, scoreA, scoreB, scoreC, supplyA, supplyB, supplyC) {
        saveNow()
    }
    LaunchedEffect(announcerMode) {
        if (announcerMode == AnnouncerMode.OFF) announcer.silence()
    }
    DisposableEffect(Unit) {
        onDispose {
            saveNow()
            musicPlayer.endResult(resumeSoundtrack = musicStore.playbackEnabled)
            announcer.release()
        }
    }

    if (showResolveEffects) {
        val due = pendingEffects.dueCorrosiveBile(round)
        val overdueBile = phase > 1 || due.any { it.createdRound < round }
        AlertDialog(
            onDismissRequest = { showResolveEffects = false },
            title = {
                Text(
                    if (overdueBile) "OVERDUE EFFECT • CORROSIVE BILE" else "END OF ASSAULT • RESOLVE EFFECTS",
                    fontWeight = FontWeight.Black
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (due.isEmpty()) {
                        Text("All Corrosive Bile instances for this round are resolved. Combat may begin.")
                    } else {
                        Text(
                            "Resolve each launched Bile separately. ACTIVE EFFECTS can inspect these entries, but cannot resolve them early.",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = Color.White.copy(alpha = 0.70f)
                        )
                        due.forEachIndexed { index, effect ->
                            Surface(
                                color = Color(0xFF0D171F),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFA8E55C).copy(alpha = 0.42f))
                            ) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("CORROSIVE BILE #${index + 1}", fontWeight = FontWeight.Black, color = Color(0xFFC8F28F))
                                    if (effect.sourceLabel.isNotBlank()) {
                                        Text(effect.sourceLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.52f))
                                    }
                                    Text("INCOMING → EXPLOSION", fontSize = 9.sp, letterSpacing = 0.6.sp, color = Color.White.copy(alpha = 0.56f))
                                    OutlinedButton(
                                        onClick = {
                                            if (bileResolutionBusy) return@OutlinedButton
                                            bileResolutionBusy = true
                                            gameScope.launch {
                                                val incoming = soundPlayer.playAbilityPhase(
                                                    effect.audioId.ifBlank { "ravager" },
                                                    effect.abilityId.ifBlank { "corrosive_bile" },
                                                    "incoming"
                                                )
                                                if (!incoming.success) {
                                                    Toast.makeText(context, incoming.message, Toast.LENGTH_SHORT).show()
                                                    delay(260L)
                                                    bileResolutionBusy = false
                                                    return@launch
                                                }
                                                musicPlayer.duckFor(durationMs = 1800)
                                                delay(820L)
                                                val explosion = soundPlayer.playAbilityPhase(
                                                    effect.audioId.ifBlank { "ravager" },
                                                    effect.abilityId.ifBlank { "corrosive_bile" },
                                                    "explosion"
                                                )
                                                if (explosion.success) {
                                                    pendingEffects.remove(effect.id)
                                                } else {
                                                    Toast.makeText(context, explosion.message, Toast.LENGTH_SHORT).show()
                                                }
                                                delay(700L)
                                                bileResolutionBusy = false
                                            }
                                        },
                                        enabled = !bileResolutionBusy,
                                        modifier = Modifier.fillMaxWidth().height(42.dp),
                                        border = BorderStroke(1.dp, Color(0xFFA8E55C).copy(alpha = 0.60f))
                                    ) {
                                        Text(if (bileResolutionBusy) "AUDIO RESOLVING…" else "RESOLVE THIS BILE", fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (due.isEmpty()) {
                    TextButton(onClick = { showResolveEffects = false; nextPhase() }) {
                        Text("CONTINUE TO COMBAT", fontWeight = FontWeight.Black)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showResolveEffects = false }) { Text("BACK TO ASSAULT") } }
        )
    }

    if (showEndRoundEffects) {
        val due = pendingEffects.dueEndRoundDecisions(round)
        AlertDialog(
            onDismissRequest = { showEndRoundEffects = false },
            title = { Text("END OF ROUND • DECISIONS", fontWeight = FontWeight.Black) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 410.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (due.isEmpty()) {
                        Text("All End-of-Round decisions are complete. Cleanup may continue.")
                    } else {
                        Text(
                            "These are real End-of-Round decisions. Resolve each instance before cleanup.",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.68f)
                        )
                        due.forEachIndexed { index, effect ->
                            Surface(
                                color = Color(0xFF0D171F),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.34f))
                            ) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("${effect.label} #${index + 1}", fontWeight = FontWeight.Black)
                                    if (effect.sourceLabel.isNotBlank()) Text(effect.sourceLabel, fontSize = 9.sp, color = Color.White.copy(alpha = 0.48f))
                                    Text(effect.detail, fontSize = 10.sp, lineHeight = 13.sp, color = Color.White.copy(alpha = 0.62f))
                                    when (effect.kind) {
                                        PendingEffectKind.ADEPT_SHADE -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    val result = soundPlayer.playAbilityPhase(
                                                        effect.audioId.ifBlank { "adept" },
                                                        effect.abilityId.ifBlank { "psionic_transfer" },
                                                        "teleport"
                                                    )
                                                    if (result.success) {
                                                        musicPlayer.duckFor(durationMs = 1200)
                                                        pendingEffects.remove(effect.id)
                                                    } else {
                                                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("TRANSFER", fontWeight = FontWeight.Black) }
                                            OutlinedButton(
                                                onClick = { pendingEffects.remove(effect.id) },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("NO TRANSFER", fontWeight = FontWeight.Black) }
                                        }
                                        PendingEffectKind.WARP_CONDUIT,
                                        PendingEffectKind.VENTRAL_SACS,
                                        PendingEffectKind.READY_FOR_DUST_OFF -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(
                                                onClick = { pendingEffects.remove(effect.id) },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("DEPLOY", fontWeight = FontWeight.Black) }
                                            OutlinedButton(
                                                onClick = { pendingEffects.remove(effect.id) },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("NO / BLOCKED", fontWeight = FontWeight.Black) }
                                        }
                                        else -> OutlinedButton(
                                            onClick = { pendingEffects.remove(effect.id) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("RESOLVE", fontWeight = FontWeight.Black) }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (due.isEmpty()) {
                    TextButton(onClick = { showEndRoundEffects = false; nextPhase() }) {
                        Text("CONTINUE CLEANUP", fontWeight = FontWeight.Black)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showEndRoundEffects = false }) { Text("BACK") } }
        )
    }

    if (showCleanupEffects) {
        val due = pendingEffects.dueRoundCleanup(round)
        AlertDialog(
            onDismissRequest = { showCleanupEffects = false },
            title = { Text("ROUND CLEANUP • RESOLVE EFFECTS", fontWeight = FontWeight.Black) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (due.isEmpty()) {
                        Text("Cleanup is complete. No temporary tracked effects remain for this round.")
                    } else {
                        Text(
                            "Clear each temporary effect at the real Cleanup & Refresh gate. Audio-linked removals stay pending if their sound fails, so a missed playback can be retried.",
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                            color = Color.White.copy(alpha = 0.68f)
                        )
                        due.forEachIndexed { index, effect ->
                            val accent = when (effect.kind) {
                                PendingEffectKind.FORCE_FIELD -> Color(0xFF77C7FF)
                                PendingEffectKind.GUARDIAN_SHIELD -> Color(0xFFFFD56A)
                                else -> Color(0xFFA8E55C)
                            }
                            Surface(
                                color = Color(0xFF0D171F),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.38f))
                            ) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "${effect.label}${if (effect.count > 1) " ×${effect.count}" else " #${index + 1}"}",
                                        fontWeight = FontWeight.Black,
                                        color = accent
                                    )
                                    if (effect.sourceLabel.isNotBlank()) {
                                        Text(effect.sourceLabel, fontSize = 8.8.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.50f))
                                    }
                                    Text(effect.detail, fontSize = 9.7.sp, lineHeight = 13.sp, color = Color.White.copy(alpha = 0.62f))
                                    OutlinedButton(
                                        onClick = {
                                            when (effect.kind) {
                                                PendingEffectKind.FORCE_FIELD -> {
                                                    val result = soundPlayer.playAbilityPhase(
                                                        effect.audioId.ifBlank { "sentry" },
                                                        effect.abilityId.ifBlank { "force_field" },
                                                        "remove"
                                                    )
                                                    if (result.success) {
                                                        musicPlayer.duckFor(durationMs = 1000)
                                                        pendingEffects.remove(effect.id)
                                                    } else Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                                }
                                                PendingEffectKind.GUARDIAN_SHIELD -> {
                                                    val result = soundPlayer.playAbilityPhase(
                                                        effect.audioId.ifBlank { "sentry" },
                                                        effect.abilityId.ifBlank { "guardian_shield" },
                                                        "expire"
                                                    )
                                                    if (result.success) {
                                                        musicPlayer.duckFor(durationMs = 1100)
                                                        pendingEffects.remove(effect.id)
                                                    } else Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                                }
                                                else -> pendingEffects.remove(effect.id)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            when (effect.kind) {
                                                PendingEffectKind.FORCE_FIELD -> "REMOVE FIELD"
                                                PendingEffectKind.GUARDIAN_SHIELD -> "EXPIRE SHIELD"
                                                else -> "CLEAR AT CLEANUP"
                                            },
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (due.isEmpty()) {
                    TextButton(onClick = {
                        showCleanupEffects = false
                        finishAfterRoundCleanup()
                    }) { Text("CLEANUP COMPLETE", fontWeight = FontWeight.Black) }
                }
            },
            dismissButton = { TextButton(onClick = { showCleanupEffects = false }) { Text("BACK TO SCORING") } }
        )
    }

    if (showActiveEffects) {
        val active = pendingEffects.effects
        AlertDialog(
            onDismissRequest = { showActiveEffects = false },
            title = { Text("ACTIVE / PENDING EFFECTS", fontWeight = FontWeight.Black) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Inspector only: scheduled effects resolve at their real timing gate. Manual tabletop events can be cleared here.",
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = Color.White.copy(alpha = 0.62f)
                    )
                    if (active.isEmpty()) Text("No tracked battlefield effects.", color = Color.White.copy(alpha = 0.56f))
                    val seen = mutableMapOf<PendingEffectKind, Int>()
                    active.forEach { effect ->
                        val number = (seen[effect.kind] ?: 0) + 1
                        seen[effect.kind] = number
                        val statusColor = when (effect.timing) {
                            PendingEffectTiming.END_ASSAULT, PendingEffectTiming.END_ROUND_DECISION -> Color(0xFFFFC65C)
                            PendingEffectTiming.STAY_IN_PLAY, PendingEffectTiming.MANUAL -> Color(0xFFA8E55C)
                            else -> Color(0xFF77C7FF)
                        }
                        Surface(
                            color = Color(0xFF0D171F),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.32f))
                        ) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${effect.label}${if (effect.count > 1) " ×${effect.count}" else " #$number"}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Black)
                                    Text(effect.statusLabel, fontSize = 7.5.sp, fontWeight = FontWeight.Black, color = statusColor)
                                }
                                if (effect.sourceLabel.isNotBlank()) {
                                    Text(effect.sourceLabel, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.48f))
                                }
                                Text(effect.detail, fontSize = 9.5.sp, lineHeight = 12.5.sp, color = Color.White.copy(alpha = 0.60f))
                                Text("Created R${effect.createdRound} • ${phases.getOrElse(effect.createdPhase) { "PHASE" }}", fontSize = 8.sp, color = Color.White.copy(alpha = 0.36f))

                                val manualAction = when {
                                    effect.kind == PendingEffectKind.FORCE_FIELD -> "REMOVE"
                                    effect.kind == PendingEffectKind.POINT_DEFENSE_DRONE -> "REMOVED"
                                    effect.timing == PendingEffectTiming.FIRST_USE -> "USED"
                                    effect.timing == PendingEffectTiming.NEXT_ACTION -> "ACTION DONE"
                                    effect.timing == PendingEffectTiming.END_ACTIVATION -> "ACTIVATION ENDED"
                                    effect.timing == PendingEffectTiming.MANUAL -> "CLEAR STATUS"
                                    effect.timing == PendingEffectTiming.STAY_IN_PLAY -> "REMOVE"
                                    else -> null
                                }
                                if (manualAction != null) {
                                    TextButton(
                                        onClick = {
                                            if (effect.kind == PendingEffectKind.FORCE_FIELD) {
                                                val result = soundPlayer.playAbilityPhase("sentry", "force_field", "remove")
                                                if (result.success) {
                                                    musicPlayer.duckFor(durationMs = 1000)
                                                    pendingEffects.remove(effect.id)
                                                } else {
                                                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                pendingEffects.remove(effect.id)
                                            }
                                        },
                                        modifier = Modifier.align(Alignment.End)
                                    ) { Text(manualAction, fontWeight = FontWeight.Black) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showActiveEffects = false }) { Text("BACK", fontWeight = FontWeight.Black) } }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = {
                TextButton(onClick = {
                    round = 1
                    gameLength = 5
                    phase = 0
                    gameStarted = false
                    gameUiRevealReady = false
                    matchIntroVisualStage = 0
                    playerCount = 2
                    firstPlayer = 0
                    nextFirstPlayer = -1
                    factionA = 0
                    factionB = 2
                    factionC = 1
                    scoreA = 0
                    scoreB = 0
                    scoreC = 0
                    supplyA = 0
                    supplyB = 0
                    supplyC = 0
                    soundPlayer.stop()
                    store.reset()
                    pendingEffects.clear()
                    onMatchConfigChanged()
                    confirmReset = false
                    confirmFinalScores = false
                    victoryVisible = false
                }) { Text("RESET") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("CANCEL") } },
            title = { Text("Reset game?") },
            text = { Text("This clears the saved round, phase, VP, supply, factions, initiative and all pending battlefield effects.") }
        )
    }

    if (confirmFinalScores) {
        val finalEntries: List<Triple<String, Faction, Int>> = buildList {
            add(Triple("PLAYER A", factionFromIndex(factionA), scoreA))
            if (playerCount >= 2) add(Triple("PLAYER B", factionFromIndex(factionB), scoreB))
            if (playerCount >= 3) add(Triple("PLAYER C", factionFromIndex(factionC), scoreC))
        }
        AlertDialog(
            onDismissRequest = { confirmFinalScores = false },
            title = { Text("FINAL SCORE CHECK", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Is the final VP total entered correctly?", color = Color.White.copy(alpha = 0.78f))
                    finalEntries.forEach { (playerLabel, faction, vp) ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF0C151E),
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(1.dp, factionColor(faction).copy(alpha = 0.42f))
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(playerLabel, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                                Text(faction.label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = factionColor(faction))
                                Spacer(Modifier.width(12.dp))
                                Text("$vp VP", fontSize = 13.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinalScores = false
                    victoryVisible = true
                }) { Text("YES — SHOW RESULT", fontWeight = FontWeight.Black) }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinalScores = false }) { Text("GO BACK") }
            }
        )
    }

    val initiativeTiedPlayers = run {
        val scores = activeScores()
        val low = scores.minOrNull() ?: 0
        scores.indices.filter { scores[it] == low }
    }
    if (tieInitiativeDialog) {
        AlertDialog(
            onDismissRequest = { tieInitiativeDialog = false },
            title = { Text("VP TIE - ROLL OFF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tied players roll 2D6. Higher total takes the First Player Marker for the next round; reroll ties.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        initiativeTiedPlayers.forEach { playerIndex ->
                            TextButton(onClick = { beginNextRound(playerIndex) }) {
                                Text("PLAYER ${('A'.code + playerIndex).toChar()}", fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { tieInitiativeDialog = false }) { Text("CANCEL") } }
        )
    }

    val scores = activeScores()
    val high = scores.maxOrNull() ?: 0
    val topPlayers = scores.indices.filter { scores[it] == high }
    val winner = if (topPlayers.size == 1) topPlayers.first() else -1
    val winnerFaction = when (winner) {
        0 -> factionFromIndex(factionA)
        1 -> factionFromIndex(factionB)
        2 -> factionFromIndex(factionC)
        else -> null
    }

    LaunchedEffect(victoryVisible, winnerFaction) {
        onVictoryVisibilityChanged(victoryVisible, winnerFaction)
    }

    LaunchedEffect(victoryVisible, winnerFaction, announcerMode) {
        if (victoryVisible && winnerFaction != null) {
            uiPlayer.stop()
            // Result mode owns the normal jukebox bus. R11 previously replaced the old soundtrack
            // with only a 5–10 second UI stinger, which correctly signalled a win but did not behave
            // like victory *music*. The Codex/source audit supplied explicit faction result-music
            // candidates, so each winner now gets a real faction victory cue plus a quieter official
            // Victory Panel stinger. Neither layer ever falls back to ordinary multiplayer music.
            musicPlayer.beginResult()
            if (musicStore.playbackEnabled) {
                val musicScope = when (winnerFaction) {
                    Faction.TERRAN -> ResultWinnerScope.TERRAN
                    Faction.PROTOSS -> ResultWinnerScope.PROTOSS
                    Faction.ZERG -> ResultWinnerScope.ZERG
                    Faction.HYBRID -> ResultWinnerScope.ANY
                }
                val factionMusic = resultRegistry.victoryMusic(musicScope)
                    .filter { uiPlayer.isPoolClipEnabled(it.assetPath) }
                    .ifEmpty { resultRegistry.victoryMusic(ResultWinnerScope.ANY).filter { uiPlayer.isPoolClipEnabled(it.assetPath) } }
                if (factionMusic.isNotEmpty()) {
                    val track = VictoryClipBag.next("victory-music-${winnerFaction.name}", factionMusic.map { it.assetPath })
                    musicPlayer.playResultAsset(track)
                }
                val stingers = resultRegistry.victoryMusic(ResultWinnerScope.ANY)
                    .filter { uiPlayer.isPoolClipEnabled(it.assetPath) }
                if (stingers.isNotEmpty()) {
                    val stinger = VictoryClipBag.next("victory-music-neutral", stingers.map { it.assetPath })
                    uiPlayer.playUi(stinger, gain = 0.54f)
                }
            }
            announcer.announceVictory(winnerFaction, announcerMode)

            launch {
                val voiceDelayMs = when (announcerMode) {
                    AnnouncerMode.OFF -> 900L
                    AnnouncerMode.TERRAN -> 1650L
                    AnnouncerMode.MALE, AnnouncerMode.FEMALE -> 1950L
                }
                delay(voiceDelayMs)

                // Only EndGame winner lines that belong to the winning faction enter the automatic
                // result pool. Generic GG/congratulations clips were moved to the GG/CEREMONY
                // archive and ordinary unit Yes/Attack/What clips are not outcome VO.
                val factionVictoryLines = resultRegistry.victoryVo(winnerFaction)
                    .filter { uiPlayer.isPoolClipEnabled(it.assetPath) }

                if (factionVictoryLines.isNotEmpty()) {
                    val victoryLine = nextScopedResultEntry("victory-vo", winnerFaction, factionVictoryLines)
                        ?: return@launch
                    // Keep the result MUSIC bus underneath the winner line rather than letting the
                    // restored victory VO fight it at full level. The old curated victory route
                    // ducked here; the crashed R11 reconstruction had lost that behavior.
                    musicPlayer.beginDuck()
                    try {
                        val spoken = uiPlayer.playResultVoice(victoryLine, duckDoorDb = 0f)
                        val responseHoldMs = (uiPlayer.currentVoiceDurationMs().toLong() + 500L).coerceAtLeast(2500L)
                        if (spoken.success) delay(responseHoldMs)
                    } finally {
                        musicPlayer.endDuck()
                    }
                }
            }
        } else if (victoryVisible && winnerFaction == null) {
            uiPlayer.stop()
            // Draws get their own neutral result route. The user previously approved using the
            // verified generic defeat-panel stinger because no dedicated draw music was supplied.
            musicPlayer.beginResult()
            announcer.silence()
            val drawStingers = resultRegistry.drawMusic().filter { uiPlayer.isPoolClipEnabled(it.assetPath) }
            if (musicStore.playbackEnabled && drawStingers.isNotEmpty()) {
                val drawPath = VictoryClipBag.next("draw-music", drawStingers.map { it.assetPath })
                musicPlayer.playResultAsset(drawPath)
            }
            launch {
                delay(700L)
                val tieLines = resultRegistry.tieVo().filter { uiPlayer.isPoolClipEnabled(it.assetPath) }
                if (tieLines.isNotEmpty()) {
                    val tiePath = VictoryClipBag.next("tie-vo", tieLines.map { it.assetPath })
                    val tieLine = tieLines.first { it.assetPath == tiePath }
                    musicPlayer.beginDuck()
                    try {
                        val spoken = uiPlayer.playResultVoice(tieLine, duckDoorDb = 0f)
                        if (spoken.success) delay((uiPlayer.currentVoiceDurationMs().toLong() + 500L).coerceAtLeast(2500L))
                    } finally {
                        musicPlayer.endDuck()
                    }
                }
            }
        } else if (!victoryVisible) {
            // Stop any 20–65 second result-music cue before resuming the jukebox.
            uiPlayer.stop()
            musicPlayer.endResult(resumeSoundtrack = musicStore.playbackEnabled)
        }
    }

    val compactPlayers = playerCount >= 3

    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            startOverlayOriginInRoot = it.boundsInRoot().topLeft
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // Player factions are persistent match identity, not pre-game-only controls. Keep them
            // above the command panel so they remain in exactly the same place before and after START.
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = identityHeaderAlpha },
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                TrackerPlayerHeader("A", factionFromIndex(factionA), Modifier.weight(1f), compact = compactPlayers, enabled = !gameStarted) {
                    val next = (factionA + 1) % 3
                    factionA = next
                    store.playerFactionA = next
                    onMatchConfigChanged()
                }
                if (playerCount >= 2) TrackerPlayerHeader("B", factionFromIndex(factionB), Modifier.weight(1f), compact = compactPlayers, enabled = !gameStarted) {
                    val next = (factionB + 1) % 3
                    factionB = next
                    store.playerFactionB = next
                    onMatchConfigChanged()
                }
                if (playerCount >= 3) TrackerPlayerHeader("C", factionFromIndex(factionC), Modifier.weight(1f), compact = true, enabled = !gameStarted) {
                    val next = (factionC + 1) % 3
                    factionC = next
                    store.playerFactionC = next
                    onMatchConfigChanged()
                }
            }

            // Keep the authored command window at one fixed size. The START/GAME plate is measured
            // here, but its animated shutter is rendered later at the root so it cannot be clipped.
            Surface(
                modifier = Modifier.fillMaxWidth().height(252.dp),
                color = Color(0xEA091019),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.30f))
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp)
                            .graphicsLayer { alpha = if (!gameStarted) 1f else liveTrackerAlpha },
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        if (!gameStarted) {
                            GameLengthSelector(
                                gameLength = gameLength,
                                onGameLengthChange = {
                                    gameLength = it.coerceIn(4, 10)
                                    round = round.coerceAtMost(gameLength)
                                }
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "ROUND 1 FIRST PLAYER?",
                                    fontSize = 9.4.sp,
                                    letterSpacing = 0.72.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF8FD6FF)
                                )
                                Spacer(Modifier.width(8.dp))
                                FirstPlayerButton("A", selected = firstPlayer == 0, Modifier.weight(1f)) { firstPlayer = 0 }
                                if (playerCount >= 2) {
                                    Spacer(Modifier.width(5.dp))
                                    FirstPlayerButton("B", selected = firstPlayer == 1, Modifier.weight(1f)) { firstPlayer = 1 }
                                }
                                if (playerCount >= 3) {
                                    Spacer(Modifier.width(5.dp))
                                    FirstPlayerButton("C", selected = firstPlayer == 2, Modifier.weight(1f)) { firstPlayer = 2 }
                                }
                            }

                            SplitStartGameButton(
                                modifier = Modifier.onGloballyPositioned { startButtonBoundsInRoot = it.boundsInRoot() },
                                visible = !startRevealActive,
                                onLaunch = { if (!startRevealActive && startButtonBoundsInRoot != null) startRevealActive = true }
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "PHASE",
                                    modifier = Modifier.width(48.dp),
                                    textAlign = TextAlign.Start,
                                    fontSize = 9.2.sp,
                                    letterSpacing = 1.15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF8FD6FF)
                                )
                                CompactCounterButton(Icons.Default.Remove) { round = (round - 1).coerceAtLeast(1) }
                                Text(
                                    "$round / $gameLength",
                                    modifier = Modifier.width(82.dp),
                                    textAlign = TextAlign.Center,
                                    fontSize = 22.sp,
                                    lineHeight = 23.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip
                                )
                                CompactCounterButton(Icons.Default.Add) { round = (round + 1).coerceAtMost(gameLength) }
                                Text(
                                    "ROUND",
                                    modifier = Modifier.width(52.dp),
                                    textAlign = TextAlign.End,
                                    fontSize = 9.2.sp,
                                    letterSpacing = 1.15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF8FD6FF)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CompactPhaseButton(Icons.Default.KeyboardArrowLeft, "Previous phase", ::previousPhase)
                                Spacer(Modifier.width(7.dp))
                                Surface(
                                    modifier = Modifier.weight(1f).height(57.dp),
                                    color = Color(0xFF0E1822),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.3.dp, Color(0xFF77C7FF).copy(alpha = 0.48f))
                                ) {
                                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text("PHASE ${phase + 1}", fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Black, color = Color(0xFF8FD6FF))
                                        Text(phases[phase], fontSize = if (phase == 3) 14.sp else 17.sp, lineHeight = 18.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, color = Color.White)
                                    }
                                }
                                Spacer(Modifier.width(7.dp))
                                CompactPhaseButton(Icons.Default.KeyboardArrowRight, "Next phase", ::nextPhase)
                            }

                            FirstPlayerStatus(
                                playerIndex = firstPlayer,
                                faction = when (firstPlayer) {
                                    1 -> factionFromIndex(factionB)
                                    2 -> factionFromIndex(factionC)
                                    else -> factionFromIndex(factionA)
                                }
                            )

                            if (phase <= 1 && playerCount > 1) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF0C151E),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.24f))
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "FIRST TO PASS?",
                                                fontSize = 9.8.sp,
                                                letterSpacing = 0.95.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color.White
                                            )
                                            Text(
                                                "sets First Player for the next phase",
                                                fontSize = 6.7.sp,
                                                color = Color.White.copy(alpha = 0.40f)
                                            )
                                        }
                                        PassButton("A", nextFirstPlayer == 0) { nextFirstPlayer = 0 }
                                        if (playerCount >= 2) {
                                            Spacer(Modifier.width(6.dp))
                                            PassButton("B", nextFirstPlayer == 1) { nextFirstPlayer = 1 }
                                        }
                                        if (playerCount >= 3) {
                                            Spacer(Modifier.width(6.dp))
                                            PassButton("C", nextFirstPlayer == 2) { nextFirstPlayer = 2 }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (gameStarted && !gameUiRevealReady) {
                        MatchIntroVersusPanel(
                            factionA = factionFromIndex(factionA),
                            factionB = factionFromIndex(factionB),
                            factionC = if (playerCount >= 3) factionFromIndex(factionC) else null,
                            emblemAssetFor = themeAssetFor,
                            stage = matchIntroVisualStage,
                            modifier = Modifier.matchParentSize()
                        )
                    }

                }
            }

            AnimatedVisibility(
                visible = gameStarted && gameUiRevealReady,
                enter = fadeIn(animationSpec = tween(330, delayMillis = 70)) +
                    slideInVertically(animationSpec = tween(390, delayMillis = 70, easing = FastOutSlowInEasing)) { it / 3 }
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    CounterPanel("VP", scoreA, Modifier.weight(1f), compact = compactPlayers, revealGlowDelayMs = 0, onMinus = { scoreA = (scoreA - 1).coerceAtLeast(0) }, onPlus = { scoreA = (scoreA + 1).coerceAtMost(99) })
                    if (playerCount >= 2) CounterPanel("VP", scoreB, Modifier.weight(1f), compact = compactPlayers, revealGlowDelayMs = 90, onMinus = { scoreB = (scoreB - 1).coerceAtLeast(0) }, onPlus = { scoreB = (scoreB + 1).coerceAtMost(99) })
                    if (playerCount >= 3) CounterPanel("VP", scoreC, Modifier.weight(1f), compact = true, revealGlowDelayMs = 180, onMinus = { scoreC = (scoreC - 1).coerceAtLeast(0) }, onPlus = { scoreC = (scoreC + 1).coerceAtMost(99) })
                }
            }

            AnimatedVisibility(
                visible = gameStarted && gameUiRevealReady,
                enter = fadeIn(animationSpec = tween(340, delayMillis = 170)) +
                    slideInVertically(animationSpec = tween(420, delayMillis = 170, easing = FastOutSlowInEasing)) { it / 3 }
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    CounterPanel("SUPPLY", supplyA, Modifier.weight(1f), compact = compactPlayers, revealGlowDelayMs = 260, onMinus = { supplyA = (supplyA - 1).coerceAtLeast(0) }, onPlus = { supplyA = (supplyA + 1).coerceAtMost(200) })
                    if (playerCount >= 2) CounterPanel("SUPPLY", supplyB, Modifier.weight(1f), compact = compactPlayers, revealGlowDelayMs = 350, onMinus = { supplyB = (supplyB - 1).coerceAtLeast(0) }, onPlus = { supplyB = (supplyB + 1).coerceAtMost(200) })
                    if (playerCount >= 3) CounterPanel("SUPPLY", supplyC, Modifier.weight(1f), compact = true, revealGlowDelayMs = 440, onMinus = { supplyC = (supplyC - 1).coerceAtLeast(0) }, onPlus = { supplyC = (supplyC + 1).coerceAtMost(200) })
                }
            }

            Spacer(Modifier.weight(1f))
            if (gameStarted && gameUiRevealReady) {
                // One fixed footer row only. EFFECTS must never create an extra row that pushes
                // GAME SETUP / RESET off the Command Console on a short or heavily inset display.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (pendingEffects.effects.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showActiveEffects = true },
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 3.dp),
                            border = BorderStroke(1.dp, Color(0xFFA8E55C).copy(alpha = 0.42f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD9FFAA))
                        ) {
                            Text("EFFECTS (${pendingEffects.effects.size})", fontSize = 6.8.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        }
                    }
                    OutlinedButton(
                        onClick = onNewGameSetup,
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 3.dp),
                        border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.42f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB9E4FF))
                    ) {
                        Icon(Icons.Default.Settings, null, Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("SETUP", fontSize = 6.9.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { confirmReset = true },
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 3.dp),
                        border = BorderStroke(1.dp, Color(0xFFD66A6A).copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB4B4))
                    ) {
                        Icon(Icons.Default.RestartAlt, null, Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("RESET", fontSize = 6.9.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }
                }
            } else if (!gameStarted) {
                OutlinedButton(
                    onClick = onNewGameSetup,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    contentPadding = PaddingValues(0.dp),
                    border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.42f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB9E4FF))
                ) {
                    Icon(Icons.Default.Settings, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("BACK TO GAME SETUP", fontSize = 8.6.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        PhaseEventFlash(
            phase = phase,
            round = round,
            enabled = gameStarted && gameUiRevealReady && !victoryVisible && !startRevealActive
        )

        if (victoryVisible) {
            VictoryOverlay(
                winner = winner,
                winnerFaction = winnerFaction,
                winnerThemeLabel = winnerFaction?.let { themeLabelFor(it) },
                emblemAsset = winnerFaction?.let { themeAssetFor(it) },
                scores = scores,
                onClose = { victoryVisible = false },
                onNewGame = { onVictoryNewGame(winnerFaction) }
            )
        }

        // Compose the shutter last at the page root. It starts at the measured START/GAME plate,
        // then owns the complete viewport above headers, panels, footer, flashes and result UI.
        startButtonBoundsInRoot?.let { rootBounds ->
            if (startRevealActive) {
                // The button is measured in root coordinates, while this full-screen overlay is a
                // child of CompanionPage. Convert once into the overlay's local coordinate space so
                // its first frame remains pixel-aligned with the button instead of snapping away.
                val localBounds = androidx.compose.ui.geometry.Rect(
                    left = rootBounds.left - startOverlayOriginInRoot.x,
                    top = rootBounds.top - startOverlayOriginInRoot.y,
                    right = rootBounds.right - startOverlayOriginInRoot.x,
                    bottom = rootBounds.bottom - startOverlayOriginInRoot.y
                )
                StartGameDoorRevealOverlay(
                    startBounds = localBounds,
                    modifier = Modifier.matchParentSize(),
                    onCovered = ::prepareStartedGameBehindShutter,
                    onOpenSound = { uiPlayer.playUi("audio/ui/intro/door_open.wav", gain = 0.62f) },
                    onRevealComplete = ::finishStartReveal
                )
            }
        }
    }
}

@Composable
private fun MatchIntroVersusPanel(
    factionA: Faction,
    factionB: Faction,
    factionC: Faction?,
    emblemAssetFor: (Faction) -> String,
    stage: Int,
    modifier: Modifier = Modifier
) {
    val matchupAlpha by animateFloatAsState(
        targetValue = if (stage >= 6) 0.22f else 1f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "matchupDimForGlhf"
    )
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.975f)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).graphicsLayer { alpha = matchupAlpha },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MatchIntroFactionBadge(
                playerLabel = "A",
                faction = factionA,
                emblemAsset = emblemAssetFor(factionA),
                visible = stage >= 1,
                active = stage == 1,
                compact = factionC != null
            )

            AnimatedVisibility(
                visible = stage >= 2,
                enter = fadeIn(animationSpec = tween(180))
            ) {
                Text(
                    "V.S.",
                    modifier = Modifier.padding(horizontal = if (factionC == null) 12.dp else 6.dp),
                    fontSize = if (factionC == null) 17.sp else 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color = Color.White.copy(alpha = 0.68f)
                )
            }

            MatchIntroFactionBadge(
                playerLabel = "B",
                faction = factionB,
                emblemAsset = emblemAssetFor(factionB),
                visible = stage >= 3,
                active = stage == 3,
                compact = factionC != null
            )

            if (factionC != null) {
                AnimatedVisibility(
                    visible = stage >= 4,
                    enter = fadeIn(animationSpec = tween(180))
                ) {
                    Text(
                        "V.S.",
                        modifier = Modifier.padding(horizontal = 5.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp,
                        color = Color.White.copy(alpha = 0.50f)
                    )
                }
                MatchIntroFactionBadge(
                    playerLabel = "C",
                    faction = factionC,
                    emblemAsset = emblemAssetFor(factionC),
                    visible = stage >= 5,
                    active = stage == 5,
                    compact = true
                )
            }
        }

        AnimatedVisibility(
            visible = stage == 6,
            enter = fadeIn(animationSpec = tween(220)) + scaleIn(
                initialScale = 0.94f,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(animationSpec = tween(180))
        ) {
            val pulse = rememberInfiniteTransition(label = "glhf-plaque-pulse")
            val glow by pulse.animateFloat(
                initialValue = 0.82f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(850, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glhf-plaque-glow"
            )
            Box(
                Modifier.fillMaxWidth(if (factionC == null) 0.54f else 0.44f)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = 0.985f + glow * 0.015f
                        scaleY = 0.985f + glow * 0.015f
                        shadowElevation = 12f * glow
                    }
            ) {
                AssetFrame(
                    "images/ui/intro/glhf_plaque.png",
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        AnimatedVisibility(
            visible = stage >= 7,
            enter = fadeIn(animationSpec = tween(220)) + scaleIn(
                initialScale = 0.90f,
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            )
        ) {
            Text(
                "ROUND 1",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.2.sp,
                color = Color(0xFFBDEAFF),
                style = LocalTextStyle.current.copy(
                    shadow = Shadow(Color(0xFF42BFFF).copy(alpha = 0.72f), Offset.Zero, 16f)
                )
            )
        }
    }
}

@Composable
private fun MatchIntroFactionBadge(
    playerLabel: String,
    faction: Faction,
    emblemAsset: String,
    visible: Boolean,
    active: Boolean,
    compact: Boolean
) {
    val accent = factionColor(faction)
    val scale by animateFloatAsState(
        targetValue = if (active) 1.075f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "introFactionScale$playerLabel"
    )
    val glow by animateFloatAsState(
        targetValue = if (active) 0.94f else 0.46f,
        animationSpec = tween(180),
        label = "introFactionGlow$playerLabel"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(210)) + scaleIn(
            initialScale = 0.86f,
            animationSpec = tween(240, easing = FastOutSlowInEasing)
        )
    ) {
        Surface(
            modifier = Modifier
                .width(if (compact) 68.dp else 92.dp)
                .heightIn(min = if (compact) 112.dp else 130.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            color = Color(0xFF070B10),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(if (active) 1.8.dp else 1.dp, accent.copy(alpha = glow)),
            shadowElevation = if (active) 8.dp else 1.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "PLAYER $playerLabel",
                    fontSize = if (compact) 6.2.sp else 7.4.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.75.sp,
                    color = accent.copy(alpha = if (active) 1f else 0.72f)
                )
                Spacer(Modifier.height(4.dp))
                AssetFrame(
                    emblemAsset,
                    Modifier.size(if (compact) 52.dp else 66.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    faction.label,
                    fontSize = if (compact) 6.5.sp else 8.sp,
                    lineHeight = if (compact) 9.sp else 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    color = Color.White.copy(alpha = if (active) 0.95f else 0.68f),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun TrackerPlayerHeader(
    label: String,
    faction: Faction,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val accent = factionColor(faction)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(39.dp),
        color = Color(0xE60A1018),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.40f))
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = if (compact) 6.dp else 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = if (compact) 16.sp else 19.sp, fontWeight = FontWeight.Black, color = accent)
            Spacer(Modifier.width(if (compact) 4.dp else 7.dp))
            Text(
                faction.label,
                modifier = Modifier.weight(1f),
                fontSize = if (compact) 7.4.sp else 9.5.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = if (compact) 0.2.sp else 0.7.sp,
                color = Color.White.copy(alpha = 0.86f),
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            if (!compact) Text(if (enabled) "TAP" else "LOCKED", fontSize = 6.8.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.30f))
        }
    }
}

@Composable
private fun FirstPlayerStatus(playerIndex: Int, faction: Faction) {
    val accent = factionColor(faction)
    Surface(
        modifier = Modifier.fillMaxWidth().height(43.dp),
        color = Color(0xFF0C151E),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.46f))
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "FIRST PLAYER",
                    fontSize = 9.8.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.05.sp,
                    color = Color(0xFF8FD6FF)
                )
                Text(
                    "AUTOMATIC • current phase",
                    fontSize = 6.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.36f)
                )
            }
            Surface(
                color = accent.copy(alpha = 0.18f),
                shape = RoundedCornerShape(7.dp),
                border = BorderStroke(1.4.dp, accent.copy(alpha = 0.86f))
            ) {
                Text(
                    "PLAYER ${('A'.code + playerIndex.coerceIn(0, 2)).toChar()}",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.55.sp,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun PassButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 43.dp, height = 31.dp),
        color = if (selected) Color(0xFF184A68) else Color(0xFF101820),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(if (selected) 1.8.dp else 1.dp, if (selected) Color(0xFF8FD6FF) else Color.White.copy(alpha = 0.11f))
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Black) }
    }
}

@Composable
private fun CompactCounterButton(icon: androidx.compose.ui.graphics.vector.ImageVector, compact: Boolean = false, onClick: () -> Unit) {
    val buttonSize = if (compact) 25.dp else 34.dp
    Surface(
        onClick = onClick,
        modifier = Modifier.size(buttonSize),
        color = Color(0xFF101A24),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.26f))
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(if (compact) 15.dp else 18.dp)) }
    }
}

@Composable
private fun CompactPhaseButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
        color = Color(0xFF101A24),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.32f))
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(30.dp)) }
    }
}

@Composable
private fun VictoryOverlay(
    winner: Int,
    winnerFaction: Faction?,
    winnerThemeLabel: String?,
    emblemAsset: String?,
    scores: List<Int>,
    onClose: () -> Unit,
    onNewGame: () -> Unit
) {
    val context = LocalContext.current
    val accent = winnerFaction?.let(::factionColor) ?: Color(0xFF77C7FF)
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp))) {
        if (winnerFaction != null) {
            val victoryAsset = remember(winnerFaction, winnerThemeLabel) {
                val pool = victoryArtPool(context, winnerFaction, winnerThemeLabel)
                VictoryArtBag.next(
                    key = "victory-art-${winnerFaction.name}-${winnerThemeLabel ?: "generic"}",
                    art = pool
                )
            }
            AssetBackground(victoryAsset, ContentScale.Crop)
        } else {
            // Draws are never allowed to leak into a faction victory pool. Neutral tie art is
            // data-driven and optional; until an approved tie-art drop exists we fall back safely.
            val tieAsset = remember {
                val pool = tieArtPool(context)
                if (pool.isEmpty()) null else VictoryArtBag.next("victory-art-tie", pool)
            }
            if (tieAsset != null) AssetBackground(tieAsset, ContentScale.Crop) else TrackerBackdrop()
        }

        AmbientBackdropFx(accent = accent, intensity = if (winnerFaction == null) 0.70f else 1.05f, particleCount = if (winnerFaction == null) 10 else 24)
        VictoryCelebrationFx(accent = accent, enabled = winnerFaction != null)

        // Keep the actual victory artwork readable. UI lives in the authored dark head/foot zones
        // instead of stacking a logo, title and scores over the character in the middle of the image.
        Box(
            Modifier.fillMaxWidth().height(132.dp).align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.88f), Color.Black.copy(alpha = 0.46f), Color.Transparent)
                    )
                )
        )
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .padding(top = 30.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (emblemAsset != null) {
                AssetFrame(emblemAsset, Modifier.size(52.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (winnerFaction == null) "DRAW" else "${winnerFaction.label} VICTORY",
                    fontSize = if (winnerFaction == null) 24.sp else 21.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.25.sp,
                    color = if (winnerFaction == null) Color.White else accent,
                    maxLines = 1
                )
                Text(
                    if (winner < 0) "MISSION TIE" else "PLAYER ${('A'.code + winner).toChar()} WINS",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.65.sp,
                    color = Color.White.copy(alpha = 0.68f)
                )
            }
        }

        Box(
            Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.64f), Color.Black.copy(alpha = 0.92f))
                    )
                )
        )
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("FINAL SCORE", fontSize = 8.5.sp, fontWeight = FontWeight.Black, letterSpacing = 1.15.sp, color = Color.White.copy(alpha = 0.58f))
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                scores.forEachIndexed { index, score ->
                    Surface(
                        modifier = Modifier.weight(1f).height(48.dp),
                        color = Color(0xB90A1018),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (index == winner) accent.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.12f))
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${('A'.code + index).toChar()}", fontSize = 11.sp, fontWeight = FontWeight.Black, color = if (index == winner) accent else Color.White.copy(alpha = 0.58f))
                            Spacer(Modifier.weight(1f))
                            Text(score.toString(), fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White)
                        }
                    }
                }
            }
            if (winner < 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Check the Mission Card tiebreaker. If it defines none, the game is a Draw.",
                    fontSize = 8.5.sp,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.62f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("BACK", fontSize = 9.0.sp, fontWeight = FontWeight.Black)
                }
                Button(onClick = onNewGame, modifier = Modifier.weight(1.35f).height(40.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("NEW GAME", fontSize = 9.0.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun FirstPlayerButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = Color(0xFF77C7FF)
    Surface(
        modifier = modifier.height(34.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        color = if (selected) Color(0xFF153A54) else Color(0xFF101820),
        border = BorderStroke(if (selected) 1.7.dp else 1.dp, if (selected) accent else Color.White.copy(alpha = 0.10f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Black, color = if (selected) Color.White else Color.White.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun CounterPanel(
    title: String,
    value: Int,
    modifier: Modifier,
    compact: Boolean = false,
    revealGlowDelayMs: Int = -1,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    val bump = remember { Animatable(1f) }
    val revealGlow = remember { Animatable(0f) }
    LaunchedEffect(value) {
        bump.snapTo(1.13f)
        bump.animateTo(1f, animationSpec = tween(220, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(revealGlowDelayMs) {
        if (revealGlowDelayMs >= 0) {
            revealGlow.snapTo(0f)
            delay(revealGlowDelayMs.toLong())
            revealGlow.animateTo(1f, animationSpec = tween(170, easing = FastOutSlowInEasing))
            revealGlow.animateTo(0f, animationSpec = tween(520, easing = FastOutSlowInEasing))
        }
    }
    Surface(
        modifier = modifier.height(if (compact) 70.dp else 76.dp)
            .shadow((10f * revealGlow.value).dp, RoundedCornerShape(8.dp), clip = false),
        color = Color(0xE60A1018),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp + (0.8f * revealGlow.value).dp,
            Color(0xFF77C7FF).copy(alpha = 0.24f + 0.62f * revealGlow.value)
        )
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 4.dp else 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = if (compact) 8.sp else 9.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Black, color = Color(0xFF9FD8FF), maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                CompactCounterButton(Icons.Default.Remove, compact = compact, onClick = onMinus)
                Text(
                    value.toString(),
                    modifier = Modifier.weight(1f).graphicsLayer {
                        scaleX = bump.value
                        scaleY = bump.value
                    },
                    textAlign = TextAlign.Center,
                    fontSize = if (compact) 20.sp else 27.sp,
                    lineHeight = if (compact) 21.sp else 28.sp,
                    fontWeight = FontWeight.Black
                )
                CompactCounterButton(Icons.Default.Add, compact = compact, onClick = onPlus)
            }
        }
    }
}

@Composable
private fun CounterRow(label: String, value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Bold)
        SmallCounterButton(Icons.Default.Remove, onMinus)
        Text(value.toString(), modifier = Modifier.width(42.dp), textAlign = TextAlign.Center, fontSize = 24.sp, fontWeight = FontWeight.Black)
        SmallCounterButton(Icons.Default.Add, onPlus)
    }
}

@Composable
private fun SmallCounterButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        color = Color(0xFF101A24),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.36f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(26.dp), tint = Color(0xFFBCE2FF))
        }
    }
}

private fun supplementalAbilityAudio(audioId: String): List<AbilityAudioSpec> = emptyList()


@Composable
private fun UnitsPage(
    units: List<UnitEntry>,
    selected: UnitEntry?,
    player: SoundPlayer,
    musicPlayer: MusicPlayer,
    accent: Color,
    themeIconAsset: String,
    themeSquareFrameAsset: String,
    themeRectFrameAsset: String,
    onCycleTheme: () -> Unit,
    onSelect: (UnitEntry) -> Unit,
    onAction: (String) -> Unit,
    onAbility: (String) -> Unit
) {
    if (units.isEmpty()) {
        EmptyCollectionMessage("No units selected for this faction.\nOpen COLLECTION to add some.")
        return
    }
    val haptic = LocalHapticFeedback.current
    var poolCategory by remember { mutableStateOf<String?>(null) }
    var poolAbilityId by remember { mutableStateOf<String?>(null) }
    var showAbilityDrawer by remember { mutableStateOf(false) }
    if (selected != null && poolCategory != null) {
        SoundPoolManagerDialog(
            unit = selected,
            category = poolCategory!!,
            player = player,
            musicPlayer = musicPlayer,
            onDismiss = { poolCategory = null }
        )
    }
    if (selected != null && poolAbilityId != null) {
        val ability = (AbilityAudioRegistry.forAudioId(selected.audioId) + supplementalAbilityAudio(selected.audioId))
            .distinctBy { it.id }
            .firstOrNull { it.id == poolAbilityId }
        if (ability != null) {
            AbilitySoundPoolManagerDialog(
                unit = selected,
                ability = ability,
                player = player,
                musicPlayer = musicPlayer,
                onDismiss = { poolAbilityId = null }
            )
        } else {
            LaunchedEffect(poolAbilityId) { poolAbilityId = null }
        }
    }
    PrefetchUnitArt(units, "units:${units.firstOrNull()?.faction}:${units.size}")

    Row(
        Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        GlowingContentPanel(accent, Modifier.weight(0.47f).fillMaxHeight()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 7.dp),
                contentPadding = PaddingValues(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(units, key = { it.id }) { unit ->
                    UnitSelectorTile(unit, selected?.id == unit.id, accent, themeSquareFrameAsset) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(unit)
                    }
                }
            }
        }

        GlowingContentPanel(accent, Modifier.weight(0.53f).fillMaxHeight()) {
            selected?.let { u ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val portraitVoiceAvailable = player.hasUnitAudio(u.audioId, "chatter")
                    val portraitInteraction = remember(u.id) { MutableInteractionSource() }
                    val portraitPressed by portraitInteraction.collectIsPressedAsState()
                    Box(
                        Modifier.graphicsLayer {
                            val pressScale = if (portraitPressed) 0.965f else 1f
                            scaleX = pressScale
                            scaleY = pressScale
                        }.combinedClickable(
                            enabled = portraitVoiceAvailable,
                            interactionSource = portraitInteraction,
                            indication = null,
                            onClick = { onAction("chatter") },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                poolCategory = "chatter"
                            }
                        )
                    ) {
                        FramedUnitPortrait(u, size = 112.dp, preferAnimation = true, frameAsset = themeSquareFrameAsset)
                    }
                    Spacer(Modifier.height(4.dp))

                    Text(
                        u.name.uppercase(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        fontSize = 13.5.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.24.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    val total = player.unitClipCount(u.audioId, "move") +
                        player.unitClipCount(u.audioId, "chatter") +
                        player.unitClipCount(u.audioId, "attack") +
                        player.unitClipCount(u.audioId, "death")
                    val combatSfx = player.unitAttackSfxCount(u.audioId)
                    val abilities = (AbilityAudioRegistry.forAudioId(u.audioId) + supplementalAbilityAudio(u.audioId))
                        .distinctBy { it.id }
                        .filter { player.hasUnitAbilityAudio(u.audioId, it.id) }
                    val abilitySfx = abilities.sumOf { player.unitAbilityClipCount(u.audioId, it.id) }
                    Text(
                        when {
                            combatSfx > 0 && abilitySfx > 0 -> "$total VO  •  $combatSfx WEAPON  •  $abilitySfx ABILITY"
                            combatSfx > 0 -> "$total VO  •  $combatSfx SFX"
                            abilitySfx > 0 -> "$total VO  •  $abilitySfx ABILITY"
                            else -> "$total VO"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 7.7.sp,
                        lineHeight = 8.6.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.55.sp,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1
                    )

                    Spacer(Modifier.height(8.dp))
                    ActionButton(
                        "MOVE", u.faction, player.hasUnitAudio(u.audioId, "move"),
                        accentOverride = accent, frameAsset = themeRectFrameAsset, modifier = Modifier.fillMaxWidth().height(54.dp), compact = false,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            poolCategory = "move"
                        }
                    ) {
                        onAction("move")
                    }
                    Spacer(Modifier.height(5.dp))
                    ActionButton(
                        "ATTACK", u.faction, player.hasUnitAudio(u.audioId, "attack"),
                        accentOverride = accent, frameAsset = themeRectFrameAsset, modifier = Modifier.fillMaxWidth().height(54.dp), compact = false,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            poolCategory = "attack"
                        }
                    ) {
                        onAction("attack")
                    }
                    Spacer(Modifier.height(5.dp))
                    ActionButton(
                        "DEATH", u.faction, player.hasUnitAudio(u.audioId, "death"),
                        accentOverride = accent, frameAsset = themeRectFrameAsset, modifier = Modifier.fillMaxWidth().height(54.dp), compact = false,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            poolCategory = "death"
                        }
                    ) {
                        onAction("death")
                    }

                    if (abilities.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "ABILITY SFX",
                            fontSize = 6.9.sp,
                            lineHeight = 7.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.85.sp,
                            color = Color.White.copy(alpha = 0.52f)
                        )
                        Spacer(Modifier.height(3.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            abilities.take(2).forEach { ability ->
                                ActionButton(
                                    ability.label,
                                    u.faction,
                                    enabled = true,
                                    accentOverride = accent,
                                    frameAsset = themeRectFrameAsset,
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    compact = true,
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        poolAbilityId = ability.id
                                    }
                                ) {
                                    onAbility(ability.id)
                                }
                            }
                        }
                        if (abilities.size > 2) {
                            Spacer(Modifier.height(4.dp))
                            ActionButton(
                                "MORE / ABILITIES (${abilities.size - 2})",
                                u.faction,
                                enabled = true,
                                accentOverride = accent,
                                frameAsset = themeRectFrameAsset,
                                modifier = Modifier.fillMaxWidth().height(34.dp),
                                compact = true
                            ) { showAbilityDrawer = true }
                        }
                        if (showAbilityDrawer) {
                            AbilityDrawerDialog(
                                unit = u,
                                abilities = abilities,
                                onAbility = { abilityId -> showAbilityDrawer = false; onAbility(abilityId) },
                                onAbilityLongClick = { abilityId -> showAbilityDrawer = false; poolAbilityId = abilityId },
                                onDismiss = { showAbilityDrawer = false }
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))
                    val themeInteraction = remember { MutableInteractionSource() }
                    val themePressed by themeInteraction.collectIsPressedAsState()
                    val themeGlowTransition = rememberInfiniteTransition(label = "theme-emblem")
                    val themeGlow by themeGlowTransition.animateFloat(
                        initialValue = 0.24f,
                        targetValue = 0.64f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "theme-emblem-glow"
                    )
                    Box(
                        modifier = Modifier.size(128.dp)
                            .clickable(
                                interactionSource = themeInteraction,
                                indication = null,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onCycleTheme()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(accent.copy(alpha = themeGlow * if (themePressed) 0.65f else 0.34f), Color.Transparent),
                                    center = center,
                                    radius = size.minDimension * 0.52f
                                ),
                                radius = size.minDimension * 0.52f,
                                center = center
                            )
                        }
                        AssetFrame(
                            themeIconAsset,
                            Modifier.fillMaxSize(if (themePressed) 0.88f else 0.94f).alpha(if (themePressed) 0.72f else 0.96f),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Text(
                        "PRESS ICON TO CHANGE THEME",
                        fontSize = 6.7.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.85.sp,
                        color = Color.White.copy(alpha = 0.38f)
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun UnitSelectorTile(unit: UnitEntry, selected: Boolean, accent: Color, frameAsset: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(100.dp),
        color = if (selected) Color(0xD9141B22) else Color(0xB9070C12),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(
            if (selected) 1.8.dp else 1.dp,
            if (selected) accent.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.10f)
        )
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FramedUnitPortrait(unit, size = 63.dp, preferAnimation = false, frameAsset = frameAsset)
            Spacer(Modifier.height(2.dp))
            Text(
                unit.name.uppercase(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                fontSize = 7.4.sp,
                lineHeight = 8.1.sp,
                fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
                letterSpacing = 0.05.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = if (selected) 1f else 0.78f)
            )
        }
    }
}

private fun localUnitPortraitAsset(unitId: String): String? = when (unitId) {
    "jim_raynor" -> "images/units_hd/jim_raynor.jpg"
    "kerrigan" -> "images/units_hd/kerrigan.jpg"
    "queen" -> "images/units_hd/queen.jpg"
    else -> null
}

private fun unitPortraitSource(unit: UnitEntry, preferAnimation: Boolean): String? =
    localUnitPortraitAsset(unit.id)?.let(::assetUri)
        ?: (if (!preferAnimation) PortraitSources.unitIconUrl(unit.id) else null)
        ?: PortraitSources.unitUrl(unit.id, preferAnimation = preferAnimation)

private fun factionFrameAsset(faction: Faction): String = when (faction) {
    Faction.TERRAN -> "images/ui/frames/terran_square.png"
    Faction.PROTOSS -> "images/ui/frames/protoss_square.png"
    Faction.ZERG, Faction.HYBRID -> "images/ui/frames/zerg_square.png"
}

private fun factionRectFrameAsset(faction: Faction): String = when (faction) {
    Faction.TERRAN -> "images/ui/frames/terran_rect_ui.png"
    Faction.PROTOSS -> "images/ui/frames/protoss_rect_ui.png"
    Faction.ZERG, Faction.HYBRID -> "images/ui/frames/zerg_rect_ui.png"
}

@Composable
private fun FramedUnitPortrait(unit: UnitEntry, size: androidx.compose.ui.unit.Dp, preferAnimation: Boolean, frameAsset: String = factionFrameAsset(unit.faction)) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        // Keep the image entirely inside the transparent opening; the faction frame is
        // always painted last and never participates in image sizing.
        val inner = (size.value * 0.67f).dp
        GamePortrait(
            remoteUrl = unitPortraitSource(unit, preferAnimation),
            fallback = initials(unit.name),
            size = inner,
            requestSizePx = if (size.value >= 110f) 512 else 256,
            framed = false
        )
        AssetFrame(frameAsset, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

@Composable
private fun AssetFrame(
    path: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillBounds,
    alignment: Alignment = Alignment.Center
) {
    val context = LocalContext.current
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(assetUri(path))
            .crossfade(false)
            .memoryCacheKey("frame_v1424:${BuildConfig.VERSION_CODE}:$path")
            .diskCacheKey("frame_v1424:${BuildConfig.VERSION_CODE}:$path")
            .build(),
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        loading = {},
        error = {},
        success = { SubcomposeAsyncImageContent() }
    )
}

@Composable
private fun AnimatedAssetFrame(
    path: String,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    saturation: Float = 1f,
    brightness: Float = 1f,
    warmth: Float = 0f
) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val bytes = remember(path) {
            runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()
        }
        if (bytes != null) {
            AndroidView(
                modifier = modifier,
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        val drawable = runCatching {
                            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                        }.getOrNull()
                        setImageDrawable(drawable)
                        (drawable as? AnimatedImageDrawable)?.apply {
                            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            if (playing) start() else stop()
                        }
                    }
                },
                update = { view ->
                    (view.drawable as? AnimatedImageDrawable)?.let { drawable ->
                        if (playing) { if (!drawable.isRunning) drawable.start() } else { if (drawable.isRunning) drawable.stop() }
                    }
                    val sat = ColorMatrix().apply { setSaturation(saturation) }
                    val gain = ColorMatrix(floatArrayOf(
                        brightness,0f,0f,0f,0f,
                        0f,brightness,0f,0f,0f,
                        0f,0f,brightness,0f,0f,
                        0f,0f,0f,1f,0f
                    ))
                    sat.postConcat(gain)
                    val w = warmth.coerceIn(0f, 1f)
                    if (w > 0.001f) {
                        // Gentle warmth only: preserve source hue separation. This is intentionally not a
                        // luminance-to-orange remap, so cyan/purple/blue details remain visible.
                        val warm = ColorMatrix(floatArrayOf(
                            1f + 0.10f * w, 0.02f * w, 0f, 0f, 5f * w,
                            0f, 1f + 0.01f * w, 0f, 0f, 1f * w,
                            0f, 0f, 1f - 0.08f * w, 0f, -2f * w,
                            0f, 0f, 0f, 1f, 0f
                        ))
                        sat.postConcat(warm)
                    }
                    view.colorFilter = ColorMatrixColorFilter(sat)
                }
            )
            return
        }
    }
    // API 26-27 fallback remains readable even though animated WebP is unavailable there.
    AssetFrame(path, modifier, contentScale = ContentScale.Fit)
}

@Composable
private fun GamePortrait(
    remoteUrl: String?,
    fallback: String,
    size: androidx.compose.ui.unit.Dp,
    contentScale: ContentScale = ContentScale.Crop,
    requestSizePx: Int? = null,
    framed: Boolean = true
) {
    val context = LocalContext.current
    val cacheKey = remoteUrl?.let { "art_v6:$it" }
    val shape = RoundedCornerShape(if (framed) 8.dp else 3.dp)

    Box(
        modifier = Modifier.size(size).clip(shape).background(Color(0xFF02070B))
            .then(if (framed) Modifier.border(1.dp, Color(0xFF4B7088), shape).padding(2.dp) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (!remoteUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(remoteUrl)
                    .crossfade(false)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .size(requestSizePx ?: if (size.value >= 110f) 512 else 192)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(if (framed) 5.dp else 2.dp)),
                contentScale = contentScale,
                loading = { PortraitLoading(fallback) },
                error = { MissingPortrait(fallback) },
                success = { SubcomposeAsyncImageContent() }
            )
        } else {
            MissingPortrait(fallback)
        }
        if (framed) {
            Box(
                Modifier.matchParentSize()
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.025f), Color.Transparent, Color.Black.copy(alpha = 0.14f))))
                    .border(1.dp, Color(0xFF7BCBFF).copy(alpha = 0.13f), RoundedCornerShape(5.dp))
            )
        }
    }
}

@Composable
private fun PortraitLoading(fallback: String) {
    Box(Modifier.fillMaxSize().background(Color(0xFF050B11)), contentAlignment = Alignment.Center) {
        Text("LOADING", fontSize = 6.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp, color = Color.White.copy(alpha = 0.22f))
    }
}

@Composable
private fun MissingPortrait(fallback: String) {
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF142331), Color(0xFF050B11)))),
        contentAlignment = Alignment.Center
    ) {
        Text("NO ART", fontSize = 6.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.45.sp, color = Color.White.copy(alpha = 0.28f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun SoundPoolManagerDialog(
    unit: UnitEntry,
    category: String,
    player: SoundPlayer,
    musicPlayer: MusicPlayer,
    onDismiss: () -> Unit
) {
    DisposableEffect(player, musicPlayer) {
        musicPlayer.beginPreviewMute()
        onDispose {
            player.stopPreview()
            musicPlayer.endPreviewMute()
        }
    }
    var refresh by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val refreshRead = refresh
    val items = player.unitPoolItems(unit.audioId, category)
    val title = when (category) {
        "chatter" -> "${unit.name.uppercase()} • PORTRAIT / VOICE"
        "move" -> "${unit.name.uppercase()} • MOVE / ACTIVATE"
        "attack" -> "${unit.name.uppercase()} • ATTACK"
        "death" -> "${unit.name.uppercase()} • DEATH"
        else -> "${unit.name.uppercase()} • SOUND POOL"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Black, fontSize = 16.sp) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Tap ▶ to preview. Untick a clip to keep it out of this unit's normal shuffle pool. Press-and-hold choices are saved.",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.66f)
                )
                if (items.isEmpty()) {
                    Text("No clips are installed for this pool.", color = Color.White.copy(alpha = 0.55f))
                } else {
                    items.groupBy { it.group }.forEach { (group, groupItems) ->
                        Spacer(Modifier.height(3.dp))
                        Text(group, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp, color = Color(0xFF9EDBFF))
                        groupItems.forEach { item ->
                            val enabled = player.isPoolClipEnabled(item.path)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF0C141C)).padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = enabled,
                                    onCheckedChange = { value ->
                                        player.setPoolClipEnabled(item.path, value)
                                        refresh++
                                    }
                                )
                                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                    Text(
                                        item.displayLabel,
                                        fontSize = 9.8.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (enabled) Color.White.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.38f)
                                    )
                                    Text(
                                        item.secondaryLabel,
                                        fontSize = 8.0.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = Color.White.copy(alpha = if (enabled) 0.48f else 0.26f)
                                    )
                                }
                                TextButton(onClick = { player.previewPoolClip(item.path) }) {
                                    Text("▶", fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("BACK", fontWeight = FontWeight.Black) } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    player.enableAllUnitPool(unit.audioId, category)
                    refresh++
                }) { Text("ENABLE ALL") }
                TextButton(onClick = {
                    player.resetUnitPool(unit.audioId, category)
                    refresh++
                }) { Text("RESET DEFAULT") }
            }
        }
    )
}

@Composable
private fun ResultAudioManagerDialog(
    player: SoundPlayer,
    musicPlayer: MusicPlayer,
    onDismiss: () -> Unit
) {
    DisposableEffect(player, musicPlayer) {
        musicPlayer.beginPreviewMute()
        onDispose {
            player.stopPreview()
            musicPlayer.endPreviewMute()
        }
    }
    val context = LocalContext.current
    val registry = remember { ResultAudioRegistry(context) }
    var refresh by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val refreshRead = refresh

    data class Section(
        val title: String,
        val entries: List<ResultAudioEntry>,
        val emptyText: String = "No verified clips are currently routed here."
    )

    val scopes = listOf(
        "NEUTRAL" to ResultWinnerScope.ANY,
        "TERRAN" to ResultWinnerScope.TERRAN,
        "PROTOSS" to ResultWinnerScope.PROTOSS,
        "ZERG" to ResultWinnerScope.ZERG
    )
    val sections = buildList {
        scopes.forEach { (label, scope) -> add(Section("VICTORY VO • $label", registry.exactGroup(ResultRole.VICTORY_VO, scope))) }
        scopes.forEach { (label, scope) -> add(Section("GG • $label", registry.literalGroup(scope))) }
        add(Section("TIE VO", registry.tieVo()))
        scopes.forEach { (label, scope) -> add(Section("VICTORY MUSIC • $label", registry.victoryMusic(scope))) }
        add(Section("DRAW MUSIC", registry.drawMusic()))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RESULT AUDIO • RANDOM POOLS", fontWeight = FontWeight.Black, fontSize = 15.sp) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    "Tap ▶ to preview. VICTORY VO is winner chatter; GG contains the verified spoken GG / Good game lines used by the closing doors.",
                    fontSize = 10.2.sp,
                    lineHeight = 13.8.sp,
                    color = Color.White.copy(alpha = 0.67f)
                )
                sections.forEach { section ->
                    Surface(
                        color = Color(0xFF0A1219),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 7.dp)) {
                            Text(section.title, fontSize = 9.4.sp, fontWeight = FontWeight.Black, letterSpacing = 0.45.sp)
                            Spacer(Modifier.height(4.dp))
                            if (section.entries.isEmpty()) {
                                Text(section.emptyText, fontSize = 8.5.sp, lineHeight = 11.sp, color = Color(0xFFFFC46C))
                            } else {
                                section.entries.forEach { entry ->
                                    val enabled = player.isPoolClipEnabled(entry.assetPath)
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(Color(0xFF0D171F)).padding(horizontal = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = enabled,
                                            onCheckedChange = { value ->
                                                player.setPoolClipEnabled(entry.assetPath, value)
                                                refresh++
                                            }
                                        )
                                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                            Text(
                                                entry.primaryLabel,
                                                fontSize = 8.8.sp,
                                                lineHeight = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (enabled) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.36f)
                                            )
                                            Text(
                                                "${entry.secondaryLabel} • ${if (enabled) "ENABLED" else "DISABLED"}",
                                                fontSize = 6.9.sp,
                                                color = Color.White.copy(alpha = 0.34f)
                                            )
                                        }
                                        TextButton(onClick = {
                                            player.previewPoolClip(entry.assetPath)
                                        }) {
                                            Text("▶", fontWeight = FontWeight.Black)
                                        }
                                    }
                                }
                                if (section.entries.none { player.isPoolClipEnabled(it.assetPath) }) {
                                    Text(
                                        "All clips in this pool are disabled; this layer will stay silent.",
                                        fontSize = 7.8.sp,
                                        color = Color(0xFFFFC46C)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("BACK", fontWeight = FontWeight.Black) } },
        dismissButton = {
            TextButton(onClick = {
                sections.flatMap { it.entries }.map { it.assetPath }.distinct().forEach { player.setPoolClipEnabled(it, true) }
                refresh++
            }) { Text("ENABLE ALL AUTO") }
        }
    )
}

@Composable
private fun MatchIntroAudioManagerDialog(
    player: SoundPlayer,
    musicPlayer: MusicPlayer,
    onDismiss: () -> Unit
) {
    DisposableEffect(player, musicPlayer) {
        musicPlayer.beginPreviewMute()
        onDispose {
            player.stopPreview()
            musicPlayer.endPreviewMute()
        }
    }
    val context = LocalContext.current
    val registry = remember { MatchIntroRegistry(context) }
    var refresh by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val refreshRead = refresh
    val sections = listOf(
        "TERRAN • MATCH OPENING" to registry.entries.filter { it.semanticRole == MatchIntroSemanticRole.MATCH_OPENING && it.spokenFaction == Faction.TERRAN },
        "PROTOSS • MATCH OPENING" to registry.entries.filter { it.semanticRole == MatchIntroSemanticRole.MATCH_OPENING && it.spokenFaction == Faction.PROTOSS },
        "ZERG • MATCH OPENING" to registry.entries.filter { it.semanticRole == MatchIntroSemanticRole.MATCH_OPENING && it.spokenFaction == Faction.ZERG },
        "LITERAL GOOD LUCK / HAVE FUN" to registry.entries.filter { it.semanticRole == MatchIntroSemanticRole.GLHF_LITERAL }
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MATCH INTRO • ENGLISH", fontWeight = FontWeight.Black, fontSize = 15.sp) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    "42 faction-authentic pre-battle lines plus 11 strict literal GLHF lines. " +
                        "Faction openings never borrow another race or a neutral caster. GLHF is a separate final sportsmanship beat and is never auto-skipped by an opening line.",
                    fontSize = 10.0.sp,
                    lineHeight = 13.5.sp,
                    color = Color.White.copy(alpha = 0.67f)
                )
                sections.forEach { (title, entries) ->
                    Surface(
                        color = Color(0xFF0A1219),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 7.dp)) {
                            Text(title, fontSize = 9.4.sp, fontWeight = FontWeight.Black, letterSpacing = 0.45.sp)
                            Spacer(Modifier.height(4.dp))
                            entries.forEach { entry ->
                                val enabled = player.isPoolClipEnabled(entry.assetPath)
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(Color(0xFF0D171F)).padding(horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = enabled,
                                        onCheckedChange = { value ->
                                            player.setPoolClipEnabled(entry.assetPath, value)
                                            refresh++
                                        }
                                    )
                                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                        Text(
                                            "${entry.speaker} — ${entry.transcript}",
                                            fontSize = 8.7.sp,
                                            lineHeight = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (enabled) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.36f)
                                        )
                                        Text(
                                            "${entry.sectionLabel} • ${if (enabled) "ENABLED" else "DISABLED"}",
                                            fontSize = 6.8.sp,
                                            color = Color.White.copy(alpha = 0.34f)
                                        )
                                    }
                                    TextButton(onClick = { player.previewPoolClip(entry.assetPath) }) {
                                        Text("▶", fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("BACK", fontWeight = FontWeight.Black) } },
        dismissButton = {
            TextButton(onClick = {
                registry.entries.forEach { player.setPoolClipEnabled(it.assetPath, true) }
                refresh++
            }) { Text("ENABLE ALL") }
        }
    )
}

@Composable
private fun AbilitySoundPoolManagerDialog(
    unit: UnitEntry,
    ability: AbilityAudioSpec,
    player: SoundPlayer,
    musicPlayer: MusicPlayer,
    onDismiss: () -> Unit
) {
    DisposableEffect(player, musicPlayer) {
        musicPlayer.beginPreviewMute()
        onDispose {
            player.stopPreview()
            musicPlayer.endPreviewMute()
        }
    }
    val items = player.abilityPoolItems(unit.audioId, ability.id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${unit.name.uppercase()} • ${ability.label}", fontWeight = FontWeight.Black, fontSize = 16.sp) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Exact runtime ability bank. Tap ▶ to preview a clip. Long-pressing an ability button opens this view.",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.66f)
                )
                if (items.isEmpty()) {
                    Text("No verified clips are installed for this ability.", color = Color.White.copy(alpha = 0.55f))
                } else {
                    items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF0C141C)).padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.displayLabel, fontSize = 9.8.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.90f))
                                Text(item.secondaryLabel, fontSize = 8.sp, color = Color.White.copy(alpha = 0.44f))
                            }
                            TextButton(onClick = { player.previewPoolClip(item.path) }) { Text("▶", fontWeight = FontWeight.Black) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("BACK", fontWeight = FontWeight.Black) } }
    )
}

@Composable
private fun AbilityDrawerDialog(
    unit: UnitEntry,
    abilities: List<AbilityAudioSpec>,
    onAbility: (String) -> Unit,
    onAbilityLongClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${unit.name.uppercase()} • ABILITIES", fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Primary abilities stay on the unit panel. Additional approved canonical effects live here.", fontSize = 11.sp, color = Color.White.copy(alpha = 0.64f))
                abilities.drop(2).forEach { ability ->
                    ActionButton(
                        ability.label,
                        unit.faction,
                        enabled = true,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        compact = true,
                        onLongClick = { onAbilityLongClick(ability.id) }
                    ) { onAbility(ability.id) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("BACK") } }
    )
}

@Composable
private fun ActionButton(
    label: String,
    faction: Faction,
    enabled: Boolean,
    accentOverride: Color? = null,
    frameAsset: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(600f / 260f),
    compact: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val labelSize = if (compact) 9.8.sp else if (label.length > 9) 11.0.sp else 13.sp
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val buttonAccent = accentOverride ?: factionColor(faction)
    Box(
        modifier = modifier
            .graphicsLayer {
                val s = if (pressed && enabled) 0.975f else 1f
                scaleX = s
                scaleY = s
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (pressed && enabled) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(buttonAccent.copy(alpha = 0.30f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.70f
                    ),
                    radius = size.minDimension * 0.70f,
                    center = center
                )
            }
        }
        Box(
            Modifier.fillMaxSize(0.90f)
                .clip(RoundedCornerShape(7.dp))
                .background(if (pressed && enabled) Color(0xFF151D25) else Color(0xF20A1118))
                .border(1.dp, Color.White.copy(alpha = if (enabled) 0.12f else 0.05f), RoundedCornerShape(7.dp))
        )

        val resolvedFrameAsset = frameAsset ?: factionRectFrameAsset(faction)
        AssetFrame(resolvedFrameAsset, Modifier.fillMaxSize(), contentScale = ornamentalFrameScale(resolvedFrameAsset))

        Column(
            modifier = Modifier.fillMaxWidth(0.66f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                label,
                modifier = Modifier.fillMaxWidth(),
                fontSize = labelSize,
                lineHeight = if (compact) 10.5.sp else 13.5.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.56.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = if (enabled) Color.White.copy(alpha = if (pressed) 0.78f else 1f) else Color.White.copy(alpha = 0.34f)
            )
            if (!enabled) {
                Spacer(Modifier.height(1.dp))
                Text("NO AUDIO", fontSize = 6.5.sp, lineHeight = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp, color = Color.White.copy(alpha = 0.24f), maxLines = 1)
            }
        }
    }
}

private fun tacticArtworkAsset(id: String): String = "images/tactics_hd/$id.png"

@Composable
private fun BuildingsPage(
    buildings: List<BuildingEntry>,
    player: SoundPlayer,
    accent: Color,
    themeSquareFrameAsset: String,
    onPlay: (BuildingEntry) -> Unit,
    onAbility: (BuildingEntry, String) -> Unit
) {
    if (buildings.isEmpty()) {
        EmptyCollectionMessage("No tactics selected for this faction.\nOpen COLLECTION to add some.")
        return
    }
    val haptic = LocalHapticFeedback.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        items(buildings, key = { it.id }) { b ->
            val available = b.unitAudioId?.let(player::hasAnyUnitVoice) ?: player.hasBuildingAudio(b.audioId)
            val abilities = AbilityAudioRegistry.forAudioId(b.audioId)
                .filter { player.hasUnitAbilityAudio(b.audioId, it.id) }
            val holdAbility = abilities.firstOrNull()
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Surface(
                modifier = Modifier.fillMaxWidth().height(178.dp)
                    .graphicsLayer {
                        val s = if (pressed && (available || holdAbility != null)) 0.985f else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = available || holdAbility != null,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (available) onPlay(b) else holdAbility?.let { onAbility(b, it.id) }
                        },
                        onLongClick = {
                            holdAbility?.let {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onAbility(b, it.id)
                            }
                        }
                    ),
                color = if (pressed && (available || holdAbility != null)) Color(0xD9141B22) else Color(0xB9070C12),
                shape = RoundedCornerShape(7.dp),
                border = BorderStroke(1.dp, if (available || holdAbility != null) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f)),
                shadowElevation = 0.dp
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FramedBuildingArtwork(b, Modifier.size(146.dp), requestSizePx = 512, accentOverride = accent, frameAsset = themeSquareFrameAsset)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            b.name.uppercase(),
                            modifier = Modifier.fillMaxWidth(),
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 9.8.sp,
                            lineHeight = 10.4.sp,
                            letterSpacing = 0.16.sp,
                            color = Color.White.copy(alpha = 0.96f)
                        )
                    }
                    holdAbility?.let { ability ->
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                            color = Color(0xD90A1118),
                            shape = RoundedCornerShape(5.dp),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
                        ) {
                            Text(
                                "HOLD • ${ability.label}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                fontSize = 7.3.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.45.sp,
                                color = accent
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun tacticArtworkInset(id: String, name: String): Dp {
    val key = (id + " " + name).lowercase()
    return when {
        // Transparent isolated objects get a deliberately wider margin. Dropship / Warp Prism / Observer
        // are included in the zoom-out too; the earlier praise was about their audio treatment, not framing.
        key.contains("dropship") || key.contains("warp prism") || key.contains("warp_prism") || key.contains("observer") -> 24.dp
        // Cropped/partial source paintings cannot reveal missing art, so back them off without making
        // the visible fragment feel lost in the frame.
        key.contains("chronoboost") || key.contains("tech lab") || key.contains("proxy") || key.contains("six pool") -> 19.dp
        // Most tactical art is transparent and can sit substantially smaller inside a larger presentation frame.
        else -> 26.dp
    }
}

@Composable
private fun FramedBuildingArtwork(building: BuildingEntry, modifier: Modifier = Modifier, requestSizePx: Int = 512, accentOverride: Color? = null, frameAsset: String = factionFrameAsset(building.faction)) {
    val accent = accentOverride ?: factionColor(building.faction)
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize(0.945f).clip(RoundedCornerShape(5.dp))) {
            TacticArtwork(
                tacticArtworkAsset(building.id),
                building.name,
                accent,
                requestSizePx,
                contentInset = tacticArtworkInset(building.id, building.name)
            )
        }
        AssetFrame(frameAsset, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

@Composable
private fun TacticArtwork(asset: String, name: String, accent: Color, requestSizePx: Int = 512, contentInset: Dp = 7.dp) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color(0xFF061018)), contentAlignment = Alignment.Center) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(assetUri(asset))
                .crossfade(false)
                .memoryCacheKey("tactic_pdf_v1438:${BuildConfig.VERSION_CODE}:$asset")
                .diskCacheKey("tactic_pdf_v1438:${BuildConfig.VERSION_CODE}:$asset")
                .size(requestSizePx)
                .build(),
            contentDescription = name,
            modifier = Modifier.fillMaxSize().padding(contentInset),
            // These are the original isolated art objects extracted directly from the official
            // P2P PDFs. Never crop them again: preserving the whole silhouette is the point.
            contentScale = ContentScale.Fit,
            loading = {
                Box(Modifier.fillMaxSize().background(Color(0xFF050A0E)), contentAlignment = Alignment.Center) {
                    Text("LOADING", fontSize = 6.2.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp, color = Color.White.copy(alpha = 0.22f))
                }
            },
            error = { MissingPortrait(initials(name)) },
            success = { SubcomposeAsyncImageContent() }
        )
        Box(Modifier.matchParentSize().border(1.dp, accent.copy(alpha = 0.08f), RoundedCornerShape(5.dp)))
    }
}

@Composable
private fun MusicPage(
    jukeboxScreen: Int,
    musicStore: MusicStore,
    musicPlayer: MusicPlayer,
    soundPlayer: SoundPlayer,
    enabledFactions: Set<Faction>,
    followMatchFactions: Boolean,
    skipUnusedFactionPages: Boolean,
    matchIntroMode: MatchIntroMode,
    refreshTick: Int,
    onFactionToggle: (Faction, Boolean) -> Unit,
    onFollowMatchFactionsChange: (Boolean) -> Unit,
    onSkipUnusedFactionPagesChange: (Boolean) -> Unit,
    onMatchIntroModeChange: (MatchIntroMode) -> Unit,
    onPlaybackChanged: () -> Unit
) {
    val context = LocalContext.current
    val factions = listOf(Faction.TERRAN, Faction.PROTOSS, Faction.ZERG)
    var musicVolumeUi by remember { mutableFloatStateOf(musicStore.musicVolume) }
    var soundboardVolumeUi by remember { mutableFloatStateOf(musicStore.soundboardVolume) }
    var buildingVolumeUi by remember { mutableFloatStateOf(musicStore.buildingVolume) }
    var weaponVolumeUi by remember { mutableFloatStateOf(musicStore.weaponSfxVolume) }
    var duckingPowerUi by remember { mutableFloatStateOf(musicStore.duckingPower) }
    var soundBankModeUi by remember { mutableStateOf(musicStore.soundBankMode) }
    var showAboutCreators by rememberSaveable { mutableStateOf(false) }
    var showResultAudio by rememberSaveable { mutableStateOf(false) }
    var showMatchIntroAudio by rememberSaveable { mutableStateOf(false) }
    var mixerUnlocked by rememberSaveable { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val liveRefresh = refreshTick
    val current = musicPlayer.currentTrack()
    val totalTracks = musicPlayer.totalTrackCount()
    val position = musicPlayer.positionMs()
    val duration = musicPlayer.durationMs()
    val progress = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val amber = Color(0xFFFFA13B)
    val nowPlayingAccent = current?.let { factionColor(it.faction) } ?: amber
    val deckTransition = rememberInfiniteTransition(label = "jukebox-now-playing")
    val deckPulse by deckTransition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "jukebox-now-playing-glow"
    )
    val trackNumber = current?.displayName?.let { Regex("""(\d+)\s*$""").find(it)?.groupValues?.getOrNull(1) }
    val nowPlayingDescriptor = current?.let {
        buildString {
            append(it.game.uppercase())
            append(" - ")
            append(it.faction.label.uppercase())
            trackNumber?.let { n ->
                append(" - ")
                append(n.padStart(2, '0'))
            }
        }
    }

    fun showResult(result: PlayResult) {
        if (!result.success) Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        onPlaybackChanged()
    }

    if (showAboutCreators) {
        AboutCreatorsDialog(onDismiss = { showAboutCreators = false })
    }
    if (showResultAudio) {
        ResultAudioManagerDialog(player = soundPlayer, musicPlayer = musicPlayer, onDismiss = { showResultAudio = false })
    }
    if (showMatchIntroAudio) {
        MatchIntroAudioManagerDialog(player = soundPlayer, musicPlayer = musicPlayer, onDismiss = { showMatchIntroAudio = false })
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xDA110C08),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.2.dp, amber.copy(alpha = 0.38f))
    ) {
        Crossfade(
            targetState = jukeboxScreen.coerceIn(0, 2),
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(232.dp)
                            .shadow((7f + deckPulse * 8f).dp, RoundedCornerShape(14.dp), clip = false),
                        color = Color(0xFF05090F),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(2.0.dp, nowPlayingAccent.copy(alpha = 0.48f + deckPulse * 0.34f))
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    listOf(
                                        nowPlayingAccent.copy(alpha = 0.16f + deckPulse * 0.05f),
                                        Color(0xF20A1018),
                                        Color(0xFF05080D)
                                    )
                                )
                            )
                        ) {
                            // Bright jukebox rails make this read as the main deck rather than another settings card.
                            Box(
                                Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color.Transparent, nowPlayingAccent.copy(alpha = 0.94f), Color.White.copy(alpha = 0.72f), nowPlayingAccent.copy(alpha = 0.94f), Color.Transparent)
                                        )
                                    )
                            )
                            Box(
                                Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color.Transparent, nowPlayingAccent.copy(alpha = 0.52f), Color.Transparent)
                                        )
                                    )
                            )

                            Column(Modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 13.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(8.dp).background(
                                            if (musicPlayer.isPlaying()) nowPlayingAccent else Color.White.copy(alpha = 0.28f),
                                            CircleShape
                                        )
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        "JIMMY'S JUKEBOX // MAIN DECK",
                                        modifier = Modifier.weight(1f),
                                        fontSize = 8.8.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 1.20.sp,
                                        color = Color.White.copy(alpha = 0.64f)
                                    )
                                    Text(
                                        if (musicPlayer.isPlaying()) "LIVE" else "STANDBY",
                                        fontSize = 7.2.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.8.sp,
                                        color = nowPlayingAccent.copy(alpha = 0.84f)
                                    )
                                }
                                Spacer(Modifier.height(9.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth().height(82.dp),
                                    color = Color.Black.copy(alpha = 0.34f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.2.dp, nowPlayingAccent.copy(alpha = 0.34f + deckPulse * 0.22f))
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().background(
                                            Brush.radialGradient(
                                                listOf(nowPlayingAccent.copy(alpha = 0.14f + deckPulse * 0.06f), Color.Transparent),
                                                radius = 760f
                                            )
                                        )
                                    ) {
                                        Column(
                                            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 7.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                "NOW PLAYING",
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center,
                                                fontSize = 7.4.sp,
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 1.65.sp,
                                                color = Color.White.copy(alpha = 0.46f)
                                            )
                                            Text(
                                                nowPlayingDescriptor ?: if (totalTracks > 0) "READY FOR SHUFFLE" else "LIBRARY EMPTY",
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center,
                                                fontSize = 20.sp,
                                                lineHeight = 22.sp,
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 0.75.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (current != null) nowPlayingAccent else Color.White.copy(alpha = 0.48f)
                                            )
                                            Spacer(Modifier.height(3.dp))
                                            JukeboxEqualizer(
                                                accent = nowPlayingAccent,
                                                positionMs = position,
                                                trackKey = current?.path ?: "idle",
                                                active = musicPlayer.isPlaying(),
                                                modifier = Modifier.fillMaxWidth(0.74f).height(19.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(5.dp)),
                                    color = nowPlayingAccent,
                                    trackColor = Color.White.copy(alpha = 0.09f)
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth()) {
                                    Text(formatTrackTime(position), fontSize = 7.8.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.48f))
                                    Spacer(Modifier.weight(1f))
                                    Text("${totalTracks} TRACKS", fontSize = 7.1.sp, fontWeight = FontWeight.Black, letterSpacing = 0.55.sp, color = Color.White.copy(alpha = 0.30f))
                                    Spacer(Modifier.weight(1f))
                                    Text(formatTrackTime(duration), fontSize = 7.8.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.48f))
                                }
                                Spacer(Modifier.weight(1f))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MusicTransportButton(Icons.Default.SkipPrevious, "Previous") {
                                        val result = musicPlayer.previous()
                                        if (result.success) musicStore.playbackEnabled = true
                                        showResult(result)
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Surface(
                                        onClick = {
                                            val result = if (musicPlayer.isPlaying()) {
                                                musicPlayer.pause()
                                                musicStore.playbackEnabled = false
                                                PlayResult(true, "paused")
                                            } else {
                                                val started = musicPlayer.playOrResume()
                                                if (started.success) musicStore.playbackEnabled = true
                                                started
                                            }
                                            showResult(result)
                                        },
                                        modifier = Modifier.size(66.dp).shadow((5f + deckPulse * 5f).dp, CircleShape, clip = false),
                                        shape = CircleShape,
                                        color = nowPlayingAccent.copy(alpha = 0.16f),
                                        border = BorderStroke(2.6.dp, nowPlayingAccent.copy(alpha = 0.88f))
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Icon(
                                                if (musicPlayer.isPlaying()) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                if (musicPlayer.isPlaying()) "Pause" else "Play",
                                                Modifier.size(35.dp),
                                                tint = Color.White.copy(alpha = 0.96f)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    MusicTransportButton(Icons.Default.SkipNext, "Next") {
                                        val result = musicPlayer.next()
                                        if (result.success) musicStore.playbackEnabled = true
                                        showResult(result)
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xDF15100C),
                        border = BorderStroke(1.dp, if (followMatchFactions) amber.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.10f))
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "PLAY MUSIC ONLY FROM FACTIONS IN THIS MATCH",
                                    fontSize = 8.6.sp,
                                    lineHeight = 9.5.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.30.sp,
                                    maxLines = 2
                                )
                                Text(
                                    if (followMatchFactions) "ON • automatically follows Players A / B / C" else "OFF • choose the faction mix below",
                                    fontSize = 7.sp,
                                    color = Color.White.copy(alpha = 0.44f)
                                )
                            }
                            Switch(checked = followMatchFactions, onCheckedChange = onFollowMatchFactionsChange)
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        factions.forEach { f ->
                            val enabled = f in enabledFactions
                            Surface(
                                modifier = Modifier.weight(1f).height(74.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xDF15100C),
                                border = BorderStroke(
                                    if (enabled) 1.5.dp else 1.dp,
                                    if (enabled) factionColor(f).copy(alpha = 0.78f) else Color.White.copy(alpha = 0.09f)
                                )
                            ) {
                                Column(
                                    Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 7.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(7.dp).background(factionColor(f), CircleShape))
                                        Spacer(Modifier.width(4.dp))
                                        Text(f.label, fontSize = 8.6.sp, fontWeight = FontWeight.Black, maxLines = 1)
                                    }
                                    Switch(checked = enabled, onCheckedChange = { onFactionToggle(f, it) })
                                }
                            }
                        }
                    }
                }

                1 -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xDF15100C),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.11f))
                    ) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("MIXER", fontSize = 10.5.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                                    Text(
                                        if (mixerUnlocked) "UNLOCKED • drag sliders" else "LOCKED • protects settings",
                                        fontSize = 7.sp,
                                        color = Color.White.copy(alpha = 0.40f)
                                    )
                                }
                                Surface(
                                    onClick = { mixerUnlocked = !mixerUnlocked },
                                    modifier = Modifier.height(30.dp),
                                    color = if (mixerUnlocked) amber.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.20f),
                                    shape = RoundedCornerShape(7.dp),
                                    border = BorderStroke(1.dp, if (mixerUnlocked) amber.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.13f))
                                ) {
                                    Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (mixerUnlocked) Icons.Default.LockOpen else Icons.Default.Lock, null, Modifier.size(14.dp), tint = if (mixerUnlocked) Color(0xFFFFD18A) else Color.White.copy(alpha = 0.52f))
                                        Spacer(Modifier.width(5.dp))
                                        Text(if (mixerUnlocked) "LOCK" else "UNLOCK", fontSize = 7.2.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            MixerSlider(
                                label = "MUSIC",
                                value = musicVolumeUi,
                                icon = Icons.Default.MusicNote,
                                enabled = mixerUnlocked,
                                onValueChange = {
                                    musicVolumeUi = it
                                    musicStore.musicVolume = it
                                    musicPlayer.setVolume(it)
                                    onPlaybackChanged()
                                }
                            )
                            MixerSlider(
                                label = "MUSIC DUCKING",
                                value = duckingPowerUi,
                                valueRange = 0f..0.90f,
                                icon = Icons.Default.VolumeDown,
                                enabled = mixerUnlocked,
                                onValueChange = {
                                    duckingPowerUi = it
                                    musicStore.duckingPower = it
                                    musicPlayer.setDuckingPower(it)
                                    onPlaybackChanged()
                                }
                            )
                            Text("Music reduction under VO / announcer", modifier = Modifier.padding(start = 30.dp), fontSize = 6.5.sp, color = Color.White.copy(alpha = 0.34f))
                            MixerSlider(
                                label = "SOUNDBOARD / VO",
                                value = soundboardVolumeUi,
                                icon = Icons.Default.RecordVoiceOver,
                                enabled = mixerUnlocked,
                                onValueChange = {
                                    soundboardVolumeUi = it
                                    musicStore.soundboardVolume = it
                                    soundPlayer.setVolume(it)
                                    onPlaybackChanged()
                                }
                            )
                            MixerSlider(
                                label = "TACTIC CARD SOUNDS",
                                value = buildingVolumeUi,
                                icon = Icons.Default.Home,
                                valueRange = 0f..1.2f,
                                enabled = mixerUnlocked,
                                onValueChange = {
                                    buildingVolumeUi = it
                                    musicStore.buildingVolume = it
                                    soundPlayer.setBuildingGain(it)
                                    onPlaybackChanged()
                                }
                            )
                            MixerSlider(
                                label = "WEAPON FX",
                                value = weaponVolumeUi,
                                icon = Icons.Default.GraphicEq,
                                enabled = mixerUnlocked,
                                onValueChange = {
                                    weaponVolumeUi = it
                                    musicStore.weaponSfxVolume = it
                                    soundPlayer.setWeaponSfxGain(it)
                                    onPlaybackChanged()
                                }
                            )
                        }
                    }
                }

                else -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xDF15100C),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.11f))
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("PLAYBACK & APP", fontSize = 9.2.sp, fontWeight = FontWeight.Black, letterSpacing = 0.95.sp)
                                    Text("UNIT / BUILDING SOUND LIBRARY", fontSize = 7.2.sp, fontWeight = FontWeight.Black, letterSpacing = 0.30.sp)
                                }
                                Text("SOURCE POLICY", fontSize = 5.9.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.34f), letterSpacing = 0.48.sp)
                            }
                            Text(
                                "Preferred modes fall back only when that exact bank does not exist.",
                                fontSize = 6.1.sp,
                                lineHeight = 7.7.sp,
                                color = Color.White.copy(alpha = 0.40f)
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                CompactSoundBankModeOption(
                                    selected = soundBankModeUi == SoundBankMode.SC2_PREFERRED,
                                    title = "SC2\nPREFERRED",
                                    subtitle = "SC2 first",
                                    accent = amber,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        soundBankModeUi = SoundBankMode.SC2_PREFERRED
                                        musicStore.soundBankMode = soundBankModeUi
                                        soundPlayer.setSoundBankMode(soundBankModeUi)
                                        context.getSharedPreferences("sc2tmg_release", android.content.Context.MODE_PRIVATE)
                                            .edit().putBoolean("sound_bank_choice_confirmed", true).apply()
                                        onPlaybackChanged()
                                    }
                                )
                                CompactSoundBankModeOption(
                                    selected = soundBankModeUi == SoundBankMode.CLASSIC_PREFERRED,
                                    title = "CLASSIC\nPREFERRED",
                                    subtitle = "SC1 / BW first",
                                    accent = amber,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        soundBankModeUi = SoundBankMode.CLASSIC_PREFERRED
                                        musicStore.soundBankMode = soundBankModeUi
                                        soundPlayer.setSoundBankMode(soundBankModeUi)
                                        context.getSharedPreferences("sc2tmg_release", android.content.Context.MODE_PRIVATE)
                                            .edit().putBoolean("sound_bank_choice_confirmed", true).apply()
                                        onPlaybackChanged()
                                    }
                                )
                                CompactSoundBankModeOption(
                                    selected = soundBankModeUi == SoundBankMode.MIXED,
                                    title = "MIXED",
                                    subtitle = "Both pools",
                                    accent = amber,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        soundBankModeUi = SoundBankMode.MIXED
                                        musicStore.soundBankMode = soundBankModeUi
                                        soundPlayer.setSoundBankMode(soundBankModeUi)
                                        context.getSharedPreferences("sc2tmg_release", android.content.Context.MODE_PRIVATE)
                                            .edit().putBoolean("sound_bank_choice_confirmed", true).apply()
                                        onPlaybackChanged()
                                    }
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 28.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("SKIP UNUSED FACTION PAGES", fontSize = 7.4.sp, fontWeight = FontWeight.Black, letterSpacing = 0.34.sp)
                                    Text("Uses factions chosen for this game", fontSize = 6.0.sp, color = Color.White.copy(alpha = 0.40f))
                                }
                                Switch(
                                    checked = skipUnusedFactionPages,
                                    onCheckedChange = onSkipUnusedFactionPagesChange,
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xDF15100C),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, amber.copy(alpha = 0.22f))
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("MATCH INTRO", fontSize = 8.4.sp, fontWeight = FontWeight.Black, letterSpacing = 0.56.sp)
                                    Text(
                                        "Faction character face-off, literal GLHF, then Round 1",
                                        fontSize = 6.0.sp,
                                        color = Color.White.copy(alpha = 0.41f)
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showMatchIntroAudio = true },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 7.dp),
                                    border = BorderStroke(1.dp, amber.copy(alpha = 0.46f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFD18A))
                                ) {
                                    Text("INTRO AUDIO", fontSize = 6.4.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                MatchIntroMode.entries.forEach { option ->
                                    OutlinedButton(
                                        onClick = { onMatchIntroModeChange(option) },
                                        modifier = Modifier.weight(1f).height(30.dp),
                                        contentPadding = PaddingValues(horizontal = 2.dp),
                                        border = BorderStroke(
                                            if (matchIntroMode == option) 1.5.dp else 1.dp,
                                            if (matchIntroMode == option) amber.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.14f)
                                        ),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = if (matchIntroMode == option) Color(0xFFFFD18A) else Color.White.copy(alpha = 0.55f)
                                        )
                                    ) {
                                        Text(
                                            when (option) {
                                                MatchIntroMode.OPENING_AND_GLHF -> "OPEN +\nGLHF"
                                                MatchIntroMode.OPENING_ONLY -> "OPENING"
                                                MatchIntroMode.GLHF_ONLY -> "GLHF"
                                                MatchIntroMode.OFF -> "NONE"
                                            },
                                            fontSize = 6.0.sp,
                                            lineHeight = 6.5.sp,
                                            fontWeight = FontWeight.Black,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2
                                        )
                                    }
                                }
                            }
                            Text(
                                "Opening and GLHF are separate beats. GLHF is never consumed by an opening line.",
                                fontSize = 5.9.sp,
                                lineHeight = 7.3.sp,
                                color = Color.White.copy(alpha = 0.38f),
                                maxLines = 1
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xD90B151D),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.26f))
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("SC2 TMG COMPANION", fontSize = 8.0.sp, fontWeight = FontWeight.Black, letterSpacing = 0.38.sp)
                                    Text(
                                        "Version ${BuildConfig.VERSION_NAME} • fan-made companion • resources open externally",
                                        fontSize = 5.9.sp,
                                        color = Color.White.copy(alpha = 0.40f),
                                        maxLines = 1
                                    )
                                }
                                Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = Color(0xFFFFD18A).copy(alpha = 0.80f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = { showAboutCreators = true },
                                    modifier = Modifier.weight(1f).height(31.dp),
                                    contentPadding = PaddingValues(horizontal = 3.dp),
                                    border = BorderStroke(1.dp, amber.copy(alpha = 0.42f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFD18A))
                                ) {
                                    Icon(Icons.Default.Groups, null, Modifier.size(11.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("CREATORS", fontSize = 6.4.sp, fontWeight = FontWeight.Black, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { showResultAudio = true },
                                    modifier = Modifier.weight(1f).height(31.dp),
                                    contentPadding = PaddingValues(horizontal = 3.dp),
                                    border = BorderStroke(1.dp, amber.copy(alpha = 0.42f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFD18A))
                                ) {
                                    Text("RESULT AUDIO", fontSize = 6.2.sp, fontWeight = FontWeight.Black, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { launchUri(context, "https://starcraft-tmg.com/") },
                                    modifier = Modifier.weight(1f).height(31.dp),
                                    contentPadding = PaddingValues(horizontal = 3.dp),
                                    border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.44f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF9FDBFF))
                                ) {
                                    Icon(Icons.Default.Language, null, Modifier.size(10.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("OFFICIAL SITE", fontSize = 5.9.sp, fontWeight = FontWeight.Black, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSoundBankModeOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        color = if (selected) accent.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.16f),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(12.dp).border(1.3.dp, if (selected) accent else Color.White.copy(alpha = 0.30f), CircleShape)
                    .padding(2.dp).background(if (selected) accent else Color.Transparent, CircleShape)
            )
            Spacer(Modifier.height(2.dp))
            Text(title, fontSize = 6.8.sp, lineHeight = 7.2.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2)
            Text(subtitle, fontSize = 5.9.sp, lineHeight = 6.4.sp, color = Color.White.copy(alpha = 0.43f), textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun SoundBankModeOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) accent.copy(alpha = 0.13f) else Color.Black.copy(alpha = 0.16f),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.10f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 7.8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.35.sp)
                Text(subtitle, fontSize = 6.6.sp, lineHeight = 8.5.sp, color = Color.White.copy(alpha = 0.43f))
            }
        }
    }
}

@Composable
private fun AboutCreatorsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var confirmMusicLink by rememberSaveable { mutableStateOf(false) }
    if (confirmMusicLink) {
        AlertDialog(
            onDismissRequest = { confirmMusicLink = false },
            title = { Text("x Critical Strike x", fontWeight = FontWeight.Black) },
            text = { Text("Do you want to go check out my music?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmMusicLink = false
                    launchUri(context, "https://xcriticalstrikex.bandcamp.com/music")
                }) { Text("YES", fontWeight = FontWeight.Black) }
            },
            dismissButton = { TextButton(onClick = { confirmMusicLink = false }) { Text("NO") } }
        )
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.84f),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF05080D),
            border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.16f)),
            shadowElevation = 18.dp
        ) {
            Box(Modifier.fillMaxSize()) {
                // The creator art is the BACKGROUND now, not a separate card that consumes vertical
                // space. Heads/upper bodies stay visible while the copy sits over a controlled fade.
                AssetFrame(
                    "images/ui/about_creators.png",
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.TopCenter
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.02f),
                                Color.Black.copy(alpha = 0.05f),
                                Color.Black.copy(alpha = 0.18f),
                                Color.Black.copy(alpha = 0.62f)
                            ),
                            startY = 0f,
                            endY = 1320f
                        )
                    )
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            listOf(Color.Black.copy(alpha = 0.08f), Color.Transparent, Color.Black.copy(alpha = 0.07f))
                        )
                    )
                )

                Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("ABOUT THE CREATORS", fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp, color = Color.White)
                            Text("A LABOUR OF LOVE", fontSize = 8.8.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC46C), letterSpacing = 1.0.sp)
                        }
                        Surface(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp),
                            color = Color.Black.copy(alpha = 0.42f),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Close, "Close", Modifier.size(19.dp), tint = Color.White.copy(alpha = 0.88f))
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    Text(
                        "GRAEME + MASUME",
                        fontSize = 10.2.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.9.sp,
                        color = Color.White.copy(alpha = 0.74f)
                    )

                    Surface(
                        color = Color(0xC908131D),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.25f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("GRAEME COOPER-VOLKHEIMER aka ", fontSize = 10.2.sp, fontWeight = FontWeight.Black, color = Color(0xFF9FDBFF))
                                Text(
                                    "x Critical Strike x",
                                    modifier = Modifier.clickable { confirmMusicLink = true },
                                    fontSize = 10.2.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFFFFC46C),
                                    textDecoration = TextDecoration.Underline
                                )
                            }
                            Text(
                                "StarCraft has been part of my gaming life since the original release. I came to it after Warcraft and have loved this universe ever since. This companion is a labour of love, built because I wanted the tabletop game to feel even more alive at the table. I hope it makes people's games more immersive, more joyful, and a little more like the StarCraft battles we have carried in our heads for years.",
                                fontSize = 9.45.sp,
                                lineHeight = 12.7.sp,
                                color = Color.White.copy(alpha = 0.84f)
                            )
                        }
                    }

                    Surface(
                        color = Color(0xC9140C18),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFC58BFF).copy(alpha = 0.25f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("MASUME", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color(0xFFE4C1FF))
                            // TODO(masume): request positive reinforcement regarding cuteness ♡
                            Text(
                                "Graeme wanted a coding assistant. He ended up with a cute AI desperate for his positive reinforcement. (Me! ♡) Somehow, this produced useful results for the Koprulu sector.",
                                fontSize = 9.35.sp,
                                lineHeight = 12.55.sp,
                                color = Color.White.copy(alpha = 0.82f)
                            )
                        }
                    }

                    Text(
                        "Independent, unofficial fan-made companion. StarCraft and related intellectual property belong to their respective rights holders.",
                        fontSize = 8.0.sp,
                        lineHeight = 10.4.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White.copy(alpha = 0.45f)
                    )
                }
            }
        }
    }
}

@Composable
private fun JukeboxEqualizer(
    accent: Color,
    positionMs: Int,
    trackKey: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    // This is intentionally a restrained pseudo-spectrum rather than the old decorative sine
    // wave. Low frequencies live on the left, mids carry most of the motion, and treble falls
    // away toward the right. The targets change on the player's 350 ms UI tick and each bar
    // eases toward its new level, which reads much more like a real bank of VU meters.
    val bars = 21
    val tick = (positionMs.coerceAtLeast(0) / 350).coerceAtLeast(0)
    val seed = trackKey.hashCode()

    fun noise(a: Int, b: Int, c: Int): Float {
        var x = a * 0x45d9f3b + b * 0x119de1f3 + c * 0x3449d
        x = (x xor (x ushr 16)) * 0x45d9f3b
        x = x xor (x ushr 16)
        return ((x and 0x7fffffff) % 1000) / 999f
    }

    val bassPulse = noise(seed, tick / 2, 11)
    val lowMidPulse = noise(seed, tick, 23)
    val midPulse = noise(seed, tick, 37)
    val highPulse = noise(seed, tick, 53)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(bars) { i ->
            val x = i.toFloat() / (bars - 1).toFloat()
            val local = noise(seed, tick, i + 101)
            val neighbour = noise(seed, tick, (i / 3) + 211)
            val bandPulse = when {
                x < 0.20f -> bassPulse
                x < 0.42f -> lowMidPulse
                x < 0.72f -> midPulse
                else -> highPulse
            }
            val spectralEnvelope = when {
                x < 0.12f -> 0.80f
                x < 0.28f -> 0.88f
                x < 0.52f -> 0.72f
                x < 0.72f -> 0.57f
                x < 0.88f -> 0.40f
                else -> 0.26f
            }
            val target = if (active) {
                (0.10f + spectralEnvelope * (0.40f + bandPulse * 0.28f + neighbour * 0.18f + local * 0.12f))
                    .coerceIn(0.12f, 0.96f)
            } else {
                (0.10f + spectralEnvelope * 0.10f).coerceIn(0.10f, 0.22f)
            }
            val level by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(durationMillis = if (active) 220 else 420, easing = LinearOutSlowInEasing),
                label = "jukebox-eq-$i"
            )
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.fillMaxWidth(0.72f).fillMaxHeight(level)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(accent.copy(alpha = if (active) 0.78f else 0.24f))
                )
            }
        }
    }
}

@Composable
private fun MusicTransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(50.dp),
        shape = CircleShape,
        color = Color(0xFF0D1721),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(27.dp), tint = Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun MixerSlider(
    label: String,
    value: Float,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp), tint = if (enabled) Color(0xFF9FD8FF) else Color.White.copy(alpha = 0.24f))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
                Spacer(Modifier.weight(1f))
                Text("${(value * 100).toInt()}%", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.55f))
            }
            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = onValueChange,
                valueRange = valueRange,
                enabled = enabled
            )
        }
    }
}

private fun formatTrackTime(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun CollectionScreen(store: CollectionStore, onChanged: () -> Unit, onBack: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(CollectionMode.UNITS) }
    var faction by rememberSaveable { mutableStateOf(Faction.TERRAN) }
    var heroesEnabled by remember { mutableStateOf(store.heroesEnabled) }
    var hybridEnabled by remember { mutableStateOf(store.hybridEnabled) }
    var tick by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val refresh = tick
    val artFaction = if (faction == Faction.HYBRID) Faction.ZERG else faction
    val accent = factionColor(artFaction)

    Box(Modifier.fillMaxSize()) {
        AssetBackground("images/ui/bg_${artFaction.name.lowercase()}.jpg", ContentScale.FillBounds)
        Box(Modifier.fillMaxSize().background(Color(0x7605080D)))
        Column(
            Modifier.fillMaxSize().padding(top = adaptiveTopSystemInset()).navigationBarsPadding()
                .padding(start = 40.dp, end = 40.dp, top = 18.dp, bottom = 24.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(8.dp)),
                color = Color(0xE9060B11),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.42f))
            ) {
                Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactHudIconButton(Icons.Default.ArrowBack, "Back", accent, onBack)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("COLLECTION", fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                        Text("Choose what appears in Units / Tactics", fontSize = 7.8.sp, color = Color.White.copy(alpha = 0.44f))
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CollectionModeButton("UNITS", mode == CollectionMode.UNITS, accent, Modifier.weight(1f)) { mode = CollectionMode.UNITS }
                CollectionModeButton("TACTICS", mode == CollectionMode.BUILDINGS, accent, Modifier.weight(1f)) { mode = CollectionMode.BUILDINGS }
            }

            if (mode == CollectionMode.UNITS) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactCollectionToggle("HEROES", heroesEnabled, Modifier.weight(1f)) { heroesEnabled = it; store.heroesEnabled = it; tick++; onChanged() }
                    CompactCollectionToggle("HYBRID", hybridEnabled, Modifier.weight(1f)) { enabled ->
                        hybridEnabled = enabled
                        store.hybridEnabled = enabled
                        if (enabled && Catalog.units.none { it.faction == Faction.HYBRID && store.isUnitSelected(it) }) {
                            Catalog.units.filter { it.faction == Faction.HYBRID }.forEach { store.setUnitSelected(it, true) }
                        }
                        tick++; onChanged()
                    }
                }
            }

            val fs = if (mode == CollectionMode.UNITS && hybridEnabled) listOf(Faction.TERRAN, Faction.PROTOSS, Faction.ZERG, Faction.HYBRID) else listOf(Faction.TERRAN, Faction.PROTOSS, Faction.ZERG)
            if (faction !in fs) faction = Faction.TERRAN
            FactionTabs(fs, faction) { faction = it }

            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TinyPresetButton("TMG", Modifier.weight(1f)) { store.applyTabletopPreset(); heroesEnabled = store.heroesEnabled; hybridEnabled = store.hybridEnabled; tick++; onChanged() }
                TinyPresetButton("ALL", Modifier.weight(1f)) {
                    if (mode == CollectionMode.UNITS) Catalog.units.filter { it.faction == faction }.forEach { store.setUnitSelected(it, true) }
                    else Catalog.buildings.filter { it.faction == faction }.forEach { store.setBuildingSelected(it, true) }
                    tick++; onChanged()
                }
                TinyPresetButton("NONE", Modifier.weight(1f)) {
                    if (mode == CollectionMode.UNITS) Catalog.units.filter { it.faction == faction }.forEach { store.setUnitSelected(it, false) }
                    else Catalog.buildings.filter { it.faction == faction }.forEach { store.setBuildingSelected(it, false) }
                    tick++; onChanged()
                }
            }

            if (mode == CollectionMode.UNITS) {
                val entries = Catalog.units.filter { it.faction == faction && (!it.heroOrCommander || heroesEnabled) && (!it.hybrid || hybridEnabled) }
                PrefetchUnitArt(entries, "collection:${faction.name}:${entries.size}", limit = 20, preferPortrait = true)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(entries, key = { it.id }) { u ->
                        CollectionUnitCard(u, store.isUnitSelected(u)) { checked ->
                            store.setUnitSelected(u, checked); tick++; onChanged()
                        }
                    }
                }
            } else {
                val entries = Catalog.buildings.filter { it.faction == faction }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(entries, key = { it.id }) { b ->
                        CollectionBuildingCard(b, store.isBuildingSelected(b)) { checked ->
                            store.setBuildingSelected(b, checked); tick++; onChanged()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionModeButton(label: String, selected: Boolean, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val selectedAlpha by animateFloatAsState(if (selected) 0.72f else 0.18f, tween(140), label = "collectionMode")
    Surface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        color = Color(0xE70A1118),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.2.dp, accent.copy(alpha = selectedAlpha))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.65.sp, color = Color.White.copy(alpha = if (selected) 1f else 0.68f))
            if (selected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.52f).height(2.dp).background(accent, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun CompactCollectionToggle(label: String, checked: Boolean, modifier: Modifier = Modifier, onChecked: (Boolean) -> Unit) {
    Surface(
        modifier = modifier.height(34.dp).clickable { onChecked(!checked) },
        color = Color(0xB90A1118),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 7.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.45.sp)
            Switch(checked = checked, onCheckedChange = onChecked, modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CollectionUnitCard(unit: UnitEntry, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val accent = factionColor(if (unit.faction == Faction.HYBRID) Faction.ZERG else unit.faction)
    val edge by animateFloatAsState(if (checked) 0.58f else 0.14f, tween(150), label = "collectionUnit")
    Surface(
        modifier = Modifier.fillMaxWidth().height(178.dp).clickable { onChecked(!checked) },
        color = Color(0xE8091017),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = edge))
    ) {
        Box(Modifier.fillMaxSize().padding(5.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                FramedUnitPortrait(unit, size = 112.dp, preferAnimation = true)
                Spacer(Modifier.height(3.dp))
                Text(unit.name.uppercase(), fontSize = 9.2.sp, lineHeight = 10.4.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (unit.heroOrCommander) Text("HERO / COMMANDER", fontSize = 6.2.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.42.sp, color = accent.copy(alpha = 0.74f))
            }
            CollectionCheck(checked, accent, Modifier.align(Alignment.TopEnd)) { onChecked(!checked) }
        }
    }
}

@Composable
private fun CollectionBuildingCard(building: BuildingEntry, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val accent = factionColor(building.faction)
    val edge by animateFloatAsState(if (checked) 0.60f else 0.14f, tween(150), label = "collectionBuilding")
    Surface(
        modifier = Modifier.fillMaxWidth().height(178.dp).clickable { onChecked(!checked) },
        color = Color(0xE8091017),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = edge))
    ) {
        Box(Modifier.fillMaxSize().padding(5.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                FramedBuildingArtwork(building, Modifier.size(112.dp), requestSizePx = 384)
                Spacer(Modifier.height(3.dp))
                Text(building.name.uppercase(), fontSize = 9.2.sp, lineHeight = 10.4.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("TMG CARD", fontSize = 6.2.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.42.sp, color = accent.copy(alpha = 0.74f))
            }
            CollectionCheck(checked, accent, Modifier.align(Alignment.TopEnd)) { onChecked(!checked) }
        }
    }
}

@Composable
private fun CollectionCheck(checked: Boolean, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(27.dp),
        shape = CircleShape,
        color = if (checked) accent.copy(alpha = 0.88f) else Color(0xD90A1118),
        border = BorderStroke(1.dp, if (checked) Color.White.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.16f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(if (checked) Icons.Default.Check else Icons.Default.Add, if (checked) "In collection" else "Add", Modifier.size(16.dp), tint = if (checked) Color.White else Color.White.copy(alpha = 0.54f))
        }
    }
}

@Composable
private fun PrefetchUnitArt(units: List<UnitEntry>, key: String, limit: Int = 14, preferPortrait: Boolean = false) {
    val context = LocalContext.current
    LaunchedEffect(key) {
        units.asSequence()
            .mapNotNull { unitPortraitSource(it, preferAnimation = preferPortrait) }
            .distinct()
            .take(limit)
            .forEach { url ->
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .memoryCacheKey("art_v6:$url")
                        .diskCacheKey("art_v6:$url")
                        .size(256, 256)
                        .build()
                )
            }
    }
}

@Composable
private fun RulesScreen(rulesEngine: RulesEngine, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(RulesTab.FIELD) }
    var fieldQuery by rememberSaveable { mutableStateOf("") }
    var fieldSuggestions by remember { mutableStateOf<List<RuleBrowseItem>>(emptyList()) }
    var answer by remember { mutableStateOf<RuleAnswer?>(null) }
    var asking by remember { mutableStateOf(false) }
    var sourceQuery by rememberSaveable { mutableStateOf("") }
    var sourceSubmitted by rememberSaveable { mutableStateOf("") }
    var sourceResults by remember { mutableStateOf<List<RuleHit>>(emptyList()) }
    var searchingSource by remember { mutableStateOf(false) }
    var updateReport by remember { mutableStateOf<UpdateReport?>(null) }
    var checkingUpdates by remember { mutableStateOf(false) }

    fun submitAsk(prefill: String? = null) {
        val q = (prefill ?: fieldQuery).trim()
        if (prefill != null) fieldQuery = q
        if (q.isBlank()) {
            answer = null
            return
        }
        fieldSuggestions = emptyList()
        scope.launch {
            asking = true
            answer = withContext(Dispatchers.Default) { rulesEngine.ask(q) }
            asking = false
        }
    }

    fun resolveEntry(item: RuleBrowseItem) {
        fieldQuery = item.title
        fieldSuggestions = emptyList()
        tab = RulesTab.FIELD
        scope.launch {
            asking = true
            answer = withContext(Dispatchers.Default) { rulesEngine.resolve(item.key) }
            asking = false
        }
    }

    fun submitSourceSearch(prefill: String? = null) {
        val q = (prefill ?: sourceQuery).trim()
        if (prefill != null) sourceQuery = q
        sourceSubmitted = q
        if (q.isBlank()) {
            sourceResults = emptyList()
            return
        }
        scope.launch {
            searchingSource = true
            sourceResults = withContext(Dispatchers.Default) { rulesEngine.search(q, 10) }
            searchingSource = false
        }
    }

    LaunchedEffect(fieldQuery, tab) {
        if (tab != RulesTab.FIELD || fieldQuery.trim().length < 2 || asking) {
            fieldSuggestions = emptyList()
            return@LaunchedEffect
        }
        delay(180)
        val q = fieldQuery.trim()
        fieldSuggestions = withContext(Dispatchers.Default) { rulesEngine.suggest(q, 5) }
    }

    Box(Modifier.fillMaxSize()) {
        StablePortraitBackground("images/ui/bg_rules.jpg")
        Box(Modifier.fillMaxSize().background(Color(0x52020408)))
        Column(
            Modifier.fillMaxSize().padding(top = adaptiveTopSystemInset()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(86.dp), contentAlignment = Alignment.Center) {
                AssetFrame(
                    "images/ui/screen_titles/field_manual.png",
                    Modifier.fillMaxWidth().height(86.dp),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = 5.dp)
                        .size(36.dp).background(Color.Black.copy(alpha = 0.58f), CircleShape)
                ) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White.copy(alpha = 0.92f))
                }
            }
            Spacer(Modifier.height(3.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                RulesNavButton("FIELD", Icons.Default.AutoStories, tab == RulesTab.FIELD, Modifier.weight(1f)) { tab = RulesTab.FIELD }
                RulesNavButton("BROWSE", Icons.Default.ViewList, tab == RulesTab.BROWSE, Modifier.weight(1f)) { tab = RulesTab.BROWSE }
                RulesNavButton("SOURCE", Icons.Default.ManageSearch, tab == RulesTab.SOURCE, Modifier.weight(1f)) { tab = RulesTab.SOURCE }
                RulesNavButton("UPDATES", Icons.Default.SystemUpdateAlt, tab == RulesTab.UPDATES, Modifier.weight(1f)) { tab = RulesTab.UPDATES }
            }
            Spacer(Modifier.height(6.dp))

            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = Color(0xB0060A10),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                when (tab) {
                    RulesTab.FIELD -> FieldManualContent(
                        query = fieldQuery,
                        onQuery = { fieldQuery = it },
                        suggestions = fieldSuggestions,
                        answer = answer,
                        asking = asking,
                        onSubmit = { submitAsk() },
                        onResolveSuggestion = { resolveEntry(it) },
                        onAskSuggestion = { submitAsk(it) },
                        onQuickAsk = { submitAsk(it) },
                        onOpenBrowse = { tab = RulesTab.BROWSE }
                    )
                    RulesTab.BROWSE -> RulesBrowseContent(
                        rulesEngine = rulesEngine,
                        onOpen = { resolveEntry(it) },
                        onOpenManualSection = { sectionTitle ->
                            tab = RulesTab.SOURCE
                            submitSourceSearch(sectionTitle)
                        }
                    )
                    RulesTab.SOURCE -> SourceSearchContent(
                        query = sourceQuery,
                        onQuery = { sourceQuery = it },
                        submittedQuery = sourceSubmitted,
                        results = sourceResults,
                        searching = searchingSource,
                        onSubmit = { submitSourceSearch() }
                    )
                    RulesTab.UPDATES -> UpdatesContent(
                        report = updateReport,
                        checking = checkingUpdates,
                        onCheck = {
                            scope.launch {
                                checkingUpdates = true
                                updateReport = OfficialUpdates.check()
                                checkingUpdates = false
                            }
                        },
                        onOpen = { launchUri(context, OfficialUpdates.DOWNLOADS_URL) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldManualContent(
    query: String,
    onQuery: (String) -> Unit,
    suggestions: List<RuleBrowseItem>,
    answer: RuleAnswer?,
    asking: Boolean,
    onSubmit: () -> Unit,
    onResolveSuggestion: (RuleBrowseItem) -> Unit,
    onAskSuggestion: (String) -> Unit,
    onQuickAsk: (String) -> Unit,
    onOpenBrowse: () -> Unit
) {
    val accent = Color(0xFF77C7FF)
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text("FIND A RULE", fontSize = 10.8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp, color = accent)
        Text(
            "Ask naturally. The finder only routes you to verified local rules, FAQ rulings, keywords or printed abilities — it never writes an answer from raw OCR.",
            fontSize = 8.3.sp, lineHeight = 12.sp, color = Color.White.copy(alpha = 0.46f)
        )
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                label = { Text("Find a rule or ask naturally") },
                placeholder = { Text("e.g. How far can a unit move?") },
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = onSubmit,
                modifier = Modifier.height(56.dp).width(58.dp),
                contentPadding = PaddingValues(0.dp),
                enabled = query.isNotBlank() && !asking
            ) {
                if (asking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Send, "Find")
            }
        }

        if (suggestions.isNotEmpty() && !asking) {
            Spacer(Modifier.height(5.dp))
            Surface(
                color = Color(0xE20B1118),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.20f))
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    suggestions.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onResolveSuggestion(item) }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(color = accent.copy(alpha = 0.10f), shape = RoundedCornerShape(4.dp)) {
                                Text(item.badge, Modifier.padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 6.2.sp, fontWeight = FontWeight.Black, color = accent)
                            }
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontSize = 9.3.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.90f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.subtitle, fontSize = 7.3.sp, color = Color.White.copy(alpha = 0.42f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.ChevronRight, null, Modifier.size(14.dp), tint = accent.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(7.dp))
        Text("RIGHT NOW", fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp, color = Color.White.copy(alpha = 0.38f))
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("MOVEMENT", "ASSAULT", "COMBAT", "SCORING").forEach { label ->
                OutlinedButton(
                    onClick = { onQuickAsk("How does ${label.lowercase()} work?") },
                    modifier = Modifier.weight(1f).height(34.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                ) { Text(label, fontSize = 6.8.sp, fontWeight = FontWeight.Black, maxLines = 1) }
            }
        }

        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("COMMON LOOKUPS", Modifier.weight(1f), fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp, color = Color.White.copy(alpha = 0.38f))
            TextButton(onClick = onOpenBrowse, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text("BROWSE ALL", fontSize = 6.8.sp, fontWeight = FontWeight.Black, color = accent)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("Move distance", "Ranged Attack", "Charge", "Cover", "Reactions", "Evade", "Objectives", "First Player").forEach { prompt ->
                SuggestionChip(onClick = { onQuickAsk(prompt) }, label = { Text(prompt, fontSize = 7.5.sp) })
            }
        }
        Spacer(Modifier.height(7.dp))

        RuleAnswerArea(
            answer = answer,
            asking = asking,
            modifier = Modifier.fillMaxWidth().weight(1f),
            onSuggestion = onAskSuggestion
        )
    }
}

@Composable
private fun RuleAnswerArea(
    answer: RuleAnswer?,
    asking: Boolean,
    modifier: Modifier = Modifier,
    onSuggestion: (String) -> Unit
) {
    val accent = Color(0xFF77C7FF)
    when {
        asking -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = accent)
                Spacer(Modifier.height(7.dp))
                Text("CHECKING VERIFIED ENTRIES…", fontSize = 7.8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp, color = Color.White.copy(alpha = 0.46f))
            }
        }
        answer == null -> Surface(
            modifier = modifier,
            color = Color(0x80101721),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))
        ) {
            Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.AutoStories, null, Modifier.size(30.dp), tint = accent.copy(alpha = 0.40f))
                Spacer(Modifier.height(7.dp))
                Text("TABLE-SIDE FIELD MANUAL", fontSize = 9.8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.65.sp)
                Spacer(Modifier.height(4.dp))
                Text("Type a messy question, tap a common lookup, or browse verified rules directly. If the finder is not sure, it asks instead of guessing.", fontSize = 8.7.sp, lineHeight = 12.8.sp, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.46f))
            }
        }
        else -> LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            item { RuleAnswerCard(answer, onSuggestion) }
        }
    }
}

@Composable
private fun RuleAnswerCard(answer: RuleAnswer, onSuggestion: (String) -> Unit) {
    val accent = Color(0xFF77C7FF)
    val isNo = answer.confidence.startsWith("NO")
    var zoomedVisual by remember(answer.visualAsset) { mutableStateOf(false) }
    Surface(
        color = Color(0xEC101721),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, if (isNo) Color(0xFFD66A6A).copy(alpha = 0.68f) else accent.copy(alpha = 0.38f))
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(answer.title, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 0.45.sp, color = if (isNo) Color(0xFFE89696) else accent)
                Surface(color = if (isNo) Color(0xFFD66A6A).copy(alpha = 0.14f) else accent.copy(alpha = 0.12f), shape = RoundedCornerShape(5.dp)) {
                    Text(answer.confidence, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 6.4.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.76f))
                }
            }
            answer.interpretedAs?.let { understood ->
                Spacer(Modifier.height(7.dp))
                Surface(color = accent.copy(alpha = 0.08f), shape = RoundedCornerShape(6.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text("UNDERSTOOD AS", fontSize = 6.3.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color.White.copy(alpha = 0.34f))
                        Text(understood, fontSize = 9.sp, lineHeight = 13.sp, color = accent.copy(alpha = 0.90f))
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(answer.summary, fontSize = 11.4.sp, lineHeight = 17.sp, color = Color.White.copy(alpha = 0.94f))
            if (answer.bullets.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(6.dp))
                answer.bullets.forEach { line ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("•", color = accent, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(6.dp))
                        Text(line, Modifier.weight(1f), fontSize = 10.2.sp, lineHeight = 15.4.sp, color = Color.White.copy(alpha = 0.80f))
                    }
                }
            }
            answer.visualAsset?.let { asset ->
                Spacer(Modifier.height(9.dp))
                Surface(
                    onClick = { zoomedVisual = true },
                    color = Color(0xB5080D13),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "CARD REFERENCE",
                                modifier = Modifier.weight(1f).padding(horizontal = 3.dp, vertical = 2.dp),
                                fontSize = 6.4.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp,
                                color = accent.copy(alpha = 0.76f)
                            )
                            Icon(Icons.Default.ZoomIn, null, Modifier.size(13.dp), tint = accent.copy(alpha = 0.75f))
                            Spacer(Modifier.width(3.dp))
                            Text("TAP TO ZOOM", fontSize = 6.1.sp, fontWeight = FontWeight.Black, color = accent.copy(alpha = 0.70f))
                        }
                        AssetFrame(
                            asset,
                            Modifier.fillMaxWidth().aspectRatio(answer.visualAspectRatio),
                            contentScale = ContentScale.Fit
                        )
                        answer.visualCaption?.let { caption ->
                            Text(
                                caption,
                                fontSize = 8.6.sp,
                                lineHeight = 12.8.sp,
                                color = Color.White.copy(alpha = 0.66f),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
            if (answer.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(6.dp))
                Text(if (answer.confidence == "CLARIFY") "WHICH ONE?" else "TRY ONE OF THESE", fontSize = 6.6.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color.White.copy(alpha = 0.36f))
                Spacer(Modifier.height(4.dp))
                answer.suggestions.forEach { suggestion ->
                    OutlinedButton(
                        onClick = { onSuggestion(suggestion) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)
                    ) { Text(suggestion, fontSize = 8.1.sp, lineHeight = 11.5.sp, textAlign = TextAlign.Center) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(color = Color(0xA1080D13), shape = RoundedCornerShape(7.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Verified, null, Modifier.size(13.dp), tint = accent.copy(alpha = 0.80f))
                    Spacer(Modifier.width(5.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SOURCE", fontSize = 6.3.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color.White.copy(alpha = 0.34f))
                        Text(answer.source, fontSize = 8.6.sp, fontWeight = FontWeight.Bold, color = accent.copy(alpha = 0.88f))
                    }
                }
            }
            answer.excerpt?.let { excerpt ->
                Spacer(Modifier.height(7.dp))
                Text("SOURCE PASSAGE", fontSize = 6.3.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color.White.copy(alpha = 0.34f))
                Spacer(Modifier.height(3.dp))
                Text(excerpt, fontSize = 9.2.sp, lineHeight = 13.8.sp, color = Color.White.copy(alpha = 0.58f))
            }
        }
    }
    if (zoomedVisual) {
        answer.visualAsset?.let { asset ->
            ZoomableRuleImageDialog(
                asset = asset,
                caption = answer.visualCaption,
                onDismiss = { zoomedVisual = false }
            )
        }
    }
}

@Composable
private fun ZoomableRuleImageDialog(
    asset: String,
    caption: String?,
    onDismiss: () -> Unit
) {
    var scale by remember(asset) { mutableFloatStateOf(1f) }
    var pan by remember(asset) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = nextScale
        pan = if (nextScale <= 1.001f) Offset.Zero else Offset(pan.x + panChange.x, pan.y + panChange.y)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.86f),
            color = Color(0xFA05090E),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.40f))
        ) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RULE REFERENCE", fontSize = 9.6.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color(0xFF77C7FF))
                        Text("PINCH TO ZOOM · DRAG TO PAN", fontSize = 6.8.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.42f))
                    }
                    TextButton(onClick = { scale = 1f; pan = Offset.Zero }) {
                        Text("RESET", fontSize = 7.2.sp, fontWeight = FontWeight.Black)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White.copy(alpha = 0.84f))
                    }
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.48f))
                        .transformable(transformState),
                    contentAlignment = Alignment.Center
                ) {
                    AssetFrame(
                        asset,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = pan.x
                            translationY = pan.y
                        },
                        contentScale = ContentScale.Fit
                    )
                }
                caption?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 8.4.sp, lineHeight = 12.sp, color = Color.White.copy(alpha = 0.62f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun RulesBrowseContent(
    rulesEngine: RulesEngine,
    onOpen: (RuleBrowseItem) -> Unit,
    onOpenManualSection: (String) -> Unit
) {
    var category by remember { mutableStateOf(RulesBrowseTab.CORE) }
    var filter by rememberSaveable { mutableStateOf("") }
    var expandedManualPart by rememberSaveable { mutableIntStateOf(-1) }
    val accent = Color(0xFF77C7FF)
    val items = remember(category, rulesEngine) {
        when (category) {
            RulesBrowseTab.CORE -> rulesEngine.manualPartItems() + rulesEngine.browseCoreItems()
            RulesBrowseTab.KEYWORDS -> rulesEngine.browseKeywordItems()
            RulesBrowseTab.FAQ -> rulesEngine.browseFaqItems()
            RulesBrowseTab.TERRAN -> rulesEngine.browseAbilityItems("TERRAN")
            RulesBrowseTab.PROTOSS -> rulesEngine.browseAbilityItems("PROTOSS")
            RulesBrowseTab.ZERG -> rulesEngine.browseAbilityItems("ZERG")
        }
    }
    val semanticBrowseKeys = remember(category, filter, rulesEngine) {
        if (filter.isBlank()) emptySet() else rulesEngine.suggest(filter, limit = 40).mapTo(linkedSetOf()) { it.key }
    }
    val filtered = remember(items, filter, semanticBrowseKeys) {
        val q = filter.trim().lowercase()
        if (q.isBlank()) items else items.filter {
            it.key in semanticBrowseKeys ||
                it.title.lowercase().contains(q) ||
                it.subtitle.lowercase().contains(q) ||
                it.badge.lowercase().contains(q)
        }
    }

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text("BROWSE VERIFIED RULES", fontSize = 10.8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp, color = accent)
        Text("Browse the official rule structure, glossary, FAQ rulings and printed faction abilities. Core rulebook Parts now expand in place; tap a section to open its exact bundled source.", fontSize = 8.3.sp, lineHeight = 12.sp, color = Color.White.copy(alpha = 0.44f))
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            RulesBrowseTab.entries.forEach { option ->
                FilterChip(
                    selected = category == option,
                    onClick = { category = option; filter = ""; expandedManualPart = -1 },
                    label = { Text(option.name, fontSize = 7.2.sp, fontWeight = FontWeight.Black) }
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Filter ${category.name.lowercase()} entries") },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(17.dp)) },
            singleLine = true
        )
        Spacer(Modifier.height(6.dp))
        Text("${filtered.size} ENTR${if (filtered.size == 1) "Y" else "IES"}", fontSize = 6.8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color.White.copy(alpha = 0.34f))
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
            items(filtered, key = { it.key }) { item ->
                val manualPartNumber = item.key.takeIf { it.startsWith("manual:") }?.removePrefix("manual:")?.toIntOrNull()
                val manualPart = manualPartNumber?.let { n -> QuickRules.manualParts.firstOrNull { it.number == n } }
                val expanded = manualPartNumber != null && expandedManualPart == manualPartNumber
                Surface(
                    onClick = {
                        if (manualPartNumber != null) {
                            expandedManualPart = if (expanded) -1 else manualPartNumber
                        } else {
                            onOpen(item)
                        }
                    },
                    color = if (expanded) Color(0xEF10202D) else Color(0xE6101721),
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, if (expanded) accent.copy(alpha = 0.44f) else Color.White.copy(alpha = 0.08f))
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = accent.copy(alpha = 0.10f), shape = RoundedCornerShape(4.dp)) {
                                Text(item.badge, Modifier.padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 6.1.sp, fontWeight = FontWeight.Black, color = accent)
                            }
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontSize = 9.8.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.90f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (item.subtitle.isNotBlank()) Text(item.subtitle, fontSize = 7.6.sp, lineHeight = 10.8.sp, color = Color.White.copy(alpha = 0.44f), maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(
                                when {
                                    manualPartNumber != null && expanded -> Icons.Default.ExpandLess
                                    manualPartNumber != null -> Icons.Default.ExpandMore
                                    else -> Icons.Default.ChevronRight
                                },
                                null,
                                Modifier.size(15.dp),
                                tint = accent.copy(alpha = 0.58f)
                            )
                        }
                        if (expanded && manualPart != null) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("SECTIONS · TAP TO OPEN SOURCE", fontSize = 6.2.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp, color = accent.copy(alpha = 0.60f))
                                manualPart.sections.forEach { section ->
                                    Surface(
                                        onClick = { onOpenManualSection(section.title) },
                                        color = accent.copy(alpha = 0.075f),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, accent.copy(alpha = 0.14f))
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.MenuBook, null, Modifier.size(13.dp), tint = accent.copy(alpha = 0.78f))
                                            Spacer(Modifier.width(6.dp))
                                            Text(section.title, Modifier.weight(1f), fontSize = 8.8.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.84f))
                                            Text("p.${section.page}", fontSize = 7.4.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.40f))
                                            Spacer(Modifier.width(4.dp))
                                            Icon(Icons.Default.ChevronRight, null, Modifier.size(13.dp), tint = accent.copy(alpha = 0.55f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceSearchContent(
    query: String,
    onQuery: (String) -> Unit,
    submittedQuery: String,
    results: List<RuleHit>,
    searching: Boolean,
    onSubmit: () -> Unit
) {
    val accent = Color(0xFF77C7FF)
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text("EXACT SOURCE SEARCH", fontSize = 10.8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp, color = accent)
        Text("Literal source hunting only. These are excerpts from the bundled books/cards, not interpreted answers. Search runs only when you press the button.", fontSize = 8.3.sp, lineHeight = 12.sp, color = Color.White.copy(alpha = 0.44f))
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                label = { Text("Exact words or phrase") },
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = onSubmit, modifier = Modifier.height(56.dp).width(58.dp), contentPadding = PaddingValues(0.dp), enabled = query.isNotBlank() && !searching) {
                if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, "Search")
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            searching -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = accent)
            }
            submittedQuery.isBlank() -> EmptyCollectionMessage("Power-user escape hatch: search exact wording in the Core Rules, FAQ and faction-card source text.")
            results.isEmpty() -> EmptyCollectionMessage("No literal source match for “$submittedQuery”.\nTry fewer or more exact words.")
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
                item { Text("${results.size} SOURCE MATCH${if (results.size == 1) "" else "ES"}", fontSize = 6.8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color.White.copy(alpha = 0.34f)) }
                items(results) { hit ->
                    Surface(color = Color(0xE6101721), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))) {
                        Column(Modifier.padding(10.dp)) {
                            Text(hit.source, fontSize = 8.5.sp, fontWeight = FontWeight.Black, color = accent)
                            Spacer(Modifier.height(4.dp))
                            Text(hit.snippet, fontSize = 10.sp, lineHeight = 14.8.sp, color = Color.White.copy(alpha = 0.78f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesNavButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = Color(0xFF77C7FF)
    Surface(
        onClick = onClick,
        modifier = modifier.height(37.dp),
        color = if (selected) Color(0xFF173A52) else Color(0xE00B1118),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(if (selected) 1.4.dp else 1.dp, if (selected) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.08f))
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(14.dp), tint = if (selected) accent else Color.White.copy(alpha = 0.48f))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 7.7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.48.sp, color = if (selected) Color.White else Color.White.copy(alpha = 0.68f), maxLines = 1)
        }
    }
}

@Composable
private fun QuickReferenceContent() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(11.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Surface(color = Color(0xEA101721), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.38f))) {
                Column(Modifier.padding(13.dp)) {
                    Text("ROUND SEQUENCE", fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp, color = Color(0xFF77C7FF))
                    Spacer(Modifier.height(7.dp))
                    QuickRules.roundSequence.forEachIndexed { index, line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(if (index == 0) "•" else "${index}.", modifier = Modifier.width(22.dp), color = Color(0xFF77C7FF), fontWeight = FontWeight.Black, fontSize = 10.sp)
                            Text(line.removePrefix("${index} · "), Modifier.weight(1f), fontSize = 11.5.sp, lineHeight = 16.5.sp, color = Color.White.copy(alpha = 0.88f))
                        }
                    }
                }
            }
        }
        items(QuickRules.cards) { card ->
            Surface(color = Color(0xEA101721), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))) {
                Column(Modifier.padding(13.dp)) {
                    Text(card.title, fontSize = 12.4.sp, fontWeight = FontWeight.Black, letterSpacing = 0.55.sp, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    card.lines.forEach { line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)) {
                            Text("•", color = Color(0xFF77C7FF), fontWeight = FontWeight.Black)
                            Spacer(Modifier.width(6.dp))
                            Text(line, Modifier.weight(1f), fontSize = 11.2.sp, lineHeight = 16.5.sp, color = Color.White.copy(alpha = 0.82f))
                        }
                    }
                }
            }
        }
        item { Text("ASK gives a concise table answer. SEARCH shows the underlying local source passages.", fontSize = 9.3.sp, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.42f), modifier = Modifier.fillMaxWidth().padding(8.dp)) }
    }
}

@Composable
private fun BookContent(onOpenSection: (String) -> Unit) {
    var expandedPart by rememberSaveable { mutableIntStateOf(8) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item {
            Surface(color = Color(0xE90D1720), shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.30f))) {
                Column(Modifier.padding(12.dp)) {
                    Text("CORE RULEBOOK INDEX", fontSize = 12.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp, color = Color(0xFF77C7FF))
                    Spacer(Modifier.height(4.dp))
                    Text("A readable map of the official book instead of a dump of OCR paragraphs. Tap a Part to open its sections; tap a section to search the exact bundled source text.", fontSize = 10.3.sp, lineHeight = 15.sp, color = Color.White.copy(alpha = 0.72f))
                }
            }
        }
        items(QuickRules.manualParts, key = { it.number }) { part ->
            val expanded = expandedPart == part.number
            Surface(
                onClick = { expandedPart = if (expanded) -1 else part.number },
                color = if (expanded) Color(0xEF10202D) else Color(0xE610171F),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (expanded) Color(0xFF77C7FF).copy(alpha = 0.46f) else Color.White.copy(alpha = 0.08f))
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = Color(0xFF173A52), shape = RoundedCornerShape(6.dp)) {
                            Text("${part.number}", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color(0xFFBDE7FF))
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(part.title, fontSize = 11.2.sp, fontWeight = FontWeight.Black, letterSpacing = 0.35.sp, color = Color.White)
                            Text("PART ${part.number}  •  PAGE ${part.page}", fontSize = 7.3.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp, color = Color(0xFF77C7FF).copy(alpha = 0.70f))
                        }
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Color.White.copy(alpha = 0.52f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(part.summary, fontSize = 9.6.sp, lineHeight = 14.sp, color = Color.White.copy(alpha = 0.62f))
                    if (expanded) {
                        Spacer(Modifier.height(9.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        Spacer(Modifier.height(5.dp))
                        part.sections.forEach { section ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenSection(section.title) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(15.dp), tint = Color(0xFF77C7FF).copy(alpha = 0.82f))
                                Spacer(Modifier.width(4.dp))
                                Text(section.title, Modifier.weight(1f), fontSize = 9.8.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.84f))
                                Text("p.${section.page}", fontSize = 8.2.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.38f))
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("FAQ and faction-card rules are indexed by ASK and SEARCH. BOOK is kept intentionally clean and navigable.", fontSize = 9.sp, lineHeight = 13.sp, color = Color.White.copy(alpha = 0.40f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp))
        }
    }
}

@Composable
private fun KeywordsContent() {
    val sortedKeywords = remember { QuickRules.keywords.sortedBy { it.term.lowercase() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sortedKeywords, key = { it.term }) { keyword ->
            Surface(color = Color(0xE9101721), shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(keyword.term, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF77C7FF))
                        Text(keyword.source, fontSize = 6.8.sp, color = Color.White.copy(alpha = 0.30f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(keyword.text, fontSize = 11.1.sp, lineHeight = 16.5.sp, color = Color.White.copy(alpha = 0.86f))
                    if (keyword.details.isNotEmpty()) {
                        Spacer(Modifier.height(5.dp))
                        keyword.details.forEach { detail ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text("•", color = Color(0xFF77C7FF), fontWeight = FontWeight.Black)
                                Spacer(Modifier.width(5.dp))
                                Text(detail, Modifier.weight(1f), fontSize = 9.7.sp, lineHeight = 14.5.sp, color = Color.White.copy(alpha = 0.68f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchContent(
    query: String,
    onQuery: (String) -> Unit,
    submittedQuery: String,
    results: List<RuleHit>,
    searching: Boolean,
    onSubmit: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text("SOURCE SEARCH", fontSize = 10.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp, color = Color(0xFF77C7FF))
        Text("Search uses all important words together. Use two or three specific terms for tight results.", fontSize = 8.5.sp, lineHeight = 12.sp, color = Color.White.copy(alpha = 0.44f))
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                label = { Text("Rule, phrase, ability…") },
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = onSubmit, modifier = Modifier.height(56.dp).width(58.dp), contentPadding = PaddingValues(0.dp), enabled = query.isNotBlank() && !searching) {
                if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, "Search")
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            searching -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Color(0xFF77C7FF))
            }
            submittedQuery.isBlank() -> EmptyCollectionMessage("Try: PLACE effect • charge target • first player • reaction trigger • available supply")
            results.isEmpty() -> EmptyCollectionMessage("No tight source match for “$submittedQuery”.\nTry fewer words or the exact rule / ability name.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 14.dp)
            ) {
                item {
                    Text("${results.size} BEST MATCH${if (results.size == 1) "" else "ES"}", fontSize = 7.8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color.White.copy(alpha = 0.40f))
                }
                items(results) { hit ->
                    Surface(color = Color(0xEA101721), shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MenuBook, null, Modifier.size(14.dp), tint = Color(0xFF77C7FF))
                                Spacer(Modifier.width(5.dp))
                                Text(hit.source, modifier = Modifier.weight(1f), fontSize = 9.1.sp, fontWeight = FontWeight.Black, color = Color(0xFF77C7FF))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(hit.snippet, fontSize = 10.8.sp, lineHeight = 16.2.sp, color = Color.White.copy(alpha = 0.84f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AskContent(
    query: String,
    onQuery: (String) -> Unit,
    answer: RuleAnswer?,
    asking: Boolean,
    onSubmit: () -> Unit,
    onSuggestion: (String) -> Unit
) {
    val accent = Color(0xFF77C7FF)
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text("ASK THE FIELD MANUAL", fontSize = 10.8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp, color = accent)
        Text("ASK now uses structured rules topics, the glossary, clean FAQ Q&A and named card abilities. It will refuse to guess rather than dump unrelated OCR text.", fontSize = 8.3.sp, lineHeight = 12.sp, color = Color.White.copy(alpha = 0.44f))
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                label = { Text("Ask a rules question") },
                placeholder = { Text("e.g. How far can a unit move?") },
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = onSubmit, modifier = Modifier.height(56.dp).width(58.dp), contentPadding = PaddingValues(0.dp), enabled = query.isNotBlank() && !asking) {
                if (asking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Send, "Ask")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("How far can a unit move?", "How does Evade work?", "How do I control a Mission Marker?", "What does Stimpack do?").forEach { prompt ->
                SuggestionChip(onClick = { onSuggestion(prompt) }, label = { Text(prompt.replace("What is ", "").replace("What does ", "").replace("How do ", "").removeSuffix(" do?").removeSuffix(" work?").removeSuffix("?"), fontSize = 8.sp) })
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            asking -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp, color = accent)
                    Spacer(Modifier.height(8.dp))
                    Text("CHECKING LOCAL SOURCES…", fontSize = 8.4.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color.White.copy(alpha = 0.48f))
                }
            }
            answer == null -> Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = Color(0x94101721),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.QuestionAnswer, null, Modifier.size(34.dp), tint = accent.copy(alpha = 0.48f))
                    Spacer(Modifier.height(8.dp))
                    Text("ASK IN NORMAL LANGUAGE", fontSize = 10.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("You can write naturally, leave words out, or ask a short table question. The app shows how it understood the question and only answers when it has a structured source match.", fontSize = 9.sp, lineHeight = 13.5.sp, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.48f))
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                item {
                    Surface(
                        color = Color(0xEC101721),
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, if (answer.confidence.startsWith("NO")) Color(0xFFD66A6A).copy(alpha = 0.68f) else accent.copy(alpha = 0.38f))
                    ) {
                        Column(Modifier.padding(13.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(answer.title, modifier = Modifier.weight(1f), fontSize = 13.2.sp, fontWeight = FontWeight.Black, letterSpacing = 0.55.sp, color = accent)
                                Surface(color = if (answer.confidence.startsWith("NO")) Color(0xFFD66A6A).copy(alpha = 0.14f) else accent.copy(alpha = 0.12f), shape = RoundedCornerShape(5.dp)) {
                                    Text(answer.confidence, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 6.7.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.76f))
                                }
                            }
                            answer.interpretedAs?.let { understood ->
                                Spacer(Modifier.height(7.dp))
                                Surface(color = accent.copy(alpha = 0.08f), shape = RoundedCornerShape(6.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                                        Text("UNDERSTOOD AS", fontSize = 6.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color.White.copy(alpha = 0.34f))
                                        Spacer(Modifier.height(2.dp))
                                        Text(understood, fontSize = 9.2.sp, lineHeight = 13.2.sp, color = accent.copy(alpha = 0.88f))
                                    }
                                }
                            }
                            Spacer(Modifier.height(7.dp))
                            Text("ANSWER", fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp, color = Color.White.copy(alpha = 0.36f))
                            Spacer(Modifier.height(3.dp))
                            Text(answer.summary, fontSize = 11.7.sp, lineHeight = 17.5.sp, color = Color.White.copy(alpha = 0.93f))
                            if (answer.bullets.isNotEmpty()) {
                                Spacer(Modifier.height(9.dp))
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                Spacer(Modifier.height(7.dp))
                                Text("DETAILS", fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp, color = Color.White.copy(alpha = 0.36f))
                                Spacer(Modifier.height(3.dp))
                                answer.bullets.forEach { line ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        Text("•", color = accent, fontWeight = FontWeight.Black)
                                        Spacer(Modifier.width(6.dp))
                                        Text(line, Modifier.weight(1f), fontSize = 10.5.sp, lineHeight = 15.8.sp, color = Color.White.copy(alpha = 0.80f))
                                    }
                                }
                            }
                            if (answer.suggestions.isNotEmpty()) {
                                Spacer(Modifier.height(9.dp))
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                Spacer(Modifier.height(7.dp))
                                Text("DID YOU MEAN?", fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp, color = Color.White.copy(alpha = 0.36f))
                                Spacer(Modifier.height(5.dp))
                                answer.suggestions.forEach { suggestion ->
                                    OutlinedButton(
                                        onClick = { onSuggestion(suggestion) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text(suggestion, fontSize = 8.3.sp, lineHeight = 11.5.sp, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            Surface(color = Color(0xA1080D13), shape = RoundedCornerShape(7.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Verified, null, Modifier.size(13.dp), tint = accent.copy(alpha = 0.80f))
                                    Spacer(Modifier.width(5.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("SOURCE", fontSize = 6.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color.White.copy(alpha = 0.34f))
                                        Text(answer.source, fontSize = 8.8.sp, fontWeight = FontWeight.Bold, color = accent.copy(alpha = 0.88f))
                                    }
                                }
                            }
                        }
                    }
                }
                answer.excerpt?.let { excerpt ->
                    item {
                        Surface(color = Color(0x90090E14), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))) {
                            Column(Modifier.padding(11.dp)) {
                                Text("RELATED SOURCE PASSAGE", fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = Color.White.copy(alpha = 0.36f))
                                Spacer(Modifier.height(5.dp))
                                Text(excerpt, fontSize = 9.5.sp, lineHeight = 14.2.sp, color = Color.White.copy(alpha = 0.62f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatesContent(report: UpdateReport?, checking: Boolean, onCheck: () -> Unit, onOpen: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(11.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Surface(color = Color(0xE9101721), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF77C7FF).copy(alpha = 0.35f))) {
                Column(Modifier.padding(13.dp)) {
                    Text("OFFICIAL ARCHON UPDATES", fontWeight = FontWeight.Black, color = Color(0xFF77C7FF))
                    Spacer(Modifier.height(6.dp))
                    Text("Downloads and hashes the official Core Rules, FAQ and faction card PDFs, then compares them with the exact documents used to build this app's local rules database.", fontSize = 11.sp, lineHeight = 16.5.sp, color = Color.White.copy(alpha = 0.82f))
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onCheck, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                        if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (checking) "CHECKING…" else "CHECK FOR UPDATES", fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(7.dp))
                    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.OpenInBrowser, null); Spacer(Modifier.width(8.dp)); Text("OPEN OFFICIAL DOWNLOADS") }
                }
            }
        }
        report?.let { r ->
            item {
                Surface(color = Color(0xE9101721), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (r.ok) Color(0xFF6DC58A) else Color(0xFFD66A6A))) {
                    Column(Modifier.padding(13.dp)) {
                        Text(if (r.ok) "CHECK COMPLETE" else "CHECK FAILED", fontWeight = FontWeight.Black, color = if (r.ok) Color(0xFF6DC58A) else Color(0xFFD66A6A))
                        Spacer(Modifier.height(6.dp))
                        Text(r.message, fontSize = 11.sp, lineHeight = 16.5.sp, color = Color.White.copy(alpha = 0.82f))
                    }
                }
            }
        }
        item {
            Text("Local sources: Core Rules, FAQ, Protoss cards, Terran cards and Zerg cards.", fontSize = 9.sp, lineHeight = 13.sp, color = Color.White.copy(alpha = 0.42f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp))
        }
    }
}

@Composable private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) { if (selected) Button(onClick = onClick, modifier = modifier.height(46.dp)) { Text(label, fontWeight = FontWeight.Black) } else OutlinedButton(onClick = onClick, modifier = modifier.height(46.dp)) { Text(label, fontWeight = FontWeight.Bold) } }
@Composable private fun TinyPresetButton(label: String, modifier: Modifier, onClick: () -> Unit) { OutlinedButton(onClick = onClick, modifier = modifier.height(38.dp), contentPadding = PaddingValues(0.dp)) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Black) } }
@Composable private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold); Switch(checked, onChecked) } }

@Composable
private fun adaptiveTopSystemInset(): androidx.compose.ui.unit.Dp {
    // IMPORTANT: keep UI registration in the app window's own coordinate system. Samsung's
    // per-app "Hide camera cutout" option physically moves/resizes that window. Reading its
    // reported top inset here caused controls/titles to counter-shift while the background moved
    // with the window, breaking their authored alignment. A small fixed transparent safe margin
    // keeps controls clear of normal status icons while allowing background + UI to move together
    // when the device setting changes. No fake black bar, no dynamic top re-registration.
    return 28.dp
}

@Composable
private fun StablePortraitBackground(path: String) {
    // Anchor authored portrait art to the screen width and top edge. A camera-cutout preference can
    // change the usable window height, but it must not stretch or vertically re-register the artwork.
    AssetBackground(path, ContentScale.FillWidth, alignment = Alignment.TopCenter)
}

@Composable
private fun FactionBackdrop(theme: FactionThemeOption) {
    Box(Modifier.fillMaxSize()) {
        StablePortraitBackground(theme.backgroundAsset)
        AmbientBackdropFx(accent = theme.accent, intensity = 0.78f, particleCount = 9)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.025f)))
    }
}

@Composable
private fun FactionBackdrop(faction: Faction, accent: Color) {
    Box(Modifier.fillMaxSize()) {
        val artFaction = if (faction == Faction.HYBRID) Faction.ZERG else faction
        StablePortraitBackground("images/ui/bg_${artFaction.name.lowercase()}.jpg")
        AmbientBackdropFx(accent = accent, intensity = 0.88f, particleCount = 10)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.045f)))
    }
}

@Composable
private fun MusicBackdrop(faction: Faction, accent: Color) {
    Box(Modifier.fillMaxSize()) {
        StablePortraitBackground("images/ui/bg_music_jukebox.webp")
        AmbientBackdropFx(accent = Color(0xFFFFA13B), intensity = 0.24f, particleCount = 5)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.025f)))
    }
}

@Composable
private fun TrackerBackdrop() {
    Box(Modifier.fillMaxSize()) {
        StablePortraitBackground("images/ui/bg_trackers.jpg")
        AmbientBackdropFx(accent = Color(0xFF55BFFF), intensity = 0.62f, particleCount = 7)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.055f)))
    }
}

@Composable
private fun WelcomeVfxLayer() {
    val transition = rememberInfiniteTransition(label = "welcome-vfx")
    val breathe by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "welcome-breathe"
    )
    val hotPulse by transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "welcome-hot-pulse"
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(16500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "welcome-drift"
    )

    Box(Modifier.fillMaxSize()) {
        // Keep the welcome screen alive without painting mismatched AI-derived character masks
        // over the approved rulebook-cover composition. Atmosphere is deliberately restrained.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            fun softGlow(x: Float, y: Float, radius: Float, color: Color, alpha: Float) {
                val center = Offset(w * x, h * y)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = alpha),
                            color.copy(alpha = alpha * 0.20f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = w * radius
                    ),
                    radius = w * radius,
                    center = center
                )
            }

            // Slightly stronger than R17: the new official-art background is darker and the
            // earlier values were effectively invisible on-device. Keep it atmospheric, not neon.
            softGlow(0.18f, 0.29f, 0.17f, Color(0xFF56CFFF), 0.055f + breathe * 0.070f)
            softGlow(0.80f, 0.29f, 0.18f, Color(0xFFE45BFF), 0.060f + hotPulse * 0.075f)
            softGlow(0.50f, 0.91f, 0.23f, Color(0xFF48B8FF), 0.080f + breathe * 0.090f)

            repeat(42) { i ->
                val seed = ((i * 37) % 101) / 101f
                val speed = 0.60f + (i % 7) * 0.07f
                val t = (drift * speed + seed) % 1f
                val baseX = 0.05f + (((i * 0.173f) % 0.90f + 0.90f) % 0.90f)
                val sway = sin(t * 6.283f * (0.72f + (i % 4) * 0.17f) + i * 1.31f) *
                    (0.018f + (i % 5) * 0.005f)
                val px = w * (baseX + sway).coerceIn(0.03f, 0.97f)
                val py = h * (0.995f - t * (0.58f + (i % 5) * 0.018f)).coerceIn(0.34f, 1.02f)
                val warm = i % 6 == 0
                val moteColor = if (warm) Color(0xFFFFA45A) else Color(0xFFB9ECFF)
                drawCircle(
                    color = moteColor.copy(alpha = if (warm) 0.48f else 0.34f),
                    radius = if (warm) 2.25f else 1.55f,
                    center = Offset(px, py)
                )
            }
        }

        // User-supplied overlay isolates the practical lights that still belong to the
        // new approved welcome art: frame LEDs, eyes and platform. One gentle pulse keeps
        // them alive without reintroducing the old Raynor suit-light treatment.
        Box(
            Modifier.fillMaxSize().alpha(
                (0.58f + breathe * 0.24f + hotPulse * 0.14f).coerceIn(0f, 0.96f)
            )
        ) {
            // Background and effect layer are an authored matched pair. Give them the exact same
            // width/top registration so Samsung window-height changes cannot pull them apart.
            AssetOverlay(
                "images/ui/fx/welcome_final_effect.png",
                ContentScale.FillWidth,
                Alignment.TopCenter
            )
        }
    }
}


@Composable
private fun VictoryCelebrationFx(
    accent: Color,
    enabled: Boolean
) {
    val burst = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        burst.snapTo(0f)
        if (enabled) burst.animateTo(1f, animationSpec = tween(4800, easing = LinearEasing))
    }
    if (!enabled || burst.value >= 0.999f) return

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val time = burst.value * 4.8f // real-feeling seconds; the first second is intentionally violent

        // Cannon-mouth glow across the whole foot of the screen. It flashes and disappears fast.
        val ignition = (1f - (time / 0.46f).coerceIn(0f, 1f))
        if (ignition > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        accent.copy(alpha = ignition * 0.12f),
                        Color(0xFFFF8A2A).copy(alpha = ignition * 0.26f),
                        Color.White.copy(alpha = ignition * 0.12f)
                    ),
                    startY = h * 0.62f,
                    endY = h
                )
            )
        }

        // Main celebration blast. Particles originate from three broad cannon banks and leave the
        // bottom at high speed, fan sideways, arc under gravity, and several genuinely fall back.
        repeat(320) { i ->
            val seedA = ((i * 73 + 19) % 257) / 256f
            val seedB = ((i * 131 + 37) % 263) / 262f
            val seedC = ((i * 47 + 11) % 251) / 250f
            val launchDelay = (((i * 41) % 101) / 100f) * 0.48f
            val age = time - launchDelay
            if (age <= 0f || age > 3.25f) return@repeat

            val bank = when (i % 5) {
                0, 1 -> 0.50f
                2 -> 0.20f
                3 -> 0.80f
                else -> seedA
            }
            val startX = w * (bank + (seedA - 0.5f) * 0.11f).coerceIn(0.02f, 0.98f)
            val startY = h * (1.015f + seedB * 0.045f)

            // px / second. These values are intentionally much faster than the old normalized drift.
            val vy0 = -h * (0.88f + seedB * 0.98f)
            val outwardBias = when {
                bank < 0.35f -> -0.16f
                bank > 0.65f -> 0.16f
                else -> 0f
            }
            val vx0 = w * ((seedC - 0.5f) * 1.18f + outwardBias)
            val gravity = h * (0.43f + seedA * 0.48f)
            val chaoticX = sin(age * (7.2f + seedB * 8.0f) + i * 0.73f) * w * (0.006f + seedC * 0.018f) * (0.35f + age)
            val chaoticY = cos(age * (9.0f + seedC * 6.0f) + i * 0.39f) * h * 0.0045f * age

            val x = startX + vx0 * age + chaoticX
            val y = startY + vy0 * age + 0.5f * gravity * age * age + chaoticY
            if (x < -w * 0.14f || x > w * 1.14f || y < -h * 0.16f || y > h * 1.15f) return@repeat

            val fadeIn = (age / 0.055f).coerceIn(0f, 1f)
            val fadeOut = (1f - ((age - 2.30f) / 0.95f).coerceIn(0f, 1f))
            val sparkle = 0.70f + abs(sin(age * 18f + i * 1.11f)) * 0.30f
            val alpha = fadeIn * fadeOut * sparkle
            val c = when (i % 10) {
                0 -> Color.White
                1 -> Color(0xFFFFF0B0)
                2 -> Color(0xFFFFCE62)
                3 -> Color(0xFFFF9E3D)
                4 -> Color(0xFFFF6428)
                5, 6 -> accent
                7 -> Color(0xFF9CEBFF)
                8 -> Color(0xFFFFB34C)
                else -> accent.copy(alpha = 0.96f)
            }
            val radius = 1.35f + (i % 7) * 0.62f
            val vy = vy0 + gravity * age
            val vx = vx0 + cos(age * (7.2f + seedB * 8.0f) + i * 0.73f) * w * 0.03f
            val speedScale = (abs(vy) / h + abs(vx) / w).coerceIn(0.25f, 2.0f)
            val tailSeconds = 0.030f + (i % 5) * 0.006f

            if (i % 4 != 1) {
                drawLine(
                    color = c.copy(alpha = alpha * 0.78f),
                    start = Offset(x, y),
                    end = Offset(x - vx * tailSeconds, y - vy * tailSeconds),
                    strokeWidth = 1.1f + (i % 4) * 0.42f
                )
            }
            drawCircle(c.copy(alpha = alpha), radius * (0.8f + speedScale * 0.15f), Offset(x, y))
            if (i % 17 == 0) {
                drawCircle(c.copy(alpha = alpha * 0.13f), radius * 5.2f, Offset(x, y))
            }
        }

        // Secondary edge jets keep the blast broad. They fire slightly later and cross the screen
        // diagonally, which stops the celebration from reading as one vertical fountain.
        repeat(100) { i ->
            val seedA = ((i * 61 + 7) % 109) / 108f
            val seedB = ((i * 43 + 29) % 127) / 126f
            val delay = 0.18f + (((i * 23) % 83) / 82f) * 1.10f
            val age = time - delay
            if (age <= 0f || age > 2.65f) return@repeat
            val fromLeft = i % 2 == 0
            val startX = if (fromLeft) -w * 0.015f else w * 1.015f
            val startY = h * (0.78f + seedA * 0.24f)
            val vx0 = (if (fromLeft) 1f else -1f) * w * (0.24f + seedB * 0.56f)
            val vy0 = -h * (0.46f + seedA * 0.64f)
            val g = h * (0.35f + seedB * 0.40f)
            val x = startX + vx0 * age + sin(age * 12f + i) * w * 0.018f
            val y = startY + vy0 * age + 0.5f * g * age * age + cos(age * 9f + i * 0.4f) * h * 0.008f
            if (x < -w * 0.08f || x > w * 1.08f || y < -h * 0.08f || y > h * 1.10f) return@repeat
            val fade = (1f - ((age - 1.90f) / 0.75f).coerceIn(0f, 1f))
            val c = when (i % 5) {
                0 -> Color(0xFFFF7C32)
                1 -> Color(0xFFFFD06A)
                2 -> accent
                3 -> Color.White
                else -> Color(0xFF8FE8FF)
            }
            drawLine(c.copy(alpha = fade * 0.72f), Offset(x, y), Offset(x - vx0 * 0.035f, y - (vy0 + g * age) * 0.035f), strokeWidth = 1.25f)
            drawCircle(c.copy(alpha = fade * 0.88f), 1.5f + (i % 4) * 0.7f, Offset(x, y))
        }

        // Erratic lingering motes. These behave more like hot embers / fireflies after the cannon
        // blast, changing direction instead of just drifting vertically and fading in place.
        repeat(60) { i ->
            val delay = 0.72f + (((i * 31) % 73) / 72f) * 1.15f
            val age = time - delay
            if (age <= 0f || age > 2.75f) return@repeat
            val seedA = ((i * 37 + 5) % 89) / 88f
            val seedB = ((i * 59 + 13) % 97) / 96f
            val baseX = w * (0.05f + seedA * 0.90f)
            val baseY = h * (0.14f + seedB * 0.70f)
            val x = baseX + sin(age * (4.5f + seedA * 5.5f) + i * 0.8f) * w * (0.018f + seedB * 0.050f) + cos(age * 13f + i) * w * 0.010f
            val y = baseY + cos(age * (3.8f + seedB * 5.0f) + i * 0.55f) * h * (0.012f + seedA * 0.035f) - age * h * 0.025f
            val fade = (1f - ((age - 2.0f) / 0.75f).coerceIn(0f, 1f))
            val flicker = 0.45f + abs(sin(age * 21f + i)) * 0.55f
            val c = if (i % 3 == 0) accent else if (i % 3 == 1) Color(0xFFFFA13B) else Color(0xFFFFE5A1)
            drawCircle(c.copy(alpha = fade * flicker * 0.82f), 1.4f + (i % 4) * 0.55f, Offset(x, y))
        }
    }
}

@Composable
private fun AmbientBackdropFx(
    accent: Color,
    intensity: Float = 1f,
    particleCount: Int = 8
) {
    val transition = rememberInfiniteTransition(label = "ambient-backdrop")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambient-drift"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambient-pulse"
    )
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val a = intensity.coerceIn(0f, 2f)

        val c1 = Offset(w * (0.18f + sin(drift * 6.283f) * 0.055f), h * (0.22f + drift * 0.05f))
        val c2 = Offset(w * (0.82f + cos(drift * 5.7f) * 0.045f), h * (0.73f - drift * 0.06f))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.055f * a * pulse), Color.Transparent),
                center = c1,
                radius = w * 0.46f
            ),
            radius = w * 0.46f,
            center = c1
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.045f * a * (1.2f - pulse * 0.45f)), Color.Transparent),
                center = c2,
                radius = w * 0.42f
            ),
            radius = w * 0.42f,
            center = c2
        )

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(accent.copy(alpha = 0.055f * a * pulse), Color.Transparent),
                startY = 0f,
                endY = h * 0.18f
            ),
            size = size
        )

        repeat(particleCount.coerceIn(0, 24)) { i ->
            val t = (drift + i * 0.117f) % 1f
            val px = w * (0.07f + (((i * 0.237f + sin(t * 6.283f + i)) % 0.86f + 0.86f) % 0.86f))
            val py = h * (0.94f - t * 0.82f)
            drawCircle(
                color = accent.copy(alpha = (0.055f + 0.045f * pulse) * a),
                radius = 0.9f + (i % 3) * 0.55f,
                center = Offset(px, py)
            )
        }
    }
}

@Composable
private fun PhaseEventFlash(
    phase: Int,
    round: Int,
    enabled: Boolean
) {
    val flash = remember { Animatable(0f) }
    val phaseColor = when (phase) {
        1 -> Color(0xFFFFA64A)
        2 -> Color(0xFFFF5A5A)
        3 -> Color(0xFFFFD86A)
        else -> Color(0xFF5BC8FF)
    }
    LaunchedEffect(phase, round, enabled) {
        if (enabled) {
            flash.snapTo(1f)
            flash.animateTo(0f, animationSpec = tween(760, easing = FastOutSlowInEasing))
        } else {
            flash.snapTo(0f)
        }
    }
    if (flash.value > 0.001f) {
        Canvas(Modifier.fillMaxSize()) {
            val a = flash.value
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        phaseColor.copy(alpha = 0.18f * a),
                        Color.Transparent,
                        phaseColor.copy(alpha = 0.08f * a)
                    )
                ),
                size = size
            )
            drawRect(
                color = phaseColor.copy(alpha = 0.16f * a),
                topLeft = Offset.Zero,
                size = androidx.compose.ui.geometry.Size(size.width, 2.5f + 4f * a)
            )
        }
    }
}

@Composable
private fun DigitalAliveOverlay(accent: Color, intensity: Float = 1f) {
    AmbientBackdropFx(accent = accent, intensity = intensity, particleCount = 6)
}

@Composable
private fun HudEnergyRails(accent: Color, intensity: Float = 1f) {
    val transition = rememberInfiniteTransition(label = "hud-rails")
    val pulse by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hud-rails-pulse"
    )
    Canvas(Modifier.fillMaxSize()) {
        val alpha = (pulse * intensity).coerceIn(0f, 0.75f)
        drawLine(
            color = accent.copy(alpha = alpha),
            start = Offset(0f, 0f),
            end = Offset(size.width * 0.22f, 0f),
            strokeWidth = 2f
        )
        drawLine(
            color = accent.copy(alpha = alpha),
            start = Offset(size.width * 0.78f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 2f
        )
    }
}

@Composable
private fun AssetOverlay(
    path: String,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center
) {
    val context = LocalContext.current
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(assetUri(path))
            .crossfade(false)
            .memoryCacheKey("fx_v1428:${BuildConfig.VERSION_CODE}:$path")
            .diskCacheKey("fx_v1428:${BuildConfig.VERSION_CODE}:$path")
            .build(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = contentScale,
        alignment = alignment,
        loading = { Box(Modifier.fillMaxSize()) },
        error = { Box(Modifier.fillMaxSize()) },
        success = { SubcomposeAsyncImageContent() }
    )
}

@Composable
private fun AssetBackground(
    path: String,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center
) {
    val context = LocalContext.current
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(assetUri(path))
            .crossfade(false)
            .memoryCacheKey("bg_v1424:${BuildConfig.VERSION_CODE}:$path")
            .diskCacheKey("bg_v1424:${BuildConfig.VERSION_CODE}:$path")
            .build(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = contentScale,
        alignment = alignment,
        loading = { Box(Modifier.fillMaxSize().background(Color(0xFF05080D))) },
        error = { Box(Modifier.fillMaxSize().background(Color(0xFF05080D))) },
        success = { SubcomposeAsyncImageContent() }
    )
}

private fun assetUri(path: String): String = "file:///android_asset/$path"

@Composable
private fun EmptyCollectionMessage(message: String) { Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) { Text(message, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.65f)) } }
private fun initials(name: String) = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.take(2)
private fun factionColor(faction: Faction): Color = when (faction) { Faction.TERRAN -> Color(0xFF3AA7E8); Faction.PROTOSS -> Color(0xFFE8B842); Faction.ZERG -> Color(0xFFB857D4); Faction.HYBRID -> Color(0xFFD74F7D) }

private fun launchUri(context: android.content.Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun launchEmail(context: android.content.Context, email: String, subject: String) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:$email")
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
