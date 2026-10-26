package org.fairshare.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fairshare.app.domain.Ledger
import org.fairshare.app.math.BigInteger
import org.fairshare.app.model.*
import org.fairshare.app.platform.parseCalendarDate
import org.fairshare.app.ui.memberGroupSpending
import org.fairshare.app.ui.monthlyGroupSpending
import kotlin.test.*

/** Synthetic fixtures only. No installed app database, wall-clock threshold, or network required. */
class PhaseOneScaleTest {
    private val people = listOf(SELF_ID) + List(59) { "member-${it + 1}" }
    private val currencies = listOf("USD", "JPY", "KWD") // 2, 0 and 3 decimal places.

    private fun largeLedger(): AppState {
        val expenses = buildList {
            repeat(24) { month ->
                val monthIndex = 2024 * 12 + 10 + month // Nov 2024 through Oct 2026.
                val year = monthIndex / 12
                val number = monthIndex % 12 + 1
                for (day in 1..28) currencies.forEachIndexed { currencyIndex, currency ->
                    val index = month * 84 + (day - 1) * 3 + currencyIndex
                    val amount = 1L + (index * 7919L % 100_000L)
                    val owed = Ledger.split(amount, people, SplitMode.EQUAL)
                    val payer = people[index % people.size]
                    val second = people[(index + 17) % people.size]
                    add(Expense("expense-$index", "scale", "Expense $index", amount, payer, owed,
                        date = parseCalendarDate("$year-${number.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"),
                        currency = currency,
                        paidBy = if (index % 7 == 0) mapOf(payer to amount / 2, second to amount - amount / 2) else emptyMap(),
                        deleted = index % 31 == 0))
                }
            }
        }
        return AppState(members = people.map { Member(it, "Person $it") },
            groups = listOf(Group("scale", "Two-year large ledger", "USD", people)), expenses = expenses,
            settlements = currencies.flatMapIndexed { index, currency -> listOf(
                Settlement("payment-$index", "scale", people[1], SELF_ID, 137L + index, currency = currency),
                Settlement("deleted-payment-$index", "scale", people[2], SELF_ID, 999_999, deleted = true, currency = currency)) },
            activity = expenses.map { Activity("event-${it.id}", "scale", "Added ${it.title}", it.date, expenseId = it.id) })
    }

    /** Independent account-posting oracle; it does not invoke balance or transfer implementation. */
    private fun expected(state: AppState, currency: String): Map<String, Long> {
        val result = people.associateWith { 0L }.toMutableMap()
        state.expenses.filter { !it.deleted && it.currency == currency }.forEach { expense ->
            expense.paidBy.ifEmpty { mapOf(expense.payerId to expense.amountMinor) }.forEach { (id, amount) -> result[id] = result.getValue(id) + amount }
            expense.owed.forEach { (id, amount) -> result[id] = result.getValue(id) - amount }
        }
        state.settlements.filter { !it.deleted && it.currency == currency }.forEach { payment ->
            result[payment.fromId] = result.getValue(payment.fromId) + payment.amountMinor
            result[payment.toId] = result.getValue(payment.toId) - payment.amountMinor
        }
        return result
    }

    @Test fun sixtyPeopleTwoYearsAndThreeCurrenciesConserveAndSettleIndependently() {
        val state = largeLedger()
        assertEquals(60, state.groups.single().activeMemberIds.size)
        assertEquals(2_016, state.expenses.size)
        assertEquals(currencies, Ledger.groupCurrencies(state, "scale"))
        val payments = mutableListOf<Settlement>()
        currencies.forEach { currency ->
            val balances = Ledger.balances(state, "scale", currency)
            assertEquals(expected(state, currency), balances)
            assertEquals(0L, balances.values.sum())
            val direct = Ledger.directTransfers(state, "scale", currency)
            val suggested = Ledger.transfers(state, "scale", currency)
            assertTrue(suggested.size <= direct.size)
            val posted = people.associateWith { 0L }.toMutableMap()
            suggested.forEachIndexed { index, transfer ->
                assertTrue(transfer.amountMinor > 0)
                assertNotEquals(transfer.fromId, transfer.toId)
                posted[transfer.fromId] = posted.getValue(transfer.fromId) - transfer.amountMinor
                posted[transfer.toId] = posted.getValue(transfer.toId) + transfer.amountMinor
                payments += Settlement("close-$currency-$index", "scale", transfer.fromId, transfer.toId, transfer.amountMinor, currency = currency)
            }
            assertEquals(balances, posted)
        }
        val settled = state.copy(settlements = state.settlements + payments)
        currencies.forEach { currency ->
            assertTrue(Ledger.balances(settled, "scale", currency).values.all { it == 0L })
            assertTrue(Ledger.transfers(settled, "scale", currency).isEmpty())
        }
        assertEquals(2_016, state.expenses.size) // Suggestions never consume the source records.
    }

