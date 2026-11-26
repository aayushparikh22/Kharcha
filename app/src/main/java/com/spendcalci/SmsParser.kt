package com.spendcalci

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedTxn(
    val hash: String,
    val amount: Double,
    val merchant: String,
    val source: String, // UPI, CARD or OTHER
    val account: String,
    val timestamp: Long,
    val category: String? = null, // preset for messages that never need a prompt (SIP mandates)
    val note: String? = null,
)

/**
 * Debit-only parser for bank SMS. Known formats (HDFC, Axis, SBI, ICICI) are matched precisely;
 * anything else goes through a generic debit fallback that covers most Indian banks.
 * Credits, OTPs and refunds never match.
 */
object SmsParser {
    private val num = """([\d,]+(?:\.\d+)?)"""
    private val flags = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val ci = RegexOption.IGNORE_CASE

    private val mandate = Regex(
        """UPI\s+Mandate:?\s*Sent\s+Rs\.?\s*$num\s+from\s+.+?A/C\s+\*?(\w+)\s+To\s+(.+?)\s+(\d{2}/\d{2}/\d{2})\s+Ref\s+(\d+)""", flags,
    )
    private val card = Regex(
        """Spent\s+Rs\.?\s*$num\s+On\s+.+?\s+Card\s+(\w+)\s+At\s+(.+?)\s+On\s+(\d{4}-\d{2}-\d{2}):(\d{2}:\d{2}:\d{2})""", flags,
    )
    private val upi = Regex(
        """Sent\s+Rs\.?\s*$num\s+From\s+.+?A/C\s+\*?(\w+)\s+To\s+(.+?)\s+On\s+(\d{2}/\d{2}/\d{2})\s+Ref\s+(\d+)""", flags,
    )
    private val axis = Regex(
        """INR\s+$num\s+debited\s+.*?A/c\s+no\.?\s+X*(\d{4})\s*,?\s*(\d{2}-\d{2}-\d{2}),?\s*(\d{2}:\d{2}:\d{2})\s+UPI/\w+/(\d+)/(.+?)\s*Not\s*you""", flags,
    )
    private val sbi = Regex(
        """A/C\s+[X*]*(\d{4})\s+debited\s+by\s+$num\s+on\s+date\s+(\d{2}[A-Za-z]{3}\d{2})\s+trf\s+to\s+(.+?)\s+Refno\s+(\d+)""", flags,
    )
    private val icici = Regex(
        """Acct\s+X*(\d{3,})\s+debited\s+for\s+Rs\.?\s*$num\s+on\s+(\d{2}-[A-Za-z]{3}-\d{2});?\s*(.+?)\s+credited.*?UPI:\s*(\d+)""", flags,
    )

    private val genericReject = Regex("""credit alert|refund|received|reversal|\botp\b|verification code""", ci)
    private val genericDebit = Regex("""\b(debited|debit|spent|sent|paid|withdrawn|purchase)\b""", ci)
    private val amountBefore = Regex("""(?:debited|spent|sent|paid)\s+(?:from|by|for|of|with)?\s*(?:Rs\.?|INR|₹)\s*([\d,]+(?:\.\d+)?)""", ci)
    private val amountAfter = Regex("""(?:Rs\.?|INR|₹)\s*([\d,]+(?:\.\d+)?)\s*(?:has\s+been\s+)?(?:debited|spent|sent|paid)""", ci)
    private val genericAccount = Regex("""\b(?:a/c|ac|acct|account|card)\b\.?\s*(?:no\.?)?\s*[X*]*\s*(\d{4,})""", ci)
    private val genericMerchant = Regex(
        """\b(?:to|at|towards)\s+([^\n.]{3,40}?)\s*(?:\.|\n|,|\s+on\b|\s+ref\b|\s+upi\b|\s+via\b|\s+not\s+you|$)""", ci,
    )
    private val genericRef = Regex("""\b(?:ref|refno|utr|txn\s*id|transaction\s*id)\.?\s*(?:no\.?)?\s*:?\s*#?([A-Za-z0-9]{6,})""", ci)
    private val zone = ZoneId.systemDefault()

