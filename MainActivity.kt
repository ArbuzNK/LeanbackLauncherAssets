package com.ArbuzNK.leanbacklauncher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Color as AndroidGraphicsColor
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tvprovider.media.tv.TvContractCompat
import coil.imageLoader
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.font.FontStyle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.media.SoundPool

// === МОДЕЛИ ДАННЫХ ===

@Immutable
data class LiveChannelInfo(
    val id: Long,
    val packageName: String,
    val displayName: String?,
    val programs: List<LiveProgramInfo>
)
@Immutable
data class ProviderChannelLogo(
    val id: Long,
    val logoUri: Uri,
    val intentUri: Uri?
)

@Immutable
data class ChannelProviderGroup(
    val packageName: String,
    val title: String,
    val channels: List<ProviderChannelLogo>
)

@Immutable
data class LiveProgramInfo(
    val id: Long,
    val channelId: Long,
    val channelName: String? = null,
    val title: String? = null,
    val description: String? = null,
    val posterArtUri: Uri? = null,
    val drawableResId: Int? = null,
    val intentUri: Uri? = null,
    val playbackPositionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    // Package that owns this TV recommendation; used to colour the panel from its app icon.
    val appPackageName: String? = null
) {
    val progress: Float
        get() = if (durationMillis > 0) {
            (playbackPositionMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

@Immutable
data class RecChannelInfo(
    val channelId: Long,
    val packageName: String,
    val name: String
)

@Immutable
data class PromoBanner(
    val id: String,
    val title: String,
    val brand: String,
    val description: String,
    val bannerResId: Int,
    val isVertical: Boolean = false,
    val onClick: () -> Unit = {},
    // Firebase Storage download URL. Local bannerResId remains the fallback.
    val imageUrl: String? = null,
    // Determined before the card enters the row, so its width never jumps after display.
    val aspectRatio: Float = 16f / 9f
)

/** One horizontal feed containing native recommendations and remote ad cards. */
private sealed interface RecommendationFeedItem {
    val stableKey: String

    data class Program(
        val program: LiveProgramInfo,
        override val stableKey: String
    ) : RecommendationFeedItem

    data class Advertisement(val banner: PromoBanner) : RecommendationFeedItem {
        override val stableKey: String = "ad:${banner.id}"
    }
}

@Immutable
data class TvInputItem(
    val id: String,
    val label: String,
    val iconRes: Int,
    val isConnected: Boolean,
    val intent: Intent?,
    // Set only when Android exposes a connected HDMI-CEC device name.
    val connectedDeviceName: String? = null
)

/** Precomputed focus neighbours of one app-grid cell. */
private data class GridNeighbors(
    val prev: FocusRequester?,
    val next: FocusRequester?,
    // null внутри сетки = искать соседа в соседней секции.
    val up: FocusRequester?,
    val down: FocusRequester?
)

/** Internal metadata used to turn Android TV passthrough sources into launcher tiles. */
private data class DetectedTvInput(
    val id: String,
    val originalLabel: String,
    val isHdmi: Boolean,
    val portNumber: Int?,
    val state: Int,
    val connectedDeviceName: String? = null
)

// === ПРОСТОЙ БАННЕР ЗАГРУЗКИ ===

@Composable
fun LoadingBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.loading_recommendations),
            color = Color(0xAAFFFFFF),
            fontSize = 16.sp,
            fontWeight = FontWeight.Light
        )
    }
}

// Preserve the existing Apps/Games order when toggling favorites. Favorites are
// shown in their own row, so marking an app favorite must not move it in the main row.
private fun orderLauncherApps(
    apps: List<TvAppInfo>,
    customOrder: List<String>
): List<TvAppInfo> {
    val savedPositions = customOrder.withIndex().associate { it.value to it.index }
    return apps.withIndex()
        .sortedWith(
            compareBy<IndexedValue<TvAppInfo>> {
                savedPositions[it.value.packageName] ?: Int.MAX_VALUE
            }.thenBy { it.index }
        )
        .map { it.value }
}

// A separate persisted order controls the mixed Apps + Games Favorites row.
private fun orderFavoriteApps(
    apps: List<TvAppInfo>,
    favoritePackages: Set<String>,
    favoriteCustomOrder: List<String>
): List<TvAppInfo> {
    val savedPositions = favoriteCustomOrder.withIndex().associate { it.value to it.index }
    return apps.withIndex()
        .filter { it.value.packageName in favoritePackages && it.value.icon != null }
        .distinctBy { it.value.packageName }
        .sortedWith(
            compareBy<IndexedValue<TvAppInfo>> {
                savedPositions[it.value.packageName] ?: Int.MAX_VALUE
            }.thenBy { it.index }
        )
        .map { it.value }
}

// === КАРТОЧКА ПРИЛОЖЕНИЯ ===

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCard(
    painter: Painter,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    isFastScrolling: Boolean = false,
    isMoveMode: Boolean = false,
    onFocus: (() -> Unit)? = null,
    backgroundColor: Color = Color.Transparent,
) {
    var isFocused by remember { mutableStateOf(false) }
    // TV remotes send DPAD_CENTER/ENTER key events rather than pointer long-clicks.
    // Handle the held key explicitly so long-press opens the card menu on Android TV.
    var dpadSelectDownTime by remember { mutableLongStateOf(-1L) }
    val cardShape = RoundedCornerShape(0.dp)


    val scaleAnimated by animateFloatAsState(
        targetValue = if (isFocused && !isFastScrolling) 1.15f else 1.0f,
        animationSpec = if (isFastScrolling) {
            snap()
        } else {
            tween(
                durationMillis = 180,
                easing = CubicBezierEasing(0.0f, 0.58f, 1.0f, 1.0f)
            )
        },
        label = "AppScale"
    )


    Box(
        modifier = modifier
            .zIndex(if (isFocused) 10f else 1f)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (focusState.isFocused) onFocus?.invoke()
            }
            .graphicsLayer {
                scaleX = if (isMoveMode) maxOf(scaleAnimated, 1.18f) else scaleAnimated
                scaleY = if (isMoveMode) maxOf(scaleAnimated, 1.18f) else scaleAnimated
            }
            .then(
                if (isFocused && !isFastScrolling) {
                    Modifier.shadow(
                        if (isMoveMode) 16.dp else 11.dp,
                        cardShape,
                        clip = false
                    )
                } else Modifier
            )
            .clip(cardShape)
            .background(backgroundColor, cardShape)
            .then(
                if (isMoveMode) Modifier.border(2.dp, Color(0xFF80CBC4), cardShape) else Modifier
            )
            .onPreviewKeyEvent { composeKeyEvent ->
                val keyEvent = composeKeyEvent.nativeKeyEvent
                val isSelectKey = keyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                        keyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                        keyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A

                if (!isSelectKey) {
                    false
                } else {
                    when (keyEvent.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (keyEvent.repeatCount == 0) {
                                dpadSelectDownTime = keyEvent.downTime
                            }
                            // Consume the physical key sequence so Compose's clickable
                            // does not also launch the app after a long press.
                            true
                        }

                        KeyEvent.ACTION_UP -> {
                            if (dpadSelectDownTime == keyEvent.downTime) {
                                val pressDuration = keyEvent.eventTime - keyEvent.downTime
                                if (pressDuration >= 500L) {
                                    onLongClick()
                                } else {
                                    onClick()
                                }
                            }
                            dpadSelectDownTime = -1L
                            true
                        }

                        else -> true
                    }
                }
            }
            .combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

// === КАРТОЧКА ВХОДА (HDMI / AV) ===

