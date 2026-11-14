package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that decides whether a person who came in by a direct invitation is a member of its
 * conversation (ORISO-Frontend#2252). A row is in one of three states:
 *
 * <ul>
 *   <li><b>member</b>: Matrix confirmed the join, no removal due ({@code removedAt} and {@code
 *       revokeDueAt} empty, no join attempt);
 *   <li><b>pending</b>: a removal is due ({@code revokeDueAt}) or a join attempt runs ({@code
 *       joinAttempt}); never counts as member;
 *   <li><b>removed</b>: the sweep saw Matrix confirm the person is out ({@code removedAt}).
 * </ul>
 *
 * Every change runs under the row's write lock, a join attempt only touches its own row state (its
 * attempt token), and nothing here cancels a due removal except a confirmed join of a still open
 * invitation. Only the sweep, after Matrix confirmed, marks a row removed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectInvitationMemberships {

  /** How long a join may take before the sweep treats the attempt as abandoned. */
  static final Duration JOIN_GRACE = Duration.ofMinutes(2);

  private final @NonNull DirectInvitationMemberRepository members;
  private final @NonNull AgencyInviteLinkRepository links;
  private final @NonNull SessionAppointmentRepository appointments;
  private final @NonNull Clock clock;

  public static boolean isMember(DirectInvitationMember row) {
    return row.getRemovedAt() == null
        && row.getRevokeDueAt() == null
        && row.getJoinAttempt() == null;
  }

  /** The first person of an appointment conversation, who created it: a member at once. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordCreator(Session session, User user, AgencyInviteLink link) {
    members.save(newRow(session, user, link));
  }

  /**
   * Starts a join attempt before Matrix is asked: the row is pending with a removal due after
   * {@link #JOIN_GRACE} at the latest (an earlier due removal is kept), so a revoke running during
   * the join sees the person and an abandoned attempt is cleaned up by the sweep.
   *
   * @return the attempt token, or empty when the person is a member already
   */
  @Transactional
  public Optional<String> reserve(Session session, User user, AgencyInviteLink link) {
    var row =
        members
            .findBySessionIdAndUserIdForUpdate(session.getId(), user.getUserId())
            .orElseGet(() -> newRow(session, user, link));
    if (row.getId() != null && isMember(row)) {
      return Optional.empty();
    }
    var attempt = UUID.randomUUID().toString();
    var due = row.getRemovedAt() != null ? null : row.getRevokeDueAt();
    row.setRemovedAt(null);
    row.setRevokeDueAt(earliest(due, now().plus(JOIN_GRACE)));
    row.setJoinAttempt(attempt);
    row.setGuest(isGuest(user));
    row.setInviteLinkId(link.getId());
    row.setJoinedAt(now());
    members.save(row);
    return Optional.of(attempt);
  }

  /**
   * After Matrix confirmed the join: the person becomes a member only if this attempt still owns
   * the row and the invitation is still open (active, not expired, not deleted, appointment not
   * cancelled), read under the invitation's row lock. Otherwise a removal is due now.
   *
   * @return whether the person is a member now
   */
  @Transactional
  public boolean confirm(Session session, User user, Long linkId, String attempt) {
    // Lock order invitation, then row, as in an expiry, a switch-off or a delete.
    var open = isOpen(linkId);
    var row = members.findBySessionIdAndUserIdForUpdate(session.getId(), user.getUserId());
    if (row.isEmpty() || !Objects.equals(row.get().getJoinAttempt(), attempt)) {
      // Another attempt or the sweep took the row over; that one decides.
      return false;
    }
    var member = row.get();
    member.setJoinAttempt(null);
    if (open) {
      member.setRevokeDueAt(null);
      members.save(member);
      return true;
    }
    member.setRevokeDueAt(earliest(member.getRevokeDueAt(), now()));
    members.save(member);
    log.warn("Invitation {} ended while member {} joined; removal is due", linkId, member.getId());
    return false;
  }

  /**
   * A join attempt failed: the removal is due now (or earlier, if it already was). Never marks the
   * person removed and never cancels a due removal; the sweep checks Matrix and does that.
   */
  @Transactional
  public void abandon(Session session, User user, String attempt) {
    members
        .findBySessionIdAndUserIdForUpdate(session.getId(), user.getUserId())
        .filter(row -> Objects.equals(row.getJoinAttempt(), attempt))
        .ifPresent(
            row -> {
              row.setJoinAttempt(null);
              row.setRevokeDueAt(earliest(row.getRevokeDueAt(), now()));
              members.save(row);
            });
  }

  private boolean isOpen(Long linkId) {
    return links
        .findByIdForUpdate(linkId)
        .filter(l -> InviteLinkStatus.ACTIVE.name().equals(l.getStatus()))
        .filter(l -> l.getExpiresAt() == null || l.getExpiresAt().isAfter(now()))
        .filter(l -> l.getAppointmentId() != null)
        .flatMap(l -> appointments.findById(l.getAppointmentId()))
        .filter(a -> a.getStatus() != SessionAppointment.Status.CANCELLED)
        .isPresent();
  }

  private DirectInvitationMember newRow(Session session, User user, AgencyInviteLink link) {
    return DirectInvitationMember.builder()
        .sessionId(session.getId())
        .userId(user.getUserId())
        .inviteLinkId(link.getId())
        .guest(isGuest(user))
        .joinedAt(now())
        .build();
  }

  private static boolean isGuest(User user) {
    return user.getTemporaryAccountSnapshot() != null;
  }

  private static LocalDateTime earliest(LocalDateTime current, LocalDateTime candidate) {
    return current == null || candidate.isBefore(current) ? candidate : current;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
