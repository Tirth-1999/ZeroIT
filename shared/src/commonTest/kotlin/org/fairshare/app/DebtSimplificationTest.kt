package org.fairshare.app.domain

import org.fairshare.app.math.BigInteger
import org.fairshare.app.model.*
import kotlin.random.Random
import kotlin.test.*

class DebtSimplificationTest {
    private fun state(ids: List<String> = listOf("a", "b", "c"), simplify: Boolean = true) =
        AppState(members = ids.map { Member(it, it) }, groups = listOf(Group("g", "Trip", memberIds = ids, simplifyDebts = simplify)))

    private fun debt(id: String, from: String, to: String, amount: Long = 100, currency: String? = null) =
        Expense(id, "g", id, amount, to, mapOf(from to amount), currency = currency)

    private fun assertConserves(state: AppState, transfers: List<Transfer>, currency: String = "USD") {
        val net = state.groups.single().memberIds.associateWith { BigInteger.ZERO }.toMutableMap()
        transfers.forEach {
            assertTrue(it.amountMinor > 0)
            assertNotEquals(it.fromId, it.toId)
            net[it.fromId] = net.getValue(it.fromId) - BigInteger.valueOf(it.amountMinor)
            net[it.toId] = net.getValue(it.toId) + BigInteger.valueOf(it.amountMinor)
        }
        assertEquals(Ledger.balances(state, "g", currency).mapValues { BigInteger.valueOf(it.value) }, net)
    }

    @Test fun chainsRouteDirectlyOnlyWhenEnabledWithoutChangingRecords() {
        val original = state(simplify = false).copy(expenses = listOf(debt("ab", "a", "b"), debt("bc", "b", "c")))
        val before = original.copy()
        assertEquals(listOf(Transfer("a", "b", 100), Transfer("b", "c", 100)), Ledger.transfers(original, "g"))
        assertTrue(Ledger.memberHasDebt(original, "g", "b"))
        val enabled = original.copy(groups = original.groups.map { it.copy(simplifyDebts = true) })
        assertEquals(listOf(Transfer("a", "c", 100)), Ledger.transfers(enabled, "g"))
        assertFalse(Ledger.memberHasDebt(enabled, "g", "b"))
        assertEquals(Ledger.balances(original, "g"), Ledger.balances(enabled, "g"))
        assertEquals(before, original)
        assertEquals(original.expenses, enabled.expenses)
        assertEquals(original.settlements, enabled.settlements)
    }

    @Test fun cyclesDisappearOnlyInSimplifiedSuggestions() {
        val cycle = state(simplify = false).copy(expenses = listOf(debt("ab", "a", "b"), debt("bc", "b", "c"), debt("ca", "c", "a")))
        assertEquals(3, Ledger.directTransfers(cycle, "g").size)
        assertTrue(Ledger.balances(cycle, "g").values.all { it == 0L })
        assertTrue(Ledger.memberHasDebt(cycle, "g", "a"))
        assertTrue(Ledger.simplifiedTransfers(cycle, "g").isEmpty())
        assertFalse(Ledger.memberHasDebt(cycle.copy(groups = cycle.groups.map { it.copy(simplifyDebts = true) }), "g", "a"))
    }

    @Test fun partialPaymentsReversePaymentsAndDeletedRecordsNetOnlyOriginalPair() {
        val original = state(simplify = false).copy(expenses = listOf(debt("ab", "a", "b"), debt("bc", "b", "c"), debt("deleted", "c", "a").copy(deleted = true)),
            settlements = listOf(Settlement("partial", "g", "a", "b", 40), Settlement("reverse", "g", "b", "a", 10), Settlement("deleted", "g", "a", "b", 999, deleted = true)))
        assertEquals(listOf(Transfer("a", "b", 70), Transfer("b", "c", 100)), Ledger.directTransfers(original, "g"))
        assertConserves(original, Ledger.directTransfers(original, "g"))
        val overpaid = original.copy(settlements = listOf(Settlement("over", "g", "a", "b", 130)))
        assertEquals(listOf(Transfer("b", "a", 30), Transfer("b", "c", 100)), Ledger.directTransfers(overpaid, "g"))
        assertConserves(overpaid, Ledger.simplifiedTransfers(overpaid, "g"))
    }

    @Test fun currenciesNeverCancelAndNonDefaultCurrencyBlocksRemoval() {
        val mixed = state().copy(expenses = listOf(debt("usd", "a", "b", 100), debt("eur", "b", "a", 100, "EUR"), debt("yen", "c", "a", 500, "JPY")),
            settlements = listOf(Settlement("paid", "g", "a", "b", 100)))
        assertTrue(Ledger.memberHasDebt(mixed, "g", "b"))
        assertTrue(Ledger.transfers(mixed, "g").isEmpty())
        assertEquals(listOf(Transfer("b", "a", 100)), Ledger.transfers(mixed, "g", "EUR"))
        assertEquals(listOf(Transfer("c", "a", 500)), Ledger.directTransfers(mixed, "g", "JPY"))
        for (currency in Ledger.groupCurrencies(mixed, "g")) {
            assertConserves(mixed, Ledger.directTransfers(mixed, "g", currency), currency)
            assertConserves(mixed, Ledger.simplifiedTransfers(mixed, "g", currency), currency)
        }
    }

