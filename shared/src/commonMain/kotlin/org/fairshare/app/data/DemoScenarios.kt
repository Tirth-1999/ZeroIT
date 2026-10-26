package org.fairshare.app.data

import org.fairshare.app.model.*
import kotlinx.datetime.LocalDate
import org.fairshare.app.platform.parseCalendarDate
import org.fairshare.app.platform.todayDate

/** Add-only, repeatable fixtures: existing groups, edits and profile are never replaced. */
internal fun AppState.withDemoScenarios(): AppState {
    var result = this
    val today = parseCalendarDate(todayDate())
    val day = 86_400_000L
    fun add(key: String, name: String, currency: String, names: List<String>, items: List<Pair<String, Long>>, settled: Boolean = false, direct: Boolean = false, dates: List<Long>? = null, currencies: List<String>? = null) {
        val groupId = "demo-scenarios-$key"
        if (result.groups.any { it.id == groupId }) return
        val people = names.mapIndexed { index, person -> Member("demo-scenarios-$key-person-$index", person) }
        val ids = listOf(SELF_ID) + people.map { it.id }
        val group = Group(groupId, name, currency, ids, dates?.minOrNull() ?: (today - 14 * day), if (direct) "DIRECT" else "GROUP")
        val expenses = items.mapIndexed { index, (title, amount) ->
            val unequal = !settled && index % 4 == 2
            val shares = if (unequal) {
                val weights = ids.indices.map { it + 1L }
                val totalWeight = weights.sum()
                val portions = weights.map { amount * it / totalWeight }.toMutableList()
                portions[0] += amount - portions.sum()
                ids.mapIndexed { i, id -> id to portions[i] }.toMap()
            } else ids.mapIndexed { i, id -> id to (amount / ids.size + if (i < amount % ids.size) 1 else 0) }.toMap()
            Expense("$groupId-expense-$index", groupId, title, amount, if (settled) SELF_ID else ids[(index + if (dates == null) 1 else 0) % ids.size], shares,
                dates?.get(index) ?: (today - (items.size - 1 - index).toLong() / 2 * day), currency = currencies?.get(index),
                notes = if (unequal) "Demo expense · Unequal shares; open the split editor to inspect them." else when (index % 3) { 0 -> "Demo expense · Try editing the amount, payer or split."; 1 -> "Demo expense · Shared equally, including any remaining cent."; else -> "Demo expense · Payment recorded for testing only." })
        }
        val payments = if (settled && expenses.isNotEmpty()) ids.drop(1).map { person ->
            Settlement("$groupId-payment-$person", groupId, person, SELF_ID, expenses.sumOf { it.owed.getValue(person) }, today)
        } else emptyList()
        val history = listOf(Activity("$groupId-created", groupId, "Created demo group $name", group.createdAt)) +
            expenses.map { Activity("${it.id}-added", groupId, "Added ${it.title}: ${org.fairshare.app.domain.Ledger.formatMoney(it.amountMinor, it.currency ?: currency)}", it.date, expenseId = it.id) } +
            payments.map { Activity("${it.id}-recorded", groupId, "Recorded demo settlement (no money transferred)", it.date, settlementId = it.id) }
        result = result.copy(members = result.members + people.filter { person -> result.members.none { it.id == person.id } }, groups = result.groups + group,
            expenses = result.expenses + expenses, settlements = result.settlements + payments, activity = result.activity + history)
    }
    add("home", "Demo · Maple House", "USD", listOf("Maya", "Leo", "Priya"), listOf("October rent" to 240000L, "Weekly groceries" to 12487L, "Internet" to 7999L, "Electricity" to 9632L, "Kitchen supplies" to 3850L, "Pizza night" to 5820L, "Cleaning supplies" to 2749L, "Coffee beans" to 1860L, "Fresh groceries" to 8735L, "Water bill" to 4200L))
    add("trip", "Demo · Lisbon Trip", "EUR", listOf("Noah", "Sofia", "Ava"), listOf("Apartment · three nights" to 48600L, "Airport train" to 2880L, "Dinner by the river" to 9235L, "Museum tickets" to 6400L, "Pastries and coffee" to 1875L, "Day trip tickets" to 7600L, "Taxi home" to 2140L, "Last-night dinner" to 11850L))
    add("weekend", "Demo · Lake Weekend", "USD", listOf("Ethan", "Zoe"), listOf("Cabin deposit" to 21000L, "Road-trip fuel" to 6341L, "BBQ groceries" to 9578L, "Kayak rental" to 9000L, "Breakfast stop" to 3275L, "Parking" to 1500L))
    add("settled", "Demo · Birthday Dinner", "USD", listOf("Grace", "Oliver"), listOf("Birthday dinner" to 12600L, "Cake" to 3600L), settled = true)
    add("planning", "Demo · Next Adventure", "USD", listOf("Amelia", "Lucas"), emptyList())
    add("friend", "Demo · Coffee with Jamie", "USD", listOf("Jamie (demo)"), listOf("Coffee catch-up" to 1250L, "Lunch together" to 3860L, "Cinema tickets" to 3000L), direct = true)
    // A new fixture identity extends existing demo installs without replacing edited samples.
    // Calendar months (not fixed 30-day intervals) keep the comparison useful across year boundaries.
    val currentDate = LocalDate.parse(todayDate())
    val monthlyItems = mutableListOf<Pair<String, Long>>()
    val monthlyDates = mutableListOf<Long>()
    val monthlyCurrencies = mutableListOf<String>()
    for (monthsAgo in 5 downTo 0) {
        val absoluteMonth = currentDate.year * 12 + currentDate.monthNumber - 1 - monthsAgo
        val year = absoluteMonth / 12
        val month = absoluteMonth % 12 + 1
        val prefix = "$year-${month.toString().padStart(2, '0')}"
        val monthIndex = 5 - monthsAgo
        val titles = listOf("Shared groceries", "Weekend plans", "Travel tickets")
        val amounts = listOf(12600L + monthIndex * 2350L, 7800L + (monthIndex % 3) * 4200L, 6400L + monthIndex * 1700L)
        titles.forEachIndexed { index, title ->
            val dayOfMonth = if (monthsAgo == 0) minOf(3 + index * 5, currentDate.dayOfMonth) else 3 + index * 5
            monthlyItems += "$title · $prefix" to amounts[index]
            monthlyDates += parseCalendarDate("$prefix-${dayOfMonth.toString().padStart(2, '0')}")
            monthlyCurrencies += if (index == 2) "EUR" else "USD"
        }
    }
    add("monthly-v1", "Demo · Six-month spending", "USD", listOf("Maya (demo)", "Sam (demo)"),
        monthlyItems, dates = monthlyDates, currencies = monthlyCurrencies)
    return result
}
