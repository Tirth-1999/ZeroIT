package org.fairshare.app

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import org.fairshare.app.platform.creationDateLabel
import org.fairshare.app.platform.parseCalendarDate

class CreationDateLabelTest {
    @Test fun creationDayUsesLocalTimeWhileLegacyEnteredDatesDoNotShift() {
        val added = Instant.parse("2026-10-22T01:00:00Z").toEpochMilliseconds()
        val expenseDate = parseCalendarDate("2026-10-01")
        assertEquals("Oct 21, 2026", creationDateLabel(added, expenseDate, TimeZone.of("America/Chicago")))
        assertEquals("Oct 22, 2026", creationDateLabel(added, expenseDate, TimeZone.of("Asia/Tokyo")))
        assertEquals("Oct 1, 2026", creationDateLabel(expenseDate, expenseDate, TimeZone.of("America/Chicago")))
    }
}
