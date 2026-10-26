package org.fairshare.app.platform

import kotlinx.datetime.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()
fun todayDate(): String = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
fun parseCalendarDate(text: String): Long {
    require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(text)) { "Use a date like 2026-10-20." }
    return LocalDate.parse(text).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
}
fun calendarDateInput(at: Long) = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.UTC).date.toString()
private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
fun calendarDateLabel(at: Long): String {
    val d = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.UTC).date
    return "${months[d.monthNumber - 1]} ${d.dayOfMonth}, ${d.year}"
}
fun activityDateLabel(at: Long): String {
    val t = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${months[t.monthNumber - 1]} ${t.dayOfMonth}, ${t.year} · ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/** Legacy records lack an independent creation instant; retain their entered calendar date. */
fun creationDateLabel(createdAt: Long, transactionDate: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (createdAt == transactionDate) return calendarDateLabel(transactionDate)
    val date = Instant.fromEpochMilliseconds(createdAt).toLocalDateTime(zone).date
    return "${months[date.monthNumber - 1]} ${date.dayOfMonth}, ${date.year}"
}
