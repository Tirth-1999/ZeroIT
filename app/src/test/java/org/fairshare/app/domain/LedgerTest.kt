package org.fairshare.app.domain

import java.math.BigInteger
import kotlin.random.Random
import org.fairshare.app.model.*
import org.junit.Assert.*
import org.junit.Test

class LedgerTest {
    private val ids = listOf("a", "b", "c")
    private val group = Group("g", "Trip", memberIds = ids)
    private val expense = Expense("e", "g", "Dinner", 3000, "a", ids.associateWith { 1000L })
    private val initial = AppState(groups = listOf(group), expenses = listOf(expense))

    @Test fun equalRemainderIsDeterministic() {
        val result = Ledger.split(9021, listOf("a", "b", "c", "d", "e"), SplitMode.EQUAL)
        assertEquals(listOf(1805L, 1804L, 1804L, 1804L, 1804L), result.values.toList())
        assertEquals(mapOf("a" to 1L, "b" to 0L, "c" to 0L), Ledger.split(1, ids, SplitMode.EQUAL))
    }

    @Test fun exactUsesCurrencyMinorUnitsAndRequiresReconciliation() {
        assertEquals(mapOf("a" to 123L, "b" to 177L), Ledger.split(300, ids.take(2), SplitMode.EXACT, mapOf("a" to "1.23", "b" to "1.77")))
        assertEquals(mapOf("a" to 123L, "b" to 177L), Ledger.split(300, ids.take(2), SplitMode.EXACT, mapOf("a" to "123", "b" to "177"), "JPY"))
        invalid { Ledger.split(300, ids, SplitMode.EXACT, mapOf("a" to "2.99")) }
        invalid { Ledger.split(300, ids, SplitMode.EXACT, mapOf("a" to "4", "b" to "-1")) }
    }

    @Test fun percentagesAndFractionalSharesUseLargestRemainder() {
        assertEquals(listOf(33L, 34L, 34L), Ledger.split(101, ids, SplitMode.PERCENT, mapOf("a" to "33", "b" to "33.5", "c" to "33.5")).values.toList())
        assertEquals(listOf(25L, 25L, 51L), Ledger.split(101, ids, SplitMode.SHARES, mapOf("a" to ".5", "b" to "0.5", "c" to "1")).values.toList())
        assertEquals(listOf(0L, 101L, 0L), Ledger.split(101, ids, SplitMode.SHARES, mapOf("b" to "3")).values.toList())
        invalid { Ledger.split(100, ids, SplitMode.PERCENT, mapOf("a" to "99.999")) }
        invalid { Ledger.split(100, ids, SplitMode.SHARES) }
        invalid { Ledger.split(100, ids, SplitMode.SHARES, mapOf("a" to "-1", "b" to "2")) }
    }

    @Test fun adjustmentsDistributeRemainderThenApplyExtras() {
        assertEquals(listOf(1200L, 900L, 900L), Ledger.split(3000, ids, SplitMode.ADJUSTMENT, mapOf("a" to "3")).values.toList())
        assertEquals(listOf(800L, 1100L, 1100L), Ledger.split(3000, ids, SplitMode.ADJUSTMENT, mapOf("a" to "-3")).values.toList())
        invalid { Ledger.split(3000, ids, SplitMode.ADJUSTMENT, mapOf("a" to "-100")) }
    }

    @Test fun parserRejectsAmbiguousOrUnrepresentableMoney() {
        assertEquals(123L, Ledger.parseMoney("1.23"))
        assertEquals(-123L, Ledger.parseMoney(" -1.23 "))
        assertEquals(123L, Ledger.parseMoney("123", "JPY"))
        assertEquals(1234L, Ledger.parseMoney("1.234", "KWD"))
        assertEquals(Long.MAX_VALUE, Ledger.parseMoney("92233720368547758.07"))
        assertEquals(Long.MIN_VALUE, Ledger.parseMoney("-92233720368547758.08"))
        listOf("", "1,000", "NaN", "1e3", "1.001", "92233720368547758.08").forEach { value -> invalid { Ledger.parseMoney(value) } }
        invalid { Ledger.parseMoney("1.1", "JPY") }
        invalid { Ledger.parseMoney("1", "FAKE") }
        assertEquals("$1.23", Ledger.formatMoney(123))
        assertTrue(Ledger.formatMoney(123, "JPY").endsWith("123"))
        assertTrue(Ledger.formatMoney(1234, "KWD").endsWith("1.234"))
    }

    @Test fun invalidParticipantsAndOverflowDoNotSilentlyWrap() {
        invalid { Ledger.split(-1, ids, SplitMode.EQUAL) }
        invalid { Ledger.split(100, emptyList(), SplitMode.EQUAL) }
        invalid { Ledger.split(100, listOf("a", "a"), SplitMode.EQUAL) }
        invalid { Ledger.split(100, listOf(""), SplitMode.EQUAL) }
        invalid { Ledger.split(100, ids, SplitMode.EXACT, mapOf("unknown" to "1")) }
        val large = expense.copy(amountMinor = Long.MAX_VALUE, owed = mapOf("b" to Long.MAX_VALUE))
        invalid { Ledger.balances(initial.copy(expenses = listOf(large, large.copy(id = "e2"))), "g") }
    }

