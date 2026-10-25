package com.tflwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import androidx.core.widget.RemoteViewsCompat
import java.text.SimpleDateFormat
import java.util.*

object WidgetRenderer {
    private const val MAX_ROWS = 40
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

        // List rows can't have their own click handlers; they fill in this template instead.
        // (Must be mutable so the launcher can add the fill-in extras.)
        val templatePi = PendingIntent.getBroadcast(
            ctx, widgetId + 2_000_000, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.rows, templatePi)

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
        setRows(ctx, widgetId, v, emptyList())
        v.setViewVisibility(R.id.rows, View.GONE)
        v.setViewVisibility(R.id.message, View.VISIBLE)
        v.setTextViewText(R.id.message, msg)
        push(ctx, widgetId, v)
    }

    fun showUnconfigured(ctx: Context, id: Int) = message(ctx, id, "TfL Departures", "Tap here to choose a station")
    fun showError(ctx: Context, id: Int, title: String, err: String) = message(ctx, id, title, "Couldn't load: $err")

    fun showLoading(ctx: Context, id: Int) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_departures)
        v.setTextViewText(R.id.updated, "…")
        AppWidgetManager.getInstance(ctx).partiallyUpdateAppWidget(id, v)
    }

    /**
     * Draws the widget from its saved departures. Trains whose departure time has
     * passed are dropped and minutes are worked out from the current time, so this
     * stays correct between network refreshes. Returns false if there's nothing saved.
     */
    fun renderFromCache(ctx: Context, widgetId: Int): Boolean {
        val cache = Prefs.loadCache(ctx, widgetId) ?: return false
        val now = System.currentTimeMillis()
        val upcoming = cache.arrivals.filter { it.expectedEpochMs >= now }
        val updated = timeFmt.format(Date(cache.fetchedAt))

        if (upcoming.isEmpty()) {
            message(ctx, widgetId, cache.title,
                if (cache.filtered) "No live departures to your destination right now" else "No live departures right now")
            return true
        }
        val v = base(ctx, widgetId, cache.title)
        val showClock = Prefs.showClock(ctx, widgetId)
        v.setViewVisibility(R.id.message, View.GONE)
        v.setViewVisibility(R.id.rows, View.VISIBLE)
        // Scrollable list: all departures, the widget size just decides how many are visible at once
        setRows(ctx, widgetId, v, upcoming.take(MAX_ROWS).map { a ->
            RemoteViews(ctx.packageName, R.layout.widget_row).apply {
                setInt(R.id.line_color, "setBackgroundColor", lineColor(a.lineId))
                setTextViewText(R.id.destination, a.destination)
                setTextViewText(R.id.details, listOf(a.lineName, a.platform).filter { it.isNotBlank() }.joinToString(" · "))
                val mins = ((a.expectedEpochMs - now) / 60_000).toInt()
                setTextViewText(R.id.eta, when {
                    a.status == "Cancelled" -> "Cancelled"
                    mins == 0 -> "Due"
                    else -> "$mins min"
                })
                // Cancelled in red, delayed in orange, otherwise the usual yellow
                setTextColor(R.id.eta, Color.parseColor(when (a.status) {
                    "Cancelled" -> "#FF6B6B"
                    "Delayed" -> "#FFA94D"
                    else -> "#FFD300"
                }))
                setTextViewText(R.id.clock_time, timeFmt.format(Date(a.expectedEpochMs)))
                setViewVisibility(R.id.clock_time, if (showClock) View.VISIBLE else View.GONE)
                setOnClickFillInIntent(R.id.row_root, Intent()) // tap a row = refresh
            }
        })
        // Time of the last successful fetch (not this redraw), so you can tell how fresh it is
        v.setTextViewText(R.id.updated, updated)
        push(ctx, widgetId, v)
        return true
    }

    /** "Oxford Circus Underground Station → Brixton Underground Station" → "Oxford Circus → Brixton" */
    fun shortName(name: String): String = name.replace(
        Regex(" (Underground|DLR|Rail|Overground|Elizabeth line|Tram Stop|Tram)? ?Station(?=$| →)| \\(.*?\\)", RegexOption.IGNORE_CASE), ""
    ).replace(Regex("\\s+"), " ").trim()

    private fun setRows(ctx: Context, widgetId: Int, v: RemoteViews, rows: List<RemoteViews>) {
        val items = RemoteViewsCompat.RemoteCollectionItems.Builder()
            .setViewTypeCount(1)
            .apply { rows.forEachIndexed { i, r -> addItem(i.toLong(), r) } }
            .build()
        RemoteViewsCompat.setRemoteAdapter(ctx, v, widgetId, R.id.rows, items)
    }

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
            // National Rail operators (lineId = "nr-" + Darwin operator code)
            "nr-LE" -> "#D70428" // Greater Anglia
            "nr-CC" -> "#B7007C" // c2c
            "nr-TL" -> "#E9438D" // Thameslink
            "nr-GN" -> "#00A6E6" // Great Northern
            "nr-SE" -> "#00AFE8" // Southeastern
            "nr-SN" -> "#8CC63E" // Southern
            "nr-SW" -> "#24398C" // South Western Railway
            "nr-GW" -> "#0A493E" // GWR
            "nr-CH" -> "#00BFFF" // Chiltern
            "nr-LM" -> "#FF8300" // West Midlands Trains
            "nr-VT" -> "#004354" // Avanti West Coast
            "nr-GR" -> "#CE0E2D" // LNER
            "nr-EM" -> "#4E2E58" // East Midlands Railway
            "nr-HX" -> "#532E63" // Heathrow Express
            "nr-GX" -> "#E2001A" // Gatwick Express
            "liberty", "lioness", "mildmay", "suffragette", "weaver", "windrush", "london-overground" -> "#EE7C0E"
            else -> "#DC241F" // buses & everything else: TfL red
        }
    )
}
