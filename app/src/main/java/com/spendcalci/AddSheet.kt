package com.spendcalci

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Manual entry for spends that never produce an SMS (cash, UPI outside SMS, other cards). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddSheet(dao: TxnDao, cardAccounts: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amount by remember { mutableStateOf("") }
    var what by remember { mutableStateOf("") }
    var pay by remember { mutableStateOf("UPI") }
    var newCard by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var pickDate by remember { mutableStateOf(false) }
    val value = amount.toDoubleOrNull()
    val bucket: String? = when (pay) {
        "NEWCARD" -> newCard.takeIf { it.length == 4 }?.let { "CARD:$it" }
        else -> pay
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Panel) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add expense", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                amount, { s -> amount = s.filter { it.isDigit() || it == '.' } }, Modifier.fillMaxWidth(),
                label = { Text("Amount (₹)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(what, { what = it }, Modifier.fillMaxWidth(), label = { Text("Paid to / what for") }, singleLine = true)
            Text("Paid with", color = androidx.compose.ui.graphics.Color(0xFF94A3B8), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("UPI", "CASH", "OTHER").forEach { m ->
                    FilterChip(pay == m, { pay = m }, label = { Text(bucketLabel(m)) })
                }
                cardAccounts.forEach { s ->
                    FilterChip(pay == "CARD:$s", { pay = "CARD:$s" }, label = { Text(bucketLabel("CARD:$s")) })
                }
                FilterChip(pay == "NEWCARD", { pay = "NEWCARD" }, label = { Text("＋ Other card") })
            }
            if (pay == "NEWCARD") OutlinedTextField(
                newCard, { newCard = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(),
                label = { Text("Last 4 digits of card") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            CategoryPicker(cat) { cat = it }
            OutlinedButton({ pickDate = true }, Modifier.fillMaxWidth()) {
                Text("📅 " + date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")))
            }
            Button(
                onClick = {
                    val b = bucket ?: return@Button
                    scope.launch {
                        val time = if (date == LocalDate.now()) LocalTime.now() else LocalTime.NOON
                        val ts = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
                        val isCard = b.startsWith("CARD:")
                        dao.insert(
                            Txn(
                                hash = "MAN-" + java.util.UUID.randomUUID(), amount = value!!,
                                merchant = what.trim().ifBlank { "Manual expense" },
                                source = if (isCard) "CARD" else b,
                                account = if (isCard) b.removePrefix("CARD:") else "manual",
                                timestamp = ts, category = cat, note = null,
                            )
                        )
                        SpendWidget.refresh(ctx)
                        sheet.hide(); onDismiss()
                    }
                },
                enabled = value != null && value > 0 && cat != null && bucket != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add") }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis() + 86_400_000L
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("OK") }
            },
        ) { DatePicker(state) }
    }
}
