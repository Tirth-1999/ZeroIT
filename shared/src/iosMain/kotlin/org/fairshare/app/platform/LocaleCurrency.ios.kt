package org.fairshare.app.platform

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCurrencyCode
import platform.Foundation.currentLocale

actual fun preferredLocaleCurrency(): String? =
    (NSLocale.currentLocale.objectForKey(NSLocaleCurrencyCode) as? String)
        ?.takeIf { it in supportedCurrencyCodes() }
