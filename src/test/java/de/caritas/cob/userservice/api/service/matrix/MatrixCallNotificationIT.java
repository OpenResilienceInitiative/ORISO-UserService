package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
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
import de.caritas.cob.userservice.api.service.notification.InternalChatEmailService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.ConsultantMessageStatService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Exercises the actual sync input and committed notification storage, not private handlers. */
@DataJpaTest
@TestPropertySource(
    properties = {
      "spring.profiles.active=testing,call-notification-account-fixture",
      "matrix.calls.observation-retry-ms=10"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  EventNotificationService.class,
  ConsultantDisplayNameResolver.class,
  EventNotificationDeduplicationWriter.class,
  MatrixCallBindingService.class,
  MatrixCallLifecycleService.class,
  MatrixCallConversationResolver.class,
  MatrixCallBindingWriter.class,
  MatrixCallInviteNotificationService.class,
  MatrixCallNotificationIT.ActiveAccountFixture.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatrixCallNotificationIT {
  @Autowired private EventNotificationRepository notifications;
  @Autowired private EventNotificationService notificationService;
  @Autowired private MatrixCallBindingService callBindings;
  @Autowired private MatrixCallLifecycleService lifecycle;
  @Autowired private MatrixCallConversationResolver conversations;
  @Autowired private MatrixCallInviteNotificationService inviteService;
  @MockitoBean private MatrixSynapseService matrix;
  @MockitoBean private MatrixFeedUpdateSignalService feedUpdateSignals;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository bindingRepository;

  @MockitoBean private SessionRepository sessions;
  @MockitoBean private de.caritas.cob.userservice.api.port.out.ChatRepository chats;
  @MockitoBean private UserRepository users;
  @MockitoBean private ConsultantRepository consultants;
  @MockitoBean private IdentityTombstoneService tombstones;
  @Autowired private AccountInactivityService accountLifecycle;
  @Autowired private javax.sql.DataSource accountDataSource;

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  @org.springframework.context.annotation.Profile("call-notification-account-fixture")
  static class ActiveAccountFixture {
    @org.springframework.context.annotation.Bean
    AccountInactivityService accountLifecycle(
        javax.sql.DataSource dataSource,
        org.springframework.transaction.PlatformTransactionManager transactionManager) {
      return new AccountInactivityService(
          new JdbcTemplate(dataSource),
          transactionManager,
          java.time.Clock.systemUTC(),
          mock(
              de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects
                  .class));
    }
  }

  @org.junit.jupiter.api.BeforeEach
  void initializeAccountLifecycleRows() {
    initializeAccountLifecycleTable(accountDataSource);
    org.mockito.Mockito.lenient()
        .when(
            sessions.lockCallStatus(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong()))
        .thenAnswer(
            call ->
                sessions
                    .findByMatrixRoomId(call.getArgument(1, String.class))
                    .filter(value -> java.util.Objects.equals(value.getId(), call.getArgument(0)))
                    .map(value -> value.getStatus() == null ? 0 : value.getStatus().getValue()));
  }

  static void initializeAccountLifecycleTable(javax.sql.DataSource dataSource) {
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,"
            + "tenant_id BIGINT,assigned_months INT NOT NULL,revision BIGINT NOT NULL,"
            + "last_activity TIMESTAMP(6) NOT NULL,due_at TIMESTAMP(6) NOT NULL,"
            + "status VARCHAR(20) NOT NULL,last_error VARCHAR(1000),attempts INT DEFAULT 0 NOT NULL)");
    jdbc.update("DELETE FROM account_inactivity");
  }

  @Test
  void currentInviteProducesAPersistedNotificationForTheOtherConversationMember() {
    assertInviteNotifications(System.currentTimeMillis(), 1, false);
  }

  @Test
  void inviteLookupFailureRetriesTheSameCursorBeforePersistingTheInvitation() {
    String source = "!retry-invite-source:example";
    String media = "!retry-invite-media:example";
    String caller = "@retry-invite-caller:example";
    String receiver = "@retry-invite-receiver:example";
    var owner = mock(User.class);
    when(owner.getUserId()).thenReturn("retry-invite-caller");
    when(owner.getTenantId()).thenReturn(7L);
    when(owner.getMatrixUserId()).thenReturn(caller);
    var recipient = mock(Consultant.class);
    when(recipient.getId()).thenReturn("retry-invite-receiver");
    when(recipient.getTenantId()).thenReturn(7L);
    when(recipient.getMatrixUserId()).thenReturn(receiver);
    var session = mock(Session.class);
    when(session.getId()).thenReturn(46L);
    when(session.getTenantId()).thenReturn(7L);
    when(session.getMatrixRoomId()).thenReturn(source);
    when(session.getUser()).thenReturn(owner);
    when(session.getConsultant()).thenReturn(recipient);
    when(sessions.findByMatrixRoomId(source)).thenReturn(Optional.of(session));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(caller)).thenReturn(Optional.of(owner));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(receiver))
        .thenReturn(Optional.of(recipient));
    when(matrix.getAdminToken()).thenReturn("synthetic-token");
    when(matrix.getMatrixApiUrl()).thenReturn("https://matrix.example");
    when(matrix.getCallRoomMembers(source))
        .thenThrow(
            new MatrixSynapseService.CallLookupUnavailableException(
                "synthetic membership transport failure"))
        .thenReturn(Optional.of(List.of(caller, receiver)));
    when(matrix.getCallRoomBinding(media, caller))
        .thenReturn(Optional.of(Map.of("call_id", "retry-invite", "source_room_id", source)));
    when(matrix.ensureAdminInRoom(media, caller)).thenReturn(true);
    long now = System.currentTimeMillis();
    var invite =
        Map.<String, Object>of(
            "type",
            "org.oriso.call.invite",
            "sender",
            caller,
            "event_id",
            "$retry-invite",
            "origin_server_ts",
            now,
            "content",
            Map.of(
                "call_id",
                "retry-invite",
                "call_room_id",
                media,
                "lifetime",
                60000,
                "is_video",
                true));
    var requestedUrls = new java.util.concurrent.CopyOnWriteArrayList<String>();
    when(matrix.makeMatrixRequest(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              String url = invocation.getArgument(0, String.class);
              requestedUrls.add(url);
              return url.contains("since=")
                  ? Map.of("next_batch", "quiet", "rooms", Map.of())
                  : Map.of(
                      "next_batch",
                      "invite-recovered",
                      "rooms",
                      Map.of(
                          "join",
                          Map.of(source, Map.of("timeline", Map.of("events", List.of(invite))))));
            });
    var listener = fastRetryListener();
    try {
      listener.initialize();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                assertThat(bindingRepository.findByMediaRoomId(media)).isPresent();
                assertThat(notifications.findAll())
                    .singleElement()
                    .satisfies(
                        event -> {
                          assertThat(event.getEventType()).isEqualTo("call.invited");
                          assertThat(event.getRecipientUserId()).isEqualTo("retry-invite-receiver");
                        });
                var urlSnapshot = List.copyOf(requestedUrls);
                assertThat(urlSnapshot).hasSizeGreaterThanOrEqualTo(3);
                assertThat(urlSnapshot.subList(0, 2))
                    .allSatisfy(url -> assertThat(url).doesNotContain("since="));
                assertThat(urlSnapshot)
                    .anySatisfy(url -> assertThat(url).contains("since=invite-recovered"));
              });
    } finally {
      listener.shutdown();
      notifications.deleteAll();
      bindingRepository.deleteAll();
    }
  }

  @Test
  void departureLookupFailureRetriesTheSameCursorAndKeepsTheActualEndTimestamp() {
    String source = "!retry-departure-source:example";
    String media = "!retry-departure-media:example";
    String caller = "@retry-departure-caller:example";
    String receiver = "@retry-departure-receiver:example";
    var owner = mock(User.class);
    when(owner.getUserId()).thenReturn("retry-departure-caller");
    when(owner.getTenantId()).thenReturn(7L);
    when(owner.getMatrixUserId()).thenReturn(caller);
    var recipient = mock(Consultant.class);
    when(recipient.getId()).thenReturn("retry-departure-receiver");
    when(recipient.getTenantId()).thenReturn(7L);
    when(recipient.getMatrixUserId()).thenReturn(receiver);
    var session = mock(Session.class);
    when(session.getId()).thenReturn(47L);
    when(session.getTenantId()).thenReturn(7L);
    when(session.getMatrixRoomId()).thenReturn(source);
    when(session.getUser()).thenReturn(owner);
    when(session.getConsultant()).thenReturn(recipient);
    when(sessions.findByMatrixRoomId(source)).thenReturn(Optional.of(session));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(caller)).thenReturn(Optional.of(owner));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(receiver))
        .thenReturn(Optional.of(recipient));
    when(matrix.getAdminToken()).thenReturn("synthetic-token");
    when(matrix.getMatrixApiUrl()).thenReturn("https://matrix.example");
    var servingDeparture = new java.util.concurrent.atomic.AtomicBoolean();
    var departureFailurePending = new java.util.concurrent.atomic.AtomicBoolean(true);
    when(matrix.getCallRoomMembers(source))
        .thenAnswer(
            ignored ->
                servingDeparture.get() && departureFailurePending.getAndSet(false)
                    ? throwLookupUnavailable()
                    : Optional.of(List.of(caller, receiver)));
    when(matrix.getCallRoomBinding(media, caller))
        .thenReturn(Optional.of(Map.of("call_id", "retry-departure", "source_room_id", source)));
    when(matrix.ensureAdminInRoom(media, caller)).thenReturn(true);
    long joinedAt = System.currentTimeMillis();
    var invite =
        Map.<String, Object>of(
            "type",
            "org.oriso.call.invite",
            "sender",
            caller,
            "event_id",
            "$retry-departure-invite",
            "origin_server_ts",
            joinedAt,
            "content",
            Map.of(
                "call_id",
                "retry-departure",
                "call_room_id",
                media,
                "lifetime",
                60000,
                "is_video",
                true));
    var joinedRooms = new java.util.LinkedHashMap<String, Object>();
    joinedRooms.put(source, Map.of("timeline", Map.of("events", List.of(invite))));
    joinedRooms.put(
        media,
        Map.of(
            "state",
            Map.of(
                "events",
                List.of(
                    rtcMember(caller, "retry-caller-device", joinedAt, false),
                    rtcMember(receiver, "retry-receiver-device", joinedAt, false)))));
    var initial =
        Map.<String, Object>of("next_batch", "attendance", "rooms", Map.of("join", joinedRooms));
    var departure =
        syncRoom(
            media,
            "timeline",
            List.of(
                departure(caller, "retry-caller-device", joinedAt, 1),
                departure(receiver, "retry-receiver-device", joinedAt, 1)));
    departure = new java.util.HashMap<>(departure);
    departure.put("next_batch", "departure-recovered");
    var requestedUrls = new java.util.concurrent.CopyOnWriteArrayList<String>();
    Map<String, Object> departureResponse = departure;
    when(matrix.makeMatrixRequest(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              String url = invocation.getArgument(0, String.class);
              requestedUrls.add(url);
              if (!url.contains("since=")) return initial;
              if (url.contains("since=attendance")) {
                servingDeparture.set(true);
                return departureResponse;
              }
              return Map.of("next_batch", "quiet", "rooms", Map.of());
            });
    var listener = fastRetryListener();
    try {
      listener.initialize();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                assertThat(notifications.findAll().stream())
                    .filteredOn(event -> "call.ended".equals(event.getEventType()))
                    .hasSize(2);
                assertThat(bindingRepository.findByMediaRoomId(media))
                    .get()
                    .extracting(binding -> binding.getEndedAt())
                    .isEqualTo(joinedAt + 1);
                var urlSnapshot = List.copyOf(requestedUrls);
                assertThat(
                        urlSnapshot.stream()
                            .filter(url -> url.contains("since=attendance"))
                            .count())
                    .isGreaterThanOrEqualTo(2);
                assertThat(urlSnapshot)
                    .anySatisfy(url -> assertThat(url).contains("since=departure-recovered"));
              });
    } finally {
      listener.shutdown();
      notifications.deleteAll();
      bindingRepository.deleteAll();
    }
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
    when(matrix.getCallRoomMembers(source)).thenReturn(Optional.of(List.of(caller, receiver)));
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
                inviteService,
                mock(
                    de.caritas.cob.userservice.api.service.notification
                        .AdviceSeekerReplyEmailService.class),
                freshEmailCursor(),
                mock(InternalChatEmailService.class));
    var listener = fresh.get();
    try {
      listener.initialize();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                assertThat(notifications.findAll())
                    .filteredOn(event -> "call.invited".equals(event.getEventType()))
                    .singleElement()
                    .satisfies(
                        event -> {
                          assertThat(event.getRecipientUserId()).isEqualTo("recovery-receiver");
                          assertThat(event.getSourceSessionId()).isEqualTo(45L);
                          assertThat(event.getParams()).contains("\"callId\":\"recovery-call\"");
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

  @Test
  void observedCallStartNotifiesInvitedCurrentMemberAndSurvivesListenerRestart() {
    assertCallCompletionAfterRestart(false, false, false);
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
    when(matrix.getCallRoomMembers(source)).thenReturn(Optional.of(List.of(caller, receiver)));
    when(matrix.getCallRoomMembers(media)).thenReturn(Optional.of(List.of(caller, receiver)));
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
                inviteService,
                mock(
                    de.caritas.cob.userservice.api.service.notification
                        .AdviceSeekerReplyEmailService.class),
                freshEmailCursor(),
                mock(InternalChatEmailService.class));
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
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                var started =
                    notifications.findAll().stream()
                        .filter(event -> "call.started".equals(event.getEventType()))
                        .toList();
                assertThat(started).hasSize(neverJoin || !observeMedia ? 0 : 1);
                assertThat(started)
                    .allSatisfy(
                        event -> {
                          assertThat(event.getRecipientUserId()).isEqualTo("lifecycle-consultant");
                          assertThat(event.getTenantId()).isEqualTo(7L);
                          assertThat(event.getParams())
                              .contains(
                                  "\"callId\":\"lifecycle-call\"",
                                  "\"callRoomId\":\"" + media + "\"",
                                  "\"callType\":\"video\"");
                          if (groupSource) {
                            assertThat(event.getSourceSessionId()).isNull();
                            assertThat(event.getParams()).contains("\"seriesId\":84");
                          } else {
                            assertThat(event.getSourceSessionId()).isEqualTo(43L);
                            assertThat(event.getActionPath())
                                .isEqualTo(
                                    "/sessions/consultant/sessionView/!lifecycle-source:example/43");
                          }
                        });
              });
      listener.shutdown();
      var startedIds =
          notifications.findAll().stream()
              .filter(event -> "call.started".equals(event.getEventType()))
              .map(event -> event.getId())
              .toList();
      if (change == RecipientChange.REMOVED) {
        when(matrix.getCallRoomMembers(source)).thenReturn(Optional.of(List.of(caller)));
      } else if (change == RecipientChange.OTHER_TENANT) {
        when(consultant.getTenantId()).thenReturn(8L);
      } else if (change == RecipientChange.NEW_MEMBER) {
        var lateMember = mock(User.class);
        when(lateMember.getUserId()).thenReturn("late-noninvitee");
        when(lateMember.getTenantId()).thenReturn(7L);
        when(users.findByMatrixUserIdAndDeleteDateIsNull("@late:example"))
            .thenReturn(Optional.of(lateMember));
        when(matrix.getCallRoomMembers(source))
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
                assertThat(
                        notifications.findAll().stream()
                            .filter(event -> "call.started".equals(event.getEventType()))
                            .map(event -> event.getId())
                            .toList())
                    .containsExactlyInAnyOrderElementsOf(startedIds);
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void startedAudienceUsesOriginalInviteesAndCurrentAccessEvenWithoutRecipientAttendance(
      boolean groupSource) {
    assertStartedAudience(groupSource, null);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(AccountInactivityService.Status.class)
  void startedAudienceRequiresActiveLifecycleEvenIfDomainAndRoomMembershipRemain(
      AccountInactivityService.Status status) {
    assertStartedAudience(false, status);
  }

  private void assertStartedAudience(boolean groupSource, AccountInactivityService.Status status) {
    String source = "!started-audience-source:example";
    String media = "!started-audience-media:example";
    String caller = "@started-caller:example";
    String receiver = "@started-consultant:example";
    String absent = "@started-absent:example";
    String removed = "@started-removed:example";
    String deleted = "@started-deleted:example";
    String moved = "@started-other-tenant:example";
    String newcomer = "@started-newcomer:example";
    long now = System.currentTimeMillis();
    var owner = startedUser("started-caller", caller);
    var absentUser = startedUser("started-absent", absent);
    var removedUser = startedUser("started-removed", removed);
    var deletedUser = startedUser("started-deleted", deleted);
    var movedUser = startedUser("started-other-tenant", moved);
    var newUser = startedUser("started-newcomer", newcomer);
    for (var user : List.of(owner, absentUser, removedUser, deletedUser, movedUser, newUser)) {
      when(users.findByMatrixUserIdAndDeleteDateIsNull(user.getMatrixUserId()))
          .thenReturn(Optional.of(user));
    }
    var consultant = mock(Consultant.class);
    when(consultant.getId()).thenReturn("started-consultant");
    when(consultant.getTenantId()).thenReturn(7L);
    when(consultant.getMatrixUserId()).thenReturn(receiver);
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(receiver))
        .thenReturn(Optional.of(consultant));
    var session = mock(Session.class);
    when(session.getId()).thenReturn(143L);
    when(session.getTenantId()).thenReturn(7L);
    when(session.getMatrixRoomId()).thenReturn(source);
    when(sessions.findByMatrixRoomId(source))
        .thenReturn(groupSource ? Optional.empty() : Optional.of(session));
    if (groupSource) {
      var group = mock(de.caritas.cob.userservice.api.model.Chat.class);
      when(group.getId()).thenReturn(184L);
      when(group.getChatOwner()).thenReturn(consultant);
      when(group.getMatrixRoomId()).thenReturn(source);
      when(chats.findByMatrixRoomId(source)).thenReturn(Optional.of(group));
    }
    when(matrix.getCallRoomMembers(source))
        .thenReturn(Optional.of(List.of(caller, receiver, absent, removed, deleted, moved)));
    when(matrix.getCallRoomBinding(media, caller))
        .thenReturn(Optional.of(Map.of("call_id", "started-audience", "source_room_id", source)));
    var invites = inviteService;
    try {
      assertThat(
              invites.handle(
                  source,
                  Map.of(
                      "sender",
                      caller,
                      "origin_server_ts",
                      now,
                      "content",
                      Map.of(
                          "call_id",
                          "started-audience",
                          "call_room_id",
                          media,
                          "lifetime",
                          60000,
                          "is_video",
                          false))))
          .isTrue();
      boolean receiverActive = status == null || status == AccountInactivityService.Status.ACTIVE;
      if (status != null) {
        accountLifecycle.assignAtCreation(
            "started-consultant", 7L, 12, 1L, java.time.Instant.now());
        new JdbcTemplate(accountDataSource)
            .update(
                "UPDATE account_inactivity SET status=? WHERE identity_id=?",
                status.name(),
                "started-consultant");
        assertThat(accountLifecycle.snapshot("started-consultant").orElseThrow().status())
            .isEqualTo(status);
      }
      // Eligibility changes after the durable invitation, before attendance is observed.
      movedUser.setTenantId(8L);
      when(users.findByMatrixUserIdAndDeleteDateIsNull(deleted)).thenReturn(Optional.empty());
      when(matrix.getCallRoomMembers(source))
          .thenReturn(Optional.of(List.of(caller, receiver, absent, deleted, moved, newcomer)));
      var attendance =
          Map.<String, Object>of(
              "state",
              Map.of("events", List.of(rtcMember(caller, "started-caller-device", now, false))));
      assertThat(lifecycle.handleRoom(media, attendance)).isTrue();
      var started =
          notifications.findAll().stream()
              .filter(event -> "call.started".equals(event.getEventType()))
              .toList();
      assertThat(started)
          .extracting(event -> event.getRecipientUserId())
          .containsExactlyInAnyOrder(
              receiverActive
                  ? new String[] {"started-consultant", "started-absent"}
                  : new String[] {"started-absent"});
      assertThat(started)
          .allSatisfy(
              event -> {
                assertThat(event.getTenantId()).isEqualTo(7L);
                assertThat(event.getParams())
                    .contains(
                        "\"roomRef\":\"" + source + "\"",
                        "\"callId\":\"started-audience\"",
                        "\"callRoomId\":\"" + media + "\"",
                        "\"callType\":\"audio\"");
                if (groupSource) {
                  assertThat(event.getSourceSessionId()).isNull();
                  assertThat(event.getParams()).contains("\"seriesId\":184");
                } else {
                  assertThat(event.getSourceSessionId()).isEqualTo(143L);
                }
                String base =
                    "started-consultant".equals(event.getRecipientUserId())
                        ? "/sessions/consultant/sessionView/"
                        : "/sessions/user/view/";
                assertThat(event.getActionPath())
                    .isEqualTo(
                        base
                            + (groupSource
                                ? "!started-audience-source%3Aexample/184"
                                : source + "/143"));
              });
      var initialIds = started.stream().map(event -> event.getId()).toList();
      lifecycle.handleRoom(media, attendance);
      assertThat(
              notifications.findAll().stream()
                  .filter(event -> "call.started".equals(event.getEventType()))
                  .map(event -> event.getId())
                  .toList())
          .containsExactlyInAnyOrderElementsOf(initialIds);
      // An original invitee can become authorized again during this same running call.
      when(matrix.getCallRoomMembers(source))
          .thenReturn(
              Optional.of(List.of(caller, receiver, absent, removed, deleted, moved, newcomer)));
      lifecycle.handleRoom(
          media,
          Map.of(
              "timeline",
              Map.of(
                  "events",
                  List.of(
                      rtcMember(
                          removed,
                          "started-returning-device",
                          System.currentTimeMillis(),
                          false)))));
      assertThat(
              notifications.findAll().stream()
                  .filter(event -> "call.started".equals(event.getEventType())))
          .extracting(event -> event.getRecipientUserId())
          .containsExactlyInAnyOrder(
              receiverActive
                  ? new String[] {"started-consultant", "started-absent", "started-removed"}
                  : new String[] {"started-absent", "started-removed"});
      lifecycle.handleRoom(media, attendance);
      assertThat(
              notifications.findAll().stream()
                  .filter(event -> "call.started".equals(event.getEventType())))
          .hasSize(receiverActive ? 3 : 2);
    } finally {
      notifications.deleteAll();
      bindingRepository.deleteAll();
    }
  }

  @Test
  void oneBatchRetainsStartBeforeEndAndAnEndedCallDoesNotAnnounceToRestoredInvitees() {
    String source = "!started-terminal-source:example";
    String media = "!started-terminal-media:example";
    String caller = "@started-terminal-caller:example";
    String receiver = "@started-terminal-receiver:example";
    String late = "@started-terminal-late:example";
    long timestamp = System.currentTimeMillis() - 10;
    for (var user :
        List.of(
            startedUser("terminal-caller", caller),
            startedUser("terminal-receiver", receiver),
            startedUser("terminal-late", late))) {
      when(users.findByMatrixUserIdAndDeleteDateIsNull(user.getMatrixUserId()))
          .thenReturn(Optional.of(user));
    }
    var session = mock(Session.class);
    when(session.getId()).thenReturn(243L);
    when(session.getTenantId()).thenReturn(7L);
    when(sessions.findByMatrixRoomId(source)).thenReturn(Optional.of(session));
    when(matrix.getCallRoomMembers(source)).thenReturn(Optional.of(List.of(caller, receiver)));
    try {
      assertThat(
              callBindings.register(
                  de.caritas.cob.userservice.api.model.MatrixCallBinding.builder()
                      .sourceRoomId(source)
                      .callId("started-terminal")
                      .mediaRoomId(media)
                      .callerMatrixId(caller)
                      .sessionId(243L)
                      .tenantId(7L)
                      .invitedAt(timestamp - 100)
                      .inviteExpiresAt(timestamp + 60000)
                      .invitedMatrixIds(java.util.Set.of(receiver, late))
                      .build()))
          .isTrue();
      assertThat(
              lifecycle.handleRoom(
                  media,
                  Map.of(
                      "timeline",
                      Map.of(
                          "events",
                          List.of(
                              rtcMember(caller, "terminal-device", timestamp, false),
                              departure(caller, "terminal-device", timestamp, 0))))))
          .isTrue();
      var events = notifications.findAll();
      assertThat(events)
          .extracting(event -> event.getEventType())
          .containsExactlyInAnyOrder("call.started", "call.ended", "call.missed");
      var start =
          events.stream()
              .filter(event -> "call.started".equals(event.getEventType()))
              .findFirst()
              .orElseThrow();
      var end =
          events.stream()
              .filter(event -> "call.ended".equals(event.getEventType()))
              .findFirst()
              .orElseThrow();
      assertThat(start.getRecipientUserId()).isEqualTo("terminal-receiver");
      assertThat(end.getRecipientUserId()).isEqualTo("terminal-caller");
      assertThat(start.getCreateDate()).isBeforeOrEqualTo(end.getCreateDate());
      when(matrix.getCallRoomMembers(source))
          .thenReturn(Optional.of(List.of(caller, receiver, late)));
      lifecycle.handleRoom(
          media,
          Map.of(
              "state",
              Map.of(
                  "events",
                  List.of(
                      rtcMember(
                          late, "terminal-late-device", System.currentTimeMillis(), false)))));
      assertThat(notifications.findAll())
          .extracting(event -> event.getId())
          .containsExactlyInAnyOrderElementsOf(
              events.stream().map(event -> event.getId()).toList());
    } finally {
      notifications.deleteAll();
      bindingRepository.deleteAll();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("callAudienceLifecycleStatuses")
  void invitationAndTerminalAudienceRequireCurrentActiveLifecycle(
      String eventType, AccountInactivityService.Status status) {
    String source = "!active-audience-source:example";
    String media = "!active-audience-media:example";
    String caller = "@active-audience-caller:example";
    String receiver = "@active-audience-receiver:example";
    var owner = startedUser("active-audience-caller", caller);
    var recipient = mock(Consultant.class);
    when(recipient.getId()).thenReturn("active-audience-receiver");
    when(recipient.getTenantId()).thenReturn(7L);
    when(recipient.getMatrixUserId()).thenReturn(receiver);
    when(users.findByMatrixUserIdAndDeleteDateIsNull(caller)).thenReturn(Optional.of(owner));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(receiver))
        .thenReturn(Optional.of(recipient));
    var session = mock(Session.class);
    when(session.getId()).thenReturn(244L);
    when(session.getTenantId()).thenReturn(7L);
    when(session.getMatrixRoomId()).thenReturn(source);
    when(session.getUser()).thenReturn(owner);
    when(session.getConsultant()).thenReturn(recipient);
    when(sessions.findByMatrixRoomId(source)).thenReturn(Optional.of(session));
    when(matrix.getCallRoomMembers(source)).thenReturn(Optional.of(List.of(caller, receiver)));
    when(matrix.getCallRoomBinding(media, caller))
        .thenReturn(Optional.of(Map.of("call_id", "active-audience", "source_room_id", source)));
    when(matrix.ensureAdminInRoom(media, caller)).thenReturn(true);
    var invites = inviteService;
    long timestamp = System.currentTimeMillis() - 100;
    var invite =
        Map.<String, Object>of(
            "sender", caller,
            "origin_server_ts", timestamp,
            "content",
                Map.of("call_id", "active-audience", "call_room_id", media, "lifetime", 60000));
    try {
      if ("call.invited".equals(eventType)) setAccountLifecycleStatus(status);
      assertThat(invites.handle(source, invite)).isTrue();
      assertThat(bindingRepository.findByMediaRoomId(media)).isPresent();
      if (!"call.invited".equals(eventType)) {
        var attendance = new java.util.ArrayList<Map<String, Object>>();
        attendance.add(rtcMember(caller, "active-caller-device", timestamp, false));
        if ("call.ended".equals(eventType)) {
          attendance.add(rtcMember(receiver, "active-receiver-device", timestamp, false));
        }
        assertThat(lifecycle.handleRoom(media, Map.of("state", Map.of("events", attendance))))
            .isTrue();
        var existingHistory = notifications.findAll().stream().map(event -> event.getId()).toList();
        setAccountLifecycleStatus(status);
        var departures = new java.util.ArrayList<Map<String, Object>>();
        departures.add(departure(caller, "active-caller-device", timestamp, 0));
        if ("call.ended".equals(eventType)) {
          departures.add(departure(receiver, "active-receiver-device", timestamp, 0));
        }
        assertThat(lifecycle.handleRoom(media, Map.of("timeline", Map.of("events", departures))))
            .isTrue();
        assertThat(bindingRepository.findByMediaRoomId(media).orElseThrow().getEndedAt())
            .isNotNull();
        assertThat(notifications.findAll())
            .extracting(event -> event.getId())
            .containsAll(existingHistory);
        // A legacy account without a lifecycle row still receives the real terminal event.
        assertThat(notifications.findAll())
            .filteredOn(event -> "call.ended".equals(event.getEventType()))
            .extracting(event -> event.getRecipientUserId())
            .contains("active-audience-caller");
      }
      assertThat(notifications.findAll())
          .filteredOn(
              event ->
                  eventType.equals(event.getEventType())
                      && "active-audience-receiver".equals(event.getRecipientUserId()))
          .hasSize(status == AccountInactivityService.Status.ACTIVE ? 1 : 0);
      var history = notifications.findAll().stream().map(event -> event.getId()).toList();
      invites.handle(source, invite);
      lifecycle.handleRoom(media, Map.of());
      assertThat(notifications.findAll())
          .extracting(event -> event.getId())
          .containsExactlyInAnyOrderElementsOf(history);
    } finally {
      notifications.deleteAll();
      bindingRepository.deleteAll();
    }
  }

  private void setAccountLifecycleStatus(AccountInactivityService.Status status) {
    accountLifecycle.assignAtCreation(
        "active-audience-receiver", 7L, 12, 1L, java.time.Instant.now());
    new JdbcTemplate(accountDataSource)
        .update(
            "UPDATE account_inactivity SET status=? WHERE identity_id=?",
            status.name(),
            "active-audience-receiver");
    assertThat(accountLifecycle.snapshot("active-audience-receiver").orElseThrow().status())
        .isEqualTo(status);
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
      callAudienceLifecycleStatuses() {
    return java.util.stream.Stream.of("call.invited", "call.ended", "call.missed")
        .flatMap(
            event ->
                java.util.Arrays.stream(AccountInactivityService.Status.values())
                    .map(status -> org.junit.jupiter.params.provider.Arguments.of(event, status)));
  }

  private User startedUser(String id, String matrixId) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@synthetic.oriso.test")
        .matrixUserId(matrixId)
        .tenantId(7L)
        .build();
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

  private MatrixEmailSyncCursorStore freshEmailCursor() {
    var cursor = mock(MatrixEmailSyncCursorStore.class);
    when(cursor.readOrCreateActivation())
        .thenReturn(new MatrixEmailSyncCursorStore.Start(null, 0L));
    return cursor;
  }

  private MatrixEventListenerService fastRetryListener() {
    return new MatrixEventListenerService(
        matrix,
        mock(SessionService.class),
        mock(MobilePushNotificationService.class),
        notificationService,
        Optional.empty(),
        users,
        consultants,
        sessions,
        mock(ConsultantMessageStatService.class),
        inviteService,
        mock(
            de.caritas.cob.userservice.api.service.notification.AdviceSeekerReplyEmailService
                .class),
        freshEmailCursor(),
        mock(InternalChatEmailService.class)) {
      @Override
      void sleep(long millis) throws InterruptedException {
        super.sleep(Math.min(millis, 10));
      }
    };
  }

  private static Optional<List<String>> throwLookupUnavailable() {
    throw new MatrixSynapseService.CallLookupUnavailableException(
        "synthetic membership transport failure");
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
    when(matrix.getCallRoomMembers(sourceRoom))
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
                inviteService,
                mock(
                    de.caritas.cob.userservice.api.service.notification
                        .AdviceSeekerReplyEmailService.class),
                freshEmailCursor(),
                mock(InternalChatEmailService.class));
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
