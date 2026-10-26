package org.fairshare.app.ui

import kotlin.test.*
import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.*

class NonGroupProjectionTest {
    @Test fun nonGroupViewCombinesOnlyBackingLedgersAndResolvesLegacyCurrencyBeforeMapping() {
        val people = listOf(Member(SELF_ID, "You"), Member("alice", "Alice"), Member("bob", "Bob"))
        val usd = Group("direct-usd", "Alice", "USD", listOf(SELF_ID, "alice"), kind = "DIRECT")
        val eur = Group("people-eur", "Non-group expenses", "EUR", people.map { it.id }, kind = "NONGROUP")
        val regular = Group("regular", "Holiday", "GBP", people.map { it.id }, kind = "GROUP")
        val source = AppState(members = people, groups = listOf(usd, eur, regular), expenses = listOf(
            Expense("usd-legacy", usd.id, "Coffee", 100, SELF_ID, mapOf("alice" to 100)),
            Expense("eur-legacy", eur.id, "Lunch", 300, "bob", people.associate { it.id to 100L }),
            Expense("foreign-usd", eur.id, "Tickets", 200, "alice", mapOf(SELF_ID to 200), currency = "USD"),
            Expense("deleted", eur.id, "Removed", 700, SELF_ID, mapOf("bob" to 700), deleted = true),
            Expense("regular-only", regular.id, "Hotel", 900, SELF_ID, mapOf("alice" to 900))
        ), settlements = listOf(
            Settlement("usd-payment", usd.id, "alice", SELF_ID, 20),
            Settlement("eur-payment", eur.id, SELF_ID, "bob", 50),
            Settlement("foreign-payment", eur.id, SELF_ID, "alice", 40, currency = "USD"),
            Settlement("regular-payment", regular.id, "alice", SELF_ID, 100)
        ), activity = listOf(
            Activity("usd-event", usd.id, "Coffee added"),
            Activity("eur-event", eur.id, "Lunch added"),
            Activity("regular-event", regular.id, "Hotel added")
        ))
        val before = source.copy()
        val (projected, group) = nonGroupProjection(source)

        assertEquals(NON_GROUP_ID, group.id)
        assertEquals("Non-group expenses", group.name)
        assertTrue(group.isNonGroup)
        assertEquals(listOf(SELF_ID, "alice", "bob"), group.memberIds)
        assertEquals(listOf(group), projected.groups)
        assertEquals(people, projected.members)
        assertEquals(listOf("usd-legacy", "eur-legacy", "foreign-usd", "deleted"), projected.expenses.map { it.id })
        assertEquals(listOf("USD", "EUR", "USD", "EUR"), projected.expenses.map { it.currency })
        assertTrue(projected.expenses.all { it.groupId == NON_GROUP_ID })
        assertTrue(projected.expenses.single { it.id == "deleted" }.deleted)
        assertEquals(listOf("usd-payment", "eur-payment", "foreign-payment"), projected.settlements.map { it.id })
        assertEquals(listOf("USD", "EUR", "USD"), projected.settlements.map { it.currency })
        assertTrue(projected.settlements.all { it.groupId == NON_GROUP_ID })
        assertEquals(listOf("usd-event", "eur-event"), projected.activity.map { it.id })
        assertTrue(projected.activity.all { it.groupId == NON_GROUP_ID })
        assertEquals(listOf("Coffee added", "Lunch added"), projected.activity.map { it.message })
        assertEquals(mapOf(SELF_ID to -80L, "alice" to 80L, "bob" to 0L), Ledger.balances(projected, NON_GROUP_ID, "USD"))
        assertEquals(mapOf(SELF_ID to -50L, "alice" to -100L, "bob" to 150L), Ledger.balances(projected, NON_GROUP_ID, "EUR"))
        assertEquals(listOf("USD", "EUR"), Ledger.groupCurrencies(projected, NON_GROUP_ID))
        assertEquals(before, source)
        assertNull(source.expenses.first().currency)
        assertEquals(usd.id, source.expenses.first().groupId)
        assertEquals(eur.id, source.settlements[1].groupId)
    }

    @Test fun emptyNonGroupViewDoesNotIncludeRegularGroupsOrTransactions() {
        val regular = Group("regular", "Home", "EUR", listOf(SELF_ID), kind = "GROUP")
        val source = AppState(groups = listOf(regular), expenses = listOf(Expense("e", regular.id, "Supplies", 100, SELF_ID, mapOf(SELF_ID to 100))))
        val (projected, group) = nonGroupProjection(source)
        assertEquals(listOf(SELF_ID), group.memberIds)
        assertTrue(projected.expenses.isEmpty())
        assertTrue(projected.settlements.isEmpty())
        assertTrue(projected.activity.isEmpty())
        assertEquals(mapOf(SELF_ID to 0L), Ledger.balances(projected, group.id))
        assertEquals(listOf(regular), source.groups)
    }
}
