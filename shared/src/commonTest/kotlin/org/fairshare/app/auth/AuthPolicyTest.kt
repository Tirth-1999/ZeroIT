package org.fairshare.app.auth

import kotlin.test.*

/** Synthetic policy assertions only; no JWT, nonce, vault, provider, persistence or device evidence. */
class AuthPolicyTest {
    private val key = AccountKey("staging-project", "11111111-1111-1111-1111-111111111111")
    private val session = "22222222-2222-2222-2222-222222222222"
    private val now = 100L
    private fun principal() = TrustedVerificationInput(key, session, IdentityProvider.GOOGLE, true, true, true, 10, 200)
    private fun check() = AuthoritativeCheck.Available(key, session, SessionStatus.ACTIVE, true, ApplicationAccountStatus.ENABLED)
    private fun decide(p: TrustedVerificationInput = principal(), c: AuthoritativeCheck = check()) =
        SessionGatePolicy.decide(p, key.project, now, c)
    private fun begin(current: AuthSnapshot = AuthSnapshot(), id: String = "attempt-a", project: String = key.project,
                      provider: IdentityProvider = IdentityProvider.GOOGLE) =
        AuthReducer.reduce(current, AuthEvent.Begin(provider, project, id))
    private fun attempt(snapshot: AuthSnapshot) = (snapshot.state as AuthState.SigningIn).attempt
    private fun complete(snapshot: AuthSnapshot, p: TrustedVerificationInput = principal()) =
        AuthReducer.reduce(snapshot, AuthEvent.Complete(attempt(snapshot), ExchangeOutcome.Success(p), now))

    @Test fun successfulAttemptIsConsumedAndDoesNotCachePermission() {
        val start = begin()
        val done = complete(start)
        assertEquals(AuthState.SignedIn(key, session), done.state)
        assertEquals(done, AuthReducer.reduce(done, AuthEvent.Complete(attempt(start), ExchangeOutcome.Success(principal()), now)))
        assertEquals(GateDecision.AUTH_CHECK_UNAVAILABLE, decide(c = AuthoritativeCheck.Unavailable))
    }

    @Test fun supersededAttemptAndEveryBindingMismatchCannotActivate() {
        val first = begin()
        val second = begin(first, "attempt-b")
        assertTrue(second.generation > first.generation)
        val wrong = listOf(attempt(first), attempt(second).copy(id = "other"),
            attempt(second).copy(provider = IdentityProvider.APPLE), attempt(second).copy(project = "other"),
            attempt(second).copy(generation = first.generation))
        wrong.forEach { value ->
            assertEquals(second, AuthReducer.reduce(second, AuthEvent.Complete(value, ExchangeOutcome.Success(principal()), now)))
        }
        assertIs<AuthState.SignedIn>(complete(second).state)
    }

    @Test fun returnedProviderAndProjectMustMatchAttempt() {
        assertIs<AuthState.NeedsAuthentication>(complete(begin(), principal().copy(provider = IdentityProvider.APPLE)).state)
        assertIs<AuthState.NeedsAuthentication>(complete(begin(), principal().copy(account = key.copy(project = "other"))).state)
    }

    @Test fun cancelConsumesAttemptAndCanRetryWithoutResurrection() {
        val start = begin()
        val cancelled = AuthReducer.reduce(start, AuthEvent.Cancel(attempt(start)))
        assertEquals(AuthState.Guest, cancelled.state)
        assertEquals(cancelled, AuthReducer.reduce(cancelled, AuthEvent.Complete(attempt(start), ExchangeOutcome.Success(principal()), now)))
        assertTrue(begin(cancelled).generation > start.generation)
    }

    @Test fun staleCancelDoesNotCancelNewAttempt() {
        val old = begin()
        val current = begin(old, "next")
        assertEquals(current, AuthReducer.reduce(current, AuthEvent.Cancel(attempt(old))))
    }

    @Test fun errorsAndUnknownOutcomesNeverBecomeSuccess() {
        val expected = listOf(
            ExchangeOutcome.Cancelled to AuthState.Guest,
            ExchangeOutcome.Failed to AuthState.NeedsAuthentication(AuthenticationReason.EXCHANGE_FAILED),
            ExchangeOutcome.Unavailable to AuthState.Unavailable(null),
            ExchangeOutcome.Unknown to AuthState.Unavailable(null),
        )
        expected.forEach { (outcome, state) ->
            val started = begin()
            assertEquals(state, AuthReducer.reduce(started, AuthEvent.Complete(attempt(started), outcome, now)).state)
        }
    }

