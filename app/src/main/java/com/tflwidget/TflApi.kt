package com.tflwidget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Station(val id: String, val name: String, val modes: List<String>)

data class Arrival(
    val lineId: String,
    val lineName: String,
    val destination: String,
    val platform: String,
    val secondsToStation: Int,
    val expectedEpochMs: Long,
    val naptanId: String = "",
    val destinationNaptanId: String = "",
)

/** Minimal client for the TfL Unified API (https://api.tfl.gov.uk). */
object TflApi {
    private const val BASE = "https://api.tfl.gov.uk"

    /**
     * Station layouts and line stop lists rarely change, so they're kept on disk for a
     * week. Otherwise they'd be downloaded again whenever Android closes the app.
     */
    private var store: SharedPreferences? = null
    private const val STATIC_TTL_MS = 7L * 24 * 60 * 60 * 1000

    fun init(context: Context) {
        store = context.getSharedPreferences("tfl_static", Context.MODE_PRIVATE)
    }

    private fun readStatic(key: String): String? {
        val sp = store ?: return null
        val savedAt = sp.getLong("${key}_at", 0)
        if (System.currentTimeMillis() - savedAt > STATIC_TTL_MS) return null
        return sp.getString(key, null)
    }

    private fun writeStatic(key: String, value: String) {
        store?.edit()?.putString(key, value)?.putLong("${key}_at", System.currentTimeMillis())?.apply()
    }
    private const val MODES = "tube,dlr,overground,elizabeth-line,tram,bus,national-rail"

    private fun get(path: String): String {
        val url = BASE + path
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("TfL API error $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun searchStations(query: String): List<Station> {
        val json = JSONObject(get("/StopPoint/Search/${enc(query)}?modes=$MODES&maxResults=25"))
        val matches = json.optJSONArray("matches") ?: return emptyList()
        return (0 until matches.length()).map { i ->
            val m = matches.getJSONObject(i)
            val modes = m.optJSONArray("modes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            Station(m.getString("id"), m.optString("name"), modes)
        }
    }

    /**
     * Gets arrivals for a stop. Hub IDs (e.g. HUBKGX) and stop areas often return
     * nothing directly, so fall back to querying their child stops.
     */
    fun arrivals(stopId: String): List<Arrival> {
        var result = if (stopId.startsWith("HUB")) emptyList() else fetchArrivals(stopId)
        if (result.isEmpty()) {
            result = childIds(stopId).take(10).flatMap {
                runCatching { fetchArrivals(it) }.getOrDefault(emptyList())
            }
        }
        return result.distinctBy { Triple(it.lineId, it.destination, it.secondsToStation) }
            .sortedBy { it.secondsToStation }
    }

    private fun fetchArrivals(id: String): List<Arrival> {
        val arr = JSONArray(get("/StopPoint/${enc(id)}/Arrivals"))
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            Arrival(
                lineId = a.optString("lineId"),
                lineName = a.optString("lineName"),
                destination = a.optString("destinationName").ifBlank { a.optString("towards") }
                    .removeSuffix(" Underground Station")
                    .removeSuffix(" DLR Station")
                    .removeSuffix(" Rail Station"),
                platform = a.optString("platformName"),
                secondsToStation = a.optInt("timeToStation"),
                expectedEpochMs = runCatching { java.time.Instant.parse(a.getString("expectedArrival")).toEpochMilli() }
                    .getOrElse { System.currentTimeMillis() + a.optInt("timeToStation") * 1000L },
                naptanId = a.optString("naptanId"),
                destinationNaptanId = a.optString("destinationNaptanId"),
            )
        }
    }

    private val childCache = mutableMapOf<String, List<String>>()

    /** Child stops of a station (memory, then disk, then network). */
    private fun childIds(id: String): List<String> {
        childCache[id]?.let { return it }
        val key = "children_$id"
        val saved = readStatic(key)?.let { JSONArray(it) }
        val list = if (saved != null) {
            (0 until saved.length()).map { saved.getString(it) }
        } else {
            val sp = JSONObject(get("/StopPoint/${enc(id)}"))
            val children = sp.optJSONArray("children") ?: JSONArray()
            (0 until children.length()).map { children.getJSONObject(it).getString("id") }
                .also { writeStatic(key, JSONArray(it).toString()) }
        }
        return list.also { childCache[id] = it }
    }

    // ---- Destination filtering ----

    private val routeCache = mutableMapOf<String, List<List<String>>>()
    private val stopIdCache = mutableMapOf<String, Set<String>>()

    /** Ordered stop lists for every branch/direction of a line. */
    private fun routeSequences(lineId: String): List<List<String>> =
        routeCache[lineId] ?: run {
            val key = "route_$lineId"
            val saved = readStatic(key)?.let { JSONArray(it) }
            val list = if (saved != null) {
                (0 until saved.length()).map { i ->
                    val r = saved.getJSONArray(i)
                    (0 until r.length()).map { r.getString(it) }
                }
            } else {
                val json = JSONObject(get("/Line/${enc(lineId)}/Route/Sequence/all?excludeCrowding=true"))
                val routes = json.optJSONArray("orderedLineRoutes") ?: JSONArray()
                (0 until routes.length()).map { i ->
                    val ids = routes.getJSONObject(i).optJSONArray("naptanIds") ?: JSONArray()
                    (0 until ids.length()).map { ids.getString(it) }
                }.also { l -> writeStatic(key, JSONArray(l.map { JSONArray(it) }).toString()) }
            }
            list.also { routeCache[lineId] = it }
        }

    /** A station ID plus its child stops (hubs contain tube/rail/DLR stations). */
    fun stopIds(id: String): Set<String> =
        stopIdCache[id] ?: (setOf(id) + runCatching { childIds(id) }.getOrDefault(emptyList()))
            .also { stopIdCache[id] = it }

    /**
     * True if the train will call at any of [destIds] after leaving its current stop.
     * If we can't tell (missing data / API failure), the departure is kept.
     */
    fun callsAt(a: Arrival, destIds: Set<String>): Boolean {
        if (a.destinationNaptanId in destIds) return true
        val routes = runCatching { routeSequences(a.lineId) }.getOrElse { return true }
        if (routes.isEmpty() || a.naptanId.isBlank()) return true
        var originKnown = false
        for (r in routes) {
            val from = r.indexOf(a.naptanId)
            if (from < 0) continue
            originKnown = true
            val to = r.indexOfFirst { it in destIds }
            if (to <= from) continue
            val end = if (a.destinationNaptanId.isBlank()) r.lastIndex else r.indexOf(a.destinationNaptanId)
            if (end >= to) return true
        }
        return !originKnown
    }
}
