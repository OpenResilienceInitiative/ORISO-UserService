package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.exception.httpresponses.ConsentNotRecordedException;
import de.caritas.cob.userservice.api.model.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement of the data-protection consent on a counselling room (ADR-018 §9,
 * ORISO-UserService#927).
 *
 * <h2>Why this sits on the write path and no longer on the assignment</h2>
 *
 * Until 2026-09-07 this guard ran inside {@code AssignEnquiryFacade.assignAnonymousEnquiry}, so an
 * anonymous live chat could never come about at all: the entry room deliberately asks for consent
 * only <b>after</b> acceptance, because the counselling centre becomes visible only then. The
 * counsellor pressed accept, got a 409, and the advice seeker waited forever. The barrier itself
 * was never the problem, only its position — so it moved to the moment it actually protects: the
 * first message written into the room. Assignment is now free; writing is not.
 *
 * <h2>Why the gate is not simply "the account has confirmed"</h2>
 *
 * Two carriers, checked in this order. {@code session.consentedLegalVersionId} (ADR-022 decision 2)
 * is the room-scoped state and wins, because {@code CreateUserFacade} deliberately clears the
 * account-level {@code dataPrivacyConfirmation} for anonymous registrations precisely so the
 * in-chat gate fires there. The account-level timestamp remains the fallback for rooms created
 * before that column existed, and it is what carries consent given at registration in Agency
 * Counselling (the topic-before-consent invariant of ADR-014). A gate on only one of the two would
 * reject one of the two products.
 *
 * <h2>What it must never do</h2>
 *
 * Block anything for a voluntary step. E-mail, 2FA and credential saving are optional throughout
 * (ADR-018 §9: forward lock yes, backward lock no) and have no bearing here. And it must never
 * block the assignment again — that is the regression this class exists to prevent.
 */
@Component
@Slf4j
public class SessionWriteConsentGuard {

  /**
   * Verifies that this room may be written into at all.
   *
   * <p>Deliberately not scoped to the advice seeker: the acceptance criterion is "without consent
   * nobody writes". The counsellor accepts and waits the few seconds the entry room needs, rather
   * than posting into a room whose owner has not yet agreed to the processing.
   *
   * @param session the counselling session whose room is about to be written into
   * @throws ConsentNotRecordedException if the advice seeker has not recorded their consent; a 409
   *     carrying {@code X-Reason: DATA_PRIVACY_CONSENT_MISSING}, so a client can tell it apart from
   *     the reasonless 409 that means "somebody else has already taken this enquiry"
   */
  public void verifyMayWrite(Session session) {
    if (session == null || session.getUser() == null) {
      /* Not a room with an advice seeker awaiting consent. Failing a write on a
      malformed session would turn a hardening measure into an outage; the
      existing access checks own that case. */
      return;
    }
    if (!session.isConsentGateApplicable()) {
      /* Group chats (including SELF_HELP) have no Gate 2 on this session at all —
      their session user is a tenant system user, not a person who could agree.
      See Session#isConsentGateApplicable. */
      return;
    }
    if (session.getConsentedLegalVersionId() != null) {
      return;
    }
    if (session.getUser().getDataPrivacyConfirmation() != null) {
      return;
    }
    log.warn(
        "Refusing a write into session {} — no data privacy confirmation recorded",
        session.getId());
    /* The message reaches a client and concerns somebody who may have chosen not to
    identify themselves, so it names the session and the reason and nothing about
    the person. */
    throw new ConsentNotRecordedException(
        String.format(
            "Session %s cannot be written into before the advice seeker confirmed the data"
                + " protection notice",
            session.getId()));
  }
}
