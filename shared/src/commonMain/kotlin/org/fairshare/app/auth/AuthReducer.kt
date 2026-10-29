package org.fairshare.app.auth

/**
 * Disconnected synchronous policy. No SDK, credentials, persistence, dispatch or ledger effects.
 * ClosingAcknowledged simulates externally completed cleanup; it does not implement a barrier.
 */
object AuthReducer {
    fun reduce(current: AuthSnapshot, event: AuthEvent): AuthSnapshot {
        if (current.generation < 0 || current.state == AuthState.Exhausted) return AuthSnapshot(Long.MAX_VALUE, AuthState.Exhausted)
        return when (event) {
            is AuthEvent.Begin -> begin(current, event)
            is AuthEvent.Complete -> complete(current, event)
            is AuthEvent.Cancel -> if (matches(current, event.attempt)) current.copy(state = AuthState.Guest) else current
            AuthEvent.SignOut -> advance(current) { AuthState.Closing(accountOf(current.state)) }
            is AuthEvent.ClosingAcknowledged -> if (current.state is AuthState.Closing && event.generation == current.generation)
                current.copy(state = AuthState.Guest) else current
            is AuthEvent.GateResult -> applyGate(current, event)
        }
    }

    private fun begin(current: AuthSnapshot, event: AuthEvent.Begin): AuthSnapshot {
        if (current.state is AuthState.Closing) return current
        if (accountOf(current.state) != null) return advance(current) { AuthState.Closing(accountOf(current.state)) }
        if (event.provider == IdentityProvider.UNSUPPORTED || event.project.isBlank() || event.attemptId.isBlank())
            return advance(current) { AuthState.NeedsAuthentication(AuthenticationReason.INVALID_CREDENTIALS) }
        return advance(current) { generation -> AuthState.SigningIn(AuthAttempt(event.attemptId, generation, event.provider, event.project)) }
    }

    private fun complete(current: AuthSnapshot, event: AuthEvent.Complete): AuthSnapshot {
        if (!matches(current, event.attempt)) return current
        val state = when (val outcome = event.outcome) {
            is ExchangeOutcome.Success -> {
                val principal = outcome.principal
                if (principal.provider == event.attempt.provider &&
                    SessionGatePolicy.acceptsVerification(principal, event.attempt.project, event.nowEpochSeconds))
                    AuthState.SignedIn(principal.account, principal.sessionId)
                else AuthState.NeedsAuthentication(AuthenticationReason.INVALID_CREDENTIALS)
            }
            ExchangeOutcome.Cancelled -> AuthState.Guest
            ExchangeOutcome.Failed -> AuthState.NeedsAuthentication(AuthenticationReason.EXCHANGE_FAILED)
            ExchangeOutcome.Unavailable, ExchangeOutcome.Unknown -> AuthState.Unavailable(null)
        }
        return current.copy(state = state)
    }

    private fun matches(current: AuthSnapshot, attempt: AuthAttempt): Boolean =
        current.state is AuthState.SigningIn && current.state.attempt == attempt && current.generation == attempt.generation

    private fun applyGate(current: AuthSnapshot, event: AuthEvent.GateResult): AuthSnapshot {
        val state = current.state
        val account: AccountKey
        val sessionId: String
        when (state) {
            is AuthState.SignedIn -> { account = state.account; sessionId = state.sessionId }
            is AuthState.Unavailable -> {
                account = state.account ?: return current
                sessionId = state.sessionId ?: return current
            }
            else -> return current
        }
        if (current.generation != event.generation || account != event.account || sessionId != event.sessionId) return current
        // An outage does not discard the binding needed to honor a definitive denial.
        // A late allow must never reactivate unavailable state; fresh restoration is separate.
        if (state is AuthState.Unavailable && event.decision in setOf(
                GateDecision.ACTIVE, GateDecision.BOOTSTRAP_ELIGIBLE, GateDecision.AUTH_CHECK_UNAVAILABLE)) return current
        return current.copy(state = when (event.decision) {
            GateDecision.ACTIVE -> state // Retain local presence only, never cache permission.
            GateDecision.BOOTSTRAP_ELIGIBLE -> state // No bootstrap is executed by this policy.
            GateDecision.AUTH_CHECK_UNAVAILABLE -> AuthState.Unavailable(account, sessionId)
            GateDecision.UNAUTHENTICATED -> AuthState.Blocked(account, AuthenticationReason.UNCONFIRMED)
            GateDecision.SESSION_REVOKED -> AuthState.Blocked(account, AuthenticationReason.SESSION_REVOKED)
            GateDecision.ACCOUNT_DISABLED -> AuthState.Blocked(account, AuthenticationReason.ACCOUNT_DISABLED)
        })
    }

    private fun accountOf(state: AuthState): AccountKey? = when (state) {
        is AuthState.SignedIn -> state.account
        is AuthState.Blocked -> state.account
        is AuthState.Unavailable -> state.account
        is AuthState.Closing -> state.account
        else -> null
    }

    private fun advance(current: AuthSnapshot, state: (Long) -> AuthState): AuthSnapshot =
        if (current.generation == Long.MAX_VALUE) AuthSnapshot(Long.MAX_VALUE, AuthState.Exhausted)
        else (current.generation + 1).let { AuthSnapshot(it, state(it)) }
}
