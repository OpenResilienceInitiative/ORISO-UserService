package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
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
import java.util.Optional;
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

  /**
   * Let in once: an admission holds for the whole conversation and is returned on every later
   * visit. Otherwise one open knock per person and conversation: knocking again returns it, and
   * after a decline the next visit knocks anew. The conversation row is locked first, so two
   * parallel visits cannot both find no knock and insert two.
   */
  @Transactional
  public GroupChatJoinRequest knock(
      Long sessionId, User user, Long inviteLinkId, InviteLinkChatType medium) {
    sessionRepository
        .findByIdForUpdate(sessionId)
        .orElseThrow(() -> new NotFoundException("Session %s not found", sessionId));
    return admissionOf(sessionId, user)
        .or(
            () ->
                repository
                    .findPendingCallKnocksForUpdate(sessionId, user.getUserId(), Via.DIRECT_LINK)
                    .stream()
                    .findFirst()
                    .filter(this::stillWaitingOrSupersede))
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

  /** The person's admission in this conversation, else their latest knock there. */
  public GroupChatJoinRequest ownFor(Long sessionId, User user) {
    return admissionOf(sessionId, user)
        .or(
            () ->
                repository.findFirstBySessionIdAndUserIdAndViaOrderByIdDesc(
                    sessionId, user.getUserId(), Via.DIRECT_LINK))
        .orElseThrow(() -> new NotFoundException("No call knock in session %s", sessionId));
  }

  private Optional<GroupChatJoinRequest> admissionOf(Long sessionId, User user) {
    return repository.findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
        sessionId, user.getUserId(), Via.DIRECT_LINK, Status.ADMITTED);
  }

  /** Idempotent: only a pending knock changes. */
  @Transactional
  public GroupChatJoinRequest cancelOwn(Long knockId, User user) {
    // Locked like decide: a cancel racing an admission must not overwrite it.
    var knock =
        repository
            .findByIdForUpdate(knockId)
            .filter(k -> k.getVia() == Via.DIRECT_LINK)
            .filter(k -> Objects.equals(k.getUserId(), user.getUserId()))
            .orElseThrow(() -> new NotFoundException("Call knock %s not found", knockId));
    if (knock.isPending()) {
      knock.setStatus(Status.CANCELLED);
      knock.setDecidedAt(LocalDateTime.now());
      knock.setDecidedBy(user.getUserId());
      return repository.save(knock);
    }
    return knock;
  }

  /** Without sessionId and matrixRoomId: all of the counsellor's waiting knocks, as before. */
  public List<GroupChatJoinRequest> pendingFor(
      Consultant consultant, Long sessionId, String matrixRoomId) {
    return repository.findCallKnocksForConsultant(
        consultant.getId(),
        Via.DIRECT_LINK,
        Status.PENDING,
        waitingSince(),
        callScope(sessionId, matrixRoomId));
  }

  @Transactional
  public GroupChatJoinRequest admit(Long knockId, Consultant consultant) {
    return decide(knockId, consultant, Status.ADMITTED);
  }

  @Transactional
  public GroupChatJoinRequest decline(Long knockId, Consultant consultant) {
    return decide(knockId, consultant, Status.DECLINED);
  }

  /** Only the current call: letting in every knock of every conversation at once is refused. */
  @Transactional
  public List<GroupChatJoinRequest> admitAll(
      Consultant consultant, Long sessionId, String matrixRoomId) {
    var scope = callScope(sessionId, matrixRoomId);
    if (scope == null) {
      throw new BadRequestException("admit-all needs sessionId or matrixRoomId");
    }
    var now = LocalDateTime.now();
    // Loaded under lock in one query, so a knock cancelled or declined meanwhile is not pending
    // any more; no earlier unlocked read can leave a stale entity in the persistence context.
    return repository
        .findCallKnocksForConsultantForUpdate(
            consultant.getId(), Via.DIRECT_LINK, Status.PENDING, waitingSince(), scope)
        .stream()
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

  /** The conversation of the current call; the call room is the conversation's Matrix room. */
  private Long callScope(Long sessionId, String matrixRoomId) {
    if (matrixRoomId == null || matrixRoomId.isBlank()) {
      return sessionId;
    }
    var roomSession =
        sessionRepository
            .findByMatrixRoomId(matrixRoomId)
            .map(Session::getId)
            .orElseThrow(() -> new NotFoundException("No conversation for room %s", matrixRoomId));
    if (sessionId != null && !sessionId.equals(roomSession)) {
      throw new BadRequestException("sessionId and matrixRoomId name different conversations");
    }
    return roomSession;
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

  /**
   * A stale knock is cancelled before a new one opens, so an old waiting-room view cannot admit it
   * and the call policy and the conversation endpoints judge the same, latest knock.
   */
  private boolean stillWaitingOrSupersede(GroupChatJoinRequest knock) {
    if (stillWaiting(knock)) {
      return true;
    }
    knock.setStatus(Status.CANCELLED);
    knock.setDecidedAt(LocalDateTime.now());
    repository.save(knock);
    return false;
  }

  private boolean stillWaiting(GroupChatJoinRequest knock) {
    return !knock.getRequestedAt().isBefore(waitingSince());
  }

  private LocalDateTime waitingSince() {
    return LocalDateTime.now().minusMinutes(pendingMinutes);
  }
}
