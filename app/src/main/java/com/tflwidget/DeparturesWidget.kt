package com.tflwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.work.*
import java.util.concurrent.TimeUnit

class DeparturesWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { refreshNow(context, it) }
        schedulePeriodic(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                WidgetRenderer.showLoading(context, id)
                refreshNow(context, id)
            }
        }
    }

    /** Re-render when the user resizes the widget so the row count fits. */
    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: android.os.Bundle
    ) {
        refreshNow(context, widgetId)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { Prefs.delete(context, it) }
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    companion object {
        const val ACTION_REFRESH = "com.tflwidget.REFRESH"
        private const val PERIODIC_WORK = "tfl_periodic"

        fun refreshNow(context: Context, widgetId: Int) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setInputData(workDataOf(UpdateWorker.KEY_WIDGET_ID to widgetId))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("tfl_refresh_$widgetId", ExistingWorkPolicy.REPLACE, req)
        }

        /** Android limits background periodic work to every 15 minutes. */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