    @Test fun greedyFallbackNeverAddsMorePaymentsThanDirectBaseline() {
        val original = state(listOf("a", "b", "d", "c")).copy(expenses = listOf(debt("ac", "a", "c", 40), debt("bd", "b", "d", 60)))
        val direct = listOf(Transfer("a", "c", 40), Transfer("b", "d", 60))
        assertEquals(direct, Ledger.directTransfers(original, "g"))
        assertEquals(direct, Ledger.simplifiedTransfers(original, "g"))
    }

    @Test fun hugeBilateralIntermediatesAreChunkedWithoutOverflow() {
        val original = state(simplify = false).copy(expenses = (1..2).flatMap { n -> listOf(
            debt("ab$n", "a", "b", Long.MAX_VALUE), debt("bc$n", "b", "c", Long.MAX_VALUE), debt("ca$n", "c", "a", Long.MAX_VALUE)
        ) })
        val direct = Ledger.directTransfers(original, "g")
        assertEquals(6, direct.size)
        assertTrue(direct.all { it.amountMinor == Long.MAX_VALUE })
        assertConserves(original, direct)
        assertTrue(Ledger.simplifiedTransfers(original, "g").isEmpty())
    }

    @Test fun multiplePayersAndBilateralFriendBalancesShareOneConservedAllocation() {
        val random = Random(7391)
        val ids = listOf(SELF_ID, "a", "b", "c")
        repeat(100) {
            val total = random.nextLong(1, 100_000)
            val paid = Ledger.split(total, ids, SplitMode.SHARES, ids.associateWith { random.nextInt(1, 100).toString() })
            val owed = Ledger.split(total, ids, SplitMode.SHARES, ids.associateWith { random.nextInt(1, 100).toString() })
            val original = state(ids, simplify = false).copy(expenses = listOf(Expense("e", "g", "Dinner", total, SELF_ID, owed, paidBy = paid)))
            val direct = Ledger.directTransfers(original, "g")
            assertConserves(original, direct)
            assertConserves(original, Ledger.simplifiedTransfers(original, "g"))
            ids.drop(1).forEach { friend ->
                val bilateral = direct.sumOf {
                    when {
                        it.fromId == friend && it.toId == SELF_ID -> it.amountMinor
                        it.fromId == SELF_ID && it.toId == friend -> -it.amountMinor
                        else -> 0L
                    }
                }
                assertEquals(bilateral, Ledger.friendBalances(original, friend).getValue("USD"))
            }
            assertTrue(Ledger.simplifiedTransfers(original, "g").size <= direct.size)
        }
    }
    @Test fun friendsFollowConfiguredRoutingAndClearAfterSimplifiedPayment() {
        val ids = listOf(SELF_ID, "middle", "creditor")
        val original = state(ids).copy(expenses = listOf(debt("one", SELF_ID, "middle"), debt("two", "middle", "creditor")))
        assertEquals(mapOf("USD" to 0L), Ledger.friendBalances(original, "middle"))
        assertEquals(mapOf("USD" to -100L), Ledger.friendBalances(original, "creditor"))
        val paid = original.copy(settlements = listOf(Settlement("s", "g", SELF_ID, "creditor", 100)))
        assertTrue(Ledger.transfers(paid, "g").isEmpty())
        assertEquals(mapOf("USD" to 0L), Ledger.friendBalances(paid, "middle"))
        assertEquals(mapOf("USD" to 0L), Ledger.friendBalances(paid, "creditor"))
        val direct = paid.copy(groups = paid.groups.map { it.copy(simplifyDebts = false) })
        assertEquals(mapOf("USD" to -100L), Ledger.friendBalances(direct, "middle"))
        assertEquals(mapOf("USD" to 100L), Ledger.friendBalances(direct, "creditor"))
        assertEquals(original.expenses, paid.expenses)
    }

    @Test fun directFallbackCanPreserveIntermediateEdgesAndItsPaymentsStillClearBalances() {
        val original = state(listOf("a", "b", "c", "d", "e")).copy(expenses = listOf(
            debt("ca", "c", "a", 2), debt("de", "d", "e", 7), debt("bd", "b", "d", 3)
        ))
        val direct = listOf(Transfer("c", "a", 2), Transfer("d", "e", 7), Transfer("b", "d", 3))
        assertEquals(direct, Ledger.simplifiedTransfers(original, "g"))
        assertConserves(original, direct)
        val paid = original.copy(settlements = direct.mapIndexed { i, t -> Settlement("s$i", "g", t.fromId, t.toId, t.amountMinor) })
        assertTrue(Ledger.balances(paid, "g").values.all { it == 0L })
        assertTrue(Ledger.transfers(paid, "g").isEmpty())
    }

}
