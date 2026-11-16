package com.sangar.gal.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A usage event with everything GAL does not need stripped off: who came to the front or left it, and when. */
data class AppEvent(val timeMillis: Long, val packageName: String?, val kind: Kind) {
    enum class Kind { RESUMED, PAUSED, SCREEN_OFF }
}

/** Time one app spent in front during one hour of one day. The raw material of the home screen's insights. */
data class HourUse(val date: LocalDate, val hour: Int, val packageName: String, val millis: Long)

/**
 * Turns the phone's raw usage events into "this app, this hour, this long". Pure, so it is unit tested; the
 * Android side ([HourlyUsageSync]) only fetches the events.
 */
object HourlyUsage {

    /** Rows shorter than this are noise (an app flashing past in the app switcher). */
    const val MIN_ROW_MILLIS = 5_000L

    data class Span(val packageName: String, val start: Long, val end: Long)

    /**
     * Foreground spans inside [from, to). An app still in front when the events run out counts up to [to] when
     * [openRunsToEnd] (the window ends now), and otherwise only up to the last event seen, so a missing "paused"
     * event can never invent hours of use on a past day.
     */
    fun spans(events: List<AppEvent>, from: Long, to: Long, openRunsToEnd: Boolean): List<Span> {
        val open = HashMap<String, Long>()
        val out = ArrayList<Span>()
        var last = from
        fun close(pkg: String, start: Long, end: Long) {
            val s = maxOf(start, from)
            val e = minOf(end, to)
            if (e > s) out += Span(pkg, s, e)
        }
        for (event in events.sortedBy { it.timeMillis }) {
            last = maxOf(last, event.timeMillis)
            when (event.kind) {
                AppEvent.Kind.RESUMED -> event.packageName?.let { open.putIfAbsent(it, event.timeMillis) }
                AppEvent.Kind.PAUSED -> event.packageName?.let { pkg -> open.remove(pkg)?.let { close(pkg, it, event.timeMillis) } }
                AppEvent.Kind.SCREEN_OFF -> {
                    open.forEach { (pkg, start) -> close(pkg, start, event.timeMillis) }
                    open.clear()
                }
            }
        }
        val end = if (openRunsToEnd) to else minOf(last, to)
        open.forEach { (pkg, start) -> close(pkg, start, end) }
        return out
    }

    /** Cuts each span at the hour marks of [zone] and adds the pieces up. */
    fun byHour(spans: List<Span>, zone: ZoneId): List<HourUse> {
        val totals = HashMap<Triple<LocalDate, Int, String>, Long>()
        for (span in spans) {
            var cursor = span.start
            while (cursor < span.end) {
                val at = Instant.ofEpochMilli(cursor).atZone(zone)
                val nextHour = at.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                // A clock change can make "the next hour" not later than now; always move forward.
                val pieceEnd = minOf(span.end, if (nextHour > cursor) nextHour else cursor + 3_600_000L)
                val key = Triple(at.toLocalDate(), at.hour, span.packageName)
                totals[key] = (totals[key] ?: 0L) + (pieceEnd - cursor)
                cursor = pieceEnd
            }
        }
        return totals
            .filterValues { it >= MIN_ROW_MILLIS }
            .map { (key, millis) -> HourUse(key.first, key.second, key.third, millis) }
            .sortedWith(compareBy({ it.date }, { it.hour }, { it.packageName }))
    }

    fun rows(
        events: List<AppEvent>,
        from: Long,
        to: Long,
        zone: ZoneId,
        openRunsToEnd: Boolean,
        exclude: Set<String> = emptySet(),
    ): List<HourUse> = byHour(spans(events, from, to, openRunsToEnd).filter { it.packageName !in exclude }, zone)

    /** Total time the [packages] spent in front, from rows of one day. */
    fun total(rows: List<HourUse>, packages: Set<String>, day: LocalDate): Long =
        rows.filter { it.date == day && it.packageName in packages }.sumOf { it.millis }
}
