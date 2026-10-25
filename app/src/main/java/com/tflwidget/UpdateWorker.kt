package com.tflwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val auto = inputData.getBoolean(KEY_AUTO, false)
        val screenOn = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val periodic = !auto && single(inputData) == AppWidgetManager.INVALID_APPWIDGET_ID

        // The auto-refresh loop only runs while the screen is on. With the screen off it
        // stops completely (no more wake-ups); unlocking the phone restarts it.
        if (!screenOn && (auto || periodic)) return@withContext Result.success()
        if (auto) DeparturesWidget.scheduleAuto(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
        // 15-minute safety net: restart the loop if Android dropped it
        if (periodic) DeparturesWidget.scheduleAuto(ctx, ExistingWorkPolicy.KEEP)

        val single = single(inputData)
        val ids = if (single != AppWidgetManager.INVALID_APPWIDGET_ID) intArrayOf(single)
        else AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, DeparturesWidget::class.java))

        for (id in ids) {
            val stopId = Prefs.stopId(ctx, id)
            if (stopId == null) {
                WidgetRenderer.showUnconfigured(ctx, id)
                continue
            }
            val from = Prefs.stopName(ctx, id) ?: stopId
            val destId = Prefs.destId(ctx, id)
            val title = Prefs.destName(ctx, id)?.let { "$from → $it" } ?: from
            // Scheduled refreshes skip the network when the saved data is still good:
            // it's recent and the next train is a while away, so nothing would change.
            // (Taps, unlocks and settings changes always fetch.)
            if (auto && isFresh(ctx, id, title)) {
                WidgetRenderer.renderFromCache(ctx, id)
                continue
            }
            try {
                var arrivals = TflApi.arrivals(stopId)
                if (destId != null) {
                    val destIds = TflApi.stopIds(destId)
                    arrivals = arrivals.filter { TflApi.callsAt(it, destIds) }
                }
                arrivals = (arrivals + nationalRail(stopId, destId)).sortedBy { it.expectedEpochMs }
                Prefs.saveCache(ctx, id, Prefs.Cache(title, destId != null, System.currentTimeMillis(), arrivals))
                WidgetRenderer.renderFromCache(ctx, id)
            } catch (e: Exception) {
                // Keep showing the last departures (minus any that have left) rather than an error
                val cached = Prefs.loadCache(ctx, id)?.takeIf { it.title == title }
                if (cached == null || !WidgetRenderer.renderFromCache(ctx, id)) {
                    WidgetRenderer.showError(ctx, id, title, e.message ?: "Network error")
                }
            }
        }
        DeparturesWidget.scheduleTick(ctx)
        Result.success()
    }

    /**
     * National Rail trains from the same station. With a destination, Darwin filters
     * to trains calling there; a destination with no rail station means none apply.
     * Failures are ignored so TfL departures still show.
     */
    private fun nationalRail(stopId: String, destId: String?): List<Arrival> {
        if (!NationalRailApi.enabled) return emptyList()
        return runCatching {
            val from = NationalRailApi.crsCodes(stopId)
            val to = destId?.let { NationalRailApi.crsCodes(it) }
            if (from.isEmpty() || (to != null && to.isEmpty())) return emptyList()
            from.flatMap { crs ->
                if (to == null) NationalRailApi.departures(crs, null)
                else to.flatMap { NationalRailApi.departures(crs, it) }
            }.distinctBy { Triple(it.lineId, it.destination, it.expectedEpochMs) }
        }.onFailure { android.util.Log.w("TflWidget", "National Rail fetch failed", it) }
            .getOrDefault(emptyList())
    }

    private fun isFresh(ctx: Context, id: Int, title: String): Boolean {
        val cache = Prefs.loadCache(ctx, id)?.takeIf { it.title == title } ?: return false
        val now = System.currentTimeMillis()
        val next = cache.arrivals.firstOrNull { it.expectedEpochMs >= now } ?: return false
        return now - cache.fetchedAt < FRESH_MS && next.expectedEpochMs - now >= NEXT_TRAIN_MS
    }

    private fun single(d: Data) = d.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

    companion object {
        const val KEY_WIDGET_ID = "widget_id"
        const val KEY_AUTO = "auto"
        private const val FRESH_MS = 2 * 60_000L
        private const val NEXT_TRAIN_MS = 5 * 60_000L
    }
}
