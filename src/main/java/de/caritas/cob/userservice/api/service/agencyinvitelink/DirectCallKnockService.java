package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Via;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knock-first admission for audio and video direct links (#1475, ADR-029 §2), on the knock model of
 * the self-help groups: a person knocks on the call in their own 1:1 conversation, and the
 * counsellor of that conversation admits or declines, one by one or all at once.
 */
@Service
@RequiredArgsConstructor
public class DirectCallKnockService {

  private final @NonNull GroupChatJoinRequestRepository repository;
  private final @NonNull SessionRepository sessionRepository;

  /** A person who left without cancelling must not stay in the counsellor's list for ever. */
  @Value("${direct-links.knock.pending-minutes:120}")
  private long pendingMinutes = 120;

  /** One open knock per person and conversation: knocking again returns it. */
  @Transactional
  public GroupChatJoinRequest knock(
      Long sessionId, User user, Long inviteLinkId, InviteLinkChatType medium) {
    return repository
        .findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
            sessionId, user.getUserId(), Via.DIRECT_LINK, Status.PENDING)
        .filter(this::stillWaiting)
        .orElseGet(
            () ->
                repository.save(
                    GroupChatJoinRequest.builder()
                        .via(Via.DIRECT_LINK)
                        .sessionId(sessionId)
                        .userId(user.getUserId())
                        .inviteLinkId(inviteLinkId)
                        .medium(medium.name())
                        .status(Status.PENDING)
                        .requestedAt(LocalDateTime.now())
                        .build()));
  }

  public GroupChatJoinRequest getOwn(Long knockId, User user) {
    return requireOwn(knockId, user);
  }

  /** Idempotent: only a pending knock changes. */
  @Transactional
  public GroupChatJoinRequest cancelOwn(Long knockId, User user) {
    var knock = requireOwn(knockId, user);
    if (knock.isPending()) {
      knock.setStatus(Status.CANCELLED);
      knock.setDecidedAt(LocalDateTime.now());
      knock.setDecidedBy(user.getUserId());
      return repository.save(knock);
    }
    return knock;
  }

  public List<GroupChatJoinRequest> pendingFor(Consultant consultant) {
    return repository.findCallKnocksForConsultant(
        consultant.getId(), Via.DIRECT_LINK, Status.PENDING, waitingSince());
  }

  @Transactional
  public GroupChatJoinRequest admit(Long knockId, Consultant consultant) {
    return decide(knockId, consultant, Status.ADMITTED);
  }

  @Transactional
  public GroupChatJoinRequest decline(Long knockId, Consultant consultant) {
    return decide(knockId, consultant, Status.DECLINED);
  }

  @Transactional
  public List<GroupChatJoinRequest> admitAll(Consultant consultant) {
    var now = LocalDateTime.now();
    return pendingFor(consultant).stream()
        .map(
            knock -> {
              knock.setStatus(Status.ADMITTED);
              knock.setDecidedAt(now);
              knock.setDecidedBy(consultant.getId());
              return repository.save(knock);
            })
        .toList();
  }

  private GroupChatJoinRequest decide(Long knockId, Consultant consultant, Status outcome) {
    var knock =
        repository
            .findByIdForUpdate(knockId)
            .filter(k -> k.getVia() == Via.DIRECT_LINK)
            .filter(k -> isCounsellorOf(k.getSessionId(), consultant))
            .orElseThrow(() -> new NotFoundException("Call knock %s not found", knockId));
    if (knock.getStatus() == outcome) {
      return knock;
    }
    if (!knock.isPending()) {
      throw new ConflictException("Call knock is already " + knock.getStatus());
    }
    knock.setStatus(outcome);
    knock.setDecidedAt(LocalDateTime.now());
    knock.setDecidedBy(consultant.getId());
    return repository.save(knock);
  }

  private boolean isCounsellorOf(Long sessionId, Consultant consultant) {
    return sessionId != null
        && sessionRepository
            .findById(sessionId)
            .map(Session::getConsultant)
            .map(owner -> Objects.equals(owner.getId(), consultant.getId()))
            .orElse(false);
  }

  private GroupChatJoinRequest requireOwn(Long knockId, User user) {
    return repository
        .findById(knockId)
        .filter(k -> k.getVia() == Via.DIRECT_LINK)
        .filter(k -> Objects.equals(k.getUserId(), user.getUserId()))
        .orElseThrow(() -> new NotFoundException("Call knock %s not found", knockId));
  }

  private boolean stillWaiting(GroupChatJoinRequest knock) {
    return !knock.getRequestedAt().isBefore(waitingSince());
  }

  private LocalDateTime waitingSince() {
    return LocalDateTime.now().minusMinutes(pendingMinutes);
  }
}
