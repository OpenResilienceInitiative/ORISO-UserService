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
 * Timeline only. It must never be read by guest admission, share links, knocks or any other access
 * decision; those read {@code MatrixCallBinding} exclusively.
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
    if (bindings.findBySourceRoomIdAndCallId(sourceRoomId, callId).isPresent()) {
      return Outcome.NOT_FOUND;
    }
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
    Ending ending;
    try {
      ending = writeEnd(conversation, caller, callId, members);
    } catch (DataIntegrityViolationException | PessimisticLockingFailureException raced) {
      // The registration landed while the end was written: end that row instead.
      ending = writeEnd(conversation, caller, callId, members);
    }
    if (ending == null) return Outcome.NOT_FOUND;
    if (ending.endedNow()) notifyEnded(conversation, members, ending.row());
    return Outcome.OK;
  }

  private record Ending(MatrixCallObservation row, boolean endedNow) {}

  private Ending writeEnd(
      MatrixCallConversation conversation, String caller, String callId, List<String> members) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              var row =
                  observations
                      .findForUpdateBySourceRoomIdAndCallId(conversation.getMatrixRoomId(), callId)
                      .orElse(null);
              long now = System.currentTimeMillis();
              if (row == null) {
                if (!members.contains(caller)) return null;
                recordEnd(conversation, callId, caller, now);
                return new Ending(null, false);
              }
              if (!caller.equals(row.getCreatorMatrixId())
                  || !Objects.equals(row.getTenantId(), conversation.getTenantId())) {
                return null;
              }
              recordEnd(conversation, callId, caller, now);
              if (row.getEndedAt() != null) return new Ending(row, false);
              row.finish(now);
              observations.save(row);
              return new Ending(row, true);
            });
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
