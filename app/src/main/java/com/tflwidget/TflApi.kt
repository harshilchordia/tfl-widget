package com.tflwidget

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
    private const val MODES = "tube,dlr,overground,elizabeth-line,tram,bus,national-rail"

    private fun get(path: String, appKey: String?): String {
        val sep = if (path.contains('?')) '&' else '?'
        val url = BASE + path + if (!appKey.isNullOrBlank()) "${sep}app_key=${enc(appKey)}" else ""
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

    fun searchStations(query: String, appKey: String?): List<Station> {
        val json = JSONObject(get("/StopPoint/Search/${enc(query)}?modes=$MODES&maxResults=25", appKey))
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
    fun arrivals(stopId: String, appKey: String?): List<Arrival> {
        var result = if (stopId.startsWith("HUB")) emptyList() else fetchArrivals(stopId, appKey)
        if (result.isEmpty()) {
            result = childIds(stopId, appKey).take(10).flatMap {
                runCatching { fetchArrivals(it, appKey) }.getOrDefault(emptyList())
            }
        }
        return result.distinctBy { Triple(it.lineId, it.destination, it.secondsToStation) }
            .sortedBy { it.secondsToStation }
    }

    private fun fetchArrivals(id: String, appKey: String?): List<Arrival> {
        val arr = JSONArray(get("/StopPoint/${enc(id)}/Arrivals", appKey))
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

    private fun childIds(id: String, appKey: String?): List<String> {
        val sp = JSONObject(get("/StopPoint/${enc(id)}", appKey))
        val children = sp.optJSONArray("children") ?: return emptyList()
        return (0 until children.length()).map { children.getJSONObject(it).getString("id") }
    }

    // ---- Destination filtering ----

    private val routeCache = mutableMapOf<String, List<List<String>>>()
    private val stopIdCache = mutableMapOf<String, Set<String>>()

    /** Ordered stop lists for every branch/direction of a line. */
    private fun routeSequences(lineId: String, appKey: String?): List<List<String>> =
        routeCache[lineId] ?: run {
            val json = JSONObject(get("/Line/${enc(lineId)}/Route/Sequence/all?excludeCrowding=true", appKey))
            val routes = json.optJSONArray("orderedLineRoutes") ?: JSONArray()
            val list = (0 until routes.length()).map { i ->
                val ids = routes.getJSONObject(i).optJSONArray("naptanIds") ?: JSONArray()
                (0 until ids.length()).map { ids.getString(it) }
            }
            list.also { routeCache[lineId] = it }
        }

    /** A station ID plus its child stops (hubs contain tube/rail/DLR stations). */
    fun stopIds(id: String, appKey: String?): Set<String> =
        stopIdCache[id] ?: (setOf(id) + runCatching { childIds(id, appKey) }.getOrDefault(emptyList()))
            .also { stopIdCache[id] = it }

    /**
     * True if the train will call at any of [destIds] after leaving its current stop.
     * If we can't tell (missing data / API failure), the departure is kept.
     */
    fun callsAt(a: Arrival, destIds: Set<String>, appKey: String?): Boolean {
        if (a.destinationNaptanId in destIds) return true
        val routes = runCatching { routeSequences(a.lineId, appKey) }.getOrElse { return true }
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
