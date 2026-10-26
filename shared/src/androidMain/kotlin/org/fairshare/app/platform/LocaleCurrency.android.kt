package org.fairshare.app.platform

import java.util.Currency
import java.util.Locale

actual fun preferredLocaleCurrency(): String? = runCatching {
    Currency.getInstance(Locale.getDefault()).currencyCode
}.getOrNull()?.takeIf { it in supportedCurrencyCodes() }
