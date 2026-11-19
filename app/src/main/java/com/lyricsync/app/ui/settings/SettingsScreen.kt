package com.lyricsync.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsync.app.R
import com.lyricsync.app.media.LyricSource
import com.lyricsync.app.media.LyricsPrefs
import com.lyricsync.app.ui.theme.CyanAccent
import com.lyricsync.app.ui.theme.IndigoBottom
import com.lyricsync.app.ui.theme.IndigoTop
import com.lyricsync.app.ui.theme.PillBorder
import com.lyricsync.app.ui.theme.PillSurface

@Composable
fun SettingsScreen(
    statusCardVisible: Boolean,
    lyricSource: LyricSource,
    onStatusCardChange: (Boolean) -> Unit,
    onSourceChange: (LyricSource) -> Unit,
    floatingBarWidthDp: Int,
    onFloatingBarWidthChange: (Int) -> Unit,
    onAboutClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(IndigoTop, IndigoBottom)))
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                    tint = Color.White
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.settings_status_card),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = statusCardVisible,
                    onCheckedChange = onStatusCardChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = CyanAccent,
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                        uncheckedTrackColor = PillSurface,
                        uncheckedBorderColor = PillBorder
                    )
                )
            }
        }

        Text(
            text = stringResource(R.string.settings_source_title),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp)
        )
        SettingsCard {
            Column {
                SourceRow(R.string.settings_source_auto, LyricSource.AUTO, lyricSource, onSourceChange)
                SourceRow(R.string.settings_source_lrclib, LyricSource.LRCLIB, lyricSource, onSourceChange)
                SourceRow(R.string.settings_source_netease, LyricSource.NETEASE, lyricSource, onSourceChange)
                SourceRow(R.string.settings_source_qq, LyricSource.QQ, lyricSource, onSourceChange)
                SourceRow(R.string.settings_source_kugou, LyricSource.KUGOU, lyricSource, onSourceChange)
            }
        }

        Text(
            text = stringResource(R.string.settings_floating_bar_title),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp)
        )
        SettingsCard {
            Column {
                Text(
                    text = stringResource(R.string.settings_floating_bar_value, floatingBarWidthDp),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.End)
                )
                Slider(
                    value = floatingBarWidthDp.toFloat(),
                    onValueChange = { onFloatingBarWidthChange(it.toInt()) },
                    valueRange = LyricsPrefs.FLOATING_BAR_MIN_DP.toFloat()..LyricsPrefs.FLOATING_BAR_MAX_DP.toFloat(),
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = CyanAccent,
                        inactiveTrackColor = PillSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SettingsCard(modifier = Modifier.padding(top = 20.dp)) {
            Text(
                text = stringResource(R.string.settings_about),
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onAboutClick)
                    .padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun SourceRow(
    @StringRes labelRes: Int,
    source: LyricSource,
    selected: LyricSource,
    onSourceChange: (LyricSource) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSourceChange(source) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = source == selected,
            onClick = { onSourceChange(source) },
            colors = RadioButtonDefaults.colors(
                selectedColor = CyanAccent,
                unselectedColor = Color.White.copy(alpha = 0.6f)
            )
        )
        Text(
            text = stringResource(labelRes),
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PillSurface)
            .border(width = 1.dp, color = PillBorder, shape = shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        content()
    }
}
