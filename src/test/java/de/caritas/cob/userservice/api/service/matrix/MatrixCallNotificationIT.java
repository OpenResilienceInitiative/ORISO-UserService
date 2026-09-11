package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.mobilepushmessage.MobilePushNotificationService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationDeduplicationWriter;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.ConsultantMessageStatService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Exercises the actual sync input and committed notification storage, not private handlers. */
@DataJpaTest
@TestPropertySource(
    properties = {"spring.profiles.active=testing", "matrix.calls.observation-retry-ms=10"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  EventNotificationService.class,
  EventNotificationDeduplicationWriter.class,
  MatrixCallBindingService.class,
  MatrixCallLifecycleService.class,
  MatrixCallConversationResolver.class,
  MatrixCallBindingWriter.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatrixCallNotificationIT {
  @Autowired private EventNotificationRepository notifications;
  @Autowired private EventNotificationService notificationService;
  @Autowired private MatrixCallBindingService callBindings;
  @Autowired private MatrixCallLifecycleService lifecycle;
  @Autowired private MatrixCallConversationResolver conversations;
  @MockitoBean private MatrixSynapseService matrix;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository bindingRepository;

  @MockitoBean private SessionRepository sessions;
  @MockitoBean private de.caritas.cob.userservice.api.port.out.ChatRepository chats;
  @MockitoBean private UserRepository users;
  @MockitoBean private ConsultantRepository consultants;
  @MockitoBean private IdentityTombstoneService tombstones;

  @Test
  void currentInviteProducesAPersistedNotificationForTheOtherConversationMember() {
    assertInviteNotifications(System.currentTimeMillis(), 1, false);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void failedMediaSubscriptionIsRecoveredAfterRestartWithoutReplayingTheInvite(
      boolean losePreviouslyObservedRoom) {
    String source = "!recovery-source:example";
    String media = "!recovery-media:example";
    String caller = "@recovery-caller:example";
    String receiver = "@recovery-receiver:example";
    var owner = mock(User.class);
    when(owner.getUserId()).thenReturn("recovery-caller");
    when(owner.getTenantId()).thenReturn(7L);
    var recipient = mock(User.class);
    when(recipient.getUserId()).thenReturn("recovery-receiver");
    when(recipient.getTenantId()).thenReturn(7L);
    var session = mock(Session.class);
    when(session.getId()).thenReturn(45L);
    when(session.getTenantId()).thenReturn(7L);
    when(session.getMatrixRoomId()).thenReturn(source);
    when(sessions.findByMatrixRoomId(source)).thenReturn(Optional.of(session));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(caller)).thenReturn(Optional.of(owner));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(receiver)).thenReturn(Optional.of(recipient));
    when(matrix.getAdminToken()).thenReturn("synthetic-token");
    when(matrix.getMatrixApiUrl()).thenReturn("https://matrix.example");
    when(matrix.getRoomMembers(source)).thenReturn(Optional.of(List.of(caller, receiver)));
    when(matrix.getCallRoomBinding(media, caller))
        .thenReturn(Optional.of(Map.of("call_id", "recovery-call", "source_room_id", source)));
    var allowRecovery = new java.util.concurrent.atomic.AtomicBoolean(losePreviouslyObservedRoom);
    var subscribed = new java.util.concurrent.atomic.AtomicBoolean();
    when(matrix.ensureAdminInRoom(media, caller))
        .thenAnswer(
            invocation -> {
              boolean available = allowRecovery.get();
              subscribed.set(available);
              return available;
            });
    long now = System.currentTimeMillis();
    Map<String, Object> invite =
        Map.of(
            "type",
            "org.oriso.call.invite",
            "sender",
            caller,
            "event_id",
            "$recovery-invite",
            "origin_server_ts",
            now,
            "content",
            Map.of(
                "call_id",
                "recovery-call",
                "call_room_id",
                media,
                "lifetime",
                60000,
                "is_video",
                true));
    var firstSync = new java.util.concurrent.atomic.AtomicBoolean(true);
    var finish = new java.util.concurrent.atomic.AtomicBoolean();
    var mediaBatches = new java.util.concurrent.atomic.AtomicInteger();
    var leavePending = new java.util.concurrent.atomic.AtomicBoolean();
    var quietBatches = new java.util.concurrent.atomic.AtomicInteger();
    when(matrix.makeMatrixRequest(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              if (firstSync.getAndSet(false)) return syncRoom(source, "timeline", List.of(invite));
              if (leavePending.getAndSet(false))
                return Map.of(
                    "next_batch",
                    "left-media",
                    "rooms",
                    Map.of(
                        "leave", Map.of(media, Map.of("timeline", Map.of("events", List.of())))));
              if (!subscribed.get()) {
                quietBatches.incrementAndGet();
                return Map.of("next_batch", "quiet", "rooms", Map.of());
              }
              mediaBatches.incrementAndGet();
              return syncRoom(
                  media,
                  finish.get() ? "timeline" : "state",
                  finish.get()
                      ? List.of(
                          departure(caller, "caller-device", now, 1),
                          departure(receiver, "receiver-device", now, 1))
                      : List.of(
                          rtcMember(caller, "caller-device", now, false),
                          rtcMember(receiver, "receiver-device", now, false)));
            });
    java.util.function.Supplier<MatrixEventListenerService> fresh =
        () ->
            new MatrixEventListenerService(
                matrix,
                mock(SessionService.class),
                mock(MobilePushNotificationService.class),
                notificationService,
                Optional.empty(),
                users,
                consultants,
                sessions,
                mock(ConsultantMessageStatService.class),
                new MatrixCallInviteNotificationService(
                    matrix,
                    conversations,
                    users,
                    consultants,
                    notificationService,
                    callBindings,
                    lifecycle));
    var listener = fresh.get();
    try {
      listener.initialize();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                assertThat(notifications.findAll())
                    .hasSize(1)
                    .allSatisfy(
                        event -> {
                          assertThat(event.getEventType()).isEqualTo("call.invited");
                          assertThat(event.getRecipientUserId()).isEqualTo("recovery-receiver");
                        });
              });
      if (losePreviouslyObservedRoom) {
        await()
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(() -> assertThat(mediaBatches.get()).isGreaterThanOrEqualTo(2));
        allowRecovery.set(false);
        subscribed.set(false);
        quietBatches.set(0);
        leavePending.set(true);
        await()
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(() -> assertThat(quietBatches.get()).isGreaterThanOrEqualTo(2));
      }
      listener.shutdown();
      mediaBatches.set(0);
      allowRecovery.set(true);
      listener = fresh.get();
      listener.initialize();
      await()
          .during(Duration.ofMillis(300))
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(mediaBatches.get()).isGreaterThanOrEqualTo(2));
      finish.set(true);
      await()
          .during(Duration.ofMillis(300))
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                var ended =
                    notifications.findAll().stream()
                        .filter(event -> "call.ended".equals(event.getEventType()))
                        .toList();
                assertThat(ended).hasSize(2);
                assertThat(ended)
                    .extracting(event -> event.getRecipientUserId())
                    .containsExactlyInAnyOrder("recovery-caller", "recovery-receiver");
                assertThat(ended)
                    .allSatisfy(
                        event -> {
                          assertThat(event.getSourceSessionId()).isEqualTo(45L);
                          assertThat(event.getTenantId()).isEqualTo(7L);
                        });
              });
    } finally {
      listener.shutdown();
      notifications.deleteAll();
      bindingRepository.deleteAll();
    }
  }

  @Test
  void mediaRoomDepartureAfterRestartFinishesTheOriginalConversationCallExactlyOnce() {
    assertCallCompletionAfterRestart(false);
  }

  @Test
  void expiredDevicesAfterRestartFinishTheCallWithoutAnyDepartureEvent() {
    assertCallCompletionAfterRestart(true);
  }

  private void assertCallCompletionAfterRestart(boolean expireWithoutDeparture) {
    assertCallCompletionAfterRestart(expireWithoutDeparture, false);
  }

  @Test
  void unansweredInvitationAfterRestartNotifiesOnlyTheInvitedNonattendeeOnce() {
    assertCallCompletionAfterRestart(true, true);
  }

  private void assertCallCompletionAfterRestart(boolean expireWithoutDeparture, boolean neverJoin) {
    assertCallCompletionAfterRestart(expireWithoutDeparture, neverJoin, false);
  }

  @Test
  void mediaStateBeforeTheSourceInviteInOneSyncStillRecordsAttendance() {
    assertCallCompletionAfterRestart(false, false, true);
  }

  private void assertCallCompletionAfterRestart(
      boolean expireWithoutDeparture, boolean neverJoin, boolean mediaFirst) {
    assertCallCompletionAfterRestart(
        expireWithoutDeparture, neverJoin, mediaFirst, RecipientChange.NONE);
  }

  private enum RecipientChange {
    NONE,
    REMOVED,
    OTHER_TENANT,
    NEW_MEMBER
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = RecipientChange.class,
      names = {"REMOVED", "OTHER_TENANT", "NEW_MEMBER"})
  void missedRecipientsRemainBoundToTheInvitationAndCurrentAuthorization(RecipientChange change) {
    assertCallCompletionAfterRestart(true, true, false, change);
  }

  private void assertCallCompletionAfterRestart(
      boolean expireWithoutDeparture,
      boolean neverJoin,
      boolean mediaFirst,
      RecipientChange change) {
    assertCallCompletionAfterRestart(expireWithoutDeparture, neverJoin, mediaFirst, change, false);
  }

  private enum GroupCompletion {
    DEPARTURE,
    DEVICE_EXPIRY,
    UNANSWERED,
    SAME_TIMESTAMP_DEPARTURE,
    MEDIA_UNOBSERVED
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(GroupCompletion.class)
  void groupLifecycleAfterRestartRetainsItsGroupIdentityAndRecipientTargets(GroupCompletion mode) {
    assertCallCompletionAfterRestart(
        mode == GroupCompletion.DEVICE_EXPIRY
            || mode == GroupCompletion.UNANSWERED
            || mode == GroupCompletion.MEDIA_UNOBSERVED,
        mode == GroupCompletion.UNANSWERED || mode == GroupCompletion.MEDIA_UNOBSERVED,
        false,
        RecipientChange.NONE,
        true,
        mode == GroupCompletion.SAME_TIMESTAMP_DEPARTURE ? 0 : 1,
        mode != GroupCompletion.MEDIA_UNOBSERVED);
  }

  private void assertCallCompletionAfterRestart(
      boolean expireWithoutDeparture,
      boolean neverJoin,
      boolean mediaFirst,
      RecipientChange change,
      boolean groupSource) {
    assertCallCompletionAfterRestart(
        expireWithoutDeparture, neverJoin, mediaFirst, change, groupSource, 1);
  }

  private void assertCallCompletionAfterRestart(
      boolean expireWithoutDeparture,
      boolean neverJoin,
      boolean mediaFirst,
      RecipientChange change,
      boolean groupSource,
      long departureOffset) {
    assertCallCompletionAfterRestart(
        expireWithoutDeparture, neverJoin, mediaFirst, change, groupSource, departureOffset, true);
  }

  private void assertCallCompletionAfterRestart(
      boolean expireWithoutDeparture,
      boolean neverJoin,
      boolean mediaFirst,
      RecipientChange change,
      boolean groupSource,
      long departureOffset,
      boolean observeMedia) {
    String source = "!lifecycle-source:example";
    String media = "!lifecycle-media:example";
    String caller = "@lifecycle-caller:example";
    String receiver = "@lifecycle-receiver:example";
    long now = System.currentTimeMillis();
    var asker = mock(User.class);
    when(asker.getUserId()).thenReturn("lifecycle-asker");
    when(asker.getTenantId()).thenReturn(7L);
    when(asker.getMatrixUserId()).thenReturn(caller);
    var consultant = mock(Consultant.class);
    when(consultant.getId()).thenReturn("lifecycle-consultant");
    when(consultant.getTenantId()).thenReturn(7L);
    when(consultant.getMatrixUserId()).thenReturn(receiver);
    var session = mock(Session.class);
    when(session.getId()).thenReturn(43L);
    when(session.getTenantId()).thenReturn(7L);
    when(session.getMatrixRoomId()).thenReturn(source);
    when(session.getUser()).thenReturn(asker);
    when(session.getConsultant()).thenReturn(consultant);
    when(sessions.findByMatrixRoomId(source))
        .thenReturn(groupSource ? Optional.empty() : Optional.of(session));
    if (groupSource) {
      var group = mock(de.caritas.cob.userservice.api.model.Chat.class);
      when(group.getId()).thenReturn(84L);
      when(group.getChatOwner()).thenReturn(consultant);
      when(group.getMatrixRoomId()).thenReturn(source);
      when(chats.findByMatrixRoomId(source)).thenReturn(Optional.of(group));
    }
    when(users.findByMatrixUserIdAndDeleteDateIsNull(caller)).thenReturn(Optional.of(asker));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(receiver))
        .thenReturn(Optional.of(consultant));
    when(matrix.getAdminToken()).thenReturn("synthetic-token");
    when(matrix.getMatrixApiUrl()).thenReturn("https://matrix.example");
    when(matrix.getRoomMembers(source)).thenReturn(Optional.of(List.of(caller, receiver)));
    when(matrix.getRoomMembers(media)).thenReturn(Optional.of(List.of(caller, receiver)));
    when(matrix.ensureAdminInRoom(media, caller)).thenReturn(true);
    when(matrix.getCallRoomBinding(media, caller))
        .thenReturn(Optional.of(Map.of("call_id", "lifecycle-call", "source_room_id", source)));
    Map<String, Object> invite =
        Map.of(
            "type",
            "org.oriso.call.invite",
            "sender",
            caller,
            "event_id",
            "$lifecycle-invite",
            "origin_server_ts",
            now,
            "content",
            Map.of(
                "call_id",
                "lifecycle-call",
                "call_room_id",
                media,
                "lifetime",
                neverJoin ? 2000 : 60000,
                "is_video",
                true));
    var response =
        new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>(
            mediaFirst
                ? Map.of("next_batch", "quiet", "rooms", Map.of())
                : syncRoom(source, "timeline", List.of(invite)));
    var orderedRooms = new java.util.LinkedHashMap<String, Object>();
    orderedRooms.put(
        media,
        Map.of(
            "state",
            Map.of(
                "events",
                List.of(
                    rtcMember(caller, "caller-device", now, false),
                    rtcMember(receiver, "receiver-device", now, false)))));
    orderedRooms.put(source, Map.of("timeline", Map.of("events", List.of(invite))));
    Map<String, Object> initialCombinedSync =
        Map.of("next_batch", "initial-combined", "rooms", Map.of("join", orderedRooms));
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    when(matrix.makeMatrixRequest(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              if (requests.incrementAndGet() == 1 && mediaFirst) return initialCombinedSync;
              return response.get();
            });
    java.util.function.Supplier<MatrixEventListenerService> freshListener =
        () ->
            new MatrixEventListenerService(
                matrix,
                mock(SessionService.class),
                mock(MobilePushNotificationService.class),
                notificationService,
                Optional.empty(),
                users,
                consultants,
                sessions,
                mock(ConsultantMessageStatService.class),
                new MatrixCallInviteNotificationService(
                    matrix,
                    conversations,
                    users,
                    consultants,
                    notificationService,
                    callBindings,
                    lifecycle));
    var listener = freshListener.get();
    try {
      listener.initialize();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () ->
                  assertThat(notifications.findAll())
                      .anySatisfy(
                          event -> assertThat(event.getEventType()).isEqualTo("call.invited")));
      // Initial sync state carries the current device memberships, not only timeline events.
      long attendanceTimestamp = mediaFirst ? now : System.currentTimeMillis();
      response.set(
          mediaFirst || !observeMedia
              ? Map.of("next_batch", "quiet", "rooms", Map.of())
              : syncRoom(
                  media,
                  "state",
                  neverJoin
                      ? List.of()
                      : List.of(
                          rtcMember(
                              caller,
                              "caller-device",
                              attendanceTimestamp,
                              false,
                              expireWithoutDeparture ? 2000 : 60000),
                          rtcMember(
                              receiver,
                              "receiver-device",
                              attendanceTimestamp,
                              false,
                              expireWithoutDeparture ? 2000 : 60000))));
      int beforeAttendance = requests.get();
      await()
          .during(Duration.ofMillis(300))
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(requests.get()).isGreaterThan(beforeAttendance + 2));
      listener.shutdown();
      if (change == RecipientChange.REMOVED) {
        when(matrix.getRoomMembers(source)).thenReturn(Optional.of(List.of(caller)));
      } else if (change == RecipientChange.OTHER_TENANT) {
        when(consultant.getTenantId()).thenReturn(8L);
      } else if (change == RecipientChange.NEW_MEMBER) {
        var lateMember = mock(User.class);
        when(lateMember.getUserId()).thenReturn("late-noninvitee");
        when(lateMember.getTenantId()).thenReturn(7L);
        when(users.findByMatrixUserIdAndDeleteDateIsNull("@late:example"))
            .thenReturn(Optional.of(lateMember));
        when(matrix.getRoomMembers(source))
            .thenReturn(Optional.of(List.of(caller, receiver, "@late:example")));
      }
      // No source invite is replayed. A new listener must recover the durable media binding
      // and attendance before processing the last departures.
      response.set(
          expireWithoutDeparture
              ? Map.of("next_batch", "quiet-batch", "rooms", Map.of())
              : syncRoom(
                  media,
                  "timeline",
                  List.of(
                      departure(caller, "caller-device", attendanceTimestamp, departureOffset),
                      departure(
                          receiver, "receiver-device", attendanceTimestamp, departureOffset))));
      listener = freshListener.get();
      listener.initialize();
      var firstRequestAfterExpiry = new java.util.concurrent.atomic.AtomicInteger(-1);
      await()
          .during(Duration.ofMillis(300))
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                if (change != RecipientChange.NONE || !observeMedia) {
                  // Negative recipient assertions must not pass before the expiry path runs.
                  assertThat(System.currentTimeMillis()).isGreaterThan(now + 2000);
                  firstRequestAfterExpiry.compareAndSet(-1, requests.get());
                  assertThat(requests.get()).isGreaterThan(firstRequestAfterExpiry.get() + 2);
                }
                var ended =
                    notifications.findAll().stream()
                        .filter(
                            event ->
                                (neverJoin ? "call.missed" : "call.ended")
                                    .equals(event.getEventType()))
                        .toList();
                boolean excluded =
                    !observeMedia
                        || change == RecipientChange.REMOVED
                        || change == RecipientChange.OTHER_TENANT;
                assertThat(ended).hasSize(excluded ? 0 : neverJoin ? 1 : 2);
                assertThat(ended)
                    .extracting(event -> event.getRecipientUserId())
                    .containsExactlyInAnyOrder(
                        excluded
                            ? new String[] {}
                            : neverJoin
                                ? new String[] {"lifecycle-consultant"}
                                : new String[] {"lifecycle-asker", "lifecycle-consultant"});
                if (neverJoin) {
                  assertThat(notifications.findAll())
                      .noneSatisfy(
                          event -> assertThat(event.getEventType()).isEqualTo("call.ended"));
                }
                assertThat(ended)
                    .allSatisfy(
                        event -> {
                          if (groupSource) {
                            assertThat(event.getSourceSessionId()).isNull();
                            assertThat(event.getParams()).contains("\"seriesId\":84");
                            String base =
                                "lifecycle-consultant".equals(event.getRecipientUserId())
                                    ? "/sessions/consultant/sessionView/"
                                    : "/sessions/user/view/";
                            assertThat(event.getActionPath())
                                .isEqualTo(base + "!lifecycle-source%3Aexample/84");
                          } else {
                            assertThat(event.getSourceSessionId()).isEqualTo(43L);
                          }
                          assertThat(event.getTenantId()).isEqualTo(7L);
                        });
              });
    } finally {
      listener.shutdown();
      notifications.deleteAll();
      bindingRepository.deleteAll();
    }
  }

  private static Map<String, Object> syncRoom(
      String room, String section, List<Map<String, Object>> events) {
    return Map.of(
        "next_batch",
        "lifecycle-batch",
        "rooms",
        Map.of("join", Map.of(room, Map.of(section, Map.of("events", events)))));
  }

  private static Map<String, Object> rtcMember(
      String sender, String device, long timestamp, boolean departure) {
    return rtcMember(sender, device, timestamp, departure, 60000);
  }

  private static Map<String, Object> rtcMember(
      String sender, String device, long timestamp, boolean departure, long expires) {
    Map<String, Object> content =
        departure
            ? Map.of()
            : Map.of(
                "application",
                "m.call",
                "scope",
                "m.room",
                "call_id",
                "",
                "device_id",
                device,
                "expires",
                expires,
                "focus_active",
                Map.of("type", "livekit"),
                "foci_preferred",
                List.of());
    return Map.of(
        "type",
        "org.matrix.msc3401.call.member",
        "event_id",
        "$" + device + timestamp + (departure ? "-leave" : "-join"),
        "sender",
        sender,
        "state_key",
        "_" + sender + "_" + device,
        "origin_server_ts",
        timestamp,
        "content",
        content);
  }

  private static Map<String, Object> departure(
      String sender, String device, long joinedAt, long offset) {
    var event = new java.util.HashMap<>(rtcMember(sender, device, joinedAt + offset, true));
    event.put("unsigned", Map.of("replaces_state", "$" + device + joinedAt + "-join"));
    return event;
  }

  @Test
  void expiredInviteFromInitialSyncDoesNotNotifyAgain() {
    assertInviteNotifications(System.currentTimeMillis() - 120_000, 0, false);
  }

  @Test
  void listenerRestartKeepsTheSameCommittedNotification() {
    assertInviteNotifications(System.currentTimeMillis(), 1, true);
  }

  private void assertInviteNotifications(long timestamp, int expectedCount, boolean restart) {
    assertInviteNotifications(timestamp, expectedCount, restart, 7L, true);
  }

  @Test
  void aRoomMemberFromAnotherTenantReceivesNothing() {
    assertInviteNotifications(System.currentTimeMillis(), 0, false, 8L, true);
  }

  @Test
  void aSenderWhoIsNoLongerAMemberCannotNotifyTheRoom() {
    assertInviteNotifications(System.currentTimeMillis(), 0, false, 7L, false);
  }

  private void assertInviteNotifications(
      long timestamp,
      int expectedCount,
      boolean restart,
      long recipientTenant,
      boolean senderIsMember) {
    assertInviteNotifications(
        timestamp, expectedCount, restart, recipientTenant, senderIsMember, true);
  }

  @Test
  void aMediaRoomBoundToAnotherConversationCannotProduceAnInvite() {
    assertInviteNotifications(System.currentTimeMillis(), 0, false, 7L, true, false);
  }

  private void assertInviteNotifications(
      long timestamp,
      int expectedCount,
      boolean restart,
      long recipientTenant,
      boolean senderIsMember,
      boolean matchingBinding) {
    assertInviteNotifications(
        timestamp, expectedCount, restart, recipientTenant, senderIsMember, matchingBinding, false);
  }

  @Test
  void aRegisteredMediaRoomCannotBeReboundToAnotherCallAfterRestart() {
    assertInviteNotifications(System.currentTimeMillis(), 1, true, 7L, true, true, true);
  }

  private void assertInviteNotifications(
      long timestamp,
      int expectedCount,
      boolean restart,
      long recipientTenant,
      boolean senderIsMember,
      boolean matchingBinding,
      boolean rebindMedia) {
    assertInviteNotifications(
        timestamp,
        expectedCount,
        restart,
        recipientTenant,
        senderIsMember,
        matchingBinding,
        rebindMedia,
        null);
  }

  @Test
  void groupChatWithoutSessionGetsAnInvitationPointingToItsOwnConversation() {
    var group = mock(de.caritas.cob.userservice.api.model.Chat.class);
    var owner = mock(Consultant.class);
    when(owner.getTenantId()).thenReturn(7L);
    when(group.getId()).thenReturn(84L);
    when(group.getChatOwner()).thenReturn(owner);
    when(group.getMatrixRoomId()).thenReturn("!call-source:example");
    assertInviteNotifications(System.currentTimeMillis(), 1, false, 7L, true, true, false, group);
  }

  private void assertInviteNotifications(
      long timestamp,
      int expectedCount,
      boolean restart,
      long recipientTenant,
      boolean senderIsMember,
      boolean matchingBinding,
      boolean rebindMedia,
      de.caritas.cob.userservice.api.model.Chat sourceChat) {
    assertInviteNotifications(
        timestamp,
        expectedCount,
        restart,
        recipientTenant,
        senderIsMember,
        matchingBinding,
        rebindMedia,
        sourceChat,
        false);
  }

  @Test
  void anOversizedCallIdDoesNotPreventTheFollowingValidInvitation() {
    assertInviteNotifications(
        System.currentTimeMillis(), 1, false, 7L, true, true, false, null, true);
  }

  private void assertInviteNotifications(
      long timestamp,
      int expectedCount,
      boolean restart,
      long recipientTenant,
      boolean senderIsMember,
      boolean matchingBinding,
      boolean rebindMedia,
      de.caritas.cob.userservice.api.model.Chat sourceChat,
      boolean oversizedFirst) {
    String sourceRoom = "!call-source:example";
    String sender = "@caller:example";
    String recipient = "@receiver:example";
    var asker = mock(User.class);
    when(asker.getUserId()).thenReturn("call-asker");
    when(asker.getTenantId()).thenReturn(7L);
    when(asker.getMatrixUserId()).thenReturn(sender);
    var consultant = mock(Consultant.class);
    when(consultant.getId()).thenReturn("call-consultant");
    when(consultant.getTenantId()).thenReturn(recipientTenant);
    when(consultant.getMatrixUserId()).thenReturn(recipient);
    var session = mock(Session.class);
    when(session.getId()).thenReturn(42L);
    when(session.getTenantId()).thenReturn(7L);
    when(session.getMatrixRoomId()).thenReturn(sourceRoom);
    when(session.getUser()).thenReturn(asker);
    when(session.getConsultant()).thenReturn(consultant);
    when(sessions.findByMatrixRoomId(sourceRoom))
        .thenReturn(sourceChat == null ? Optional.of(session) : Optional.empty());
    when(chats.findByMatrixRoomId(sourceRoom)).thenReturn(Optional.ofNullable(sourceChat));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(sender)).thenReturn(Optional.of(asker));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(recipient))
        .thenReturn(Optional.of(consultant));
    when(matrix.getAdminToken()).thenReturn("synthetic-token");
    when(matrix.ensureAdminInRoom("!call-media:example", sender)).thenReturn(true);
    when(matrix.getMatrixApiUrl()).thenReturn("https://matrix.example");
    when(matrix.getCallRoomBinding("!call-media:example", sender))
        .thenReturn(
            Optional.of(
                Map.of(
                    "call_id",
                    "durable-call",
                    "source_room_id",
                    matchingBinding ? sourceRoom : "!unrelated:example")));
    when(matrix.getRoomMembers(sourceRoom))
        .thenReturn(Optional.of(senderIsMember ? List.of(sender, recipient) : List.of(recipient)));
    var inviteContent =
        new java.util.HashMap<String, Object>(
            Map.of(
                "call_id",
                "durable-call",
                "call_room_id",
                "!call-media:example",
                "lifetime",
                60000,
                "is_video",
                true));
    Map<String, Object> invite =
        Map.of(
            "type",
            "org.oriso.call.invite",
            "sender",
            sender,
            "event_id",
            "$call-invite",
            "origin_server_ts",
            timestamp,
            "content",
            inviteContent);
    var syncRequests = new java.util.concurrent.atomic.AtomicInteger();
    var events = new java.util.ArrayList<Map<String, Object>>();
    if (oversizedFirst) {
      String oversizedId = "x".repeat(192);
      var invalidContent = new java.util.HashMap<>(inviteContent);
      invalidContent.put("call_id", oversizedId);
      invalidContent.put("call_room_id", "!oversized-media:example");
      var invalidInvite = new java.util.HashMap<>(invite);
      invalidInvite.put("content", invalidContent);
      invalidInvite.put("event_id", "$oversized-invite");
      when(matrix.getCallRoomBinding("!oversized-media:example", sender))
          .thenReturn(Optional.of(Map.of("call_id", oversizedId, "source_room_id", sourceRoom)));
      events.add(invalidInvite);
    }
    events.add(invite);
    Map<String, Object> response =
        Map.of(
            "next_batch",
            "batch",
            "rooms",
            Map.of("join", Map.of(sourceRoom, Map.of("timeline", Map.of("events", events)))));
    when(matrix.makeMatrixRequest(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              syncRequests.incrementAndGet();
              return response;
            });
    java.util.function.Supplier<MatrixEventListenerService> newListener =
        () ->
            new MatrixEventListenerService(
                matrix,
                mock(SessionService.class),
                mock(MobilePushNotificationService.class),
                notificationService,
                Optional.empty(),
                users,
                consultants,
                sessions,
                mock(ConsultantMessageStatService.class),
                new MatrixCallInviteNotificationService(
                    matrix,
                    conversations,
                    users,
                    consultants,
                    notificationService,
                    callBindings,
                    lifecycle));
    var listener = newListener.get();
    try {
      listener.initialize();
      await()
          .during(Duration.ofMillis(300))
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                assertThat(syncRequests.get()).isGreaterThanOrEqualTo(2);
                var saved = notifications.findAll();
                assertThat(saved).hasSize(expectedCount);
                if (expectedCount > 0) {
                  org.mockito.Mockito.verify(matrix, org.mockito.Mockito.atLeastOnce())
                      .ensureAdminInRoom("!call-media:example", sender);
                }
                assertThat(saved)
                    .allSatisfy(
                        event -> {
                          assertThat(event.getRecipientUserId()).isEqualTo("call-consultant");
                          if (sourceChat == null) {
                            assertThat(event.getSourceSessionId()).isEqualTo(42L);
                          } else {
                            assertThat(event.getSourceSessionId()).isNull();
                            assertThat(event.getParams()).contains("\"seriesId\":84");
                            assertThat(event.getActionPath())
                                .isEqualTo(
                                    "/sessions/consultant/sessionView/!call-source%3Aexample/84");
                          }
                          assertThat(event.getTenantId()).isEqualTo(7L);
                          assertThat(event.getEventType()).isEqualTo("call.invited");
                        });
              });
      if (restart) {
        var originalIds = notifications.findAll().stream().map(event -> event.getId()).toList();
        listener.shutdown();
        if (rebindMedia) {
          inviteContent.put("call_id", "another-call");
          when(matrix.getCallRoomBinding("!call-media:example", sender))
              .thenReturn(
                  Optional.of(Map.of("call_id", "another-call", "source_room_id", sourceRoom)));
        }
        int requestsBeforeRestart = syncRequests.get();
        listener = newListener.get();
        listener.initialize();
        await()
            .during(Duration.ofMillis(300))
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(
                () -> {
                  assertThat(syncRequests.get()).isGreaterThanOrEqualTo(requestsBeforeRestart + 2);
                  assertThat(notifications.findAll().stream().map(event -> event.getId()).toList())
                      .containsExactlyElementsOf(originalIds);
                });
      }
    } finally {
      listener.shutdown();
      notifications.deleteAll();
      bindingRepository.deleteAll();
    }
  }
}
