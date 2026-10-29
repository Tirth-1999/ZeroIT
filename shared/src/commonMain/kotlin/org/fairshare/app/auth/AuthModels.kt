package org.fairshare.app.auth

/** Identity only. Neither presentation fields nor credentials belong in this model. */
data class AccountKey(val project: String, val accountId: String)

enum class IdentityProvider { GOOGLE, APPLE, UNSUPPORTED }

/**
 * Injected result from a TRUSTED verifier/exchange boundary, not arbitrary decoded JWT data.
 * This pure module performs no signature, issuer, audience, algorithm or nonce verification.
 * Constructing this value does not establish trust; production adapters must prove those checks.
 */
data class TrustedVerificationInput(
    val account: AccountKey,
    val sessionId: String,
    val provider: IdentityProvider,
    val verificationAccepted: Boolean,
    val nonAnonymous: Boolean,
    val providerConfirmed: Boolean,
    val notBeforeEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
)

data class AuthAttempt(
    val id: String,
    val generation: Long,
    val provider: IdentityProvider,
    val project: String,
)

enum class AuthenticationReason { INVALID_CREDENTIALS, EXCHANGE_FAILED, SESSION_REVOKED, ACCOUNT_DISABLED, UNCONFIRMED }

sealed interface AuthState {
    data object Guest : AuthState
    data class SigningIn(val attempt: AuthAttempt) : AuthState
    /** Local presence only: never permission to dispatch a shared request. */
    data class SignedIn(val account: AccountKey, val sessionId: String) : AuthState
    data class NeedsAuthentication(val reason: AuthenticationReason) : AuthState
    /** Retain only identity binding so a later definitive denial can still lock this session. */
    data class Unavailable(val account: AccountKey?, val sessionId: String? = null) : AuthState
    data class Blocked(val account: AccountKey, val reason: AuthenticationReason) : AuthState
    /** External cleanup acknowledgement is required; this value does not perform cleanup. */
    data class Closing(val account: AccountKey?) : AuthState
    /** No generation is reused after exhaustion. Guest ledger remains outside this module. */
    data object Exhausted : AuthState
}

data class AuthSnapshot(val generation: Long = 0, val state: AuthState = AuthState.Guest)

sealed interface ExchangeOutcome {
    data class Success(val principal: TrustedVerificationInput) : ExchangeOutcome
    data object Cancelled : ExchangeOutcome
    data object Failed : ExchangeOutcome
    data object Unavailable : ExchangeOutcome
    data object Unknown : ExchangeOutcome
}

enum class SessionStatus { ACTIVE, MISSING, REVOKED, EXPIRED, SUBJECT_MISMATCH }
enum class ApplicationAccountStatus { ENABLED, DISABLED, ABSENT }

sealed interface AuthoritativeCheck {
    data object Unavailable : AuthoritativeCheck
    data object Unknown : AuthoritativeCheck
    data class Available(
        val account: AccountKey,
        val sessionId: String,
        val sessionStatus: SessionStatus,
        val identityConfirmed: Boolean,
        val applicationAccount: ApplicationAccountStatus,
    ) : AuthoritativeCheck
}

enum class GateDecision {
    UNAUTHENTICATED, AUTH_CHECK_UNAVAILABLE, SESSION_REVOKED,
    ACCOUNT_DISABLED, BOOTSTRAP_ELIGIBLE, ACTIVE,
}

sealed interface AuthEvent {
    data class Begin(val provider: IdentityProvider, val project: String, val attemptId: String) : AuthEvent
    data class Complete(val attempt: AuthAttempt, val outcome: ExchangeOutcome, val nowEpochSeconds: Long) : AuthEvent
    data class Cancel(val attempt: AuthAttempt) : AuthEvent
    data object SignOut : AuthEvent
    data class ClosingAcknowledged(val generation: Long) : AuthEvent
    /** Only apply an authoritative result to the exact session/generation that requested it. */
    data class GateResult(val generation: Long, val account: AccountKey, val sessionId: String, val decision: GateDecision) : AuthEvent
}
