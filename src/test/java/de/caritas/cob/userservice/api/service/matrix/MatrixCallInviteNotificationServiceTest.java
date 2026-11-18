package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.CallRoomOrigin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Security review 5901360577 (Shirloin), point 2: the plaintext invite creates a call binding just
 * as {@link MatrixSessionCallBindingService#bind} does, so it needs the same evidence that the call
 * room is a dedicated call room of this call: not one of ORISO's conversation rooms, and created
 * and bound by the inviting member.
 */
class MatrixCallInviteNotificationServiceTest {
  private static final String SOURCE = "!group-source:example";
  private static final String MEDIA = "!call-media:example";
  private static final String CALLER = "@counsellor:example";
  private static final String MEMBER = "@asker:example";
  private static final long TENANT = 7L;

  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final MatrixCallConversationResolver conversations =
      mock(MatrixCallConversationResolver.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final MatrixCallBindingService callBindings = mock(MatrixCallBindingService.class);
  private final MatrixCallLifecycleService lifecycle = mock(MatrixCallLifecycleService.class);
  private final OrisoRoomRegistry rooms = mock(OrisoRoomRegistry.class);
  private final MatrixCallGuestService callGuests = mock(MatrixCallGuestService.class);
  private MatrixCallInviteNotificationService service;

  @BeforeEach
  void setUp() {
    service =
        new MatrixCallInviteNotificationService(
            matrix,
            conversations,
            users,
            consultants,
            mock(EventNotificationService.class),
            callBindings,
            lifecycle,
            callGuests,
            mock(SessionCallAuthority.class),
            new DedicatedCallRoom(rooms, matrix));
    // A group chat: any member's invite carries the call (the 1:1 authority rule does not apply).
    when(conversations.resolveForWriting(SOURCE))
        .thenReturn(Optional.of(new MatrixCallConversation(SOURCE, TENANT, null, 3L)));
    when(conversations.lockCurrentForWriting(any())).thenReturn(true);
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(CALLER, MEMBER)));
    var consultant = new Consultant();
    consultant.setId("counsellor-id");
    consultant.setTenantId(TENANT);
    consultant.setMatrixUserId(CALLER);
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(CALLER))
        .thenReturn(Optional.of(consultant));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(anyString())).thenReturn(Optional.empty());
    var asker = new User();
    asker.setUserId("asker-id");
    asker.setTenantId(TENANT);
    asker.setMatrixUserId(MEMBER);
    when(users.findByMatrixUserIdAndDeleteDateIsNull(MEMBER)).thenReturn(Optional.of(asker));
    when(lifecycle.isActiveRecipient(anyString())).thenReturn(true);
    when(matrix.getCallRoomBinding(anyString(), anyString()))
        .thenReturn(Optional.of(Map.of("call_id", "call", "source_room_id", SOURCE)));
    when(matrix.getCallRoomOrigin(MEDIA, CALLER))
        .thenReturn(Optional.of(new CallRoomOrigin(CALLER, CALLER)));
    when(callBindings.register(any())).thenReturn(true);
  }

  @Test
  void aDedicatedCallRoomTheCallerCreatedAndBoundIsRegistered() {
    assertThat(service.handle(SOURCE, invite(MEDIA))).isTrue();

    verify(callBindings).register(any());
  }

  @Test
  void anOrisoConversationRoomIsNeverRegisteredAsTheCallRoom() {
    // E.g. a 1:1 session room the member is also in: the observer would join it as admin and an
    // admitted guest would be invited into that conversation.
    when(rooms.isKnownConversationRoom(MEDIA)).thenReturn(true);

    assertThat(service.handle(SOURCE, invite(MEDIA))).isFalse();

    verify(callBindings, never()).register(any());
    verify(matrix, never()).ensureAdminInRoom(anyString(), anyString());
  }

  @Test
  void theConversationItselfIsNeverRegisteredAsTheCallRoom() {
    assertThat(service.handle(SOURCE, invite(SOURCE))).isFalse();

    verify(callBindings, never()).register(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"creator", "binding sender", "both", "unreadable"})
  void aCallRoomTheCallerDidNotCreateAndBindIsNeverRegistered(String foreign) {
    when(matrix.getCallRoomOrigin(MEDIA, CALLER))
        .thenReturn(
            switch (foreign) {
              case "creator" -> Optional.of(new CallRoomOrigin(MEMBER, CALLER));
              case "binding sender" -> Optional.of(new CallRoomOrigin(CALLER, MEMBER));
              case "both" -> Optional.of(new CallRoomOrigin(MEMBER, MEMBER));
              default -> Optional.empty();
            });

    assertThat(service.handle(SOURCE, invite(MEDIA))).isFalse();

    verify(callBindings, never()).register(any());
  }

  // Round 10 (F1a): one room whose pass throws must not stop the others. The listener writes
  // next_batch only after handleMediaRooms and reconcileMediaRooms return.

  private static final String POISON = "!poison:example";
  private static final String HEALTHY = "!healthy:example";

  @Test
  void aRoomWhosePassThrowsDoesNotStopAnyReconcileLoop() {
    var both = List.of(POISON, HEALTHY);
    var failure = new org.springframework.dao.IncorrectResultSizeDataAccessException(1, 2);
    when(callBindings.unobservedMediaRooms(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(both);
    when(callBindings.expiredMediaRooms(org.mockito.ArgumentMatchers.anyLong())).thenReturn(both);
    when(callBindings.neverObservedSessionCalls(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(both);
    when(callGuests.endedRoomsWithOutstandingGuests()).thenReturn(both);
    org.mockito.Mockito.doThrow(failure).when(lifecycle).restoreObservation(POISON);
    when(lifecycle.handleRoom(POISON, Map.of())).thenThrow(failure);
    when(lifecycle.finishNeverObserved(
            org.mockito.ArgumentMatchers.eq(POISON), org.mockito.ArgumentMatchers.anyLong()))
        .thenThrow(failure);

    service.reconcileMediaRooms();

    verify(lifecycle).restoreObservation(HEALTHY);
    verify(lifecycle, org.mockito.Mockito.times(2)).handleRoom(HEALTHY, Map.of());
    verify(lifecycle)
        .finishNeverObserved(
            org.mockito.ArgumentMatchers.eq(HEALTHY), org.mockito.ArgumentMatchers.anyLong());
    verify(callGuests)
        .runningSessionRoomsWithOutstandingGuests(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void aLiveRoomWhosePassThrowsStopsNoOtherRoomAndHoldsTheBatchForAReplay() {
    Map<String, Object> poisonData = Map.of("timeline", Map.of("events", List.of()));
    Map<String, Object> healthyData = Map.of("state", Map.of("events", List.of()));
    var batch = new java.util.LinkedHashMap<String, Object>();
    batch.put(POISON, poisonData);
    batch.put(HEALTHY, healthyData);
    when(callBindings.knownMediaRooms(any())).thenReturn(java.util.Set.of(POISON, HEALTHY));
    when(lifecycle.handleRoom(POISON, poisonData))
        .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock wait"))
        .thenReturn(true);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.handleMediaRooms(batch))
        .as("the failed room's events exist only in this batch: replay it")
        .isInstanceOf(MatrixCallInviteNotificationService.CallRoomBatchReplay.class);
    verify(lifecycle).handleRoom(HEALTHY, healthyData);

    org.assertj.core.api.Assertions.assertThatCode(() -> service.handleMediaRooms(batch))
        .as("the replay succeeds and the batch can be acknowledged")
        .doesNotThrowAnyException();
  }

  @Test
  void aLiveRoomThatKeepsFailingHoldsTheBatchOnlyABoundedNumberOfTimes() {
    Map<String, Object> poisonData = Map.of("timeline", Map.of("events", List.of()));
    Map<String, Object> healthyData = Map.of("state", Map.of("events", List.of()));
    var batch = new java.util.LinkedHashMap<String, Object>();
    batch.put(POISON, poisonData);
    batch.put(HEALTHY, healthyData);
    when(callBindings.knownMediaRooms(any())).thenReturn(java.util.Set.of(POISON, HEALTHY));
    when(lifecycle.handleRoom(POISON, poisonData))
        .thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1, 2));

    for (int attempt = 0;
        attempt < MatrixCallInviteNotificationService.LIVE_ROOM_RETRIES;
        attempt++)
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.handleMediaRooms(batch))
          .isInstanceOf(MatrixCallInviteNotificationService.CallRoomBatchReplay.class);

    org.assertj.core.api.Assertions.assertThatCode(() -> service.handleMediaRooms(batch))
        .as("then the room is left to the sweeps and the batch is acknowledged")
        .doesNotThrowAnyException();
    verify(
            lifecycle,
            org.mockito.Mockito.times(MatrixCallInviteNotificationService.LIVE_ROOM_RETRIES + 1))
        .handleRoom(HEALTHY, healthyData);
  }

  @Test
  void aLiveRoomLeftToTheSweepsIsFirstMarkedUnobserved() {
    // Round 10 security scan: the dropped batch may hold a departure; the durable marker makes the
    // room's stale memberships count as absent until the room is observed again.
    Map<String, Object> poisonData = Map.of("timeline", Map.of("events", List.of()));
    when(callBindings.knownMediaRooms(any())).thenReturn(java.util.Set.of(POISON));
    when(lifecycle.handleRoom(POISON, poisonData))
        .thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1, 2));
    for (int attempt = 0;
        attempt < MatrixCallInviteNotificationService.LIVE_ROOM_RETRIES;
        attempt++)
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> service.handleMediaRooms(Map.of(POISON, poisonData)))
          .isInstanceOf(MatrixCallInviteNotificationService.CallRoomBatchReplay.class);
    verify(lifecycle, never()).loseBatch(POISON);

    service.handleMediaRooms(Map.of(POISON, poisonData));

    verify(lifecycle).loseBatch(POISON);
  }

  @Test
  void aSuccessfulPassResetsTheRoomsFailureCount() {
    // Round 11 (F4): only consecutive failures count. After a success, a new failure holds the
    // batch again instead of marking the room unobserved at once.
    Map<String, Object> roomData = Map.of("timeline", Map.of("events", List.of()));
    when(callBindings.knownMediaRooms(any())).thenReturn(java.util.Set.of(POISON));
    var pass = new java.util.concurrent.atomic.AtomicInteger();
    when(lifecycle.handleRoom(POISON, roomData))
        .thenAnswer(
            ignored -> {
              if (pass.incrementAndGet()
                  == MatrixCallInviteNotificationService.LIVE_ROOM_RETRIES + 1) return true;
              throw new org.springframework.dao.CannotAcquireLockException("lock wait");
            });
    for (int attempt = 0;
        attempt < MatrixCallInviteNotificationService.LIVE_ROOM_RETRIES;
        attempt++)
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> service.handleMediaRooms(Map.of(POISON, roomData)))
          .isInstanceOf(MatrixCallInviteNotificationService.CallRoomBatchReplay.class);
    service.handleMediaRooms(Map.of(POISON, roomData));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.handleMediaRooms(Map.of(POISON, roomData)))
        .as("the first failure after a success holds the batch again")
        .isInstanceOf(MatrixCallInviteNotificationService.CallRoomBatchReplay.class);
    verify(lifecycle, never()).loseBatch(POISON);
  }

  @Test
  void moreFailingRoomsThanTrackedAreMarkedOnceTheBatchWasReplayedOftenEnough() {
    // Round 11 (F3): with more failing rooms than the per-room count tracks, each room's count is
    // evicted before it reaches the bound. The per-batch bound still acknowledges the batch, with
    // every failing room marked unobserved.
    var batch = new java.util.LinkedHashMap<String, Object>();
    for (int index = 0;
        index <= MatrixCallInviteNotificationService.MAX_TRACKED_FAILING_ROOMS;
        index++) batch.put("!failing-" + index + ":example", Map.of("timeline", Map.of()));
    assertThat(batch).hasSize(257);
    when(callBindings.knownMediaRooms(any())).thenReturn(batch.keySet());
    when(lifecycle.handleRoom(anyString(), any()))
        .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock wait"));
    for (int attempt = 0;
        attempt < MatrixCallInviteNotificationService.LIVE_ROOM_RETRIES;
        attempt++)
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.handleMediaRooms(batch))
          .isInstanceOf(MatrixCallInviteNotificationService.CallRoomBatchReplay.class);

    org.assertj.core.api.Assertions.assertThatCode(() -> service.handleMediaRooms(batch))
        .as("acknowledged after the bound")
        .doesNotThrowAnyException();
    for (String room : batch.keySet()) verify(lifecycle).loseBatch(room);
  }

  @Test
  void aLiveRoomWhoseMarkerCannotBeWrittenKeepsHoldingTheBatch() {
    Map<String, Object> poisonData = Map.of("timeline", Map.of("events", List.of()));
    when(callBindings.knownMediaRooms(any())).thenReturn(java.util.Set.of(POISON));
    when(lifecycle.handleRoom(POISON, poisonData))
        .thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1, 2));
    org.mockito.Mockito.doThrow(new org.springframework.dao.CannotAcquireLockException("down"))
        .when(lifecycle)
        .loseBatch(POISON);

    for (int attempt = 0;
        attempt < MatrixCallInviteNotificationService.LIVE_ROOM_RETRIES + 3;
        attempt++)
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> service.handleMediaRooms(Map.of(POISON, poisonData)))
          .as("never acknowledged without the marker")
          .isInstanceOf(MatrixCallInviteNotificationService.CallRoomBatchReplay.class);
  }

  @Test
  void aLeaveThatCannotBeRecordedHoldsTheBatch() {
    // The leave arrives once; dropping it would leave the room observed with stale memberships.
    org.mockito.Mockito.doThrow(new org.springframework.dao.CannotAcquireLockException("down"))
        .when(lifecycle)
        .loseObservation(POISON);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.handleMediaRoomLeft(POISON))
        .isInstanceOf(MatrixCallInviteNotificationService.CallRoomBatchReplay.class);
  }

  private static Map<String, Object> invite(String callRoom) {
    return Map.of(
        "type",
        "org.oriso.call.invite",
        "sender",
        CALLER,
        "event_id",
        "$invite",
        "origin_server_ts",
        System.currentTimeMillis(),
        "content",
        Map.of("call_id", "call", "call_room_id", callRoom, "lifetime", 60000, "is_video", true));
  }

  @Test
  void twoCallRoomPassesNeverRunAtOnceSoTheReplayCountStaysThatOfOneBatch() throws Exception {
    // #1656: the replay count and the per-room failure counts are plain fields of one sync worker.
    // A second caller must wait for the running pass instead of sharing them.
    Map<String, Object> roomData = Map.of("timeline", Map.of("events", List.of()));
    when(callBindings.knownMediaRooms(any())).thenReturn(java.util.Set.of(HEALTHY));
    var firstInside = new java.util.concurrent.CountDownLatch(1);
    var releaseFirst = new java.util.concurrent.CountDownLatch(1);
    var inside = new java.util.concurrent.atomic.AtomicInteger();
    var overlapped = new java.util.concurrent.atomic.AtomicBoolean();
    when(lifecycle.handleRoom(HEALTHY, roomData))
        .thenAnswer(
            ignored -> {
              if (inside.incrementAndGet() > 1) overlapped.set(true);
              firstInside.countDown();
              try {
                releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS);
              } finally {
                inside.decrementAndGet();
              }
              return true;
            });
    var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
    Runnable pass =
        () -> {
          try {
            service.handleMediaRooms(Map.of(HEALTHY, roomData));
          } catch (Throwable failure) {
            failures.add(failure);
          }
        };
    var first = new Thread(pass, "first-pass");
    var second = new Thread(pass, "second-pass");
    try {
      first.start();
      assertThat(firstInside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      second.start();
      // CodeRabbit 4585785802: no sleep. Wait, bounded, until the second pass either overlaps the
      // first or is blocked on a monitor the first pass holds (the pass lock).
      var threads = java.lang.management.ManagementFactory.getThreadMXBean();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      boolean blockedByFirst = false;
      while (!overlapped.get() && !blockedByFirst && System.nanoTime() < deadline) {
        var info = threads.getThreadInfo(second.getId());
        blockedByFirst =
            info != null
                && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockOwnerId() == first.getId();
        if (!blockedByFirst) Thread.onSpinWait();
      }
      assertThat(overlapped).as("the second pass waits for the first").isFalse();
      assertThat(blockedByFirst).as("the second pass blocks on the first pass's lock").isTrue();
      releaseFirst.countDown();
      first.join(5_000);
      second.join(5_000);
      assertThat(failures).isEmpty();
      assertThat(overlapped).isFalse();
      verify(lifecycle, org.mockito.Mockito.times(2)).handleRoom(HEALTHY, roomData);
    } finally {
      releaseFirst.countDown();
      first.join(5_000);
      second.join(5_000);
    }
  }
}
