package org.fairshare.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import org.fairshare.app.math.BigInteger
import org.fairshare.app.model.AppState
import org.fairshare.app.model.Expense
import org.fairshare.app.model.Group
import org.fairshare.app.model.SELF_ID
import org.fairshare.app.model.Settlement
import org.fairshare.app.platform.parseCalendarDate

class GroupSpendingChartTest {
    @Test fun monthlyChartFillsLatestSixCalendarMonthsAndKeepsCurrenciesSeparate() {
        fun expense(id: String, date: String, amount: Long = 100, currency: String? = null, groupId: String = "trip", deleted: Boolean = false) =
            Expense(id, groupId, id, amount, SELF_ID, mapOf(SELF_ID to amount), date = parseCalendarDate(date), currency = currency, deleted = deleted)
        val state = AppState(groups = listOf(Group("trip", "Trip", "EUR", listOf(SELF_ID))), expenses = listOf(
            expense("old", "2025-12-31"),
            expense("jan", "2026-01-01"),
            expense("feb", "2026-02-01"),
            expense("apr", "2026-04-01"),
            expense("jul", "2026-07-01"),
            expense("sep-a", "2026-09-01", Long.MAX_VALUE),
            expense("sep-b", "2026-09-30", Long.MAX_VALUE),
            expense("oct", "2026-10-01", 200, "EUR"),
            expense("usd", "2026-10-01", 900, "USD"),
            expense("deleted", "2026-11-01", deleted = true),
            expense("other", "2026-12-01", groupId = "home"),
        ), settlements = listOf(Settlement("payment", "trip", SELF_ID, "alice", 1000, date = parseCalendarDate("2026-12-01"))))
        val months = monthlyGroupSpending(state, "trip", "EUR")
        assertEquals(listOf("May 2026", "Jun 2026", "Jul 2026", "Aug 2026", "Sep 2026", "Oct 2026"), months.map { it.label })
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.valueOf(2), months[4].amountMinor)
        assertEquals(BigInteger.valueOf(200), months.last().amountMinor)
        assertEquals(listOf(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(900)), monthlyGroupSpending(state, "trip", "USD").map { it.amountMinor })
        assertEquals(BigInteger.ZERO, months[3].amountMinor)
        assertEquals(emptyList(), monthlyGroupSpending(state, "trip", "GBP"))
    }

    @Test fun chartProportionsAreBoundedAndDoNotOverflowAggregateMinorUnits() {
        val huge = BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.valueOf(10)
        assertEquals(1f, spendingBarFraction(huge, huge))
        assertEquals(.5f, spendingBarFraction(huge, huge * BigInteger.valueOf(2)))
        assertEquals(1f, spendingBarFraction(huge, BigInteger.valueOf(1)))
        assertEquals(0f, spendingBarFraction(BigInteger.ZERO, huge))
        assertEquals(0f, spendingBarFraction(huge, BigInteger.ZERO))
    }

    @Test fun reportsSeparateConsumptionFromFundingAndPayments() {
        val state = AppState(groups = listOf(Group("trip", "Trip", "USD", listOf(SELF_ID, "alice", "bob"), removedMemberIds = listOf("bob"))), expenses = listOf(
            Expense("lunch", "trip", "Lunch", 2323, SELF_ID, mapOf(SELF_ID to 1162, "alice" to 1161), date = parseCalendarDate("2026-10-01"), paidBy = mapOf(SELF_ID to 1000, "alice" to 1323)),
            Expense("tickets", "trip", "Tickets", 100, "bob", mapOf(SELF_ID to 100), date = parseCalendarDate("2026-10-02")),
            Expense("historical", "trip", "Historical", 50, "former", mapOf("former" to 50), date = parseCalendarDate("2026-10-03")),
            Expense("euro", "trip", "Euro", 9999, SELF_ID, mapOf(SELF_ID to 9999), currency = "EUR"),
            Expense("deleted", "trip", "Deleted", 9999, SELF_ID, mapOf(SELF_ID to 9999), deleted = true),
            Expense("elsewhere", "other", "Elsewhere", 9999, SELF_ID, mapOf(SELF_ID to 9999)),
        ), settlements = listOf(Settlement("settle", "trip", "alice", SELF_ID, 9999)))
        val members = memberGroupSpending(state, "trip", "USD").associateBy { it.memberId }
        assertEquals(setOf(SELF_ID, "alice", "bob", "former"), members.keys)
        assertEquals(BigInteger.valueOf(1262), members.getValue(SELF_ID).shareMinor)
        assertEquals(BigInteger.valueOf(1000), members.getValue(SELF_ID).paidMinor)
        assertEquals(BigInteger.valueOf(1161), members.getValue("alice").shareMinor)
        assertEquals(BigInteger.valueOf(1323), members.getValue("alice").paidMinor)
        assertEquals(BigInteger.ZERO, members.getValue("bob").shareMinor)
        assertEquals(BigInteger.valueOf(100), members.getValue("bob").paidMinor)
        val month = monthlyGroupSpending(state, "trip", "USD").last()
        assertEquals(BigInteger.valueOf(2473), month.amountMinor)
        assertEquals(BigInteger.valueOf(1262), month.ownShareMinor)
        assertEquals(BigInteger.valueOf(1000), month.ownPaidMinor)
        assertEquals(month.amountMinor, members.values.fold(BigInteger.ZERO) { sum, item -> sum + item.shareMinor })
        assertEquals(month.amountMinor, members.values.fold(BigInteger.ZERO) { sum, item -> sum + item.paidMinor })
    }

    @Test fun personalAndMemberAggregatesNeverOverflowLong() {
        val expenses = (1..2).map { id -> Expense("$id", "trip", "Large", Long.MAX_VALUE, SELF_ID, mapOf(SELF_ID to Long.MAX_VALUE), date = parseCalendarDate("2026-10-01")) }
        val state = AppState(groups = listOf(Group("trip", "Trip", "USD", listOf(SELF_ID))), expenses = expenses)
        val expected = BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.valueOf(2)
        assertEquals(expected, monthlyGroupSpending(state, "trip", "USD").last().ownShareMinor)
        assertEquals(expected, monthlyGroupSpending(state, "trip", "USD").last().ownPaidMinor)
        val self = memberGroupSpending(state, "trip", "USD").single()
        assertEquals(expected, self.shareMinor)
        assertEquals(expected, self.paidMinor)
    }

    @Test fun quietMonthsAndYearBoundaryRemainInTheTimeline() {
        val state = AppState(groups = listOf(Group("trip", "Trip", "USD", listOf(SELF_ID))), expenses = listOf(
            Expense("aug", "trip", "Summer", 100, SELF_ID, mapOf(SELF_ID to 100), date = parseCalendarDate("2025-08-15")),
            Expense("jan", "trip", "Winter", 200, SELF_ID, mapOf(SELF_ID to 200), date = parseCalendarDate("2026-01-03")),
        ))
        val months = monthlyGroupSpending(state, "trip", "USD")
        assertEquals(listOf("Aug 2025", "Sep 2025", "Oct 2025", "Nov 2025", "Dec 2025", "Jan 2026"), months.map { it.label })
        assertEquals(listOf(100L, 0L, 0L, 0L, 0L, 200L), months.map { it.amountMinor.toLong() })
    }
}
