package org.fairshare.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import org.fairshare.app.model.*

class ExpenseCurrencyDefaultsTest {
    private val group = Group("trip", "Tokyo", "JPY", listOf(SELF_ID, "alice"))

    @Test fun newDraftUsesTheConfiguredGroupCurrencyRatherThanAUniversalDefault() {
        assertEquals("JPY", initialExpenseCurrency(group, null))
        assertEquals("EUR", initialExpenseCurrency(group.copy(currency = "EUR"), null))
    }

    @Test fun editingPreservesAnExplicitTransactionOverrideAndLegacyUsesItsGroup() {
        val expense = Expense("booking", group.id, "Booking", 2500L, SELF_ID,
            mapOf(SELF_ID to 1250L, "alice" to 1250L), currency = "EUR")
        assertEquals("EUR", initialExpenseCurrency(group, expense))
        assertEquals("JPY", initialExpenseCurrency(group, expense.copy(currency = null)))
        assertEquals("JPY", group.currency)
    }

    @Test fun fixedDefaultAmountsAreOnlyAppliedInTheirOriginalUnits() {
        val exact = ExpenseDefaults(splitMode = SplitMode.EXACT,
            values = mapOf(SELF_ID to "10", "alice" to "20"), currency = "EUR")
        assertEquals(emptyMap(), initialExpenseSplitValues(group.copy(expenseDefaults = exact)))
        assertEquals(exact.values, initialExpenseSplitValues(group.copy(currency = "EUR", expenseDefaults = exact)))
        assertEquals(emptyMap(), initialExpenseSplitValues(group.copy(expenseDefaults = exact.copy(splitMode = SplitMode.ADJUSTMENT))))
        assertEquals(exact.values, initialExpenseSplitValues(group.copy(expenseDefaults = exact.copy(splitMode = SplitMode.SHARES))))
    }

}
