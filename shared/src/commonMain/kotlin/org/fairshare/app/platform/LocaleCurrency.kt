package org.fairshare.app.platform

/** Device region is a suggestion only; this never selects or converts transaction currency. */
expect fun preferredLocaleCurrency(): String?
