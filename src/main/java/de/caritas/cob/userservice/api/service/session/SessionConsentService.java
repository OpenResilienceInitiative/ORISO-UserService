package de.caritas.cob.userservice.api.service.session;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationMemberships;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-022 decision 2 — the write path of the session consent pointer (Gate 2).
 *
 * <h2>What this deliberately is not</h2>
 *
 * There is no consent log here and there must never be one. Passing the gate again simply
 * <b>overwrites</b> {@code session.consented_legal_version_id}. ADR-022 rejected a per-user consent
 * event log because it would create a behavioural record about anonymous help-seekers that does not
 * exist today, for evidentiary value the legal-text publication history in ORISO-AgencyService
 * already provides (ADR-021 decision 3). Nothing about <i>who</i> agreed <i>when</i> is recorded —
 * only which version the room is cleared for, so the client knows whether to open the composer.
 *
 * <p>Seam for ADR-022 decisions 4–7 (separate work): the in-chat change notification and the Yes/No
 * re-consent control both end here, in this one method. They change what triggers the call, not
 * what is stored.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SessionConsentService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull DirectInvitationMemberRepository members;

  /**
   * Records that the help-seeker of this room has agreed to the given legal-text version. Called
   * when Gate 2 is passed — the first time and on every re-consent alike.
   *
   * @param sessionId the session whose pointer is moved
   * @param adviceSeeker the authenticated help-seeker; must own the session
   * @param legalVersionId the legal-text version the room is cleared for
   * @throws BadRequestException if no legal version is supplied
   * @throws NotFoundException if the session does not exist
   * @throws ConflictException if the room has no Gate 2 at all (group chats, whose session is owned
   *     by a tenant system user — see {@link Session#isConsentGateApplicable()})
   * @throws ForbiddenException if the authenticated user is neither the session's help-seeker nor a
   *     confirmed member of its shared appointment conversation
   */
  @Transactional
  public void recordConsent(Long sessionId, User adviceSeeker, Long legalVersionId) {
    if (isNull(legalVersionId)) {
      /* A null would be indistinguishable from "never consented" and would silently
      reopen the gate on the next read. */
      throw new BadRequestException(
          String.format("No legal version supplied for session %s", sessionId));
    }
    var session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(
                () -> new NotFoundException(String.format("Session %s not found", sessionId)));
    if (!session.isConsentGateApplicable()) {
      /* Checked before ownership so the caller gets the honest reason. Group-chat
      sessions are owned by a tenant system user, so an ownership check alone would
      answer 403 "not yours" for a room that has no gate to pass in the first place. */
      throw new ConflictException(
          String.format(
              "Session %s has no consent gate — its owner is not a help-seeker", sessionId));
    }
    if (isOwnedBy(session, adviceSeeker)) {
      /* Overwrite, never append: ADR-022 decision 2. */
      session.setConsentedLegalVersionId(legalVersionId);
      sessionRepository.save(session);
      return;
    }
    /* ORISO-Frontend#2252, decision B: everybody else in a shared appointment conversation
    agrees for themselves, on their own member row — never on the owner's pointer. */
    var member =
        lockedMemberRow(session, adviceSeeker)
            .orElseThrow(
                /* The message reaches a client of somebody who may have chosen not to identify
                themselves, so it names the session and nothing about the person. */
                () ->
                    new ForbiddenException(
                        String.format(
                            "Session %s does not belong to the authenticated user", sessionId)));
    member.setConsentedLegalVersionId(legalVersionId);
    members.save(member);
  }

  /**
   * Whether this person still has to pass Gate 2 before writing in the room. Evaluated per person:
   * the owner by the session pointer, a member of a shared appointment conversation by their own
   * row — an owner's consent never clears the gate for anybody else.
   */
  @Transactional(readOnly = true)
  public boolean consentRequired(Session session, String userId) {
    if (!session.isConsentGateApplicable()) {
      return false;
    }
    if (nonNull(userId) && session.isAdvised(userId)) {
      return isNull(session.getConsentedLegalVersionId());
    }
    if (!session.isDirectInvitationConversation() || isNull(userId)) {
      return true;
    }
    return members
        .findBySessionIdAndUserId(session.getId(), userId)
        .map(DirectInvitationMember::getConsentedLegalVersionId)
        .isEmpty();
  }

  /** {@link #consentRequired(Session, String)} for a session known only by its id. */
  @Transactional(readOnly = true)
  public boolean consentRequired(Long sessionId, String userId) {
    return sessionRepository
        .findById(sessionId)
        .map(session -> consentRequired(session, userId))
        .orElse(false);
  }

  private Optional<DirectInvitationMember> lockedMemberRow(Session session, User adviceSeeker) {
    if (!session.isDirectInvitationConversation()
        || isNull(adviceSeeker)
        || isNull(adviceSeeker.getUserId())) {
      return Optional.empty();
    }
    return members
        .findBySessionIdAndUserIdForUpdate(session.getId(), adviceSeeker.getUserId())
        .filter(DirectInvitationMemberships::isMember);
  }

  private boolean isOwnedBy(Session session, User adviceSeeker) {
    return nonNull(adviceSeeker)
        && nonNull(adviceSeeker.getUserId())
        && session.isAdvised(adviceSeeker.getUserId());
  }
}
