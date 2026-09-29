package com.skob.raspisanie

import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

data class PeriodEntry(
    val period: Int,
    val subject: String,
    val online: Boolean,
    val start: LocalTime,
    val end: LocalTime
)

data class Reminder(
    val hour: Int,
    val minute: Int,
    val daysAhead: Int
)

data class DayConfig(
    val reminders: List<Reminder>,
    val travelMinutes: Int,
    val prepMinutes: Int,
    val homeLat: Double,
    val homeLon: Double,
    val exemptSubjects: Set<String>,
    val itemsBySubject: Map<String, List<String>>,
    val alwaysBring: List<String>,
    val siteFacultyId: String,
    val siteGroupId: String
)

object ScheduleEngine {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun loadConfig(context: android.content.Context): DayConfig {
        val text = context.assets.open("config.json").bufferedReader().use { it.readText() }
        val json = JSONObject(text)

        val remindersJson = json.getJSONArray("reminders")
        val reminders = mutableListOf<Reminder>()
        for (i in 0 until remindersJson.length()) {
            val obj = remindersJson.getJSONObject(i)
            reminders.add(
                Reminder(
                    hour = obj.getInt("hour"),
                    minute = obj.getInt("minute"),
                    daysAhead = obj.getInt("days_ahead")
                )
            )
        }

        val exemptJson = json.optJSONArray("exempt_subjects")
        val exemptSubjects = mutableSetOf<String>()
        if (exemptJson != null) {
            for (i in 0 until exemptJson.length()) exemptSubjects.add(exemptJson.getString(i))
        }

        val alwaysBringJson = json.optJSONArray("always_bring")
        val alwaysBring = mutableListOf<String>()
        if (alwaysBringJson != null) {
            for (i in 0 until alwaysBringJson.length()) alwaysBring.add(alwaysBringJson.getString(i))
        }

        val itemsJson = json.optJSONObject("items_by_subject")
        val items = mutableMapOf<String, List<String>>()
        if (itemsJson != null) {
            val keys = itemsJson.keys()
            while (keys.hasNext()) {
                val subject = keys.next()
                val arr = itemsJson.getJSONArray(subject)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                items[subject] = list
            }
        }

        return DayConfig(
            reminders = reminders,
            travelMinutes = json.optInt("travel_minutes", 20),
            prepMinutes = json.optInt("prep_minutes", 15),
            homeLat = json.optDouble("home_lat", 0.0),
            homeLon = json.optDouble("home_lon", 0.0),
            exemptSubjects = exemptSubjects,
            itemsBySubject = items,
            alwaysBring = alwaysBring,
            siteFacultyId = json.getString("site_faculty_id"),
            siteGroupId = json.getString("site_group_id")
        )
    }

    // Забирает расписание на конкретный день напрямую с сайта asu.ru
    private fun fetchDaySchedule(config: DayConfig, date: LocalDate): List<PeriodEntry> {
        val dateParam = "%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)
        val url = "https://www.asu.ru/timetable/students/${config.siteFacultyId}/${config.siteGroupId}/?date=$dateParam"

        val session = Jsoup.newSession()
            .userAgent(USER_AGENT)
            .timeout(15000)
            .header("Accept-Language", "ru-RU,ru;q=0.9")

        // Шаг 1: обычная загрузка страницы — там же лежит текущий X-CS-ID
        val initialResponse = session.newRequest(url)
            .header("Referer", "https://www.asu.ru/timetable/")
            .execute()
        val initialBody = initialResponse.body()
        val csId = Regex("X-CS-ID[\"']\\s*,\\s*[\"']([a-f0-9]{10,})[\"']")
            .find(initialBody)?.groupValues?.get(1)

        var secondStatus: Int? = null
        var doc = initialResponse.parse()

        // Шаг 2: повторный запрос с этим заголовком — так сайт отдаёт настоящую таблицу пар
        if (csId != null) {
            val secondResponse = session.newRequest(url)
                .header("X-CS-ID", csId)
                .header("Referer", url)
                .header("X-Requested-With", "XMLHttpRequest")
                .execute()
            secondStatus = secondResponse.statusCode()
            doc = secondResponse.parse()
        }

        val tableRoot = doc.selectFirst("div.schedule_table")
        if (tableRoot == null) {
            throw Exception(
                "не нашли таблицу; http1=${initialResponse.statusCode()} " +
                    "csId=${csId != null} http2=$secondStatus длина_ответа=${doc.html().length}"
            )
        }

        val rows = tableRoot.select("div.schedule_table-body-row")
            .filterNot { it.hasClass("schedule_table-body-row__dropdown") }

        return rows.mapNotNull { row ->
            val period = row.selectFirst("div[data-type=num]")?.text()?.trim()?.toIntOrNull()
                ?: return@mapNotNull null
            val timeText = row.selectFirst("div[data-type=time]")?.text()?.trim() ?: return@mapNotNull null
            val parts = timeText.split("-").map { it.trim() }
            if (parts.size != 2) return@mapNotNull null
            val start = LocalTime.parse(parts[0])
            val end = LocalTime.parse(parts[1])

            val subjectCell = row.selectFirst("div[data-type=subject]")
            val badge = subjectCell?.selectFirst(".schedule_table-badge")?.text()?.trim()
            val subtext = subjectCell?.selectFirst(".schedule_table-subtext")?.text()?.trim()
            var subject = subjectCell?.text()?.trim() ?: ""
            if (!badge.isNullOrEmpty()) subject = subject.removePrefix(badge).trim()
            if (!subtext.isNullOrEmpty()) subject = subject.removeSuffix(subtext).trim()
            val online = subtext?.contains("дистанц", ignoreCase = true) == true

            PeriodEntry(period, subject, online, start, end)
        }.sortedBy { it.period }
    }