    @Test fun editDeleteRestoreAndSettlementPreserveLedgerMeaning() {
        assertEquals(mapOf("a" to 2000L, "b" to -1000L, "c" to -1000L), Ledger.balances(initial, "g"))
        val edited = initial.copy(expenses = listOf(expense.copy(payerId = "b")))
        assertEquals(mapOf("a" to -1000L, "b" to 2000L, "c" to -1000L), Ledger.balances(edited, "g"))
        assertTrue(Ledger.balances(initial.copy(expenses = listOf(expense.copy(deleted = true))), "g").values.all { it == 0L })
        val settled = initial.copy(settlements = listOf(Settlement("s", "g", "b", "a", 400)))
        assertEquals(mapOf("a" to 1600L, "b" to -600L, "c" to -1000L), Ledger.balances(settled, "g"))
        assertEquals(Ledger.balances(initial, "g"), Ledger.balances(settled.copy(settlements = listOf(settled.settlements.single().copy(deleted = true))), "g"))
        assertEquals(listOf(Transfer("b", "a", 600), Transfer("c", "a", 1000)), Ledger.transfers(settled, "g"))
        val fullySettled = initial.copy(settlements = Ledger.transfers(initial, "g").mapIndexed { i, t -> Settlement("s$i", "g", t.fromId, t.toId, t.amountMinor) })
        assertTrue(Ledger.balances(fullySettled, "g").values.all { it == 0L })
    }

    @Test fun malformedStoredRecordsAreRejectedAndOtherGroupsAreExcluded() {
        invalid { Ledger.balances(initial.copy(expenses = listOf(expense.copy(owed = mapOf("b" to 1)))), "g") }
        invalid { Ledger.balances(initial.copy(expenses = listOf(expense.copy(payerId = "outside"))), "g") }
        invalid { Ledger.balances(initial.copy(settlements = listOf(Settlement("s", "g", "b", "b", 100))), "g") }
        assertEquals(Ledger.balances(initial, "g"), Ledger.balances(initial.copy(expenses = listOf(expense, expense.copy(id = "other", groupId = "other"))), "g"))
    }

    @Test fun phaseOneAcceptanceScenarioWithPartialSettlementEditDeleteRestore() {
        val members = listOf(SELF_ID, "alice", "bob")
        val originalExpense = expense.copy(payerId = SELF_ID, owed = Ledger.split(3000, members, SplitMode.EQUAL))
        val original = initial.copy(groups = listOf(group.copy(memberIds = members)), expenses = listOf(originalExpense))
        assertEquals(mapOf(SELF_ID to 2000L, "alice" to -1000L, "bob" to -1000L), Ledger.balances(original, "g"))
        val payment = Settlement("s", "g", "alice", SELF_ID, 500)
        val paid = original.copy(settlements = listOf(payment))
        assertEquals(mapOf(SELF_ID to 1500L, "alice" to -500L, "bob" to -1000L), Ledger.balances(paid, "g"))
        val editedExpense = originalExpense.copy(amountMinor = 6000, owed = Ledger.split(6000, members, SplitMode.EQUAL))
        val edited = paid.copy(expenses = listOf(editedExpense))
        assertEquals(mapOf(SELF_ID to 3500L, "alice" to -1500L, "bob" to -2000L), Ledger.balances(edited, "g"))
        val deleted = edited.copy(expenses = listOf(editedExpense.copy(deleted = true)))
        // A recorded payment survives expense deletion and becomes a credit to its payer.
        assertEquals(mapOf(SELF_ID to -500L, "alice" to 500L, "bob" to 0L), Ledger.balances(deleted, "g"))
        val restored = deleted.copy(expenses = listOf(deleted.expenses.single().copy(deleted = false)))
        assertEquals(Ledger.balances(edited, "g"), Ledger.balances(restored, "g"))
        assertEquals(listOf(Transfer("alice", SELF_ID, 1500), Transfer("bob", SELF_ID, 2000)), Ledger.transfers(restored, "g"))
    }

    @Test fun largeIntermediateTotalsMayCancelWithoutOverflow() {
        val large = expense.copy(amountMinor = Long.MAX_VALUE, owed = mapOf("b" to Long.MAX_VALUE))
        val reverse = large.copy(id = "reverse", payerId = "b", owed = mapOf("a" to Long.MAX_VALUE))
        val cancel = initial.copy(expenses = listOf(large, large.copy(id = "duplicate"), reverse))
        assertEquals(mapOf("a" to Long.MAX_VALUE, "b" to -Long.MAX_VALUE, "c" to 0L), Ledger.balances(cancel, "g"))
        assertEquals(listOf(Transfer("b", "a", Long.MAX_VALUE)), Ledger.transfers(cancel, "g"))
    }

