package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConsentNotRecordedException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * ORISO-UserService#927 / ADR-018 §9: the server-side half of the consent gate.
 *
 * <p><b>Its position is the whole point, and it moved on 2026-09-07.</b> The barrier used to sit in
 * {@code AssignEnquiryFacade.assignAnonymousEnquiry}, which made an anonymous live chat impossible:
 * the entry room asks for consent only after acceptance, because the counselling centre is visible
 * only then, so the counsellor's accept always answered 409 and the advice seeker waited forever.
 * The barrier now sits on the write path — assignment is free, writing is not.
 *
 * <p><b>Scope is the second difficulty, and getting it wrong breaks the product.</b> Consent lives
 * in two places: room-scoped on the session (ADR-022 decision 2) and account-scoped on the user,
 * which is what carries consent given at registration in Agency Counselling (ADR-014). {@code
 * CreateUserFacade} deliberately clears the account-level field for anonymous registrations so the
 * in-chat gate fires there and only there — a gate on either carrier alone rejects one of the two
 * products.
 *
 * <p>Forward lock only: this must never block anything for e-mail, 2FA or credential saving. Those
 * are voluntary throughout and have no bearing on the guard.
 */
class SessionWriteConsentGuardTest {

  private final SessionWriteConsentGuard guard = new SessionWriteConsentGuard();

  private Session sessionWithConfirmation(LocalDateTime confirmation) {
    var user = new User();
    user.setDataPrivacyConfirmation(confirmation);
    var session = new Session();
    session.setId(42L);
    session.setUser(user);
    session.setConversationType(ConversationType.LIVE_CHAT);
    return session;
  }

  @Test
  void refusesAWriteIntoARoomWithNoRecordedConsent() {
    assertThatThrownBy(() -> guard.verifyMayWrite(sessionWithConfirmation(null)))
        .isInstanceOf(ConsentNotRecordedException.class)
        /* Still a ConflictException, so every existing catch and status mapping
        keeps working — only the reason is new. */
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void namesAReasonThatIsNotTheAlreadyTakenConflict() {
    /* The real complaint from the field: the accept and write paths answer 409 for
    "somebody else has already taken this enquiry" too, and the frontend showed
    that sentence for a missing consent. Only this conflict carries a reason. */
    var thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            ConsentNotRecordedException.class,
            () -> guard.verifyMayWrite(sessionWithConfirmation(null)));

    assertThat(thrown.getCustomHttpHeaders().getFirst("X-Reason"))
        .isEqualTo(HttpStatusExceptionReason.DATA_PRIVACY_CONSENT_MISSING.name());
  }

  @Test
  void allowsAWriteOnceTheAccountLevelConsentWasRecorded() {
    assertThatCode(() -> guard.verifyMayWrite(sessionWithConfirmation(LocalDateTime.now())))
        .doesNotThrowAnyException();
  }

  @Test
  void allowsAWriteWhenTheSessionCarriesAConsentPointer() {
    /* ADR-022 decision 2 moves the Gate 2 state onto the session. A room cleared for
    a legal-text version has passed the gate, even though CreateUserFacade
    deliberately leaves the account-level timestamp null. */
    var session = sessionWithConfirmation(null);
    session.setConsentedLegalVersionId(7L);

    assertThatCode(() -> guard.verifyMayWrite(session)).doesNotThrowAnyException();
  }

  @Test
  void guardsARoomWhoseConversationTypeWasNeverStamped() {
    /* `conversation_type` is nullable, and Session#isConsentGateApplicable treats a null
    as a gated room on purpose — rows written before the column existed are counselling
    rooms, not group chats. Pinned separately from the LIVE_CHAT case because the
    exemption below is the neighbouring branch: widen it to "not a known counselling
    type" and every un-stamped room silently loses its gate, which is the one failure
    mode of this class that nothing else would catch. */
    var session = sessionWithConfirmation(null);
    session.setConversationType(null);

    assertThatThrownBy(() -> guard.verifyMayWrite(session))
        .isInstanceOf(ConsentNotRecordedException.class);
  }

  @Test
  void leavesRoomsWithoutAGateAlone() {
    /* A group chat's session is owned by a tenant system user, not by a person who
    could agree — one pointer there cannot express per-participant consent, so
    Session#isConsentGateApplicable excludes it. Guarding it anyway would mute
    every self-help group. */
    var session = sessionWithConfirmation(null);
    session.setConversationType(ConversationType.SELF_HELP);

    assertThatCode(() -> guard.verifyMayWrite(session)).doesNotThrowAnyException();
  }

  @Test
  void leaksNothingAboutTheAdviceSeekerInTheRejection() {
    /* The message concerns somebody who may have chosen not to identify themselves.
    It names the session and the reason — nothing about the person. */
    var thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            ConsentNotRecordedException.class,
            () -> guard.verifyMayWrite(sessionWithConfirmation(null)));

    assertThat(thrown.getMessage()).contains("42");
    assertThat(thrown.getMessage()).doesNotContainIgnoringCase("dataPrivacyConfirmation");
  }

  @Test
  void doesNotThrowWhenTheSessionCarriesNoUserAtAll() {
    /* A session without a user cannot be an advice seeker awaiting consent, and
    failing a write on a malformed session would turn a hardening measure into an
    outage. Let the existing access checks speak. */
    var session = new Session();
    session.setId(43L);
    session.setConversationType(ConversationType.LIVE_CHAT);

    assertThatCode(() -> guard.verifyMayWrite(session)).doesNotThrowAnyException();
    assertThatCode(() -> guard.verifyMayWrite(null)).doesNotThrowAnyException();
  }
}
