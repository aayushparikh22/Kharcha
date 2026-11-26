package com.spendcalci

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Trends(all: List<Txn>) {
    var weekly by rememberSaveable { mutableStateOf(true) }
    val today = LocalDate.now()
    val keys: List<LocalDate> = if (weekly) {
        (7 downTo 0).map { today.with(DayOfWeek.MONDAY).minusWeeks(it.toLong()) }
    } else {
        (5 downTo 0).map { today.withDayOfMonth(1).minusMonths(it.toLong()) }
    }
    fun keyOf(t: Txn): LocalDate =
        if (weekly) t.day().with(DayOfWeek.MONDAY) else t.day().withDayOfMonth(1)

    val byKey = remember(all, weekly) { all.filter { it.category != null }.groupBy { keyOf(it) } }
    val data = keys.map { k ->
        byKey[k].orEmpty().groupBy { it.category!! }.mapValues { e -> e.value.sumOf { it.myShare() } }
    }
    val sums = data.map { it.values.sum() }
    val max = sums.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val thisP = sums.last()
    val lastP = sums[sums.size - 2]
    val avg = sums.average()
    val topCat = categoryNames()
        .maxByOrNull { c -> data.sumOf { it[c] ?: 0.0 } }
        ?.takeIf { c -> data.sumOf { it[c] ?: 0.0 } > 0 }

    val prog = remember { Animatable(0f) }
    LaunchedEffect(weekly, all.size, sums) { prog.snapTo(0f); prog.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    val measurer = rememberTextMeasurer()
    val labelFmt = DateTimeFormatter.ofPattern(if (weekly) "d/M" else "MMM")
    val periodWord = if (weekly) "week" else "month"

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Trends", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(weekly, { weekly = true }, label = { Text("Weekly") })
                FilterChip(!weekly, { weekly = false }, label = { Text("Monthly") })
            }
        }
        item {
            Card2 {
                Text(if (weekly) "Last 8 weeks" else "Last 6 months", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Canvas(Modifier.fillMaxWidth().height(220.dp)) {
                    val labelH = 22.dp.toPx()
                    val topPad = 18.dp.toPx()
                    val chartH = size.height - labelH - topPad
                    val slot = size.width / keys.size
                    val barW = slot * 0.55f
                    keys.forEachIndexed { i, k ->
                        val x = i * slot + (slot - barW) / 2
                        var y = topPad + chartH
                        categoryNames().forEach { c ->
                            val v = data[i][c] ?: 0.0
                            if (v > 0) {
                                val h = (v / max).toFloat() * chartH * prog.value
                                y -= h
                                drawRoundRect(catColor(c), Offset(x, y), Size(barW, h), CornerRadius(4.dp.toPx()))
                            }
                        }
                        val lbl = measurer.measure(k.format(labelFmt), TextStyle(fontSize = 10.sp, color = TextDim))
                        drawText(lbl, topLeft = Offset(i * slot + (slot - lbl.size.width) / 2, size.height - labelH + 4.dp.toPx()))
                        if (sums[i] > 0) {
                            val t = measurer.measure(
                                if (sums[i] >= 1000) "%.1fk".format(sums[i] / 1000) else "%.0f".format(sums[i]),
                                TextStyle(fontSize = 10.sp, color = Color(0xFFE5E9F5)),
                            )
                            drawText(t, topLeft = Offset(i * slot + (slot - t.size.width) / 2, y - t.size.height - 2.dp.toPx()))
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("This $periodWord", money(thisP), Modifier.weight(1f))
                val diff = if (lastP > 0) (thisP - lastP) / lastP * 100 else null
                Stat(
                    "vs last $periodWord",
                    diff?.let { (if (it >= 0) "▲ " else "▼ ") + "%.0f%%".format(abs(it)) } ?: "–",
                    Modifier.weight(1f),
                    if (diff == null) null else if (diff > 0) Danger else Positive,
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(if (weekly) "Weekly average" else "Monthly average", money(avg), Modifier.weight(1f))
                Stat("Top category", topCat?.let { "${catEmoji(it)} $it" } ?: "–", Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                categoryNames().forEach { c -> Text(catEmoji(c), fontSize = 18.sp) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color? = null) {
    Card2(modifier) {
        Text(label, color = TextDim, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color ?: Color.Unspecified)
    }
}
