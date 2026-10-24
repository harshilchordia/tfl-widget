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
        scheduleAuto(context, ExistingWorkPolicy.KEEP)
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
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(AUTO_WORK)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.tflwidget.REFRESH"
        private const val PERIODIC_WORK = "tfl_periodic"
        private const val AUTO_WORK = "tfl_auto"

        /**
         * Auto-refresh loop: a one-off job that runs after the user's chosen interval
         * and then schedules the next one. (PeriodicWork can't go below 15 minutes.)
         * Use REPLACE to restart with a new interval, KEEP to just make sure it's running,
         * APPEND_OR_REPLACE from inside the worker so it doesn't cancel itself.
         */
        fun scheduleAuto(context: Context, policy: ExistingWorkPolicy) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setInitialDelay(Prefs.refreshMinutes(context).toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf(UpdateWorker.KEY_AUTO to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(AUTO_WORK, policy, req)
        }

        fun refreshNow(context: Context, widgetId: Int) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setInputData(workDataOf(UpdateWorker.KEY_WIDGET_ID to widgetId))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("tfl_refresh_$widgetId", ExistingWorkPolicy.REPLACE, req)
        }

        /** Safety net: restarts the auto-refresh loop if the system ever drops it. */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
