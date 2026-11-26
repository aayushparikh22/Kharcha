package com.spendcalci

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/** Who owes you what from split payments, with per-person and per-payment settling. */
@Composable
fun DebtsScreen(all: List<Txn>, dao: TxnDao) {
    val scope = rememberCoroutineScope()
    val debts = debtsOf(all)
    val total = debts.sumOf { it.owed }
    val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Who owes you", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (debts.isEmpty()) "Nobody owes you anything 🎉"
                    else "${money(total)} to collect from ${debts.size} ${if (debts.size == 1) "person" else "people"}",
                    color = if (debts.isEmpty()) TextDim else Amber,
                )
            }
        }
        items(debts, key = { it.name }) { d ->
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(Panel2),
                        contentAlignment = Alignment.Center,
                    ) { Text(d.name.take(1).uppercase(), fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${d.items.size} payment${if (d.items.size > 1) "s" else ""}", color = TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(money(d.owed), fontWeight = FontWeight.Bold, color = Amber, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(10.dp))
                d.items.forEach { (t, m) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.merchant, maxLines = 1)
                            Text(
                                "${t.timestamp.let { java.time.Instant.ofEpochMilli(it).atZone(zone).format(dateFmt) }} • ${t.category ?: "Unlabelled"} • paid ${money(t.amount)}",
                                color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            )
                        }
                        Text(money(m.owed), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { scope.launch { settle(dao, t, d.name) } }) { Text("Settled") }
                    }
                }
                Button(
                    onClick = { scope.launch { d.items.forEach { (t, _) -> settle(dao, t, d.name) } } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Mark all settled") }
            }
        }
    }
}

private suspend fun settle(dao: TxnDao, t: Txn, name: String) {
    val updated = t.members().map { if (it.name == name) it.copy(settled = true) else it }
    dao.updateMembers(t.id, encodeMembers(updated))
}
