package com.lyricsync.app.ui.lyrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyricsync.app.BuildConfig
import com.lyricsync.app.R
import com.lyricsync.app.floating.FloatingLyricService
import com.lyricsync.app.media.LyricsPrefs
import com.lyricsync.app.media.PlaybackInfo
import com.lyricsync.app.model.LyricLine
import com.lyricsync.app.ui.settings.SettingsScreen
import com.lyricsync.app.ui.theme.CyanAccent
import com.lyricsync.app.ui.theme.IndigoBottom
import com.lyricsync.app.ui.theme.IndigoTop
import com.lyricsync.app.ui.theme.PillBorder
import com.lyricsync.app.ui.theme.PillSurface
import com.lyricsync.app.ui.theme.TealBottom
import com.lyricsync.app.ui.theme.TealTop
import com.lyricsync.app.ui.theme.VioletAccent
import com.lyricsync.app.ui.theme.VioletBottom
import com.lyricsync.app.ui.theme.VioletTop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun LyricsScreen(viewModel: LyricsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val (backgroundTop, backgroundBottom) = rememberAnimatedBackgroundColors()
    val backgroundBrush = Brush.verticalGradient(listOf(backgroundTop, backgroundBottom))

    var settingsOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var offsetOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }

    var wasRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.refreshing) {
        if (state.refreshing) {
            wasRefreshing = true
        } else if (wasRefreshing) {
            wasRefreshing = false
            Toast.makeText(context, R.string.refresh_done, Toast.LENGTH_SHORT).show()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onScreenResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        when {
            state.lyricsLoading -> LyricsCenterMessage(
                text = stringResource(R.string.loading_lyrics)
            )
            state.lyricsUnavailable -> UnavailableLyrics(
                title = state.playback?.title.orEmpty(),
                artist = state.playback?.artist.orEmpty()
            )
            else -> LyricsList(
                lines = state.lines,
                currentIndex = state.currentIndex,
                followEnabled = state.followEnabled,
                onUserScroll = viewModel::onUserScroll,
                modifier = Modifier.fillMaxSize()
            )
        }

        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navigationBarBottom =
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(160.dp + statusBarTop)
                .background(
                    Brush.verticalGradient(
                        listOf(backgroundTop, backgroundTop.copy(alpha = 0f))
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(160.dp + navigationBarBottom)
                .background(
                    Brush.verticalGradient(
                        listOf(backgroundBottom.copy(alpha = 0f), backgroundBottom)
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            if (state.statusCardVisible) {
                PlaybackStatusCard(
                    playback = state.playback,
                    onGrantAccess = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp)
                )
            }

            if (!state.followEnabled) {
                AssistChip(
                    onClick = viewModel::resumeFollow,
                    label = {
                        Text(text = stringResource(R.string.resume_follow), fontSize = 13.sp)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = PillSurface,
                        labelColor = Color.White
                    ),
                    border = BorderStroke(width = 1.dp, color = PillBorder)
                )
            }

            if (state.menuOpen) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = viewModel::closeMenu
                        )
                )
            }

            AnimatedVisibility(
                visible = state.menuOpen,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 92.dp),
                enter = slideInVertically(
                    animationSpec = tween(220),
                    initialOffsetY = { it }
                ) + fadeIn(animationSpec = tween(220)),
                exit = slideOutVertically(
                    animationSpec = tween(180),
                    targetOffsetY = { it }
                ) + fadeOut(animationSpec = tween(180))
            ) {
                DotMenuPill(
                    onItemClick = { itemRes ->
                        viewModel.closeMenu()
                        when (itemRes) {
                            R.string.menu_playback_status -> viewModel.toggleStatusCard()
                            R.string.menu_settings -> settingsOpen = true
                            R.string.menu_search_lyrics -> searchOpen = true
                            R.string.menu_offset -> offsetOpen = true
                            R.string.menu_about -> aboutOpen = true
                            R.string.menu_floating_bar -> startFloatingBar(context)
                        }
                    }
                )
            }

            DotButton(
                onClick = viewModel::toggleMenu,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            )

            RefreshDotButton(
                refreshing = state.refreshing,
                onClick = viewModel::refresh,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp)
            )
        }

        if (settingsOpen) {
            SettingsScreen(
                statusCardVisible = state.statusCardVisible,
                lyricSource = state.lyricSource,
                onStatusCardChange = viewModel::setStatusCardVisible,
                onSourceChange = viewModel::setLyricSource,
                onAboutClick = { aboutOpen = true },
                onBack = { settingsOpen = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    BackHandler(enabled = settingsOpen) { settingsOpen = false }

    if (searchOpen) {
        SearchLyricsDialog(
            onSearch = { title, artist ->
                searchOpen = false
                viewModel.searchLyrics(title, artist)
            },
            onDismiss = { searchOpen = false }
        )
    }
    if (offsetOpen) {
        OffsetDialog(
            offsetMs = state.offsetMs,
            onAdjust = viewModel::adjustOffset,
            onDismiss = { offsetOpen = false }
        )
    }
    if (aboutOpen) {
        AboutDialog(onDismiss = { aboutOpen = false })
    }
}

private fun startFloatingBar(context: Context) {
    if (Settings.canDrawOverlays(context)) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, FloatingLyricService::class.java)
        )
    } else {
        Toast.makeText(context, R.string.overlay_permission_toast, Toast.LENGTH_LONG).show()
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName)
            )
        )
    }
}

