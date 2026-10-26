package org.fairshare.app

import kotlin.test.*
import org.fairshare.app.data.withDemoScenarios
import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.*

class DemoScenariosTest {
    @Test fun demosAreAdditiveIdempotentAndBalanced() {
        val existing = AppState(profileName = "Taylor", members = listOf(Member(SELF_ID, "Taylor")),
            groups = listOf(Group("existing", "My group", memberIds = listOf(SELF_ID))), themeMode = "DARK", onboardingComplete = true)
        val seeded = existing.withDemoScenarios()
        assertEquals(8, seeded.groups.size)
        assertEquals(47, seeded.expenses.size)
        assertEquals(2, seeded.settlements.size)
        assertEquals(existing.groups.first(), seeded.groups.first())
        assertEquals("Taylor", seeded.profileName)
        assertEquals("DARK", seeded.themeMode)
        assertEquals(seeded, seeded.withDemoScenarios())
        seeded.expenses.forEach { assertEquals(it.amountMinor, it.owed.values.sum()) }
        seeded.groups.forEach { assertEquals(0L, Ledger.balances(seeded, it.id).values.sum()) }
        assertTrue(Ledger.balances(seeded, "demo-scenarios-settled").values.all { it == 0L })
        // Reloading samples must preserve edits and deletions made while exploring them.
        val edited = seeded.copy(expenses = seeded.expenses.mapIndexed { index, expense -> if (index == 0) expense.copy(title = "My edited demo", deleted = true) else expense })
        assertEquals(edited, edited.withDemoScenarios())
    }
    @Test fun monthlySamplesAreCalendarBasedAndPreserveExistingEdits() {
        val initial = AppState(members = listOf(Member(SELF_ID, "Taylor")))
        val seeded = initial.withDemoScenarios()
        val groupId = "demo-scenarios-monthly-v1"
        val samples = seeded.expenses.filter { it.groupId == groupId }
        assertEquals(18, samples.size)
        val byMonth = samples.groupBy { org.fairshare.app.platform.calendarDateInput(it.date).take(7) }
        assertEquals(6, byMonth.size)
        byMonth.values.forEach { month ->
            assertEquals(3, month.size)
            assertEquals(setOf("USD", "EUR"), month.map { it.currency }.toSet())
            assertEquals(2, month.count { it.currency == "USD" })
        }
        val today = org.fairshare.app.platform.parseCalendarDate(org.fairshare.app.platform.todayDate())
        assertTrue(samples.all { it.date <= today })
        assertEquals(seeded, seeded.withDemoScenarios())
        val removedAndEdited = seeded.copy(expenses = seeded.expenses.filterNot { it.id == samples.first().id }.map {
            if (it.id == samples.last().id) it.copy(title = "My own title", deleted = true) else it
        })
        assertEquals(removedAndEdited, removedAndEdited.withDemoScenarios())
        samples.forEach { sample -> assertTrue(seeded.activity.any { it.expenseId == sample.id }) }
    }
}
