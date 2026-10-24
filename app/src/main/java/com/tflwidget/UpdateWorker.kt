package com.tflwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tflwidget.Prefs.appKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val single = inputData.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
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
            try {
                var arrivals = TflApi.arrivals(stopId, ctx.appKey)
                if (destId != null) {
                    val destIds = TflApi.stopIds(destId, ctx.appKey)
                    arrivals = arrivals.filter { TflApi.callsAt(it, destIds, ctx.appKey) }
                }
                WidgetRenderer.showArrivals(ctx, id, title, arrivals, filtered = destId != null)
            } catch (e: Exception) {
                WidgetRenderer.showError(ctx, id, title, e.message ?: "Network error")
            }
        }
        Result.success()
    }

    companion object { const val KEY_WIDGET_ID = "widget_id" }
}
