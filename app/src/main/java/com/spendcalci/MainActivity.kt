package com.spendcalci

import android.Manifest
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onStop() {
        super.onStop()
        SpendWidget.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        val db = AppDb.get(this)
        setContent { AppTheme { App(db.dao(), db.settings()) } }
    }
}

private enum class Tab(val title: String, val icon: String) {
    Overview("Overview", "📊"),
    Activity("Activity", "🧾"),
    Owes("Owes", "🤝"),
    Trends("Trends", "📈"),
    Unlabelled("Unlabelled", "🏷️"),
}

@Composable
private fun App(dao: TxnDao, settings: SettingsDao) {
    val ctx = LocalContext.current
    val all by dao.all().collectAsState(emptyList())
    val cardAccounts by dao.cardAccounts().collectAsState(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var adding by remember { mutableStateOf(false) }
    var customizing by rememberSaveable { mutableStateOf(false) }
    var nagShown by rememberSaveable { mutableStateOf(false) }
    var nagOpen by remember { mutableStateOf(false) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { TimeFix.applyIfPresent(ctx) }
        perms.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS))
    }

    val monthTxns = remember(all, month) { all.filter { it.ym() == month } }
    val pendingList = remember(all) { all.filter { it.category == null }.sortedBy { it.timestamp } }
    val pending = pendingList.size
    val goUnlabelled = { tab = Tab.Unlabelled.ordinal }

    LaunchedEffect(pending) {
        if (pending > 0 && !nagShown) { nagShown = true; nagOpen = true }
    }

    if (customizing) {
        BackHandler { customizing = false }
        Box(Modifier.fillMaxSize().background(Bg)) {
            CustomizeScreen(dao, settings) { customizing = false }
        }
        return
    }

    Scaffold(
        containerColor = Bg,
        floatingActionButton = {
            if (tab == Tab.Activity.ordinal) SmallFloatingActionButton(
                onClick = { adding = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
            ) { Text("＋", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
        },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                Tab.values().forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = {
                            BadgedBox(badge = { if (t == Tab.Unlabelled && pending > 0) Badge { Text("$pending") } }) { Text(t.icon) }
                        },
                        label = { Text(t.title, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Panel2),
                    )
                }
            }
        },
    ) { pad ->
        AnimatedContent(
            tab, Modifier.padding(pad), label = "tab",
            transitionSpec = {
                val d = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(300)) { it / 4 * d } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(tween(300)) { -it / 4 * d } + fadeOut(tween(200)))
            },
        ) { t ->
            when (Tab.values()[t]) {
                Tab.Overview -> Overview(month, { month = it }, monthTxns, pending, goUnlabelled) { customizing = true }
                Tab.Activity -> Transactions(month, { month = it }, monthTxns, all, pending, goUnlabelled) { editing = it }
                Tab.Owes -> DebtsScreen(all, dao)
                Tab.Trends -> Trends(all)
                Tab.Unlabelled -> Unlabelled(pendingList, dao)
            }
        }
    }
    editing?.let { EditSheet(it, dao) { editing = null } }
    if (adding) AddSheet(dao, cardAccounts) { adding = false }

    if (nagOpen && pending > 0) AlertDialog(
        onDismissRequest = { nagOpen = false },
        containerColor = Panel,
        title = { Text("🏷️ $pending unlabelled payment${if (pending > 1) "s" else ""}") },
        text = { Text("${money(pendingList.sumOf { it.amount })} isn't accounted for yet. Label them now while you still remember.") },
        confirmButton = { Button({ nagOpen = false; tab = Tab.Unlabelled.ordinal }) { Text("Label now") } },
        dismissButton = { TextButton({ nagOpen = false }) { Text("Later") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(t: Txn, dao: TxnDao, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var cat by remember { mutableStateOf(t.category) }
    var note by remember { mutableStateOf(t.note ?: "") }
    var editMode by remember { mutableStateOf(t.category == null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var split by remember { mutableStateOf(t.split) }
    var draft by remember { mutableStateOf(SplitDraft(t.amount, t.members())) }
    fun close(action: suspend () -> Unit) = scope.launch {
        action(); sheet.hide(); onDismiss()
    }
    suspend fun save() {
        val c = cat ?: return
        if (split) {
            val existing = t.members().associateBy { it.name }
            val list = draft.toMembers().map { m -> m.copy(settled = existing[m.name]?.settled ?: false) }
            dao.categorizeSplit(t.id, c, note.trim(), encodeMembers(list))
        } else {
            dao.categorizeNormal(t.id, c, note.trim())
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Panel) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(money(t.amount), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (!editMode) {
                    IconButton({ editMode = true }) { Icon(Icons.Outlined.Edit, "Edit", tint = MaterialTheme.colorScheme.primary) }
                    IconButton({ confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete", tint = Danger) }
                }
            }
            Text("${t.merchant}  •  ${bucketLabel(t.bucket())}  •  ${t.account}", color = TextDim)
            Text(
                Instant.ofEpochMilli(t.timestamp).atZone(zone).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy  •  h:mm a")),
                color = TextDim,
            )
            if (t.split) {
                Text(
                    "👥 Split ${t.members().size + 1} ways • paid ${money(t.amount)} • your share ${money(t.myShare())}",
                    color = hex(SPLIT_COLOR), style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!editMode) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel2).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(catEmoji(t.category), fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(t.category ?: "Unlabelled", fontWeight = FontWeight.SemiBold, color = catColor(t.category))
                        Text(t.note?.takeIf { it.isNotBlank() } ?: "No note", color = TextDim)
                    }
                }
            } else {
                CategoryPicker(cat) { cat = it }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Exact use") }, singleLine = true)
                SingleChoice(
                    options = listOf(false to "🧍 Just me", true to "👥 Split"),
                    selected = split,
                    onSelect = { split = it },
                )
                if (split) SplitEditor(draft)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({
                        if (t.category == null) onDismiss() else {
                            cat = t.category; note = t.note ?: ""; split = t.split
                            draft = SplitDraft(t.amount, t.members())
                            editMode = false
                        }
                    }, Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        { close { save() } }, Modifier.weight(2f),
                        enabled = cat != null && (!split || draft.isValid),
                    ) { Text("Save") }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = Panel,
        title = { Text("Delete this payment?") },
        text = { Text("${money(t.amount)} to ${t.merchant} will be removed from your records.") },
        confirmButton = { TextButton({ confirmDelete = false; close { dao.delete(t.id) } }) { Text("Delete", color = Danger) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}
