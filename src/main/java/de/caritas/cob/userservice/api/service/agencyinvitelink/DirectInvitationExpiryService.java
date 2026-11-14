package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * When a direct invitation with an appointment ends (ORISO-Frontend#2252, decision 9): at expiry,
 * and equally when its counsellor switches it off or deletes it. Guests (temporary accounts,
 * interpreters included) leave a shared appointment conversation, which is archived; everybody who
 * came in by link leaves the 1:1 an invitation was created from, which stays open. The guests'
 * accounts themselves are deleted by the ADR-029 temporary-account cleanup, not here.
 *
 * <p>Fail closed: a person counts as removed only once Matrix confirmed it. The status change and
 * the due marks commit first, without any Matrix call under the row lock; the removals run after
 * the commit, each in a transaction of its own. A removal Matrix did not confirm stays due and the
 * sweep retries it after {@link #RETRY_DELAY}, also after the link itself was deleted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectInvitationExpiryService {

  static final Duration RETRY_DELAY = Duration.ofMinutes(5);

  private final @NonNull AgencyInviteLinkRepository links;
  private final @NonNull SessionAppointmentRepository appointments;
  private final @NonNull DirectInvitationMemberRepository members;
  private final @NonNull SessionRepository sessions;
  private final @NonNull UserRepository users;
  private final @NonNull DirectInvitationRoomMembership rooms;
  private final @NonNull SessionArchiveService archive;
  private final @NonNull PlatformTransactionManager transactionManager;
  private final @NonNull Clock clock;

  public List<Long> dueIds(long afterId, int pageSize) {
    return links.findExpiredInvitationIds(now(), afterId, PageRequest.of(0, pageSize));
  }

  public List<Long> pendingRemovalIds(long afterId, int pageSize) {
    return members.findPendingRevocationIds(now(), afterId, PageRequest.of(0, pageSize));
  }

  /**
   * Closes one invitation under a row lock and only from ACTIVE, so competing replicas close it
   * once.
   *
   * @return whether this run closed it
   */
  @Transactional
  public boolean expire(Long linkId) {
    var link = links.findByIdForUpdate(linkId).orElse(null);
    if (link == null
        || !InviteLinkStatus.ACTIVE.name().equals(link.getStatus())
        || link.getAppointmentId() == null
        || link.getExpiresAt() == null
        || !link.getExpiresAt().isBefore(now())) {
      return false;
    }
    link.setStatus(InviteLinkStatus.EXPIRED.name());
    link.setUpdateDate(now());
    links.save(link);
    revoke(link);
    return true;
  }

  /**
   * The people of an invitation that ends lose access: at expiry, when it is switched off and
   * before it is deleted. A link without appointment has no shared conversation and is left alone.
   * Marks the removals due now; Matrix is called once the caller's transaction committed.
   */
  @Transactional
  public void revoke(AgencyInviteLink link) {
    if (link.getAppointmentId() == null) {
      return;
    }
    var session =
        appointments
            .findById(link.getAppointmentId())
            .map(SessionAppointment::getSession)
            .orElse(null);
    if (session == null) {
      return;
    }
    var now = now();
    var due = new ArrayList<Long>();
    for (var member : members.findByInviteLinkIdAndRemovedAtIsNull(link.getId())) {
      // Anyone who entered an existing 1:1 by link leaves it; accounts stay in a shared
      // appointment conversation, which is archived below.
      if (!link.isJoinsExistingSession() && !member.isGuest()) {
        continue;
      }
      if (member.getRevokeDueAt() == null || member.getRevokeDueAt().isAfter(now)) {
        member.setRevokeDueAt(now);
      }
      due.add(members.save(member).getId());
    }
    if (!link.isJoinsExistingSession()) {
      archive.archiveBySystem(session);
    }
    afterCommit(() -> due.forEach(this::removeInOwnTransaction));
  }

  /**
   * Before a conversation is deleted: its Matrix room stays, and the deletion takes every member
   * row with it, so nothing would take the invited people out later. Everybody who entered it by
   * invitation leaves now; the conversation's own advice seeker stays, as in any deletion. Fail
   * closed: the deletion waits until Matrix confirmed everybody out.
   *
   * @throws ServiceUnavailableException while somebody is still in the room or joining it
   */
  @Transactional
  public void requireInvitedOutBeforeDeletion(Session session) {
    var owner = session.getUser() != null ? session.getUser().getUserId() : null;
    var allOut = true;
    for (var member : members.findBySessionIdAndRemovedAtIsNull(session.getId())) {
      if (Objects.equals(member.getUserId(), owner)) {
        continue;
      }
      if (DirectInvitationMemberships.attemptRunning(member, now())) {
        allOut = false;
        continue;
      }
      allOut &= tryRemove(session, member);
      members.save(member);
    }
    if (!allOut) {
      throw new ServiceUnavailableException(
          "Invited people of session " + session.getId() + " are still in its room");
    }
  }

  /**
   * One due removal Matrix has not confirmed yet. The row stays locked while Matrix is asked, so a
   * join attempt of the same person waits and then sees the outcome.
   *
   * @return whether it is confirmed now
   */
  @Transactional
  public boolean retryRemoval(Long memberId) {
    var member = members.findByIdForUpdate(memberId).orElse(null);
    if (member == null || member.getRemovedAt() != null || member.getRevokeDueAt() == null) {
      return false;
    }
    if (DirectInvitationMemberships.attemptRunning(member, now())) {
      // Not in the room yet reads as "out": wait until the attempt confirmed, failed or is stale.
      return false;
    }
    var session = sessions.findById(member.getSessionId()).orElse(null);
    if (session == null) {
      // The conversation is gone, and its room membership with it.
      member.setRemovedAt(now());
      member.setJoinAttempt(null);
      members.save(member);
      return true;
    }
    var removed = tryRemove(session, member);
    members.save(member);
    return removed;
  }

  private void removeInOwnTransaction(Long memberId) {
    try {
      var template = new TransactionTemplate(transactionManager);
      template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      template.executeWithoutResult(status -> retryRemoval(memberId));
    } catch (RuntimeException failure) {
      // Still due: the sweep tries again.
      log.warn(
          "Invited member {} could not be removed yet: {}",
          memberId,
          failure.getClass().getSimpleName());
    }
  }

  private boolean tryRemove(Session session, DirectInvitationMember member) {
    var matrixId = users.findById(member.getUserId()).map(User::getMatrixUserId).orElse(null);
    // A person without a Matrix account (deleted meanwhile) is in no room any more.
    var removed = matrixId == null || rooms.remove(session, matrixId);
    if (removed) {
      member.setRemovedAt(now());
      // A join attempt still on record lost the row: its confirmation must not undo this removal.
      member.setJoinAttempt(null);
    } else {
      member.setRevokeDueAt(now().plus(RETRY_DELAY));
      log.warn(
          "Invited member {} of session {} is still in the room; the sweep tries again",
          member.getId(),
          session.getId());
    }
    return removed;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  private static void afterCommit(Runnable work) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      work.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            work.run();
          }
        });
  }
}