    @Test fun invalidVerificationDominatesUnavailableAndDisabled() {
        val invalid = listOf(
            principal().copy(verificationAccepted = false), principal().copy(nonAnonymous = false),
            principal().copy(providerConfirmed = false), principal().copy(provider = IdentityProvider.UNSUPPORTED),
            principal().copy(account = key.copy(project = "other")), principal().copy(account = key.copy(accountId = "invalid")),
            principal().copy(sessionId = "invalid"), principal().copy(expiresAtEpochSeconds = now),
            principal().copy(notBeforeEpochSeconds = now + 1), principal().copy(notBeforeEpochSeconds = 200, expiresAtEpochSeconds = 100),
        )
        invalid.forEach { p ->
            assertEquals(GateDecision.UNAUTHENTICATED, decide(p, AuthoritativeCheck.Unavailable))
            assertEquals(GateDecision.UNAUTHENTICATED, decide(p, check().copy(applicationAccount = ApplicationAccountStatus.DISABLED)))
        }
    }

    @Test fun timeBoundariesAreInclusiveNotBeforeExclusiveExpiry() {
        assertEquals(GateDecision.ACTIVE, decide(principal().copy(notBeforeEpochSeconds = now)))
        assertEquals(GateDecision.ACTIVE, decide(principal().copy(expiresAtEpochSeconds = now + 1)))
        assertEquals(GateDecision.UNAUTHENTICATED, decide(principal().copy(expiresAtEpochSeconds = now)))
    }

    @Test fun availableCheckMustBindExactSessionAndSubjectBeforeAccountStatus() {
        SessionStatus.entries.filter { it != SessionStatus.ACTIVE }.forEach { status ->
            ApplicationAccountStatus.entries.forEach { account ->
                assertEquals(GateDecision.SESSION_REVOKED, decide(c = check().copy(sessionStatus = status, applicationAccount = account)))
            }
        }
        assertEquals(GateDecision.SESSION_REVOKED, decide(c = check().copy(account = key.copy(accountId = "33333333-3333-3333-3333-333333333333"))))
        assertEquals(GateDecision.SESSION_REVOKED, decide(c = check().copy(sessionId = "33333333-3333-3333-3333-333333333333")))
        assertEquals(GateDecision.SESSION_REVOKED, decide(c = check().copy(account = key.copy(project = "another"))))
    }

    @Test fun confirmationPrecedesAccountAndUnknownCheckIsRetryable() {
        assertEquals(GateDecision.UNAUTHENTICATED, decide(c = check().copy(identityConfirmed = false, applicationAccount = ApplicationAccountStatus.DISABLED)))
        assertEquals(GateDecision.AUTH_CHECK_UNAVAILABLE, decide(c = AuthoritativeCheck.Unknown))
        assertEquals(GateDecision.AUTH_CHECK_UNAVAILABLE, decide(c = AuthoritativeCheck.Unavailable))
        assertEquals(GateDecision.ACCOUNT_DISABLED, decide(c = check().copy(applicationAccount = ApplicationAccountStatus.DISABLED)))
        assertEquals(GateDecision.BOOTSTRAP_ELIGIBLE, decide(c = check().copy(applicationAccount = ApplicationAccountStatus.ABSENT)))
        assertEquals(GateDecision.ACTIVE, decide())
    }

    @Test fun signOutRequiresExactClosingAcknowledgementAndRejectsLateResult() {
        val started = begin()
        val closing = AuthReducer.reduce(started, AuthEvent.SignOut)
        assertIs<AuthState.Closing>(closing.state)
        assertEquals(closing, AuthReducer.reduce(closing, AuthEvent.Complete(attempt(started), ExchangeOutcome.Success(principal()), now)))
        assertEquals(closing, AuthReducer.reduce(closing, AuthEvent.ClosingAcknowledged(started.generation)))
        assertEquals(closing, begin(closing, "new"))
        val guest = AuthReducer.reduce(closing, AuthEvent.ClosingAcknowledged(closing.generation))
        assertEquals(AuthState.Guest, guest.state)
        assertIs<AuthState.SigningIn>(begin(guest).state)
    }

    @Test fun accountSwitchNeverDirectlyActivatesAnotherAccount() {
        val active = complete(begin())
        val closing = begin(active, "account-b")
        assertEquals(AuthState.Closing(key), closing.state)
        val guest = AuthReducer.reduce(closing, AuthEvent.ClosingAcknowledged(closing.generation))
        val next = begin(guest, "account-b")
        val other = principal().copy(account = key.copy(accountId = "33333333-3333-3333-3333-333333333333"))
        assertEquals(AuthState.SignedIn(other.account, session), complete(next, other).state)
        assertNotEquals(key, other.account)
    }

    @Test fun gateCallbacksCannotAffectAnotherGenerationAccountOrSession() {
        val active = complete(begin())
        val event = AuthEvent.GateResult(active.generation, key, session, GateDecision.ACCOUNT_DISABLED)
        listOf(event.copy(generation = active.generation - 1), event.copy(account = key.copy(project = "other")), event.copy(sessionId = "other"))
            .forEach { assertEquals(active, AuthReducer.reduce(active, it)) }
        val blocked = AuthReducer.reduce(active, event)
        assertEquals(AuthState.Blocked(key, AuthenticationReason.ACCOUNT_DISABLED), blocked.state)
        assertEquals(blocked, AuthReducer.reduce(blocked, event.copy(decision = GateDecision.ACTIVE)))
    }

