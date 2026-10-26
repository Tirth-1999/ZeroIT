package org.fairshare.app.domain

import org.fairshare.app.math.BigInteger
import org.fairshare.app.math.BigDecimal
import org.fairshare.app.math.toBigDecimal
import org.fairshare.app.platform.currencyDigits
import org.fairshare.app.platform.moneyText
import org.fairshare.app.model.Expense
import org.fairshare.app.model.Settlement
import org.fairshare.app.model.AppState
import org.fairshare.app.model.SplitMode
import org.fairshare.app.model.Transfer
import org.fairshare.app.model.SELF_ID

/** All persisted money is expressed in currency minor units. Positive balances are receivables. */
object Ledger {
    fun split(
        total: Long,
        ids: List<String>,
        mode: SplitMode,
        values: Map<String, String> = emptyMap(),
        currency: String = "USD",
    ): Map<String, Long> {
        require(total >= 0) { "Amount cannot be negative" }
        require(ids.isNotEmpty() && ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) {
            "Select unique participants"
        }
        require(values.keys.all { it in ids }) { "Split contains an unknown participant" }
        val amount = BigInteger.valueOf(total)
        val allocation = when (mode) {
            SplitMode.EQUAL -> equal(amount, ids.size)
            SplitMode.EXACT -> ids.map { BigInteger.valueOf(parseMoney(values[it].orEmpty().ifBlank { "0" }, currency)) }
                .also { shares ->
                    require(shares.all { it.signum() >= 0 }) { "Exact amounts cannot be negative" }
                    require(shares.fold(BigInteger.ZERO, BigInteger::add) == amount) { "Exact amounts must add up to the expense total" }
                }
            SplitMode.PERCENT, SplitMode.SHARES -> {
                val weights = ids.map { decimal(values[it].orEmpty().ifBlank { "0" }) }
                require(weights.all { it.signum() >= 0 }) { "Split weights cannot be negative" }
                val sum = weights.fold(BigDecimal.ZERO, BigDecimal::add)
                require(sum.signum() > 0) { "Enter at least one positive share" }
                if (mode == SplitMode.PERCENT) require(sum.compareTo(BigDecimal("100")) == 0) { "Percentages must add up to 100" }
                weighted(amount, weights)
            }
            SplitMode.ADJUSTMENT -> {
                val extra = ids.map { BigInteger.valueOf(parseMoney(values[it].orEmpty().ifBlank { "0" }, currency)) }
                val remainder = amount - extra.fold(BigInteger.ZERO, BigInteger::add)
                equal(remainder, ids.size).zip(extra) { base, adjustment -> base + adjustment }
                    .also { shares -> require(shares.all { it.signum() >= 0 }) { "Adjustments would give someone a negative share" } }
            }
        }
        return ids.zip(allocation.map(::asLong)).toMap()
    }

    fun expenseCurrency(state: AppState, expense: Expense): String =
        checkedCurrency(expense.currency ?: state.groups.first { it.id == expense.groupId }.currency)

    fun settlementCurrency(state: AppState, settlement: Settlement): String =
        checkedCurrency(settlement.currency ?: state.groups.first { it.id == settlement.groupId }.currency)

    /** The group's preference remains available even before any expense is entered. No conversion occurs. */
    fun groupCurrencies(state: AppState, groupId: String): List<String> {
        val group = state.groups.firstOrNull { it.id == groupId } ?: throw IllegalArgumentException("Group not found")
        return (listOf(checkedCurrency(group.currency)) +
            state.expenses.filter { it.groupId == groupId && !it.deleted }.map { expenseCurrency(state, it) } +
            state.settlements.filter { it.groupId == groupId && !it.deleted }.map { settlementCurrency(state, it) }).distinct()
    }

    fun expensePayers(expense: Expense): Map<String, Long> =
        expense.paidBy.ifEmpty { mapOf(expense.payerId to expense.amountMinor) }

