package org.fairshare.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.fairshare.app.domain.Ledger
import org.fairshare.app.math.BigInteger
import org.fairshare.app.model.AppState
import org.fairshare.app.model.SELF_ID
import org.fairshare.app.platform.moneyText

internal data class MonthlyGroupSpending(val month: LocalDate, val amountMinor: BigInteger, val ownShareMinor: BigInteger = BigInteger.ZERO, val ownPaidMinor: BigInteger = BigInteger.ZERO) {
    val label: String get() = "${monthLabels[month.monthNumber - 1]} ${month.year}"
}

private val monthLabels = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Calendar expense dates use UTC, matching the date entered in the expense form. */
internal fun monthlyGroupSpending(state: AppState, groupId: String, currency: String): List<MonthlyGroupSpending> {
    val recorded = state.expenses.asSequence()
        .filter { it.groupId == groupId && !it.deleted && Ledger.expenseCurrency(state, it) == currency }
        .groupBy {
            val date = Instant.fromEpochMilliseconds(it.date).toLocalDateTime(TimeZone.UTC).date
            LocalDate(date.year, date.monthNumber, 1)
        }
        .mapValues { (month, expenses) -> MonthlyGroupSpending(month,
            expenses.fold(BigInteger.ZERO) { sum, expense -> sum + BigInteger.valueOf(expense.amountMinor) },
            expenses.fold(BigInteger.ZERO) { sum, expense -> sum + BigInteger.valueOf(expense.owed[SELF_ID] ?: 0L) },
            expenses.fold(BigInteger.ZERO) { sum, expense -> sum + BigInteger.valueOf(Ledger.expensePayers(expense)[SELF_ID] ?: 0L) }) }
    val last = recorded.keys.maxOrNull() ?: return emptyList()
    // Anchor to the most recent recorded expense, including quiet months as zero. Historical
    // ledgers stay useful; opening a chart does not inject today's date into stored data.
    val lastIndex = last.year * 12 + last.monthNumber - 1
    return (lastIndex - 5..lastIndex).map { index ->
        val month = LocalDate(index / 12, index % 12 + 1, 1)
        recorded[month] ?: MonthlyGroupSpending(month, BigInteger.ZERO)
    }
}

/** Divide before converting to Float so aggregate totals never pass through Long. */
internal fun spendingBarFraction(amount: BigInteger, peak: BigInteger): Float {
    if (amount <= BigInteger.ZERO || peak <= BigInteger.ZERO) return 0f
    val bounded = minOf(amount, peak)
    return (bounded * BigInteger.valueOf(1000)).divideAndRemainder(peak).first().toInt() / 1000f
}

internal data class MemberGroupSpending(val memberId: String, val shareMinor: BigInteger, val paidMinor: BigInteger)

/** Shares are consumption; paid contributions are funding. Settlements are neither. */
internal fun memberGroupSpending(state: AppState, groupId: String, currency: String): List<MemberGroupSpending> {
    val expenses = state.expenses.filter { it.groupId == groupId && !it.deleted && Ledger.expenseCurrency(state, it) == currency }
    val ids = (state.groups.firstOrNull { it.id == groupId }?.memberIds.orEmpty() +
        expenses.flatMap { it.owed.keys + Ledger.expensePayers(it).keys }).distinct()
    return ids.map { id ->
        MemberGroupSpending(id,
            expenses.fold(BigInteger.ZERO) { sum, expense -> sum + BigInteger.valueOf(expense.owed[id] ?: 0L) },
            expenses.fold(BigInteger.ZERO) { sum, expense -> sum + BigInteger.valueOf(Ledger.expensePayers(expense)[id] ?: 0L) })
    }
}

