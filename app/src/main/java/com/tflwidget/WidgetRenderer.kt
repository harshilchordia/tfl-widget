package com.tflwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.*

object WidgetRenderer {
    private const val MAX_ROWS = 8
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.UK).apply { timeZone = TimeZone.getTimeZone("Europe/London") }

    private fun base(ctx: Context, widgetId: Int, title: String): RemoteViews {
        val views = RemoteViews(ctx.packageName, R.layout.widget_departures)
        views.setTextViewText(R.id.station_name, shortName(title))

        val refresh = Intent(ctx, DeparturesWidget::class.java).apply {
            action = DeparturesWidget.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val refreshPi = PendingIntent.getBroadcast(
            ctx, widgetId, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.refresh_button, refreshPi)

        views.setOnClickPendingIntent(R.id.rows, refreshPi)

        val config = Intent(ctx, ConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        views.setOnClickPendingIntent(
            R.id.station_name,
            PendingIntent.getActivity(ctx, widgetId, config, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )
        return views
    }

    private fun push(ctx: Context, widgetId: Int, views: RemoteViews) =
        AppWidgetManager.getInstance(ctx).updateAppWidget(widgetId, views)

    private fun message(ctx: Context, widgetId: Int, title: String, msg: String) {
        val v = base(ctx, widgetId, title)
        v.removeAllViews(R.id.rows)
        v.setViewVisibility(R.id.message, View.VISIBLE)
        v.setTextViewText(R.id.message, msg)
        push(ctx, widgetId, v)
    }

    fun showUnconfigured(ctx: Context, id: Int) = message(ctx, id, "TfL Departures", "Tap here to choose a station")
    fun showError(ctx: Context, id: Int, title: String, err: String) = message(ctx, id, title, "Couldn't load: $err")

    fun showLoading(ctx: Context, id: Int) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_departures)
        v.setTextViewText(R.id.updated, "Refreshing…")
        AppWidgetManager.getInstance(ctx).partiallyUpdateAppWidget(id, v)
    }

    fun showArrivals(ctx: Context, widgetId: Int, title: String, arrivals: List<Arrival>, filtered: Boolean = false) {
        if (arrivals.isEmpty()) {
            message(ctx, widgetId, title,
                if (filtered) "No live departures to your destination right now" else "No live departures right now")
            return
        }
        val v = base(ctx, widgetId, title)
        val size = size(ctx, widgetId)
        val showClock = Prefs.showClock(ctx, widgetId)
        v.setViewVisibility(R.id.message, View.GONE)
        v.removeAllViews(R.id.rows)
        arrivals.take(size.rows).forEach { a ->
            val row = RemoteViews(ctx.packageName, if (size.compact) R.layout.widget_row_compact else R.layout.widget_row)
            row.setInt(R.id.line_color, "setBackgroundColor", lineColor(a.lineId))
            row.setTextViewText(R.id.destination, a.destination)
            val mins = a.secondsToStation / 60
            val eta = when {
                mins == 0 -> "Due"
                size.narrow && showClock -> "${mins}m"
                else -> "$mins min"
            }
            val clock = timeFmt.format(Date(a.expectedEpochMs))
            if (size.compact) {
                // One line per departure: no line/platform, clock time next to minutes
                row.setTextViewText(R.id.eta, if (showClock) "$eta · $clock" else eta)
            } else {
                row.setTextViewText(R.id.details, listOf(a.lineName, a.platform).filter { it.isNotBlank() }.joinToString(" · "))
                row.setTextViewText(R.id.eta, eta)
                row.setTextViewText(R.id.clock_time, clock)
                row.setViewVisibility(R.id.clock_time, if (showClock) View.VISIBLE else View.GONE)
            }
            v.addView(R.id.rows, row)
        }
        v.setTextViewTextSize(R.id.station_name, android.util.TypedValue.COMPLEX_UNIT_SP, if (size.compact) 11f else 13f)
        val pad = (if (size.compact) 6 else 10) * ctx.resources.displayMetrics.density
        v.setViewPadding(R.id.root, pad.toInt(), pad.toInt(), pad.toInt(), pad.toInt())
        v.setTextViewText(R.id.updated, if (size.narrow) "" else "Updated " + timeFmt.format(Date()))
        push(ctx, widgetId, v)
    }

    /** "Oxford Circus Underground Station → Brixton Underground Station" → "Oxford Circus → Brixton" */
    fun shortName(name: String): String = name.replace(
        Regex(" (Underground|DLR|Rail|Overground|Elizabeth line|Tram Stop|Tram)? ?Station(?=$| →)| \\(.*?\\)", RegexOption.IGNORE_CASE), ""
    ).replace(Regex("\\s+"), " ").trim()

    private data class Size(val rows: Int, val compact: Boolean, val narrow: Boolean)

    /**
     * Works out how many departures fit in the widget's current size.
     * Portrait uses MIN_WIDTH x MAX_HEIGHT; that's the common case on phones.
     */
    private fun size(ctx: Context, widgetId: Int): Size {
        val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(widgetId)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
        val narrow = w < 180
        val available = h - HEADER_DP
        // Short widgets switch to single-line rows so more departures fit
        val compact = h < 130 || narrow
        val rowH = if (compact) COMPACT_ROW_DP else FULL_ROW_DP
        return Size((available / rowH).coerceIn(1, MAX_ROWS), compact, narrow)
    }

    private const val HEADER_DP = 32
    private const val FULL_ROW_DP = 38
    private const val COMPACT_ROW_DP = 24

    private fun lineColor(lineId: String): Int = Color.parseColor(
        when (lineId) {
            "bakerloo" -> "#B36305"
            "central" -> "#E32017"
            "circle" -> "#FFD300"
            "district" -> "#00782A"
            "hammersmith-city" -> "#F3A9BB"
            "jubilee" -> "#A0A5A9"
            "metropolitan" -> "#9B0056"
            "northern" -> "#000000"
            "piccadilly" -> "#003688"
            "victoria" -> "#0098D4"
            "waterloo-city" -> "#95CDBA"
            "dlr" -> "#00A4A7"
            "elizabeth" -> "#6950A1"
            "tram" -> "#84B817"
            "liberty", "lioness", "mildmay", "suffragette", "weaver", "windrush", "london-overground" -> "#EE7C0E"
            else -> "#DC241F" // buses & everything else: TfL red
        }
    )
}
