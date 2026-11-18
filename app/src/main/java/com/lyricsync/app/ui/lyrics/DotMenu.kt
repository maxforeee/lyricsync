package com.lyricsync.app.ui.lyrics

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsync.app.R
import com.lyricsync.app.ui.theme.CyanAccent
import com.lyricsync.app.ui.theme.PillBorder
import com.lyricsync.app.ui.theme.PillSurface
import com.lyricsync.app.ui.theme.VioletAccent

@Composable
fun DotButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.dot_content_description)
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(elevation = 12.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(listOf(CyanAccent, VioletAccent)),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(Color.White, CircleShape)
                )
            }
        }
    }
}

private val menuItems: List<Int> = listOf(
    R.string.menu_settings,
    R.string.menu_search_lyrics,
    R.string.menu_floating_bar,
    R.string.menu_offset,
    R.string.menu_about
)

@Composable
fun DotMenuPill(
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(28.dp)
    Column(
        modifier = modifier
            .widthIn(min = 56.dp, max = 160.dp)
            .heightIn(min = 220.dp)
            .shadow(elevation = 16.dp, shape = pillShape, clip = false)
            .background(color = PillSurface, shape = pillShape)
            .border(width = 1.dp, color = PillBorder, shape = pillShape)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        menuItems.forEach { itemRes ->
            MenuRow(labelRes = itemRes, onClick = { onItemClick(itemRes) })
        }
    }
}

@Composable
private fun MenuRow(
    @StringRes labelRes: Int,
    onClick: () -> Unit
) {
    Text(
        text = stringResource(labelRes),
        color = Color.White,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp)
    )
}
