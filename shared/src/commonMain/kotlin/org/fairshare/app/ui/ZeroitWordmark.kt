package org.fairshare.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/** Compact wordmark; IT has extra weight without changing the approved symbol. */
@Composable
internal fun ZeroitWordmark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Zero") }
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("IT") }
        },
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.SansSerif,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.7).sp,
        maxLines = 1,
    )
}
