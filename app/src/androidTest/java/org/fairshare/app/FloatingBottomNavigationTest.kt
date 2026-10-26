package org.fairshare.app

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.ui.FairshareApp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Touches the rendered tabs and samples the moving capsule, rather than setting selection state. */
class FloatingBottomNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun capsuleSlidesAndRetargetsBeforeThePreviousSlideEnds() = withNavigation(LayoutDirection.Ltr) {
        val start = capsuleCenter("start")
        compose.onNodeWithTag("nav_account").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(96)
        val middle = capsuleCenter("middle")
        assertTrue("The capsule must visibly move between tabs", middle > start + .03f && middle < .85f)
        // Use a 1 ms pointer tap so the synthetic event's default 60 ms
        // duration does not advance a quarter of the 220 ms animation.
        // Return to Groups: a remounted/snapped capsule would jump >.25 of
        // the bar here; a continuous animation can move during event delivery.
        compose.onNodeWithTag("nav_groups").performTouchInput {
            down(center); advanceEventTime(1); up()
        }
        compose.mainClock.advanceTimeByFrame()
        val retargeted = capsuleCenter("retargeted")
        assertTrue("An interrupted slide must continue from its current position: middle=$middle, after=$retargeted",
            abs(retargeted - middle) < .20f)
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("nav_groups").assertIsSelected()
        assertTrue("The capsule must finish behind Groups", abs(capsuleCenter("finished") - .125f) < .025f)
    }

    @Test fun capsuleRespectsRightToLeftTabPositions() = withNavigation(LayoutDirection.Rtl) {
        assertTrue("Groups starts at the right in RTL", abs(capsuleCenter("rtl-start") - .875f) < .025f)
        compose.onNodeWithTag("nav_activity").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("nav_activity").assertIsSelected()
        assertTrue("Activity is the second physical slot in RTL", abs(capsuleCenter("finished") - .375f) < .025f)
        val track = compose.onNodeWithTag("floating_navigation_track")
        track.performTouchInput {
            down(Offset(width * .375f, centerY))
            moveTo(Offset(width * .875f, centerY), delayMillis = 300)
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("nav_activity").assertIsSelected()
        track.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("nav_groups").assertIsSelected()
        assertTrue("RTL drag release maps the right physical slot to Groups",
            abs(capsuleCenter("rtl-drag-finished") - .875f) < .03f)
    }

    @Test fun dragPreviewsWithoutNavigationUntilReleaseAndCancellationReturnsToSelection() = withNavigation(LayoutDirection.Ltr) {
        val track = compose.onNodeWithTag("floating_navigation_track")
        val start = capsuleCenter("drag-start")
        // A held horizontal pointer updates the glass preview, not the page or
        // selected accessibility tab. Keep the pointer down across assertions.
        track.performTouchInput {
            down(Offset(width * .125f, centerY))
            moveTo(Offset(width * .875f, centerY), delayMillis = 300)
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("nav_groups").assertIsSelected()
        compose.onNodeWithTag("nav_account").assertIsNotSelected()
        compose.onNodeWithTag("groups_screen").assertExists()
        assertTrue("The held glass preview must follow the drag across the bar",
            capsuleCenter("drag-held-preview") > start + .30f)
        track.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("nav_account").assertIsSelected()
        compose.onNodeWithTag("settings_name").assertExists()
        assertTrue("Release must settle behind Account", abs(capsuleCenter("drag-released") - .875f) < .03f)

        // Cancellation must spring back rather than committing the hovered tab.
        track.performTouchInput {
            down(Offset(width * .875f, centerY))
            moveTo(Offset(width * .125f, centerY), delayMillis = 300)
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("nav_account").assertIsSelected()
        track.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("nav_account").assertIsSelected()
        compose.onNodeWithTag("nav_groups").assertIsNotSelected()
        assertTrue("Cancelled dragging must restore the committed capsule",
            abs(capsuleCenter("drag-cancelled") - .875f) < .03f)

        // A vertical scroll attempt is not a tab-selection gesture.
        track.performTouchInput {
            down(Offset(width * .875f, height * .65f))
            moveTo(Offset(width * .875f, height * .20f), delayMillis = 250)
            up()
        }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("nav_account").assertIsSelected()
    }

    private fun withNavigation(direction: LayoutDirection, test: () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Motion is intentionally disabled by the system", Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f)
        val database = "floating-navigation-${System.nanoTime()}.db"
        val repository = LocalRepository(context, database)
        try {
            runBlocking { repository.setProfile("Taylor") }
            compose.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) { FairshareApp(repository) }
            }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            test()
        } finally {
            compose.mainClock.autoAdvance = true
            repository.close()
            context.deleteDatabase(database)
        }
    }

    private fun capsuleCenter(frame: String): Float {
        val pixels = compose.onNodeWithTag("floating_bottom_navigation").captureToImage().toPixelMap()
        // Inspect a horizontal strip above icons and labels. Determine each
        // frame's background from its median pixels: three slots are background,
        // so a moving one-slot capsule cannot dominate this baseline. This also
        // tolerates a legitimate theme update during tab restoration.
        val y = (pixels.height * .14f).toInt()
        val colors = (0 until pixels.width).map { pixels[it, y] }
        val red = colors.map { it.red }.sorted()[colors.size / 2]
        val green = colors.map { it.green }.sorted()[colors.size / 2]
        val blue = colors.map { it.blue }.sorted()[colors.size / 2]
        fun contrast(x: Int): Float {
            val c = colors[x]
            return abs(c.red - red) + abs(c.green - green) + abs(c.blue - blue)
        }
        // Exclude the outer rounded rim, then find the largest contiguous patch
        // of contrasting fill rather than matching one stale exact RGB value.
        val left = (pixels.width * .04f).toInt()
        val right = (pixels.width * .96f).toInt()
        val peak = (left until right).maxOf { contrast(it) }
        val threshold = maxOf(.025f, peak * .40f)
        var bestStart = -1
        var bestLength = 0
        var runStart = -1
        var substantialPatches = 0
        for (x in left..right) {
            val filled = x < right && contrast(x) > threshold
            if (filled && runStart < 0) runStart = x
            if (!filled && runStart >= 0) {
                val length = x - runStart
                if (length > pixels.width * .05f) substantialPatches++
                if (length > bestLength) { bestStart = runStart; bestLength = length }
                runStart = -1
            }
        }
        compose.captureEvidence("sliding-capsule-$frame")
        assertTrue("A visible capsule must be drawn ($frame): peak=$peak, threshold=$threshold, patch=$bestStart+$bestLength",
            bestLength > pixels.width * .05f)
        assertTrue("Glass feedback must remain one connected capsule ($frame): patches=$substantialPatches",
            substantialPatches == 1)
        return (bestStart + bestLength / 2f) / pixels.width
    }
}
