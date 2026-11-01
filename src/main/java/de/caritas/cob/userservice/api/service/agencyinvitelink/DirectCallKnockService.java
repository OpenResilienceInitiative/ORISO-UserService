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
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Knock-first admission for audio and video direct links (#1475, ADR-029 §2), on the knock model of
 * the self-help groups: a person knocks on the call in their own 1:1 conversation, and the
 * counsellor of that conversation admits or declines, one by one or all at once.
 *
 * <p>Call share links (#1487) use the same queue: a guest knocks on one running call through its
 * link ({@link Via#CALL_LINK}), and every counsellor in that call decides, not only the one who
 * created the link (Frank, 2026-10-28). Membership is the share link's own rule ({@link
 * MatrixCallGuestService#counsellorCall}): in the call room, same Träger. Fail closed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectCallKnockService {

  private final @NonNull GroupChatJoinRequestRepository repository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull AgencyInviteLinkRepository inviteLinkRepository;
  private final @NonNull MatrixCallBindingRepository callBindings;
  private final @NonNull MatrixCallGuestService callGuests;
  private final @NonNull UserRepository users;
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull EventNotificationService eventNotifications;

  private static final Set<Via> CALL_KNOCKS = EnumSet.of(Via.DIRECT_LINK, Via.CALL_LINK);
  private static final Comparator<GroupChatJoinRequest> BY_REQUESTED_AT =
      Comparator.comparing(GroupChatJoinRequest::getRequestedAt)
          .thenComparing(GroupChatJoinRequest::getId);

  /** A person who left without cancelling must not stay in the counsellor's list for ever. */
  @Value("${direct-links.knock.pending-minutes:120}")
  private long pendingMinutes = 120;

  /**
   * Let in once: an admission holds for the whole conversation and is returned on every later
   * visit. Otherwise one open knock per person and conversation: knocking again returns it, and
   * after a decline the next visit knocks anew. The conversation row is locked first, so two
   * parallel visits cannot both find no knock and insert two. A new knock tells the counsellor
   * (FE#1952); returning an open knock or an admission does not ring again.
   */
  @Transactional
  public GroupChatJoinRequest knock(
      Long sessionId, User user, Long inviteLinkId, InviteLinkChatType medium) {
    var session =
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
        .orElseGet(() -> openKnock(session, user, inviteLinkId, medium));
  }

  private GroupChatJoinRequest openKnock(
      Session session, User user, Long inviteLinkId, InviteLinkChatType medium) {
    var knock =
        repository.save(
            GroupChatJoinRequest.builder()
                .via(Via.DIRECT_LINK)
                .sessionId(session.getId())
                .userId(user.getUserId())
                .inviteLinkId(inviteLinkId)
                .medium(medium.name())
                .status(Status.PENDING)
                .requestedAt(LocalDateTime.now())
                .build());
    tellCounsellorAfterCommit(session, knock);
    return knock;
  }

  /**
   * The entry is written in its own transaction, so it waits for the knock to commit: a knock that
   * rolls back must not ring the counsellor for a person who is not waiting. At afterCommit the
   * writer's REQUIRES_NEW boundary still persists; a failure there never reaches the person.
   */
  private void tellCounsellorAfterCommit(Session session, GroupChatJoinRequest knock) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      eventNotifications.createCallKnockRequestedNotification(session, knock);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              eventNotifications.createCallKnockRequestedNotification(session, knock);
            } catch (RuntimeException notificationFailure) {
              log.warn(
                  "Knock {} committed, but its notification for session {} failed",
                  knock.getId(),
                  session.getId(),
                  notificationFailure);
            }
          }
        });
  }

  /**
   * A guest knocks on one running call through its share link (#1487). Same rules as {@link
   * #knock}; the caller holds the lock on the link row, so parallel visits insert one knock. The
   * knock never names the conversation of the call: the guest must not learn it.
   */
  public GroupChatJoinRequest knockOnCallLink(Long linkId, User user, String medium) {
    return repository
        .findFirstByInviteLinkIdAndUserIdAndViaAndStatusOrderByIdDesc(
            linkId, user.getUserId(), Via.CALL_LINK, Status.PENDING)
        .filter(this::stillWaiting)
        .orElseGet(
            () ->
                repository.save(
                    GroupChatJoinRequest.builder()
                        .via(Via.CALL_LINK)
                        .userId(user.getUserId())
                        .inviteLinkId(linkId)
                        .medium(medium)
                        .status(Status.PENDING)
                        .requestedAt(LocalDateTime.now())
                        .build()));
  }

  /**
   * Let in once (#1487): the guest's admission on this link holds until the call ends. Locked, so a
   * counsellor's decline (which locks the same row first) waits for a seating in progress and then
   * sees and revokes its seat, or the seating sees the decline.
   */
  public Optional<GroupChatJoinRequest> callLinkAdmission(Long linkId, User user) {
    return repository
        .findCallLinkKnocksForUpdate(
            linkId,
            user.getUserId(),
            Via.CALL_LINK,
            Status.ADMITTED,
            org.springframework.data.domain.PageRequest.of(0, 1))
        .stream()
        .findFirst();
  }

  /** "Anklopfen" switched off (#1487): everybody waiting on the link is let in at once. */
  public List<GroupChatJoinRequest> admitWaitingOnCallLink(Long linkId, String decidedBy) {
    return closeWaitingOnCallLink(linkId, Status.ADMITTED, decidedBy);
  }

  /**
   * "Anklopfen" switched back on (#1487): earlier admissions no longer let anybody in again. Guests
   * already in the call keep their seat; a guest who joins anew knocks.
   */
  public List<GroupChatJoinRequest> cancelAdmissionsOnCallLink(Long linkId) {
    var now = LocalDateTime.now();
    return repository
        .findByInviteLinkIdAndViaAndStatus(linkId, Via.CALL_LINK, Status.ADMITTED)
        .stream()
        .map(
            knock -> {
              knock.setStatus(Status.CANCELLED);
              knock.setDecidedAt(now);
              return repository.save(knock);
            })
        .toList();
  }

  /** A counsellor removed the guest from the call (#1487): no open or granted knock remains. */
  public void declineGuestOnCallLink(Long linkId, String userId, String decidedBy) {
    var now = LocalDateTime.now();
    repository.findByInviteLinkIdAndUserIdAndVia(linkId, userId, Via.CALL_LINK).stream()
        .filter(knock -> knock.isPending() || knock.getStatus() == Status.ADMITTED)
        .forEach(
            knock -> {
              knock.setStatus(Status.DECLINED);
              knock.setDecidedAt(now);
              knock.setDecidedBy(decidedBy);
              repository.save(knock);
            });
  }

  /** The call of the link ended (#1487): nobody keeps waiting for it. */
  public List<GroupChatJoinRequest> cancelWaitingOnCallLink(Long linkId) {
    return closeWaitingOnCallLink(linkId, Status.CANCELLED, null);
  }

  private List<GroupChatJoinRequest> closeWaitingOnCallLink(
      Long linkId, Status outcome, String decidedBy) {
    var now = LocalDateTime.now();
    return repository
        .findByInviteLinkIdAndViaAndStatus(linkId, Via.CALL_LINK, Status.PENDING)
        .stream()
        .map(
            knock -> {
              knock.setStatus(outcome);
              knock.setDecidedAt(now);
              knock.setDecidedBy(decidedBy);
              return repository.save(knock);
            })
        .toList();
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
            .filter(k -> CALL_KNOCKS.contains(k.getVia()))
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

  /**
   * Without sessionId and matrixRoomId: all of the counsellor's waiting knocks, as before
   * (call-link knocks only of links they created). With either, only the current call's:
   * direct-link knocks of that conversation and call-link knocks (#1487) of any share link of a
   * call in that room. Either way a call-link knock is shown only to a counsellor who is in its
   * call.
   */
  public List<GroupChatJoinRequest> pendingFor(
      Consultant consultant, Long sessionId, String matrixRoomId) {
    var scope = callScope(sessionId, matrixRoomId);
    var since = waitingSince();
    return Stream.concat(
            scope.directLinks()
                ? repository
                    .findCallKnocksForConsultant(
                        consultant.getId(),
                        Via.DIRECT_LINK,
                        Status.PENDING,
                        since,
                        scope.sessionId())
                    .stream()
                : Stream.<GroupChatJoinRequest>empty(),
            scope.callLinks()
                ? repository
                    .findCallLinkKnocksForConsultant(
                        consultant.getId(), Via.CALL_LINK, Status.PENDING, since, scope.roomId())
                    .stream()
                    .filter(inCallOf(consultant))
                : Stream.<GroupChatJoinRequest>empty())
        .sorted(BY_REQUESTED_AT)
        .toList();
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
    if (!scope.scoped()) {
      throw new BadRequestException("admit-all needs sessionId or matrixRoomId");
    }
    // A group-call room has no 1:1 conversation to lock; its knocks are locked row by row below.
    if (scope.sessionId() != null) {
      lockConversation(scope.sessionId());
    }
    var now = LocalDateTime.now();
    // Loaded under lock in one query, so a knock cancelled or declined meanwhile is not pending
    // any more; no earlier unlocked read can leave a stale entity in the persistence context.
    var since = waitingSince();
    return Stream.concat(
            scope.directLinks()
                ? repository
                    .findCallKnocksForConsultantForUpdate(
                        consultant.getId(),
                        Via.DIRECT_LINK,
                        Status.PENDING,
                        since,
                        scope.sessionId())
                    .stream()
                : Stream.<GroupChatJoinRequest>empty(),
            scope.callLinks()
                ? repository
                    .findCallLinkKnocksForConsultantForUpdate(
                        consultant.getId(), Via.CALL_LINK, Status.PENDING, since, scope.roomId())
                    .stream()
                    .filter(inCallOf(consultant))
                : Stream.<GroupChatJoinRequest>empty())
        .sorted(BY_REQUESTED_AT)
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
    repository.findSessionIdById(knockId).ifPresent(this::lockConversation);
    var knock =
        repository
            .findByIdForUpdate(knockId)
            .filter(k -> decides(k, consultant))
            .orElseThrow(() -> new NotFoundException("Call knock %s not found", knockId));
    if (knock.getStatus() == outcome) {
      return knock;
    }
    if (knock.getVia() == Via.CALL_LINK
        && knock.getStatus() == Status.ADMITTED
        && outcome == Status.DECLINED) {
      return revokeCallLinkAdmission(knock, consultant);
    }
    if (!knock.isPending()) {
      throw new ConflictException("Call knock is already " + knock.getStatus());
    }
    knock.setStatus(outcome);
    knock.setDecidedAt(LocalDateTime.now());
    knock.setDecidedBy(consultant.getId());
    return repository.save(knock);
  }

  /**
   * Declining an admitted call-link guest takes the admission back (#1487): the guest loses the
   * call-room seat at once and cannot come back through the link for this call.
   */
  private GroupChatJoinRequest revokeCallLinkAdmission(
      GroupChatJoinRequest knock, Consultant consultant) {
    knock.setStatus(Status.DECLINED);
    knock.setDecidedAt(LocalDateTime.now());
    knock.setDecidedBy(consultant.getId());
    var binding =
        inviteLinkRepository
            .findById(knock.getInviteLinkId())
            .map(link -> link.getCallBindingId())
            .flatMap(callBindings::findById)
            .orElse(null);
    var guestMatrixId =
        users
            .findByUserIdAndDeleteDateIsNull(knock.getUserId())
            .map(User::getMatrixUserId)
            .orElse(null);
    var moderator =
        consultants
            .findByIdAndDeleteDateIsNull(consultant.getId())
            .map(Consultant::getMatrixUserId)
            .orElse(null);
    if (binding != null) {
      callGuests.revokeSeat(binding, guestMatrixId, moderator);
    }
    return repository.save(knock);
  }

  private boolean decides(GroupChatJoinRequest knock, Consultant consultant) {
    return switch (knock.getVia()) {
      case DIRECT_LINK -> isCounsellorOf(knock.getSessionId(), consultant);
      case CALL_LINK -> isInCallOfLink(knock.getInviteLinkId(), consultant);
      default -> false;
    };
  }

  /** One membership lookup per share link, however many guests knock on it. */
  private Predicate<GroupChatJoinRequest> inCallOf(Consultant consultant) {
    var byLink = new HashMap<Long, Boolean>();
    return knock ->
        knock.getInviteLinkId() != null
            && byLink.computeIfAbsent(
                knock.getInviteLinkId(), linkId -> isInCallOfLink(linkId, consultant));
  }

  /**
   * Any counsellor in the link's running call decides, whoever created the link, and only while the
   * link is active. Same rule as changing the link; Matrix unreachable counts as not in it.
   */
  private boolean isInCallOfLink(Long linkId, Consultant consultant) {
    return linkId != null
        && consultant.getTenantId() != null
        && inviteLinkRepository
            .findById(linkId)
            .filter(link -> InviteLinkKind.CALL.name().equals(link.getLinkKind()))
            .filter(link -> InviteLinkStatus.ACTIVE.name().equals(link.getStatus()))
            .filter(link -> consultant.getTenantId().equals(link.getTenantId()))
            .map(link -> link.getCallBindingId())
            .flatMap(callBindings::findById)
            .map(
                binding ->
                    callGuests.counsellorCall(
                        consultant.getTenantId(),
                        consultant.getId(),
                        binding.getSourceRoomId(),
                        binding.getCallId()))
            .map(call -> call.outcome() == MatrixCallGuestService.Outcome.ADMITTED)
            .orElse(false);
  }

  /**
   * Admissions take the conversation lock that knock takes, in the same order (conversation, then
   * knock), so an admission cannot slip between knock's admission check and its pending lookup.
   */
  private void lockConversation(Long sessionId) {
    sessionRepository
        .findByIdForUpdate(sessionId)
        .orElseThrow(() -> new NotFoundException("Session %s not found", sessionId));
  }

  /**
   * The current call: its conversation and the conversation's Matrix room. Unscoped when neither is
   * given. A room without a 1:1 conversation is still a call scope when a call runs in it, so a
   * share link of a group call is found (#1487); it then matches no direct-link knock.
   */
  private CallScope callScope(Long sessionId, String matrixRoomId) {
    if (matrixRoomId == null || matrixRoomId.isBlank()) {
      if (sessionId == null) {
        return CallScope.ANY;
      }
      var room = sessionRepository.findById(sessionId).map(Session::getMatrixRoomId).orElse(null);
      return new CallScope(sessionId, room, true, room != null);
    }
    var roomSession = sessionRepository.findByMatrixRoomId(matrixRoomId).map(Session::getId);
    if (roomSession.isEmpty() && !callBindings.existsBySourceRoomId(matrixRoomId)) {
      throw new NotFoundException("No conversation for room %s", matrixRoomId);
    }
    if (sessionId != null && !sessionId.equals(roomSession.orElse(null))) {
      throw new BadRequestException("sessionId and matrixRoomId name different conversations");
    }
    return new CallScope(roomSession.orElse(null), matrixRoomId, roomSession.isPresent(), true);
  }

  /** {@code sessionId}/{@code roomId} null with the flag on means unscoped. */
  private record CallScope(Long sessionId, String roomId, boolean directLinks, boolean callLinks) {
    static final CallScope ANY = new CallScope(null, null, true, true);

    boolean scoped() {
      return this != ANY;
    }
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
        .filter(k -> CALL_KNOCKS.contains(k.getVia()))
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
