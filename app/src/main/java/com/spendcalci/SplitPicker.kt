package com.spendcalci

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

enum class SplitMode { EQUAL, PERCENT, AMOUNT }

class SplitPerson(val name: String, val isMe: Boolean = false, initial: Double = 0.0) {
    /** Percent (in PERCENT mode), rupees (in AMOUNT mode) or unused (in EQUAL mode). */
    var value by mutableDoubleStateOf(initial)
}

/** Editable split of one payment. Your own share is always whatever is left after the others. */
class SplitDraft(val total: Double, existing: List<SplitMember> = emptyList()) {
    var mode by mutableStateOf(if (existing.isEmpty()) SplitMode.EQUAL else SplitMode.AMOUNT)
        private set
    val people = mutableStateListOf<SplitPerson>()

    init {
        if (existing.isEmpty()) {
            people.add(SplitPerson("You", isMe = true))
        } else {
            people.add(SplitPerson("You", isMe = true, initial = total - existing.sumOf { it.owed }))
            existing.forEach { people.add(SplitPerson(it.name, initial = it.owed)) }
        }
    }

    fun shareOf(p: SplitPerson): Double = when (mode) {
        SplitMode.EQUAL -> total / people.size
        SplitMode.PERCENT -> total * p.value / 100.0
        SplitMode.AMOUNT -> p.value
    }

    val allocated: Double get() = people.sumOf { shareOf(it) }
    val remaining: Double get() = total - allocated
    val isBalanced: Boolean get() = abs(remaining) < 0.05
    val isValid: Boolean get() = people.size >= 2 && isBalanced && people.all { it.value >= 0 }

    fun chooseMode(m: SplitMode) {
        if (m == mode) return
        val shares = people.map { shareOf(it) }
        people.forEachIndexed { i, p ->
            when (m) {
                SplitMode.PERCENT -> p.value = if (total > 0) shares[i] / total * 100 else 0.0
                SplitMode.AMOUNT -> p.value = shares[i]
                SplitMode.EQUAL -> Unit
            }
        }
        mode = m
    }

    fun addPerson(name: String) {
        val n = name.trim()
        if (n.isEmpty() || people.any { it.name.equals(n, ignoreCase = true) }) return
        people.add(SplitPerson(n))
    }

    fun remove(p: SplitPerson) {
        if (!p.isMe) people.remove(p)
    }

    /** Puts whatever is still unassigned onto your own share. */
    fun assignRestToMe() {
        val me = people.firstOrNull { it.isMe } ?: return
        val rest = remaining
        when (mode) {
            SplitMode.AMOUNT -> me.value += rest
            SplitMode.PERCENT -> if (total > 0) me.value += rest / total * 100
            SplitMode.EQUAL -> Unit
        }
    }

    /** Amounts owed by the other people (your own share is the rest of the payment). */
    fun toMembers(): List<SplitMember> = people.filter { !it.isMe }
        .map { SplitMember(it.name, round2(shareOf(it))) }

    private fun round2(x: Double) = Math.round(x * 100) / 100.0
}

private fun fmt(v: Double): String = if (v == floor(v)) v.toLong().toString() else "%.2f".format(v)

/** Splitwise-style editor: equal, by percentage, or by exact amount, with the remaining amount always visible. */
@Composable
fun SplitEditor(draft: SplitDraft) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasContacts by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasContacts = it }

    LaunchedEffect(Unit) {
        if (!hasContacts) askContacts.launch(Manifest.permission.READ_CONTACTS)
    }
    LaunchedEffect(query, hasContacts) {
        contacts = if (hasContacts) withContext(Dispatchers.IO) { Contacts.search(ctx, query.trim()) } else emptyList()
    }

    val typed = query.trim()
    val taken = draft.people.map { it.name.lowercase() }.toSet()
    val canAddTyped = typed.isNotEmpty() && typed.lowercase() !in taken
    val suggestions = contacts.filter { it.lowercase() !in taken }.take(4)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Split between", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(draft.mode == SplitMode.EQUAL, { draft.chooseMode(SplitMode.EQUAL) }, label = { Text("Equal") })
            FilterChip(draft.mode == SplitMode.PERCENT, { draft.chooseMode(SplitMode.PERCENT) }, label = { Text("By %") })
            FilterChip(draft.mode == SplitMode.AMOUNT, { draft.chooseMode(SplitMode.AMOUNT) }, label = { Text("By ₹") })
        }

        draft.people.forEach { p -> PersonRow(draft, p) }

        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(16.dp),
            placeholder = { Text("Add person or search contacts") },
        )
        if (canAddTyped) CandidateRow("＋  Add \"$typed\"") { draft.addPerson(typed); query = "" }
        suggestions.forEach { name -> CandidateRow("👤  $name") { draft.addPerson(name); query = "" } }
        if (!hasContacts) {
            Text(
                "Allow contacts access to search your contacts.",
                color = TextDim, style = MaterialTheme.typography.labelSmall,
            )
        }

        RemainingCard(draft)
    }
}

@Composable
private fun PersonRow(draft: SplitDraft, p: SplitPerson) {
    var text by remember(p) { mutableStateOf(fmt(p.value)) }
    LaunchedEffect(p.value) {
        if (text.toDoubleOrNull() != p.value) text = fmt(p.value)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
            Text(if (p.isMe) "🙂" else p.name.take(1).uppercase(), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (p.isMe) "You" else p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(money(draft.shareOf(p)), style = MaterialTheme.typography.bodySmall, color = TextDim)
        }
        if (draft.mode != SplitMode.EQUAL) {
            OutlinedTextField(
                text,
                { t ->
                    if (t.length <= 10 && t.matches(Regex("""\d*\.?\d*"""))) {
                        text = t
                        p.value = t.toDoubleOrNull() ?: 0.0
                    }
                },
                Modifier.width(132.dp),
                singleLine = true,
                suffix = { Text(if (draft.mode == SplitMode.PERCENT) "%" else "₹") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
            )
        }
        if (!p.isMe) {
            TextButton(onClick = { draft.remove(p) }) { Text("✕", color = TextDim) }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun CandidateRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) { Text(label) }
}

@Composable
private fun RemainingCard(draft: SplitDraft) {
    val r = draft.remaining
    val color = when {
        draft.people.size < 2 -> TextDim
        draft.isBalanced -> Positive
        r > 0 -> Amber
        else -> Danger
    }
    val message = when {
        draft.people.size < 2 -> "Add at least one other person"
        draft.isBalanced -> "✓  ${money(draft.total)} fully assigned"
        r > 0 -> "${money(r)} still unassigned"
        else -> "Over by ${money(-r)}"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (!draft.isBalanced && r > 0 && draft.mode != SplitMode.EQUAL && draft.people.size >= 2) {
            TextButton(onClick = { draft.assignRestToMe() }) { Text("Give to me") }
        }
    }
}
