package org.fairshare.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Generated from branding/zeroit-mark.json. */
@Composable
internal fun ZeroitMark(modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "ZeroIT logo" }) {
        drawRoundRect(Color(0xFF202020), cornerRadius = CornerRadius(size.width * 0.22f))
        scale(size.width / 1024f, size.height / 1024f, pivot = Offset.Zero) {
            val stroke0 = Path().apply {
                moveTo(492f, 136f)
                cubicTo(302f, 136f, 184f, 254f, 184f, 432f)
                lineTo(184f, 650f)
                cubicTo(184f, 658f, 190f, 663f, 198f, 660f)
                lineTo(364f, 592f)
                cubicTo(370f, 590f, 374f, 585f, 374f, 578f)
                lineTo(374f, 430f)
                cubicTo(374f, 358f, 412f, 322f, 482f, 322f)
                lineTo(492f, 322f)
                cubicTo(498f, 322f, 502f, 318f, 502f, 312f)
                lineTo(502f, 146f)
                cubicTo(502f, 140f, 498f, 136f, 492f, 136f)
                close()
            }
            drawPath(stroke0, Color(0xFF17675A))
            val stroke1 = Path().apply {
                moveTo(532f, 136f)
                cubicTo(722f, 136f, 840f, 254f, 840f, 432f)
                lineTo(840f, 650f)
                cubicTo(840f, 658f, 834f, 663f, 826f, 660f)
                lineTo(660f, 592f)
                cubicTo(654f, 590f, 650f, 585f, 650f, 578f)
                lineTo(650f, 430f)
                cubicTo(650f, 358f, 612f, 322f, 542f, 322f)
                lineTo(532f, 322f)
                cubicTo(526f, 322f, 522f, 318f, 522f, 312f)
                lineTo(522f, 146f)
                cubicTo(522f, 140f, 526f, 136f, 532f, 136f)
                close()
            }
            drawPath(stroke1, Color(0xFF92D6BD))
            val stroke2 = Path().apply {
                moveTo(200f, 686f)
                lineTo(364f, 619f)
                cubicTo(371f, 616f, 377f, 620f, 379f, 629f)
                cubicTo(389f, 689f, 425f, 718f, 480f, 718f)
                lineTo(544f, 718f)
                cubicTo(599f, 718f, 635f, 689f, 645f, 629f)
                cubicTo(647f, 620f, 653f, 616f, 660f, 619f)
                lineTo(824f, 686f)
                cubicTo(831f, 689f, 835f, 694f, 833f, 703f)
                cubicTo(806f, 820f, 713f, 888f, 580f, 888f)
                lineTo(444f, 888f)
                cubicTo(311f, 888f, 218f, 820f, 191f, 703f)
                cubicTo(189f, 694f, 193f, 689f, 200f, 686f)
                close()
            }
            drawPath(stroke2, Color(0xFFFFFFFF))
        }
    }
}
