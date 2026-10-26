package org.fairshare.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.fairshare.app.model.Expense
import org.fairshare.app.model.SELF_ID
import org.fairshare.app.model.Settlement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.TimeZone
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class LocalRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun refreshRereadsSavedStateWithoutWritingAndPreservesStateOnCorruption() = runBlocking {
        val name = "refresh-${UUID.randomUUID()}.db"
        val reader = LocalRepository(context, name)
        val writer = LocalRepository(context, name)
        val database = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        try {
            reader.setProfile("Taylor")
            writer.load()
            writer.setProfile("Blair")
            reader.load()
            assertEquals("Taylor", reader.state.value.profileName)
            val revisions = database.stateDao().revisionCount()
            reader.refresh()
            assertEquals(writer.state.value, reader.state.value)
            assertEquals(revisions, database.stateDao().revisionCount())
            val valid = reader.state.value
            database.stateDao().write(StateRow(json = "{broken"))
            var failed = false
            try { reader.refresh() } catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
            assertEquals(valid, reader.state.value)
            assertEquals("{broken", database.stateDao().read()!!.json)
            assertEquals(revisions, database.stateDao().revisionCount())
        } finally {
            reader.close()
            writer.close()
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun auditCalendarDatesUseUtcRegardlessOfDeviceTimeZone() = runBlocking {
        val name = "repository-${UUID.randomUUID()}.db"
        val repository = LocalRepository(context, name)
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            repository.setProfile("Taylor")
            val groupId = repository.createGroup("Trip", "USD", listOf("Alice"))
            val members = repository.state.value.groups.single().memberIds
            val date = LocalDate.of(2026, 10, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            repository.saveExpense(Expense("dated-expense", groupId, "Dinner", 3000, SELF_ID, members.associateWith { 1500L }, date = date))
            assertTrue(repository.state.value.activity.last().message.contains("Oct 20, 2026"))
            repository.saveSettlement(Settlement("dated-payment", groupId, members[1], SELF_ID, 400, date = date))
            assertTrue(repository.state.value.activity.last().message.contains("Oct 20, 2026"))
        } finally {
            TimeZone.setDefault(originalTimeZone)
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun committedLedgerAndAuditSurviveRepositoryRecreation() = runBlocking {
        val name = "repository-${UUID.randomUUID()}.db"
        var repository = LocalRepository(context, name)
        try {
            repository.load()
            assertFalse(repository.state.value.onboardingComplete)
            assertTrue(repository.state.value.groups.isEmpty())
            repository.setProfile("Taylor")
            val groupId = repository.createGroup("Trip", "USD", listOf("Alice", "Bob"))
            val members = repository.state.value.groups.single().memberIds
            val alice = members[1]
            val expense = Expense("dinner", groupId, "Dinner", 3000, SELF_ID, members.associateWith { 1000L }, notes = "Shared meal")
            repository.saveExpense(expense)
            repository.saveSettlement(Settlement("payment", groupId, alice, SELF_ID, 400))
            repository.saveExpense(expense.copy(title = "Dinner and tea", notes = "Updated note"))
            repository.deleteExpense(expense.id)
            assertTrue(repository.state.value.expenses.single().deleted)
            repository.restoreExpense(expense.id)
            repository.deleteSettlement("payment")
            repository.setTheme("DARK")
            val expected = repository.state.value
            repository.close()
            repository = LocalRepository(context, name)
            repository.load()
            assertEquals(expected, repository.state.value)
            assertEquals("Taylor", repository.state.value.members.first { it.id == SELF_ID }.name)
            assertEquals(7, repository.state.value.activity.size)
            val database = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
            try {
                assertEquals(9, database.stateDao().revisionCount())
            } finally { database.close() }
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun invalidChangesDoNotCommitAndConcurrentWritesAreNotLost() = runBlocking {
        val name = "repository-${UUID.randomUUID()}.db"
        val repository = LocalRepository(context, name)
        try {
            repository.setProfile("Taylor")
            val groupId = repository.createGroup("Trip", "USD", listOf("Alice"))
            val members = repository.state.value.groups.single().memberIds
            val expense = Expense("dinner", groupId, "Dinner", 3000, SELF_ID, members.associateWith { 1500L })
            repository.saveExpense(expense)
            val before = repository.state.value
            expectRejected { repository.saveExpense(expense.copy(owed = mapOf(SELF_ID to 1L))) }
            expectRejected { repository.saveExpense(expense.copy(payerId = "stranger")) }
            expectRejected { repository.addMember(groupId, "alice") }
            expectRejected { repository.setTheme("PURPLE") }
            expectRejected { repository.saveSettlement(Settlement("too-much", groupId, members[1], SELF_ID, 1501)) }
            expectRejected { repository.saveSettlement(Settlement("wrong-way", groupId, SELF_ID, members[1], 1)) }
            assertEquals(before, repository.state.value)
            coroutineScope {
                (1..8).map { index -> async { repository.addMember(groupId, "Guest $index") } }.awaitAll()
            }
            assertEquals(10, repository.state.value.groups.single().memberIds.size)
            repository.loadDemo()
            repository.loadDemo()
            assertEquals(2, repository.state.value.groups.size)
            assertEquals(2, repository.state.value.expenses.size)
            val demoDate = repository.state.value.expenses.single { it.id == "demo-dinner" }.date
            assertEquals(java.time.LocalDate.now(), java.time.Instant.ofEpochMilli(demoDate).atZone(java.time.ZoneOffset.UTC).toLocalDate())
            assertEquals(0L, demoDate % 86_400_000L)
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun selectedPeopleAreReusedAndMultipleMembersPersistWithoutChangingExpenseShares() = runBlocking {
        val name = "repository-${UUID.randomUUID()}.db"
        var repository = LocalRepository(context, name)
        try {
            repository.setProfile("Taylor")
            val firstGroup = repository.createGroup("Home", "USD", listOf("Alice", "Bob"))
            val alice = repository.state.value.members.single { it.name == "Alice" }.id
            val bob = repository.state.value.members.single { it.name == "Bob" }.id
            val secondGroup = repository.createGroup("Trip", "EUR", listOf("Charlie"), listOf(alice))
            val expense = Expense("original-shares", secondGroup, "Lunch", 1000, SELF_ID, mapOf(SELF_ID to 500, alice to 500))
            repository.saveExpense(expense)
            val savedExpense = repository.state.value.expenses.single()
            repository.addMembers(secondGroup, listOf(bob), listOf("Dana", "Eli"))
            val state = repository.state.value
            assertEquals(listOf(SELF_ID, alice, bob), state.groups.single { it.id == firstGroup }.memberIds)
            val tripIds = state.groups.single { it.id == secondGroup }.memberIds
            assertEquals(6, tripIds.size)
            assertTrue(tripIds.containsAll(listOf(SELF_ID, alice, bob)))
            assertEquals(1, state.members.count { it.id == alice })
            assertEquals(1, state.members.count { it.id == bob })
            assertEquals(savedExpense, state.expenses.single())
            assertEquals("Added members Bob, Dana, Eli", state.activity.last().message)
            repository.close()
            repository = LocalRepository(context, name)
            repository.load()
            assertEquals(state, repository.state.value)
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun rejectedMemberBatchesLeaveStateAndAuditUnchanged() = runBlocking {
        val name = "repository-${UUID.randomUUID()}.db"
        var repository = LocalRepository(context, name)
        val database = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        try {
            repository.setProfile("Taylor")
            val home = repository.createGroup("Home", "USD", listOf("Alice", "Bob"))
            val alice = repository.state.value.members.single { it.name == "Alice" }.id
            val bob = repository.state.value.members.single { it.name == "Bob" }.id
            val trip = repository.createGroup("Trip", "USD", emptyList(), listOf(alice))
            val before = repository.state.value
            val revisions = database.stateDao().revisionCount()
            expectRejected { repository.addMembers(trip, listOf(bob, bob), listOf("Charlie")) }
            expectRejected { repository.addMembers(trip, listOf(SELF_ID), listOf("Charlie")) }
            expectRejected { repository.addMembers(trip, listOf(alice), listOf("Charlie")) }
            expectRejected { repository.addMembers(trip, listOf("missing"), listOf("Charlie")) }
            expectRejected { repository.addMembers(trip, listOf(bob), listOf("Charlie", "charlie")) }
            expectRejected { repository.addMembers(trip, listOf(bob), listOf("Bob")) }
            expectRejected { repository.addMembers(trip, listOf(bob), listOf(" Alice ")) }
            expectRejected { repository.addMembers(trip, emptyList(), listOf("Bob")) }
            expectRejected { repository.addMembers(home, emptyList(), listOf("New person", "Taylor")) }
            expectRejected { repository.createGroup("Invalid group", "USD", listOf("New person"), listOf(SELF_ID)) }
            expectRejected { repository.createGroup("Invalid group", "USD", listOf("Bob"), listOf(bob)) }
            expectRejected { repository.addMembers(trip, emptyList(), (1..99).map { "Guest $it" }) }
            repository.addMembers(trip, emptyList(), emptyList())
            assertEquals(before, repository.state.value)
            assertEquals(revisions, database.stateDao().revisionCount())
            repository.close()
            repository = LocalRepository(context, name)
            repository.load()
            assertEquals(before, repository.state.value)
        } finally {
            database.close()
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun malformedSavedStateIsPreservedAndReported() = runBlocking {
        val name = "repository-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        database.stateDao().write(StateRow(json = "{invalid json"))
        database.close()
        val repository = LocalRepository(context, name)
        try {
            var failed = false
            try { repository.load() } catch (_: IllegalStateException) { failed = true }
            assertTrue("A corrupt database must surface a load error", failed)
            val verify = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
            try { assertEquals("{invalid json", verify.stateDao().read()!!.json) } finally { verify.close() }
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun transactionCurrencyAndMultiplePayersPersistAndSettlementCannotConsumeAnotherCurrency() = runBlocking {
        val name = "currency-${UUID.randomUUID()}.db"
        var repository = LocalRepository(context, name)
        try {
            repository.setProfile("Taylor")
            val group = repository.createGroup("World trip", "USD", listOf("Alice"))
            val alice = repository.state.value.members.single { it.name == "Alice" }.id
            repository.saveExpense(Expense("usd", group, "Hotel", 2000, SELF_ID, mapOf(alice to 2000)))
            repository.saveExpense(Expense("eur", group, "Dinner", 3000, alice, mapOf(SELF_ID to 1500, alice to 1500), currency = "EUR", paidBy = mapOf(SELF_ID to 500, alice to 2500)))
            val before = repository.state.value
            expectRejected { repository.saveSettlement(Settlement("wrong-currency", group, alice, SELF_ID, 1, currency = "EUR")) }
            expectRejected { repository.saveExpense(before.expenses.last().copy(paidBy = mapOf(alice to 2999))) }
            expectRejected { repository.saveExpense(before.expenses.last().copy(currency = "FAKE")) }
            assertEquals(before, repository.state.value)
            repository.saveSettlement(Settlement("euro-payment", group, SELF_ID, alice, 500, currency = "EUR"))
            assertEquals(mapOf(SELF_ID to 2000L, alice to -2000L), org.fairshare.app.domain.Ledger.balances(repository.state.value, group, "USD"))
            assertEquals(mapOf(SELF_ID to -500L, alice to 500L), org.fairshare.app.domain.Ledger.balances(repository.state.value, group, "EUR"))
            val expected = repository.state.value
            repository.close()
            repository = LocalRepository(context, name)
            repository.load()
            assertEquals(expected, repository.state.value)
            assertTrue(repository.state.value.activity.last().message.contains("€5.00"))
        } finally { repository.close(); context.deleteDatabase(name) }
    }

    @Test fun nonGroupLedgersReuseParticipantSetsAndPreserveIndependentTransactions() = runBlocking {
        val name = "non-group-${UUID.randomUUID()}.db"
        var repository = LocalRepository(context, name)
        try {
            repository.setProfile("Taylor")
            val alice = repository.addFriend("Alice")
            val bob = repository.addFriend("Bob")
            val direct = repository.ensureDirectGroup(alice, "USD")
            assertEquals(direct, repository.ensureNonGroupLedger(listOf(alice, SELF_ID), "USD"))
            val multi = repository.ensureNonGroupLedger(listOf(bob, SELF_ID, alice), "EUR")
            assertEquals(multi, repository.ensureNonGroupLedger(listOf(alice, bob, SELF_ID), "EUR"))
            assertTrue(repository.state.value.groups.single { it.id == multi }.isNonGroup)
            assertFalse(repository.state.value.groups.single { it.id == multi }.isDirect)
            repository.saveExpense(Expense("non-group-meal", multi, "Meal", 3000, SELF_ID, mapOf(SELF_ID to 1000, alice to 1000, bob to 1000), currency = "JPY"))
            val expected = repository.state.value
            expectRejected { repository.ensureNonGroupLedger(listOf(alice, bob), "EUR") }
            expectRejected { repository.ensureNonGroupLedger(listOf(SELF_ID, alice, alice), "EUR") }
            expectRejected { repository.ensureNonGroupLedger(listOf(SELF_ID, "missing"), "EUR") }
            expectRejected { repository.addMember(multi, "Charlie") }
            assertEquals(expected, repository.state.value)
            repository.close()
            repository = LocalRepository(context, name)
            repository.load()
            assertEquals(expected, repository.state.value)
        } finally { repository.close(); context.deleteDatabase(name) }
    }

    private suspend fun expectRejected(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue("Invalid changes must be rejected", failed)
    }

    @Test fun friendIdentityAndDirectLedgersAreReusableAtomicAndPersistent() = runBlocking {
        val name = "repository-${UUID.randomUUID()}.db"
        var repository = LocalRepository(context, name)
        try {
            repository.setProfile("Taylor")
            val home = repository.createGroup("Home", "USD", listOf("Alice"))
            val alice = repository.state.value.members.single { it.name == "Alice" }.id
            assertEquals(alice, repository.addFriend(" ALICE "))
            val bob = repository.addFriend("Bob")
            assertEquals(bob, repository.addFriend("bob"))
            val direct = repository.ensureDirectGroup(alice, "USD")
            assertEquals(direct, repository.ensureDirectGroup(alice, "USD"))
            val euroDirect = repository.ensureDirectGroup(alice, "EUR")
            assertNotEquals(direct, euroDirect)
            val directGroup = repository.state.value.groups.single { it.id == direct }
            assertTrue(directGroup.isDirect)
            assertEquals(listOf(SELF_ID, alice), directGroup.memberIds)
            assertFalse(repository.state.value.groups.single { it.id == home }.isDirect)
            repository.saveExpense(Expense("friend-lunch", direct, "Lunch", 1000, SELF_ID, mapOf(SELF_ID to 500, alice to 500)))
            repository.saveSettlement(Settlement("friend-payment", direct, alice, SELF_ID, 200))
            assertEquals(mapOf("USD" to 300L, "EUR" to 0L), org.fairshare.app.domain.Ledger.friendBalances(repository.state.value, alice))
            val before = repository.state.value
            expectRejected { repository.addFriend("taylor") }
            expectRejected { repository.addFriend("  ") }
            expectRejected { repository.ensureDirectGroup(SELF_ID, "USD") }
            expectRejected { repository.ensureDirectGroup("missing", "USD") }
            expectRejected { repository.ensureDirectGroup(alice, "INVALID") }
            expectRejected { repository.addMembers(direct, listOf(bob), emptyList()) }
            assertEquals(before, repository.state.value)
            coroutineScope {
                (1..5).map { async { repository.ensureDirectGroup(bob, "USD") } }.awaitAll().also { ids -> assertEquals(1, ids.distinct().size) }
            }
            val expected = repository.state.value
            repository.close()
            repository = LocalRepository(context, name)
            repository.load()
            assertEquals(expected, repository.state.value)
            assertEquals(direct, repository.ensureDirectGroup(alice, "USD"))
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun snapshotsWithoutGroupKindRemainReadableAsRegularGroups() = runBlocking {
        val name = "repository-${UUID.randomUUID()}.db"
        var repository = LocalRepository(context, name)
        try {
            repository.setProfile("Taylor")
            val home = repository.createGroup("Home", "USD", listOf("Alice"))
            val expected = repository.state.value
            repository.close()
            val database = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
            try {
                val row = database.stateDao().read()!!
                val json = com.google.gson.JsonParser.parseString(row.json).asJsonObject
                json.getAsJsonArray("groups").forEach { it.asJsonObject.remove("kind") }
                database.stateDao().write(row.copy(json = json.toString()))
            } finally { database.close() }
            repository = LocalRepository(context, name)
            repository.load()
            assertFalse(repository.state.value.groups.single().isDirect)
            assertEquals(expected.groups.single().copy(kind = null), repository.state.value.groups.single())
            repository.addMember(home, "Bob")
            assertEquals(3, repository.state.value.groups.single().memberIds.size)
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }
}
