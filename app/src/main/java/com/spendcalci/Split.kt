package com.spendcalci

import android.content.Context
import android.provider.ContactsContract
import java.util.Locale

/** A person you split a payment with. [owed] is their share; [settled] flips once they pay you back. */
data class SplitMember(val name: String, val owed: Double, val settled: Boolean = false)

data class Debt(val name: String, val owed: Double, val items: List<Pair<Txn, SplitMember>>)

private const val ROW_SEP = "\n"
private const val COL_SEP = "\t"

fun encodeMembers(list: List<SplitMember>): String = list.joinToString(ROW_SEP) { m ->
    listOf(
        m.name.replace(COL_SEP, " ").replace(ROW_SEP, " "),
        String.format(Locale.US, "%.2f", m.owed),
        if (m.settled) "1" else "0",
    ).joinToString(COL_SEP)
}

fun decodeMembers(raw: String?): List<SplitMember> = raw.orEmpty().lines().mapNotNull { line ->
    val p = line.split(COL_SEP)
    if (p.size < 2) null else SplitMember(p[0], p[1].toDoubleOrNull() ?: 0.0, p.getOrNull(2) == "1")
}

fun Txn.members(): List<SplitMember> = if (split) decodeMembers(splitMembers) else emptyList()

/** Your own share: the full amount minus what the people you split with owe you. */
fun Txn.myShare(): Double = if (split) (amount - members().sumOf { it.owed }).coerceAtLeast(0.0) else amount

/** Outstanding amounts other people owe you, grouped by person, largest first. */
fun debtsOf(txns: List<Txn>): List<Debt> = txns
    .flatMap { t -> t.members().filter { !it.settled }.map { t to it } }
    .groupBy { it.second.name }
    .map { (name, rows) -> Debt(name, rows.sumOf { it.second.owed }, rows.sortedByDescending { it.first.timestamp }) }
    .sortedByDescending { it.owed }

object Contacts {
    /** Display names of contacts matching [query] (all contacts when blank). Empty if permission is missing. */
    fun search(ctx: Context, query: String): List<String> {
        val col = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        val sel = if (query.isBlank()) null else "$col LIKE ?"
        val args = if (query.isBlank()) null else arrayOf("%${query.trim()}%")
        val names = LinkedHashSet<String>()
        try {
            ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, arrayOf(col), sel, args, "$col COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext() && names.size < 30) c.getString(0)?.takeIf { it.isNotBlank() }?.let { names.add(it) }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return names.toList()
    }
}
