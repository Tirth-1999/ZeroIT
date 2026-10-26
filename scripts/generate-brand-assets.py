#!/usr/bin/env python3
"""Generate matching SVG, Android and Compose marks from one geometry source."""
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
brand = json.loads((ROOT / 'branding/zeroit-mark.json').read_text())
def path_data(stroke):
    return ' '.join(s[0] + ','.join(str(n) for n in s[1:]) for s in stroke['segments'])
svg = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" role="img" aria-label="ZeroIT logo">', '<title>ZeroIT — three contributions, one shared zero</title>', f'<rect width="1024" height="1024" rx="{brand["cornerRadius"]}" fill="{brand["background"]}"/>']
vector = ['<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="1024" android:viewportHeight="1024">', '<!-- Generated from branding/zeroit-mark.json. -->', f'<path android:fillColor="{brand["background"]}" android:pathData="M0,0h1024v1024h-1024z"/>']
kt = '''package org.fairshare.app.ui

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
'''
for i, stroke in enumerate(brand['shapes']):
    d = path_data(stroke)
    svg.append(f'<path d="{d}" fill="{stroke["color"]}"/>')
    vector.append(f'<path android:fillColor="{stroke["color"]}" android:pathData="{d}"/>')
    kt += f'            val stroke{i} = Path().apply {{\n'
    for segment in stroke['segments']:
        fn = {'M':'moveTo','L':'lineTo','C':'cubicTo','Z':'close'}[segment[0]]
        kt += '                ' + fn + '(' + ', '.join(str(n)+'f' for n in segment[1:]) + ')\n'
    kt += f'            }}\n            drawPath(stroke{i}, Color(0xFF{stroke["color"][1:]}))\n'
kt += '        }\n    }\n}\n'
svg.append('</svg>'); vector.append('</vector>')
(ROOT/'branding/zeroit-mark.svg').write_text('\n'.join(svg)+'\n')
(ROOT/'app/src/main/res/drawable/ic_zeroit.xml').write_text('\n'.join(vector)+'\n')
(ROOT/'shared/src/commonMain/kotlin/org/fairshare/app/ui/ZeroitMark.kt').write_text(kt)
print('Generated ZeroIT SVG, Android vector and shared Compose artwork')

# Adaptive launchers own the outer mask. Keep the glyph in Android's safe area.
res = ROOT/'app/src/main/res'
(res/'mipmap-anydpi-v26').mkdir(exist_ok=True)
foreground = vector.copy()
foreground[2] = '<group android:scaleX="0.8" android:scaleY="0.8" android:pivotX="512" android:pivotY="512">'
foreground.insert(-1, '</group>')
(res/'drawable/ic_zeroit_foreground.xml').write_text('\n'.join(foreground)+'\n')
(res/'drawable/ic_zeroit_background.xml').write_text('<shape xmlns:android="http://schemas.android.com/apk/res/android"><solid android:color="#202020"/></shape>\n')
(res/'mipmap-anydpi-v26/ic_zeroit.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/ic_zeroit_background"/><foreground android:drawable="@drawable/ic_zeroit_foreground"/></adaptive-icon>\n')
