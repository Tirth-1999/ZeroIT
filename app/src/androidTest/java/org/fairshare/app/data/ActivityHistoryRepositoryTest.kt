package org.fairshare.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import com.google.gson.Gson
import org.fairshare.app.model.*
import org.fairshare.app.platform.nowMillis
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ActivityHistoryRepositoryTest {
    @Test fun auditedLegacyLinkIsRepairedOnceAndPersistsAcrossReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureName = "activity-history-fixture-${UUID.randomUUID()}.db"
        val name = "activity-history-repair-${UUID.randomUUID()}.db"
        val fixture = LocalRepository(context, fixtureName)
        val database = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        val codec = Gson()
        try {
            fixture.setProfile("Taylor")
            val groupId = fixture.createGroup("Trip", "USD", listOf("Alice"))
            val beforeWrite = fixture.state.value
            val members = beforeWrite.groups.single().memberIds
            fixture.saveExpense(Expense("audited-expense", groupId, "Lunch", 2000, SELF_ID, members.associateWith { 1000L }))
            val expected = fixture.state.value
            val eventId = expected.activity.last().id
            val legacy = expected.copy(activityLinkVersion = 0, activity = expected.activity.map { it.copy(expenseId = null, settlementId = null) })
            database.stateDao().commitIfUnchanged(null, StateRow(json = codec.toJson(legacy)), AuditRevision(
                action = "save_expense", at = expected.activity.last().at,
                beforeJson = codec.toJson(beforeWrite), afterJson = codec.toJson(legacy),
            ))
            val countBeforeRepair = database.stateDao().revisionCount()
            val reader = SharedRepository(database)
            reader.load()
            assertEquals("audited-expense", reader.state.value.activity.single { it.id == eventId }.expenseId)
            assertEquals(expected, reader.state.value)
            assertEquals(countBeforeRepair + 1, database.stateDao().revisionCount())
            reader.refresh()
            assertEquals(countBeforeRepair + 1, database.stateDao().revisionCount())
            assertEquals(expected, codec.fromJson(database.stateDao().read()!!.json, AppState::class.java))
            database.close()
            val reopened = LocalRepository(context, name)
            try {
                reopened.load()
                assertEquals(expected, reopened.state.value)
            } finally { reopened.close() }
            val inspector = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
            try { assertEquals(countBeforeRepair + 1, inspector.stateDao().revisionCount()) }
            finally { inspector.close() }
        } finally {
            fixture.close()
            database.close()
            context.deleteDatabase(fixtureName)
            context.deleteDatabase(name)
        }
    }

    @Test fun creationTimesAndExplicitActivityLinksSurviveEditsDeletesAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "activity-history-${UUID.randomUUID()}.db"
        var repository = LocalRepository(context, name)
        try {
            repository.setProfile("Taylor")
            val groupId = repository.createGroup("Trip", "USD", listOf("Alice"))
            val alice = repository.state.value.members.single { it.name == "Alice" }.id
            val before = nowMillis()
            repository.saveExpense(Expense("e", groupId, "Lunch", 5000, SELF_ID, mapOf(alice to 5000), date = 0, createdAt = 0))
            val added = repository.state.value.expenses.single()
            assertTrue(added.createdAt in before..nowMillis())
            assertEquals(0, added.date)
            val originalAudit = repository.state.value.activity
            assertEquals("e", originalAudit.last().expenseId)
            repository.saveExpense(added.copy(title = "Late lunch", date = 86400000, createdAt = 1))
            assertEquals(added.createdAt, repository.state.value.expenses.single().createdAt)
            assertEquals(originalAudit, repository.state.value.activity.take(originalAudit.size))
            repository.saveSettlement(Settlement("p", groupId, alice, SELF_ID, 500, date = 0, createdAt = 0))
            val payment = repository.state.value.settlements.single()
            assertEquals("p", repository.state.value.activity.last().settlementId)
            assertTrue(payment.createdAt >= before)
            repository.saveSettlement(payment.copy(amountMinor = 400, createdAt = 1))
            assertEquals(payment.createdAt, repository.state.value.settlements.single().createdAt)
            assertEquals("p", repository.state.value.activity.last().settlementId)
            repository.deleteSettlement("p")
            assertEquals("p", repository.state.value.activity.last().settlementId)
            assertNull(repository.state.value.activity.last().expenseId)
            val auditBeforeDelete = repository.state.value.activity
            repository.deleteExpense("e")
            assertEquals(auditBeforeDelete, repository.state.value.activity.take(auditBeforeDelete.size))
            assertEquals("e", repository.state.value.activity.last().expenseId)
            assertTrue(repository.state.value.expenses.single().deleted)
            repository.restoreExpense("e")
            assertEquals(added.createdAt, repository.state.value.expenses.single().createdAt)
            val expected = repository.state.value
            repository.close()
            repository = LocalRepository(context, name)
            repository.load()
            assertEquals(expected, repository.state.value)
        } finally { repository.close(); context.deleteDatabase(name) }
    }
}