@Composable
private fun SearchLyricsDialog(
    onSearch: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PillSurface,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text(text = stringResource(R.string.search_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(text = stringResource(R.string.search_field_title)) },
                    singleLine = true,
                    colors = lyricFieldColors()
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(text = stringResource(R.string.search_field_artist)) },
                    singleLine = true,
                    colors = lyricFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSearch(title, artist) },
                enabled = title.isNotBlank()
            ) {
                Text(
                    text = stringResource(R.string.search_action),
                    color = if (title.isNotBlank()) CyanAccent else Color.White.copy(alpha = 0.3f)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.dialog_close),
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    )
}

@Composable
private fun lyricFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = CyanAccent,
    focusedBorderColor = CyanAccent,
    unfocusedBorderColor = PillBorder,
    focusedLabelColor = CyanAccent,
    unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
)

@Composable
private fun OffsetDialog(
    offsetMs: Int,
    onAdjust: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PillSurface,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text(text = stringResource(R.string.offset_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = { onAdjust(-LyricsPrefs.OFFSET_STEP_MS) },
                        enabled = offsetMs > LyricsPrefs.OFFSET_MIN_MS
                    ) {
                        Text(
                            text = stringResource(R.string.offset_minus),
                            color = CyanAccent,
                            fontSize = 16.sp
                        )
                    }
                    Text(
                        text = stringResource(R.string.offset_value, formatOffset(offsetMs)),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        onClick = { onAdjust(LyricsPrefs.OFFSET_STEP_MS) },
                        enabled = offsetMs < LyricsPrefs.OFFSET_MAX_MS
                    ) {
                        Text(
                            text = stringResource(R.string.offset_plus),
                            color = CyanAccent,
                            fontSize = 16.sp
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.offset_hint),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_close), color = CyanAccent)
            }
        }
    )
}

private fun formatOffset(ms: Int): String = if (ms > 0) "+$ms" else ms.toString()

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PillSurface,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text(text = stringResource(R.string.app_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )
                Text(
                    text = stringResource(R.string.about_github),
                    color = CyanAccent,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/maxforeee/lyricsync")
                    }
                )
                Text(
                    text = stringResource(R.string.about_sources),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_close), color = CyanAccent)
            }
        }
    )
}

