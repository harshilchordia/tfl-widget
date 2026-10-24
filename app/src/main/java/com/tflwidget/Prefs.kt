package com.tflwidget

import android.content.Context

object Prefs {
    private const val FILE = "tfl_widget"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun saveStation(c: Context, widgetId: Int, id: String, name: String) =
        sp(c).edit().putString("stop_$widgetId", id).putString("name_$widgetId", name).apply()

    fun stopId(c: Context, widgetId: Int) = sp(c).getString("stop_$widgetId", null)
    fun stopName(c: Context, widgetId: Int) = sp(c).getString("name_$widgetId", null)

    fun saveDestination(c: Context, widgetId: Int, id: String?, name: String?) =
        sp(c).edit().putString("dest_$widgetId", id).putString("destname_$widgetId", name).apply()

    fun destId(c: Context, widgetId: Int) = sp(c).getString("dest_$widgetId", null)
    fun destName(c: Context, widgetId: Int) = sp(c).getString("destname_$widgetId", null)

    fun showClock(c: Context, widgetId: Int) = sp(c).getBoolean("clock_$widgetId", true)
    fun setShowClock(c: Context, widgetId: Int, show: Boolean) =
        sp(c).edit().putBoolean("clock_$widgetId", show).apply()

    fun delete(c: Context, widgetId: Int) = sp(c).edit()
        .remove("clock_$widgetId")
        .remove("stop_$widgetId").remove("name_$widgetId")
        .remove("dest_$widgetId").remove("destname_$widgetId").apply()

    val REFRESH_OPTIONS = intArrayOf(1, 2, 5, 15)
    fun refreshMinutes(c: Context) = sp(c).getInt("refresh_minutes", 1)
    fun setRefreshMinutes(c: Context, m: Int) = sp(c).edit().putInt("refresh_minutes", m).apply()

    var Context.appKey: String?
        get() = sp(this).getString("app_key", null)
        set(v) { sp(this).edit().putString("app_key", v).apply() }
}
