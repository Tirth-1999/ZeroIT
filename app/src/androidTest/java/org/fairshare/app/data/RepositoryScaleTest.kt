package org.fairshare.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Large current-state persistence, not 2,016 artificial full-snapshot audit writes. */
@RunWith(AndroidJUnit4::class)
class RepositoryScaleTest {
    @Test fun largeThreeCurrencyLedgerMutationCommitsOneAuditAndSurvivesRefreshAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "repository-scale-${UUID.randomUUID()}.db"
        val codec = Gson()
        val currencies = listOf("USD", "JPY", "KWD")
        val people = listOf(Member(SELF_ID, "Taylor")) + (1..59).map { Member("person-$it", "Person $it") }
        val memberIds = people.map { it.id }
        val start = LocalDate.of(2024, 11, 1)
        val expenses = (0 until 2_016).map { index ->
            val at = start.plusMonths((index / 84).toLong()).plusDays(((index % 84) / 3).toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            Expense("large-$index", "scale", "Historical expense $index", 6000,
                memberIds[index % 60], memberIds.associateWith { 100L }, date = at,
                currency = currencies[index % 3], createdAt = at)
        }
        val initial = AppState("Taylor", people, listOf(Group("scale", "Two years of history", "USD", memberIds)),
            expenses = expenses, activity = expenses.map { Activity("event-${it.id}", "scale", "Added ${it.title}", it.createdAt, expenseId = it.id) },
            onboardingComplete = true, activityLinkVersion = 1)
        val seedJson = codec.toJson(initial)
        assertTrue("The fixture must exceed Android's conventional 2 MiB cursor row budget", seedJson.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024)
        // Intentionally create/write with the old SupportSQLite path, then open
        // this exact file through production's bundled driver. No export/reset.
        val seed = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        try { seed.stateDao().write(StateRow(json = seedJson)) } finally { seed.close() }
        var repository = LocalRepository(context, name)
        try {
            repository.load()
            assertEquals(initial, repository.state.value)
            val original = initial.expenses.first()
            val edited = original.copy(title = "Reconciled historical expense", amountMinor = 12_000,
                owed = memberIds.associateWith { 200L })
            repository.saveExpense(edited)
            val expected = repository.state.value
            assertEquals(60, expected.groups.single().activeMemberIds.size)
            assertEquals(2_016, expected.expenses.size)
            assertEquals(2_017, expected.activity.size)
            assertEquals(edited, expected.expenses.first())
            assertEquals(initial.expenses.drop(1), expected.expenses.drop(1))
            assertEquals(initial.activity, expected.activity.take(initial.activity.size))
            assertEquals(original.id, expected.activity.last().expenseId)
            assertEquals(initial.members, expected.members)
            assertEquals(initial.groups, expected.groups)
            assertEquals(initial.settlements, expected.settlements)
            assertEquals(currencies, Ledger.groupCurrencies(expected, "scale"))
            val expectedBalances = currencies.associateWith { Ledger.balances(expected, "scale", it) }
            val initialUsd = Ledger.balances(initial, "scale", "USD")
            assertEquals(initialUsd.mapValues { (id, balance) -> balance + if (id == original.payerId) 5900L else -100L }, expectedBalances.getValue("USD"))
            for (currency in currencies) {
                assertEquals(0L, expectedBalances.getValue(currency).values.sum())
                if (currency != "USD") assertEquals(Ledger.balances(initial, "scale", currency), expectedBalances.getValue(currency))
            }
            val inspector = bundledDatabase(context, name)
            try {
                assertEquals("One operation produces exactly one complete audit revision", 1, inspector.stateDao().revisionCount())
                val revision = inspector.stateDao().transactionRevisions().single()
                assertEquals("save_expense", revision.action)
                assertEquals(initial, codec.fromJson(revision.beforeJson, AppState::class.java))
                assertEquals(expected, codec.fromJson(revision.afterJson, AppState::class.java))
                assertEquals(expected, codec.fromJson(inspector.stateDao().read()!!.json, AppState::class.java))
            } finally { inspector.close() }
            repository.refresh()
            assertEquals(expected, repository.state.value)
            repository.close()
            repository = LocalRepository(context, name)
            repository.load()
            repository.refresh()
            assertEquals(expected, repository.state.value)
            currencies.forEach { assertEquals(expectedBalances.getValue(it), Ledger.balances(repository.state.value, "scale", it)) }
            val reopened = bundledDatabase(context, name)
            try {
                assertEquals("Read, refresh and reopen must not add audit revisions", 1, reopened.stateDao().revisionCount())
                assertEquals(expected, codec.fromJson(reopened.stateDao().read()!!.json, AppState::class.java))
            } finally { reopened.close() }
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    private fun bundledDatabase(context: Context, name: String): LocalDatabase =
        Room.databaseBuilder(context, LocalDatabase::class.java, name)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
}
