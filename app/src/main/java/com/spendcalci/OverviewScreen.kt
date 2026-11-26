package com.spendcalci

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun Card2(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(16.dp),
        content = content,
    )
}

@Composable
fun MonthSwitcher(month: YearMonth, onMonth: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        FilledTonalIconButton({ onMonth(month.minusMonths(1)) }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Panel)) { Text("‹", fontSize = 22.sp) }
        AnimatedContent(month, label = "month", transitionSpec = {
            val d = if (targetState > initialState) 1 else -1
            (slideInVertically { it * d } + fadeIn()) togetherWith (slideOutVertically { -it * d } + fadeOut())
        }) {
            Text(it.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        FilledTonalIconButton(
            { onMonth(month.plusMonths(1)) }, enabled = month < YearMonth.now(),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Panel),
        ) { Text("›", fontSize = 22.sp) }
    }
}

@Composable
fun Overview(
    month: YearMonth, onMonth: (YearMonth) -> Unit, txns: List<Txn>, pending: Int,
    onPending: () -> Unit, onCustomize: () -> Unit,
) {
    val labelled = txns.filter { it.category != null }
    var src by remember(month) { mutableStateOf<String?>(null) }
    var sel by remember(month) { mutableStateOf<String?>(null) }
    val bucketTotals = labelled.groupBy { it.bucket() }.mapValues { e -> e.value.sumOf { it.myShare() } }.filterValues { it > 0 }
    val bucketOrder = bucketTotals.keys.sortedWith(compareBy({ bucketRank(it) }, { it }))
    val done = labelled.filter { src == null || it.bucket() == src }
    val totals = categoryNames().associateWith { c -> done.filter { it.category == c }.sumOf { it.myShare() } }.filterValues { it > 0 }
    val total = totals.values.sum()
    val animTotal by animateFloatAsState(total.toFloat(), tween(800), label = "total")
    val merchants = done.groupBy { it.merchant }.mapValues { e -> e.value.sumOf { it.myShare() } }
        .entries.sortedByDescending { it.value }.take(5)
    val splits = txns.filter { it.split }
    val owedToMe = splits.sumOf { t -> t.members().filter { !it.settled }.sumOf { it.owed } }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Overview", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onCustomize) { Text("⚙ Customize") }
            }
        }
        item { MonthSwitcher(month, onMonth) }
        item {
            Column {
                Text("Total spent", color = TextDim)
                Text(money(animTotal.toDouble()), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text("${txns.size} transactions", color = TextDim)
            }
        }
        if (pending > 0) item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Amber.copy(alpha = 0.15f))
                    .clickable { onPending() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⏳", fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Text("$pending payment${if (pending > 1) "s" else ""} waiting for a category", Modifier.weight(1f), color = Amber)
                Text("Categorize ›", color = Amber, fontWeight = FontWeight.SemiBold)
            }
        }
        if (splits.isNotEmpty()) item {
            Card2 {
                Text("👥 ${splits.size} split payment${if (splits.size > 1) "s" else ""}", style = MaterialTheme.typography.titleMedium)
                Text("${money(owedToMe)} still to collect from people", color = TextDim)
            }
        }
        if (bucketTotals.isNotEmpty()) item {
            Card2 {
                Text("By payment method & card", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                val all = bucketTotals.values.sum()
                Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)) {
                    bucketOrder.forEach { b ->
                        Box(
                            Modifier.weight(bucketTotals.getValue(b).toFloat()).fillMaxHeight()
                                .background(bucketColor(b).copy(alpha = if (src == null || src == b) 1f else 0.25f)),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                bucketOrder.forEach { b ->
                    val v = bucketTotals.getValue(b)
                    val bg by animateColorAsState(if (src == b) bucketColor(b).copy(alpha = 0.2f) else Color.Transparent, label = "bucket")
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg)
                            .clickable { src = if (src == b) null else b }.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(bucketColor(b)))
                        Spacer(Modifier.width(10.dp))
                        Text(bucketLabel(b), Modifier.weight(1f))
                        Text(money(v), fontWeight = FontWeight.SemiBold)
                        Text("  ${"%.0f".format(v / all * 100)}%", color = TextDim)
                    }
                }
                Text("Tap a method or card to filter the charts below", color = TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (totals.isEmpty()) item {
            Card2 { Text("No categorized spending this month yet.", color = TextDim) }
        } else {
            item {
                Card2 {
                    Donut(totals, sel, if (sel == null) money(total) else money(totals[sel] ?: 0.0), sel ?: "Total")
                    Spacer(Modifier.height(12.dp))
                    totals.entries.sortedByDescending { it.value }.forEach { (c, v) ->
                        val bg by animateColorAsState(if (sel == c) catColor(c).copy(alpha = 0.2f) else Color.Transparent, label = "legend")
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg)
                                .clickable { sel = if (sel == c) null else c }.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(catColor(c)))
                            Spacer(Modifier.width(10.dp))
                            Text("${catEmoji(c)} $c", Modifier.weight(1f))
                            Text(money(v), fontWeight = FontWeight.SemiBold)
                            Text("  ${"%.0f".format(v / total * 100)}%", color = TextDim)
                        }
                    }
                }
            }
            item {
                Card2 {
                    Text("Money flow", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    FlowDiagram(totals)
                }
            }
            item {
                Card2 {
                    Text("Top spends", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val max = merchants.first().value
                    merchants.forEach { (m, v) ->
                        val frac by animateFloatAsState((v / max).toFloat(), tween(800), label = "bar")
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row {
                                Text(m, Modifier.weight(1f), maxLines = 1)
                                Text(money(v), color = TextDim)
                            }
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Panel2)) {
                                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Donut(totals: Map<String, Double>, sel: String?, centerValue: String, centerLabel: String) {
    val prog = remember { Animatable(0f) }
    LaunchedEffect(totals) { prog.snapTo(0f); prog.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) {
            val total = totals.values.sum()
            val stroke = 30.dp.toPx()
            val pad = stroke / 2
            val gap = if (totals.size > 1) 3f else 0f
            var start = -90f
            totals.entries.sortedByDescending { it.value }.forEach { (c, v) ->
                val full = (v / total * 360f).toFloat()
                val dim = sel != null && sel != c
                drawArc(
                    catColor(c).copy(alpha = if (dim) 0.25f else 1f), start + gap / 2, ((full - gap) * prog.value).coerceAtLeast(0.1f),
                    useCenter = false, topLeft = Offset(pad, pad), size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Butt),
                )
                start += full
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerLabel, color = TextDim, style = MaterialTheme.typography.labelLarge)
            Text(centerValue, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/** Sankey-style flow: "Spent" on the left splits into one band per category on the right. */
@Composable
private fun FlowDiagram(totals: Map<String, Double>) {
    val measurer = rememberTextMeasurer()
    val total = totals.values.sum()
    val prog = remember { Animatable(0f) }
    LaunchedEffect(totals) { prog.snapTo(0f); prog.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    Canvas(Modifier.fillMaxWidth().height(260.dp)) {
        val h = size.height
        val gap = 10.dp.toPx()
        val nodeW = 12.dp.toPx()
        val rightX = size.width - 110.dp.toPx() - nodeW
        val x0 = nodeW + 4.dp.toPx()
        val scale = (h - gap * (totals.size - 1)) / total.toFloat()
        val leftScale = h / total.toFloat()
        clipRect(0f, 0f, size.width * prog.value, h) {
            drawRoundRect(Color(0xFF64748B), Offset(0f, 0f), Size(nodeW, h))
            var ly = 0f
            var ry = 0f
            totals.entries.sortedByDescending { it.value }.forEach { (cat, amt) ->
                val color = catColor(cat)
                val lh = amt.toFloat() * leftScale
                val rh = amt.toFloat() * scale
                val mid = (x0 + rightX) / 2
                val p = Path().apply {
                    moveTo(x0, ly)
                    cubicTo(mid, ly, mid, ry, rightX, ry)
                    lineTo(rightX, ry + rh)
                    cubicTo(mid, ry + rh, mid, ly + lh, x0, ly + lh)
                    close()
                }
                drawPath(p, color.copy(alpha = 0.4f), style = Fill)
                drawRoundRect(color, Offset(rightX, ry), Size(nodeW, rh))
                val label = measurer.measure(
                    "$cat\n${money(amt)}", TextStyle(fontSize = 11.sp, color = Color(0xFFE5E9F5)),
                )
                drawText(label, topLeft = Offset(rightX + nodeW + 8.dp.toPx(), ry + rh / 2 - label.size.height / 2f))
                ly += lh
                ry += rh + gap
            }
        }
    }
}
