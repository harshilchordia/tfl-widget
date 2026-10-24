package com.tflwidget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Refreshes widgets as soon as the phone is woken/unlocked.
 *
 * Android doesn't allow SCREEN_ON / USER_PRESENT receivers in the manifest, so we
 * register one at runtime. It works whenever our process is alive (which the
 * auto-refresh loop keeps happening regularly); if Android has killed the process,
 * the normal auto-refresh schedule picks up within one interval instead.
 */
class TflWidgetApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, WakeReceiver(), filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private class WakeReceiver : BroadcastReceiver() {
        private var lastRefresh = 0L

        override fun onReceive(context: Context, intent: Intent) {
            // Screen-on and unlock usually arrive a few seconds apart: only refresh once
            val now = SystemClock.elapsedRealtime()
            if (now - lastRefresh < DEBOUNCE_MS) return
            val ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, DeparturesWidget::class.java))
            if (ids.isEmpty()) return
            lastRefresh = now
            ids.forEach { DeparturesWidget.refreshNow(context, it) }
        }
    }

    companion object { private const val DEBOUNCE_MS = 20_000L }
}
