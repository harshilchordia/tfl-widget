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
        .remove("clock_$widgetId").remove("cache_$widgetId")
        .remove("stop_$widgetId").remove("name_$widgetId")
        .remove("dest_$widgetId").remove("destname_$widgetId").apply()

    /** Last successful fetch for a widget, so it can be redrawn (and departed trains dropped) offline. */
    data class Cache(val title: String, val filtered: Boolean, val fetchedAt: Long, val arrivals: List<Arrival>)

    fun saveCache(c: Context, widgetId: Int, cache: Cache) {
        val arr = org.json.JSONArray()
        cache.arrivals.forEach { a ->
            arr.put(org.json.JSONObject()
                .put("lineId", a.lineId).put("lineName", a.lineName).put("destination", a.destination)
                .put("platform", a.platform).put("expected", a.expectedEpochMs))
        }
        val json = org.json.JSONObject()
            .put("title", cache.title).put("filtered", cache.filtered)
            .put("fetchedAt", cache.fetchedAt).put("arrivals", arr)
        sp(c).edit().putString("cache_$widgetId", json.toString()).apply()
    }

    fun loadCache(c: Context, widgetId: Int): Cache? = runCatching {
        val json = org.json.JSONObject(sp(c).getString("cache_$widgetId", null) ?: return null)
        val arr = json.getJSONArray("arrivals")
        Cache(
            title = json.getString("title"),
            filtered = json.optBoolean("filtered"),
            fetchedAt = json.getLong("fetchedAt"),
            arrivals = (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                val expected = a.getLong("expected")
                Arrival(a.getString("lineId"), a.getString("lineName"), a.getString("destination"),
                    a.getString("platform"), ((expected - System.currentTimeMillis()) / 1000).toInt(), expected)
            },
        )
    }.getOrNull()

    fun clearCache(c: Context, widgetId: Int) = sp(c).edit().remove("cache_$widgetId").apply()

    val REFRESH_OPTIONS = intArrayOf(1, 2, 5, 15)
    fun refreshMinutes(c: Context) = sp(c).getInt("refresh_minutes", 2)
    fun setRefreshMinutes(c: Context, m: Int) = sp(c).edit().putInt("refresh_minutes", m).apply()

}