@Composable
fun InputCard(
    input: TvInputItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null
) {
    var isFocused by remember(input.id) { mutableStateOf(false) }

    val scaleAnimated by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1.0f,
        animationSpec = tween(durationMillis = 120),
        label = "InputCardScale_${input.id}"
    )
    val displayLabel = remember(input.label, input.connectedDeviceName) {
        val deviceName = input.connectedDeviceName
            ?.trim()
            ?.takeIf { it.isNotEmpty() && input.label.startsWith("HDMI", ignoreCase = true) }
        if (deviceName != null) "${input.label}/$deviceName" else input.label
    }
    val cardShape = RoundedCornerShape(0.dp)

    Box(
        modifier = modifier
            .width(130.dp)
            .height(74.dp)
            .zIndex(if (isFocused) 100f else 1f)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (focusState.isFocused) onFocus?.invoke()
            }
            .graphicsLayer {
                scaleX = scaleAnimated
                scaleY = scaleAnimated
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .then(
                if (isFocused) {
                    Modifier.shadow(11.dp, cardShape, clip = false)
                } else {
                    Modifier
                }
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
            .clip(cardShape)
            .background(Color.White, cardShape),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // SVG/vector icon keeps its original colors; AV must not be tinted.
            Image(
                painter = painterResource(id = input.iconRes),
                contentDescription = input.label,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 1.dp),
                contentScale = ContentScale.Fit
            )
            Text(
                text = displayLabel,
                color = Color(0xFF202124),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Предзагружаем первые уникальные баннеры и запоминаем их пропорции заранее:
 *  ширина карточки становится известна ДО её появления на экране, и ряд не дёргается.
 *  Первые 12 — в полном размере (они видны сразу), следующие 18 — крошечным декодом
 *  ради пропорций: память ТВ-приставки не трогаем, дисковый кеш с raw-данными всё равно
 *  даёт полноразмерную картинку, когда карточка доезжает до экрана. */
fun prefetchBannerSizes(context: Context, programs: Sequence<LiveProgramInfo>) {
    val imageLoader = context.imageLoader
    programs
        .mapNotNull { it.posterArtUri }
        .distinct()
        .take(30)
        .forEach { uri ->
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(600, 340)
                .crossfade(false)
                .allowHardware(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .listener(
                    onSuccess = { _, result ->
                        (result.drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
                            PosterBitmapCache.put(uri.toString(), bitmap)
                        }

                        val w = result.drawable.intrinsicWidth
                        val h = result.drawable.intrinsicHeight

                        if (w > 0 && h > 0) {
                            PosterAspectRatioCache.put(
                                uri.toString(),
                                w.toFloat() / h.toFloat()
                            )
                        }
                    }
                )
                .build()

            imageLoader.enqueue(request)
        }
}

/** Plays the selected source-switch sound; it does not delay the source Intent. */
private fun playSourceTransitionSound(context: Context) {
    val player = try {
        MediaPlayer.create(context.applicationContext, R.raw.xboxone)
    } catch (error: Exception) {
        Log.w("SourceTransition", "Не удалось подготовить звук переключения источника", error)
        null
    } ?: return

    player.setOnCompletionListener { completedPlayer ->
        runCatching { completedPlayer.release() }
    }
    player.setOnErrorListener { failedPlayer, what, extra ->
        Log.w("SourceTransition", "Ошибка воспроизведения звука: what=$what, extra=$extra")
        runCatching { failedPlayer.release() }
        true
    }
    try {
        player.start()
    } catch (error: Exception) {
        Log.w("SourceTransition", "Не удалось запустить звук переключения источника", error)
        runCatching { player.release() }
    }
}

// === MAIN ACTIVITY ===

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission("com.android.providers.tv.permission.READ_EPG_DATA") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("com.android.providers.tv.permission.READ_EPG_DATA"), 1001)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val sharedPrefs = getSharedPreferences("launcher_settings", MODE_PRIVATE)

        val savedHidden = sharedPrefs.getStringSet("hidden_packages", emptySet()) ?: emptySet()
        val savedEnabled = sharedPrefs.getStringSet("enabled_channel_ids", null)?.mapNotNull { it.toLongOrNull() }
        val savedMix = sharedPrefs.getStringSet("mix_channel_ids", null)?.mapNotNull { it.toLongOrNull() }

        setContent {
            var isSettingsOpen by remember { mutableStateOf(false) }
            var showRecommendations by remember {
                mutableStateOf(sharedPrefs.getBoolean("show_recommendations", true))
            }
            var maxAppsPerRow by remember {
                mutableIntStateOf(sharedPrefs.getInt("max_apps_per_row", 10).coerceIn(4, 50))
            }
            var inputSwitchSoundEnabled by remember {
                mutableStateOf(sharedPrefs.getBoolean("input_switch_sound_enabled", false))
            }
            var favoritePackages by remember {
                mutableStateOf(
                    sharedPrefs.getStringSet("favorite_packages", LinkedHashSet<String>())
                        ?.toSet().orEmpty()
                )
            }
            var providerChannelGroups by remember {
                mutableStateOf<List<ChannelProviderGroup>>(emptyList())
            }
            var selectedChannelProviders by remember {
                mutableStateOf(
                    sharedPrefs.getStringSet("selected_channel_providers", emptySet())
                        ?.toSet().orEmpty()
                )
            }
            var favoriteCustomOrder by remember {
                mutableStateOf(
                    sharedPrefs.getString("favorite_custom_order", null)
                        ?.split(",")?.filter { it.isNotBlank() }.orEmpty()
                )
            }
            var appsCustomOrder by remember {
                mutableStateOf(
                    sharedPrefs.getString("apps_custom_order", null)
                        ?.split(",")?.filter { it.isNotBlank() }.orEmpty()
                )
            }
            var gamesCustomOrder by remember {
                mutableStateOf(
                    sharedPrefs.getString("games_custom_order", null)
                        ?.split(",")?.filter { it.isNotBlank() }.orEmpty()
                )
            }
            val defaultSectionOrder = listOf("recommendations", "favorites", "apps", "inputs", "games")
            var sectionOrder by remember {
                val savedOrder = sharedPrefs.getString("section_order", null)
                    ?.split(",")
                    ?.filter { it in defaultSectionOrder }
                    ?.distinct()
                    .orEmpty()
                // Add Favorites to an existing installation immediately before Apps,
                // while preserving the user's previously saved order for other sections.
                val migratedOrder = when {
                    savedOrder.isEmpty() -> defaultSectionOrder
                    "favorites" in savedOrder -> savedOrder
                    else -> savedOrder.toMutableList().apply {
                        val appsIndex = indexOf("apps").takeIf { it >= 0 } ?: size
                        add(appsIndex, "favorites")
                    }
                }
                mutableStateOf(migratedOrder + defaultSectionOrder.filterNot { it in migratedOrder })
            }
            var currentBgUri by remember {
                mutableStateOf(sharedPrefs.getString("bg_uri", null))
            }

            var isRecommendationsLoading by remember { mutableStateOf(true) }
            val installedApps = remember { mutableStateListOf<TvAppInfo>() }
            var gamePackageNames by remember { mutableStateOf<Set<String>>(emptySet()) }
            val tvInputs = remember { mutableStateListOf<TvInputItem>() }

            val hiddenApps = remember { mutableStateListOf<String>().apply { addAll(savedHidden) } }
            val enabledChannelIds = remember {
                mutableStateListOf<Long>().apply {
                    if (savedEnabled != null) addAll(savedEnabled)
                }
            }
            val mixChannelIds = remember {
                mutableStateListOf<Long>().apply {
                    if (savedMix != null) addAll(savedMix) else addAll(enabledChannelIds)
                }
            }

            var availableRecChannels by remember { mutableStateOf<List<RecChannelInfo>>(emptyList()) }
            var liveChannels by remember { mutableStateOf<List<LiveChannelInfo>>(emptyList()) }

            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val coroutineScope = rememberCoroutineScope()

            fun reloadInputs() {
                val inputs = getTvInputs(context)
                // Avoid rebuilding LazyRow when a callback reports an unrelated or
                // unchanged input. Compare visible fields, not newly-created Intent objects.
                val unchanged = tvInputs.size == inputs.size && tvInputs.indices.all { index ->
                    val old = tvInputs[index]
                    val new = inputs[index]
                    old.id == new.id &&
                            old.label == new.label &&
                            old.iconRes == new.iconRes &&
                            old.isConnected == new.isConnected &&
                            old.connectedDeviceName == new.connectedDeviceName
                }
                if (!unchanged) {
                    tvInputs.clear()
                    tvInputs.addAll(inputs)
                }
            }

            // Listen for live TV input changes instead of waiting for ON_RESUME.
            // HDMI-CEC child inputs can be added/removed or renamed when a device
            // is connected/disconnected; state callbacks also refresh the row.
            DisposableEffect(context) {
                val manager = context.getSystemService(TV_INPUT_SERVICE) as? TvInputManager
                if (manager == null) {
                    onDispose { }
                } else {
                    val callback = object : TvInputManager.TvInputCallback() {
                        override fun onInputAdded(inputId: String) = reloadInputs()
                        override fun onInputRemoved(inputId: String) = reloadInputs()
                        override fun onInputUpdated(inputId: String) = reloadInputs()
                        override fun onInputStateChanged(inputId: String, state: Int) = reloadInputs()
                        override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) = reloadInputs()
                    }
                    manager.registerCallback(callback, Handler(Looper.getMainLooper()))
                    onDispose { manager.unregisterCallback(callback) }
                }
            }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    providerChannelGroups = loadProviderChannelGroups(context)
                    val apps = getInstalledTvApps()
                    val games = getGamePackageNames(apps)
                    val inputs = getTvInputs(context)
                    withContext(Dispatchers.Main) {
                        installedApps.clear()
                        installedApps.addAll(apps)
                        gamePackageNames = games
                        tvInputs.clear()
                        tvInputs.addAll(inputs)
                        // Re-read after the initial async scan so a connection callback
                        // that arrived during startup cannot be overwritten by stale data.
                        reloadInputs()
                    }
                }
            }

            var reloadJob by remember { mutableStateOf<Job?>(null) }

            fun triggerReloadRecommendations() {
                reloadJob?.cancel()
                reloadJob = coroutineScope.launch(Dispatchers.IO) {
                    if (!showRecommendations) {
                        withContext(Dispatchers.Main) {
                            liveChannels = emptyList()
                            isRecommendationsLoading = false
                        }
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        isRecommendationsLoading = true
                    }

                    val startTime = System.currentTimeMillis()

                    try {
                        val channels = discoverRecommendationChannels(context)
                        val currentEnabled = enabledChannelIds.toList()

                        val savedSet = sharedPrefs.getStringSet("enabled_channel_ids", null)
                        val knownSet = sharedPrefs.getStringSet("all_known_channel_ids", null)

                        val effectiveEnabledIds = mutableListOf<Long>()

                        if (savedSet == null || knownSet == null) {
                            val newIds = channels.map { it.channelId }
                            effectiveEnabledIds.addAll(newIds)

                            withContext(Dispatchers.Main) {
                                availableRecChannels = channels
                                enabledChannelIds.clear()
                                enabledChannelIds.addAll(newIds)
                                mixChannelIds.clear()
                                mixChannelIds.addAll(newIds)
                            }

                            sharedPrefs.edit().putStringSet(
                                "all_known_channel_ids",
                                newIds.map { it.toString() }.toSet()
                            ).apply()
                        } else {
                            val knownIds = knownSet.mapNotNull { it.toLongOrNull() }.toSet()
                            val brandNewChannels = channels.filter { !knownIds.contains(it.channelId) }

                            effectiveEnabledIds.addAll(currentEnabled)

                            if (brandNewChannels.isNotEmpty()) {
                                val newIds = brandNewChannels.map { it.channelId }
                                effectiveEnabledIds.addAll(newIds)

                                withContext(Dispatchers.Main) {
                                    availableRecChannels = channels
                                    enabledChannelIds.addAll(newIds)
                                    mixChannelIds.addAll(newIds)
                                }

                                val updatedKnown = (knownIds + newIds).map { it.toString() }.toSet()
                                sharedPrefs.edit().putStringSet("all_known_channel_ids", updatedKnown).apply()
                            } else {
                                withContext(Dispatchers.Main) {
                                    availableRecChannels = channels
                                }
                            }
                        }

                        sharedPrefs.edit().putStringSet("enabled_channel_ids", enabledChannelIds.map { it.toString() }.toSet()).apply()
                        sharedPrefs.edit().putStringSet("mix_channel_ids", mixChannelIds.map { it.toString() }.toSet()).apply()

                        val fetched = fetchLiveChannels(context, effectiveEnabledIds).toMutableList()

                        if (effectiveEnabledIds.contains(-1L)) {
                            val watchNextProgs = fetchWatchNextPrograms(context)
                            if (watchNextProgs.isNotEmpty()) {
                                fetched.add(
                                    0,
                                    LiveChannelInfo(
                                        id = -1L,
                                        packageName = "system.watch_next",
                                        displayName = context.getString(R.string.continue_watching),
                                        programs = watchNextProgs
                                    )
                                )
                            }
                        }

                        withContext(Dispatchers.Main) {
                            liveChannels = fetched
                        }

                        prefetchBannerSizes(
                            context,
                            fetched.asSequence().flatMap { it.programs.asSequence() }
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("LauncherData", "Ошибка получения рекомендаций", e)
                        withContext(Dispatchers.Main) {
                            liveChannels = emptyList()
                        }
                    }

                    // Не удерживаем экран загрузки 5 секунд, когда данные уже готовы.
                    // Короткая минимальная длительность нужна только против мигания индикатора.
                    val elapsedTime = System.currentTimeMillis() - startTime
                    val remainingDelay = 250L - elapsedTime
                    if (remainingDelay > 0) delay(remainingDelay)

                    withContext(Dispatchers.Main) {
                        isRecommendationsLoading = false
                    }
                }
            }

            DisposableEffect(showRecommendations) {
                val contentResolver = context.contentResolver

                var observerReloadJob: Job? = null
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        super.onChange(selfChange)
                        observerReloadJob?.cancel()
                        observerReloadJob = coroutineScope.launch {
                            delay(300L)
                            triggerReloadRecommendations()
                        }
                    }
                }

                try {
                    contentResolver.registerContentObserver(TvContractCompat.Channels.CONTENT_URI, true, observer)
                    contentResolver.registerContentObserver(TvContractCompat.WatchNextPrograms.CONTENT_URI, true, observer)
                } catch (e: Exception) {
                    Log.e("LauncherData", "Ошибка подписки: ${e.message}")
                }

                onDispose {
                    observerReloadJob?.cancel()
                    try { contentResolver.unregisterContentObserver(observer) } catch (e: Exception) {}
                }
            }

            val wallpaperLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    try {
                        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    } catch (e: Exception) {}
                    val uriString = uri.toString()
                    sharedPrefs.edit().putString("bg_uri", uriString).apply()
                    currentBgUri = uriString
                }
            }

            LaunchedEffect(showRecommendations) {
                triggerReloadRecommendations()
            }

            var isFirstLaunch by remember { mutableStateOf(true) }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        reloadInputs()
                        if (isFirstLaunch) {
                            isFirstLaunch = false
                        } else {
                            triggerReloadRecommendations()
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            // Games live in their own row and must not also appear in the Apps row.
            // Hidden packages remain hidden in both rows.
            val visibleApps by remember {
                derivedStateOf {
                    orderLauncherApps(
                        apps = installedApps.filter {
                            it.packageName !in hiddenApps && it.packageName !in gamePackageNames
                        },
                        customOrder = appsCustomOrder
                    )
                }
            }
            val visibleGames by remember {
                derivedStateOf {
                    orderLauncherApps(
                        apps = installedApps.filter {
                            it.packageName in gamePackageNames && it.packageName !in hiddenApps
                        },
                        customOrder = gamesCustomOrder
                    )
                }
            }

            val visibleProviderGroups by remember {
                derivedStateOf {
                    providerChannelGroups.filter {
                        it.packageName in selectedChannelProviders
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                MainScreen(
                    channels = liveChannels,
                    mixChannelIds = mixChannelIds,
                    tvInputs = tvInputs,
                    apps = visibleApps,
                    games = visibleGames,
                    providerGroups = visibleProviderGroups,
                    currentBgUri = currentBgUri,
                    showRecommendations = showRecommendations,
                    maxAppsPerRow = maxAppsPerRow,
                    sectionOrder = sectionOrder,
                    inputSwitchSoundEnabled = inputSwitchSoundEnabled,
                    favoritePackages = favoritePackages,
                    favoriteCustomOrder = favoriteCustomOrder,
                    onToggleFavorite = { packageName ->
                        val oldFavorites = favoritePackages
                        val currentFavoriteOrder = orderFavoriteApps(
                            apps = (visibleApps + visibleGames).distinctBy { it.packageName },
                            favoritePackages = oldFavorites,
                            favoriteCustomOrder = favoriteCustomOrder
                        ).map { it.packageName }
                        val updated = oldFavorites.toMutableSet()
                        val wasFavorite = packageName in updated
                        if (!updated.add(packageName)) updated.remove(packageName)
                        favoritePackages = updated

                        // Seed an order for favorites created before this setting existed,
                        // then append a newly favorited app. Removing a favorite keeps its slot.
                        if (!wasFavorite) {
                            val updatedOrder = favoriteCustomOrder.toMutableList()
                            currentFavoriteOrder.forEach { pkg ->
                                if (pkg !in updatedOrder) updatedOrder.add(pkg)
                            }
                            if (packageName !in updatedOrder) updatedOrder.add(packageName)
                            favoriteCustomOrder = updatedOrder
                            sharedPrefs.edit()
                                .putString("favorite_custom_order", updatedOrder.joinToString(","))
                                .apply()
                        }
                        sharedPrefs.edit().putStringSet("favorite_packages", HashSet(updated)).apply()
                    },
                    onMoveApp = { packageName, direction, isGame, originSection ->
                        if (originSection == "favorites") {
                            // In Favorites, move according to the actual mixed Apps + Games row,
                            // rather than trying to reorder only the source app category.
                            val favoriteDisplay = orderFavoriteApps(
                                apps = (visibleApps + visibleGames).distinctBy { it.packageName },
                                favoritePackages = favoritePackages,
                                favoriteCustomOrder = favoriteCustomOrder
                            )
                            val currentIndex = favoriteDisplay.indexOfFirst { it.packageName == packageName }
                            val targetIndex = currentIndex + direction
                            if (currentIndex >= 0 && targetIndex in favoriteDisplay.indices) {
                                val displayedOrder = favoriteDisplay.map { it.packageName }.toMutableList()
                                val moving = displayedOrder.removeAt(currentIndex)
                                displayedOrder.add(targetIndex, moving)

                                val savedOrder = favoriteCustomOrder.toMutableList()
                                favoriteDisplay.forEach { item ->
                                    if (item.packageName !in savedOrder) savedOrder.add(item.packageName)
                                }
                                val displayedSet = favoriteDisplay.map { it.packageName }.toSet()
                                val visibleSlots = savedOrder.indices.filter { savedOrder[it] in displayedSet }
                                displayedOrder.forEachIndexed { displayIndex, pkg ->
                                    visibleSlots.getOrNull(displayIndex)?.let { slot -> savedOrder[slot] = pkg }
                                }
                                favoriteCustomOrder = savedOrder
                                sharedPrefs.edit()
                                    .putString("favorite_custom_order", savedOrder.joinToString(","))
                                    .apply()
                            }
                        } else {
                            val categoryList = if (isGame) visibleGames else visibleApps
                            val currentIndex = categoryList.indexOfFirst { it.packageName == packageName }
                            val targetIndex = currentIndex + direction
                            if (currentIndex >= 0 && targetIndex in categoryList.indices) {
                                // Reorder the complete Apps or Games list. Being a favorite no
                                // longer blocks crossing the old favorite/non-favorite boundary.
                                val displayedOrder = categoryList.map { it.packageName }.toMutableList()
                                val moving = displayedOrder.removeAt(currentIndex)
                                displayedOrder.add(targetIndex, moving)

                                val savedOrder = (if (isGame) gamesCustomOrder else appsCustomOrder).toMutableList()
                                val categoryInstalledPackages = installedApps
                                    .filter { (it.packageName in gamePackageNames) == isGame }
                                    .map { it.packageName }
                                categoryInstalledPackages.forEach { pkg ->
                                    if (pkg !in savedOrder) savedOrder.add(pkg)
                                }
                                val displayedSet = categoryList.map { it.packageName }.toSet()
                                val visibleSlots = savedOrder.indices.filter { savedOrder[it] in displayedSet }
                                displayedOrder.forEachIndexed { displayIndex, pkg ->
                                    visibleSlots.getOrNull(displayIndex)?.let { slot -> savedOrder[slot] = pkg }
                                }

                                if (isGame) {
                                    gamesCustomOrder = savedOrder
                                    sharedPrefs.edit().putString("games_custom_order", savedOrder.joinToString(",")).apply()
                                } else {
                                    appsCustomOrder = savedOrder
                                    sharedPrefs.edit().putString("apps_custom_order", savedOrder.joinToString(",")).apply()
                                }
                            }
                        }
                    },
                    isRecommendationsLoading = isRecommendationsLoading,
                    onOpenSettings = { isSettingsOpen = true },
                    onAppClick = { packageName ->
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) startActivity(launchIntent)
                    },
                    onProgramClick = { intentUri ->
                        if (intentUri != null) {
                            try {
                                val intent = Intent.parseUri(intentUri.toString(), Intent.URI_INTENT_SCHEME).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, intentUri).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(intent)
                                } catch (ex: Exception) {
                                    val launchIntent = packageManager.getLaunchIntentForPackage("org.smarttube.stable")
                                    if (launchIntent != null) startActivity(launchIntent)
                                }
                            }
                        } else {
                            val launchIntent = packageManager.getLaunchIntentForPackage("org.smarttube.stable")
                            if (launchIntent != null) startActivity(launchIntent)
                        }
                    }
                )

                if (isSettingsOpen) {
                    Dialog(
                        onDismissRequest = { isSettingsOpen = false },
                        properties = DialogProperties(
                            usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = false
                        )
                    ) {
                        SettingsScreen(
                            showRecommendations = showRecommendations,
                            onToggleRecommendations = { enabled ->
                                showRecommendations = enabled
                                sharedPrefs.edit()
                                    .putBoolean("show_recommendations", enabled)
                                    .apply()
                                triggerReloadRecommendations()
                            },
                            maxAppsPerRow = maxAppsPerRow,
                            onMaxAppsPerRowChange = { requestedValue ->
                                val value = requestedValue.coerceIn(4, 50)
                                maxAppsPerRow = value
                                sharedPrefs.edit()
                                    .putInt("max_apps_per_row", value)
                                    .apply()
                            },
                            sectionOrder = sectionOrder,
                            onMoveSection = { sectionKey, newIndex ->
                                val updatedOrder = sectionOrder.toMutableList()
                                val oldIndex = updatedOrder.indexOf(sectionKey)

                                if (
                                    oldIndex >= 0 &&
                                    newIndex in updatedOrder.indices &&
                                    oldIndex != newIndex
                                ) {
                                    updatedOrder.removeAt(oldIndex)
                                    updatedOrder.add(newIndex, sectionKey)
                                    sectionOrder = updatedOrder

                                    sharedPrefs.edit()
                                        .putString(
                                            "section_order",
                                            updatedOrder.joinToString(",")
                                        )
                                        .apply()
                                }
                            },
                            inputSwitchSoundEnabled = inputSwitchSoundEnabled,
                            onToggleInputSwitchSound = { enabled ->
                                inputSwitchSoundEnabled = enabled
                                sharedPrefs.edit()
                                    .putBoolean("input_switch_sound_enabled", enabled)
                                    .apply()
                            },
                            availableRecChannels = availableRecChannels,
                            enabledChannelIds = enabledChannelIds,
                            mixChannelIds = mixChannelIds,
                            onToggleChannel = { channelId ->
                                if (enabledChannelIds.contains(channelId)) {
                                    enabledChannelIds.remove(channelId)
                                } else {
                                    enabledChannelIds.add(channelId)
                                }

                                sharedPrefs.edit()
                                    .putStringSet(
                                        "enabled_channel_ids",
                                        enabledChannelIds.map { it.toString() }.toSet()
                                    )
                                    .apply()
                                triggerReloadRecommendations()
                            },
                            onToggleMixChannel = { channelId ->
                                if (mixChannelIds.contains(channelId)) {
                                    mixChannelIds.remove(channelId)
                                } else {
                                    mixChannelIds.add(channelId)
                                }

                                sharedPrefs.edit()
                                    .putStringSet(
                                        "mix_channel_ids",
                                        mixChannelIds.map { it.toString() }.toSet()
                                    )
                                    .apply()
                            },
                            installedApps = installedApps,
                            hiddenApps = hiddenApps,
                            onToggleAppVisibility = { packageName ->
                                if (hiddenApps.contains(packageName)) {
                                    hiddenApps.remove(packageName)
                                } else {
                                    hiddenApps.add(packageName)
                                }

                                sharedPrefs.edit()
                                    .putStringSet("hidden_packages", hiddenApps.toSet())
                                    .apply()
                            },
                            channelProviders = providerChannelGroups,
                            selectedChannelProviders = selectedChannelProviders,
                            onToggleChannelProvider = { packageName ->
                                val updated = selectedChannelProviders.toMutableSet()

                                if (!updated.add(packageName)) {
                                    updated.remove(packageName)
                                }

                                selectedChannelProviders = updated

                                sharedPrefs.edit()
                                    .putStringSet("selected_channel_providers", updated)
                                    .apply()
                            },
                            hasCustomWallpaper = currentBgUri != null,
                            onSelectWallpaper = {
                                wallpaperLauncher.launch(arrayOf("image/*"))
                            },
                            onResetWallpaper = {
                                sharedPrefs.edit().remove("bg_uri").apply()
                                currentBgUri = null
                            },
                            onBack = { isSettingsOpen = false }
                        )
                    }
                }
            }
        }
    }

    /**
     * Opens a real channel from the built-in tuner when one is available.
     * If the TV provider has no readable channel rows yet, the standard TV app
     * receives Channels.CONTENT_URI and can tune its default/last channel.
     */
    private fun buildDigitalTvIntent(context: Context, tunerInputId: String): Intent {
        val channelUri: Uri? = try {
            context.contentResolver.query(
                TvContract.buildChannelsUriForInput(tunerInputId),
                arrayOf("_id"),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex("_id")
                if (idIndex >= 0 && cursor.moveToFirst()) {
                    TvContract.buildChannelUri(cursor.getLong(idIndex))
                } else {
                    null
                }
            }
        } catch (error: Exception) {
            Log.w("TvInput", "Не удалось прочитать каналы встроенного тюнера", error)
            null
        }

        return Intent(
            Intent.ACTION_VIEW,
            channelUri ?: TvContract.Channels.CONTENT_URI
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun getTvInputs(context: Context): List<TvInputItem> {
        val tvInputManager = context.getSystemService(TV_INPUT_SERVICE) as? TvInputManager
            ?: return emptyList()

        val systemInputs = tvInputManager.tvInputList
        // Built-in antenna/cable/satellite TV tuners are TYPE_TUNER, not passthrough inputs.
        // Keep one Sony-style "Digital" tile; the TV app manages tuner modes and connectors.
        val tunerInput = systemInputs
            .filter { it.type == TvInputInfo.TYPE_TUNER }
            .sortedWith(
                compareBy<TvInputInfo> {
                    val name = it.loadLabel(context).toString().lowercase(Locale.ROOT)
                    when {
                        name.contains("digital") -> 0
                        name.contains("dvb") || name.contains("tuner") -> 1
                        name == "tv" || name.contains("television") -> 2
                        else -> 3
                    }
                }.thenBy { it.loadLabel(context).toString().lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
            .firstOrNull()

        val detected = mutableListOf<DetectedTvInput>()
        val portNumberRegex = Regex("""hdmi[\s._/\-]*(\d+)""", RegexOption.IGNORE_CASE)
        val avRegex = Regex(
            """(^|[^a-z0-9])av(?:\s*\d+)?([^a-z0-9]|$)""",
            RegexOption.IGNORE_CASE
        )
        val genericDeviceNameRegex = Regex(
            """^(hdmi(?:\s*\d+)?(?:\s*/\s*mhl)?|mhl|unknown|unknown device|tv input|input|digital|dvb|tuner|television)$""",
            RegexOption.IGNORE_CASE
        )

        // Read physical inputs only. HDMI-CEC devices are child TV inputs whose
        // parentId points back to the physical HDMI port; they are used as its suffix,
        // never shown as extra ports themselves.
        for (input in systemInputs) {
            if (!input.isPassthroughInput || input.parentId != null) continue

            val id = input.id
            val label = input.loadLabel(context).toString().trim()
            val searchable = "$label $id"
            val lower = searchable.lowercase(Locale.ROOT)
            val isHdmi = input.type == TvInputInfo.TYPE_HDMI || lower.contains("hdmi")
            val isAv = lower.contains("composite") ||
                    lower.contains("component") ||
                    lower.contains("cvbs") ||
                    lower.contains("s-video") ||
                    avRegex.containsMatchIn(searchable)

            if (!isHdmi && !isAv) continue

            // Read the port number from the visible label only. Some TV firmwares
            // include large internal IDs such as "HDMI 100004"; those are not port numbers.
            val portNumber = if (isHdmi) {
                portNumberRegex.find(label)
                    ?.groupValues?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..16 }
            } else {
                null
            }
            val state = tvInputManager.getInputState(id)

            val connectedDeviceName = if (isHdmi) {
                systemInputs.asSequence()
                    .filter { child -> child.parentId == id && child.isPassthroughInput }
                    .filter { child ->
                        tvInputManager.getInputState(child.id) != TvInputManager.INPUT_STATE_DISCONNECTED
                    }
                    .map { child -> child.loadLabel(context).toString().trim() }
                    .firstOrNull { deviceName ->
                        deviceName.isNotBlank() &&
                                !genericDeviceNameRegex.matches(deviceName) &&
                                !deviceName.equals(label, ignoreCase = true)
                    }
            } else {
                null
            }

            detected += DetectedTvInput(
                id = id,
                originalLabel = label,
                isHdmi = isHdmi,
                portNumber = portNumber,
                state = state,
                connectedDeviceName = connectedDeviceName
            )
        }

        val results = mutableListOf<TvInputItem>()

        tunerInput?.let { input ->
            val label = input.loadLabel(context).toString().trim()
            results += TvInputItem(
                id = "digital:${input.id}",
                label = "Digital",
                iconRes = R.drawable.ic_digital_tv,
                isConnected = tvInputManager.getInputState(input.id) != TvInputManager.INPUT_STATE_DISCONNECTED,
                intent = buildDigitalTvIntent(context, input.id)
            )
            Log.d("TvInput", "Digital tuner exposed as '$label' (id=${input.id})")
        }

        val hdmiCandidates = detected
            .filter { it.isHdmi }
            .sortedWith(
                compareBy<DetectedTvInput> { it.portNumber ?: Int.MAX_VALUE }
                    .thenBy { it.originalLabel.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )

        // Keep explicit port numbers where possible; fill gaps for inputs whose system
        // labels only say "HDMI". The final list is sorted as HDMI 1, HDMI 2, HDMI 3...
        val usedPortNumbers = mutableSetOf<Int>()
        val numberedHdmi = mutableListOf<Pair<DetectedTvInput, Int>>()
        var nextFreeNumber = 1
        for (candidate in hdmiCandidates) {
            var number = candidate.portNumber?.takeIf { it > 0 && it !in usedPortNumbers }
            if (number == null) {
                while (nextFreeNumber in usedPortNumbers) nextFreeNumber++
                number = nextFreeNumber
            }
            usedPortNumbers += number
            numberedHdmi += candidate to number
        }

        for ((candidate, number) in numberedHdmi.sortedBy { it.second }) {
            val intent = Intent(
                Intent.ACTION_VIEW,
                TvContract.buildChannelUriForPassthroughInput(candidate.id)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            results += TvInputItem(
                id = candidate.id,
                label = "HDMI $number",
                iconRes = R.drawable.ic_hdmi,
                isConnected = candidate.state != TvInputManager.INPUT_STATE_DISCONNECTED,
                intent = intent,
                connectedDeviceName = candidate.connectedDeviceName
            )
        }

        val avCandidates = detected
            .filter { !it.isHdmi }
            .distinctBy { it.id }
            .sortedWith(
                compareBy<DetectedTvInput> { it.originalLabel.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )

        for ((index, candidate) in avCandidates.withIndex()) {
            val intent = Intent(
                Intent.ACTION_VIEW,
                TvContract.buildChannelUriForPassthroughInput(candidate.id)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val avLabel = if (index == 0) "AV" else "AV ${index + 1}"
            results += TvInputItem(
                id = candidate.id,
                label = avLabel,
                iconRes = R.drawable.ic_av,
                isConnected = candidate.state != TvInputManager.INPUT_STATE_DISCONNECTED,
                intent = intent
            )
        }

        return results
    }

    private fun getInstalledTvApps(): List<TvAppInfo> {
        val appsList = mutableListOf<TvAppInfo>()
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val myPackageName = packageName

        for (info in resolveInfos) {
            val packageName = info.activityInfo.packageName
            if (packageName == myPackageName) continue
            val label = info.loadLabel(packageManager).toString()
            val icon = info.activityInfo.loadBanner(packageManager) ?: info.loadIcon(packageManager)
            appsList.add(TvAppInfo(name = label, packageName = packageName, icon = icon))
        }
        return appsList
    }

    /**
     * Finds game applications among the TV-launchable apps.
     * Newer Android versions expose ApplicationInfo.category; older versions use FLAG_IS_GAME.
     */
    private fun getGamePackageNames(apps: List<TvAppInfo>): Set<String> {
        val gamePackages = mutableSetOf<String>()

        for (app in apps) {
            val isGame = try {
                val appInfo = packageManager.getApplicationInfo(app.packageName, 0)
                val legacyGameFlag = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                val gameCategory = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        appInfo.category == ApplicationInfo.CATEGORY_GAME
                gameCategory || legacyGameFlag
            } catch (e: Exception) {
                false
            }

            if (isGame) gamePackages.add(app.packageName)
        }

        return gamePackages
    }
    @SuppressLint("RestrictedApi")
    private fun discoverRecommendationChannels(
        context: Context
    ): List<RecChannelInfo> {
        val result = mutableListOf<RecChannelInfo>()
        val pm = context.packageManager
        var hasTvirl = false

        result.add(RecChannelInfo(channelId = -1L, packageName = "system.watch_next", name = "Продолжить просмотр"))

        try {
            val cursor = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf("_id", "package_name", "display_name"),
                null, null, null
            )
            cursor?.use { c ->
                val idIndex = c.getColumnIndex("_id")
                val pkgIndex = c.getColumnIndex("package_name")
                val nameIndex = c.getColumnIndex("display_name")

                while (c.moveToNext()) {
                    val channelId = if (idIndex != -1) c.getLong(idIndex) else continue
                    val pkg = if (pkgIndex != -1 && !c.isNull(pkgIndex)) c.getString(pkgIndex) else ""
                    val channelName = if (nameIndex != -1 && !c.isNull(nameIndex)) c.getString(nameIndex) else "Канал"

                    if (pkg.contains("tvirl", ignoreCase = true)) {
                        hasTvirl = true
                        continue
                    }

                    val appLabel = if (pkg.isNotEmpty()) {
                        try {
                            val appInfo = pm.getApplicationInfo(pkg, 0)
                            pm.getApplicationLabel(appInfo).toString()
                        } catch (e: Exception) { "" }
                    } else ""

                    val fullTitle = when {
                        appLabel.isNotEmpty() && !channelName.contains(appLabel, ignoreCase = true) -> "$appLabel • $channelName"
                        channelName.isNotEmpty() -> channelName
                        else -> context.getString(
                            R.string.channel_number,
                            channelId
                        )
                    }

                    result.add(RecChannelInfo(channelId = channelId, packageName = pkg, name = fullTitle))
                }
            }
        } catch (e: Exception) {
            Log.e("TvProviderDebug", "Ошибка: ${e.message}")
        }

        if (hasTvirl) {
            result.add(0, RecChannelInfo(channelId = -999L, packageName = "com.tvirl.app", name = "TViRL"))
        }

        return result
    }

    @SuppressLint("RestrictedApi")
    private fun fetchWatchNextPrograms(context: Context): List<LiveProgramInfo> {
        val watchNextList = mutableListOf<LiveProgramInfo>()

        try {
            val cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    "_id", "title", "short_description", "poster_art_uri", "thumbnail_uri",
                    "intent_uri", "last_playback_position_millis", "duration_millis", "package_name"
                ),
                null, null, null
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex("_id")
                val titleIdx = c.getColumnIndex("title")
                val descIdx = c.getColumnIndex("short_description")
                val posterIdx = c.getColumnIndex("poster_art_uri")
                val thumbIdx = c.getColumnIndex("thumbnail_uri")
                val intentIdx = c.getColumnIndex("intent_uri")
                val posIdx = c.getColumnIndex("last_playback_position_millis")
                val durIdx = c.getColumnIndex("duration_millis")
                val pkgIdx = c.getColumnIndex("package_name")

                while (c.moveToNext()) {
                    val id = if (idIdx != -1) c.getLong(idIdx) else 0L
                    val title = if (titleIdx != -1 && !c.isNull(titleIdx)) c.getString(titleIdx) else null
                    val desc = if (descIdx != -1 && !c.isNull(descIdx)) c.getString(descIdx) else null
                    val pkgName = if (pkgIdx != -1 && !c.isNull(pkgIdx)) c.getString(pkgIdx) else ""

                    val posterStr = if (posterIdx != -1 && !c.isNull(posterIdx)) c.getString(posterIdx) else null
                    val thumbStr = if (thumbIdx != -1 && !c.isNull(thumbIdx)) c.getString(thumbIdx) else null

                    val imageUriStr = when {
                        !posterStr.isNullOrEmpty() -> posterStr
                        !thumbStr.isNullOrEmpty() -> thumbStr
                        else -> null
                    }
                    val posterUri = imageUriStr?.let { Uri.parse(it) }

                    val intentStr = if (intentIdx != -1 && !c.isNull(intentIdx)) c.getString(intentIdx) else null
                    val intentUri = if (!intentStr.isNullOrEmpty()) Uri.parse(intentStr) else null

                    val position = if (posIdx != -1 && !c.isNull(posIdx)) c.getLong(posIdx) else 0L
                    val duration = if (durIdx != -1 && !c.isNull(durIdx)) c.getLong(durIdx) else 0L

                    val isTvirl = pkgName.contains("tvirl", ignoreCase = true)
                    val isFinished = duration > 0L && (position.toFloat() / duration.toFloat()) > 0.98f

                    if (!title.isNullOrEmpty() && !isFinished) {
                        watchNextList.add(
                            LiveProgramInfo(
                                id = id,
                                channelId = -1L,
                                channelName = if (isTvirl) "TViRL • Недавние" else "Продолжить просмотр",
                                title = title,
                                description = desc,
                                posterArtUri = posterUri,
                                intentUri = intentUri,
                                playbackPositionMillis = position,
                                durationMillis = duration,
                                appPackageName = pkgName.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TvProviderDebug", "Ошибка чтения Watch Next: ${e.message}")
        }

        return watchNextList
    }

    @SuppressLint("RestrictedApi")
    private fun fetchLiveChannels(context: Context, enabledChannelIds: List<Long>): List<LiveChannelInfo> {
        if (enabledChannelIds.isEmpty()) return emptyList()

        val resultChannels = mutableListOf<LiveChannelInfo>()
        val isTvirlEnabled = enabledChannelIds.contains(-999L)

        try {
            val cursor = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf("_id", "package_name", "display_name"),
                null, null, null
            )

            cursor?.use { c ->
                val idIndex = c.getColumnIndex("_id")
                val pkgIndex = c.getColumnIndex("package_name")
                val nameIndex = c.getColumnIndex("display_name")

                while (c.moveToNext()) {
                    val channelId = if (idIndex != -1) c.getLong(idIndex) else continue
                    val pkg = if (pkgIndex != -1 && !c.isNull(pkgIndex)) c.getString(pkgIndex) else ""
                    val channelName = if (nameIndex != -1 && !c.isNull(nameIndex)) c.getString(nameIndex) else "Рекомендации"

                    val isTvirlChannel = pkg.contains("tvirl", ignoreCase = true)
                    val isEnabled = if (isTvirlChannel) isTvirlEnabled else enabledChannelIds.contains(channelId)

                    if (isEnabled) {
                        val programs = fetchProgramsForChannel(
                            context,
                            channelId,
                            if (isTvirlChannel) "TViRL" else channelName,
                            pkg
                        )

                        if (programs.isNotEmpty()) {
                            resultChannels.add(
                                LiveChannelInfo(
                                    id = channelId,
                                    packageName = pkg,
                                    displayName = if (isTvirlChannel) "TViRL" else channelName,
                                    programs = programs
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TvProviderDebug", "Ошибка чтения каналов: ${e.message}")
        }

        return resultChannels
    }

    private fun fetchProgramsForChannel(
        context: Context,
        channelId: Long,
        channelName: String,
        appPackageName: String? = null
    ): List<LiveProgramInfo> {
        val programsList = mutableListOf<LiveProgramInfo>()

        try {
            val previewUri = TvContractCompat.buildPreviewProgramsUriForChannel(channelId)
            val cursor = context.contentResolver.query(
                previewUri,
                arrayOf(
                    "_id", "title", "short_description", "poster_art_uri", "thumbnail_uri",
                    "intent_uri", "last_playback_position_millis", "duration_millis"
                ),
                null, null, null
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex("_id")
                val titleIdx = c.getColumnIndex("title")
                val descIdx = c.getColumnIndex("short_description")
                val posterIdx = c.getColumnIndex("poster_art_uri")
                val thumbIdx = c.getColumnIndex("thumbnail_uri")
                val intentIdx = c.getColumnIndex("intent_uri")
                val posIdx = c.getColumnIndex("last_playback_position_millis")
                val durIdx = c.getColumnIndex("duration_millis")

                while (c.moveToNext()) {
                    val id = if (idIdx != -1) c.getLong(idIdx) else 0L
                    val title = if (titleIdx != -1 && !c.isNull(titleIdx)) c.getString(titleIdx) else null
                    val desc = if (descIdx != -1 && !c.isNull(descIdx)) c.getString(descIdx) else null

                    val posterStr = if (posterIdx != -1 && !c.isNull(posterIdx)) c.getString(posterIdx) else null
                    val thumbStr = if (thumbIdx != -1 && !c.isNull(thumbIdx)) c.getString(thumbIdx) else null

                    val imageUriStr = when {
                        !posterStr.isNullOrEmpty() -> posterStr
                        !thumbStr.isNullOrEmpty() -> thumbStr
                        else -> null
                    }
                    val posterUri = imageUriStr?.let { Uri.parse(it) }

                    val intentStr = if (intentIdx != -1 && !c.isNull(intentIdx)) c.getString(intentIdx) else null
                    val intentUri = if (!intentStr.isNullOrEmpty()) Uri.parse(intentStr) else null

                    val position = if (posIdx != -1 && !c.isNull(posIdx)) c.getLong(posIdx) else 0L
                    val duration = if (durIdx != -1 && !c.isNull(durIdx)) c.getLong(durIdx) else 0L

                    if (!title.isNullOrEmpty()) {
                        programsList.add(
                            LiveProgramInfo(
                                id = id,
                                channelId = channelId,
                                channelName = channelName,
                                title = title,
                                description = desc,
                                posterArtUri = posterUri,
                                intentUri = intentUri,
                                playbackPositionMillis = position,
                                durationMillis = duration,
                                appPackageName = appPackageName
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TvProviderDebug", "Ошибка чтения программ: ${e.message}")
        }

        return programsList
    }
}
@SuppressLint("Range")
suspend fun loadProviderChannelGroups(
    context: Context
): List<ChannelProviderGroup> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val groups = LinkedHashMap<String, MutableList<ProviderChannelLogo>>()
    val titles = HashMap<String, String>()

    val projection = arrayOf(
        TvContract.Channels._ID,
        TvContract.Channels.COLUMN_PACKAGE_NAME,
        TvContract.Channels.COLUMN_DISPLAY_NAME
    )

    try {
        resolver.query(
            TvContract.Channels.CONTENT_URI,
            projection,
            null,
            null,
            "${TvContract.Channels.COLUMN_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(TvContract.Channels._ID)
            val packageIndex =
                cursor.getColumnIndex(TvContract.Channels.COLUMN_PACKAGE_NAME)
            val nameIndex =
                cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val packageName = cursor.getString(packageIndex)
                    ?: continue

                val channelName = cursor.getString(nameIndex).orEmpty()

                val logoUri = runCatching {
                    TvContract.buildChannelLogoUri(id)
                }.getOrNull() ?: continue

                groups.getOrPut(packageName) { mutableListOf() }
                titles.getOrPut(packageName) {
                    val appLabel = runCatching {
                        val app = if (Build.VERSION.SDK_INT >= 33) {
                            context.packageManager.getApplicationInfo(
                                packageName,
                                PackageManager.ApplicationInfoFlags.of(0)
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            context.packageManager.getApplicationInfo(
                                packageName, 0
                            )
                        }
                        context.packageManager.getApplicationLabel(app).toString()
                    }.getOrDefault(packageName)

                    "$appLabel TV"
                }

                val intentUri = runCatching {
                    TvContract.buildChannelUri(id)
                }.getOrNull()

                groups.getValue(packageName).add(
                    ProviderChannelLogo(
                        id = id,
                        logoUri = logoUri,
                        intentUri = intentUri
                    )
                )
            }
        }
    } catch (e: SecurityException) {
        Log.w("ProviderChannels", "Нет доступа к списку TV-каналов", e)
    } catch (e: Exception) {
        Log.e("ProviderChannels", "Ошибка загрузки TV-каналов", e)
    }

    groups.mapNotNull { (packageName, channels) ->
        if (channels.isEmpty()) null
        else ChannelProviderGroup(
            packageName = packageName,
            title = titles[packageName] ?: packageName,
            channels = channels.toList()
        )
    }
}

@Composable
fun ProviderLogoRow(
    group: ChannelProviderGroup,
    onChannelClick: (Uri?) -> Unit,
    modifier: Modifier = Modifier,
    isFastScrolling: Boolean = false,
    channelFocusRequesters: List<FocusRequester> = emptyList(),
    upFocusRequesters: List<FocusRequester> = emptyList(),
    downFocusRequesters: List<FocusRequester> = emptyList(),
    onChannelFocus: (Int) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = group.title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                start = 55.dp,
                bottom = 8.dp
            )
        )

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 55.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(85.dp)
        ) {
            itemsIndexed(
                items = group.channels,
                key = { _, channel ->
                    "provider_${group.packageName}_${channel.id}"
                },
                contentType = { _, _ -> "provider_channel_card" }
            ) { channelIndex, channel ->
                AppCard(
                    painter = rememberAsyncImagePainter(channel.logoUri),
                    onClick = { onChannelClick(channel.intentUri) },
                    isFastScrolling = isFastScrolling,
                    backgroundColor = Color.White,
                    modifier = Modifier
                        .then(
                            channelFocusRequesters.getOrNull(channelIndex)?.let {
                                Modifier.focusRequester(it)
                            } ?: Modifier
                        )
                        .focusProperties {
                            if (upFocusRequesters.isNotEmpty()) {
                                up = upFocusRequesters[
                                    channelIndex.coerceIn(0, upFocusRequesters.lastIndex)
                                ]
                            }

                            if (downFocusRequesters.isNotEmpty()) {
                                down = downFocusRequesters[
                                    channelIndex.coerceIn(0, downFocusRequesters.lastIndex)
                                ]
                            }
                        }
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                onChannelFocus(channelIndex)
                            }
                        }
                        .size(130.dp, 74.dp)
                )
            }
        }
    }
}


// === КАРТОЧКА БАННЕРА С ПОЛОСОЙ ПРОГРЕССА ===

@Composable
fun LeanbackCard(
    program: LiveProgramInfo,
    onProgramClick: (Uri?) -> Unit,
    modifier: Modifier = Modifier,
    isFastScrolling: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    var imageAspectRatio by remember(program.posterArtUri) {
        mutableFloatStateOf(
            PosterAspectRatioCache[program.posterArtUri?.toString()]
                ?: (16f / 9f)
        )
    }
    var pendingImageAspectRatio by remember(program.posterArtUri) { mutableFloatStateOf(Float.NaN) }
    var dominantPanelColor by remember(program.appPackageName, program.intentUri) {
        mutableStateOf(
            program.appPackageName?.let { AppIconPanelColorCache[it] } ?: Color(0xFF37474F)
        )
    }
    val panelColor by animateColorAsState(
        targetValue = panelBackgroundColor(dominantPanelColor, isFocused),
        animationSpec = if (isFastScrolling) snap() else tween(durationMillis = 180),
        label = "ProgramPanelColor"
    )
    val fastScrollingNow by rememberUpdatedState(isFastScrolling)
    val cardShape = RoundedCornerShape(0.dp)

    // Во время быстрого перемещения не меняем ширину уже видимых карточек:
    // декодирование изображения может завершиться в этот момент и вызвать relayout LazyRow.
    // Сохраняем новые пропорции и применяем их после остановки прокрутки.
    LaunchedEffect(isFastScrolling, program.posterArtUri) {
        if (!isFastScrolling) {
            val pendingRatio = pendingImageAspectRatio
            if (pendingRatio.isFinite() && pendingRatio > 0f) {
                pendingImageAspectRatio = Float.NaN
                if (kotlin.math.abs(imageAspectRatio - pendingRatio) > 0.01f) {
                    imageAspectRatio = pendingRatio
                }
            }
        }
    }

    val leanbackCurve = remember { CubicBezierEasing(0.2f, 0.0f, 0.2f, 1.0f) }
    val scaleAnimated by animateFloatAsState(
        targetValue = if (isFocused && !isFastScrolling) 1.10f else 1.0f,
        animationSpec = if (isFastScrolling) snap() else tween(durationMillis = 150, easing = leanbackCurve),
        label = "CardScale"
    )

    val context = LocalContext.current
    val programPackageName = remember(program.appPackageName, program.intentUri) {
        resolveProgramAppPackageName(program)
    }

    // Водяной знак берёт передний слой Adaptive Icon, чтобы не превращать цветной фон
    // иконки в белый квадрат. Готовый Painter кешируется per-пакет: во время быстрой
    // прокрутки не нужны ни Coil, ни PackageManager — только чтение из HashMap.
    val appIconPainter by produceState<Painter?>(
        initialValue = AppIconPainterCache.get(programPackageName),
        key1 = programPackageName,
        key2 = isFastScrolling
    ) {
        if (isFastScrolling) {
            value = AppIconPainterCache.get(programPackageName)
            return@produceState
        }
        value = programPackageName?.let { packageName ->
            AppIconPainterCache.get(packageName) ?: run {
                val drawable = withContext(Dispatchers.IO) {
                    runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
                } ?: return@let null
                val foreground =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
                        drawable.foreground ?: drawable
                    } else {
                        drawable
                    }
                val painter = withContext(Dispatchers.Default) {
                    runCatching {
                        drawableToWatermarkPainter(
                            foreground.constantState?.newDrawable()?.mutate() ?: foreground
                        )
                    }.getOrNull()
                }
                if (painter != null) AppIconPainterCache.put(packageName, painter)
                painter
            }
        }
    }

    // Нижняя панель берёт цвет из полной иконки приложения-владельца рекомендации,
    // а не из постера/баннера. Во время быстрой прокрутки PackageManager не трогаем:
    // показываем цвет из кеша, новые цвета вычисляем после остановки.
    LaunchedEffect(programPackageName, isFastScrolling) {
        val packageName = programPackageName ?: return@LaunchedEffect
        val cachedColor = AppIconPanelColorCache[packageName]
        if (cachedColor != null) {
            dominantPanelColor = cachedColor
            return@LaunchedEffect
        }
        if (isFastScrolling) return@LaunchedEffect
        val appIcon = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        } ?: return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) {
            extractDominantPanelColor(appIcon)
        }
        if (extracted != null) {
            AppIconPanelColorCache.put(packageName, extracted)
            dominantPanelColor = extracted
        }
    }

    val imageRequest = remember(program.posterArtUri) {
        if (program.posterArtUri != null) {
            ImageRequest.Builder(context)
                .data(program.posterArtUri)
                // Экранные карточки невелики: не декодируем постеры в полном исходном разрешении.
                .size(600, 340)
                .crossfade(false)
                .allowHardware(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .listener(
                    onSuccess = { _, result ->
                        // Сохраняем декодированный hardware-битмап: во время быстрой
                        // прокрутки карточки рисуют его напрямую, без Coil.
                        PosterBitmapCache.put(
                            program.posterArtUri?.toString(),
                            (result.drawable as? BitmapDrawable)?.bitmap
                        )
                        // Размеры постера нужны только для адаптивной ширины карточки;
                        // цвет панели берётся отдельно из иконки приложения.
                        val w = result.drawable.intrinsicWidth
                        val h = result.drawable.intrinsicHeight
                        if (w > 0 && h > 0) {
                            val ratio = w.toFloat() / h.toFloat()
                            PosterAspectRatioCache.put(
                                program.posterArtUri?.toString(),
                                ratio
                            )
                            if (fastScrollingNow) {
                                pendingImageAspectRatio = ratio
                            } else if (kotlin.math.abs(imageAspectRatio - ratio) > 0.01f) {
                                imageAspectRatio = ratio
                            }
                        }
                    }
                )
                .build()
        } else null
    }

    // Поведение как у Leanback ImageCardView с adjustViewBounds:
    // высота изображения постоянная, ширина рассчитывается по его настоящим пропорциям.
    val imageHeight = 190.dp
    val cardWidth = calculateImageCardWidth(imageHeight, imageAspectRatio)

    Box(
        modifier = modifier
            .zIndex(if (isFocused) 100f else 1f)
            .width(cardWidth)
            .wrapContentHeight()
            .graphicsLayer {
                scaleX = scaleAnimated
                scaleY = scaleAnimated
                alpha = 1.0f
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .then(
                if (isFocused && !isFastScrolling) Modifier.shadow(8.dp, RoundedCornerShape(0.dp), clip = false) else Modifier
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onProgramClick(program.intentUri) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imageHeight)
                    .background(Color(0xFF202020))
            ) {
                when {
                    program.posterArtUri != null -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val cachedPoster = PosterBitmapCache.get(
                                program.posterArtUri.toString()
                            )

                            if (cachedPoster != null) {
                                Image(
                                    bitmap = cachedPoster.asImageBitmap(),
                                    contentDescription = program.title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                AsyncImage(
                                    model = imageRequest,
                                    contentDescription = program.title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    program.drawableResId != null -> {
                        Image(
                            painter = painterResource(id = program.drawableResId),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (program.progress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x88000000))
                            .align(Alignment.BottomStart)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = program.progress)
                                .background(Color(0xFFFF0000))
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panelColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isFocused) {
                            program.title ?: "Без названия"
                        } else {
                            program.channelName ?: "SmartTube"
                        },
                        color = if (isFocused) Color.White else Color(0xFF999999),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isFocused && !program.description.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = program.description,
                            color = Color(0xFFBBBBBB),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // In the idle state, show a subtle white app mark at the right edge
                // of the colored lower panel. It disappears while focused so the title
                // and description can use the full row width. Nothing is drawn over the poster.
                if (!isFocused) {
                    appIconPainter?.let { painter ->
                        Image(
                            painter = painter,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(Color.White),
                            modifier = Modifier
                                // Keep the watermark inside the original panel height.
                                .size(16.dp)
                                .graphicsLayer { alpha = 0.42f }
                        )
                    }
                }
            }
        }
    }
}


/**
 * Reads a remote image's aspect ratio before the corresponding ad is put into the feed.
 * Coil also caches the decoded image, so AsyncImage can reuse it when the card appears.
 * A safe landscape ratio is used if the image cannot be loaded.
 */
private suspend fun resolveBannerAspectRatio(context: Context, imageUrl: String): Float {
    val fallbackRatio = 16f / 9f
    return try {
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(450, 260)
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .allowHardware(true)
            .build()
        val result = context.imageLoader.execute(request)
        if (result is SuccessResult) {
            val width = result.drawable.intrinsicWidth
            val height = result.drawable.intrinsicHeight
            if (width > 0 && height > 0) width.toFloat() / height.toFloat() else fallbackRatio
        } else {
            fallbackRatio
        }
    } catch (error: Exception) {
        Log.w("FirebaseAds", "Не удалось определить пропорции баннера; используем стандартные", error)
        fallbackRatio
    }
}

/**
 * Аналог adjustViewBounds в Leanback ImageCardView: держим высоту постоянной,
 * а ширину вычисляем из пропорций картинки. Ограничения не дают сверхшироким
 * панорамам или очень узким постерам ломать горизонтальный ряд на телевизоре.
 */
private fun calculateImageCardWidth(imageHeight: androidx.compose.ui.unit.Dp, aspectRatio: Float): androidx.compose.ui.unit.Dp {
    val safeAspectRatio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    return (imageHeight * safeAspectRatio).coerceIn(110.dp, 335.dp)
}

private object PosterAspectRatioCache {
    private const val MAX_ENTRIES = 256

    private val ratios = object : LinkedHashMap<String, Float>(
        MAX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Float>?
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    operator fun get(url: String?): Float? {
        if (url.isNullOrBlank()) return null
        return ratios[url]
    }

    @Synchronized
    fun put(url: String?, ratio: Float) {
        if (url.isNullOrBlank()) return
        if (!ratio.isFinite() || ratio <= 0f) return

        ratios[url] = ratio
    }
}

/** Decoded poster bitmaps shared by all recommendation cards. A hardware bitmap is
 *  backed by a GPU texture uploaded once, so drawing it during fast scrolling costs
 *  almost nothing: no decoding, no allocations, no network, no texture uploads. */
private object PosterBitmapCache {
    private const val MAX_ENTRIES = 48

    private val bitmaps = object : LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(url: String?): Bitmap? = if (url.isNullOrBlank()) null else bitmaps[url]

    @Synchronized
    fun put(url: String?, bitmap: Bitmap?) {
        if (url.isNullOrBlank() || bitmap == null || bitmap.isRecycled) return
        bitmaps[url] = bitmap
    }
}

/** Process-wide cache of watermark painters, so fast scrolling never touches
 *  PackageManager or Coil: the painter is ready together with its GPU texture. */
private object AppIconPainterCache {
    private const val MAX_ENTRIES = 96

    private val painters = object : LinkedHashMap<String, Painter>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Painter>?) =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(packageName: String?): Painter? = packageName?.let { painters[it] }

    @Synchronized
    fun put(packageName: String, painter: Painter) {
        painters[packageName] = painter
    }
}

/** Draws a drawable once into a small software bitmap and wraps it as a Painter. */
private fun drawableToWatermarkPainter(drawable: Drawable, sizePx: Int = 96): Painter? {
    return runCatching {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val oldBounds = Rect(drawable.bounds)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        drawable.bounds = oldBounds
        androidx.compose.ui.graphics.painter.BitmapPainter(bitmap.asImageBitmap())
    }.getOrNull()
}

/** Process-wide cache avoids sampling the same application icon for every programme card. */
/**
 * Bounded process-wide LRU cache: avoids sampling the same app icon repeatedly
 * without retaining colors for every package ever seen during a long session.
 */
private object AppIconPanelColorCache {
    private const val MAX_ENTRIES = 256

    private val colors = object : LinkedHashMap<String, Color>(
        MAX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Color>?
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    operator fun get(packageName: String): Color? =
        colors[packageName]

    @Synchronized
    fun put(packageName: String, color: Color) {
        colors[packageName] = color
    }
}

/** Resolve the application package from TV Provider metadata or its launch intent URI. */
private fun resolveProgramAppPackageName(program: LiveProgramInfo): String? {
    program.appPackageName?.takeIf { it.isNotBlank() }?.let { return it }

    val uri = program.intentUri ?: return null
    val host = uri.host?.takeIf {
        it.contains('.') && !it.equals("intent", ignoreCase = true)
    }
    if (host != null) return host

    return runCatching {
        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        intent.component?.packageName ?: intent.`package`
    }.getOrNull()
}

/**
 * Extracts a representative, preferably colourful pixel from a tiny image sample.
 * The bitmap work is invoked on Dispatchers.Default, never on the Compose UI thread.
 */
private fun extractDominantPanelColor(drawable: Drawable?): Color? {
    if (drawable == null) return null

    val sampleSize = 24
    val originalBitmap = (drawable as? BitmapDrawable)?.bitmap
    var convertedBitmap: Bitmap? = null
    var sampleBitmap: Bitmap? = null

    try {
        if (originalBitmap != null) {
            // Coil may return a hardware bitmap. Make a software copy before sampling pixels.
            val sourceBitmap = if (originalBitmap.config?.name == "HARDWARE") {
                originalBitmap.copy(Bitmap.Config.ARGB_8888, false).also { convertedBitmap = it }
            } else {
                originalBitmap
            }
            sampleBitmap = Bitmap.createScaledBitmap(sourceBitmap, sampleSize, sampleSize, true)
        } else {
            sampleBitmap = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888)
            val drawableCopy = drawable.constantState?.newDrawable()?.mutate() ?: return null
            val oldBounds = Rect(drawableCopy.bounds)
            drawableCopy.setBounds(0, 0, sampleSize, sampleSize)
            drawableCopy.draw(Canvas(sampleBitmap!!))
            drawableCopy.bounds = oldBounds
        }

        val bitmap = sampleBitmap ?: return null
        data class Bucket(
            var count: Int = 0,
            var score: Float = 0f,
            var red: Int = 0,
            var green: Int = 0,
            var blue: Int = 0
        )

        val buckets = HashMap<Int, Bucket>()
        var fallbackCount = 0
        var fallbackRed = 0
        var fallbackGreen = 0
        var fallbackBlue = 0

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (AndroidGraphicsColor.alpha(pixel) < 72) continue

                val red = AndroidGraphicsColor.red(pixel)
                val green = AndroidGraphicsColor.green(pixel)
                val blue = AndroidGraphicsColor.blue(pixel)
                fallbackCount++
                fallbackRed += red
                fallbackGreen += green
                fallbackBlue += blue

                val r = red / 255f
                val g = green / 255f
                val b = blue / 255f
                val maxChannel = maxOf(r, g, b)
                val minChannel = minOf(r, g, b)
                val saturation = if (maxChannel <= 0f) 0f else (maxChannel - minChannel) / maxChannel
                val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b

                // Ignore near-black shadows and plain white borders so the panel picks up
                // the artwork's actual accent colour rather than its empty background.
                if (luminance < 0.055f || (luminance > 0.94f && saturation < 0.18f)) continue

                val key = ((red shr 4) shl 8) or ((green shr 4) shl 4) or (blue shr 4)
                val bucket = buckets.getOrPut(key) { Bucket() }
                bucket.count++
                bucket.score += 1f + saturation * 1.35f
                bucket.red += red
                bucket.green += green
                bucket.blue += blue
            }
        }

        val winner = buckets.values.maxByOrNull { it.score }
        if (winner != null && winner.count > 0) {
            val count = winner.count.toFloat() * 255f
            return Color(winner.red / count, winner.green / count, winner.blue / count, 1f)
        }
        if (fallbackCount > 0) {
            val count = fallbackCount.toFloat() * 255f
            return Color(fallbackRed / count, fallbackGreen / count, fallbackBlue / count, 1f)
        }
        return null
    } catch (error: Exception) {
        Log.d("LeanbackPanelColor", "Не удалось вычислить цвет нижней панели", error)
        return null
    } finally {
        sampleBitmap?.let {
            if (it !== originalBitmap && !it.isRecycled) it.recycle()
        }
        convertedBitmap?.let {
            if (it !== originalBitmap && it !== sampleBitmap && !it.isRecycled) it.recycle()
        }
    }
}

/** Darkens the panel tint (derived from the app icon) to keep white title text readable. */
private fun panelBackgroundColor(dominant: Color, isFocused: Boolean): Color {
    val base = Color(0xFF101010)
    val tintStrength = if (isFocused) 0.42f else 0.28f
    return Color(
        red = (base.red + (dominant.red - base.red) * tintStrength).coerceIn(0f, 1f),
        green = (base.green + (dominant.green - base.green) * tintStrength).coerceIn(0f, 1f),
        blue = (base.blue + (dominant.blue - base.blue) * tintStrength).coerceIn(0f, 1f),
        alpha = 1f
    )
}

/** Рекламная карточка визуально повторяет обычную карточку Leanback. */
@Composable
private fun LeanbackPromoCard(
    banner: PromoBanner,
    isFastScrolling: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember(banner.id) { mutableStateOf(false) }
    val dominantPanelColor = Color(0xFF37474F)
    val context = LocalContext.current
    val panelColor by animateColorAsState(
        targetValue = panelBackgroundColor(dominantPanelColor, isFocused),
        animationSpec = if (isFastScrolling) {
            snap()
        } else {
            tween(durationMillis = 180)
        },
        label = "ProgramPanelColor"
    )
    val leanbackCurve = remember { CubicBezierEasing(0.2f, 0.0f, 0.2f, 1.0f) }
    val scaleAnimated by animateFloatAsState(
        targetValue = if (isFocused && !isFastScrolling) 1.10f else 1.0f,
        animationSpec = if (isFastScrolling) snap() else tween(durationMillis = 150, easing = leanbackCurve),
        label = "AdCardScale_${banner.id}"
    )

    val remoteUrl = banner.imageUrl
        ?.trim()
        ?.takeIf { it.startsWith("https://", ignoreCase = true) }
    // Пропорции определяются заранее до добавления рекламы в LazyRow.
    // Поэтому при появлении карточки её ширина уже правильная и не меняется после загрузки.
    val imageAspectRatio = banner.aspectRatio
        .takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    val imageRequest = remember(remoteUrl, banner.bannerResId) {
        ImageRequest.Builder(context)
            .data(remoteUrl ?: banner.bannerResId)
            .size(600, 340)
            .apply {
                if (remoteUrl != null) {
                    placeholder(banner.bannerResId)
                    error(banner.bannerResId)
                    fallback(banner.bannerResId)
                }
            }
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .allowHardware(true)
            .listener(
                onSuccess = { _, result ->
                    PosterBitmapCache.put(
                        "promo:${remoteUrl ?: banner.id}",
                        (result.drawable as? BitmapDrawable)?.bitmap
                    )
                }
            )
            .build()
    }

    val imageHeight = 190.dp
    val cardWidth = if (banner.isVertical) 130.dp
    else calculateImageCardWidth(imageHeight, imageAspectRatio)

    Box(
        modifier = modifier
            .width(cardWidth)
            .wrapContentHeight()
            .zIndex(if (isFocused) 100f else 1f)
            .graphicsLayer {
                scaleX = scaleAnimated
                scaleY = scaleAnimated
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .then(
                if (isFocused && !isFastScrolling) {
                    Modifier.shadow(8.dp, RoundedCornerShape(0.dp), clip = false)
                } else {
                    Modifier
                }
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imageHeight)
                    .background(Color(0xFF202020))
            ) {
                // Во время быстрой прокрутки рекламу не грузим через Coil: рисуем
                // ранее декодированный битмап из кеша либо тёмный фон карточки.
                val cachedAd = PosterBitmapCache.get("promo:${remoteUrl ?: banner.id}")
                if (isFastScrolling) {
                    if (cachedAd != null) {
                        Image(
                            bitmap = cachedAd.asImageBitmap(),
                            contentDescription = banner.title,
                            contentScale = if (banner.isVertical) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = banner.title,
                        // При автоматической ширине пропорции уже совпадают с контейнером,
                        // поэтому Fit сохраняет весь кадр без обрезки. Ручной vertical-режим
                        // оставляет прежнее поведение Crop.
                        contentScale = if (banner.isVertical) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panelColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (isFocused) banner.title else banner.brand,
                    color = if (isFocused) Color.White else Color(0xFF999999),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (isFocused && banner.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = banner.description,
                        color = Color(0xFFBBBBBB),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


@Composable
private fun LauncherClock() {
    val formatter = remember { SimpleDateFormat("H:mm", Locale.getDefault()) }
    var currentTime by remember(formatter) { mutableStateOf(formatter.format(Date())) }

    LaunchedEffect(formatter) {
        while (true) {
            val now = System.currentTimeMillis()
            currentTime = formatter.format(Date(now))
            // Формат H:mm меняется раз в минуту, поэтому не нужно просыпаться каждую секунду.
            delay(60_000L - (now % 60_000L))
        }
    }

    Text(
        text = currentTime,
        color = Color(0xDDFFFFFF),
        fontSize = 32.sp,
        fontWeight = FontWeight.Light
    )
}
@Composable
private fun LauncherBackground(
    backgroundUri: String?,
    offsetY: Float
) {
    var containerHeight by remember { mutableIntStateOf(0) }

    val maskBrush = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to Color.Transparent,
                0.30f to Color(0x18000000),
                0.58f to Color(0x70000000),
                0.95f to Color.Black,
                1.00f to Color.Black
            )
        )
    }

    // Весь фон — два слоя вместо четырёх: каждый полноэкранный композитинг
    // на слабой приставке дорог, особенно на каждом кадре анимации параллакса.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerHeight = it.height }
    ) {
        // Слой 1: обои + затемнение + градиент-маска в одном graphicsLayer.
        // Вертикальное растяжение 1.05 совмещено для всех трёх: на Crop-обоях
        // оно незаметно, а плоский цвет затемнения его вообще не чувствует.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = offsetY
                    scaleY = 1.05f
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
        ) {
            AsyncImage(
                model = backgroundUri ?: R.drawable.bg_default,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(maskBrush)
            )
        }

        // Слой 2: непрозрачное перекрытие нижней границы обоев.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .align(Alignment.TopStart)
                .graphicsLayer {
                    translationY = containerHeight.toFloat() + offsetY - 1f
                }
                .background(Color.Black)
        )
    }
}

// === ГЛАВНЫЙ ЭКРАН ===

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun MainScreen(
    channels: List<LiveChannelInfo>,
    mixChannelIds: List<Long> = emptyList(),
    tvInputs: List<TvInputItem> = emptyList(),
    apps: List<TvAppInfo>,
    currentBgUri: String?,
    showRecommendations: Boolean,
    isRecommendationsLoading: Boolean = false,
    isOverlay: Boolean = false,
    onOpenSettings: () -> Unit,
    onAppClick: (String) -> Unit,
    onProgramClick: (Uri?) -> Unit,
    games: List<TvAppInfo> = emptyList(),
    maxAppsPerRow: Int = 10,
    sectionOrder: List<String> = listOf("recommendations", "favorites", "apps", "inputs", "games"),
    inputSwitchSoundEnabled: Boolean = false,
    favoritePackages: Set<String> = emptySet(),
    favoriteCustomOrder: List<String> = emptyList(),
    onToggleFavorite: (String) -> Unit = {},
    onMoveApp: (
        packageName: String,
        direction: Int,
        isGame: Boolean,
        originSection: String
    ) -> Unit = { _, _, _, _ -> },
    providerGroups: List<ChannelProviderGroup> = emptyList()
) {

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val hostView = LocalView.current

    var tickSoundId by remember { mutableIntStateOf(0) }
    var tickSoundLoaded by remember { mutableStateOf(false) }

    val navigationSoundPool = remember(context) {
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }

    DisposableEffect(navigationSoundPool) {
        navigationSoundPool.setOnLoadCompleteListener { _, _, status ->
            tickSoundLoaded = status == 0
        }

        tickSoundId = navigationSoundPool.load(
            context,
            R.raw.effect_tick_48k,
            1
        )

        onDispose {
            navigationSoundPool.setOnLoadCompleteListener(null)
            navigationSoundPool.release()
        }
    }

    // playSoundEffect() is gated by the host View's sound-effects flag.
    // Enable it for this launcher surface; Android's global system-sounds setting
    // can still mute framework effects.
    SideEffect {
        hostView.isSoundEffectsEnabled = true
    }

    var selectedAppForMenu by remember { mutableStateOf<TvAppInfo?>(null) }
    var selectedAppIsGame by remember { mutableStateOf(false) }
    var selectedAppOriginSection by remember { mutableStateOf("apps") }
    var favoriteFocusRestorePackage by remember { mutableStateOf<String?>(null) }
    var favoriteFocusRestoreSection by remember { mutableStateOf<String?>(null) }
    var moveModePackage by remember { mutableStateOf<String?>(null) }
    var moveModeIsGame by remember { mutableStateOf(false) }
    var moveModeOriginSection by remember { mutableStateOf<String?>(null) }
    // Lock the exact rendered whole-screen translation when entering move mode.
    // Focus may change rows while the item is reordered, but the screen must not follow it.
    var moveModeLockedContentOffsetPx by remember { mutableFloatStateOf(0f) }
    var moveModeStartFocusedRowIndex by remember { mutableIntStateOf(0) }
    var moveModeLockedSectionOffsets by remember { mutableStateOf<Map<String, Dp>?>(null) }
    var moveModeLockedBackgroundOffsetPx by remember { mutableFloatStateOf(Float.NaN) }
    var moveModeConsumedKeyUp by remember { mutableIntStateOf(KeyEvent.KEYCODE_UNKNOWN) }
    var focusedRowIndex by remember { mutableIntStateOf(0) }
    var focusedAppColumnIndex by remember { mutableIntStateOf(0) }
    // Each section remembers its own selection; never derive the target card
    // from the column/index of the section we're leaving.
    var lastFocusedAppsRowOffset by remember { mutableIntStateOf(0) }
    var lastFocusedAppsColumn by remember { mutableIntStateOf(0) }
    var lastFocusedFavoriteIndex by remember { mutableIntStateOf(0) }
    var lastFocusedInputIndex by remember { mutableIntStateOf(0) }
    var lastFocusedGameIndex by remember { mutableIntStateOf(0) }
    var focusedProviderPackage by remember {
        mutableStateOf<String?>(null)
    }

    var lastFocusedProviderIndices by remember {
        mutableStateOf<Map<String, Int>>(emptyMap())
    }
    var activeRowTitle by remember { mutableStateOf<String?>(null) }
    // Use an immediate content shift when focus moves between wrapped app rows,
    // so the newly focused card is already on the fixed focus line.

    val recommendationsListState = rememberLazyListState()
    val favoritesHorizontalScrollState = rememberScrollState()
    val appsHorizontalListState = rememberLazyListState()
    val inputsListState = rememberLazyListState()
    val gamesListState = rememberLazyListState()
    val providerRowHeight = 130.dp
    val providerRowGap = 18.dp

    var isFastScrolling by remember { mutableStateOf(false) }
    var leftRightPressStartTime by remember { mutableLongStateOf(0L) }
    var lastHorizontalDownTime by remember { mutableLongStateOf(0L) }
    var isHoldingLeftRight by remember { mutableStateOf(false) }
    var fastScrollResetJob by remember { mutableStateOf<Job?>(null) }
    val inputScope = rememberCoroutineScope()

    val normalizedMaxAppsPerRow = maxAppsPerRow.coerceIn(4, 50)
    val appRows = remember(apps, normalizedMaxAppsPerRow) {
        val chunks = apps.chunked(normalizedMaxAppsPerRow)
        if (chunks.isEmpty()) listOf(emptyList<TvAppInfo>()) else chunks
    }
    // A real Favorites row, separate from the complete Apps and Games sections.
    // Favorite games are included too; the original Apps/Games rows remain intact.
    val favoriteRowApps = remember(apps, games, favoritePackages, favoriteCustomOrder) {
        orderFavoriteApps(
            apps = (apps + games).distinctBy { it.packageName },
            favoritePackages = favoritePackages,
            favoriteCustomOrder = favoriteCustomOrder
        )
    }
    val hasFavoritesSection = favoriteRowApps.isNotEmpty()
    val hasInputsSection = tvInputs.isNotEmpty()

    val visibleSectionOrder = remember(
        sectionOrder,
        showRecommendations,
        hasFavoritesSection,
        hasInputsSection,
        games
    ) {
        sectionOrder.filter { sectionKey ->
            when (sectionKey) {
                "recommendations" -> showRecommendations
                "favorites" -> hasFavoritesSection
                "apps" -> true
                "inputs" -> hasInputsSection
                "games" -> games.isNotEmpty()
                else -> false
            }
        }.let { ordered -> if ("apps" in ordered) ordered else listOf("apps") }
    }
    val layoutSectionOrder = remember(
        sectionOrder,
        hasFavoritesSection,
        hasInputsSection,
        games
    ) {
        sectionOrder.filter { sectionKey ->
            when (sectionKey) {
                "recommendations" -> true
                "favorites" -> hasFavoritesSection
                "apps" -> true
                "inputs" -> hasInputsSection
                "games" -> games.isNotEmpty()
                else -> false
            }
        }.let { ordered ->
            if ("apps" in ordered) ordered else listOf("apps")
        }
    }

    // Row indices are derived from the visible section order instead of hardcoded
    // positions. Reordering sections in Settings therefore keeps focus tracking,
    // scroll targets and per-row animations correct.
    val rowIndexBySection = remember(visibleSectionOrder, appRows.size) {
        val map = mutableMapOf<String, Int>()
        var cursor = 0
        visibleSectionOrder.forEach { key ->
            map[key] = cursor
            cursor += when (key) {
                "apps" -> appRows.size
                else -> 1
            }
        }
        map
    }
    val recommendationsRowIndex = rowIndexBySection["recommendations"]
    val favoritesRowIndex = rowIndexBySection["favorites"] ?: 0
    val appsRowIndex = rowIndexBySection["apps"] ?: 0
    val inputsRowIndex = rowIndexBySection["inputs"] ?: (appsRowIndex + appRows.size)
    val gamesRowIndex = rowIndexBySection["games"] ?: (inputsRowIndex + 1)
    // During a reorder, focus may temporarily detach and reattach to the card's new cell.
    // Keep section styling anchored to the original section until move mode ends.
    val visualFocusedRowIndex = if (moveModePackage != null) {
        moveModeStartFocusedRowIndex
    } else {
        focusedRowIndex
    }
    val visualFocusedAppsRowOffset = visualFocusedRowIndex - appsRowIndex

    // Requesters survive reordering by following the app package instead of a grid cell.
    // This prevents focus from dropping temporarily when a card changes row/column.
    val appFocusRequesterCache = remember { mutableMapOf<String, FocusRequester>() }
    val appFocusRequesters = remember(appRows, normalizedMaxAppsPerRow) {
        appRows.map { row ->
            List(normalizedMaxAppsPerRow) { column ->
                row.getOrNull(column)?.packageName?.let { packageName ->
                    appFocusRequesterCache.getOrPut(packageName) { FocusRequester() }
                } ?: FocusRequester()
            }
        }
    }
    val favoriteFocusKeys = favoriteRowApps.map { it.packageName }
    val favoriteFocusRequesterCache = remember { mutableMapOf<String, FocusRequester>() }
    val favoriteFocusRequesters = remember(favoriteFocusKeys) {
        favoriteRowApps.map { app ->
            favoriteFocusRequesterCache.getOrPut(app.packageName) { FocusRequester() }
        }
    }
    val inputFocusKeys = tvInputs.map { it.id }
    val inputFocusRequesters = remember(inputFocusKeys) {
        List(inputFocusKeys.size) { FocusRequester() }
    }
    val gameFocusKeys = games.map { it.packageName }
    val gameFocusRequesterCache = remember { mutableMapOf<String, FocusRequester>() }
    val gameFocusRequesters = remember(gameFocusKeys) {
        games.map { game ->
            gameFocusRequesterCache.getOrPut(game.packageName) { FocusRequester() }
        }
    }
    val providerFocusRequesters = remember(providerGroups) {
        providerGroups.associate { group ->
            group.packageName to List(group.channels.size) {
                FocusRequester()
            }
        }
    }

    // Find the nearest real app in a row, avoiding empty slots and requesters that
    // are not attached to a focusable card.
    fun findFocusableAppRequester(rowOffset: Int, preferredColumn: Int): FocusRequester? {
        val row = appRows.getOrNull(rowOffset) ?: return null
        if (row.isEmpty()) return null
        val startColumn = preferredColumn.coerceIn(0, row.lastIndex)
        for (distance in 0..row.lastIndex) {
            val leftColumn = startColumn - distance
            if (leftColumn >= 0 && row.getOrNull(leftColumn)?.icon != null) {
                return appFocusRequesters.getOrNull(rowOffset)?.getOrNull(leftColumn)
            }
            val rightColumn = startColumn + distance
            if (distance > 0 && rightColumn < row.size && row.getOrNull(rightColumn)?.icon != null) {
                return appFocusRequesters.getOrNull(rowOffset)?.getOrNull(rightColumn)
            }
        }
        return null
    }

    fun findFocusableFavoriteRequester(preferredIndex: Int): FocusRequester? {
        if (favoriteFocusRequesters.isEmpty()) return null
        return favoriteFocusRequesters[preferredIndex.coerceIn(0, favoriteFocusRequesters.lastIndex)]
    }

    fun findFocusableGameRequester(preferredIndex: Int): FocusRequester? {
        if (games.isEmpty()) return null
        val startIndex = preferredIndex.coerceIn(0, games.lastIndex)
        for (distance in 0..games.lastIndex) {
            val leftIndex = startIndex - distance
            if (leftIndex >= 0 && games.getOrNull(leftIndex)?.icon != null) {
                return gameFocusRequesters.getOrNull(leftIndex)
            }
            val rightIndex = startIndex + distance
            if (distance > 0 && rightIndex < games.size && games.getOrNull(rightIndex)?.icon != null) {
                return gameFocusRequesters.getOrNull(rightIndex)
            }
        }
        return null
    }

    fun sectionNeighborRequester(
        currentSection: String,
        moveUp: Boolean,
        preferredIndex: Int
    ): FocusRequester? {
        // Из входов вниз — к первой группе каналов.
        if (!moveUp && currentSection == "inputs" &&
            providerGroups.isNotEmpty()
        ) {
            val group = providerGroups.first()
            val requesters =
                providerFocusRequesters[group.packageName].orEmpty()

            if (requesters.isNotEmpty()) {
                val index = (
                        lastFocusedProviderIndices[group.packageName] ?: 0
                        ).coerceIn(0, requesters.lastIndex)

                return requesters[index]
            }
        }

        val currentIndex = visibleSectionOrder.indexOf(currentSection)
        if (currentIndex < 0) return null
        val targetIndex = currentIndex + if (moveUp) -1 else 1
        val targetSection = visibleSectionOrder.getOrNull(targetIndex) ?: return null
        return when (targetSection) {
            "favorites" -> findFocusableFavoriteRequester(lastFocusedFavoriteIndex)
            "apps" -> {
                if (appRows.isEmpty()) {
                    null
                } else {
                    val targetRow = lastFocusedAppsRowOffset.coerceIn(0, appRows.lastIndex)
                    findFocusableAppRequester(
                        rowOffset = targetRow,
                        preferredColumn = lastFocusedAppsColumn
                    )
                }
            }
            "inputs" -> if (inputFocusRequesters.isNotEmpty()) {
                inputFocusRequesters[lastFocusedInputIndex.coerceIn(0, inputFocusRequesters.lastIndex)]
            } else null
            "games" -> findFocusableGameRequester(lastFocusedGameIndex)
            else -> null
        }
    }

    // Focus neighbours of every grid cell, computed once per data change. Every
    // DPAD move recomposes the whole grid; re-running these searches for every
    // card each time dominated the main thread on TV boxes.
    val gridNeighborCache = remember(appRows, appFocusRequesters) {
        fun requesterAt(row: Int, col: Int) =
            appFocusRequesters.getOrNull(row)?.getOrNull(col)

        fun searchVertical(targetRow: Int, col: Int): FocusRequester? {
            val targetApps = appRows.getOrNull(targetRow) ?: return null
            val clampedCol = col.coerceIn(0, (targetApps.size - 1).coerceAtLeast(0))
            if (targetApps.getOrNull(clampedCol) != null) {
                return requesterAt(targetRow, clampedCol)
            }
            var dist = 1
            while (true) {
                val leftCol = clampedCol - dist
                val rightCol = clampedCol + dist
                val hasLeft = leftCol >= 0 && targetApps.getOrNull(leftCol) != null
                val hasRight = rightCol < targetApps.size && targetApps.getOrNull(rightCol) != null
                when {
                    hasLeft && hasRight ->
                        return requesterAt(
                            targetRow,
                            if ((col - leftCol) <= (rightCol - col)) leftCol else rightCol
                        )
                    hasLeft -> return requesterAt(targetRow, leftCol)
                    hasRight -> return requesterAt(targetRow, rightCol)
                }
                if (leftCol < 0 && rightCol >= targetApps.size) return null
                dist++
            }
        }

        appRows.mapIndexed { rowOffset, rowApps ->
            List(normalizedMaxAppsPerRow) { slotIndex ->
                val prev: FocusRequester? = run {
                    var col = slotIndex - 1
                    while (col >= 0) {
                        if (rowApps.getOrNull(col) != null) return@run requesterAt(rowOffset, col)
                        col--
                    }
                    null
                }
                val next: FocusRequester? = run {
                    var col = slotIndex + 1
                    while (col < rowApps.size) {
                        if (rowApps.getOrNull(col) != null) return@run requesterAt(rowOffset, col)
                        col++
                    }
                    null
                }
                GridNeighbors(
                    prev = prev,
                    next = next,
                    up = if (rowOffset > 0) searchVertical(rowOffset - 1, slotIndex) else null,
                    down = if (rowOffset < appRows.lastIndex) {
                        searchVertical(rowOffset + 1, slotIndex)
                    } else null
                )
            }
        }
    }


    // After a live reorder, the composable may be re-created in another grid slot.
    // Request focus again by package name so the moving card stays selected.
    LaunchedEffect(
        moveModePackage,
        moveModeOriginSection,
        apps,
        games,
        favoriteRowApps,
        normalizedMaxAppsPerRow
    ) {
        val packageToKeepFocused = moveModePackage ?: return@LaunchedEffect
        // Wait for the reordered card to be recomposed and its FocusRequester attached.
        withFrameNanos { }
        val requester = when (moveModeOriginSection) {
            "favorites" -> {
                val index = favoriteRowApps.indexOfFirst { it.packageName == packageToKeepFocused }
                favoriteFocusRequesters.getOrNull(index)
            }
            "apps" -> {
                val index = apps.indexOfFirst { it.packageName == packageToKeepFocused }
                if (index >= 0) {
                    findFocusableAppRequester(index / normalizedMaxAppsPerRow, index % normalizedMaxAppsPerRow)
                } else null
            }
            "games" -> {
                val index = games.indexOfFirst { it.packageName == packageToKeepFocused }
                gameFocusRequesters.getOrNull(index)
            }
            else -> null
        }
        runCatching { requester?.requestFocus() }
    }

    // Adding the first favorite creates a new focusable row. Keep focus on the card
    // whose context menu was used, rather than letting Compose move focus into the
    // new row and consequently shifting the whole launcher to another focus target.
    LaunchedEffect(
        favoriteFocusRestorePackage,
        favoriteFocusRestoreSection,
        favoritePackages,
        apps,
        games,
        favoriteRowApps,
        normalizedMaxAppsPerRow
    ) {
        val packageToRestore = favoriteFocusRestorePackage ?: return@LaunchedEffect
        withFrameNanos { }
        val requester = when (favoriteFocusRestoreSection) {
            "apps" -> {
                val index = apps.indexOfFirst { it.packageName == packageToRestore }
                if (index >= 0) {
                    findFocusableAppRequester(index / normalizedMaxAppsPerRow, index % normalizedMaxAppsPerRow)
                } else null
            }
            "games" -> {
                val index = games.indexOfFirst { it.packageName == packageToRestore }
                gameFocusRequesters.getOrNull(index)
            }
            "favorites" -> {
                val index = favoriteRowApps.indexOfFirst { it.packageName == packageToRestore }
                favoriteFocusRequesters.getOrNull(index)
            }
            else -> null
        }
        val restored = runCatching { requester?.requestFocus() ?: false }.getOrDefault(false)
        if (restored) {
            favoriteFocusRestorePackage = null
            favoriteFocusRestoreSection = null
        }
    }

    val density = LocalDensity.current

    val appCardHorizontalGap = 6.dp
    val appCardVerticalGap = 4.dp
    val appCardWidth = 130.dp
    val appCardHeight = 74.dp

    // Полные модификаторы карточек кешируются, чтобы карточки сетки пропускались
    // рекомпозицией (skippable) при навигации пультом: иначе сотни карточек
    // пересобирались на каждое нажатие только из-за новых экземпляров Modifier.
    // focusProperties читает состояние лениво, в момент поиска фокуса, поэтому
    // граничные переходы к соседним секциям остаются живыми.
    val gridCardModifiers = remember(gridNeighborCache, appFocusRequesters) {
        appRows.indices.map { rowOffset ->
            List(normalizedMaxAppsPerRow) { slotIndex ->
                Modifier
                    .focusRequester(appFocusRequesters[rowOffset][slotIndex])
                    .focusProperties {
                        val n = gridNeighborCache.getOrNull(rowOffset)?.getOrNull(slotIndex)
                        if (n?.prev != null) left = n.prev
                        if (n?.next != null) right = n.next
                        val upTarget = n?.up
                            ?: sectionNeighborRequester("apps", moveUp = true, preferredIndex = slotIndex)
                        val downTarget = n?.down
                            ?: sectionNeighborRequester("apps", moveUp = false, preferredIndex = slotIndex)
                        if (upTarget != null) up = upTarget
                        if (downTarget != null) down = downTarget
                    }
                    .size(width = appCardWidth, height = appCardHeight)
            }
        }
    }

    val appsAnchorHeight = 85.dp
    val appRowHeight = appCardHeight
    val appRowGap = appCardVerticalGap
    val appRowStep = appRowHeight + appRowGap
    val appSectionHeight = appRowHeight * appRows.size.toFloat() +
            appRowGap * (appRows.size - 1).coerceAtLeast(0).toFloat()
    val isAppsSectionActive = visualFocusedRowIndex >= appsRowIndex && visualFocusedRowIndex < inputsRowIndex

    var rootHeightPx by remember { mutableIntStateOf(0) }
    var recommendationsHeightPx by remember { mutableIntStateOf(0) }

    // One shared stack origin: sections flow in visibleSectionOrder from this
    // point. No section (including Apps or Recommendations) is used as an anchor.
    val defaultRecommendationsTopPx = rootHeightPx.toFloat() - recommendationsHeightPx.toFloat() -
            with(density) { 117.dp.toPx() }
    // Cache derived layout data so ordinary focus/recomposition updates do not
    // recreate the section-height and section-position maps every time.
    val sectionHeightsPx = remember(recommendationsHeightPx, appSectionHeight, density) {
        mapOf(
            "recommendations" to recommendationsHeightPx.toFloat(),
            "favorites" to with(density) { 85.dp.toPx() },
            "apps" to with(density) { appSectionHeight.toPx() },
            "inputs" to with(density) { 85.dp.toPx() },
            "games" to with(density) { 85.dp.toPx() }
        )
    }

    // Уплотняем нижние секции только при фокусе на рекомендациях.
    val compactLowerSections =
        recommendationsRowIndex != null && visualFocusedRowIndex == recommendationsRowIndex
    val compactSectionGap = 8.dp

    fun sectionGapPx(
        previous: String,
        next: String,
        compact: Boolean
    ): Float = with(density) {
        when {
            previous == "inputs" &&
                    next == "games" &&
                    providerGroups.isNotEmpty() -> {
                val channelStep =
                    (providerRowHeight + providerRowGap).toPx()

                if (compact) {
                    compactSectionGap.toPx() +
                            providerRowHeight.toPx() +
                            channelStep * (providerGroups.size - 1).coerceAtLeast(0)
                } else {
                    48.dp.toPx() +
                            channelStep * providerGroups.size
                }
            }

            compact -> compactSectionGap.toPx()

            previous == "recommendations" &&
                    next in setOf("favorites", "apps") -> 16.dp.toPx()

            previous == "favorites" && next == "apps" -> 16.dp.toPx()

            previous == "apps" && next == "inputs" -> 24.dp.toPx()

            previous == "inputs" && next == "games" -> (-3).dp.toPx()

            previous == "games" && next == "apps" -> 8.dp.toPx()

            else -> 24.dp.toPx()
        }
    }

    // Layout positions are ALWAYS the normal (non-compact) ones: entering or
    // leaving recommendations must not relayout any section. The compact shift
    // is applied as a draw-time translationY, so the whole lower half slides as
    // GPU layers instead of jumping through a full relayout of every section.
    fun computeSectionPositions(compact: Boolean): Map<String, Float> {
        val positions = mutableMapOf<String, Float>()

        if (layoutSectionOrder.isNotEmpty()) {
            positions[layoutSectionOrder.first()] = defaultRecommendationsTopPx
            for (index in 0 until layoutSectionOrder.lastIndex) {
                val previous = layoutSectionOrder[index]
                val next = layoutSectionOrder[index + 1]
                positions[next] = positions.getValue(previous) +
                        sectionHeightsPx.getValue(previous) +
                        sectionGapPx(previous, next, compact)
            }
        }
        return positions
    }

    val normalPositions = remember(
        layoutSectionOrder,
        sectionHeightsPx,
        defaultRecommendationsTopPx,
        providerGroups,
        providerRowHeight,
        providerRowGap
    ) { computeSectionPositions(compact = false) }

    val compactPositions = remember(
        layoutSectionOrder,
        sectionHeightsPx,
        defaultRecommendationsTopPx,
        providerGroups,
        providerRowHeight,
        providerRowGap
    ) { computeSectionPositions(compact = true) }

    // 0 = обычный режим, 1 = уплотнённый. Анимируется: секции «подплывают»
    // слоями, а не прыгают раскладкой.
    val compactFactor by animateFloatAsState(
        targetValue = if (compactLowerSections) 1f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = CubicBezierEasing(0.0f, 0.25f, 0.25f, 1.0f)
        ),
        label = "CompactFactor"
    )

    fun compactDelta(key: String): Float =
        ((compactPositions[key] ?: normalPositions[key] ?: 0f) -
                (normalPositions[key] ?: 0f)) * compactFactor

    val sectionTopPositionsPx = normalPositions
    // Раскладочные (статические) позиции — для offset секций.
    val favoritesRowTopPx = sectionTopPositionsPx["favorites"] ?: defaultRecommendationsTopPx
    val appsRowTopPx = sectionTopPositionsPx["apps"] ?: defaultRecommendationsTopPx

    val inputsRowTopPx = sectionTopPositionsPx["inputs"] ?: (
            appsRowTopPx +
                    with(density) { appSectionHeight.toPx() } +
                    with(density) { 24.dp.toPx() }
            )

    val gamesRowTopPx = sectionTopPositionsPx["games"] ?: (
            appsRowTopPx +
                    with(density) { appSectionHeight.toPx() } +
                    with(density) { 24.dp.toPx() }
            )

    val recommendationsTopPx = sectionTopPositionsPx["recommendations"] ?: defaultRecommendationsTopPx

    val providerRowsStartPx = inputsRowTopPx +
            with(density) { 85.dp.toPx() } +
            with(density) { 24.dp.toPx() }

// Ряды каналов находятся между входами и играми.
    val providerRowStepPx = with(density) {
        (providerRowHeight + providerRowGap).toPx()
    }

    // Раскладочные позиции рядов каналов (статические).
    val providerRowPositions = remember(
        providerGroups,
        providerRowsStartPx,
        providerRowStepPx
    ) {
        providerGroups.mapIndexed { index, group ->
            group.packageName to (
                    providerRowsStartPx + providerRowStepPx * index
                    )
        }.toMap()
    }

    // Позиции рядов каналов для таргетинга фокуса (статические — как у секций).

    val focusLineYpx = rootHeightPx * 0.5f
    val focusedAppsRowOffset = focusedRowIndex - appsRowIndex
    // The app rows have a fixed layout step, so calculate their unshifted Y directly.
    // This keeps the target independent from the foreground's current translation.
    val focusedAppsRowTopPx = if (
        rootHeightPx > 0 && focusedAppsRowOffset in appRows.indices
    ) {
        appsRowTopPx + with(density) {
            (appRowStep * focusedAppsRowOffset.toFloat()).toPx()
        }
    } else {
        Float.NaN
    }
    val appCardHalfHeightPx = with(density) { appCardHeight.toPx() / 2f }
    val gamesCardHalfHeightPx = with(density) { 74.dp.toPx() / 2f }
    val focusedProviderRowTopPx = focusedProviderPackage
        ?.let { packageName -> providerRowPositions[packageName] }
    val contentTargetPx = when {
        rootHeightPx <= 0 -> 0f

        focusedProviderRowTopPx != null ->
            focusLineYpx -
                    focusedProviderRowTopPx -
                    with(density) { 64.dp.toPx() }

        hasFavoritesSection && focusedRowIndex == favoritesRowIndex ->
            focusLineYpx - favoritesRowTopPx - appCardHalfHeightPx
        focusedAppsRowOffset in appRows.indices ->
            focusLineYpx - focusedAppsRowTopPx - appCardHalfHeightPx
        hasInputsSection && focusedRowIndex == inputsRowIndex ->
            focusLineYpx - inputsRowTopPx - appCardHalfHeightPx
        focusedRowIndex == gamesRowIndex ->
            focusLineYpx - gamesRowTopPx - gamesCardHalfHeightPx
        recommendationsRowIndex != null && focusedRowIndex == recommendationsRowIndex -> 0f
        else -> 0f
    }
    val activeSectionKey = when {
        visualFocusedAppsRowOffset in appRows.indices -> appsRowIndex // Apps, including all wrapped lines
        hasInputsSection && visualFocusedRowIndex == inputsRowIndex -> inputsRowIndex // Inputs
        games.isNotEmpty() && visualFocusedRowIndex == gamesRowIndex -> gamesRowIndex // Games
        hasFavoritesSection && visualFocusedRowIndex == favoritesRowIndex -> favoritesRowIndex // Favorites
        recommendationsRowIndex != null && visualFocusedRowIndex == recommendationsRowIndex -> recommendationsRowIndex // Recommendations
        else -> -1 // Top controls / no section
    }

    val activeSectionId = when {
        hasFavoritesSection && visualFocusedRowIndex == favoritesRowIndex -> "favorites"
        visualFocusedAppsRowOffset in appRows.indices -> "apps"
        hasInputsSection && visualFocusedRowIndex == inputsRowIndex -> "inputs"
        games.isNotEmpty() && visualFocusedRowIndex == gamesRowIndex -> "games"
        showRecommendations && visualFocusedRowIndex == 0 -> "recommendations"
        else -> null
    }
    // Для заголовков используем именно события реального фокуса, а не расчёт индексов строк.
    val titleSectionId = when (activeRowTitle) {
        "Избранное" -> "favorites"
        "Приложения" -> "apps"
        "Входы" -> "inputs"
        "Игры" -> "games"
        else -> null
    }
    // Заголовки нижних секций скрываем только пока реальный фокус находится
    // на рекомендациях. В обычной навигации используем focusedRowIndex напрямую:
    // visualFocusedRowIndex намеренно фиксируется во время перемещения карточек.
    val hideLowerSectionTitles =
        recommendationsRowIndex != null && visualFocusedRowIndex == recommendationsRowIndex

    // Keep inactive sections separated from the active one using the configured order.
    val sectionGap = 24.dp
    fun targetSectionSeparation(sectionKey: String): Dp {
        // Keep inter-section offsets based on the section where movement began.
        // Otherwise focus callbacks during reordering can animate neighbouring rows.
        val activeKey = (if (moveModePackage != null) moveModeOriginSection else activeSectionId)
            ?: return 0.dp

// При фокусе на рекомендациях нижние секции образуют единую сетку.
        if (activeKey == "recommendations") return 0.dp

        val activeIndex = visibleSectionOrder.indexOf(activeKey)
        val targetIndex = visibleSectionOrder.indexOf(sectionKey)
        if (activeIndex < 0 || targetIndex < 0 || activeIndex == targetIndex) return 0.dp
        val distance = kotlin.math.abs(targetIndex - activeIndex)
        val amount = sectionGap * distance.toFloat()
        return if (targetIndex < activeIndex) -amount else amount
    }


    val recommendationsSeparationState = animateDpAsState(
        targetValue = if (moveModePackage != null) {
            moveModeLockedSectionOffsets?.get("recommendations")
                ?: targetSectionSeparation("recommendations")
        } else {
            targetSectionSeparation("recommendations")
        },

        animationSpec = tween(
            durationMillis = 420,
            easing = CubicBezierEasing(0.0f, 0.25f, 0.25f, 1.0f)
        ),
        label = "RecommendationsSeparation"
    )
    val favoritesSeparationState = animateDpAsState(
        targetValue = if (moveModePackage != null) {
            moveModeLockedSectionOffsets?.get("favorites")
                ?: targetSectionSeparation("favorites")
        } else {
            targetSectionSeparation("favorites")
        },

        animationSpec = tween(
            durationMillis = 420,
            easing = CubicBezierEasing(0.0f, 0.25f, 0.25f, 1.0f)
        ),
        label = "FavoritesSeparation"
    )

    val contentOffset = remember { Animatable(0f) }
    val appsSeparationState = animateDpAsState(
        targetValue = if (moveModePackage != null) {
            moveModeLockedSectionOffsets?.get("apps")
                ?: targetSectionSeparation("apps")
        } else {
            targetSectionSeparation("apps")
        },


        animationSpec = tween(
            durationMillis = 420,
            easing = CubicBezierEasing(0.0f, 0.25f, 0.25f, 1.0f)
        ),
        label = "AppsSeparation"
    )

    val inputsSeparationState = animateDpAsState(
        targetValue = if (moveModePackage != null) {
            moveModeLockedSectionOffsets?.get("inputs")
                ?: targetSectionSeparation("inputs")
        } else {
            targetSectionSeparation("inputs")
        },

        animationSpec = tween(
            durationMillis = 420,
            easing = CubicBezierEasing(0.0f, 0.25f, 0.25f, 1.0f)
        ),
        label = "InputsSeparation"
    )

    val gamesSeparationState = animateDpAsState(
        targetValue = if (moveModePackage != null) {
            moveModeLockedSectionOffsets?.get("games")
                ?: targetSectionSeparation("games")
        } else {
            targetSectionSeparation("games")
        },

        animationSpec = tween(
            durationMillis = 420,
            easing = CubicBezierEasing(0.0f, 0.25f, 0.25f, 1.0f)
        ),
        label = "GamesSeparation"
    )
    // Keep the existing screen-position animation unchanged during normal navigation.
    // While a card is being rearranged, however, focus may cross app rows and update
    // contentTargetPx. Freeze the current whole-screen offset in move mode so only the
    // card arrangement changes; when move mode ends, normal positioning resumes.

    // A settings reorder must not translate the whole launcher.
    val latestFocusTarget = rememberUpdatedState(
        Triple(activeSectionKey, contentTargetPx, moveModePackage != null) to sectionOrder
    )

    LaunchedEffect(Unit) {
        var firstTarget = true
        var previousActiveSectionKey: Int? = null
        var previousSectionOrder: List<String>? = null

        snapshotFlow { latestFocusTarget.value }
            .collectLatest { (focusTarget, currentSectionOrder) ->
                val (_, targetPx, isRearranging) = focusTarget

                val sectionOrderChanged =
                    previousSectionOrder != null &&
                            previousSectionOrder != currentSectionOrder

                previousSectionOrder = currentSectionOrder

                if (isRearranging) {
                    contentOffset.stop()
                    return@collectLatest
                }

                if (sectionOrderChanged) {
                    // A section reorder changes the geometry of every row. The previous
                    // focus offset was calculated for the old geometry; keeping it makes
                    // the entire launcher jump when a section is moved to the top.
                    // Reset only this stale translation, then let normal focus navigation
                    // calculate a fresh target on the next focus change.
                    contentOffset.snapTo(0f)
                    firstTarget = false
                    return@collectLatest
                }

                val sameSection = previousActiveSectionKey == focusTarget.first
                previousActiveSectionKey = focusTarget.first

                if (sameSection && !firstTarget) {
                    if (kotlin.math.abs(contentOffset.value - targetPx) > 1f) {
                        contentOffset.animateTo(
                            targetValue = targetPx,
                            animationSpec = tween(
                                durationMillis = 180,
                                easing = FastOutSlowInEasing
                            )
                        )
                    }
                    return@collectLatest
                }
                if (firstTarget) {
                    firstTarget = false
                    contentOffset.snapTo(targetPx)
                } else if (kotlin.math.abs(contentOffset.value - targetPx) > 1f) {
                    contentOffset.animateTo(
                        targetPx,
                        animationSpec = tween(
                            durationMillis = 180,
                            easing = FastOutSlowInEasing
                        )
                    )
                }
            }
    }

    // Hard-lock the rendered translation during move mode. Stopping Animatable alone is not
    // enough if focus changes produce new targets while cards are being reordered.

    // Capture the wallpaper parallax target only when the active section changes.
    // Movement between multiple app lines therefore cannot move the wallpaper.
    var stableBackgroundTargetPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(activeSectionKey, sectionOrder) {
        stableBackgroundTargetPx = when {
            hasFavoritesSection && activeSectionKey == favoritesRowIndex -> with(density) { (-30).dp.toPx() }
            activeSectionKey == appsRowIndex -> contentTargetPx * 0.27f
            hasInputsSection && activeSectionKey == inputsRowIndex -> contentTargetPx * 0.27f
            games.isNotEmpty() && activeSectionKey == gamesRowIndex -> contentTargetPx * 0.28f
            else -> 0f
        }
    }
    val bgOffsetY by animateFloatAsState(
        targetValue = if (moveModePackage != null && moveModeLockedBackgroundOffsetPx.isFinite()) {
            moveModeLockedBackgroundOffsetPx
        } else stableBackgroundTargetPx,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "BgParallax"
    )

    val recommendationsRowScale by animateFloatAsState(
        targetValue = if (recommendationsRowIndex != null && visualFocusedRowIndex == recommendationsRowIndex) 1.02f else 0.98f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "RecommendationsRowScale"
    )
    val recommendationsRowAlpha by animateFloatAsState(
        targetValue = if (recommendationsRowIndex != null && visualFocusedRowIndex == recommendationsRowIndex) 1f else 0.74f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "RecommendationsRowAlpha"
    )
    val favoritesRowScale by animateFloatAsState(
        targetValue = if (hasFavoritesSection && visualFocusedRowIndex == favoritesRowIndex) 1.02f else 0.98f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "FavoritesRowScale"
    )
    val favoritesRowAlpha by animateFloatAsState(
        targetValue = if (hasFavoritesSection && visualFocusedRowIndex == favoritesRowIndex) 1f else 0.74f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "FavoritesRowAlpha"
    )
    // This state exists even while the row is absent, so the first favorite fades
    // in instead of popping into the layout at full opacity.
    val favoritesRowEntranceAlpha by animateFloatAsState(
        targetValue = if (hasFavoritesSection) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "FavoritesRowEntranceAlpha"
    )
    val appsRowScale by animateFloatAsState(
        targetValue = if (isAppsSectionActive) 1.02f else 1f,
        animationSpec = tween(
            durationMillis = 220,
            easing = FastOutSlowInEasing
        ),
        label = "AppsRowScale"
    )
    val appsRowAlpha by animateFloatAsState(
        targetValue = if (isAppsSectionActive) 1f else 0.74f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "AppsRowAlpha"
    )
    val inputsRowScale by animateFloatAsState(
        targetValue = if (hasInputsSection && visualFocusedRowIndex == inputsRowIndex) 1.02f else 0.98f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "InputsRowScale"
    )
    val inputsRowAlpha by animateFloatAsState(
        targetValue = if (hasInputsSection && visualFocusedRowIndex == inputsRowIndex) 1f else 0.74f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "InputsRowAlpha"
    )
    val gamesRowScale by animateFloatAsState(
        targetValue = if (visualFocusedRowIndex == gamesRowIndex) 1.02f else 0.98f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "GamesRowScale"
    )
    val gamesRowAlpha by animateFloatAsState(
        targetValue = if (visualFocusedRowIndex == gamesRowIndex) 1f else 0.74f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "GamesRowAlpha"
    )

    var promoBanners by remember { mutableStateOf(emptyList<PromoBanner>()) }

    LaunchedEffect(Unit) {
        promoBanners = runCatching {
            FirebaseAdsRepository.fetchBanners().map { remoteBanner ->
                val aspectRatio = if (remoteBanner.isVertical) {
                    16f / 9f
                } else {
                    resolveBannerAspectRatio(context, remoteBanner.imageUrl)
                }
                PromoBanner(
                    id = remoteBanner.id,
                    title = remoteBanner.title,
                    brand = remoteBanner.brand,
                    description = remoteBanner.description,
                    bannerResId = R.drawable.bg_default,
                    isVertical = remoteBanner.isVertical,
                    onClick = {
                        val clickUrl = remoteBanner.clickUrl
                        if (!clickUrl.isNullOrBlank()) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(clickUrl))
                                )
                            }.onFailure { error ->
                                Log.w("FirebaseAds", "Не удалось открыть рекламную ссылку", error)
                            }
                        }
                    },
                    imageUrl = remoteBanner.imageUrl,
                    aspectRatio = aspectRatio
                )
            }
        }.onFailure { error ->
            Log.w("FirebaseAds", "Не удалось загрузить баннеры; продолжаем без рекламы", error)
        }.getOrDefault(emptyList())
    }

    val mixChannelIdsSnapshot by remember(mixChannelIds) {
        derivedStateOf { mixChannelIds.toList() }
    }

    val mixedPrograms = remember(channels, mixChannelIdsSnapshot) {
        if (channels.isEmpty()) {
            emptyList()
        } else {
            val allPrograms = mutableListOf<LiveProgramInfo>()
            channels.forEach { allPrograms.addAll(it.programs) }

            val watchNext = allPrograms
                .filter { it.channelId == -1L }
                .sortedByDescending { it.playbackPositionMillis }

            val otherPrograms = allPrograms
                .filter { it.channelId != -1L }
                .distinctBy { it.title }

            fun isProgramInMix(program: LiveProgramInfo): Boolean {
                val isTvirl = program.channelName?.contains("TViRL", ignoreCase = true) == true
                return if (isTvirl) {
                    mixChannelIdsSnapshot.contains(-999L) || mixChannelIdsSnapshot.contains(program.channelId)
                } else {
                    mixChannelIdsSnapshot.contains(program.channelId)
                }
            }

            val shuffledPart = otherPrograms
                .filter { isProgramInMix(it) }
                .shuffled()

            val sequentialPart = otherPrograms
                .filter { !isProgramInMix(it) }
                .groupBy { it.channelName ?: "Другое" }
                .values
                .flatten()

            watchNext + shuffledPart + sequentialPart
        }
    }

    val recommendationFeedItems = remember(mixedPrograms, promoBanners) {
        val feed = mutableListOf<RecommendationFeedItem>()
        val programs = mixedPrograms.take(50)
        var bannerIndex = 0

        programs.forEachIndexed { index, program ->
            val programKey = program.intentUri?.toString()
                ?: "${program.channelId}_${program.id}"
            feed += RecommendationFeedItem.Program(program, "program:$programKey")

            if ((index + 1) % 4 == 0 && bannerIndex < promoBanners.size) {
                feed += RecommendationFeedItem.Advertisement(promoBanners[bannerIndex])
                bannerIndex++
            }
        }

        while (bannerIndex < promoBanners.size) {
            feed += RecommendationFeedItem.Advertisement(promoBanners[bannerIndex])
            bannerIndex++
        }

        feed
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isOverlay) Color.Transparent else Color.Black)
            .onSizeChanged { size ->
                if (rootHeightPx != size.height) rootHeightPx = size.height
            }
            .onPreviewKeyEvent { keyEvent ->
                val nativeEvent = keyEvent.nativeKeyEvent
                val keyCode = nativeEvent.keyCode

                // Consume the matching key-up even if OK/BACK already ended move mode
                // on key-down, so that key release cannot launch the app or exit the screen.
                if (nativeEvent.action == KeyEvent.ACTION_UP && keyCode == moveModeConsumedKeyUp) {
                    moveModeConsumedKeyUp = KeyEvent.KEYCODE_UNKNOWN
                    return@onPreviewKeyEvent true
                }

                val isMoveModeKey = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A,
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_ESCAPE -> true
                    else -> false
                }

                // Move mode is controlled directly with the TV remote. Each arrow
                // immediately swaps the focused app with its neighbour; OK finishes.
                val movingPackage = moveModePackage
                if (movingPackage != null && isMoveModeKey) {
                    if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                        moveModeConsumedKeyUp = keyCode
                        // One reposition per physical press. Holding an arrow must not skip
                        // over several cards before focus has reattached to the moved card.
                        if (nativeEvent.repeatCount == 0) when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> onMoveApp(
                                movingPackage, -1, moveModeIsGame, moveModeOriginSection ?: "apps"
                            )
                            KeyEvent.KEYCODE_DPAD_RIGHT -> onMoveApp(
                                movingPackage, 1, moveModeIsGame, moveModeOriginSection ?: "apps"
                            )
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (moveModeOriginSection == "apps") {
                                    onMoveApp(movingPackage, -normalizedMaxAppsPerRow, false, "apps")
                                }
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (moveModeOriginSection == "apps") {
                                    onMoveApp(movingPackage, normalizedMaxAppsPerRow, false, "apps")
                                }
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_BUTTON_A,
                            KeyEvent.KEYCODE_BACK,
                            KeyEvent.KEYCODE_ESCAPE -> {
                                moveModePackage = null
                                moveModeOriginSection = null
                                moveModeLockedSectionOffsets = null
                                moveModeLockedBackgroundOffsetPx = Float.NaN
                            }
                        }
                    }
                    return@onPreviewKeyEvent true
                }

                // While fast-scrolling, key repeats only move focus. Skip all state
                // bookkeeping for them: the repeat flood at ~30 events/sec otherwise
                // saturates the main thread on TV boxes and the launcher looks frozen
                // until the key is released.
                if (isFastScrolling &&
                    nativeEvent.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                ) {
                    return@onPreviewKeyEvent false
                }

                if (nativeEvent.action == KeyEvent.ACTION_DOWN) {

                    // Use Android's native navigation effects, like the TV launcher.
                    // Play only on the initial key-down, not on long-press repeats.
                    if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                        val navigationSound = when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> SoundEffectConstants.NAVIGATION_LEFT
                            KeyEvent.KEYCODE_DPAD_RIGHT -> SoundEffectConstants.NAVIGATION_RIGHT
                            KeyEvent.KEYCODE_DPAD_UP -> SoundEffectConstants.NAVIGATION_UP
                            KeyEvent.KEYCODE_DPAD_DOWN -> SoundEffectConstants.NAVIGATION_DOWN
                            else -> null
                        }
                        navigationSound?.let {
                            if (tickSoundLoaded && tickSoundId != 0) {
                                navigationSoundPool.play(
                                    tickSoundId,
                                    0.55f,
                                    0.55f,
                                    1,
                                    0,
                                    1.0f
                                )
                            }
                        }
                    }

                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        val now = System.currentTimeMillis()
                        fastScrollResetJob?.cancel()
                        if (!isHoldingLeftRight) {
                            if (lastHorizontalDownTime != 0L && now - lastHorizontalDownTime < 240L) {
                                isFastScrolling = true
                            }
                            isHoldingLeftRight = true
                            leftRightPressStartTime = now
                        } else if (keyEvent.nativeKeyEvent.repeatCount > 0 || now - leftRightPressStartTime > 240L) {
                            isFastScrolling = true
                        }
                        lastHorizontalDownTime = now
                    }
                } else if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        isHoldingLeftRight = false
                        leftRightPressStartTime = 0L
                        fastScrollResetJob?.cancel()
                        fastScrollResetJob = inputScope.launch {
                            delay(220L)
                            isFastScrolling = false
                        }
                    }
                }
                false
            }
    ) {
        if (!isOverlay) {
            LauncherBackground(
                backgroundUri = currentBgUri,
                offsetY = bgOffsetY
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = if (moveModePackage != null) {
                        moveModeLockedContentOffsetPx
                    } else {
                        contentOffset.value
                    }
                }
        ) {
            providerGroups.forEachIndexed { groupIndex, group ->
                val rowTop = providerRowPositions[group.packageName]
                    ?: return@forEachIndexed

                val channelRequesters =
                    providerFocusRequesters[group.packageName].orEmpty()

                val upRequesters = if (groupIndex > 0) {
                    providerFocusRequesters[
                        providerGroups[groupIndex - 1].packageName
                    ].orEmpty()
                } else {
                    inputFocusRequesters
                }

                val downRequesters = if (groupIndex < providerGroups.lastIndex) {
                    providerFocusRequesters[
                        providerGroups[groupIndex + 1].packageName
                    ].orEmpty()
                } else {
                    gameFocusRequesters
                }

                key(group.packageName) {
                    ProviderLogoRow(
                        group = group,
                        onChannelClick = onProgramClick,
                        isFastScrolling = isFastScrolling,
                        channelFocusRequesters = channelRequesters,
                        upFocusRequesters = upRequesters,
                        downFocusRequesters = downRequesters,
                        onChannelFocus = { channelIndex ->
                            focusedProviderPackage = group.packageName

                            lastFocusedProviderIndices =
                                lastFocusedProviderIndices +
                                        (group.packageName to channelIndex)

                            activeRowTitle = group.title
                        },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset {
                                IntOffset(0, rowTop.roundToInt())
                            }
                            .graphicsLayer {
                                translationY = compactDelta(group.packageName)
                            }
                            .fillMaxWidth()
                            .height(providerRowHeight)
                    )
                }
            }
            val voiceHints = remember {
                context.resources
                    .getStringArray(R.array.search_orb_text_to_show)
                    .toList()
            }

            var voiceHintIndex by remember {
                mutableIntStateOf(0)
            }

            LaunchedEffect(voiceHints.size) {
                if (voiceHints.isNotEmpty()) {
                    while (true) {
                        delay(5_000L)
                        voiceHintIndex =
                            (voiceHintIndex + 1) % voiceHints.size
                    }
                }
            }
            // ВЕРХНЯЯ ПАНЕЛЬ
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 48.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = Intent("android.intent.action.ASSIST").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (ex: Exception) {
                                Toast.makeText(context, "Голосовой поиск недоступен", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color(0x44FFFFFF)
                    ),
                    modifier = Modifier
                        .size(49.dp)
                        .offset(x = 10.dp)
                        .onFocusChanged {
                            if (it.isFocused) {
                                focusedRowIndex = -1
                                activeRowTitle = null
                            }
                        }
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_microphone),
                            contentDescription = "Поиск",
                            modifier = Modifier.size(49.dp).clip(CircleShape)
                        )
                    }
                }
                if (voiceHints.isNotEmpty()) {
                    AnimatedContent(
                        targetState = voiceHints[voiceHintIndex],
                        transitionSpec = {
                            (
                                    slideInVertically { height -> height / 2 } +
                                            fadeIn()
                                    ) togetherWith (
                                    slideOutVertically { height -> -height / 2 } +
                                            fadeOut()
                                    )
                        },
                        label = "voiceHintAnimation"
                    ) { hint ->
                        Text(
                            text = hint,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 18.sp,
                            fontStyle = FontStyle.Italic,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(598.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Surface(
                        onClick = { focusManager.clearFocus(force = true); onOpenSettings() },
                        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color(0x22FFFFFF)
                        ),
                        modifier = Modifier
                            .size(50.dp)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    focusedRowIndex = -1
                                    activeRowTitle = null
                                }
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = "Настройки",
                                modifier = Modifier.size(32.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                    LauncherClock()
                }
            }

            // РЕКОМЕНДАЦИИ И ЯКОРЬ ПРИЛОЖЕНИЙ
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(x = 0, y = recommendationsTopPx.roundToInt())
                    }
                    .fillMaxWidth()
                    .wrapContentHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (showRecommendations) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                if (recommendationsHeightPx != size.height) {
                                    recommendationsHeightPx = size.height
                                }
                            }
                            .onFocusChanged {
                                if (it.hasFocus) {
                                    recommendationsRowIndex?.let { idx -> focusedRowIndex = idx }
                                    activeRowTitle = null
                                }
                            }
                            .graphicsLayer {
                                // Separation is a draw-time translation, not a layout
                                // offset: animating it must not relayout the section.
                                translationY = recommendationsSeparationState.value.toPx()
                            }
                            .zIndex(if (recommendationsRowIndex != null && visualFocusedRowIndex == recommendationsRowIndex) 5f else 1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = recommendationsRowAlpha
                                    scaleX = recommendationsRowScale
                                    scaleY = recommendationsRowScale
                                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                                }
                                .zIndex(if (recommendationsRowIndex != null && visualFocusedRowIndex == recommendationsRowIndex) 5f else 1f)
                        ) {
                            if (isRecommendationsLoading && mixedPrograms.isEmpty() && promoBanners.isEmpty()) {
                                LazyRow(
                                    state = recommendationsListState,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight()
                                ) {
                                    items(1) {
                                        LoadingBanner()
                                    }
                                }
                            } else {
                                val alphaAnimated by animateFloatAsState(
                                    targetValue = 1f,
                                    animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                                    label = "BannersFadeIn"
                                )

                                LazyRow(
                                    state = recommendationsListState,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
                                    modifier = Modifier
                                        .focusRestorer()
                                        .fillMaxWidth()
                                        .wrapContentHeight()
                                        .graphicsLayer { alpha = alphaAnimated }
                                ) {
                                    items(
                                        items = recommendationFeedItems,
                                        key = { feedItem -> feedItem.stableKey },
                                        contentType = { feedItem ->
                                            when (feedItem) {
                                                is RecommendationFeedItem.Program -> "program_card"
                                                is RecommendationFeedItem.Advertisement -> "recommendation_card"
                                            }
                                        }
                                    ) { feedItem ->
                                        when (feedItem) {
                                            is RecommendationFeedItem.Program -> {
                                                val program = feedItem.program
                                                var isCardFocused by remember { mutableStateOf(false) }

                                                Box(
                                                    modifier = Modifier
                                                        .zIndex(if (isCardFocused && !isFastScrolling) 100f else 1f)
                                                        .onFocusChanged { isCardFocused = it.isFocused }
                                                ) {
                                                    LeanbackCard(
                                                        program = program,
                                                        onProgramClick = onProgramClick,
                                                        isFastScrolling = isFastScrolling
                                                    )
                                                }
                                            }

                                            is RecommendationFeedItem.Advertisement -> {
                                                LeanbackPromoCard(
                                                    banner = feedItem.banner,
                                                    isFastScrolling = isFastScrolling,
                                                    onClick = { feedItem.banner.onClick() }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(appsAnchorHeight)
                )
            }

            // ОТДЕЛЬНЫЙ РЯД ИЗБРАННОГО. Избранные приложения также остаются
            // в полном списке приложений; этот ряд даёт быстрые закреплённые ярлыки.
            if (rootHeightPx > 0 && hasFavoritesSection) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(85.dp)
                        .offset {
                            IntOffset(x = 0, y = favoritesRowTopPx.roundToInt())
                        }
                        .graphicsLayer {
                            translationY = favoritesSeparationState.value.toPx() +
                                    compactDelta("favorites")
                        }
                        .onFocusChanged { focusState ->
                            if (focusState.hasFocus) {
                                focusedRowIndex = favoritesRowIndex
                                activeRowTitle = "Избранное"
                            }
                        }
                        .zIndex(if (visualFocusedRowIndex == favoritesRowIndex) 5f else 1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = favoritesRowAlpha * favoritesRowEntranceAlpha
                                scaleX = favoritesRowScale
                                scaleY = favoritesRowScale
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }
                            .zIndex(if (visualFocusedRowIndex == favoritesRowIndex) 5f else 1f)
                    ) {
                        val favoriteGamePackageNames = remember(games) {
                            games.mapTo(HashSet()) { it.packageName }
                        }

                        Row(
                            modifier = Modifier
                                .focusRestorer()
                                .fillMaxWidth()
                                .height(85.dp)
                                .horizontalScroll(favoritesHorizontalScrollState)
                                .padding(horizontal = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            favoriteRowApps.forEachIndexed { favoriteIndex, favoriteApp ->
                                key(favoriteApp.packageName) {
                                    val isGameFavorite =
                                        favoriteApp.packageName in favoriteGamePackageNames
                                    val upRequester = sectionNeighborRequester(
                                        "favorites", moveUp = true, preferredIndex = favoriteIndex
                                    )
                                    val downRequester = sectionNeighborRequester(
                                        "favorites", moveUp = false, preferredIndex = favoriteIndex
                                    )
                                    AppCard(
                                        painter = rememberAsyncImagePainter(favoriteApp.icon),
                                        onClick = {
                                            hostView.playSoundEffect(SoundEffectConstants.CLICK)
                                            onAppClick(favoriteApp.packageName)
                                        },
                                        onLongClick = {
                                            selectedAppForMenu = favoriteApp
                                            selectedAppIsGame = isGameFavorite
                                            selectedAppOriginSection = "favorites"
                                        },
                                        isFastScrolling = isFastScrolling,
                                        isMoveMode = moveModePackage == favoriteApp.packageName &&
                                                moveModeOriginSection == "favorites",
                                        onFocus = {
                                            focusedProviderPackage = null
                                            focusedRowIndex = favoritesRowIndex
                                            focusedAppColumnIndex = favoriteIndex
                                            lastFocusedFavoriteIndex = favoriteIndex
                                            activeRowTitle = "Избранное"
                                        },
                                        modifier = Modifier
                                            .focusRequester(favoriteFocusRequesters[favoriteIndex])
                                            .focusProperties {
                                                if (upRequester != null) up = upRequester
                                                if (downRequester != null) down = downRequester
                                            }
                                            .size(width = 130.dp, height = 74.dp)
                                    )
                                }
                            }
                        }
                        val favoritesTitleAlpha by animateFloatAsState(
                            targetValue = if (!hideLowerSectionTitles && titleSectionId == "favorites") 1f else 0f,
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                            label = "FavoritesTitleAlpha"
                        )
                        Text(
                            text = stringResource(R.string.section_favorites),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(y = (-30).dp)
                                .padding(start = 48.dp)
                                .zIndex(200f)
                                .graphicsLayer { alpha = favoritesTitleAlpha }
                        )
                    }
                }
            }

            // РЯДЫ ПРИЛОЖЕНИЙ (БЕЗ ВИРТУАЛИЗАЦИИ, СО ВСЕМИ ЖИВЫМИ ФОКУС-РЕКВЕСТЕРАМИ)
            if (rootHeightPx > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(appSectionHeight)
                        .offset {
                            IntOffset(x = 0, y = appsRowTopPx.roundToInt())
                        }
                        .graphicsLayer {
                            translationY = appsSeparationState.value.toPx() +
                                    compactDelta("apps")
                        }
                        .graphicsLayer {
                            alpha = appsRowAlpha
                            scaleX = appsRowScale
                            scaleY = appsRowScale
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        }
                        .zIndex(if (isAppsSectionActive) 5f else 1f)
                ) {
                    // Заменяем LazyRow на обычный Row с horizontalScroll, чтобы ни одна колонка не удалялась из памяти
                    LazyRow(
                        state = appsHorizontalListState,
                        modifier = Modifier
                            .focusRestorer()
                            .fillMaxWidth()
                            .height(appSectionHeight),
                        contentPadding = PaddingValues(horizontal = 55.dp),
                        horizontalArrangement = Arrangement.spacedBy(appCardHorizontalGap)
                    ) {
                        items(
                            count = normalizedMaxAppsPerRow,
                            key = { slotIndex -> "apps-column-$slotIndex" },
                            contentType = { "apps-column" }
                        ) { slotIndex ->
                            Column(
                                modifier = Modifier
                                    .zIndex(
                                        if (
                                            isAppsSectionActive &&
                                            focusedAppColumnIndex == slotIndex
                                        ) 10f else 0f
                                    )
                                    .width(appCardWidth)
                                    .height(appSectionHeight),
                                verticalArrangement = Arrangement.spacedBy(appCardVerticalGap)
                            ) {
                                for (rowOffset in appRows.indices) {
                                    val rowApps = appRows[rowOffset]
                                    val app = rowApps.getOrNull(slotIndex)

                                    key(app?.packageName ?: "empty_app_row_${rowOffset}_$slotIndex") {
                                        Box(
                                            modifier = Modifier
                                                .width(appCardWidth)
                                                .height(appRowHeight)
                                        ) {
                                            if (app != null && app.icon != null) {
                                                // Лямбды кешируются, чтобы карточки сетки
                                                // пропускались рекомпозицией при навигации.
                                                val onGridAppClick = remember(app) {
                                                    {
                                                        hostView.playSoundEffect(SoundEffectConstants.CLICK)
                                                        onAppClick(app.packageName)
                                                    }
                                                }
                                                val onGridAppLongClick = remember(app) {
                                                    {
                                                        selectedAppForMenu = app
                                                        selectedAppIsGame = false
                                                        selectedAppOriginSection = "apps"
                                                    }
                                                }
                                                val onGridAppFocus = remember(app, rowOffset, slotIndex, appsRowIndex) {
                                                    {
                                                        focusedProviderPackage = null
                                                        focusedRowIndex = appsRowIndex + rowOffset
                                                        focusedAppColumnIndex = slotIndex
                                                        lastFocusedAppsRowOffset = rowOffset
                                                        lastFocusedAppsColumn = slotIndex
                                                        activeRowTitle = "Приложения"
                                                    }
                                                }
                                                AppCard(
                                                    painter = rememberAsyncImagePainter(app.icon),
                                                    onClick = onGridAppClick,
                                                    onLongClick = onGridAppLongClick,
                                                    isFastScrolling = isFastScrolling,
                                                    isMoveMode = moveModePackage == app.packageName &&
                                                            moveModeOriginSection == "apps",
                                                    onFocus = onGridAppFocus,
                                                    modifier = gridCardModifiers.getOrNull(rowOffset)
                                                        ?.getOrNull(slotIndex)
                                                        ?: Modifier.size(appCardWidth, appCardHeight)
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(width = appCardWidth, height = appCardHeight))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val appsTitleAlpha by animateFloatAsState(
                        targetValue = if (!hideLowerSectionTitles && titleSectionId == "apps") 1f else 0f,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        label = "AppsTitleAlpha"
                    )
                    Text(
                        text = stringResource(R.string.section_apps),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(y = (-30).dp)
                            .padding(start = 48.dp)
                            .zIndex(200f)
                            .graphicsLayer { alpha = appsTitleAlpha }
                    )
                }
            }

            // РЯД ВХОДОВ HDMI / AV — отображаем только входы, найденные системой.
            if (hasInputsSection && rootHeightPx > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(85.dp)
                        .offset {
                            IntOffset(x = 0, y = inputsRowTopPx.roundToInt())
                        }
                        .graphicsLayer {
                            translationY = inputsSeparationState.value.toPx() +
                                    compactDelta("inputs")
                        }
                        .onFocusChanged { focusState ->
                            if (focusState.hasFocus) {
                                focusedRowIndex = inputsRowIndex
                                activeRowTitle = "Входы"
                            }
                        }
                        .zIndex(if (visualFocusedRowIndex == inputsRowIndex) 5f else 1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = inputsRowAlpha
                                scaleX = inputsRowScale
                                scaleY = inputsRowScale
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }
                            .zIndex(if (visualFocusedRowIndex == inputsRowIndex) 5f else 1f)
                    ) {
                        LazyRow(
                            state = inputsListState,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            modifier = Modifier
                                .focusRestorer()
                                .fillMaxWidth()
                                .height(85.dp)
                        ) {
                            itemsIndexed(
                                items = tvInputs,
                                key = { index: Int, input: TvInputItem -> "input_${input.id}" },
                                contentType = { index: Int, item: TvInputItem -> "input_card" }
                            ) { inputIndex, input ->
                                val upRequester = sectionNeighborRequester("inputs", moveUp = true, preferredIndex = inputIndex)
                                val downRequester = sectionNeighborRequester("inputs", moveUp = false, preferredIndex = inputIndex)

                                InputCard(
                                    input = input,
                                    onClick = {
                                        val launchIntent = input.intent
                                        if (launchIntent != null) {
                                            if (inputSwitchSoundEnabled) {
                                                playSourceTransitionSound(context)
                                            }
                                            runCatching { context.startActivity(launchIntent) }
                                                .onFailure { error ->
                                                    Log.w("TvInput", "Не удалось открыть вход ${input.label}", error)
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.input_open_failed,
                                                            input.label
                                                        ),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                        } else {
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.input_switch_unavailable,
                                                    input.label
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    onFocus = {
                                        focusedProviderPackage = null
                                        focusedRowIndex = inputsRowIndex
                                        lastFocusedInputIndex = inputIndex
                                        activeRowTitle = "Входы"
                                    },
                                    modifier = Modifier
                                        .focusRequester(inputFocusRequesters[inputIndex])
                                        .focusProperties {
                                            if (upRequester != null) up = upRequester
                                            if (downRequester != null) {
                                                down = downRequester
                                            }
                                        }
                                        .size(width = 130.dp, height = 74.dp)
                                )
                            }
                        }

                        val inputsTitleAlpha by animateFloatAsState(
                            targetValue = if (!hideLowerSectionTitles && titleSectionId == "inputs") 1f else 0f,
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                            label = "InputsTitleAlpha"
                        )
                        Text(
                            text = stringResource(R.string.section_inputs),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(y = (-30).dp)
                                .padding(start = 48.dp)
                                .zIndex(200f)
                                .graphicsLayer { alpha = inputsTitleAlpha }
                        )
                    }
                }
            }

            // РЯД ИГР
            if (games.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(85.dp)
                        .offset {
                            IntOffset(x = 0, y = gamesRowTopPx.roundToInt())
                        }
                        .graphicsLayer {
                            translationY = gamesSeparationState.value.toPx() +
                                    compactDelta("games")
                        }
                        .onFocusChanged { focusState ->
                            if (focusState.hasFocus) {
                                focusedRowIndex = gamesRowIndex
                                activeRowTitle = "Игры"
                            }
                        }
                        .zIndex(if (visualFocusedRowIndex == gamesRowIndex) 5f else 1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = gamesRowAlpha
                                scaleX = gamesRowScale
                                scaleY = gamesRowScale
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }
                            .zIndex(if (visualFocusedRowIndex == gamesRowIndex) 5f else 1f)
                    ) {
                        LazyRow(
                            state = gamesListState,
                            horizontalArrangement = Arrangement.spacedBy(8.dp / gamesRowScale),
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            modifier = Modifier
                                .focusRestorer()
                                .fillMaxWidth()
                                .height(85.dp)
                        ) {
                            itemsIndexed(
                                items = games,
                                key = { index: Int, app: TvAppInfo -> "game_${app.packageName}" },
                                contentType = { index: Int, app: TvAppInfo -> "game_card" }
                            ) { gameIndex, game ->
                                if (game.icon != null) {
                                    val upRequester = sectionNeighborRequester("games", moveUp = true, preferredIndex = gameIndex)
                                    val downRequester = sectionNeighborRequester("games", moveUp = false, preferredIndex = gameIndex)

                                    AppCard(
                                        painter = rememberAsyncImagePainter(game.icon),
                                        onClick = {
                                            hostView.playSoundEffect(SoundEffectConstants.CLICK)
                                            onAppClick(game.packageName)
                                        },
                                        onLongClick = {
                                            selectedAppForMenu = game
                                            selectedAppIsGame = true
                                            selectedAppOriginSection = "games"
                                        },
                                        isFastScrolling = isFastScrolling,
                                        isMoveMode = moveModePackage == game.packageName &&
                                                moveModeOriginSection == "games",
                                        onFocus = {
                                            focusedProviderPackage = null
                                            focusedRowIndex = gamesRowIndex
                                            lastFocusedGameIndex = gameIndex
                                            activeRowTitle = "Игры"
                                        },
                                        modifier = Modifier
                                            .focusRequester(gameFocusRequesters[gameIndex])
                                            .focusProperties {
                                                if (upRequester != null) up = upRequester
                                                if (downRequester != null) {
                                                    down = downRequester
                                                }
                                            }
                                            .size(width = 130.dp, height = 74.dp)
                                    )
                                }
                            }
                        }
                        val gamesTitleAlpha by animateFloatAsState(
                            targetValue = if (!hideLowerSectionTitles && titleSectionId == "games") 1f else 0f,
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                            label = "GamesTitleAlpha"
                        )
                        Text(
                            text = stringResource(R.string.section_games),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(y = (-30).dp)
                                .padding(start = 48.dp)
                                .zIndex(200f)
                                .graphicsLayer { alpha = gamesTitleAlpha }
                        )
                    }
                }
            }
        }

        // Fixed on-screen hint for remote-controlled visual reordering.
        if (moveModePackage != null) {
            val movingAppName = (apps + games).firstOrNull {
                it.packageName == moveModePackage
            }?.name ?: "Приложение"
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .zIndex(1000f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xE6222222))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(
                            R.string.move_app_title,
                            movingAppName
                        ),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (moveModeOriginSection == "apps") {
                            "← → переместить · ↑ ↓ между строками · OK завершить · Назад выйти"
                        } else {
                            "← → переместить · OK завершить · Назад выйти"
                        },
                        color = Color(0xFFCCCCCC),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // МЕНЮ ПРИЛОЖЕНИЯ ПО ДОЛГОМУ НАЖАТИЮ
        selectedAppForMenu?.let { app ->
            val selectedIsFavorite = app.packageName in favoritePackages

            Dialog(onDismissRequest = { selectedAppForMenu = null }) {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF252525))
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = app.name,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Surface(
                            onClick = {
                                favoriteFocusRestorePackage = app.packageName
                                // When adding a favorite, move focus to its new tile in the
                                // Favorites row. That row occupies the former Apps anchor,
                                // so the launcher does not scroll vertically just because
                                // the extra row was inserted. When removing one, restore the
                                // card to its original Apps/Games section as before.
                                favoriteFocusRestoreSection = if (!selectedIsFavorite) {
                                    "favorites"
                                } else {
                                    when (selectedAppOriginSection) {
                                        "favorites" -> if (selectedAppIsGame) "games" else "apps"
                                        else -> selectedAppOriginSection
                                    }
                                }
                                onToggleFavorite(app.packageName)
                                selectedAppForMenu = null
                            },
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0x11FFFFFF),
                                focusedContainerColor = Color(0x33FFFFFF)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = if (selectedIsFavorite) stringResource(R.string.favorite_add) else stringResource(R.string.favorite_remove),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Light
                                )
                            }
                        }

                        Surface(
                            onClick = {
                                // Snapshot all independently animated positions before entering move mode.
                                // This keeps the content, section gaps, and wallpaper stationary while
                                // focus follows the reordered card. Existing animations resume on exit.
                                moveModeLockedContentOffsetPx = contentOffset.value
                                moveModeStartFocusedRowIndex = focusedRowIndex
                                moveModeLockedSectionOffsets = mapOf<String, Dp>(
                                    "recommendations" to recommendationsSeparationState.value,
                                    "favorites" to favoritesSeparationState.value,
                                    "apps" to appsSeparationState.value,
                                    "inputs" to inputsSeparationState.value,
                                    "games" to gamesSeparationState.value
                                )
                                moveModeLockedBackgroundOffsetPx = bgOffsetY
                                moveModePackage = app.packageName
                                moveModeIsGame = selectedAppIsGame
                                moveModeOriginSection = selectedAppOriginSection
                                selectedAppForMenu = null
                            },
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0x11FFFFFF),
                                focusedContainerColor = Color(0x33FFFFFF)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = stringResource(R.string.move_with_remote),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Light
                                )
                            }
                        }

                        Surface(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", app.packageName, null)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {}
                                selectedAppForMenu = null
                            },
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0x22FFFFFF),
                                focusedContainerColor = Color(0x55FFFFFF)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                                Text(text = "О приложении", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Light)
                            }
                        }

                        Surface(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_DELETE).apply {
                                        data = Uri.fromParts("package", app.packageName, null)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {}
                                selectedAppForMenu = null
                            },
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0x33FF5252),
                                focusedContainerColor = Color(0x66FF5252)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                                Text(text = "Удалить", color = Color(0xFFFF8A80), fontSize = 14.sp, fontWeight = FontWeight.Normal)
                            }
                        }

                        Surface(
                            onClick = { selectedAppForMenu = null },
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0x11FFFFFF),
                                focusedContainerColor = Color(0x33FFFFFF)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(text = "Отмена", color = Color(0xFFAAAAAA), fontSize = 14.sp, fontWeight = FontWeight.Light)
                            }
                        }
                    }
                }
            }
        }
    }
}