    fun balances(state: AppState, groupId: String, currency: String? = null): Map<String, Long> {
        val group = state.groups.find { it.id == groupId } ?: throw IllegalArgumentException("Group not found")
        val selectedCurrency = checkedCurrency(currency ?: group.currency)
        require(group.memberIds.distinct().size == group.memberIds.size) { "Group contains duplicate members" }
        val net = group.memberIds.associateWith { BigInteger.ZERO }.toMutableMap()
        fun add(id: String, amount: BigInteger) {
            require(id in net) { "Record contains an unknown group member" }
            net[id] = net.getValue(id) + amount
        }
        state.expenses.filter { it.groupId == groupId && !it.deleted && expenseCurrency(state, it) == selectedCurrency }.forEach { expense ->
            require(expense.amountMinor > 0 && expense.owed.isNotEmpty()) { "Invalid expense amount or participants" }
            require(expense.owed.values.all { it >= 0 }) { "Expense shares cannot be negative" }
            val amount = BigInteger.valueOf(expense.amountMinor)
            require(expense.owed.values.fold(BigInteger.ZERO) { sum, share -> sum + BigInteger.valueOf(share) } == amount) { "Expense shares do not match its total" }
            val paid = expensePayers(expense)
            require(expense.payerId in net) { "Record contains an unknown group member" }
            require(paid.values.all { it >= 0 } && paid.values.fold(BigInteger.ZERO) { sum, n -> sum + BigInteger.valueOf(n) } == amount) { "Payments must add up to the expense total" }
            paid.forEach { (id, payment) -> add(id, BigInteger.valueOf(payment)) }
            expense.owed.forEach { (id, share) -> add(id, -BigInteger.valueOf(share)) }
        }
        state.settlements.filter { it.groupId == groupId && !it.deleted && settlementCurrency(state, it) == selectedCurrency }.forEach { settlement ->
            require(settlement.amountMinor > 0 && settlement.fromId != settlement.toId) { "Invalid settlement" }
            val amount = BigInteger.valueOf(settlement.amountMinor)
            add(settlement.fromId, amount)
            add(settlement.toId, -amount)
        }
        return net.mapValues { asLong(it.value) }
    }

    /** Per-group configured settlement relationships, summed without currency conversion.
     * Positive means the friend owes the local profile. Simplified groups route through their
     * configured suggestions so a settled group cannot retain phantom original-share debt.
     */
    fun friendBalances(state: AppState, friendId: String): Map<String, Long> {
        require(friendId != SELF_ID && state.members.any { it.id == friendId }) { "Friend not found" }
        val net = linkedMapOf<String, BigInteger>()
        state.groups.filter { SELF_ID in it.memberIds && friendId in it.memberIds }.forEach { group ->
            groupCurrencies(state, group.id).forEach { currency ->
                var amount = BigInteger.ZERO
                transfers(state, group.id, currency).forEach { transfer ->
                    val value = BigInteger.valueOf(transfer.amountMinor)
                    if (transfer.fromId == friendId && transfer.toId == SELF_ID) amount += value
                    if (transfer.fromId == SELF_ID && transfer.toId == friendId) amount -= value
                }
                net[currency] = (net[currency] ?: BigInteger.ZERO) + amount
            }
        }
        return net.mapValues { asLong(it.value) }
    }

    /** Suggestions only: toggling this preference never changes any financial record. */
    fun transfers(state: AppState, groupId: String, currency: String? = null): List<Transfer> {
        val group = state.groups.firstOrNull { it.id == groupId } ?: throw IllegalArgumentException("Group not found")
        return if (group.simplifyDebts) simplifiedTransfers(state, groupId, currency)
        else directTransfers(state, groupId, currency)
    }

    /** Net each original payer/participant pair, including payments on that pair only. */
    fun directTransfers(state: AppState, groupId: String, currency: String? = null): List<Transfer> =
        buildList {
            directEdges(state, groupId, currency).forEach { (pair, value) ->
                if (value.signum() > 0) appendTransfers(this, pair.first, pair.second, value)
                else if (value.signum() < 0) appendTransfers(this, pair.second, pair.first, -value)
            }
        }

    /** Deterministic greedy routing; never suggests more payments than the direct baseline.
     * This is not a guarantee of a globally minimum payment count.
     */
    fun simplifiedTransfers(state: AppState, groupId: String, currency: String? = null): List<Transfer> {
        val direct = directTransfers(state, groupId, currency)
        val net = balances(state, groupId, currency)
        val debtors = net.filterValues { it < 0 }.map { it.key to -BigInteger.valueOf(it.value) }.toMutableList()
        val creditors = net.filterValues { it > 0 }.map { it.key to BigInteger.valueOf(it.value) }.toMutableList()
        val result = mutableListOf<Transfer>()
        var d = 0
        var c = 0
        while (d < debtors.size && c < creditors.size) {
            val (from, debt) = debtors[d]
            val (to, credit) = creditors[c]
            val amount = debt.min(credit).min(BigInteger.valueOf(Long.MAX_VALUE))
            result += Transfer(from, to, asLong(amount))
            debtors[d] = from to (debt - amount)
            creditors[c] = to to (credit - amount)
            if (debt == amount) d++
            if (credit == amount) c++
        }
        check(d == debtors.size && c == creditors.size) { "Balances must sum to zero" }
        return if (result.size <= direct.size) result else direct
    }

    /** A zero net balance can still have bilateral debts when simplification is disabled. */
    fun memberHasDebt(state: AppState, groupId: String, memberId: String): Boolean {
        val group = state.groups.firstOrNull { it.id == groupId } ?: throw IllegalArgumentException("Group not found")
        require(memberId in group.memberIds) { "Group member not found" }
        return groupCurrencies(state, groupId).any { currency ->
            transfers(state, groupId, currency).any { it.fromId == memberId || it.toId == memberId }
        }
    }

