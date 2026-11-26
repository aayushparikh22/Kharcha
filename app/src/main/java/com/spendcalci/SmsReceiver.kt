package com.spendcalci

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.takeIf { it.isNotEmpty() } ?: return
        // Personal messages (including ones that mention "debited") come from phone numbers, not bank sender IDs
        if (!SmsParser.isBankSender(msgs[0].originatingAddress)) return
        // Multipart SMS: join the parts
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val parsed = SmsParser.parse(body, msgs[0].timestampMillis) ?: return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDb.get(context).dao()
                val id = dao.insert(
                    Txn(
                        hash = parsed.hash, amount = parsed.amount, merchant = parsed.merchant,
                        source = parsed.source, account = parsed.account, timestamp = parsed.timestamp,
                        category = parsed.category, note = parsed.note,
                    )
                )
                if (id > 0) {
                    SpendWidget.refresh(context)
                    if (parsed.category == null) prompt(context, id, parsed)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun prompt(ctx: Context, id: Long, t: ParsedTxn) {
        val popup = Intent(ctx, CategoryPopupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CategoryPopupActivity.EXTRA_ID, id)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Payment prompts", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            ctx, id.toInt(), popup, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("₹${"%.2f".format(t.amount)} to ${t.merchant}")
            .setContentText("What was this for? Tap to categorize")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        nm.notify(id.toInt(), n)
    }

    companion object {
        const val CHANNEL = "payments"
    }
}