    @Test fun randomizedAllocationsAndSuggestedTransfersConserveMoney() {
        val random = Random(20261020)
        repeat(500) {
            val members = List(random.nextInt(1, 15)) { "m$it" }
            val amount = random.nextLong(1, Long.MAX_VALUE)
            val weights = members.associateWith { random.nextInt(1, 10000).toString() }
            for (mode in listOf(SplitMode.EQUAL, SplitMode.SHARES)) {
                val owed = Ledger.split(amount, members, mode, weights)
                assertEquals(BigInteger.valueOf(amount), owed.values.fold(BigInteger.ZERO) { sum, share -> sum + BigInteger.valueOf(share) })
                assertTrue(owed.values.all { it >= 0 })
                assertEquals(owed, Ledger.split(amount, members, mode, weights))
                val state = AppState(groups = listOf(group.copy(memberIds = members)), expenses = listOf(expense.copy(amountMinor = amount, payerId = members.last(), owed = owed)))
                assertEquals(BigInteger.ZERO, Ledger.balances(state, "g").values.fold(BigInteger.ZERO) { sum, n -> sum + BigInteger.valueOf(n) })
                val settlements = Ledger.transfers(state, "g").mapIndexed { i, t -> Settlement("s$i", "g", t.fromId, t.toId, t.amountMinor) }
                assertTrue(Ledger.balances(state.copy(settlements = settlements), "g").values.all { it == 0L })
            }
        }
    }

    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid input to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun friendsAggregateBilateralSharesAcrossGroupsAndDirectLedgersWithoutCurrencyConversion() {
        val members = listOf(Member(SELF_ID, "You"), Member("alice", "Alice"), Member("bob", "Bob"))
        val shared = Group("shared", "Home", memberIds = members.map { it.id }, simplifyDebts = false)
        val direct = Group("direct", "Alice", memberIds = listOf(SELF_ID, "alice"), kind = "DIRECT")
        val eur = shared.copy(id = "eur", currency = "EUR")
        val state = AppState(members = members, groups = listOf(shared, direct, eur), expenses = listOf(
            Expense("shared-meal", "shared", "Dinner", 3000, SELF_ID, mapOf(SELF_ID to 1000, "alice" to 1000, "bob" to 1000)),
            Expense("direct-meal", "direct", "Lunch", 600, "alice", mapOf(SELF_ID to 300, "alice" to 300)),
            Expense("third-party", "shared", "Taxi", 900, "bob", mapOf(SELF_ID to 450, "alice" to 450)),
            Expense("euro-meal", "eur", "Coffee", 800, "alice", mapOf(SELF_ID to 400, "alice" to 400)),
            Expense("removed", "shared", "Deleted", 100, SELF_ID, mapOf("alice" to 100), deleted = true)
        ), settlements = listOf(
            Settlement("alice-pays", "shared", "alice", SELF_ID, 200),
            Settlement("self-pays", "direct", SELF_ID, "alice", 50),
            Settlement("third-pays", "shared", "alice", "bob", 100),
            Settlement("deleted-payment", "shared", "alice", SELF_ID, 100, deleted = true)
        ))
        assertEquals(mapOf("USD" to 550L, "EUR" to -400L), Ledger.friendBalances(state, "alice"))
        assertEquals(mapOf("USD" to 550L, "EUR" to 0L), Ledger.friendBalances(state, "bob"))
        invalid { Ledger.friendBalances(state, SELF_ID) }
        invalid { Ledger.friendBalances(state, "missing") }
    }

    @Test fun friendBalancesRejectOverflowButAllowIntermediateCancellationAcrossGroups() {
        val state = AppState(members = listOf(Member(SELF_ID, "You"), Member("alice", "Alice")),
            groups = listOf("one", "two", "reverse").map { Group(it, it, memberIds = listOf(SELF_ID, "alice")) },
            expenses = listOf(
                Expense("one", "one", "One", Long.MAX_VALUE, SELF_ID, mapOf("alice" to Long.MAX_VALUE)),
                Expense("two", "two", "Two", Long.MAX_VALUE, SELF_ID, mapOf("alice" to Long.MAX_VALUE)),
                Expense("reverse", "reverse", "Reverse", Long.MAX_VALUE, "alice", mapOf(SELF_ID to Long.MAX_VALUE))
            ))
        assertEquals(mapOf("USD" to Long.MAX_VALUE), Ledger.friendBalances(state, "alice"))
        invalid { Ledger.friendBalances(state.copy(expenses = state.expenses.take(2)), "alice") }
        assertTrue(Ledger.friendBalances(state.copy(groups = emptyList(), expenses = emptyList()), "alice").isEmpty())
    }
}
