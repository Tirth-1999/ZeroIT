package org.fairshare.app

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.data.Repository
import org.fairshare.app.model.Expense
import org.fairshare.app.model.SELF_ID
import org.fairshare.app.ui.FairshareApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class GroupWorkspaceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wideDirhamBalanceKeepsCompleteAmountWithMembersInTopBar() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "group-wide-dirham-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        try {
            runBlocking {
                repository.setProfile("Taylor")
                val groupId = repository.createGroup("Dubai trip", "AED", listOf("Alice"))
                val members = repository.state.value.groups.single().memberIds
                repository.saveExpense(Expense("dirham", groupId, "Trip spending", 2_469_135_600L, SELF_ID, members.associateWith { 1_234_567_800L }, currency = "AED"))
            }
            val before = repository.state.value
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("group_${before.groups.single().id}").performClick()
            compose.onNodeWithTag("group_balance").assertTextContains("12,345,678.00", substring = true).assertIsDisplayed()
            compose.waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("group_balance").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            org.junit.Assert.assertTrue("The full dirham amount must lay out without truncation: ${layouts.map { "size=${it.size} constraints=${it.layoutInput.constraints} widthOverflow=${it.didOverflowWidth} heightOverflow=${it.didOverflowHeight} lines=${it.lineCount} font=${it.layoutInput.style.fontSize} paragraphWidth=${it.multiParagraph.width} right=${it.getLineRight(0)} last=${it.getBoundingBox(it.layoutInput.text.length - 1)}" }}", layouts.isNotEmpty() && layouts.all { layout ->
                !layout.didOverflowHeight && (0 until layout.lineCount).all { line -> !layout.isLineEllipsized(line) } &&
                    (0 until layout.layoutInput.text.length).all { offset ->
                        val glyph = layout.getBoundingBox(offset)
                        glyph.left >= -1f && glyph.right <= layout.size.width + 1f && glyph.bottom <= layout.size.height + 1f
                    }
            })
            val amount = compose.onNodeWithTag("group_balance").getUnclippedBoundsInRoot()
            val members = compose.onNodeWithTag("group_members").assertIsDisplayed().getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("The people shortcut must remain above the full-width balance", members.bottom <= amount.top)
            val artwork = compose.onAllNodesWithTag("group_people_illustration").fetchSemanticsNodes()
            if (artwork.isNotEmpty()) org.junit.Assert.assertTrue("Even a long amount must remain clear of artwork", artwork.single().boundsInRoot.left >= compose.onNodeWithTag("group_balance").fetchSemanticsNode().boundsInRoot.right)
            assertMembersBeforeSettings()
            compose.onNodeWithTag("group_members").performClick()
            compose.onNodeWithTag("members_title").assertTextEquals("2 members · Dubai trip")
            assertEquals(before, repository.state.value)
        } finally { repository.close(); context.deleteDatabase(name) }
    }

    @Test fun longExpenseFeedScrollsWithinFixedOpenEndedPanel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "group-long-panel-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        try {
            runBlocking {
                repository.setProfile("Taylor")
                val groupId = repository.createGroup("Demo · Six-month spending with friends", "USD", listOf("Alice"))
                val members = repository.state.value.groups.single().memberIds
                repeat(18) { index ->
                    repository.saveExpense(Expense(
                        "long-$index", groupId, "Weekend expense ${index + 1}", 2000, SELF_ID,
                        members.associateWith { 1000L }, date = 1_792_627_200_000L + index * 60_000L,
                    ))
                }
            }
            val before = repository.state.value
            val group = before.groups.single()
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("group_${group.id}").performClick()
            assertMembersBeforeSettings()
            val titleLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("group_title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
            org.junit.Assert.assertTrue("Long group titles must stay on one line", titleLayouts.isNotEmpty() && titleLayouts.all { it.lineCount == 1 && it.layoutInput.text.length <= 24 })
            compose.captureEvidence("09-long-group-header")
            val art = compose.onNodeWithTag("group_people_illustration").assertIsDisplayed().getUnclippedBoundsInRoot()
            val amount = compose.onNodeWithTag("group_balance").getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("Decorative people must stay beside the amount", art.left > amount.right)
            compose.onNodeWithTag("group_expense_long-17").assertIsDisplayed()
            val viewport = compose.onNodeWithTag("group_expense_list").getUnclippedBoundsInRoot()
            val panelAtTop = compose.onNodeWithTag("group_content_panel").getUnclippedBoundsInRoot()
            val headerAtTop = compose.onNodeWithTag("group_header_boundary").getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("The fixed frame must cover the feed viewport", headerAtTop.top <= viewport.top && headerAtTop.bottom >= viewport.bottom)
            compose.scrollLedgerItemToView("group_expense_long-0")
            compose.onNodeWithTag("group_expense_long-0").assertIsDisplayed()
            // Reach the end while the open-bottom frame stays attached to the viewport.
            compose.onNodeWithTag("group_expense_list").performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, 100_000f) }
            compose.waitForIdle()
            compose.onNodeWithTag("group_expense_long-0").assertIsDisplayed()
            compose.onNodeWithTag("group_expense_long-17").assertDoesNotExist()
            val panelAtEnd = compose.onNodeWithTag("group_content_panel").getUnclippedBoundsInRoot()
            val lastExpense = compose.onNodeWithTag("group_expense_long-0").getUnclippedBoundsInRoot()
            val headerAtEnd = compose.onNodeWithTag("group_header_boundary").getUnclippedBoundsInRoot()
            assertEquals("Virtualized content viewport must remain attached to the fixed frame", panelAtTop, panelAtEnd)
            org.junit.Assert.assertTrue("The last expense must remain within the open-ended frame", lastExpense.top >= viewport.top && lastExpense.bottom <= viewport.bottom)
            assertEquals("Scrolling must not move the header boundary", headerAtTop, headerAtEnd)
            assertEquals(before, repository.state.value)
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun compactGroupMembersRefreshRetryAndTabReturnPreserveLedger() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "group-workspace-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        val refreshCalls = AtomicInteger()
        val failRefresh = AtomicBoolean(false)
        val observed = object : Repository by repository {
            override suspend fun refresh() {
                refreshCalls.incrementAndGet()
                if (failRefresh.get()) throw IllegalStateException("Test reload failure")
                repository.refresh()
            }
        }
        try {
            runBlocking {
                repository.setProfile("Taylor")
                val group = repository.createGroup("Weekend", "USD", listOf("Alice", "Bob"))
                val members = repository.state.value.groups.single().memberIds
                repository.saveExpense(Expense("dinner", group, "Dinner", 3000, SELF_ID, members.associateWith { 1000L }))
                repository.saveExpense(Expense("euro", group, "Euro taxi", 900, members[1], members.associateWith { 300L }, currency = "EUR"))
            }
            val before = repository.state.value
            val group = before.groups.single()
            compose.setContent { FairshareApp(observed) }
            compose.onNodeWithTag("group_${group.id}").performClick()
            compose.onNodeWithTag("group_balance").assertTextEquals("$20.00")
            compose.onNodeWithTag("group_balance_currency_USD").assertDoesNotExist()
            compose.onNodeWithTag("group_balance_currency_EUR").assertDoesNotExist()
            compose.onNodeWithTag("group_balance_amount_EUR").assertTextEquals("€3.00").assertContentDescriptionContains("EUR", substring = true)
            val dollars = compose.onNodeWithTag("group_balance_USD").fetchSemanticsNode().boundsInRoot
            val euros = compose.onNodeWithTag("group_balance_EUR").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("Currencies should be peers on one row", dollars.right <= euros.left && kotlin.math.abs(dollars.top - euros.top) < 2f && kotlin.math.abs(dollars.height - euros.height) < 2f)
            compose.onAllNodesWithTag("add_expense").assertCountEquals(1)
            compose.onNodeWithTag("add_members").assertDoesNotExist()
            compose.onNodeWithText("Group members · 3").assertDoesNotExist()
            compose.onNodeWithTag("group_expense_dinner").assertExists()
            compose.onNodeWithTag("group_tab_expenses").assertIsSelected()
            val settleBounds = compose.onNodeWithTag("group_action_settle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val expensesBounds = compose.onNodeWithTag("group_tab_expenses").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("Settle up must be the leftmost group action", settleBounds.right <= expensesBounds.left)
            val headerBoundary = compose.onNodeWithTag("group_header_boundary").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val feedBounds = compose.onNodeWithTag("group_expense_list").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("The scrolling content surface must begin below the fixed header actions", headerBoundary.top >= expensesBounds.bottom && feedBounds.top >= headerBoundary.top)
            for (distance in listOf(12, 60)) {
                val pixels = with(compose.density) { distance.dp.toPx() }
                try {
                    compose.onNodeWithTag("group_expense_list").performTouchInput {
                        swipeDown(startY = 1f, endY = 1f + pixels, durationMillis = 600)
                    }
                } catch (failure: Throwable) {
                    throw AssertionError("Small pull ${distance}dp lost the group feed.\n${compose.onRoot(useUnmergedTree = true).printToString()}", failure)
                }
                compose.runOnIdle { assertEquals(0, refreshCalls.get()) }
            }
            pullToRefresh()
            compose.waitUntil(5000) { refreshCalls.get() == 1 }
            waitForFeedback("Saved group data reloaded")
            failRefresh.set(true)
            pullToRefresh()
            compose.waitUntil(5000) { refreshCalls.get() == 2 }
            waitForFeedback("Could not reload saved data.")
            failRefresh.set(false)
            compose.onNodeWithTag("group_refresh_retry").assertHasClickAction().performClick()
            try {
                compose.waitUntil(5000) { refreshCalls.get() >= 3 }
            } catch (failure: Throwable) {
                throw AssertionError("Retry did not invoke reload; calls=${refreshCalls.get()}", failure)
            }
            assertEquals(3, refreshCalls.get())
            waitForFeedback("Saved group data reloaded")
            compose.onNodeWithTag("group_tab_balances").performClick()
            assertMembersBeforeSettings()
            compose.onNodeWithTag("group_balances_title").assertIsDisplayed()
            group.memberIds.forEach { compose.onNodeWithTag("group_member_balance_$it").assertExists() }
            compose.onNodeWithTag("settle_up").assertDoesNotExist()
            compose.onNodeWithTag("record_payment").assertDoesNotExist()
            compose.onNodeWithTag("group_members").performClick()
            compose.onNodeWithTag("members_title").assertTextEquals("3 members · Weekend")
            group.memberIds.forEach { compose.onNodeWithTag("member_row_$it").assertExists() }
            compose.onAllNodesWithTag("add_members").assertCountEquals(1)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("group_tab_balances").assertIsSelected()
            compose.onNodeWithText("Back to expenses").assertDoesNotExist()
            compose.onNodeWithTag("group_tab_totals").performScrollTo().performClick()
            compose.onNodeWithTag("group_tab_totals").assertIsSelected()
            assertMembersBeforeSettings()
            compose.onNodeWithTag("group_total_USD").assertTextEquals("$30.00")
            compose.onNodeWithTag("group_totals_currency_USD").assertTextEquals("USD")
            compose.onNodeWithText("Group totals").assertDoesNotExist()
            compose.onNodeWithText("Active expenses and recorded payments, kept separately in each currency. Payments are not additional spending.").assertDoesNotExist()
            compose.onNodeWithTag("chart_members_USD").performScrollTo().performClick()
            compose.onNodeWithTag("chart_members_USD").assertIsSelected()
            compose.onNodeWithTag("chart_member_self_USD").performScrollTo().assertContentDescriptionContains("share $10.00 USD, paid $30.00 USD", substring = true)
            compose.onNodeWithTag("chart_monthly_USD").performScrollTo().performClick()
            compose.onNodeWithTag("chart_monthly_USD").assertIsSelected()
            compose.onNodeWithTag("group_tab_activity").performScrollTo().performClick()
            compose.onNodeWithTag("group_tab_activity").assertIsSelected()
            assertMembersBeforeSettings()
            compose.onNodeWithTag("group_tab_expenses").performScrollTo().performClick()
            compose.onNodeWithTag("group_tab_expenses").assertIsSelected()
            compose.onNodeWithTag("group_expense_dinner").assertExists()
            compose.onNodeWithTag("group_action_settle").performScrollTo().performClick()
            compose.onNodeWithTag("group_action_settle").assertIsSelected()
            assertMembersBeforeSettings()
            compose.onNodeWithTag("group_tab_balances").assertIsNotSelected()
            compose.onNodeWithTag("group_everyone_balances").assertDoesNotExist()
            compose.onNodeWithTag("group_settlement_title").assertIsDisplayed()
            compose.onNodeWithTag("record_payment").assertExists()
            compose.onNodeWithTag("group_currency_EUR").performClick()
            val alice = group.memberIds[1]
            val bob = group.memberIds[2]
            compose.onNodeWithTag("group_settlement_${SELF_ID}_$alice").assertIsDisplayed()
            compose.onNodeWithTag("group_settlement_${bob}_$alice").assertDoesNotExist()
            compose.onAllNodesWithTag("settle_up").assertCountEquals(1)
            compose.onNodeWithTag("group_other_settlements").performScrollTo().performClick()
            compose.onNodeWithTag("group_settlement_${bob}_$alice").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("group_tab_balances").performScrollTo().performClick()
            compose.onNodeWithTag("group_everyone_balances").assertIsDisplayed()
            compose.onNodeWithTag("group_settlement_title").assertDoesNotExist()
            assertEquals(before, repository.state.value)
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    private fun waitForFeedback(text: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertExists()
    }

    private fun assertMembersBeforeSettings() {
        compose.onAllNodesWithTag("group_members").assertCountEquals(1)
        val members = compose.onNodeWithTag("group_members").assertIsDisplayed().getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithTag("group_settings").assertIsDisplayed().getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("Members must appear immediately to the left of settings in the top bar", members.right <= settings.left && kotlin.math.abs((members.top + members.bottom).value - (settings.top + settings.bottom).value) < 2f)
    }

    private fun pullToRefresh() {
        compose.onNodeWithTag("group_expense_list").performTouchInput {
            swipeDown(startY = height * .03f, endY = height * .97f, durationMillis = 600)
        }
    }
}