@Composable
private fun LyricsList(
    lines: List<LyricLine>,
    currentIndex: Int,
    followEnabled: Boolean,
    onUserScroll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                onUserScroll()
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val viewportHeight = maxHeight
        val anchorOffsetPx = with(density) {
            ((36.sp.toPx() + 16.dp.toPx()) / 2f).roundToInt()
        }
        var lastAutoScrollTarget by remember { mutableStateOf(-1) }

        LaunchedEffect(currentIndex, followEnabled) {
            if (!followEnabled) {
                lastAutoScrollTarget = -1
                return@LaunchedEffect
            }
            if (lastAutoScrollTarget != currentIndex) {
                lastAutoScrollTarget = currentIndex
                // animateScrollToItem has no animationSpec parameter in this
                // Compose version; its built-in animation is already spring-based.
                listState.animateScrollToItem(
                    index = currentIndex,
                    scrollOffset = anchorOffsetPx
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = viewportHeight * 0.35f,
                bottom = viewportHeight * 0.65f
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(lines, key = { _, line -> line.timeMs }) { index, line ->
                LyricRow(
                    text = line.text,
                    distance = abs(index - currentIndex),
                    isAboveCurrent = index < currentIndex
                )
            }
        }
    }
}

@Composable
private fun LyricsCenterMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun UnavailableLyrics(title: String, artist: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        if (artist.isNotBlank()) {
            Text(
                text = artist,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            text = stringResource(R.string.no_lyrics_found),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun LyricRow(text: String, distance: Int, isAboveCurrent: Boolean) {
    val style = when (distance) {
        0 -> TextStyle(
            brush = Brush.linearGradient(listOf(CyanAccent, VioletAccent)),
            fontSize = 28.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        1 -> TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center
        )
        else -> TextStyle(
            color = Color.White,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )
    }
    val arcFraction = (distance / 6f).coerceIn(0f, 1f)
    val arcScale by animateFloatAsState(
        targetValue = 1f - 0.15f * arcFraction,
        animationSpec = tween(250),
        label = "arcScale"
    )
    val arcAlpha by animateFloatAsState(
        targetValue = 1f - 0.75f * arcFraction,
        animationSpec = tween(250),
        label = "arcAlpha"
    )
    val arcTilt by animateFloatAsState(
        targetValue = if (isAboveCurrent) 12f * arcFraction else -12f * arcFraction,
        animationSpec = tween(250),
        label = "arcTilt"
    )
    val isCurrent = distance == 0
    val currentEmphasis = remember { Animatable(1f) }
    LaunchedEffect(isCurrent) {
        if (isCurrent) {
            currentEmphasis.snapTo(0.9f)
            currentEmphasis.animateTo(1f, tween(300))
        }
    }
    Text(
        text = text,
        style = style,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = arcScale * currentEmphasis.value
                scaleY = arcScale * currentEmphasis.value
                alpha = arcAlpha * currentEmphasis.value
                rotationX = arcTilt
                cameraDistance = 12f * density
            }
            .padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

@Composable
private fun PlaybackStatusCard(
    playback: PlaybackInfo?,
    onGrantAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(PillSurface)
            .border(width = 1.dp, color = PillBorder, shape = shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (playback == null) {
            StatusText(text = stringResource(R.string.status_no_playback))
            AssistChip(
                onClick = onGrantAccess,
                label = {
                    Text(text = stringResource(R.string.status_grant_access), fontSize = 12.sp)
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = PillSurface,
                    labelColor = Color.White
                ),
                border = BorderStroke(width = 1.dp, color = PillBorder)
            )
        } else {
            StatusText(text = playback.packageName)
            StatusText(text = stringResource(R.string.status_track, playback.title, playback.artist))
            val stateLabel = stringResource(
                if (playback.playing) R.string.status_playing else R.string.status_paused
            )
            StatusText(
                text = stringResource(
                    R.string.status_time,
                    formatClock(playback.positionMs),
                    formatClock(playback.durationMs)
                ) + " · " + stateLabel
            )
            StatusText(
                text = stringResource(
                    if (playback.positionAdvancing) R.string.status_position_moving
                    else R.string.status_position_stuck
                )
            )
            StatusText(
                text = stringResource(R.string.status_updated, formatTimeOfDay(playback.lastUpdateMs))
            )
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
}

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

private fun formatTimeOfDay(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

@Composable
private fun rememberAnimatedBackgroundColors(): Pair<Color, Color> {
    val transition = rememberInfiniteTransition(label = "lyricsBackground")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "palettePhase"
    )
    val palettes = listOf(
        listOf(IndigoTop, IndigoBottom),
        listOf(TealTop, TealBottom),
        listOf(VioletTop, VioletBottom)
    )
    val segment = phase.toInt() % palettes.size
    val next = (segment + 1) % palettes.size
    val t = phase - phase.toInt()
    val top = lerp(palettes[segment][0], palettes[next][0], t)
    val bottom = lerp(palettes[segment][1], palettes[next][1], t)
    return top to bottom
}
