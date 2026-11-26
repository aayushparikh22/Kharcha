package com.spendcalci

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun CustomizeScreen(dao: TxnDao, settings: SettingsDao, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cards by dao.cardAccounts().collectAsState(emptyList())
    var editingCat by remember { mutableStateOf<CustomCategory?>(null) }
    var addingCat by remember { mutableStateOf(false) }
    var editingMethod by remember { mutableStateOf<String?>(null) }
    var editingCard by remember { mutableStateOf<String?>(null) }

    fun refreshWidget() = SpendWidget.refresh(ctx)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", fontSize = 16.sp) }
            Text("Customize", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        SectionTitle("Categories")
        Store.categories.toList().forEach { c ->
            EditRow(c.color, "${c.emoji}  ${c.name}", null) { editingCat = c }
        }
        OutlinedButton(onClick = { addingCat = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ Add category") }

        SectionTitle("Payment methods")
        listOf("UPI", "CASH", "OTHER").forEach { m ->
            EditRow(methodColorHex(m), methodLabel(m), null) { editingMethod = m }
        }

        SectionTitle("Cards")
        if (cards.isEmpty()) {
            Text("Card payments will appear here once they're detected.", color = TextDim, style = MaterialTheme.typography.bodySmall)
        }
        cards.forEach { s ->
            EditRow(cardColorHex(s), cardName(s), "•••• $s") { editingCard = s }
        }
    }

    editingCat?.let { c ->
        CategoryEditor(
            initial = c,
            canDelete = Store.categories.size > 1,
            onDismiss = { editingCat = null },
            onSave = { name, color, emoji ->
                scope.launch {
                    if (name != c.name) {
                        settings.saveCategory(CustomCategory(name, color, emoji))
                        dao.reassignCategory(c.name, name)
                        settings.deleteCategory(c.name)
                    } else {
                        settings.saveCategory(CustomCategory(name, color, emoji))
                    }
                    editingCat = null
                    refreshWidget()
                }
            },
            onDelete = {
                scope.launch {
                    val fallback = Store.categories.firstOrNull { it.name == "Miscellaneous" && it.name != c.name }
                        ?: Store.categories.firstOrNull { it.name != c.name } ?: return@launch
                    dao.reassignCategory(c.name, fallback.name)
                    settings.deleteCategory(c.name)
                    editingCat = null
                    refreshWidget()
                }
            },
        )
    }

    if (addingCat) {
        CategoryEditor(
            initial = null,
            canDelete = false,
            onDismiss = { addingCat = false },
            onSave = { name, color, emoji ->
                scope.launch {
                    settings.saveCategory(CustomCategory(name, color, emoji))
                    addingCat = false
                    refreshWidget()
                }
            },
            onDelete = {},
        )
    }

    editingMethod?.let { m ->
        ColorEditor(
            title = methodLabel(m), showName = false, initialName = "",
            initialColor = methodColorHex(m),
            onDismiss = { editingMethod = null },
        ) { _, color ->
            scope.launch {
                settings.saveSetting(AppSetting("method:$m", color))
                editingMethod = null
                refreshWidget()
            }
        }
    }

    editingCard?.let { s ->
        ColorEditor(
            title = "Card •••• $s", showName = true, initialName = cardName(s),
            initialColor = cardColorHex(s),
            onDismiss = { editingCard = null },
        ) { name, color ->
            scope.launch {
                settings.saveCard(CardAlias(s, name.ifBlank { "Card $s" }, color))
                editingCard = null
                refreshWidget()
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextDim, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun EditRow(color: String, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(hex(color)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, color = TextDim, style = MaterialTheme.typography.bodySmall) }
        }
        Text("Edit ›", color = TextDim)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(
    initial: CustomCategory?,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: PALETTE.first()) }
    var emoji by remember { mutableStateOf(initial?.emoji ?: EMOJI_CHOICES.first()) }
    val trimmed = name.trim()
    val duplicate = trimmed.isNotEmpty() && trimmed != initial?.name && Store.categories.any { it.name == trimmed }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (initial == null) "New category" else "Edit category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Name") }, isError = duplicate,
                    supportingText = { if (duplicate) Text("A category with this name already exists") },
                )
                Text("Icon", color = TextDim, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EMOJI_CHOICES.forEach { e ->
                        Box(
                            Modifier.size(42.dp).clip(CircleShape)
                                .background(if (emoji == e) hex(color).copy(alpha = 0.35f) else Panel2)
                                .clickable { emoji = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }
                Text("Colour", color = TextDim, style = MaterialTheme.typography.labelLarge)
                ColorSwatches(color) { color = it }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(trimmed, color, emoji) }, enabled = trimmed.isNotEmpty() && !duplicate) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = onDelete, enabled = canDelete) {
                    Text("Delete", color = if (canDelete) Danger else TextDim)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorEditor(
    title: String,
    showName: Boolean,
    initialName: String,
    initialColor: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (showName) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
                ColorSwatches(color) { color = it }
            }
        },
        confirmButton = { Button(onClick = { onSave(name.trim(), color) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
