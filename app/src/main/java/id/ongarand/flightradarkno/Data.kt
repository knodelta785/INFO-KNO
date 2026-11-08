package id.ongarand.flightradarkno

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class Flight(
    val number: String, val airline: String, val city: String, val iata: String,
    val country: String, val arrival: Boolean, val international: Boolean,
    val sched: Long, val actual: Long, val status: String
) {
    val delayMin: Int get() = if (actual > 0 && sched > 0) ((actual - sched) / 60).toInt() else 0
    val cancelled: Boolean get() = status.contains("cancel", true)
    val delayed: Boolean get() = delayMin >= 10 || status.contains("delay", true)
    val alert: Boolean get() = delayed || cancelled
}

object Fmt {
    private val tz = TimeZone.getTimeZone("Asia/Jakarta")
    fun time(epoch: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }.format(Date(epoch * 1000))
    fun minuteOfDay(epoch: Long): Int {
        val c = Calendar.getInstance(tz).apply { timeInMillis = epoch * 1000 }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }
}

/**
 * Sumber data: Flightradar24 (papan jadwal bandara KNO).
 * Endpoint ini TIDAK resmi/publik -- lihat catatan di README.
 */
object Fr24 {
    private fun s(o: JSONObject?, k: String) = if (o == null || o.isNull(k)) "" else o.optString(k)

    fun fetch(arrival: Boolean): List<Flight> {
        val mode = if (arrival) "arrivals" else "departures"
        val out = mutableListOf<Flight>()
        for (page in 1..3) {
            val url = "https://api.flightradar24.com/common/v1/airport.json?code=kno" +
                "&plugin%5B%5D=schedule&plugin-setting%5Bschedule%5D%5Bmode%5D=$mode" +
                "&plugin-setting%5Bschedule%5D%5Btimestamp%5D=${System.currentTimeMillis() / 1000}" +
                "&page=$page&limit=100"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) FlightRadarKNO/1.0")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().readText()
            val sched = JSONObject(body).optJSONObject("result")?.optJSONObject("response")
                ?.optJSONObject("airport")?.optJSONObject("pluginData")
                ?.optJSONObject("schedule")?.optJSONObject(mode) ?: break
            val data: JSONArray = sched.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (i in 0 until data.length()) parse(data.getJSONObject(i), arrival)?.let { out.add(it) }
            val totalPages = sched.optJSONObject("page")?.optInt("total", 1) ?: 1
            if (page >= totalPages) break
        }
        return out.distinctBy { it.number + it.sched }
    }

    private fun parse(item: JSONObject, arrival: Boolean): Flight? {
        val f = item.optJSONObject("flight") ?: return null
        val number = s(f.optJSONObject("identification")?.optJSONObject("number"), "default")
        if (number.isEmpty()) return null
        val ap = f.optJSONObject("airport")?.optJSONObject(if (arrival) "origin" else "destination")
        val pos = ap?.optJSONObject("position")?.optJSONObject("country")
        val cc = s(pos, "code"); val cn = s(pos, "name")
        val intl = if (cc.isNotEmpty()) !cc.equals("ID", true)
        else cn.isNotEmpty() && !cn.equals("Indonesia", true)
        val t = f.optJSONObject("time")
        val key = if (arrival) "arrival" else "departure"
        val sch = t?.optJSONObject("scheduled")?.optLong(key, 0) ?: 0L
        val est = t?.optJSONObject("estimated")?.optLong(key, 0) ?: 0L
        val real = t?.optJSONObject("real")?.optLong(key, 0) ?: 0L
        return Flight(
            number = number,
            airline = s(f.optJSONObject("airline"), "name"),
            city = s(ap, "name").replace(" Airport", "").replace(" International", ""),
            iata = s(ap?.optJSONObject("code"), "iata"),
            country = cn, arrival = arrival, international = intl,
            sched = sch, actual = if (real > 0) real else est,
            status = s(f.optJSONObject("status"), "text")
        )
    }
}

data class Rule(val id: Long, val flight: String, val start: Int, val end: Int, val dir: String, val intl: Boolean) {
    val isFlight get() = flight.isNotEmpty()
    fun matches(f: Flight): Boolean {
        if (isFlight) return f.number.replace(" ", "").equals(flight, true)
        if ((dir == "ARR" && !f.arrival) || (dir == "DEP" && f.arrival)) return false
        if (intl && !f.international) return false
        val m = Fmt.minuteOfDay(f.sched)
        return if (start <= end) m in start..end else (m >= start || m <= end)
    }
}

object Store {
    fun p(c: Context) = c.getSharedPreferences("kno", 0)
    fun rules(c: Context): List<Rule> {
        val a = JSONArray(p(c).getString("rules", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Rule(o.getLong("id"), o.getString("flight"), o.getInt("start"), o.getInt("end"), o.getString("dir"), o.getBoolean("intl"))
        }
    }
    fun save(c: Context, list: List<Rule>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("flight", it.flight).put("start", it.start)
                .put("end", it.end).put("dir", it.dir).put("intl", it.intl))
        }
        p(c).edit().putString("rules", a.toString()).apply()
    }
}