    /** Signed canonical edges: positive means pair.first owes pair.second. */
    private fun directEdges(state: AppState, groupId: String, currency: String?): Map<Pair<String, String>, BigInteger> {
        val group = state.groups.firstOrNull { it.id == groupId } ?: throw IllegalArgumentException("Group not found")
        val selectedCurrency = checkedCurrency(currency ?: group.currency)
        // Keep stored-record validation identical across balances and both suggestion modes.
        balances(state, groupId, selectedCurrency)
        val order = group.memberIds.withIndex().associate { it.value to it.index }
        val edges = linkedMapOf<Pair<String, String>, BigInteger>()
        fun add(from: String, to: String, amount: BigInteger) {
            if (from == to || amount.signum() == 0) return
            val forward = order.getValue(from) < order.getValue(to)
            val pair = if (forward) from to to else to to from
            edges[pair] = (edges[pair] ?: BigInteger.ZERO) + if (forward) amount else -amount
        }
        state.expenses.filter { it.groupId == groupId && !it.deleted && expenseCurrency(state, it) == selectedCurrency }.forEach expenseLoop@ { expense ->
            val paid = expensePayers(expense)
            val payerIds = group.memberIds.filter { (paid[it] ?: 0L) > 0L }
            if (payerIds.size == 1) {
                // A sole payer receives each participant's full share. Running the
                // weighted largest-remainder allocator 60 times per expense is
                // both unnecessary and costly on large group feeds.
                val payer = payerIds.single()
                group.memberIds.forEach { debtor ->
                    val share = BigInteger.valueOf(expense.owed[debtor] ?: 0L)
                    if (share.signum() > 0) add(debtor, payer, share)
                }
                return@expenseLoop
            }
            val remaining = payerIds.map { BigInteger.valueOf(paid.getValue(it)) }.toMutableList()
            group.memberIds.forEach { debtor ->
                val share = BigInteger.valueOf(expense.owed[debtor] ?: 0L)
                if (share.signum() > 0) {
                    // Stable largest-remainder allocation consumes exactly the paid contributions.
                    weighted(share, remaining.map { BigDecimal(it.toString()) }).forEachIndexed { index, value ->
                        add(debtor, payerIds[index], value)
                        remaining[index] -= value
                    }
                }
            }
        }
        state.settlements.filter { it.groupId == groupId && !it.deleted && settlementCurrency(state, it) == selectedCurrency }.forEach {
            add(it.fromId, it.toId, -BigInteger.valueOf(it.amountMinor))
        }
        return edges
    }

    private fun appendTransfers(result: MutableList<Transfer>, from: String, to: String, total: BigInteger) {
        var remaining = total
        while (remaining.signum() > 0) {
            val part = remaining.min(BigInteger.valueOf(Long.MAX_VALUE))
            result += Transfer(from, to, asLong(part))
            remaining -= part
        }
    }

    fun parseMoney(text: String, currency: String = "USD"): Long {
        val digits = fractionDigits(currency)
        val value = decimal(text)
        return try {
            value.movePointRight(digits).toBigIntegerExact().let(::asLong)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Use at most $digits decimal places for $currency")
        }
    }

    fun formatMoney(minor: Long, currency: String = "USD"): String = moneyText(BigInteger.valueOf(minor), currency)

    private fun checkedCurrency(currency: String): String = currency.also {
        require(it.length == 3 && it == it.uppercase() && currencyDigits(it) in 0..6) { "Choose a supported currency" }
    }

    private fun fractionDigits(currency: String): Int = currencyDigits(currency)

    private fun decimal(text: String): BigDecimal {
        val clean = text.trim()
        require(clean.length in 1..128 && clean.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) { "Enter a valid decimal number" }
        return clean.toBigDecimal()
    }

    private fun asLong(value: BigInteger): Long {
        require(value >= BigInteger.valueOf(Long.MIN_VALUE) && value <= BigInteger.valueOf(Long.MAX_VALUE)) {
            "Amount exceeds supported range"
        }
        return value.toLong()
    }

    private fun equal(total: BigInteger, count: Int): List<BigInteger> {
        val (base, remainder) = total.divideAndRemainder(BigInteger.valueOf(count.toLong()))
        return List(count) { index -> base + if (index < remainder.abs().toInt()) BigInteger.valueOf(remainder.signum().toLong()) else BigInteger.ZERO }
    }

    private fun weighted(total: BigInteger, weights: List<BigDecimal>): List<BigInteger> {
        val scale = weights.maxOf { it.scale() }
        val units = weights.map { it.setScale(scale).unscaledValue() }
        val sum = units.fold(BigInteger.ZERO, BigInteger::add)
        val quotients = units.map { (total * it).divideAndRemainder(sum) }
        val allocated = quotients.map { it[0] }.toMutableList()
        val remaining = (total - allocated.fold(BigInteger.ZERO, BigInteger::add)).toInt()
        quotients.indices.sortedWith(compareByDescending<Int> { quotients[it][1] }.thenBy { it })
            .take(remaining).forEach { allocated[it] += BigInteger.ONE }
        return allocated
    }
}
