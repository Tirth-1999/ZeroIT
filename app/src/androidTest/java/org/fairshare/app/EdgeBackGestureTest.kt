package org.fairshare.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.fairshare.app.ui.EdgeBackGestureHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EdgeBackGestureTest {
    @get:Rule val compose = createComposeRule()
    @Test fun bothEdgesCommitButShortInteriorAndVerticalDragsDoNot() {
        var backs = 0
        compose.setContent { MaterialTheme { EdgeBackGestureHost(true, { backs++ }) { Box(Modifier.fillMaxSize().testTag("gesture_body")) } } }
        val body = compose.onNodeWithTag("gesture_body")
        body.performTouchInput { swipe(Offset(2f, height * .3f), Offset(width * .65f, height * .3f), 500) }
        compose.runOnIdle { assertEquals(1, backs) }
        body.performTouchInput { swipe(Offset(width - 2f, height * .7f), Offset(width * .35f, height * .7f), 500) }
        compose.runOnIdle { assertEquals(2, backs) }
        body.performTouchInput { swipe(Offset(width * .4f, height * .5f), Offset(width * .8f, height * .5f), 500) }
        compose.runOnIdle { assertEquals(2, backs) }
        body.performTouchInput { swipe(Offset(2f, height * .3f), Offset(2f, height * .7f), 500) }
        compose.runOnIdle { assertEquals(2, backs) }
        body.performTouchInput { swipe(Offset(2f, height * .4f), Offset(35f, height * .4f), 500) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, backs) }
    }
}
