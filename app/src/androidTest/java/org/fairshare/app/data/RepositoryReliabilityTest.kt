package org.fairshare.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses isolated ledgers; never replaces or resets the interactive preview's database. */
@RunWith(AndroidJUnit4::class)
class RepositoryReliabilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun staleWriterCannotOverwriteAnotherSessionAndCanRetryAfterRefresh() = runBlocking {
        val name = "stale-writer-${UUID.randomUUID()}.db"
        val first = LocalRepository(context, name)
        val second = LocalRepository(context, name)
        val inspector = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        try {
            first.setProfile("Taylor")
            second.load()
            first.addFriend("Alice")
            val stale = second.state.value
            val committed = inspector.stateDao().read()
            val count = inspector.stateDao().revisionCount()
            val rejection = try { second.addFriend("Bob"); null } catch (failure: IllegalStateException) { failure }
            assertNotNull("A stale writer must explicitly fail", rejection)
            assertTrue(rejection!!.message.orEmpty().contains("Refresh"))
            assertEquals(stale, second.state.value)
            assertEquals(committed, inspector.stateDao().read())
            assertEquals(count, inspector.stateDao().revisionCount())
            second.refresh()
            second.addFriend("Bob")
            first.refresh()
            assertEquals(setOf("Taylor", "Alice", "Bob"), first.state.value.members.map { it.name }.toSet())
            assertEquals(second.state.value, first.state.value)
        } finally { first.close(); second.close(); inspector.close(); context.deleteDatabase(name) }
    }

    @Test fun failedAuditInsertRollsBackSnapshotAndLeavesRetryPossible() = runBlocking {
        val name = "failed-commit-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        val repository = LocalRepository(context, name)
        try {
            repository.setProfile("Taylor")
            val state = repository.state.value
            val row = database.stateDao().read()
            val count = database.stateDao().revisionCount()
            // Install a failure trigger through a compatible platform SQLite connection.
            // The actual production bundled driver must roll back state and audit together.
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_audit BEFORE INSERT ON audit_revisions BEGIN SELECT RAISE(ABORT, 'test audit failure'); END")
            var failed = false
            try { repository.addFriend("Alice") } catch (_: Exception) { failed = true }
            assertTrue(failed)
            assertEquals(state, repository.state.value)
            assertEquals(row, database.stateDao().read())
            assertEquals(count, database.stateDao().revisionCount())
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_audit")
            repository.addFriend("Alice")
            assertEquals(2, repository.state.value.members.size)
            assertEquals(count + 1, database.stateDao().revisionCount())
        } finally { repository.close(); database.close(); context.deleteDatabase(name) }
    }

    @Test fun informationalHistoryMigrationIsOneTimeAndMissingMarkerRemainsLegacyReadable() = runBlocking {
        val name = "link-marker-${UUID.randomUUID()}.db"
        val repository = LocalRepository(context, name)
        val database = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        try {
            repository.setProfile("Taylor")
            repository.createGroup("No transactions", "USD", emptyList())
            val original = repository.state.value
            assertEquals(1, original.activityLinkVersion)
            assertTrue(original.activity.all { it.expenseId == null && it.settlementId == null })
            val row = database.stateDao().read()!!
            val legacy = JsonParser.parseString(row.json).asJsonObject.apply { remove("activityLinkVersion") }
            database.stateDao().write(row.copy(json = legacy.toString()))
            val count = database.stateDao().revisionCount()
            repository.refresh()
            assertEquals(original, repository.state.value)
            assertEquals(count + 1, database.stateDao().revisionCount())
            repeat(3) { repository.refresh() }
            assertEquals(count + 1, database.stateDao().revisionCount())
            assertEquals(1, JsonParser.parseString(database.stateDao().read()!!.json).asJsonObject["activityLinkVersion"].asInt)
            val reopened = LocalRepository(context, name)
            try { reopened.load(); assertEquals(original, reopened.state.value) } finally { reopened.close() }
            assertEquals(count + 1, database.stateDao().revisionCount())
        } finally { repository.close(); database.close(); context.deleteDatabase(name) }
    }
}
