package org.fairshare.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.model.*
import org.fairshare.app.platform.parseCalendarDate
import org.fairshare.app.ui.FairshareApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BalanceToolsChartTest {
    @get:Rule val compose = createComposeRule()

    @Test fun groupAndFriendToolsFilterSortAndKeepIndependentChoices() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "balance-tools-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        try {
            lateinit var owe: String; lateinit var owed: String; lateinit var clear: String
            runBlocking {
                repository.setProfile("Taylor")
                owe = repository.createGroup("A dinner", "USD", listOf("Alice"))
                owed = repository.createGroup("B house", "USD", listOf("Bob"))
                clear = repository.createGroup("C settled", "USD", listOf("Charlie"))
                val alice = repository.state.value.groups.first { it.id == owe }.memberIds.first { it != SELF_ID }
                val bob = repository.state.value.groups.first { it.id == owed }.memberIds.first { it != SELF_ID }
                repository.saveExpense(Expense("debt", owe, "Dinner", 2000, alice, mapOf(SELF_ID to 1000, alice to 1000), currency = "USD"))
                repository.saveExpense(Expense("receivable", owed, "Rent", 4000, SELF_ID, mapOf(SELF_ID to 2000, bob to 2000), currency = "USD"))
            }
            val before = repository.state.value
            val alice = before.members.first { it.name == "Alice" }.id
            val bob = before.members.first { it.name == "Bob" }.id
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("home_balance_tools").performClick()
            compose.onNodeWithTag("balance_filter_OWE").performClick()
            compose.onNodeWithTag("balance_sort_LARGEST").performScrollTo().performClick()
            compose.onNodeWithTag("balance_sort_currency_USD").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("balance_tools_done").performClick()
            compose.onNodeWithTag("group_$owe").assertExists()
            compose.onNodeWithTag("group_$owed").assertDoesNotExist()
            compose.onNodeWithTag("group_$clear").assertDoesNotExist()
            compose.onNodeWithTag("nav_friends").performClick()
            compose.onNodeWithTag("friend_$bob").assertExists()
            compose.onNodeWithTag("home_balance_tools").performClick()
            compose.onNodeWithTag("balance_filter_OWED").performClick()
            compose.onNodeWithTag("balance_tools_done").performClick()
            compose.onNodeWithTag("friend_$bob").assertExists()
            compose.onNodeWithTag("friend_$alice").assertDoesNotExist()
            compose.onNodeWithTag("nav_groups").performClick()
            compose.onNodeWithTag("group_$owe").assertExists()
            compose.onNodeWithTag("group_$owed").assertDoesNotExist()
            compose.onNodeWithTag("home_balance_tools").performClick()
            compose.onNodeWithTag("balance_tools_reset").performClick()
            compose.onNodeWithTag("balance_tools_done").performClick()
            compose.onNodeWithTag("group_$owed").assertExists()
            assertEquals(before, repository.state.value)
        } finally { repository.close(); context.deleteDatabase(name) }
    }

    @Test fun verticalMonthlyChartSelectsMonthsAndSwitchesTotalStackedAndMembers() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "vertical-chart-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        try {
            lateinit var group: String
            runBlocking {
                repository.setProfile("Taylor")
                group = repository.createGroup("Monthly chart", "EUR", listOf("Alice"))
                val alice = repository.state.value.members.first { it.name == "Alice" }.id
                (5..10).forEach { month -> repository.saveExpense(Expense("month-$month", group, "Monthly groceries", month * 1000L, if (month % 2 == 0) SELF_ID else alice,
                    mapOf(SELF_ID to month * 500L, alice to month * 500L), parseCalendarDate("2026-${month.toString().padStart(2, '0')}-10"), currency = "EUR")) }
            }
            val before = repository.state.value
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("group_$group").performScrollTo().performClick()
            compose.onNodeWithTag("group_tab_totals").performScrollTo().performClick()
            compose.onNodeWithTag("chart_stacked_EUR").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("chart_month_2026-05-01_EUR").performScrollTo().assertContentDescriptionContains("May 2026, group total €50.00 EUR, your share €25.00 EUR, you paid €0.00 EUR", substring = true).performClick()
            compose.onNodeWithTag("chart_month_2026-05-01_EUR").assertIsSelected()
            compose.onNodeWithTag("chart_total_EUR").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("chart_month_2026-10-01_EUR").performScrollTo().performClick()
            compose.onNodeWithText("€10.00 more than Sep 2026").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("chart_members_EUR").performScrollTo().performClick()
            compose.onNodeWithTag("chart_member_self_EUR").performScrollTo().assertExists()
            assertEquals(before, repository.state.value)
        } finally { repository.close(); context.deleteDatabase(name) }
    }
}
