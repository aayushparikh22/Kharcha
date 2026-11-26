package com.spendcalci

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Unlabelled(pending: List<Txn>, dao: TxnDao) {
    var openId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("Unlabelled", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (pending.isEmpty()) "All caught up 🎉" else "${pending.size} to go • ${money(pending.sumOf { it.amount })} unaccounted",
                    color = if (pending.isEmpty()) TextDim else Amber,
                )
            }
        }
        items(pending, key = { it.id }) { t ->
            val open = openId == t.id
            var cat by remember(t.id) { mutableStateOf<String?>(null) }
            var note by remember(t.id) { mutableStateOf("") }
            Column(
                Modifier.fillMaxWidth().animateItemPlacement().animateContentSize()
                    .clip(RoundedCornerShape(20.dp)).background(Panel)
                    .clickable { openId = if (open) null else t.id }.padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.merchant, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(
                            Instant.ofEpochMilli(t.timestamp).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM, h:mm a")) + " • ${t.source}",
                            color = TextDim, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(money(t.amount), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                if (open) {
                    Spacer(Modifier.height(12.dp))
                    CategoryPicker(cat) { cat = it }
                    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Exact use (optional)") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        { scope.launch { dao.categorize(t.id, cat!!, note.trim()) } },
                        Modifier.fillMaxWidth(), enabled = cat != null,
                    ) { Text("Save") }
                } else {
                    Text("Tap to label", color = Amber, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}
