package com.spendcalci

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Home-screen widget: this month's donut + per-category amounts, drawn as one bitmap. */
class SpendWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { render(ctx, mgr, ids) } finally { pending.finish() }
        }
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SpendWidget::class.java))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch { render(ctx, mgr, ids) }
        }

        private suspend fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val db = AppDb.get(ctx)
            val month = YearMonth.now()
            val zone = java.time.ZoneId.systemDefault()
            val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val rows = db.dao().betweenList(from, to)
            val cats = db.settings().categoriesNow()
            val colors = cats.associate { it.name to it.color }
            val totals = cats.map { it.name }.associateWith { c ->
                rows.filter { it.category == c }.sumOf { it.myShare() }
            }.filterValues { it > 0 }
            val unlabelled = rows.count { it.category == null }
            val bmp = draw(month.format(DateTimeFormatter.ofPattern("MMMM")), totals, colors, unlabelled)

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            ids.forEach { id ->
                val views = RemoteViews(ctx.packageName, R.layout.widget_spend)
                views.setImageViewBitmap(R.id.widget_img, bmp)
                views.setOnClickPendingIntent(R.id.widget_img, open)
                mgr.updateAppWidget(id, views)
            }
        }

        private val nf = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }

        private fun colorOf(hex: String?): Int =
            runCatching { Color.parseColor(hex) }.getOrDefault(Color.GRAY)

        private fun draw(monthName: String, totals: Map<String, Double>, colors: Map<String, String>, unlabelled: Int): Bitmap {
            val w = 900
            val h = 450
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            p.color = 0xFF151B2B.toInt()
            c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 56f, 56f, p)

            // donut
            val cx = 215f
            val cy = h / 2f
            val r = 150f
            val sw = 42f
            val box = RectF(cx - r + sw / 2, cy - r + sw / 2, cx + r - sw / 2, cy + r - sw / 2)
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw
            val total = totals.values.sum()
            if (total <= 0) {
                p.color = 0xFF1E2640.toInt()
                c.drawArc(box, 0f, 360f, false, p)
            } else {
                var start = -90f
                totals.entries.sortedByDescending { it.value }.forEach { (cat, v) ->
                    val sweep = (v / total * 360f).toFloat()
                    p.color = colorOf(colors[cat])
                    c.drawArc(box, start + 1.5f, (sweep - 3f).coerceAtLeast(0.5f), false, p)
                    start += sweep
                }
            }
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.color = 0xFF94A3B8.toInt()
            p.textSize = 30f
            c.drawText(monthName, cx, cy - 22f, p)
            p.color = Color.WHITE
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = if (total >= 100000) 36f else 44f
            c.drawText("₹" + nf.format(total), cx, cy + 32f, p)

            // legend
            p.textAlign = Paint.Align.LEFT
            var y = 95f
            val x = 450f
            if (totals.isEmpty()) {
                p.typeface = Typeface.DEFAULT
                p.color = 0xFF94A3B8.toInt()
                p.textSize = 34f
                c.drawText("No spends labelled yet", x, cy, p)
            } else {
                totals.entries.sortedByDescending { it.value }.take(5).forEach { (cat, v) ->
                    p.color = colorOf(colors[cat])
                    c.drawCircle(x + 12f, y - 11f, 12f, p)
                    p.typeface = Typeface.DEFAULT
                    p.color = 0xFFE5E9F5.toInt()
                    p.textSize = 34f
                    c.drawText(cat, x + 38f, y, p)
                    p.typeface = Typeface.DEFAULT_BOLD
                    p.textAlign = Paint.Align.RIGHT
                    c.drawText("₹" + nf.format(v), w - 50f, y, p)
                    p.textAlign = Paint.Align.LEFT
                    y += 62f
                }
            }
            if (unlabelled > 0) {
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = 0xFFFBBF24.toInt()
                p.textSize = 30f
                c.drawText("🏷 $unlabelled unlabelled", x, h - 28f, p)
            }
            return bmp
        }
    }
}
