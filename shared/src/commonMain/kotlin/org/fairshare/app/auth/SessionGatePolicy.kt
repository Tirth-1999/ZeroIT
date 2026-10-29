package org.fairshare.app.auth

/** Pure classification, NOT a live checker and NOT JWT verification. No positive cache is supplied. */
object SessionGatePolicy {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun acceptsVerification(input: TrustedVerificationInput, expectedProject: String, nowEpochSeconds: Long): Boolean =
        expectedProject.isNotBlank() && input.account.project == expectedProject &&
            uuid.matches(input.account.accountId) && uuid.matches(input.sessionId) &&
            input.provider != IdentityProvider.UNSUPPORTED && input.verificationAccepted &&
            input.nonAnonymous && input.providerConfirmed &&
            input.notBeforeEpochSeconds <= nowEpochSeconds && nowEpochSeconds < input.expiresAtEpochSeconds &&
            input.notBeforeEpochSeconds < input.expiresAtEpochSeconds

    fun decide(
        principal: TrustedVerificationInput,
        expectedProject: String,
        nowEpochSeconds: Long,
        check: AuthoritativeCheck,
    ): GateDecision {
        if (!acceptsVerification(principal, expectedProject, nowEpochSeconds)) return GateDecision.UNAUTHENTICATED
        if (check !is AuthoritativeCheck.Available) return GateDecision.AUTH_CHECK_UNAVAILABLE
        if (check.account != principal.account || check.sessionId != principal.sessionId ||
            check.sessionStatus != SessionStatus.ACTIVE) return GateDecision.SESSION_REVOKED
        if (!check.identityConfirmed) return GateDecision.UNAUTHENTICATED
        return when (check.applicationAccount) {
            ApplicationAccountStatus.DISABLED -> GateDecision.ACCOUNT_DISABLED
            ApplicationAccountStatus.ABSENT -> GateDecision.BOOTSTRAP_ELIGIBLE
            ApplicationAccountStatus.ENABLED -> GateDecision.ACTIVE
        }
    }
}
