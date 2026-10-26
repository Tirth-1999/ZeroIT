package org.fairshare.app

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.data.Repository
import org.fairshare.app.model.*
import org.fairshare.app.platform.activityDateLabel
import org.fairshare.app.ui.FairshareApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deletedActivitySwipesExplainRestoreInBothFeedsWithoutChangingData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "deleted-swipe-hint-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        try {
            runBlocking {
                repository.setProfile("Taylor")
                val group = repository.createGroup("Deleted swipe", "USD", emptyList())
                repository.saveExpense(Expense("deleted-hint", group, "Lunch", 1000L, SELF_ID, mapOf(SELF_ID to 1000L)))
                repository.deleteExpense("deleted-hint")
            }
            val before = repository.state.value
            val deletion = before.activity.last()
            val added = before.activity.first { it.expenseId == "deleted-hint" }
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("nav_activity").performClick()
            fun swipeAndCheck(id: String, right: Boolean, message: String) {
                compose.scrollLedgerItemToView("activity_$id")
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                try {
                    compose.onNodeWithTag("activity_$id").performTouchInput { if (right) swipeRight() else swipeLeft() }
                    compose.mainClock.advanceTimeBy(700)
                    // Activity cards merge descendants for accessibility. Inspect
                    // the actual hint text leaf in the unmerged semantics tree.
                    compose.onNodeWithTag("activity_$id").assertIsDisplayed()
                    compose.onNodeWithTag("activity_swipe_hint_$id", useUnmergedTree = true)
                        .assertIsDisplayed().assertTextEquals(message)
                    compose.captureEvidence("deleted-swipe-$id-$right")
                } finally { compose.mainClock.autoAdvance = true }
            }
            swipeAndCheck(deletion.id, true, "Deleted expenses can’t be modified. Restore this expense first.")
            swipeAndCheck(added.id, false, "This expense was deleted. Restore it from its deletion entry in Activity.")
            assertEquals(before, repository.state.value)
            compose.onNodeWithTag("nav_groups").performClick()
            compose.onNodeWithTag("group_${before.groups.single().id}").performClick()
            compose.onNodeWithTag("group_tab_activity").performScrollTo().performClick()
            swipeAndCheck(deletion.id, false, "Deleted expenses can’t be modified. Restore this expense first.")
            compose.onNodeWithTag("activity_actions_${deletion.id}").performClick()
            compose.onNodeWithTag("activity_restore_${deletion.id}").assertIsEnabled()
            compose.onAllNodesWithTag("activity_edit_${deletion.id}").assertCountEquals(0)
            compose.onAllNodesWithTag("activity_delete_${deletion.id}").assertCountEquals(0)
            assertEquals(before, repository.state.value)
        } finally { repository.close(); context.deleteDatabase(db) }
    }

    @Test fun unlinkedInformationalActivityCannotChooseOrMutateOtherTransactions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "unlinked-activity-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        try {
            var groupId = ""
            runBlocking {
                repository.setProfile("Taylor")
                groupId = repository.createGroup("Original group", "USD", emptyList())
                val otherGroup = repository.createGroup("Other group", "USD", emptyList())
                repository.saveExpense(Expense("original", groupId, "Same description", 2000, SELF_ID, mapOf(SELF_ID to 2000)))
                repository.saveExpense(Expense("unrelated", otherGroup, "Same description", 2000, SELF_ID, mapOf(SELF_ID to 2000)))
            }
            val before = repository.state.value
            // This informational event has no transaction ID or recoverable audit link.
            // Matching prose and amounts in either group must never make it editable.
            val legacy = Activity("legacy-unlinked", groupId, "Added Same description")
            val fixture = object : Repository by repository {
                override val state = MutableStateFlow(before.copy(activity = listOf(legacy)))
            }
            compose.setContent { FairshareApp(fixture) }
            compose.onNodeWithTag("nav_activity").performClick()
            compose.scrollLedgerItemToView("activity_${legacy.id}")
            compose.onNodeWithTag("activity_${legacy.id}").performTouchInput { swipeRight() }
            assertUnlinkedActivityHasNoMutation(legacy.id)
            compose.scrollLedgerItemToView("activity_actions_${legacy.id}")
            compose.onNodeWithTag("activity_actions_${legacy.id}").performClick()
            compose.onNodeWithTag("activity_history_${legacy.id}").assertExists().assertIsNotEnabled()
            assertUnlinkedActivityHasNoMutation(legacy.id)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("nav_groups").performClick()
            compose.onNodeWithTag("group_$groupId").performClick()
            compose.onNodeWithTag("group_tab_activity").performScrollTo().performClick()
            compose.scrollLedgerItemToView("activity_${legacy.id}")
            compose.onNodeWithTag("activity_${legacy.id}").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("group_tab_activity").assertIsSelected()
            assertUnlinkedActivityHasNoMutation(legacy.id)
            compose.scrollLedgerItemToView("activity_actions_${legacy.id}")
            compose.onNodeWithTag("activity_actions_${legacy.id}").performClick()
            compose.onNodeWithTag("activity_history_${legacy.id}").assertExists().assertIsNotEnabled()
            assertUnlinkedActivityHasNoMutation(legacy.id)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            assertEquals(before, repository.state.value)
        } finally { repository.close(); context.deleteDatabase(db) }
    }

    @Test fun groupActivitySwipesAndOverflowPreserveHistoryAndReturnToGroup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "group-activity-actions-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        try {
            runBlocking {
                repository.setProfile("Taylor")
                val group = repository.createGroup("Group activity actions", "USD", listOf("Alice"))
                val members = repository.state.value.groups.single().memberIds
                repository.saveExpense(Expense("group-lunch", group, "Group lunch", 2000, SELF_ID, members.associateWith { 1000L }))
            }
            val before = repository.state.value
            val linked = before.activity.last()
            val row = "activity_${linked.id}"
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("group_${before.groups.single().id}").performClick()
            compose.onNodeWithTag("group_tab_activity").performScrollTo().performClick()
            compose.scrollLedgerItemToView(row)
            compose.onNodeWithTag(row).performTouchInput { swipeRight() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("expense_title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("expense_title").assertTextContains("Group lunch")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("expense_edit").assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("group_tab_activity").assertIsSelected()
            assertEquals(before, repository.state.value)
            compose.scrollLedgerItemToView(row)
            compose.onNodeWithTag(row).performTouchInput { swipeLeft() }
            compose.onNodeWithTag("activity_confirm_delete_${linked.id}").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(before, repository.state.value)
            compose.scrollLedgerItemToView("activity_actions_${linked.id}")
            compose.onNodeWithTag("activity_actions_${linked.id}").performClick()
            compose.onNodeWithTag("activity_edit_${linked.id}").assertExists()
            compose.onNodeWithTag("activity_delete_${linked.id}").performClick()
            performActivityMutation("activity_confirm_delete_${linked.id}", "Expense deleted")
            compose.waitUntil(5000) { repository.state.value.expenses.single().deleted }
            assertEquals(before.activity, repository.state.value.activity.take(before.activity.size))
            assertEquals(before.activity.size + 1, repository.state.value.activity.size)
            compose.onNodeWithTag("group_tab_activity").assertIsSelected()
            compose.onNodeWithTag("group_tab_expenses").performScrollTo().performClick()
            compose.onNodeWithTag("group_expense_group-lunch").assertDoesNotExist()
            compose.onNodeWithTag("group_tab_activity").performScrollTo().performClick()
            compose.onNodeWithTag(row).assertExists()
            val deletion = repository.state.value.activity.last()
            compose.scrollLedgerItemToView("activity_actions_${deletion.id}")
            compose.onNodeWithTag("activity_actions_${deletion.id}").performClick()
            performActivityMutation("activity_restore_${deletion.id}", "Expense restored")
            compose.waitUntil(5000) { !repository.state.value.expenses.single().deleted }
            assertEquals(before.activity, repository.state.value.activity.take(before.activity.size))
            assertEquals(before.activity.size + 2, repository.state.value.activity.size)
            compose.onNodeWithTag("group_tab_expenses").performScrollTo().performClick()
            compose.onNodeWithTag("group_expense_group-lunch").assertIsDisplayed()
        } finally { repository.close(); context.deleteDatabase(db) }
    }

    @Test fun swipesEditOrConfirmExpenseDeletionWhileActivityHistoryIsRetained() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "activity-actions-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        try {
            runBlocking {
                repository.setProfile("Taylor")
                val group = repository.createGroup("Activity actions", "USD", emptyList())
                repository.saveExpense(Expense("latest", group, "Backdated lunch", 5000, SELF_ID, mapOf(SELF_ID to 5000), date = 0, currency = "USD"))
            }
            val before = repository.state.value
            val linked = before.activity.last()
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("nav_activity").performClick()
            val row = "activity_${linked.id}"
            compose.scrollLedgerItemToView(row)
            compose.onNode(
                hasText(activityDateLabel(linked.at), substring = true) and hasAnyAncestor(hasTestTag(row)),
                useUnmergedTree = true,
            ).assertIsDisplayed()
            // A right swipe opens the linked expense editor, without changing the audit row.
            compose.onNodeWithTag(row).performTouchInput { swipeRight() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("expense_title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("expense_title").assertTextContains("Backdated lunch")
            assertEquals(before, repository.state.value)
            compose.onNodeWithContentDescription("Back").performClick()
            // Existing edit-cancel navigation returns through the expense detail.
            compose.onNodeWithTag("expense_edit").assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("activity_screen").assertExists()
            // A left swipe requests deletion; cancelling it performs no write.
            compose.scrollLedgerItemToView(row)
            compose.onNodeWithTag(row).performTouchInput { swipeLeft() }
            compose.onNodeWithTag("activity_confirm_delete_${linked.id}").assertExists()
            assertEquals(before, repository.state.value)
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(before, repository.state.value)
            // The visible overflow provides the same confirmed deletion as the gesture.
            compose.scrollLedgerItemToView("activity_actions_${linked.id}")
            compose.onNodeWithTag("activity_actions_${linked.id}").performClick()
            compose.onNodeWithTag("activity_edit_${linked.id}").assertExists()
            compose.onNodeWithTag("activity_delete_${linked.id}").performClick()
            performActivityMutation("activity_confirm_delete_${linked.id}", "Expense deleted")
            compose.waitUntil(5000) { repository.state.value.expenses.single().deleted }
            assertEquals(before.activity, repository.state.value.activity.take(before.activity.size))
            assertEquals(before.activity.size + 1, repository.state.value.activity.size)
            assertEquals("latest", repository.state.value.activity.last().expenseId)
            compose.scrollLedgerItemToView("activity_actions_${linked.id}")
            compose.onNodeWithTag("activity_actions_${linked.id}").assertExists().performClick()
            compose.onNodeWithTag("activity_history_${linked.id}").assertExists().assertIsNotEnabled()
            compose.onNodeWithTag("activity_edit_${linked.id}").assertDoesNotExist()
            compose.onNodeWithTag("activity_delete_${linked.id}").assertDoesNotExist()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.scrollLedgerItemToView(row)
            compose.onNodeWithTag(row).performTouchInput { swipeRight() }
            compose.onNodeWithTag("expense_title").assertDoesNotExist()
            compose.onNodeWithTag("activity_screen").assertExists()
            val deletion = repository.state.value.activity.last()
            compose.scrollLedgerItemToView("activity_actions_${deletion.id}")
            compose.onNodeWithTag("activity_actions_${deletion.id}").performClick()
            performActivityMutation("activity_restore_${deletion.id}", "Expense restored")
            compose.waitUntil(5000) { !repository.state.value.expenses.single().deleted }
            assertEquals(before.activity, repository.state.value.activity.take(before.activity.size))
            assertEquals(before.activity.size + 2, repository.state.value.activity.size)
            compose.onNodeWithTag("activity_screen").assertExists()
        } finally { repository.close(); context.deleteDatabase(db) }
    }

    @Test fun paymentActivitySwipesOpenExistingRecordAndRequireConfirmedDeletion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "payment-activity-actions-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        try {
            runBlocking {
                repository.setProfile("Taylor")
                val group = repository.createGroup("Payment activity", "USD", listOf("Alice"))
                val alice = repository.state.value.members.single { it.name == "Alice" }.id
                repository.saveExpense(Expense("debt", group, "Lunch", 5000, SELF_ID, mapOf(SELF_ID to 2500, alice to 2500), currency = "USD"))
                repository.saveSettlement(Settlement("payment", group, alice, SELF_ID, 1000, currency = "USD"))
            }
            val before = repository.state.value
            val linked = before.activity.last()
            assertEquals("payment", linked.settlementId)
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("nav_activity").performClick()
            val row = "activity_${linked.id}"
            compose.scrollLedgerItemToView(row)
            compose.onNodeWithTag(row).performTouchInput { swipeRight() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("settlement_amount").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settlement_amount").assertTextContains("10.00")
            assertEquals(before, repository.state.value)
            compose.onNodeWithTag("settlement_amount").performTextReplacement("5.00")
            compose.onNodeWithTag("settlement_save").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5000) { repository.state.value.settlements.single().amountMinor == 500L }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("save_celebration").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("activity_screen").assertExists()
            val edited = repository.state.value
            assertEquals(before.settlements.single().id, edited.settlements.single().id)
            assertEquals(before.settlements.single().createdAt, edited.settlements.single().createdAt)
            assertEquals(before.activity, edited.activity.take(before.activity.size))
            compose.scrollLedgerItemToView(row)
            compose.onNodeWithTag(row).performTouchInput { swipeLeft() }
            compose.onNodeWithTag("activity_confirm_delete_${linked.id}").assertExists()
            assertEquals(edited, repository.state.value)
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(edited, repository.state.value)
            compose.scrollLedgerItemToView(row)
            compose.onNodeWithTag(row).performTouchInput { swipeLeft() }
            compose.onNodeWithTag("activity_confirm_delete_${linked.id}").performClick()
            compose.waitUntil(5000) { repository.state.value.settlements.single().deleted }
            assertEquals(edited.activity, repository.state.value.activity.take(edited.activity.size))
            assertEquals(before.expenses, repository.state.value.expenses)
            assertEquals("payment", repository.state.value.activity.last().settlementId)
            compose.scrollLedgerItemToView("activity_actions_${linked.id}")
            compose.onNodeWithTag("activity_actions_${linked.id}").assertExists().performClick()
            compose.onNodeWithTag("activity_history_${linked.id}").assertExists().assertIsNotEnabled()
            compose.onNodeWithTag("activity_edit_${linked.id}").assertDoesNotExist()
            compose.onNodeWithTag("activity_delete_${linked.id}").assertDoesNotExist()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        } finally { repository.close(); context.deleteDatabase(db) }
    }

    private fun performActivityMutation(tag: String, message: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForCelebrationStart()
            compose.mainClock.advanceTimeBy(350)
            compose.assertCelebrationMessageDisplayed(message, "activity-" + tag + "-acknowledgement")
            compose.mainClock.advanceTimeBy(2_000)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForCelebrationEnd()
    }

    private fun assertUnlinkedActivityHasNoMutation(activityId: String) {
        compose.onNodeWithTag("expense_title").assertDoesNotExist()
        compose.onNodeWithTag("activity_edit_$activityId").assertDoesNotExist()
        compose.onNodeWithTag("activity_delete_$activityId").assertDoesNotExist()
        compose.onNodeWithTag("activity_confirm_delete_$activityId").assertDoesNotExist()
        compose.onNodeWithTag("activity_choose_expense_original").assertDoesNotExist()
        compose.onNodeWithTag("activity_choose_expense_unrelated").assertDoesNotExist()
        compose.onNodeWithText("Choose transaction to edit").assertDoesNotExist()
        compose.onNodeWithText("Choose transaction to delete").assertDoesNotExist()
    }

}
