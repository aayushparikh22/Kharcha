package com.spendcalci

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

val Bg = Color(0xFF0B0F1A)
val Panel = Color(0xFF151B2B)
val Panel2 = Color(0xFF1E2640)
val TextDim = Color(0xFF94A3B8)
val Amber = Color(0xFFFBBF24)
val Danger = Color(0xFFF87171)
val Positive = Color(0xFF34D399)

private val nf = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2 }
fun money(d: Double): String = "₹" + nf.format(d)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7C9CFF), onPrimary = Color(0xFF0B0F1A),
            background = Bg, surface = Panel, onSurface = Color(0xFFE5E9F5),
            onBackground = Color(0xFFE5E9F5), surfaceVariant = Panel2, onSurfaceVariant = TextDim,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color(0xFFE5E9F5), content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPicker(selected: String?, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Store.categories.forEach { cat ->
            val c = cat.name
            val bg by animateColorAsState(if (selected == c) catColor(c).copy(alpha = 0.35f) else Panel2, label = "chip")
            FilterChip(
                selected = selected == c, onClick = { onSelect(c) },
                label = { Text("${catEmoji(c)} $c") },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = bg, selectedContainerColor = bg,
                    labelColor = Color.White, selectedLabelColor = Color.White,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = selected == c,
                    borderColor = Color.Transparent, selectedBorderColor = catColor(c),
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatches(selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PALETTE.forEach { hexValue ->
            val isSelected = hexValue.equals(selected, ignoreCase = true)
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(hex(hexValue))
                    .then(if (isSelected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                    .clickable { onPick(hexValue) },
            )
        }
    }
}
