package org.fairshare.app

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalDatabase
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.data.StateRow
import org.fairshare.app.model.*
import org.fairshare.app.ui.FairshareApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

/** Real, isolated persisted ledgers; this suite never opens fairshare.db. */
@RunWith(AndroidJUnit4::class)
class GroupStressTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sixtyPeopleTwoYearsThreeCurrenciesRemainNavigableAndUnchanged() = withFixture(largeLedger()) { repository, before ->
        compose.setContent { FairshareApp(repository) }
        compose.onNodeWithTag("group_stress").performClick()
        compose.onNodeWithTag("group_all_currencies").assertTextEquals("View all 3 currencies").assertIsDisplayed()
        assertCompleteAmount("group_balance")
        compose.onNodeWithTag("group_people_illustration").assertDoesNotExist()
        compose.captureEvidence("stress-01-sixty-members-three-currencies")
        val titleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("group_expense_title_day-729", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertTrue("Dark group expense text must use a light foreground, not black-on-black",
            titleLayouts.single().layoutInput.style.color != Color.Black && titleLayouts.single().layoutInput.style.color.luminance() > .5f)
        val expenseBounds = compose.onNodeWithTag("group_expense_day-729").getUnclippedBoundsInRoot()
        compose.scrollLedgerItemToView("group_payment_payment-23-USD")
        val paymentBounds = compose.onNodeWithTag("group_payment_payment-23-USD").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("A recorded payment should remain more compact than an expense tile",
            paymentBounds.bottom - paymentBounds.top < expenseBounds.bottom - expenseBounds.top)
        compose.captureEvidence("stress-01b-dark-contrast-compact-payment")
        compose.scrollLedgerItemToView("group_expense_day-0")
        compose.onNodeWithTag("group_expense_day-0").assertIsDisplayed()
        compose.captureEvidence("stress-02-two-years-oldest-expense")
        compose.onNodeWithTag("group_members").performClick()
        compose.onNodeWithTag("members_title").assertTextContains("60 members", substring = true)
        compose.onNodeWithTag("member_row_person-59").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("remove_member_person-59").assertIsNotEnabled()
        compose.captureEvidence("stress-03-sixtieth-member")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("group_settings").performClick()
        compose.onNodeWithTag("group_leave").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("group_delete").performScrollTo().assertIsNotEnabled()
        compose.captureEvidence("stress-03b-large-ledger-settings-restrictions")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("group_all_currencies").performClick()
        compose.onNodeWithTag("group_balances_title").assertIsDisplayed()
        listOf("USD", "EUR", "AED").forEach { currency ->
            // The currency strip itself scrolls horizontally. Move its vertical parent
            // to the top first; performScrollTo alone only scrolls the nearest ancestor.
            compose.onNodeWithTag("group_expense_list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
            compose.onNodeWithTag("group_currency_$currency").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
            compose.onNodeWithTag("group_member_balance_person-59").performScrollTo().assertIsDisplayed()
        }
        compose.captureEvidence("stress-04-sixty-balances-aed")
        compose.onNodeWithTag("group_tab_totals").performScrollTo().performClick()
        listOf("USD", "EUR", "AED").forEach { currency ->
            val count = before.expenses.count { it.currency == currency }
            compose.onNodeWithTag("group_total_$currency").performScrollTo().assertTextEquals(org.fairshare.app.platform.moneyText(org.fairshare.app.math.BigInteger.valueOf(count * 6000L), currency))
            compose.onNodeWithTag("chart_month_2026-10-01_$currency").performScrollTo().assertExists()
        }
        compose.captureEvidence("stress-05-three-currency-totals")
        compose.onNodeWithTag("group_tab_activity").performScrollTo().performClick()
        compose.onNodeWithTag("activity_event-729").assertIsDisplayed()
        compose.scrollLedgerItemToView("activity_event-0")
        compose.onNodeWithTag("activity_event-0").assertIsDisplayed()
        compose.onNodeWithTag("activity_actions_event-0").performClick()
        compose.onNodeWithTag("activity_edit_event-0").assertExists()
        compose.onNodeWithTag("activity_delete_event-0").assertExists()
        compose.captureEvidence("stress-06-oldest-activity-direct-actions")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("nav_friends").performClick()
        compose.onNodeWithTag("friend_person-59").performScrollTo().assertIsDisplayed()
        compose.captureEvidence("stress-07-sixtieth-friend-three-currencies")
        assertEquals(before, repository.state.value)
        runBlocking { repository.refresh() }
        assertEquals("Rendering and refreshing the maximum fixture must preserve every record", before, repository.state.value)
    }

    @Test fun memberArtworkMinimumThresholdAndSixtyCountWorkInBothAppearances() {
        val members = members()
        val groups = listOf(1, 6, 7, 60).map { count -> Group("people-$count", "$count people", memberIds = members.take(count).map { it.id }) }
        withFixture(AppState("Taylor", members, groups, onboardingComplete = true, themeMode = "LIGHT", activityLinkVersion = 1)) { repository, _ ->
            compose.setContent { FairshareApp(repository) }
            for (appearance in listOf("LIGHT", "DARK")) {
                runBlocking { repository.setTheme(appearance) }
                for (count in listOf(1, 6, 7, 60)) {
                    compose.onNodeWithTag("group_people-$count").performScrollTo().performClick()
                    val artwork = compose.onNodeWithTag("group_people_illustration").assertIsDisplayed().getUnclippedBoundsInRoot()
                    val amount = compose.onNodeWithTag("group_balance").getUnclippedBoundsInRoot()
                    assertTrue("Artwork must never overlap the balance", artwork.left >= amount.right)
                    if (count > 6) {
                        val badge = compose.onNodeWithText("+${count - 6}").assertIsDisplayed().getUnclippedBoundsInRoot()
                        assertTrue("The overflow count must fit inside the illustration", badge.left >= artwork.left && badge.right <= artwork.right && badge.top >= artwork.top && badge.bottom <= artwork.bottom)
                    } else compose.onNodeWithText("+0").assertDoesNotExist()
                    compose.captureEvidence("stress-people-$count-${appearance.lowercase()}")
                    compose.onNodeWithContentDescription("Back").performClick()
                }
            }
        }
    }

    @Test fun narrowLargeTextHeaderKeepsDigitsAndControlsWithoutDecorativeArtwork() {
        val members = members().take(2)
        val group = Group("narrow", "A very long international holiday group title", memberIds = members.map { it.id })
        val expenses = listOf("USD", "AED", "EUR").map { currency ->
            Expense("wide-$currency", group.id, "Large $currency expense", 2_469_135_600L, SELF_ID, members.associate { it.id to 1_234_567_800L }, currency = currency)
        }
        val payment = Settlement("narrow-payment", group.id, "person-1", SELF_ID, 1_000_000L, currency = "AED")
        withFixture(AppState("Taylor", members, listOf(group), expenses, listOf(payment), onboardingComplete = true, themeMode = "DARK", activityLinkVersion = 1)) { repository, before ->
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                    Box(Modifier.width(320.dp)) { FairshareApp(repository) }
                }
            }
            listOf("USD", "EUR", "AED").forEach { currency ->
                compose.onNodeWithTag("overall_amount_$currency").performScrollTo().assertIsDisplayed()
                assertCompleteAmount("overall_amount_$currency")
            }
            compose.onNodeWithTag("group_narrow").performScrollTo().assertIsDisplayed()
            val cardBounds = compose.onNodeWithTag("group_narrow").getUnclippedBoundsInRoot()
            assertTrue("Large-text group summary must stay readable, never vertical money glyphs", (cardBounds.bottom - cardBounds.top) < 560.dp)
            compose.captureEvidence("stress-narrow-home-320dp-font180")
            compose.onNodeWithTag("group_narrow").performClick()
            compose.captureEvidence("stress-narrow-320dp-font180-before-assertions")
            try {
                compose.onNodeWithTag("group_members").assertIsDisplayed().assertHasClickAction()
            } catch (failure: AssertionError) {
                throw AssertionError("Members shortcut must be reachable at 320dp / 180% text.\n${compose.onRoot(useUnmergedTree = true).printToString()}", failure)
            }
            compose.onNodeWithTag("group_settings").assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithTag("group_people_illustration").assertDoesNotExist()
            assertCompleteAmount("group_balance")
            val secondCurrency = if (compose.onAllNodesWithTag("group_balance_amount_AED").fetchSemanticsNodes().isNotEmpty()) "AED" else "EUR"
            assertCompleteAmount("group_balance_amount_$secondCurrency")
            compose.onNodeWithTag("group_all_currencies").assertIsDisplayed()
            for (tab in listOf("groups", "friends", "activity", "account")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("nav_label_$tab", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Navigation labels must stay on one line", layouts.isNotEmpty() && layouts.all { it.lineCount == 1 })
                compose.onNodeWithTag("nav_$tab").assertContentDescriptionContains(tab.replaceFirstChar(Char::uppercase))
            }
            for ((card, title, amount) in listOf(
                Triple("group_expense_wide-EUR", "group_expense_title_wide-EUR", "group_expense_amount_wide-EUR"),
                Triple("group_payment_narrow-payment", "group_payment_title_narrow-payment", "group_payment_amount_narrow-payment"))) {
                compose.scrollLedgerItemToView(card)
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag(title, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val measured = layouts.map { with(compose.density) { it.size.width.toDp() to it.lineCount } }
                assertTrue("Transaction details must have usable text width: $card $measured", measured.isNotEmpty() && measured.all { it.first >= 80.dp && it.second <= 2 })
                val transactionBounds = compose.onNodeWithTag(card).getUnclippedBoundsInRoot()
                assertTrue("Transaction cards must not grow into vertical glyph columns", transactionBounds.bottom - transactionBounds.top < 400.dp)
                assertCompleteAmount(amount)
                compose.captureEvidence("stress-narrow-$card")
            }
            compose.captureEvidence("stress-narrow-320dp-font180")
            compose.onNodeWithTag("group_tab_balances").performScrollTo().performClick()
            compose.onNodeWithTag("group_member_balance_self").performScrollTo().assertIsDisplayed()
            val memberBounds = compose.onNodeWithTag("group_member_balance_self").getUnclippedBoundsInRoot()
            assertTrue("Member balance rows must remain readable", memberBounds.bottom - memberBounds.top < 260.dp)
            assertCompleteAmount("group_member_balance_amount_self")
            compose.captureEvidence("stress-narrow-everyone-balances")
            compose.onNodeWithTag("group_members").performClick()
            compose.onNodeWithTag("members_title").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("group_settings").performClick()
            compose.onNodeWithTag("group_settings_screen").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            assertEquals(before, repository.state.value)
        }
    }

    @Test fun minimumGroupsEmptyActivityAndDeletedOnlyHistoryRemainUsable() {
        val people = members().take(2)
        val groups = listOf(
            Group("solo-min", "Just me", memberIds = listOf(SELF_ID)),
            Group("removed-min", "Former member", memberIds = people.map { it.id }, removedMemberIds = listOf("person-1")),
            Group("deleted-min", "Deleted history", memberIds = people.map { it.id }))
        val expense = Expense("deleted-only", "deleted-min", "Deleted lunch", 100, SELF_ID, mapOf("person-1" to 100), deleted = true)
        val payment = Settlement("deleted-payment", "deleted-min", "person-1", SELF_ID, 100, deleted = true)
        val deletion = Activity("deleted-activity", "deleted-min", "Deleted Deleted lunch: \$1.00", expenseId = expense.id)
        withFixture(AppState("Taylor", people, groups, listOf(expense), listOf(payment), activity = listOf(deletion), onboardingComplete = true, activityLinkVersion = 1)) { repository, before ->
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("nav_activity").performClick()
            compose.onNodeWithTag("activity_${deletion.id}").assertIsDisplayed()
            compose.onNodeWithText("Deleted Deleted lunch: \$1.00").assertIsDisplayed()
            compose.captureEvidence("stress-min-empty-global-activity")
            compose.onNodeWithTag("nav_groups").performClick()
            for (id in listOf("solo-min", "removed-min", "deleted-min")) {
                compose.onNodeWithTag("group_$id").performScrollTo().performClick()
                compose.onNodeWithTag("group_balance").assertIsDisplayed()
                assertCompleteAmount("group_balance")
                if (id == "deleted-min") {
                    compose.onNodeWithTag("group_expense_deleted-only").assertDoesNotExist()
                    compose.onNodeWithTag("group_payment_deleted-payment").assertDoesNotExist()
                    compose.onNodeWithTag("group_tab_activity").performScrollTo().performClick()
                    compose.onNodeWithTag("activity_${deletion.id}").assertIsDisplayed()
                } else compose.onNodeWithText("Your expenses start here").assertIsDisplayed()
                compose.onNodeWithTag("group_tab_balances").performScrollTo().performClick()
                compose.onNodeWithTag("group_member_balance_self").assertIsDisplayed()
                // Back first returns to Expenses; select the root explicitly for the next fixture.
                compose.onNodeWithTag("nav_groups").performClick()
            }
            assertEquals(before, repository.state.value)
        }
    }

    @Test fun eightHundredGlobalEventsKeepOldestTransactionActionsAndContextExact() = withFixture(largeLedger()) { repository, before ->
        assertEquals(802, before.activity.size)
        assertEquals("day-0", before.activity.single { it.id == "event-0" }.expenseId)
        compose.setContent { FairshareApp(repository) }
        compose.onNodeWithTag("nav_activity").performClick()
        compose.onNodeWithTag("activity_screen").assertIsDisplayed()
        fun openOldestActions(evidence: String) {
            compose.scrollLedgerItemToView("activity_actions_event-0")
            // Materializing the oldest row can stop as soon as it enters the viewport.
            // Consume the existing bottom clearance so its overflow is above the FAB.
            val clearance = with(compose.density) { 160.dp.toPx() }
            compose.onNodeWithTag("activity_screen").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, clearance) }
            compose.onNodeWithTag("activity_actions_event-0").assertIsDisplayed()
            compose.captureEvidence("$evidence-before-menu")
            compose.onNodeWithTag("activity_actions_event-0").performClick()
            try {
                compose.waitUntil(3000) { compose.onAllNodesWithTag("activity_edit_event-0").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("activity_edit_event-0").assertIsDisplayed()
                compose.captureEvidence("$evidence-open-menu")
            } catch (failure: Throwable) {
                compose.captureEvidence("$evidence-menu-failed")
                fun dump(node: androidx.compose.ui.semantics.SemanticsNode, depth: Int = 0): String =
                    "${"  ".repeat(depth)}${node.id} ${node.boundsInRoot} ${node.config}\n" + node.children.joinToString("") { dump(it, depth + 1) }
                val tree = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().joinToString("\n") { dump(it) }
                throw AssertionError("The oldest activity must open actions for day-0; all windows:\n$tree", failure)
            }
        }
        compose.scrollLedgerItemToView("activity_event-0")
        compose.onNodeWithTag("activity_event-0").assertIsDisplayed()
        openOldestActions("stress-global-oldest-first")
        compose.onNodeWithTag("activity_delete_event-0").performClick()
        compose.onNodeWithText("Delete Daily spending 1?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.captureEvidence("stress-global-oldest-direct-confirm-cancelled")
        openOldestActions("stress-global-oldest-context")
        compose.onNodeWithTag("activity_context_event-0").performClick()
        compose.onNodeWithTag("group_members").assertIsDisplayed()
        compose.onNodeWithTag("group_tab_expenses").assertIsSelected()
        assertEquals(before, repository.state.value)
    }

    @Test fun zeroActiveMembersAreRejectedAsInvalidSavedData() {
        val invalid = AppState("Taylor", listOf(Member(SELF_ID, "Taylor")),
            listOf(Group("invalid-zero", "No active people", memberIds = listOf(SELF_ID), removedMemberIds = listOf(SELF_ID))),
            onboardingComplete = true, activityLinkVersion = 1)
        val failure = assertThrows(IllegalStateException::class.java) {
            withFixture(invalid) { _, _ -> fail("A group without its active local profile must not load") }
        }
        assertTrue(failure.message.orEmpty().contains("preserved"))
        assertTrue(failure.cause is IllegalArgumentException)
    }

    private fun assertCompleteAmount(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Expected amount layout for $tag", results.isNotEmpty())
        results.forEach { layout ->
            assertFalse("Amount height must not clip for $tag", layout.didOverflowHeight)
            (0 until layout.lineCount).forEach { assertFalse("Amounts must never ellipsize", layout.isLineEllipsized(it)) }
            (0 until layout.layoutInput.text.length).forEach { index ->
                val glyph = layout.getBoundingBox(index)
                assertTrue("All money glyphs must fit: $tag index $index, $glyph in ${layout.size}", glyph.left >= -1f && glyph.right <= layout.size.width + 1f && glyph.bottom <= layout.size.height + 1f)
            }
        }
    }

    private fun members() = listOf(Member(SELF_ID, "Taylor")) + (1..59).map { Member("person-$it", "Person ${it.toString().padStart(2, '0')}") }

    private fun largeLedger(): AppState {
        val people = members()
        val group = Group("stress", "International team · two-year history", memberIds = people.map { it.id }, theme = GroupTheme.TRAVEL)
        val start = LocalDate.of(2024, 11, 1)
        val expenses = (0 until 730).map { index ->
            val at = start.plusDays(index.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            Expense("day-$index", group.id, "Daily spending ${index + 1}", 6000L, if (index % 2 == 0) SELF_ID else "person-1", people.associate { it.id to 100L }, date = at, currency = listOf("USD", "EUR", "AED")[index % 3], createdAt = at)
        }
        val payments = (0 until 24).flatMap { month ->
            val at = start.plusMonths(month.toLong()).plusDays(14).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            listOf("USD", "EUR", "AED").map { currency -> Settlement("payment-$month-$currency", group.id, "person-1", SELF_ID, 100L, date = at, currency = currency, createdAt = at) }
        }
        val activity = expenses.mapIndexed { index, expense -> Activity("event-$index", group.id, "Added ${expense.title}", expense.createdAt, expenseId = expense.id) } +
            payments.map { Activity("event-${it.id}", group.id, "Recorded payment", it.createdAt, settlementId = it.id) }
        return AppState("Taylor", people, listOf(group), expenses, payments, activity, onboardingComplete = true, themeMode = "DARK", activityLinkVersion = 1)
    }

    private fun withFixture(state: AppState, test: (LocalRepository, AppState) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "stress-isolated-${System.nanoTime()}.db"
        val database = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        runBlocking { database.stateDao().write(StateRow(json = Gson().toJson(state))) }
        database.close()
        val repository = LocalRepository(context, name)
        try {
            runBlocking { repository.load() }
            assertEquals("Fixture must pass the real repository validation", state, repository.state.value)
            test(repository, state)
        } finally { repository.close(); context.deleteDatabase(name) }
    }
}
