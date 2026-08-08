package de.caritas.cob.userservice.api.facade.assignsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * ORISO-UserService#927 / ADR-018 §9: the server-side half of the consent gate.
 *
 * <p>The forward lock is real today, but it is enforced by the client alone — the composer is
 * hidden while the session sits in the pre-assignment enquiry phase. That works, and the frontend
 * invariant is now pinned by its own test. This is defence in depth for the case where the client
 * is bypassed entirely.
 *
 * <p><b>Scope is the whole difficulty here, and getting it wrong breaks the product.</b> The check
 * applies <i>only</i> to entry paths where the advice seeker did not choose the agency beforehand.
 * In Agency Counselling consent is given at registration, per the topic-before-consent invariant of
 * ADR-014, and {@code CreateUserFacade} deliberately clears the flag for anonymous registrations so
 * that the in-chat gate fires there and only there. A blanket check would therefore reject
 * perfectly consented registered enquiries.
 *
 * <p>Forward lock only: this must never delay an assignment for e-mail, 2FA or credential saving.
 * Those are voluntary throughout and have no bearing on the guard.
 */
class AnonymousEnquiryConsentGuardTest {

  private final AnonymousEnquiryConsentGuard guard = new AnonymousEnquiryConsentGuard();

  private Session sessionWithConfirmation(LocalDateTime confirmation) {
    var user = new User();
    user.setDataPrivacyConfirmation(confirmation);
    var session = new Session();
    session.setId(42L);
    session.setUser(user);
    return session;
  }

  @Test
  void rejectsAnAnonymousEnquiryWithNoRecordedConsent() {
    assertThatThrownBy(() -> guard.verifyAnonymousConsent(sessionWithConfirmation(null)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void allowsAnAnonymousEnquiryOnceConsentWasRecorded() {
    assertThatCode(() -> guard.verifyAnonymousConsent(sessionWithConfirmation(LocalDateTime.now())))
        .doesNotThrowAnyException();
  }

  @Test
  void leaksNothingAboutTheAdviceSeekerInTheRejection() {
    /* The message reaches a consultant client and, on the anonymous path, is about
    somebody who chose not to identify themselves. It names the session and the
    reason — nothing about the person. */
    var thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            ConflictException.class,
            () -> guard.verifyAnonymousConsent(sessionWithConfirmation(null)));

    assertThat(thrown.getMessage()).contains("42");
    assertThat(thrown.getMessage()).doesNotContainIgnoringCase("dataPrivacyConfirmation");
  }

  @Test
  void doesNotThrowWhenTheSessionCarriesNoUserAtAll() {
    /* A session without a user cannot be an anonymous advice seeker awaiting
    consent, and failing an assignment on a malformed session would turn a
    hardening measure into an outage. Let the existing validators speak. */
    var session = new Session();
    session.setId(43L);

    assertThatCode(() -> guard.verifyAnonymousConsent(session)).doesNotThrowAnyException();
    assertThatCode(() -> guard.verifyAnonymousConsent(null)).doesNotThrowAnyException();
  }
}
