package org.fairshare.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformAppearance(dark: Boolean) {
    SideEffect {
        UIApplication.sharedApplication.keyWindow?.overrideUserInterfaceStyle =
            if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
    }
}
