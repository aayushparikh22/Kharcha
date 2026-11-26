package com.spendcalci

import android.content.Context
import java.io.File

/** One-shot patch: applies "hash,epochMillis" lines from files/time_fix.csv, then deletes the file. */
object TimeFix {
    suspend fun applyIfPresent(ctx: Context) {
        val f = File(ctx.filesDir, "time_fix.csv")
        if (!f.exists()) return
        val dao = AppDb.get(ctx).dao()
        f.readLines().forEach { line ->
            val p = line.split(",")
            if (p.size == 2) p[1].trim().toLongOrNull()?.let { dao.setTimestamp(p[0].trim(), it) }
        }
        f.delete()
        SpendWidget.refresh(ctx)
    }
}
