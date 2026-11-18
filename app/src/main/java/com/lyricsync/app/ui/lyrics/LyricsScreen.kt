package com.lyricsync.app.ui.lyrics

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyricsync.app.R
import com.lyricsync.app.model.LyricLine
import com.lyricsync.app.ui.theme.CyanAccent
import com.lyricsync.app.ui.theme.IndigoBottom
import com.lyricsync.app.ui.theme.IndigoTop
import com.lyricsync.app.ui.theme.PillBorder
import com.lyricsync.app.ui.theme.PillSurface
import com.lyricsync.app.ui.theme.ScrimBase
import com.lyricsync.app.ui.theme.TealBottom
import com.lyricsync.app.ui.theme.TealTop
import com.lyricsync.app.ui.theme.VioletAccent
import com.lyricsync.app.ui.theme.VioletBottom
import com.lyricsync.app.ui.theme.VioletTop
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun LyricsScreen(viewModel: LyricsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backgroundBrush = rememberAnimatedBackground()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            LyricsList(
                lines = state.lines,
                currentIndex = state.currentIndex,
                followEnabled = state.followEnabled,
                onUserScroll = viewModel::onUserScroll,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        Brush.verticalGradient(listOf(ScrimBase, Color.Transparent))
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, ScrimBase))
                    )
            )

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
                    border = AssistChipDefaults.assistChipBorder(borderColor = PillBorder)
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
                        Toast.makeText(
                            context,
                            context.getString(R.string.menu_item_toast, context.getString(itemRes)),
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.closeMenu()
                    }
                )
            }

            DotButton(
                onClick = viewModel::toggleMenu,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            )
        }
    }
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
        val halfViewport = maxHeight / 2
        val centerOffsetPx = with(density) {
            ((36.sp.toPx() + 16.dp.toPx()) / 2f).roundToInt()
        }

        LaunchedEffect(currentIndex, followEnabled) {
            if (followEnabled) {
                listState.animateScrollToItem(currentIndex, centerOffsetPx)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = halfViewport),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(lines, key = { _, line -> line.timeMs }) { index, line ->
                LyricRow(text = line.text, distance = abs(index - currentIndex))
            }
        }
    }
}

@Composable
private fun LyricRow(text: String, distance: Int) {
    val style = when (distance) {
        0 -> TextStyle(
            brush = Brush.linearGradient(listOf(CyanAccent, VioletAccent)),
            fontSize = 28.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        1 -> TextStyle(
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 18.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center
        )
        else -> TextStyle(
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 16.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )
    }
    Text(
        text = text,
        style = style,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

@Composable
private fun rememberAnimatedBackground(): Brush {
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
    return Brush.verticalGradient(listOf(top, bottom))
}
