package org.fairshare.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.fairshare.app.model.GroupTheme
import org.fairshare.app.ui.balanceMeaningColor
import org.fairshare.app.ui.groupColors

class GroupBalanceColorsTest {
    @Test fun debtAndCreditStayLegibleAcrossEveryGroupThemeAndAppearance() {
        listOf(false, true).forEach { dark ->
            val base = if (dark) darkColorScheme(surface = Color(0xFF141C19), surfaceContainerLow = Color(0xFF1B2520))
                else lightColorScheme(surface = Color(0xFFF7F6F0), surfaceContainerLow = Color.White)
            val credit = balanceMeaningColor(100L, dark, base.onSurfaceVariant)
            val debt = balanceMeaningColor(-100L, dark, base.onSurfaceVariant)
            assertTrue(credit.green > credit.red, "Receivable must remain green")
            assertTrue(debt.red > debt.green, "Debt must remain red")
            GroupTheme.entries.forEach { theme ->
                val themed = groupColors(base, theme, dark)
                val themedDark = themed.surface.luminance() < .5f
                assertEquals(credit, balanceMeaningColor(100L, themedDark, themed.onSurfaceVariant))
                assertEquals(debt, balanceMeaningColor(-100L, themedDark, themed.onSurfaceVariant))
                assertEquals(themed.onSurfaceVariant, balanceMeaningColor(0L, themedDark, themed.onSurfaceVariant))
                listOf(themed.surface, themed.surfaceContainerLow).forEach { surface ->
                    listOf(credit, debt).forEach { foreground ->
                        val luminances = listOf(foreground.luminance(), surface.luminance()).sorted()
                        assertTrue((luminances[1] + .05f) / (luminances[0] + .05f) >= 4.5f, "$theme contrast should support normal balance labels")
                    }
                }
            }
        }
    }
}