    @Test fun twoYearReportsKeepLatestSixMonthsAndFullMemberTotalsSeparateByCurrency() {
        val state = largeLedger()
        currencies.forEach { currency ->
            val matching = state.expenses.filter { !it.deleted && it.currency == currency }
            val monthly = monthlyGroupSpending(state, "scale", currency)
            assertEquals(listOf("May 2026", "Jun 2026", "Jul 2026", "Aug 2026", "Sep 2026", "Oct 2026"), monthly.map { it.label })
            monthly.forEach { month ->
                val start = parseCalendarDate("${month.month.year}-${month.month.monthNumber.toString().padStart(2, '0')}-01")
                val monthExpenses = matching.filter { it.date >= start && it.date < start + 28L * 86_400_000 }
                assertEquals(sum(monthExpenses.map { it.amountMinor }), month.amountMinor)
                assertEquals(sum(monthExpenses.map { it.owed.getValue(SELF_ID) }), month.ownShareMinor)
            }
            val members = memberGroupSpending(state, "scale", currency)
            assertEquals(60, members.size)
            assertEquals(sum(matching.map { it.amountMinor }), members.fold(BigInteger.ZERO) { total, member -> total + member.paidMinor })
            assertEquals(sum(matching.map { it.amountMinor }), members.fold(BigInteger.ZERO) { total, member -> total + member.shareMinor })
        }
    }

    @Test fun largeSnapshotRoundTripRetainsAllHistoryAndMonetaryPrecision() {
        val original = largeLedger()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val restored = json.decodeFromString<AppState>(json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(2_016, restored.activity.size)
        assertEquals(original.activity.map { it.expenseId }.toSet(), restored.expenses.map { it.id }.toSet())
        currencies.forEach { assertEquals(expected(original, it), Ledger.balances(restored, "scale", it)) }
    }

    @Test fun minimumAndMaximumAmountsDistributeExactlyAcrossSixtyMembers() {
        for (total in listOf(0L, 1L, 59L, 60L, 61L, Long.MAX_VALUE)) {
            for (mode in listOf(SplitMode.EQUAL, SplitMode.SHARES)) {
                val result = Ledger.split(total, people, mode, if (mode == SplitMode.SHARES) people.associateWith { "1" } else emptyMap())
                assertEquals(60, result.size)
                assertEquals(BigInteger.valueOf(total), sum(result.values))
                assertTrue(result.values.all { it >= 0 })
                assertTrue(result.values.max() - result.values.min() <= 1L)
                assertEquals(result, Ledger.split(total, people, mode, if (mode == SplitMode.SHARES) people.associateWith { "1" } else emptyMap()))
            }
        }
        val solo = people.take(1)
        assertEquals(mapOf(SELF_ID to Long.MAX_VALUE), Ledger.split(Long.MAX_VALUE, solo, SplitMode.EQUAL))
        assertEquals(mapOf(SELF_ID to 1L), Ledger.split(1, people, SplitMode.SHARES, mapOf(SELF_ID to "0.000000000000000001")).filterValues { it > 0 })
        assertFailsWith<IllegalArgumentException> { Ledger.split(1, emptyList(), SplitMode.EQUAL) }
    }

    @Test fun removedPeopleRetainHistoricalDebtAndSoftDeletedRecordsRestoreExactlyAtScale() {
        val original = largeLedger()
        val removed = original.copy(groups = original.groups.map { it.copy(removedMemberIds = people.drop(50)) })
        assertEquals(50, removed.groups.single().activeMemberIds.size)
        currencies.forEach { assertEquals(Ledger.balances(original, "scale", it), Ledger.balances(removed, "scale", it)) }
        val deleted = original.copy(expenses = original.expenses.map { it.copy(deleted = true) }, settlements = original.settlements.map { it.copy(deleted = true) })
        currencies.forEach { assertTrue(Ledger.balances(deleted, "scale", it).values.all { amount -> amount == 0L }) }
        val restored = deleted.copy(expenses = original.expenses, settlements = original.settlements)
        assertEquals(original, restored)
        currencies.forEach { assertEquals(expected(original, it), Ledger.balances(restored, "scale", it)) }
    }

    @Test fun invalidCurrencyAndParticipantBoundariesFailWithoutChangingTheSnapshot() {
        val original = largeLedger()
        val first = original.expenses.first { !it.deleted }
        val invalid = listOf(
            first.copy(amountMinor = 0),
            first.copy(owed = first.owed + ("outsider" to 1L)),
            first.copy(paidBy = mapOf("outsider" to first.amountMinor)),
            first.copy(currency = "INVALID"),
            first.copy(owed = mapOf(SELF_ID to -1L)))
        invalid.forEach { expense ->
            assertFailsWith<IllegalArgumentException> { Ledger.balances(original.copy(expenses = listOf(expense)), "scale", first.currency) }
        }
        assertFailsWith<IllegalArgumentException> { Ledger.balances(original, "missing") }
        assertFailsWith<IllegalArgumentException> { Ledger.balances(original, "scale", "INVALID") }
        assertFailsWith<IllegalArgumentException> { Ledger.balances(original.copy(groups = original.groups.map { it.copy(memberIds = people + SELF_ID) }), "scale") }
        assertEquals(2_016, original.expenses.size)
        currencies.forEach { assertEquals(expected(original, it), Ledger.balances(original, "scale", it)) }
    }

    private fun sum(values: Iterable<Long>) = values.fold(BigInteger.ZERO) { total, value -> total + BigInteger.valueOf(value) }
}
