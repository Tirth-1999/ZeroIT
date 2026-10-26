package org.fairshare.app.platform

import androidx.compose.runtime.Composable

/** Observes the system setting while this composition is alive. */
@Composable
expect fun rememberReducedMotion(): Boolean