    fun buildMessage(config: DayConfig, daysAhead: Int): String {
        val targetDate = LocalDate.now().plusDays(daysAhead.toLong())
        val dayName = targetDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))
            .replaceFirstChar { it.uppercase() }

        val entries = try {
            fetchDaySchedule(config, targetDate)
        } catch (e: Exception) {
            return "$dayName, $targetDate — не удалось загрузить расписание с сайта (${e.message})"
        }

        if (entries.isEmpty()) {
            return "$dayName, $targetDate — пар нет."
        }

        val entryByPeriod = entries.associateBy { it.period }
        val minPeriod = entries.minOf { it.period }
        val maxPeriod = entries.maxOf { it.period }

        val firstInPerson = entries.firstOrNull {
            it.subject !in config.exemptSubjects && !it.online
        }

        val sb = StringBuilder()
        val dateShort = "%02d.%02d".format(targetDate.dayOfMonth, targetDate.monthValue)
        sb.append("$dayName, $dateShort\n")

        var period = minPeriod
        while (period <= maxPeriod) {
            val entry = entryByPeriod[period]
            if (entry == null) {
                var end = period
                while (end + 1 <= maxPeriod && entryByPeriod[end + 1] == null) end++
                sb.append(
                    if (end == period) "$period: окно\n"
                    else "$period-$end: окно\n"
                )
                period = end + 1
                continue
            }
            val suffix = when {
                entry.subject in config.exemptSubjects -> " (осв.)"
                entry.online -> " (онлайн)"
                else -> ""
            }
            sb.append("${entry.period}) ${entry.start}–${entry.end} ${entry.subject}$suffix\n")
            period++
        }

        if (firstInPerson != null) {
            val departureTime = firstInPerson.start
                .minusMinutes(config.prepMinutes.toLong())
                .minusMinutes(config.travelMinutes.toLong())
            sb.append("Выход: $departureTime\n")

            try {
                val weather = fetchWeather(config.homeLat, config.homeLon, targetDate, departureTime)
                sb.append("Погода: ${weather.first}°C → ${weather.second}°C\n")
            } catch (e: Throwable) {
                sb.append("Погода: ошибка — ${e.javaClass.simpleName}: ${e.message}\n")
            }
        } else {
            sb.append("Очных пар нет\n")
        }

        try {
            val items = (
                entries
                    .filter { it.subject !in config.exemptSubjects }
                    .flatMap { config.itemsBySubject[it.subject] ?: emptyList() } + config.alwaysBring
                ).distinct()
            if (items.isNotEmpty()) {
                sb.append("Взять: ${items.joinToString(", ")}")
            }
        } catch (e: Throwable) {
            sb.append("Ошибка в списке вещей: ${e.javaClass.simpleName}: ${e.message}")
        }

        return sb.toString().trim()
    }

    private fun fetchWeather(
        lat: Double,
        lon: Double,
        date: LocalDate,
        departureTime: LocalTime
    ): Pair<Int, Int> {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&hourly=temperature_2m&daily=temperature_2m_max&forecast_days=3&timezone=auto"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(response)

        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val targetHourString = "%04d-%02d-%02dT%02d:00".format(
            date.year, date.monthValue, date.dayOfMonth, departureTime.hour
        )
        var departureTemp = temps.getDouble(0)
        for (i in 0 until times.length()) {
            if (times.getString(i) == targetHourString) {
                departureTemp = temps.getDouble(i)
                break
            }
        }

        val daily = json.getJSONObject("daily")
        val dailyTimes = daily.getJSONArray("time")
        val dailyMax = daily.getJSONArray("temperature_2m_max")
        val targetDateString = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
        var maxTemp = dailyMax.getDouble(0)
        for (i in 0 until dailyTimes.length()) {
            if (dailyTimes.getString(i) == targetDateString) {
                maxTemp = dailyMax.getDouble(i)
                break
            }
        }

        return Pair(departureTemp.toInt(), maxTemp.toInt())
    }
}