    @Test fun unavailableDoesNotBecomeRevocationOrReactivateFromLateAllow() {
        val active = complete(begin())
        val unavailable = AuthReducer.reduce(active, AuthEvent.GateResult(active.generation, key, session, GateDecision.AUTH_CHECK_UNAVAILABLE))
        assertEquals(AuthState.Unavailable(key, session), unavailable.state)
        assertEquals(unavailable, AuthReducer.reduce(unavailable, AuthEvent.GateResult(active.generation, key, session, GateDecision.ACTIVE)))
        assertIs<AuthState.Closing>(begin(unavailable).state)
    }

    @Test fun outageRetainsBindingAndLaterDefinitiveDenialLocksSession() {
        val active = complete(begin())
        val base = AuthEvent.GateResult(active.generation, key, session, GateDecision.AUTH_CHECK_UNAVAILABLE)
        val unavailable = AuthReducer.reduce(active, base)
        val outcomes = mapOf(
            GateDecision.SESSION_REVOKED to AuthenticationReason.SESSION_REVOKED,
            GateDecision.ACCOUNT_DISABLED to AuthenticationReason.ACCOUNT_DISABLED,
            GateDecision.UNAUTHENTICATED to AuthenticationReason.UNCONFIRMED,
        )
        outcomes.forEach { (decision, reason) ->
            assertEquals(AuthState.Blocked(key, reason), AuthReducer.reduce(unavailable, base.copy(decision = decision)).state)
        }
    }

    @Test fun outageRejectsStaleOrMismatchedDenialAndAllLateAllows() {
        val active = complete(begin())
        val base = AuthEvent.GateResult(active.generation, key, session, GateDecision.AUTH_CHECK_UNAVAILABLE)
        val unavailable = AuthReducer.reduce(active, base)
        listOf(GateDecision.SESSION_REVOKED, GateDecision.ACCOUNT_DISABLED, GateDecision.UNAUTHENTICATED).forEach { denial ->
            val event = base.copy(decision = denial)
            listOf(event.copy(generation = active.generation - 1), event.copy(account = key.copy(project = "other")),
                event.copy(account = key.copy(accountId = "33333333-3333-3333-3333-333333333333")), event.copy(sessionId = "other"))
                .forEach { assertEquals(unavailable, AuthReducer.reduce(unavailable, it)) }
        }
        listOf(GateDecision.ACTIVE, GateDecision.BOOTSTRAP_ELIGIBLE, GateDecision.AUTH_CHECK_UNAVAILABLE).forEach {
            assertEquals(unavailable, AuthReducer.reduce(unavailable, base.copy(decision = it)))
        }
    }

    @Test fun unboundOutageCannotAcquireAuthorityOrConsumeUnrelatedDenial() {
        val unbound = AuthSnapshot(4, AuthState.Unavailable(null))
        GateDecision.entries.forEach { decision ->
            assertEquals(unbound, AuthReducer.reduce(unbound, AuthEvent.GateResult(4, key, session, decision)))
        }
    }

    @Test fun generationExhaustionAndInvalidGenerationFailClosed() {
        listOf(AuthSnapshot(Long.MAX_VALUE), AuthSnapshot(-1)).forEach { value ->
            val blocked = begin(value)
            assertEquals(AuthSnapshot(Long.MAX_VALUE, AuthState.Exhausted), blocked)
            assertEquals(blocked, AuthReducer.reduce(blocked, AuthEvent.ClosingAcknowledged(Long.MAX_VALUE)))
        }
        val last = begin(AuthSnapshot(Long.MAX_VALUE - 1))
        assertEquals(Long.MAX_VALUE, last.generation)
        assertEquals(AuthState.Exhausted, AuthReducer.reduce(last, AuthEvent.SignOut).state)
    }

    @Test fun unsupportedBeginFailsClosedAndNoLedgerIsPartOfTheCore() {
        listOf(begin(provider = IdentityProvider.UNSUPPORTED), begin(project = ""), begin(id = ""))
            .forEach { assertIs<AuthState.NeedsAuthentication>(it.state) }
        val externalLedger = listOf("original-id", "USD:123", "immutable-audit")
        val snapshot = externalLedger.toList()
        val active = complete(begin())
        AuthReducer.reduce(active, AuthEvent.SignOut)
        assertEquals(snapshot, externalLedger) // Sentinel only, not installed storage preservation evidence.
        assertFalse(active.toString().contains("token", ignoreCase = true))
    }
}
