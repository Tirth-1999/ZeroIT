package org.fairshare.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fairshare.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GroupLifecycleRepositoryTest {
    @Test fun simplifiedFallbackSuggestionsCanBePaidBeyondMembersNetDebt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fallback-repayment-${UUID.randomUUID()}.db"
        val repository = LocalRepository(context, name)
        try {
            val groupId = repository.createGroup("Fallback routing", "USD", listOf("B", "C", "D", "E"))
            val people = repository.state.value.groups.single().memberIds
            val b = people[1]; val c = people[2]; val d = people[3]; val e = people[4]
            repository.saveExpense(Expense("c-owes-self", groupId, "Small expense", 200, SELF_ID, mapOf(c to 200L), currency = "USD"))
            repository.saveExpense(Expense("d-owes-e", groupId, "Large expense", 700, e, mapOf(d to 700L), currency = "USD"))
            repository.saveExpense(Expense("b-owes-d", groupId, "Third expense", 300, d, mapOf(b to 300L), currency = "USD"))
            assertTrue(repository.state.value.groups.single().simplifyDebts)
            // Keeping three direct repayments is shorter than the four greedy transfers.
            // D owes E 700 directly although D's net debt is only 400.
            val suggestions = org.fairshare.app.domain.Ledger.transfers(repository.state.value, groupId, "USD")
            assertEquals(3, suggestions.size)
            assertTrue(suggestions.any { it.fromId == d && it.toId == e && it.amountMinor == 700L })
            repository.saveSettlement(Settlement("fallback-d-e", groupId, d, e, 700, currency = "USD"))
            repository.saveSettlement(Settlement("fallback-c-self", groupId, c, SELF_ID, 200, currency = "USD"))
            repository.saveSettlement(Settlement("fallback-b-d", groupId, b, d, 300, currency = "USD"))
            assertTrue(org.fairshare.app.domain.Ledger.balances(repository.state.value, groupId, "USD").values.all { it == 0L })
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun directRepaymentsAllowNetZeroIntermediariesAndBlockOverpayment() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "direct-repayment-${UUID.randomUUID()}.db"
        val repository = LocalRepository(context, name)
        try {
            val groupId = repository.createGroup("Direct debts", "USD", listOf("Alice", "Bob"))
            val group = repository.state.value.groups.single()
            val alice = group.memberIds[1]
            val bob = group.memberIds[2]
            // Alice owes you, Bob owes Alice: Alice has zero net but still has direct debt.
            repository.saveExpense(Expense("paid-self", groupId, "Tickets", 1000, SELF_ID, mapOf(alice to 1000L), currency = "USD"))
            repository.saveExpense(Expense("paid-alice", groupId, "Hotel", 1000, alice, mapOf(bob to 1000L), currency = "USD"))
            repository.setSimplifyDebts(groupId, false)
            var rejected = false
            try { repository.removeGroupMember(groupId, alice) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            repository.saveSettlement(Settlement("direct-alice-self", groupId, alice, SELF_ID, 1000, currency = "USD"))
            rejected = false
            try { repository.saveSettlement(Settlement("overpaid", groupId, bob, alice, 1001, currency = "USD")) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            repository.saveSettlement(Settlement("direct-bob-alice", groupId, bob, alice, 1000, currency = "USD"))
            repository.removeGroupMember(groupId, alice)
            assertTrue(alice in repository.state.value.groups.single().removedMemberIds)
            rejected = false
            try { repository.deleteSettlement("direct-alice-self") } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Historical changes must not reopen removed member debt", rejected)
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun settledRemovalPreservesHistoryReactivatesAndHiddenGroupsAreReadOnly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "group-lifecycle-${UUID.randomUUID()}.db"
        var repository = LocalRepository(context, name)
        try {
            val groupId = repository.createGroup("Trip", "USD", listOf("Alice", "Bob"))
            val alice = repository.state.value.groups.single().memberIds[1]
            val expense = Expense("lifecycle-dinner", groupId, "Dinner", 2000, SELF_ID, mapOf(SELF_ID to 1000L, alice to 1000L), currency = "EUR")
            repository.saveExpense(expense)
            val savedExpense = repository.state.value.expenses.single()
            suspend fun rejected(block: suspend () -> Unit) {
                var didReject = false
                try { block() } catch (_: IllegalArgumentException) { didReject = true }
                assertTrue("Expected the operation to be rejected", didReject)
            }
            rejected { repository.removeGroupMember(groupId, alice) }
            rejected { repository.setGroupVisibility(groupId, GroupVisibility.LEFT) }
            rejected { repository.setGroupVisibility(groupId, GroupVisibility.DELETED) }
            repository.saveSettlement(Settlement("lifecycle-payment", groupId, alice, SELF_ID, 1000, currency = "EUR"))
            repository.setExpenseDefaults(groupId, ExpenseDefaults(participantIds = listOf(SELF_ID, alice)))
            repository.removeGroupMember(groupId, alice)
            var group = repository.state.value.groups.single()
            assertTrue(alice in group.memberIds)
            assertFalse(alice in group.activeMemberIds)
            assertNull(group.expenseDefaults)
            assertEquals(savedExpense, repository.state.value.expenses.single())
            rejected { repository.saveExpense(expense.copy(id = "new-with-removed-person")) }
            repository.saveExpense(expense.copy(notes = "Historical edit remains available"))
            repository.addMembers(groupId, listOf(alice), emptyList())
            group = repository.state.value.groups.single()
            assertTrue(alice in group.activeMemberIds)
            assertEquals(group.memberIds.distinct(), group.memberIds)
            repository.setSimplifyDebts(groupId, false)
            repository.setExpenseDefaults(groupId, ExpenseDefaults(participantIds = listOf(SELF_ID, alice)))
            rejected { repository.setExpenseDefaults(groupId, ExpenseDefaults(participantIds = listOf(SELF_ID), splitMode = SplitMode.PERCENT, values = mapOf(SELF_ID to "30"))) }
            repository.setGroupVisibility(groupId, GroupVisibility.DELETED)
            rejected { repository.deleteExpense(expense.id) }
            rejected { repository.deleteSettlement("lifecycle-payment") }
            rejected { repository.saveExpense(expense.copy(notes = "Cannot edit hidden history")) }
            val saved = repository.state.value
            repository.close()
            repository = LocalRepository(context, name)
            repository.load()
            assertEquals(saved, repository.state.value)
            repository.setGroupVisibility(groupId, GroupVisibility.ACTIVE)
            assertFalse(repository.state.value.groups.single().isHidden)
            assertFalse(repository.state.value.groups.single().simplifyDebts)
            assertEquals(1, repository.state.value.expenses.size)
            assertEquals(1, repository.state.value.settlements.size)
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }
}
