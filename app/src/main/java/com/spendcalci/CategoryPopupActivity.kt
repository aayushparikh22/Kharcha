package com.spendcalci

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch

/** Pop-up for one newly detected payment: categorise it, split it, or ignore it entirely. */
class CategoryPopupActivity : ComponentActivity() {
    private val txnId = mutableLongStateOf(-1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        txnId.longValue = intent.getLongExtra(EXTRA_ID, -1L)
        val dao = AppDb.get(this).dao()
        setContent {
            AppTheme {
                Box(Modifier.fillMaxWidth().background(Panel)) {
                    PaymentPrompt(txnId.longValue, dao) { finishPrompt(it) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        txnId.longValue = intent.getLongExtra(EXTRA_ID, -1L)
    }

    private fun finishPrompt(id: Long) {
        NotificationManagerCompat.from(this).cancel(id.toInt())
        SpendWidget.refresh(this)
        finish()
    }

    companion object {
        const val EXTRA_ID = "txn_id"
    }
}

@Composable
private fun PaymentPrompt(id: Long, dao: TxnDao, onDone: (Long) -> Unit) {
    var loaded by remember(id) { mutableStateOf(false) }
    var txn by remember(id) { mutableStateOf<Txn?>(null) }
    LaunchedEffect(id) {
        txn = if (id > 0) dao.get(id) else null
        loaded = true
    }
    val t = txn
    LaunchedEffect(loaded, t) {
        // Nothing to ask about (ignored, deleted, or already labelled): just close
        if (loaded && (t == null || t.category != null)) onDone(id)
    }
    if (loaded && t != null && t.category == null) {
        key(t.id) { PromptForm(t, dao, onDone) }
    }
}

@Composable
private fun PromptForm(t: Txn, dao: TxnDao, onDone: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var cat by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var split by remember { mutableStateOf(false) }
    val draft = remember { SplitDraft(t.amount) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("New payment", color = TextDim, style = MaterialTheme.typography.labelLarge)
        Text(money(t.amount), style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Text("${t.merchant}  •  ${bucketLabel(t.bucket())}", color = TextDim)

        SingleChoice(
            options = listOf(false to "🧍 Just me", true to "👥 Split"),
            selected = split,
            onSelect = { split = it },
        )

        Text("What was this for?", style = MaterialTheme.typography.titleMedium)
        CategoryPicker(cat) { cat = it }
        OutlinedTextField(
            note, { note = it }, Modifier.fillMaxWidth(),
            label = { Text("Exact use (optional)") }, singleLine = true,
        )

        if (split) SplitEditor(draft)

        Button(
            onClick = {
                scope.launch {
                    val c = cat ?: return@launch
                    if (split) {
                        dao.categorizeSplit(t.id, c, note.trim(), encodeMembers(draft.toMembers()))
                    } else {
                        dao.categorize(t.id, c, note.trim())
                    }
                    onDone(t.id)
                }
            },
            enabled = cat != null && (!split || draft.isValid),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { scope.launch { dao.delete(t.id); onDone(t.id) } },
                modifier = Modifier.weight(1f),
            ) { Text("Ignore", color = Danger, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = { onDone(t.id) }, modifier = Modifier.weight(1f)) { Text("Later") }
        }
    }
}

/** Segmented two-option control used for Just me / Split. */
@Composable
fun SingleChoice(options: List<Pair<Boolean, String>>, selected: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Color.White else TextDim)
            }
        }
    }
}
