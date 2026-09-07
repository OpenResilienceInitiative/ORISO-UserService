package de.caritas.cob.userservice.api.facade.assignsession;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement of the data-protection consent on <b>anonymous</b> entry paths (ADR-018
 * §9, ORISO-UserService#927).
 *
 * <h2>Why this exists at all, given the gate already works</h2>
 *
 * The forward lock is real today, but it is enforced by the client: the composer stays hidden while
 * the session sits in the pre-assignment enquiry phase, so nothing has been sent and nobody has
 * picked the case up. That guarantee holds — it is not a false promise — but it rests on a UI
 * invariant. This is the defence in depth for a bypassed client, on the consent step for
 * special-category data under §11 KDG.
 *
 * <h2>Why it is scoped so narrowly</h2>
 *
 * Only anonymous entry paths. In Agency Counselling consent is given during registration, per the
 * topic-before-consent invariant of ADR-014, and {@code CreateUserFacade} deliberately clears
 * {@code dataPrivacyConfirmation} for anonymous registrations precisely so the in-chat gate fires
 * there and only there. A blanket check on every assignment would therefore reject perfectly
 * consented registered enquiries — the guard would break the product it is meant to protect.
 *
 * <h2>What it must never do</h2>
 *
 * Delay an assignment for anything voluntary. E-mail, 2FA and credential saving are optional
 * throughout (ADR-018 §9: forward lock yes, backward lock no) and have no bearing here.
 */
@Component
@Slf4j
public class AnonymousEnquiryConsentGuard {

  /**
   * @param session the anonymous session about to be assigned
   * @throws ConflictException if the advice seeker has not recorded their consent
   */
  public void verifyAnonymousConsent(Session session) {
    if (session == null || session.getUser() == null) {
      /* Not an anonymous advice seeker awaiting consent. Failing an assignment on
      a malformed session would turn a hardening measure into an outage; the
      existing validators own that case. */
      return;
    }
    if (session.getConsentedLegalVersionId() != null) {
      /* ADR-022 decision 2 moved the Gate 2 state onto the session. A room that is
      cleared for a legal-text version has passed the gate — and it is the only
      signal that exists there, because CreateUserFacade deliberately leaves the
      account-level timestamp null for anonymous accounts. Checked first so the
      session-scoped state wins; the account-level timestamp below stays as the
      fallback for rooms created before this column existed. */
      return;
    }
    if (session.getUser().getDataPrivacyConfirmation() != null) {
      return;
    }
    log.warn(
        "Rejecting assignment of anonymous session {} — no data privacy confirmation recorded",
        session.getId());
    /* The message reaches a consultant client and concerns somebody who chose not
    to identify themselves, so it names the session and the reason and nothing
    about the person. */
    throw new ConflictException(
        String.format(
            "Anonymous session %s cannot be assigned before the advice seeker confirmed the"
                + " data protection notice",
            session.getId()));
  }
}