    /** Bank alerts come from alphanumeric sender IDs (e.g. AD-AXISBK-S); personal texts come from phone numbers. */
    fun isBankSender(sender: String?): Boolean = sender != null && sender.any { it.isLetter() }

    fun parse(body: String, receivedAt: Long): ParsedTxn? {
        mandate.find(body)?.let { m ->
            val (amt, acct, merchant, date, ref) = m.destructured
            return ParsedTxn(
                "UPI-$ref", amt.toAmount(), merchant.clean(), "UPI", acct,
                dayTimestamp(LocalDate.parse(date, DateTimeFormatter.ofPattern("dd/MM/yy")), receivedAt),
                category = "Investment", note = "SIP",
            )
        }
        card.find(body)?.let { m ->
            val (amt, acct, merchant, date, time) = m.destructured
            val ts = LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()
            return ParsedTxn("CARD-$acct-$date-$time-$amt", amt.toAmount(), merchant.clean(), "CARD", acct, ts)
        }
        upi.find(body)?.let { m ->
            val (amt, acct, merchant, date, ref) = m.destructured
            return ParsedTxn(
                "UPI-$ref", amt.toAmount(), merchant.clean(), "UPI", acct,
                dayTimestamp(LocalDate.parse(date, DateTimeFormatter.ofPattern("dd/MM/yy")), receivedAt),
            )
        }
        axis.find(body)?.let { m ->
            val (amt, acct, date, time, ref, merchant) = m.destructured
            val ts = LocalDateTime.parse("$date $time", DateTimeFormatter.ofPattern("dd-MM-yy HH:mm:ss"))
                .atZone(zone).toInstant().toEpochMilli()
            return ParsedTxn("UPI-$ref", amt.toAmount(), merchant.clean(), "UPI", acct, ts)
        }
        sbi.find(body)?.let { m ->
            val (acct, amt, date, merchant, ref) = m.destructured
            val d = LocalDate.parse(date, DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH))
            return ParsedTxn("UPI-$ref", amt.toAmount(), merchant.clean(), "UPI", acct, dayTimestamp(d, receivedAt))
        }
        icici.find(body)?.let { m ->
            val (acct, amt, date, merchant, ref) = m.destructured
            val d = LocalDate.parse(date, DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH))
            return ParsedTxn("UPI-$ref", amt.toAmount(), merchant.clean(), "UPI", acct, dayTimestamp(d, receivedAt))
        }
        return generic(body, receivedAt)
    }

    private fun generic(body: String, receivedAt: Long): ParsedTxn? {
        if (genericReject.containsMatchIn(body)) return null
        if (!genericDebit.containsMatchIn(body)) return null
        if (body.contains("credited", ignoreCase = true) && !body.contains("debited", ignoreCase = true)) return null

        val amount = (amountBefore.find(body) ?: amountAfter.find(body))
            ?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        val account = genericAccount.find(body)?.groupValues?.get(1)?.takeLast(4).orEmpty()
        val merchant = genericMerchant.find(body)?.groupValues?.get(1)?.clean()
            ?.takeIf { it.isNotBlank() && !it.all { c -> c.isDigit() || c == ' ' } } ?: "Bank payment"
        val ref = genericRef.find(body)?.groupValues?.get(1)
        val source = when {
            Regex("""\bUPI\b""").containsMatchIn(body) -> "UPI"
            Regex("""\bcard\b""", ci).containsMatchIn(body) -> "CARD"
            else -> "OTHER"
        }
        val hash = "GEN-" + (ref ?: sha(body))
        return ParsedTxn(hash, amount, merchant, source, account, receivedAt)
    }

    // UPI SMS has no time; use arrival time when it's the same day
    private fun dayTimestamp(d: LocalDate, receivedAt: Long): Long {
        val arrival = java.time.Instant.ofEpochMilli(receivedAt).atZone(zone)
        return if (arrival.toLocalDate() == d) receivedAt else d.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun String.toAmount() = replace(",", "").toDouble()
    private fun String.clean() = trim().replace(Regex("\\s+"), " ")
}
