package com.spendcalci

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Transactions(
    month: YearMonth, onMonth: (YearMonth) -> Unit, monthTxns: List<Txn>, all: List<Txn>,
    pending: Int, onPending: () -> Unit, onOpen: (Txn) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("Month") }
    var weekStart by remember { mutableStateOf(LocalDate.now().with(DayOfWeek.MONDAY)) }
    var range by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var pickRange by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var pickExport by remember { mutableStateOf(false) }

    val buckets = all.map { it.bucket() }.distinct().sortedWith(compareBy({ bucketRank(it) }, { it }))

    val txns = when (mode) {
        "Week" -> all.filter { it.day() >= weekStart && it.day() <= weekStart.plusDays(6) }
        "Range" -> range?.let { (a, b) -> all.filter { it.day() >= a && it.day() <= b } } ?: emptyList()
        else -> monthTxns
    }
    val filtered = txns.filter { t ->
        val okFilter = when (filter) {
            null -> true
            "PENDING" -> t.category == null
            "SPLIT" -> t.split
            in buckets -> t.bucket() == filter
            else -> t.category == filter
        }
        okFilter && (q.isBlank() || t.merchant.contains(q, true) || (t.note ?: "").contains(q, true))
    }
    val groups = filtered.groupBy { it.day() }

    fun runExport(from: LocalDate, to: LocalDate) {
        val list = all.filter { it.day() >= from && it.day() <= to }
        if (list.isEmpty()) {
            Toast.makeText(ctx, "No payments in that period", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val file = withContext(Dispatchers.IO) { PaymentExport.write(ctx, list, from, to) }
            PaymentExport.share(ctx, file)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Month", "Week", "Range").forEach { m ->
                    FilterChip(
                        mode == m,
                        { mode = m; if (m == "Range" && range == null) pickRange = true },
                        label = { Text(if (m == "Range") "📅 Date range" else m) },
                    )
                }
            }
        }
        item {
            when (mode) {
                "Week" -> {
                    val fmt = DateTimeFormatter.ofPattern("d MMM")
                    val thisWeek = LocalDate.now().with(DayOfWeek.MONDAY)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        FilledTonalIconButton({ weekStart = weekStart.minusWeeks(1) }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Panel)) { Text("‹", fontSize = 22.sp) }
                        Text(
                            if (weekStart == thisWeek) "This week" else "${weekStart.format(fmt)} – ${weekStart.plusDays(6).format(fmt)}",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        )
                        FilledTonalIconButton({ weekStart = weekStart.plusWeeks(1) }, enabled = weekStart < thisWeek, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Panel)) { Text("›", fontSize = 22.sp) }
                    }
                }
                "Range" -> {
                    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")
                    OutlinedButton({ pickRange = true }, Modifier.fillMaxWidth()) {
                        Text(range?.let { (a, b) -> if (a == b) a.format(fmt) else "${a.format(fmt)} – ${b.format(fmt)}" } ?: "Pick dates")
                    }
                }
                else -> MonthSwitcher(month, onMonth)
            }
        }
        item {
            OutlinedButton(onClick = { exportOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("⬇  Export to Excel")
            }
        }
        if (txns.isNotEmpty()) item {
            Text(
                "${filtered.size} payments  •  ${money(filtered.sumOf { it.myShare() })} your share",
                color = TextDim, style = MaterialTheme.typography.labelLarge,
            )
        }
        if (pending > 0) item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Amber.copy(alpha = 0.15f))
                    .clickable { onPending() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$pending unlabelled — label them one by one", Modifier.weight(1f), color = Amber)
                Text("Start ›", color = Amber, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            OutlinedTextField(
                q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Search merchant or note") },
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf<String?>(null, "PENDING", "SPLIT") + buckets + categoryNames()) { c ->
                    FilterChip(
                        filter == c, { filter = if (filter == c) null else c },
                        label = {
                            Text(
                                when (c) {
                                    null -> "All"
                                    "PENDING" -> "⏳ Pending"
                                    "SPLIT" -> "👥 Split"
                                    in buckets -> bucketLabel(c)
                                    else -> "${catEmoji(c)} $c"
                                },
                            )
                        },
                    )
                }
            }
        }
        if (filtered.isEmpty()) item {
            Text("Nothing here.", color = TextDim, modifier = Modifier.padding(24.dp))
        }
        groups.forEach { (d, list) ->
            stickyHeader(key = d.toString()) {
                Text(
                    d.format(DateTimeFormatter.ofPattern("EEE, d MMM")) + "  •  " + money(list.sumOf { it.myShare() }),
                    Modifier.fillMaxWidth().background(Bg).padding(vertical = 6.dp),
                    color = TextDim, style = MaterialTheme.typography.labelLarge,
                )
            }
            items(list, key = { it.id }) { t -> TxnRow(t, Modifier.animateItemPlacement(), onOpen) }
        }
    }

    if (exportOpen) AlertDialog(
        onDismissRequest = { exportOpen = false },
        containerColor = Panel,
        title = { Text("Export to Excel") },
        text = {
            Column {
                val today = LocalDate.now()
                listOf(
                    "This month" to (today.withDayOfMonth(1) to today),
                    "Last 7 days" to (today.minusDays(6) to today),
                    "Last 30 days" to (today.minusDays(29) to today),
                    "This year" to (today.withDayOfYear(1) to today),
                ).forEach { (label, r) ->
                    TextButton(
                        onClick = { exportOpen = false; runExport(r.first, r.second) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label) }
                }
                TextButton(onClick = { exportOpen = false; pickExport = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Custom range…")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { exportOpen = false }) { Text("Cancel") } },
    )

    if (pickExport) DateRangeDialog(
        onPick = { a, b -> pickExport = false; runExport(a, b) },
        onDismiss = { pickExport = false },
    )

    if (pickRange) DateRangeDialog(
        onPick = { a, b -> range = a to b; pickRange = false },
        onDismiss = { pickRange = false; if (range == null) mode = "Month" },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(onPick: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDateRangePickerState(
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis() + 86_400_000L
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val s = state.selectedStartDateMillis
                    if (s != null) {
                        val a = Instant.ofEpochMilli(s).atZone(ZoneOffset.UTC).toLocalDate()
                        val b = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: a
                        onPick(a, b)
                    }
                },
                enabled = state.selectedStartDateMillis != null,
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DateRangePicker(state, Modifier.height(500.dp)) }
}

@Composable
private fun TxnRow(t: Txn, modifier: Modifier, onOpen: (Txn) -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(350)) }
    val tint = bucketColor(t.bucket())
    val shape = RoundedCornerShape(18.dp)
    val splitBorder = if (t.split) Modifier.border(1.5.dp, hex(SPLIT_COLOR).copy(alpha = 0.7f), shape) else Modifier
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .graphicsLayer { alpha = a.value; translationY = (1 - a.value) * 40f }
            .clip(shape).background(Panel).then(splitBorder)
            .clickable { onOpen(t) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(tint))
        Row(Modifier.weight(1f).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(catColor(t.category).copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Text(catEmoji(t.category), fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.merchant, maxLines = 1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                    if (t.split) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(hex(SPLIT_COLOR).copy(alpha = 0.18f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text("👥 SPLIT", color = hex(SPLIT_COLOR), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                val sub = when {
                    t.category == null -> "Tap to categorize"
                    t.split -> "${t.category} • split ${t.members().size + 1} ways"
                    else -> t.category
                }
                Text(
                    sub, color = if (t.category == null) Amber else TextDim,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(t.myShare()), fontWeight = FontWeight.SemiBold)
                if (t.split) Text("of ${money(t.amount)}", color = TextDim, style = MaterialTheme.typography.labelSmall)
                Text(
                    Instant.ofEpochMilli(t.timestamp).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a")) + " • " + bucketLabel(t.bucket()),
                    color = TextDim, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                )
            }
        }
    }
}
