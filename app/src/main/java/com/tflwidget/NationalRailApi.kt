package com.tflwidget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Live National Rail departures (Greater Anglia, c2c, Thameslink, ...) from the
 * Rail Data Marketplace "Live Departure Board" API (National Rail's Darwin data).
 * TfL's API knows these operators exist but has no live departures for them.
 *
 * The API key is set at build time (see app/build.gradle.kts). Without one,
 * National Rail departures are simply skipped.
 */
object NationalRailApi {
    private const val BASE =
        "https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS/api/20220120/GetDepBoardWithDetails"
    private val LONDON = ZoneId.of("Europe/London")

    /** Elizabeth line and Overground trains also appear in Darwin, but TfL's data covers them better. */
    private val TFL_OPERATORS = setOf("XR", "LO")

    val enabled get() = BuildConfig.RDM_API_KEY.isNotBlank()

    private var tiplocToCrs: Map<String, String> = emptyMap()

    fun init(context: Context) {
        tiplocToCrs = runCatching {
            val json = JSONObject(context.assets.open("tiploc_crs.json").bufferedReader().use { it.readText() })
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    /**
     * National Rail station codes for a TfL stop: a rail station (910G<TIPLOC>) maps
     * directly; a hub (e.g. HUBSRA) uses its rail station children. Tube/bus stops have none.
     */
    fun crsCodes(stopId: String): List<String> {
        val railIds = when {
            stopId.startsWith("910G") -> listOf(stopId)
            stopId.startsWith("HUB") -> TflApi.stopIds(stopId).filter { it.startsWith("910G") }
            else -> emptyList()
        }
        return railIds.mapNotNull { tiplocToCrs[it.removePrefix("910G")] }.distinct()
    }

    /**
     * Departures from [crs], optionally only trains that call at [toCrs].
     * Results use the same [Arrival] model as TfL so the widget treats them the same.
     */
    fun departures(crs: String, toCrs: String?): List<Arrival> {
        val query = buildString {
            append("$BASE/$crs?numRows=20&timeWindow=120")
            if (toCrs != null) append("&filterCrs=$toCrs&filterType=to")
        }
        val conn = URL(query).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("x-apikey", BuildConfig.RDM_API_KEY)
        conn.setRequestProperty("Accept", "application/json")
        val body = try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("National Rail API error $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }

        val services = JSONObject(body).optJSONArray("trainServices") ?: return emptyList()
        val now = ZonedDateTime.now(LONDON)
        return (0 until services.length()).mapNotNull { i ->
            val s = services.getJSONObject(i)
            val opCode = s.optString("operatorCode")
            if (opCode in TFL_OPERATORS) return@mapNotNull null

            val std = parseTime(s.optString("std"), now) ?: return@mapNotNull null
            val etd = s.optString("etd") // "On time", "14:35", "Delayed" or "Cancelled"
            val cancelled = s.optBoolean("isCancelled") || etd.equals("Cancelled", ignoreCase = true)
            val expected = parseTime(etd, now) ?: std
            val status = when {
                cancelled -> "Cancelled"
                etd.equals("Delayed", ignoreCase = true) -> "Delayed"
                else -> ""
            }
            val destination = s.optJSONArray("destination")?.let { d ->
                (0 until d.length()).joinToString(" & ") { d.getJSONObject(it).optString("locationName") }
            }.orEmpty()

            Arrival(
                lineId = "nr-$opCode",
                lineName = s.optString("operator"),
                destination = destination,
                platform = s.optString("platform").takeIf { it.isNotBlank() }?.let { "Platform $it" }.orEmpty(),
                secondsToStation = ((expected.toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000).toInt(),
                expectedEpochMs = expected.toInstant().toEpochMilli(),
                status = status,
            )
        }
    }

    /** "HH:mm" in UK time, placed on the right day around midnight. */
    private fun parseTime(hhmm: String, now: ZonedDateTime): ZonedDateTime? {
        val t = runCatching { LocalTime.parse(hhmm) }.getOrNull() ?: return null
        var dt = ZonedDateTime.of(LocalDate.now(LONDON), t, LONDON)
        if (dt.isBefore(now.minusHours(6))) dt = dt.plusDays(1)   // 00:10 seen at 23:50
        if (dt.isAfter(now.plusHours(18))) dt = dt.minusDays(1)   // 23:50 seen at 00:10
        return dt
    }
}
