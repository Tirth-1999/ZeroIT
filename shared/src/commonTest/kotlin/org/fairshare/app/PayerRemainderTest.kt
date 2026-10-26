package org.fairshare.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PayerRemainderTest {
    private val ids = listOf("a", "b")

    @Test fun worksBeforeCurrencyAndDescriptionAndPreservesExactDecimals() {
        assertEquals("25", payerRemainder("50", "", ids, mapOf("a" to "25"), "b"))
        assertEquals("0.2", payerRemainder("0.3", "", ids, mapOf("a" to "0.1"), "b"))
        assertEquals("13.233", payerRemainder("23.233", "", ids, mapOf("a" to "10"), "b"))
        assertEquals("25", payerRemainder("50", "", ids + "c", mapOf("a" to "25"), "b"))
    }

    @Test fun neverSuggestsReplacingAnEnteredAmountOrChangingInputs() {
        val amounts = mapOf("a" to "25", "b" to "20")
        assertNull(payerRemainder("50", "", ids, amounts, "b"))
        assertEquals(mapOf("a" to "25", "b" to "20"), amounts)
        assertNull(payerRemainder("50", "", ids, amounts, "unknown"))
    }

    @Test fun selectedCurrencyUsesItsPrecisionAndMinorUnitBounds() {
        assertEquals("25.00", payerRemainder("50", "USD", ids, mapOf("a" to "25"), "b"))
        assertEquals("25", payerRemainder("50", "JPY", ids, mapOf("a" to "25"), "b"))
        assertEquals("0.001", payerRemainder("0.003", "KWD", ids, mapOf("a" to "0.002"), "b"))
        assertNull(payerRemainder("23.233", "USD", ids, mapOf("a" to "10"), "b"))
        assertNull(payerRemainder("50", "JPY", ids, mapOf("a" to "25.1"), "b"))
        assertNull(payerRemainder("92233720368547758.08", "USD", ids, mapOf("a" to "1"), "b"))
        assertEquals("92233720368547758.07", payerRemainder("92233720368547758.08", "", ids, mapOf("a" to "0.01"), "b"))
    }

    @Test fun incompleteInvalidOrOverpaidNumbersNeverProduceMisleadingHints() {
        listOf("", ".", "abc", "1e3", "NaN", "-50", "9".repeat(129)).forEach { amount ->
            assertNull(payerRemainder(amount, "", ids, mapOf("a" to "25"), "b"))
        }
        listOf("abc", "-1", ".").forEach { paid ->
            assertNull(payerRemainder("50", "", ids, mapOf("a" to paid), "b"))
        }
        assertNull(payerRemainder("50", "", ids, mapOf("a" to "50"), "b"))
        assertNull(payerRemainder("50", "", ids, mapOf("a" to "51"), "b"))
    }
}
