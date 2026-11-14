package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import de.caritas.cob.userservice.api.model.MatrixCallObservationEnd;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationEndRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Observation-only registry for encrypted calls whose invite UserService cannot read: internal
 * groups (#2166/#1624) and 1:1 sessions (#2237). Feeds the RTC state lookup and the Activity
 * Timeline only. An observation row is never read by guest admission, share links, knocks or any
 * other access decision; those read {@code MatrixCallBinding} exclusively.
 *
 * <p>FE#2209 (decision of 13 Nov 2026): the one deliberate exception is not this row but the
 * registration request behind it. When the registering person is the assigned counsellor of a 1:1
 * session, {@link MatrixSessionCallBindingService} re-checks that request against Matrix on its own
 * (conversation, assignment, membership, call-room binding and creator) and may then write the
 * authoritative {@code MatrixCallBinding}. Group chats, advice seekers, other counsellors and
 * guests never get a binding this way, and nothing here may derive one from a stored observation.
 *
 * <p>#1630: no database transaction is open while Matrix is called. Every Matrix lookup happens
 * first, then one short transaction writes, then the notifications follow (each in its own
 * transaction). The methods are therefore deliberately not {@code @Transactional}.
 */
@Service
@RequiredArgsConstructor
public class MatrixCallObservationService {
  /** A registration is live for this long unless it is renewed or ended. */
  static final long LEASE_MILLIS = 10 * 60 * 1000L;

  /** #1628: the server looks at who is still in the call at most this often per call. */
  static final long LIVENESS_CHECK_MILLIS = 60 * 1000L;

  /** #1628: a call nobody has connected to yet is not ended before this much time has passed. */
  static final long START_GRACE_MILLIS = 2 * 60 * 1000L;

  public enum Outcome {
    OK,
    NOT_FOUND
  }

  private final MatrixCallObservationRepository observations;
  private final MatrixCallObservationEndRepository ends;
  private final MatrixCallBindingRepository bindings;
  private final MatrixCallConversationResolver conversations;
  private final UserRepository users;
  private final ConsultantRepository consultants;
  private final MatrixSynapseService matrix;
  private final EventNotificationService notifications;
  private final MatrixCallLifecycleService lifecycle;
  private final MatrixSessionCallBindingService sessionBindings;
  private final PlatformTransactionManager transactionManager;

  public Outcome register(
      AuthenticatedUser actor,
      String sourceRoomId,
      String callId,
      String mediaRoomId,
      boolean video) {
    if (blank(sourceRoomId, 255) || blank(callId, 191) || blank(mediaRoomId, 255)) {
      return Outcome.NOT_FOUND;
    }
    var conversation = conversations.resolveForWriting(sourceRoomId).orElse(null);
    // Exactly one source: an internal group (chat) or a 1:1 session (#2237). The conversation
    // type already enforces this; the explicit check keeps a row from ever naming both or neither.
    if (conversation == null
        || (conversation.getChatId() == null) == (conversation.getSessionId() == null)
        || actor.getTenantId() == null
        || !actor.getTenantId().equals(conversation.getTenantId())) {
      return Outcome.NOT_FOUND;
    }
    // A readable invite already produced the authoritative binding; notifications of both paths
    // share one dedupe key per (room, call), so an observation must never pre-empt that call.
    // FE#2209 round 3: an asker's plaintext invite of the same call id is no such binding; it must
    // not keep the assigned counsellor's call from being registered.
    if (preemptedByBinding(sourceRoomId, callId)) return Outcome.NOT_FOUND;
    String creator = matrixIdOf(actor, conversation.getTenantId()).orElse(null);
    // Matrix evidence first, with no transaction open (#1630).
    var members = matrix.getCallRoomMembers(sourceRoomId).orElse(null);
    if (creator == null || members == null || !members.contains(creator)) {
      return Outcome.NOT_FOUND;
    }
    var binding = matrix.getCallRoomBinding(mediaRoomId, creator).orElse(null);
    if (binding == null
        || !callId.equals(binding.get("call_id"))
        || !sourceRoomId.equals(binding.get("source_room_id"))) {
      return Outcome.NOT_FOUND;
    }
    var origin = matrix.getCallRoomOrigin(mediaRoomId, creator).orElse(null);
    if (origin == null
        || !creator.equals(origin.creatorMatrixId())
        || !creator.equals(origin.bindingSenderMatrixId())) {
      return Outcome.NOT_FOUND;
    }
    Registration written =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> writeRegistration(conversation, creator, callId, mediaRoomId, video));
    if (written == Registration.REJECTED) return Outcome.NOT_FOUND;
    if (written == Registration.CREATED) {
      // Verified evidence: creator is a current member, the media room binding and creator match.
      // Audience: current members of this exact room, same tenant, active accounts, not the
      // creator. After the commit, so a notification never names a row that may roll back.
      notifyInvited(conversation, members, creator, callId, mediaRoomId, video);
    }
    return Outcome.OK;
  }

  private enum Registration {
    CREATED,
    RENEWED,
    REJECTED
  }

  private Registration writeRegistration(
      MatrixCallConversation conversation,
      String creator,
      String callId,
      String mediaRoomId,
      boolean video) {
    String sourceRoomId = conversation.getMatrixRoomId();
    if (!conversations.lockCurrentForWriting(conversation)) return Registration.REJECTED;
    // CodeRabbit 4562143169: again under the source lock, which bind and the readable-invite path
    // hold while they write a binding; one committed during the Matrix lookups wins.
    if (preemptedByBinding(sourceRoomId, callId)) return Registration.REJECTED;
    long now = System.currentTimeMillis();
    var existing =
        observations.findForUpdateBySourceRoomIdAndCallId(sourceRoomId, callId).orElse(null);
    // #1628: the creator's own end, also one that came before the registration, wins. Only the
    // creator's end counts; somebody else's end of a call they could not register is kept but
    // does not suppress the creator's registration. Locking read, so an end being written right
    // now is waited for and seen.
    if (ends.findForUpdateBySourceRoomIdAndCallIdAndActorMatrixId(sourceRoomId, callId, creator)
        .isPresent()) {
      return Registration.REJECTED;
    }
    if (existing != null) {
      // #1628: an ended call stays ended.
      if (!creator.equals(existing.getCreatorMatrixId())
          || !mediaRoomId.equals(existing.getMediaRoomId())
          || existing.getEndedAt() != null
          || now >= existing.getExpiresAt()) {
        return Registration.REJECTED;
      }
      existing.renew(now, now + LEASE_MILLIS);
      observations.save(existing);
      return Registration.RENEWED;
    }
    if (observations.findByMediaRoomId(mediaRoomId).isPresent()) return Registration.REJECTED;
    observations.saveAndFlush(
        MatrixCallObservation.builder()
            .origin(MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION)
            .sourceRoomId(sourceRoomId)
            .callId(callId)
            .mediaRoomId(mediaRoomId)
            .creatorMatrixId(creator)
            .tenantId(conversation.getTenantId())
            .chatId(conversation.getChatId())
            .sessionId(conversation.getSessionId())
            .video(video)
            .registeredAt(now)
            .lastSeenAt(now)
            .expiresAt(now + LEASE_MILLIS)
            .build());
    return Registration.CREATED;
  }

  /**
   * A binding of this call that the observation must never pre-empt: a group chat's always, a 1:1
   * session call's only with the assigned counsellor's authority (FE#2209 round 3).
   */
  private boolean preemptedByBinding(String sourceRoomId, String callId) {
    return bindings
        .findBySourceRoomIdAndCallId(sourceRoomId, callId)
        .filter(b -> b.getSessionId() == null || sessionBindings.preemptsRegistration(b))
        .isPresent();
  }

  private void notifyInvited(
      MatrixCallConversation conversation,
      List<String> members,
      String creator,
      String callId,
      String mediaRoomId,
      boolean video) {
    var creatorIdentity = identity(creator, conversation.getTenantId()).orElse(null);
    if (creatorIdentity == null) return;
    for (String member : members) {
      if (member.equals(creator)) continue;
      var recipient = identity(member, conversation.getTenantId()).orElse(null);
      if (recipient == null || !lifecycle.isActiveRecipient(recipient.id())) continue;
      notifications.createCallInvitationNotification(
          conversation,
          recipient.id(),
          recipient.consultant(),
          creatorIdentity.id(),
          callId,
          mediaRoomId,
          video);
    }
  }

  /**
   * Only the registering creator may end a registration. When the end arrives before the
   * registration (#1628: the browser gave up waiting, the server was still working on it), any
   * current member of the conversation may end the call; every such end is stored on its own, per
   * person, and the creator's own end rejects the late registration of that call.
   */
  public Outcome end(AuthenticatedUser actor, String sourceRoomId, String callId) {
    if (blank(sourceRoomId, 255) || blank(callId, 191) || actor.getTenantId() == null) {
      return Outcome.NOT_FOUND;
    }
    var conversation = conversations.resolve(sourceRoomId).orElse(null);
    if (conversation == null || !actor.getTenantId().equals(conversation.getTenantId())) {
      return Outcome.NOT_FOUND;
    }
    String caller = matrixIdOf(actor, conversation.getTenantId()).orElse(null);
    if (caller == null) return Outcome.NOT_FOUND;
    // Unknown audience: fail before recording the end, so a retry can still notify (#1630: and
    // before any transaction is open).
    List<String> members =
        matrix
            .getCallRoomMembers(sourceRoomId)
            .orElseThrow(
                () ->
                    new MatrixSynapseService.CallLookupUnavailableException(
                        "call audience unavailable"));
    // FE#2209 review: a 1:1 session call's binding ends in the same transaction as its observation,
    // so neither a failing notification nor a binding written meanwhile can leave it live.
    boolean oneToOne = conversation.getSessionId() != null && conversation.getChatId() == null;
    Ending ending;
    try {
      ending =
          writeEnd(
              actor,
              conversation,
              caller,
              callId,
              members,
              boundId(oneToOne, sourceRoomId, callId));
    } catch (DataIntegrityViolationException | PessimisticLockingFailureException raced) {
      // The registration or a binding landed while the end was written: end that one instead,
      // with the binding (if any now) locked first.
      ending =
          writeEnd(
              actor,
              conversation,
              caller,
              callId,
              members,
              boundId(oneToOne, sourceRoomId, callId));
    }
    // After the commit: first the guest seats, then the notifications; neither can undo the end.
    if (ending.endedCallRoom() != null)
      sessionBindings.revokeGuestsAfterEnd(ending.endedCallRoom());
    if (!ending.observed() && ending.endedCallRoom() == null) return Outcome.NOT_FOUND;
    if (ending.endedNow()) notifyEnded(conversation, members, ending.row());
    return Outcome.OK;
  }

  /**
   * {@code observed}: this caller's end was stored (and the observation ended, if registered);
   * {@code endedCallRoom}: the call room of the session binding this end closed.
   */
  private record Ending(
      MatrixCallObservation row, boolean endedNow, boolean observed, String endedCallRoom) {}

  /** Outside the transaction: the session call's binding, which the end locks first. */
  private Long boundId(boolean oneToOne, String sourceRoomId, String callId) {
    return oneToOne ? sessionBindings.boundCallId(sourceRoomId, callId).orElse(null) : null;
  }

  private Ending writeEnd(
      AuthenticatedUser actor,
      MatrixCallConversation conversation,
      String caller,
      String callId,
      List<String> members,
      Long boundId) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              // Lock order: binding (as the lifecycle takes it), session, observation, as bind and
              // registration take session before observation.
              if (boundId != null) sessionBindings.lockBinding(boundId);
              // The session's call lock; a closed session still holds it, and ending is always
              // allowed.
              conversations.lockCurrentForWriting(conversation);
              long now = System.currentTimeMillis();
              var observed = endObservation(conversation, caller, callId, members, now);
              String endedCallRoom =
                  conversation.getSessionId() != null && conversation.getChatId() == null
                      ? sessionBindings.endWhileSessionLocked(
                          actor, conversation, callId, now, boundId)
                      : null;
              return observed == null
                  ? new Ending(null, false, false, endedCallRoom)
                  : new Ending(observed.row(), observed.endedNow(), true, endedCallRoom);
            });
  }

  private record ObservationEnd(MatrixCallObservation row, boolean endedNow) {}

  private ObservationEnd endObservation(
      MatrixCallConversation conversation,
      String caller,
      String callId,
      List<String> members,
      long now) {
    var row =
        observations
            .findForUpdateBySourceRoomIdAndCallId(conversation.getMatrixRoomId(), callId)
            .orElse(null);
    if (row == null) {
      if (!members.contains(caller)) return null;
      recordEnd(conversation, callId, caller, now);
      return new ObservationEnd(null, false);
    }
    if (!Objects.equals(row.getTenantId(), conversation.getTenantId())) return null;
    if (!caller.equals(row.getCreatorMatrixId())) {
      // CodeRabbit 4563094690: somebody else (an asker) owns the observation of this call id. Their
      // observation stays as it is, but this member's own end is stored, so a bind of this member
      // still in flight (and any later one) sees it and does not start a live call.
      if (!members.contains(caller)) return null;
      recordEnd(conversation, callId, caller, now);
      return new ObservationEnd(null, false);
    }
    recordEnd(conversation, callId, caller, now);
    if (row.getEndedAt() != null) return new ObservationEnd(row, false);
    row.finish(now);
    observations.save(row);
    return new ObservationEnd(row, true);
  }

  /** One stored end per person and call; a repeated end keeps the first time. */
  private void recordEnd(
      MatrixCallConversation conversation, String callId, String actorMatrixId, long now) {
    String sourceRoomId = conversation.getMatrixRoomId();
    if (ends.findForUpdateBySourceRoomIdAndCallIdAndActorMatrixId(
            sourceRoomId, callId, actorMatrixId)
        .isPresent()) {
      return;
    }
    ends.saveAndFlush(
        MatrixCallObservationEnd.builder()
            .sourceRoomId(sourceRoomId)
            .callId(callId)
            .actorMatrixId(actorMatrixId)
            .tenantId(conversation.getTenantId())
            .chatId(conversation.getChatId())
            .sessionId(conversation.getSessionId())
            .endedAt(now)
            .build());
  }

  private void notifyEnded(
      MatrixCallConversation conversation, List<String> members, MatrixCallObservation row) {
    for (String member : members) {
      var recipient = identity(member, row.getTenantId()).orElse(null);
      if (recipient == null || !lifecycle.isActiveRecipient(recipient.id())) continue;
      notifications.createCallEndedNotification(
          conversation,
          recipient.id(),
          recipient.consultant(),
          row.getCallId(),
          row.getMediaRoomId(),
          row.getRegisteredAt(),
          row.getEndedAt());
    }
  }

  /**
   * State for an authorized reader (conversation member). The caller checks membership and passes
   * the current members of the conversation.
   *
   * <p>#1628: the lease alone depends on the creator's browser. So at most once a minute per call
   * the server also looks at the MatrixRTC memberships in the media room: while any member of the
   * conversation is still in the call, the lease is extended; once everybody has left, the call
   * ends. Anything inconclusive (Matrix unavailable, no membership this server understands) leaves
   * the row as it is.
   */
  Optional<MatrixCallStateService.CallState> readState(
      MatrixCallConversation conversation, String callId, List<String> sourceMembers) {
    if ((conversation.getChatId() == null) == (conversation.getSessionId() == null)) {
      return Optional.empty();
    }
    var row = readable(conversation, callId).orElse(null);
    if (row == null) return endedBeforeRegistration(conversation, callId);
    if (needsLivenessCheck(row, System.currentTimeMillis())) {
      checkLiveness(conversation, row, sourceMembers);
      row = readable(conversation, callId).orElse(null);
      if (row == null) return Optional.empty();
    }
    return Optional.of(toState(row));
  }

  private Optional<MatrixCallObservation> readable(
      MatrixCallConversation conversation, String callId) {
    // Exact source match on both ids: a session row is never readable through a chat that happens
    // to share its numeric id, nor through another session, and vice versa.
    return observations
        .findBySourceRoomIdAndCallId(conversation.getMatrixRoomId(), callId)
        .filter(row -> Objects.equals(row.getTenantId(), conversation.getTenantId()))
        .filter(row -> Objects.equals(row.getChatId(), conversation.getChatId()))
        .filter(row -> Objects.equals(row.getSessionId(), conversation.getSessionId()));
  }

  /** #1628: a call that was ended before its registration landed reads as ended. */
  private Optional<MatrixCallStateService.CallState> endedBeforeRegistration(
      MatrixCallConversation conversation, String callId) {
    return ends.findFirstBySourceRoomIdAndCallIdOrderByEndedAtAsc(
            conversation.getMatrixRoomId(), callId)
        .filter(end -> Objects.equals(end.getTenantId(), conversation.getTenantId()))
        .filter(end -> Objects.equals(end.getChatId(), conversation.getChatId()))
        .filter(end -> Objects.equals(end.getSessionId(), conversation.getSessionId()))
        .map(
            end ->
                new MatrixCallStateService.CallState(
                    end.getSourceRoomId(),
                    end.getCallId(),
                    null,
                    "audio",
                    "ended",
                    end.getEndedAt(),
                    end.getEndedAt(),
                    end.getEndedAt(),
                    0,
                    List.of()));
  }

  private static boolean needsLivenessCheck(MatrixCallObservation row, long now) {
    if (row.getEndedAt() != null || row.getMediaRoomId() == null) return false;
    long last = Math.max(row.getLastSeenAt(), nonNull(row.getLivenessCheckedAt()));
    return now - last >= LIVENESS_CHECK_MILLIS;
  }

  private enum Liveness {
    SOMEBODY_IN_CALL,
    EVERYBODY_LEFT,
    UNKNOWN
  }

  private void checkLiveness(
      MatrixCallConversation conversation, MatrixCallObservation row, List<String> sourceMembers) {
    long now = System.currentTimeMillis();
    long[] lastPresence = {row.getRegisteredAt()};
    // What this verdict is based on: a renewal or another check that commits while Matrix is read
    // supersedes it (two readers probing at once must not let the older answer win).
    long seenAt = row.getLastSeenAt();
    Long checkedAt = row.getLivenessCheckedAt();
    Liveness liveness;
    try {
      liveness = probe(row, sourceMembers, now, lastPresence);
    } catch (MatrixSynapseService.CallLookupUnavailableException unavailable) {
      liveness = Liveness.UNKNOWN;
    }
    if (liveness == Liveness.EVERYBODY_LEFT && now - row.getRegisteredAt() < START_GRACE_MILLIS) {
      liveness = Liveness.UNKNOWN;
    }
    final Liveness verdict = liveness;
    MatrixCallObservation ended =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  var locked =
                      observations
                          .findForUpdateBySourceRoomIdAndCallId(
                              row.getSourceRoomId(), row.getCallId())
                          .orElse(null);
                  if (locked == null
                      || locked.getEndedAt() != null
                      || !Objects.equals(locked.getMediaRoomId(), row.getMediaRoomId())
                      || locked.getLastSeenAt() != seenAt
                      || !Objects.equals(locked.getLivenessCheckedAt(), checkedAt)) {
                    return null;
                  }
                  long at = System.currentTimeMillis();
                  locked.markLivenessChecked(at);
                  MatrixCallObservation result = null;
                  if (verdict == Liveness.SOMEBODY_IN_CALL) {
                    locked.keepAlive(at, at + LEASE_MILLIS);
                  } else if (verdict == Liveness.EVERYBODY_LEFT) {
                    locked.finishAt(
                        Math.max(locked.getRegisteredAt(), Math.min(at, lastPresence[0])));
                    result = locked;
                  }
                  observations.save(locked);
                  return result;
                });
    if (ended != null) notifyEnded(conversation, sourceMembers, ended);
  }

  /** Reads the media room as one of its joined members; the creator first. */
  private Liveness probe(
      MatrixCallObservation row, List<String> sourceMembers, long now, long[] lastPresence) {
    var mediaMembers = matrix.getCallRoomMembers(row.getMediaRoomId()).orElse(null);
    if (mediaMembers == null) return Liveness.UNKNOWN;
    var readers = new ArrayList<String>();
    if (mediaMembers.contains(row.getCreatorMatrixId())) readers.add(row.getCreatorMatrixId());
    mediaMembers.stream()
        .filter(sourceMembers::contains)
        .filter(member -> !readers.contains(member))
        .forEach(readers::add);
    if (readers.isEmpty()) return Liveness.UNKNOWN;
    var state = matrix.getCallRoomState(row.getMediaRoomId(), readers.get(0)).orElse(null);
    if (state == null) return Liveness.UNKNOWN;
    boolean understood = false;
    boolean inconclusive = false;
    for (var event : state) {
      var membership = MatrixRtcMembershipEvent.parse(event).orElse(null);
      if (membership == null) {
        // A participant's call membership this server cannot read is no proof they left.
        if (MatrixRtcMembershipEvent.TYPE.equals(event.get("type"))
            && event.get("sender") instanceof String sender
            && sourceMembers.contains(sender)) {
          inconclusive = true;
        }
        continue;
      }
      if (!sourceMembers.contains(membership.sender())) continue;
      understood = true;
      if (!membership.departed() && membership.expiresAt() > now) {
        return Liveness.SOMEBODY_IN_CALL;
      }
      lastPresence[0] =
          Math.max(
              lastPresence[0],
              membership.departed() ? membership.eventTimestamp() : membership.expiresAt());
    }
    return understood && !inconclusive ? Liveness.EVERYBODY_LEFT : Liveness.UNKNOWN;
  }

  private static MatrixCallStateService.CallState toState(MatrixCallObservation row) {
    long now = System.currentTimeMillis();
    boolean live = row.isLive(now);
    Long ended = live ? null : (row.getEndedAt() != null ? row.getEndedAt() : row.getExpiresAt());
    long duration = Math.max(0, (live ? now : ended) - row.getRegisteredAt()) / 1000;
    return new MatrixCallStateService.CallState(
        row.getSourceRoomId(),
        row.getCallId(),
        row.getMediaRoomId(),
        row.isVideo() ? "video" : "audio",
        live ? "running" : "ended",
        row.getRegisteredAt(),
        row.getRegisteredAt(),
        ended,
        duration,
        List.of());
  }

  private static long nonNull(Long value) {
    return value == null ? 0L : value;
  }

  private Optional<String> matrixIdOf(AuthenticatedUser actor, Long tenantId) {
    if (actor.getUserId() == null) return Optional.empty();
    Optional<String> id =
        actor.isConsultant()
            ? consultants
                .findByIdAndDeleteDateIsNull(actor.getUserId())
                .filter(u -> Objects.equals(u.getTenantId(), tenantId))
                .map(u -> u.getMatrixUserId())
            : users
                .findByUserIdAndDeleteDateIsNull(actor.getUserId())
                .filter(u -> Objects.equals(u.getTenantId(), tenantId))
                .map(u -> u.getMatrixUserId());
    return id.filter(value -> !value.isBlank());
  }

  private Optional<Identity> identity(String matrixId, Long tenantId) {
    return users
        .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
        .filter(u -> Objects.equals(u.getTenantId(), tenantId) && u.getUserId() != null)
        .map(u -> new Identity(u.getUserId(), false))
        .or(
            () ->
                consultants
                    .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
                    .filter(c -> Objects.equals(c.getTenantId(), tenantId) && c.getId() != null)
                    .map(c -> new Identity(c.getId(), true)));
  }

  private static boolean blank(String value, int max) {
    return value == null || value.isBlank() || value.length() > max;
  }

  private record Identity(String id, boolean consultant) {}
}
