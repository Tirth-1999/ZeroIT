package org.fairshare.app

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.*
import org.fairshare.app.ui.FairshareApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun defaultsDebtControlsRemovalLeaveDeleteAndRecovery() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "controls-${System.nanoTime()}.db"
        val repository = LocalRepository(context, database)
        try {
            runBlocking { repository.setProfile("Taylor"); repository.createGroup("Trip", "USD", listOf("Alice", "Bob")) }
            val group = repository.state.value.groups.single()
            val alice = repository.state.value.members.single { it.name == "Alice" }.id
            val bob = repository.state.value.members.single { it.name == "Bob" }.id
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("group_${group.id}").performClick()
            compose.onNodeWithTag("group_settings").performClick()
            compose.onNodeWithTag("group_simplify_debts").performScrollTo().performClick()
            compose.onNodeWithTag("group_simplify_confirm").performClick()
            compose.waitUntil(5000) { !repository.state.value.groups.single().simplifyDebts }
            compose.onNodeWithTag("group_defaults").performScrollTo().performClick()
            compose.onNodeWithTag("default_payer_$alice").performScrollTo().performClick()
            compose.onNodeWithTag("default_mode_SHARES").performScrollTo().performClick()
            mapOf(SELF_ID to "1", alice to "2", bob to "1").forEach { (id, weight) -> compose.onNodeWithTag("default_value_$id").performScrollTo().performTextInput(weight) }
            compose.onNodeWithTag("defaults_save").performScrollTo().performClick()
            compose.waitUntil(5000) { repository.state.value.groups.single().expenseDefaults != null }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("add_expense").performClick()
            compose.onNodeWithTag("expense_title").performTextInput("Default shares")
            compose.onNodeWithTag("expense_amount").performTextInput("40.00")
            compose.onNodeWithTag("expense_currency").assertTextContains("USD", substring = true).performClick()
            compose.onNodeWithTag("expense_currency_search").performTextInput("USD")
            compose.onNodeWithTag("expense_currency_USD").performClick()
            // Group preference acceptance exercises the action semantics; the
            // physical footer/keyboard path is covered by ExpenseWizardTest.
            compose.onNodeWithTag("expense_next").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("split_mode_shares").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("split_mode_shares").assertIsSelected()
            compose.onNodeWithTag("expense_next").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("expense_save").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("expense_save").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5000) { repository.state.value.expenses.size == 1 }
            val expense = repository.state.value.expenses.single()
            assertEquals(alice, expense.payerId)
            assertEquals(mapOf(SELF_ID to 1000L, alice to 2000L, bob to 1000L), expense.owed)
            compose.onNodeWithTag("group_members").performClick()
            compose.onNodeWithTag("remove_member_$bob").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("member_balance_${bob}_USD").assertTextContains("owes", substring = true)
            runBlocking {
                Ledger.transfers(repository.state.value, group.id, "USD").forEachIndexed { i, t -> repository.saveSettlement(Settlement("p$i", group.id, t.fromId, t.toId, t.amountMinor, currency = "USD")) }
            }
            compose.onNodeWithTag("remove_member_$bob").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("member_remove_confirm").performClick()
            compose.waitUntil(5000) { bob in repository.state.value.groups.single().removedMemberIds }
            assertEquals(listOf(expense), repository.state.value.expenses)
            assertNull(repository.state.value.groups.single().expenseDefaults)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("group_settings").performClick()
            compose.onNodeWithTag("group_leave").performScrollTo().performClick()
            compose.onNodeWithTag("group_visibility_confirm").performClick()
            compose.waitUntil(5000) { repository.state.value.groups.single().visibility == GroupVisibility.LEFT }
            compose.onNodeWithTag("group_${group.id}").assertDoesNotExist()
            compose.onNodeWithTag("nav_account").performClick()
            compose.onNodeWithTag("restore_group_${group.id}").performScrollTo().performClick()
            compose.waitUntil(5000) { !repository.state.value.groups.single().isHidden }
            compose.onNodeWithTag("nav_groups").performClick()
            compose.onNodeWithTag("group_${group.id}").performClick()
            compose.onNodeWithTag("group_settings").performClick()
            compose.onNodeWithTag("group_delete").performScrollTo().performClick()
            compose.onNodeWithTag("group_visibility_confirm").performClick()
            compose.waitUntil(5000) { repository.state.value.groups.single().visibility == GroupVisibility.DELETED }
            compose.onNodeWithTag("nav_account").performClick()
            compose.onNodeWithTag("restore_group_${group.id}").performScrollTo().performClick()
            compose.waitUntil(5000) { !repository.state.value.groups.single().isHidden }
            assertEquals(listOf(expense), repository.state.value.expenses)
            assertTrue(Ledger.balances(repository.state.value, group.id).values.all { it == 0L })
        } finally { repository.close(); context.deleteDatabase(database) }
    }
}
