package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Resolves invite recipients from live source-room membership, never from event-supplied IDs. */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatrixCallInviteNotificationService {
  private final MatrixSynapseService matrix;
  private final MatrixCallConversationResolver conversations;
  private final UserRepository users;
  private final ConsultantRepository consultants;
  private final EventNotificationService notifications;
  private final MatrixCallBindingService callBindings;
  private final MatrixCallLifecycleService lifecycle;
  private final MatrixCallGuestService callGuests;
  private final SessionCallAuthority authority;
  private final DedicatedCallRoom callRoom;

  /** How often a live room whose pass failed may hold its sync batch for a replay. */
  public static final int LIVE_ROOM_RETRIES = 3;

  public boolean handleMediaRoom(String room, Map<String, Object> roomData) {
    return lifecycle.handleRoom(room, roomData);
  }

  /**
   * Live sync path. FE#2209 round 10 (F1): each room is handled on its own, so a room whose pass
   * throws no longer keeps the rooms after it in the batch from being handled. The failed room's
   * membership events exist only in this batch, so it is retried by replaying the batch: once every
   * room was handled, the sync is told not to acknowledge it (the established behaviour for a
   * transient Matrix lookup failure). That replay is bounded per room: once a room failed more than
   * {@link #LIVE_ROOM_RETRIES} times in a row, it is marked unobserved (its memberships then count
   * as absent, so its guests lose the gate and their seats) and no longer holds the batch; the
   * sweeps and the observation restore take it from there. If even that marker cannot be written,
   * the batch keeps being replayed. A success resets the count.
   *
   * <p>Round 11 (F3): the replay is also bounded per batch. A replay leaves the sync cursor where
   * it was, so consecutive replays are replays of the same batch. Once it was replayed {@link
   * #LIVE_ROOM_RETRIES} times, every room that still fails is marked unobserved at once, so more
   * failing rooms than the per-room count tracks cannot keep the batch unacknowledged forever.
   * Round 11 (F1): the marker also caps the room's memberships (see {@link
   * MatrixCallLifecycleService#loseBatch}).
   */
  @SuppressWarnings("unchecked")
  public void handleMediaRooms(Map<String, Object> joinedRooms) {
    var known = callBindings.knownMediaRooms(joinedRooms.keySet());
    boolean exhausted = replaysOfThisBatch >= LIVE_ROOM_RETRIES;
    boolean replay = false;
    for (var entry : joinedRooms.entrySet()) {
      String room = entry.getKey();
      if (!known.contains(room)) continue;
      var roomData = (Map<String, Object>) entry.getValue();
      if (passed("Call room event pass", () -> lifecycle.handleRoom(room, roomData))) {
        liveRoomFailures.remove(room);
        continue;
      }
      int failures = liveRoomFailures.merge(room, 1, Integer::sum);
      if (failures <= LIVE_ROOM_RETRIES && !exhausted) {
        replay = true;
      } else if (passed("Call room observation reset", () -> lifecycle.loseBatch(room))) {
        // Round 10 security scan: the dropped batch may hold a departure. The durable marker
        // (media observation lost) makes the room's memberships count as absent until the room
        // is observed again, so a stale counsellor membership keeps nothing open (fail closed).
        log.warn("Call room event pass keeps failing for one room; marked unobserved");
      } else {
        // Without the marker the batch is never acknowledged.
        replay = true;
      }
    }
    if (replay) {
      replaysOfThisBatch++;
      throw new CallRoomBatchReplay();
    }
    replaysOfThisBatch = 0;
  }

  /** How often the current batch was replayed in a row; written by the sync worker only. */
  private int replaysOfThisBatch;

  /** Consecutive live pass failures per room; bounded, written by the sync worker only. */
  private final Map<String, Integer> liveRoomFailures =
      java.util.Collections.synchronizedMap(
          new java.util.LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
              return size() > MAX_TRACKED_FAILING_ROOMS;
            }
          });

  static final int MAX_TRACKED_FAILING_ROOMS = 256;

  /** Tells the sync not to acknowledge the batch, so a failed live room is retried. No ids. */
  public static final class CallRoomBatchReplay extends RuntimeException {
    CallRoomBatchReplay() {
      super("A call room pass failed; the sync batch is replayed", null, false, false);
    }
  }

  /**
   * A leave arrives once. Recording it is the marker that makes the room's memberships count as
   * absent, so a leave that cannot be recorded holds the batch for a replay (round 10 security
   * scan); it only writes the binding row, so it fails only while the database does.
   */
  public void handleMediaRoomLeft(String room) {
    if (!passed("Call room leave", () -> lifecycle.loseObservation(room)))
      throw new CallRoomBatchReplay();
  }

  public void reconcileMediaRooms() {
    // Round 10 (F1): every loop handles each room through passed(), so a room whose pass always
    // throws (e.g. a lookup that cannot decide) is logged and skipped, and the sync still
    // acknowledges its batch. Retry bound: the rule-lapse sweep stamps each candidate before its
    // pass and takes the least recently swept first, so with N candidates a room that keeps
    // failing is retried within about ceil(N/100) sweep cycles. The other loops keep their order
    // and retry such a room every cycle; they reach the rooms behind it as long as fewer than a
    // page (100) of rooms keep failing.
    // Each room is locked in its own transaction. Only call after a successful sync has
    // applied current membership renewals, never on a transport failure.
    for (String room : callBindings.unobservedMediaRooms(System.currentTimeMillis())) {
      passed("Call room observation restore", () -> lifecycle.restoreObservation(room));
    }
    for (String room : callBindings.expiredMediaRooms(System.currentTimeMillis())) {
      passed("Call room expiry pass", () -> lifecycle.handleRoom(room, Map.of()));
    }
    // FE#2209 review: a session call that was never observed would otherwise never end.
    long now = System.currentTimeMillis();
    for (String room :
        callBindings.neverObservedSessionCalls(
            now - MatrixCallLifecycleService.NEVER_OBSERVED_MARGIN_MILLIS)) {
      passed("Never observed call end", () -> lifecycle.finishNeverObserved(room, now));
    }
    // US#1477: an ended call can still hold a guest whose removal failed; retry without an event.
    for (String room : callGuests.endedRoomsWithOutstandingGuests()) {
      passed("Ended call guest removal", () -> lifecycle.handleRoom(room, Map.of()));
    }
    // Security review 5901360577: a running session call whose rule lapsed without any call-room
    // event (reassignment, suspension, a counsellor whose membership simply ran out). Round 8:
    // only calls whose rule may have lapsed, least recently swept first, each stamped as swept.
    // Round 9 (F2): the stamp commits before the pass, so one room that always fails neither
    // sorts first forever nor stops the others.
    long sweptAt = System.currentTimeMillis();
    for (String room : callGuests.runningSessionRoomsWithOutstandingGuests(sweptAt)) {
      passed(
          "Running session call sweep",
          () -> {
            if (lifecycle.recordSweep(room, sweptAt))
              lifecycle.sweepRunningSessionCall(room, sweptAt);
          });
    }
  }

  /**
   * Runs one room's pass. A failure is logged with its exception class only (no room or user ids)
   * and reported, never thrown, so it cannot stop the other rooms or the sync acknowledgement.
   */
  private static boolean passed(String pass, Runnable work) {
    try {
      work.run();
      return true;
    } catch (RuntimeException failure) {
      log.warn("{} failed for one call room ({})", pass, failure.getClass().getSimpleName());
      return false;
    }
  }

  @org.springframework.transaction.annotation.Transactional
  public boolean handle(String sourceRoom, Map<String, Object> event) {
    if (sourceRoom == null
        || sourceRoom.isBlank()
        || sourceRoom.length() > 255
        || !(event.get("content") instanceof Map<?, ?> content)
        || !(event.get("sender") instanceof String sender)
        || sender.isBlank()
        || sender.length() > 255
        || !(content.get("call_id") instanceof String callId)
        || callId.isBlank()
        || callId.length() > 191
        || !(content.get("call_room_id") instanceof String mediaRoom)
        || mediaRoom.isBlank()
        || mediaRoom.length() > 255) {
      return false;
    }
    Long timestamp = integer(event.get("origin_server_ts"));
    Long lifetime = integer(content.get("lifetime"));
    long now = System.currentTimeMillis();
    if (timestamp == null
        || lifetime == null
        || timestamp < 0
        || lifetime <= 0
        || lifetime > Long.MAX_VALUE - timestamp
        || timestamp > now) return false;
    boolean expired = now - timestamp >= lifetime;
    var session = conversations.resolveForWriting(sourceRoom).orElse(null);
    if (session == null) return false;
    // Security review 5901360577 (point 2): the same evidence bind() requires. Only a dedicated
    // call
    // room: never the conversation or another ORISO conversation room.
    if (callRoom.isConversationRoom(sourceRoom, mediaRoom)) return false;
    // FE#2209 round 3: an already expired invite of anybody but the assigned counsellor never
    // creates a 1:1 session call's binding. Its only effect would be a lapsed row on the call id
    // (an asker could send lifetime=1 for the counsellor's call). The counsellor's own late sync
    // and group chats keep the recovery below.
    if (expired
        && session.getSessionId() != null
        && !authority.isAssignedCounsellor(session.getSessionId(), session.getTenantId(), sender))
      return false;
    var members = matrix.getCallRoomMembers(sourceRoom).orElse(null);
    if (members == null || !members.contains(sender)) return false;
    var actor = identity(sender, session.getTenantId()).orElse(null);
    if (actor == null || !lifecycle.isActiveRecipient(actor.id())) return false;
    // The room names this call of this conversation, and the sender created it and wrote that
    // binding (shared with bind()).
    if (!callRoom.isBoundBy(sourceRoom, callId, mediaRoom, sender)) return false;
    if (!conversations.lockCurrentForWriting(session)) return false;
    var proposed =
        de.caritas.cob.userservice.api.model.MatrixCallBinding.builder()
            .sourceRoomId(sourceRoom)
            .callId(callId)
            .mediaRoomId(mediaRoom)
            .callerMatrixId(sender)
            .sessionId(session.getSessionId())
            .chatId(session.getChatId())
            .tenantId(session.getTenantId())
            .invitedAt(timestamp)
            .inviteExpiresAt(timestamp + lifetime)
            .video(Boolean.TRUE.equals(content.get("is_video")))
            .invitedMatrixIds(
                expired
                    ? java.util.Set.of()
                    : members.stream()
                        .filter(member -> !member.equals(sender))
                        .filter(member -> identity(member, session.getTenantId()).isPresent())
                        .collect(java.util.stream.Collectors.toSet()))
            .build();
    boolean registered =
        session.getSessionId() == null
            ? callBindings.register(proposed)
            : callBindings.registerWhileSourceLocked(proposed);
    if (!registered) return false;
    // Only a validated, persistently bound room may grant the listener membership.
    // Observer availability must not suppress a valid invitation. Failed observation is retried
    // from the persisted binding; expiry is gated on actually receiving media-room state.
    observeAfterCommit(mediaRoom, sender);
    // A late sync can discover a call that is still running. Recover its identity and media
    // observation without ringing again or inventing a historical invitee list from today's
    // members.
    if (expired) return true;
    callBindings.invitedMembers(sourceRoom, callId).stream()
        .filter(members::contains)
        .distinct()
        .filter(member -> !member.equals(sender))
        .map(member -> identity(member, session.getTenantId()))
        .flatMap(Optional::stream)
        .distinct()
        .filter(recipient -> !recipient.id().equals(actor.id()))
        .filter(recipient -> lifecycle.isActiveRecipient(recipient.id()))
        .forEach(
            recipient ->
                notifications.createCallInvitationNotification(
                    session,
                    recipient.id(),
                    recipient.consultant(),
                    actor.id(),
                    callId,
                    mediaRoom,
                    Boolean.TRUE.equals(content.get("is_video"))));
    return true;
  }

  private void observeAfterCommit(String mediaRoom, String sender) {
    Runnable observe =
        () -> {
          try {
            matrix.ensureAdminInRoom(mediaRoom, sender);
          } catch (RuntimeException unavailable) {
            // The committed binding is the recovery record; observer availability cannot undo it.
            log.warn("Call observer join unavailable ({})", unavailable.getClass().getSimpleName());
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              observe.run();
            }
          });
    } else {
      observe.run();
    }
  }

  private Optional<Identity> identity(String matrixId, Long tenantId) {
    var user =
        users
            .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
            .filter(value -> Objects.equals(value.getTenantId(), tenantId))
            .filter(value -> value.getUserId() != null)
            .map(value -> new Identity(value.getUserId(), false));
    return user.or(
        () ->
            consultants
                .findByMatrixUserIdAndDeleteDateIsNull(matrixId)
                .filter(value -> Objects.equals(value.getTenantId(), tenantId))
                .filter(value -> value.getId() != null)
                .map(value -> new Identity(value.getId(), true)));
  }

  private record Identity(String id, boolean consultant) {}

  private static Long integer(Object value) {
    if (value instanceof Integer number) return number.longValue();
    if (value instanceof Long number) return number;
    return null;
  }
}