@Composable
internal fun GroupSpendingChart(state: AppState, groupId: String, currency: String) {
    val months = monthlyGroupSpending(state, groupId, currency)
    if (months.isEmpty()) return
    var membersView by rememberSaveable(groupId, currency) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("group_monthly_chart_$currency")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !membersView, onClick = { membersView = false }, label = { Text("Monthly") }, modifier = Modifier.testTag("chart_monthly_$currency"))
                FilterChip(selected = membersView, onClick = { membersView = true }, label = { Text("Members") }, modifier = Modifier.testTag("chart_members_$currency"))
            }
            if (!membersView) {
                MonthlyVerticalChart(months, currency)
            } else {
                Text("Each person's share", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("All recorded expenses · payments excluded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val members = memberGroupSpending(state, groupId, currency)
                val peak = members.maxOfOrNull { it.shareMinor } ?: BigInteger.ZERO
                members.forEach { member ->
                    val name = if (member.memberId == SELF_ID) "You" else state.members.firstOrNull { it.id == member.memberId }?.name ?: "Former member"
                    val share = moneyText(member.shareMinor, currency)
                    val paid = moneyText(member.paidMinor, currency)
                    Column(Modifier.fillMaxWidth().testTag("chart_member_${member.memberId}_$currency").clearAndSetSemantics { contentDescription = "$name, share $share $currency, paid $paid $currency. Payments excluded." }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(share, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        SpendingBar(member.shareMinor, peak, true)
                        Text("Paid $paid", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Stack consumption only: your assigned share + everyone else's share = group total.
 * Paid funding overlaps consumption, so it is shown separately below the graph. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthlyVerticalChart(months: List<MonthlyGroupSpending>, currency: String) {
    var selectedIndex by rememberSaveable(months.map { it.month.toString() }, currency) { mutableStateOf(months.lastIndex) }
    var stacked by rememberSaveable(currency) { mutableStateOf(true) }
    val selectedMonth = months[selectedIndex.coerceIn(months.indices)]
    val peak = months.maxOf { it.amountMinor }.let { if (it > BigInteger.ZERO) it else BigInteger.ONE }
    val ownColor = MaterialTheme.colorScheme.primary
    val otherColor = MaterialTheme.colorScheme.tertiaryContainer
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Text("Monthly spending", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !stacked, onClick = { stacked = false }, label = { Text("Total") }, modifier = Modifier.testTag("chart_total_$currency"))
        FilterChip(selected = stacked, onClick = { stacked = true }, label = { Text("By share") }, modifier = Modifier.testTag("chart_stacked_$currency"))
    }
    if (stacked) FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ChartLegend("Your share", ownColor)
        ChartLegend("Others' share", otherColor)
    }
    Text("${months.first().label} – ${months.last().label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(60.dp).height(160.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf(peak, peak.divideAndRemainder(BigInteger.valueOf(2)).first(), BigInteger.ZERO).forEach { tick ->
                Text(moneyText(tick, currency), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                listOf(0f, .5f, 1f).forEach { fraction ->
                    val y = (size.height * fraction).coerceAtMost(size.height - 1f)
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                months.forEachIndexed { index, month ->
                    val total = moneyText(month.amountMinor, currency)
                    val own = moneyText(month.ownShareMinor, currency)
                    val paid = moneyText(month.ownPaidMinor, currency)
                    val isSelected = index == selectedIndex
                    Column(Modifier.weight(1f)
                        .testTag("chart_month_${month.month}_$currency")
                        .clickable(role = Role.Button, onClickLabel = "Compare ${month.label}") { selectedIndex = index }
                        .clearAndSetSemantics {
                            contentDescription = "${month.label}, group total $total $currency, your share $own $currency, you paid $paid $currency. Select month to compare."
                            selected = isSelected
                            role = Role.Button
                            onClick("Compare ${month.label}") { selectedIndex = index; true }
                        }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.BottomCenter) {
                            val barFraction = spendingBarFraction(month.amountMinor, peak)
                            Column(Modifier.width(26.dp).height(160.dp * barFraction)
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .background(if (stacked) otherColor else ownColor.copy(alpha = if (isSelected) 1f else .65f)),
                                verticalArrangement = Arrangement.Bottom) {
                                if (stacked && month.amountMinor > BigInteger.ZERO) {
                                    Spacer(Modifier.fillMaxWidth().fillMaxHeight(spendingBarFraction(month.ownShareMinor, month.amountMinor)).background(ownColor))
                                }
                            }
                        }
                        Text(monthLabels[month.month.monthNumber - 1], style = MaterialTheme.typography.labelSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) ownColor else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
                    }
                }
            }
        }
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selectedMonth.label, style = MaterialTheme.typography.titleSmall)
                Text(moneyText(selectedMonth.amountMinor, currency), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text("Your share ${moneyText(selectedMonth.ownShareMinor, currency)} · You paid ${moneyText(selectedMonth.ownPaidMinor, currency)}", style = MaterialTheme.typography.bodySmall)
            val previous = months.getOrNull(selectedIndex - 1)
            if (previous != null) {
                val change = selectedMonth.amountMinor - previous.amountMinor
                Text(if (change == BigInteger.ZERO) "Same spending as ${previous.label}" else "${moneyText(change.abs(), currency)} ${if (change.signum() > 0) "more" else "less"} than ${previous.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Text("Expense dates · Payments excluded · Tap a month to compare", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChartLegend(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SpendingBar(amount: BigInteger, peak: BigInteger, own: Boolean) {
    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(spendingBarFraction(amount, peak)).height(5.dp).background(if (own) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)))
    }
}