// === ЭКРАН НАСТРОЕК ===

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    showRecommendations: Boolean,
    onToggleRecommendations: (Boolean) -> Unit,
    maxAppsPerRow: Int,
    onMaxAppsPerRowChange: (Int) -> Unit,
    sectionOrder: List<String>,
    onMoveSection: (sectionKey: String, newIndex: Int) -> Unit,
    inputSwitchSoundEnabled: Boolean,
    onToggleInputSwitchSound: (Boolean) -> Unit,
    availableRecChannels: List<RecChannelInfo>,
    enabledChannelIds: List<Long>,
    mixChannelIds: List<Long>,
    onToggleChannel: (Long) -> Unit,
    onToggleMixChannel: (Long) -> Unit,
    installedApps: List<TvAppInfo>,
    hiddenApps: List<String>,
    onToggleAppVisibility: (String) -> Unit,
    hasCustomWallpaper: Boolean,
    onSelectWallpaper: () -> Unit,
    onResetWallpaper: () -> Unit,
    channelProviders: List<ChannelProviderGroup>,
    selectedChannelProviders: Set<String>,
    onToggleChannelProvider: (String) -> Unit,
    onBack: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var selectedTab by remember { mutableIntStateOf(0) }

    fun isChannelEnabled(channel: RecChannelInfo): Boolean {
        return if (channel.packageName.contains("tvirl", ignoreCase = true)) {
            enabledChannelIds.contains(-999L) || enabledChannelIds.contains(channel.channelId)
        } else {
            enabledChannelIds.contains(channel.channelId)
        }
    }

    fun isChannelMixed(channel: RecChannelInfo): Boolean {
        return if (channel.packageName.contains("tvirl", ignoreCase = true)) {
            mixChannelIds.contains(-999L) || mixChannelIds.contains(channel.channelId)
        } else {
            mixChannelIds.contains(channel.channelId)
        }
    }

    LaunchedEffect(Unit) {
        delay(120)
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
    ) {
        Surface(
            onClick = onBack,
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxSize()
        ) {}

        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .width(440.dp)
                .background(Color(0xFF1F1F1F))
                .align(Alignment.CenterEnd)
                .padding(horizontal = 24.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Настройки",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                Surface(
                    onClick = { onToggleRecommendations(!showRecommendations) },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x11FFFFFF),
                        focusedContainerColor = Color(0x33FFFFFF)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .focusRequester(focusRequester)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Рекомендации", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(text = if (showRecommendations) "Включены" else "Выключены", color = Color(0xFFB0B0B0), fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .size(width = 40.dp, height = 24.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (showRecommendations) Color(0xFF34A853) else Color(0xFF5F6368)),
                            contentAlignment = if (showRecommendations) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Box(modifier = Modifier.padding(2.dp).size(20.dp).clip(CircleShape).background(Color.White))
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_max_apps_per_row),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_apps_next_row_hint),
                            color = Color(0xFFAAAAAA),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = { onMaxAppsPerRowChange((maxAppsPerRow - 1).coerceAtLeast(4)) },
                                enabled = maxAppsPerRow > 4,
                                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color(0x11FFFFFF),
                                    focusedContainerColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("−", color = Color.White, fontSize = 22.sp)
                                }
                            }
                            Text(
                                text = maxAppsPerRow.toString(),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.width(28.dp)
                            )
                            Surface(
                                onClick = { onMaxAppsPerRowChange((maxAppsPerRow + 1).coerceAtMost(50)) },
                                enabled = maxAppsPerRow < 50, // Теперь кнопка активна вплоть до 50 элементов
                                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color(0x11FFFFFF),
                                    focusedContainerColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("+", color = Color.White, fontSize = 22.sp)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_section_order),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Все разделы останутся доступны. Изменяй их расположение кнопками ↑ и ↓.",
                        color = Color(0xFFAAAAAA),
                        fontSize = 11.sp
                    )
                    sectionOrder.forEachIndexed { index, sectionKey ->
                        val sectionLabel = when (sectionKey) {
                            "recommendations" -> "Рекомендации"
                            "favorites" -> "Избранное"
                            "apps" -> "Приложения"
                            "inputs" -> "Входы"
                            "games" -> "Игры"
                            else -> sectionKey
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(Color(0x0FFFFFFF), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = sectionLabel,
                                color = Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    onClick = { if (index > 0) onMoveSection(sectionKey, index - 1) },
                                    enabled = index > 0,
                                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Color(0x11FFFFFF),
                                        focusedContainerColor = Color(0x33FFFFFF)
                                    ),
                                    modifier = Modifier.size(width = 38.dp, height = 34.dp)
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("↑", color = if (index > 0) Color.White else Color(0x55555555), fontSize = 18.sp)
                                    }
                                }
                                Surface(
                                    onClick = { if (index < sectionOrder.lastIndex) onMoveSection(sectionKey, index + 1) },
                                    enabled = index < sectionOrder.lastIndex,
                                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Color(0x11FFFFFF),
                                        focusedContainerColor = Color(0x33FFFFFF)
                                    ),
                                    modifier = Modifier.size(width = 38.dp, height = 34.dp)
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("↓", color = if (index < sectionOrder.lastIndex) Color.White else Color(0x55555555), fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Surface(
                    onClick = { onToggleInputSwitchSound(!inputSwitchSoundEnabled) },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x11FFFFFF),
                        focusedContainerColor = Color(0x33FFFFFF)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.input_switch_sound),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(
                                    if (inputSwitchSoundEnabled) {
                                        R.string.input_switch_sound_enabled
                                    } else {
                                        R.string.input_switch_sound_disabled
                                    }
                                ),
                                color = Color(0xFFB0B0B0),
                                fontSize = 12.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(width = 40.dp, height = 24.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (inputSwitchSoundEnabled) Color(0xFF34A853)
                                    else Color(0xFF5F6368)
                                ),
                            contentAlignment = if (inputSwitchSoundEnabled) {
                                Alignment.CenterEnd
                            } else {
                                Alignment.CenterStart
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }
                    }
                }
            }

            if (showRecommendations) {
                item {
                    Text(
                        text = stringResource(R.string.recommendation_management),
                        color = Color(0xFF888888),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { selectedTab = 0 },
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (selectedTab == 0) Color(0xFF4285F4) else Color(0x11FFFFFF),
                                focusedContainerColor = if (selectedTab == 0) Color(0xFF5295FF) else Color(0x33FFFFFF)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Каналы",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        Surface(
                            onClick = { selectedTab = 1 },
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (selectedTab == 1) Color(0xFF4285F4) else Color(0x11FFFFFF),
                                focusedContainerColor = if (selectedTab == 1) Color(0xFF5295FF) else Color(0x33FFFFFF)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "🔀 Смешивание",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                if (selectedTab == 0) {
                    if (availableRecChannels.isEmpty()) {
                        item {
                            Text("Поиск каналов...", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        }
                    } else {
                        items(availableRecChannels, key = { "ch_${it.channelId}" }) { channel ->
                            val isEnabled = isChannelEnabled(channel)

                            Surface(
                                onClick = { onToggleChannel(channel.channelId) },
                                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color(0x08FFFFFF),
                                    focusedContainerColor = Color(0x22FFFFFF)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = channel.name, color = Color.White, fontSize = 14.sp)
                                    Box(
                                        modifier = Modifier
                                            .size(width = 36.dp, height = 20.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isEnabled) Color(0xFF34A853) else Color(0xFF5F6368)),
                                        contentAlignment = if (isEnabled) Alignment.CenterEnd else Alignment.CenterStart
                                    ) {
                                        Box(modifier = Modifier.padding(2.dp).size(16.dp).clip(CircleShape).background(Color.White))
                                    }
                                }
                            }
                        }
                    }
                }

                if (selectedTab == 1) {
                    item {
                        Text(
                            text = "Выберите сервисы, карточки которых будут перемешиваться между собой в общей ленте:",
                            color = Color(0xFFAAAAAA),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    val activeChannels = availableRecChannels.filter { isChannelEnabled(it) }

                    if (activeChannels.isEmpty()) {
                        item {
                            Text("Сначала включите хотя бы один канал во вкладке «Каналы»", color = Color.Gray, fontSize = 13.sp)
                        }
                    } else {
                        items(activeChannels, key = { "mix_${it.channelId}" }) { channel ->
                            val isMixed = isChannelMixed(channel)

                            Surface(
                                onClick = { onToggleMixChannel(channel.channelId) },
                                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color(0x08FFFFFF),
                                    focusedContainerColor = Color(0x22FFFFFF)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = channel.name, color = Color.White, fontSize = 14.sp)
                                    Text(
                                        text = stringResource(
                                            if (isMixed) {
                                                R.string.recommendation_in_mix
                                            } else {
                                                R.string.recommendation_separate
                                            }
                                        ),
                                        color = if (isMixed) Color(0xFF34A853) else Color.Gray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.recommendation_app_channels),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = "Выбери приложения, каналы которых показывать на главном экране.",
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp
                    )

                    if (channelProviders.isEmpty()) {
                        Text(
                            text = stringResource(R.string.recommendation_channels_not_found),
                            color = Color(0xFFAAAAAA),
                            fontSize = 13.sp
                        )
                    }

                    channelProviders.forEach { provider ->
                        Surface(
                            onClick = {
                                onToggleChannelProvider(provider.packageName)
                            },
                            shape = ClickableSurfaceDefaults.shape(
                                shape = RoundedCornerShape(8.dp)
                            ),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0x11FFFFFF),
                                focusedContainerColor = Color(0x33FFFFFF)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = provider.title,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )

                                Text(
                                    text = if (
                                        provider.packageName in selectedChannelProviders
                                    ) "✓" else "○",
                                    color = Color.White,
                                    fontSize = 20.sp
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Обои рабочего стола",
                    color = Color(0xFF888888),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )
            }

            item {
                Surface(
                    onClick = onSelectWallpaper,
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x11FFFFFF),
                        focusedContainerColor = Color(0x33FFFFFF)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Выбрать из памяти ТВ", color = Color.White, fontSize = 14.sp)
                        Image(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (hasCustomWallpaper) {
                item {
                    Surface(
                        onClick = onResetWallpaper,
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0x11FF5252),
                            focusedContainerColor = Color(0x33FF5252)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "Сбросить обои по умолчанию", color = Color(0xFFFF8A80), fontSize = 13.sp)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Видимость приложений",
                    color = Color(0xFF888888),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )
            }

            items(installedApps, key = { "app_${it.packageName}" }) { app ->
                val isHidden = hiddenApps.contains(app.packageName)
                Surface(
                    onClick = { onToggleAppVisibility(app.packageName) },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x08FFFFFF),
                        focusedContainerColor = Color(0x22FFFFFF)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = app.name, color = Color.White, fontSize = 14.sp)
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (!isHidden) Color(0xFF4285F4) else Color(0xFF5F6368)),
                            contentAlignment = if (!isHidden) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Box(modifier = Modifier.padding(2.dp).size(16.dp).clip(CircleShape).background(Color.White))
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                Surface(
                    onClick = onBack,
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x15FFFFFF),
                        focusedContainerColor = Color(0x44FFFFFF)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "Закрыть", color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}
