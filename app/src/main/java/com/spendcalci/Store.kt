package com.spendcalci

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

const val SPLIT_COLOR = "#2DD4BF"

val PALETTE = listOf(
    "#F97316", "#F59E0B", "#FACC15", "#84CC16", "#10B981", "#14B8A6", "#38BDF8",
    "#3B82F6", "#1E40AF", "#8B5CF6", "#EC4899", "#EF4444", "#94A3B8",
)

val EMOJI_CHOICES = listOf(
    "🍔", "☕", "🛒", "🚕", "✈️", "🏠", "💡", "🎬", "🎮", "💊", "📚", "🎁",
    "👨‍👩‍👧", "📈", "🧾", "🐾", "👕", "💼",
)

private val DEFAULT_CARD_COLORS = mapOf("0602" to "#F97316", "1145" to "#1E40AF")
private val METHOD_DEFAULTS = mapOf("UPI" to "#38BDF8", "CARD" to "#A78BFA", "CASH" to "#34D399", "OTHER" to "#FB923C")
private const val METHOD_KEY = "method:"

private val DEFAULT_CATEGORIES = listOf(
    CustomCategory("Food", "#F59E0B", "🍔"),
    CustomCategory("Travel", "#3B82F6", "🚕"),
    CustomCategory("Investment", "#10B981", "📈"),
    CustomCategory("Family/Friend", "#EC4899", "👨‍👩‍👧"),
    CustomCategory("Miscellaneous", "#8B5CF6", "🧾"),
)

/** Process-wide, observable copy of the customisation tables so every screen recomposes on edits. */
object Store {
    val categories = mutableStateListOf<CustomCategory>()
    val cards = mutableStateMapOf<String, CardAlias>()
    val methodColors = mutableStateMapOf<String, String>()
    private var started = false

    fun init(ctx: Context) {
        if (started) return
        started = true
        val settings = AppDb.get(ctx).settings()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            if (settings.categoriesNow().isEmpty()) DEFAULT_CATEGORIES.forEach { settings.saveCategory(it) }
        }
        scope.launch {
            settings.categories().collect { list -> categories.clear(); categories.addAll(list) }
        }
        scope.launch {
            settings.cards().collect { list -> cards.clear(); list.forEach { cards[it.suffix] = it } }
        }
        scope.launch {
            settings.prefs().collect { list ->
                methodColors.clear()
                list.filter { it.key.startsWith(METHOD_KEY) }
                    .forEach { methodColors[it.key.removePrefix(METHOD_KEY)] = it.value }
            }
        }
    }
}

fun hex(s: String): Color = Color(android.graphics.Color.parseColor(s))

fun categoryNames(): List<String> = Store.categories.map { it.name }

fun catColor(name: String?): Color =
    Store.categories.firstOrNull { it.name == name }?.let { hex(it.color) } ?: Amber

fun catEmoji(name: String?): String = Store.categories.firstOrNull { it.name == name }?.emoji ?: "❓"

fun cardName(suffix: String): String =
    Store.cards[suffix]?.name?.takeIf { it.isNotBlank() } ?: "Card $suffix"

fun cardColorHex(suffix: String): String =
    Store.cards[suffix]?.color ?: DEFAULT_CARD_COLORS[suffix] ?: PALETTE[abs(suffix.hashCode()) % PALETTE.size]

fun methodColorHex(m: String): String = Store.methodColors[m] ?: METHOD_DEFAULTS[m] ?: "#FB923C"

fun methodLabel(m: String): String = when (m) {
    "UPI" -> "📱 UPI"
    "CARD" -> "💳 Card"
    "CASH" -> "💵 Cash"
    else -> "🔖 Other"
}

fun bucketColor(b: String): Color =
    if (b.startsWith("CARD:")) hex(cardColorHex(b.removePrefix("CARD:"))) else hex(methodColorHex(b))

fun bucketLabel(b: String): String =
    if (b.startsWith("CARD:")) "💳 ${cardName(b.removePrefix("CARD:"))}" else methodLabel(b)

fun bucketRank(b: String): Int = when {
    b == "UPI" -> 0
    b.startsWith("CARD:") -> 1
    else -> 2
}
