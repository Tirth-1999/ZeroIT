package org.fairshare.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.fairshare.app.model.Group
import org.fairshare.app.ui.isContextualSettlement

class ContextualSettlementTest {
    private val group = Group("g", "Travel", memberIds = listOf("a", "b", "removed"), removedMemberIds = listOf("removed"))

    @Test fun onlyCompleteValidSuggestionsStartInCompactMode() {
        assertTrue(isContextualSettlement(group, "a", "b", "6.51", "USD"))
        assertTrue(isContextualSettlement(group, "b", "a", "650", "JPY"))
        assertFalse(isContextualSettlement(group, "", "", "", ""))
        assertFalse(isContextualSettlement(group, "a", "a", "6.51", "USD"))
        assertFalse(isContextualSettlement(group, "removed", "b", "6.51", "USD"))
        assertFalse(isContextualSettlement(group, "a", "b", "6.51", ""))
        assertFalse(isContextualSettlement(group, "a", "b", "0", "USD"))
        assertFalse(isContextualSettlement(group, "a", "b", "-2", "USD"))
        assertFalse(isContextualSettlement(group, "a", "b", "bad", "USD"))
        assertFalse(isContextualSettlement(group, "a", "b", "6.51", "JPY"))
        assertFalse(isContextualSettlement(group, "a", "b", "6.51", "ZZZ"))
    }
}
