package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.PrivacyEnvelope;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.ConsultantMessageStatService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Focused resilience tests for the Matrix sync loop bootstrap/backoff (hardening B1). These verify
 * the backoff schedule and that a transient null admin token does not permanently stop retrying.
 * They never sleep for real: the {@code sleep(long)} seam is stubbed to a no-op.
 */
@ExtendWith(MockitoExtension.class)
class MatrixEventListenerServiceTest {

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private SessionService sessionService;
  @Mock private LiveEventNotificationService liveEventNotificationService;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private RedisMessageMirrorService redisMessageMirrorService;
  @Mock private ConsultantMessageStatService consultantMessageStatService;

  private Logger logger;
  private ListAppender<ILoggingEvent> logAppender;

  private static final String MATRIX_ROOM_ID = "!room:matrix.oriso.org";
  private static final String SENDER_MATRIX_ID = "@asker:matrix.oriso.org";
  private static final String CONSULTANT_MATRIX_ID = "@consultant:matrix.oriso.org";
  private static final String ASKER_DOMAIN_ID = "asker-user-id";
  private static final String CONSULTANT_DOMAIN_ID = "consultant-id";

  @BeforeEach
  void setUpLogging() {
    logger = (Logger) LoggerFactory.getLogger(MatrixEventListenerService.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
    logger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void tearDownLogging() {
    logger.detachAppender(logAppender);
  }

  private MatrixEventListenerService newService() {
    return newService(Optional.empty());
  }

  private MatrixEventListenerService newService(Optional<RedisMessageMirrorService> mirror) {
    return new MatrixEventListenerService(
        matrixSynapseService,
        sessionService,
        liveEventNotificationService,
        eventNotificationService,
        mirror,
        userRepository,
        consultantRepository,
        sessionRepository,
        consultantMessageStatService);
  }

  private MatrixEventListenerService newServiceWithSyncExecutor() {
    var service = newService();
    wireSynchronousExecutor(service);
    return service;
  }

  private void wireSynchronousExecutor(MatrixEventListenerService service) {
    ExecutorService syncExecutor = mock(ExecutorService.class);
    lenient()
        .doAnswer(
            invocation -> {
              Runnable task = invocation.getArgument(0);
              task.run();
              return null;
            })
        .when(syncExecutor)
        .submit(any(Runnable.class));
    ReflectionTestUtils.setField(service, "executorService", syncExecutor);
  }

  @Test
  void initialize_shouldNotCreateRetryThreadsWhenListenerIsDisabled() {
    var service = newService();
    ReflectionTestUtils.setField(service, "eventListenerEnabled", false);

    service.initialize();

    assertThat(ReflectionTestUtils.getField(service, "executorService")).isNull();
    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void nextBackoffMillis_shouldFollowExponentialScheduleCappedAt60s() {
    assertThat(MatrixEventListenerService.nextBackoffMillis(5_000L)).isEqualTo(10_000L);
    assertThat(MatrixEventListenerService.nextBackoffMillis(10_000L)).isEqualTo(20_000L);
    assertThat(MatrixEventListenerService.nextBackoffMillis(20_000L)).isEqualTo(40_000L);
    assertThat(MatrixEventListenerService.nextBackoffMillis(40_000L)).isEqualTo(60_000L);
    // Capped: 40s doubles to 80s but is clamped to the 60s ceiling, and stays there.
    assertThat(MatrixEventListenerService.nextBackoffMillis(60_000L)).isEqualTo(60_000L);
  }

  @Test
  void backoffSchedule_startsAt5sAndCapsAt60s() {
    long current = MatrixEventListenerService.INITIAL_BACKOFF_MS;
    long[] expected = {5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L};

    assertThat(current).isEqualTo(expected[0]);
    for (int i = 1; i < expected.length; i++) {
      current = MatrixEventListenerService.nextBackoffMillis(current);
      assertThat(current).as("backoff step %d", i).isEqualTo(expected[i]);
    }
    assertThat(MatrixEventListenerService.MAX_BACKOFF_MS).isEqualTo(60_000L);
  }

  @Test
  void bootstrapAdminToken_shouldKeepRetryingUntilTokenBecomesAvailable() {
    // No-op sleep so the retry loop does not wait for real backoff.
    MatrixEventListenerService service =
        new MatrixEventListenerService(
            matrixSynapseService,
            sessionService,
            liveEventNotificationService,
            eventNotificationService,
            Optional.empty(),
            userRepository,
            consultantRepository,
            sessionRepository,
            consultantMessageStatService) {
          @Override
          void sleep(long millis) {
            // deterministic: never actually sleep in the test
          }
        };
    ReflectionTestUtils.setField(service, "running", true);
    var outboundHttpMetrics = mock(OutboundHttpMetrics.class);
    service.setOutboundHttpMetrics(outboundHttpMetrics);

    // Two transient failures (null) then a real token: the loop must not give up on the first null.
    when(matrixSynapseService.getAdminToken()).thenReturn(null, null, "admin-token-123");

    boolean acquired = (boolean) ReflectionTestUtils.invokeMethod(service, "bootstrapAdminToken");

    assertThat(acquired).isTrue();
    assertThat(ReflectionTestUtils.getField(service, "adminAccessToken"))
        .isEqualTo("admin-token-123");
    // Proves it did not stop after the first null: getAdminToken was polled repeatedly.
    verify(matrixSynapseService, atLeast(3)).getAdminToken();
    verify(outboundHttpMetrics, org.mockito.Mockito.times(2)).recordRetry("matrix", "admin-token");
  }

  @Test
  void bootstrapAdminToken_shouldStopWhenNotRunning() {
    MatrixEventListenerService service = newService();
    // running defaults to false -> the bootstrap loop must exit immediately without polling.
    boolean acquired = (boolean) ReflectionTestUtils.invokeMethod(service, "bootstrapAdminToken");

    assertThat(acquired).isFalse();
  }

  // ── classifyContent ────────────────────────────────────────────────────────

  static Stream<Arguments> knownMsgTypes() {
    return Stream.of(
        Arguments.of("m.text", "TEXT"),
        Arguments.of("m.image", "IMAGE"),
        Arguments.of("m.file", "FILE"),
        Arguments.of("m.audio", "AUDIO"),
        Arguments.of("m.video", "VIDEO"),
        Arguments.of("m.notice", "NOTICE"),
        Arguments.of("m.emote", "EMOTE"));
  }

  @ParameterizedTest
  @MethodSource("knownMsgTypes")
  void classifyContent_shouldMapKnownMsgTypesToContentClass(String msgtype, String expected) {
    // Notification privacy metadata must classify Matrix message kinds for the UI layer.
    var service = newService();
    assertThat(invokeClassifyContent(service, msgtype)).isEqualTo(expected);
  }

  @Test
  void classifyContent_shouldReturnUnknown_whenMsgtypeIsNull() {
    // Events without a msgtype still need a safe fallback label for logging.
    assertThat(invokeClassifyContent(newService(), null)).isEqualTo("UNKNOWN");
  }

  @Test
  void classifyContent_shouldReturnUnknown_whenMsgtypeIsBlank() {
    // Whitespace-only msgtypes must not be treated as a real content class.
    assertThat(invokeClassifyContent(newService(), "   ")).isEqualTo("UNKNOWN");
  }

  @Test
  void classifyContent_shouldReturnOther_whenMsgtypeIsUnrecognized() {
    // Future Matrix msgtypes must bucket into OTHER until explicitly supported.
    assertThat(invokeClassifyContent(newService(), "m.custom")).isEqualTo("OTHER");
  }

  // ── extractThreadRootId ────────────────────────────────────────────────────

  @Test
  void extractThreadRootId_shouldReturnNull_whenContentIsNull() {
    // Non-thread messages must not fabricate a thread root id.
    assertThat(invokeExtractThreadRootId(newService(), null)).isNull();
  }

  @Test
  void extractThreadRootId_shouldReturnNull_whenRelatesToMissing() {
    // Plain messages without m.relates_to are not thread replies.
    assertThat(invokeExtractThreadRootId(newService(), Map.of("body", "hi"))).isNull();
  }

  @Test
  void extractThreadRootId_shouldReturnNull_whenRelatesToIsNotAMap() {
    // Malformed relates_to payloads must be ignored rather than crashing the sync loop.
    assertThat(invokeExtractThreadRootId(newService(), Map.of("m.relates_to", "not-a-map")))
        .isNull();
  }

  @Test
  void extractThreadRootId_shouldReturnNull_whenRelTypeIsNotThread() {
    // Only m.thread relations identify a thread root for reply notifications.
    var content =
        contentMap("m.relates_to", Map.of("rel_type", "m.reference", "event_id", "$root"));
    assertThat(invokeExtractThreadRootId(newService(), content)).isNull();
  }

  @Test
  void extractThreadRootId_shouldReturnEventId_whenValidThreadRelation() {
    // Thread replies must carry the root event id so clients can open the correct thread.
    var content =
        contentMap("m.relates_to", Map.of("rel_type", "m.thread", "event_id", "$root-event"));
    assertThat(invokeExtractThreadRootId(newService(), content)).isEqualTo("$root-event");
  }

  // ── extractMessageBody ───────────────────────────────────────────────────────

  @Test
  void extractMessageBody_shouldReturnBody_whenPresent() {
    // Plain-text Matrix messages expose the user-visible text in the body field.
    assertThat(invokeExtractMessageBody(newService(), Map.of("body", "hello"))).isEqualTo("hello");
  }

  @Test
  void extractMessageBody_shouldReturnFormattedBody_whenBodyAbsent() {
    // Rich-text fallbacks must still yield readable text for debug mirroring.
    assertThat(invokeExtractMessageBody(newService(), Map.of("formatted_body", "<b>hello</b>")))
        .isEqualTo("<b>hello</b>");
  }

  @Test
  void extractMessageBody_shouldReturnThreadReplyMarker_whenOnlyRelatesToPresent() {
    // Thread-only payloads still need a traceable placeholder for the Redis debug mirror.
    var content = contentMap("m.relates_to", Map.of("event_id", "$parent"));
    assertThat(invokeExtractMessageBody(newService(), content)).isEqualTo("thread-reply:$parent");
  }

  @Test
  void extractMessageBody_shouldReturnNull_whenContentIsNull() {
    // Empty events must not NPE the sync handler.
    assertThat(invokeExtractMessageBody(newService(), null)).isNull();
  }

  // ── buildPrivacyEnvelope ─────────────────────────────────────────────────────

  @Test
  void buildPrivacyEnvelope_shouldFlagAttachment_whenMsgtypeIsImage() {
    // Image messages must be flagged as attachments without logging the binary payload.
    var envelope =
        invokeBuildPrivacyEnvelope(
            newService(),
            Map.of("event_id", "$e1", "origin_server_ts", 1_700_000_000_000L),
            "!room:matrix",
            "@sender:matrix",
            "m.image",
            Map.of("body", "photo"));
    assertThat(envelope.isHasAttachment()).isTrue();
    assertThat(envelope.getContentClass()).isEqualTo("IMAGE");
  }

  @Test
  void buildPrivacyEnvelope_shouldFlagAttachment_whenContentHasUrlKey() {
    // Encrypted uploads reference media via url even when msgtype is non-standard.
    var envelope =
        invokeBuildPrivacyEnvelope(
            newService(),
            Map.of("event_id", "$e2"),
            "!room:matrix",
            "@sender:matrix",
            "m.text",
            Map.of("body", "see file", "url", "mxc://matrix/file"));
    assertThat(envelope.isHasAttachment()).isTrue();
  }

  @Test
  void buildPrivacyEnvelope_shouldFlagAttachment_whenContentHasFileKey() {
    // File metadata objects also indicate an attachment for notification badges.
    var envelope =
        invokeBuildPrivacyEnvelope(
            newService(),
            Map.of("event_id", "$e3"),
            "!room:matrix",
            "@sender:matrix",
            "m.text",
            Map.of("body", "doc", "file", Map.of("mimetype", "application/pdf")));
    assertThat(envelope.isHasAttachment()).isTrue();
  }

  @Test
  void buildPrivacyEnvelope_shouldConvertNumericTimestamp() {
    // Matrix server timestamps are numeric epoch millis used for notification ordering.
    var envelope =
        invokeBuildPrivacyEnvelope(
            newService(),
            Map.of("event_id", "$e4", "origin_server_ts", 1_234_567_890L),
            "!room:matrix",
            "@sender:matrix",
            "m.text",
            Map.of("body", "hi"));
    assertThat(envelope.getTimestamp()).isEqualTo(1_234_567_890L);
  }

  @Test
  void buildPrivacyEnvelope_shouldHandleNullEventId() {
    // Partial events during sync must still produce a usable privacy envelope.
    var envelope =
        invokeBuildPrivacyEnvelope(
            newService(),
            new HashMap<String, Object>(),
            "!room:matrix",
            "@sender:matrix",
            "m.text",
            Map.of("body", "hi"));
    assertThat(envelope.getMessageId()).isNull();
    assertThat(envelope.getRoomId()).isEqualTo("!room:matrix");
  }

  // ── buildRecipientSet ────────────────────────────────────────────────────────

  private static Session sessionWithParticipants(User user, Consultant consultant) {
    var session = new Session();
    session.setUser(user);
    session.setConsultant(consultant);
    return session;
  }

  @Test
  void buildRecipientSet_shouldContainOnlyUser_whenConsultantMissing() {
    // Ask-only sessions must still notify the advice seeker of new messages.
    var user = new User();
    user.setUserId("user-1");
    assertThat(invokeBuildRecipientSet(newService(), sessionWithParticipants(user, null)))
        .containsExactly("user-1");
  }

  @Test
  void buildRecipientSet_shouldContainOnlyConsultant_whenUserMissing() {
    // Consultant-only edge cases must still route notifications to the assigned consultant.
    var consultant = new Consultant();
    consultant.setId("consultant-1");
    assertThat(invokeBuildRecipientSet(newService(), sessionWithParticipants(null, consultant)))
        .containsExactly("consultant-1");
  }

  @Test
  void buildRecipientSet_shouldContainBoth_whenUserAndConsultantPresent() {
    // Standard counselling sessions notify both participants except the sender.
    var user = new User();
    user.setUserId("user-1");
    var consultant = new Consultant();
    consultant.setId("consultant-1");
    assertThat(invokeBuildRecipientSet(newService(), sessionWithParticipants(user, consultant)))
        .containsExactlyInAnyOrder("user-1", "consultant-1");
  }

  @Test
  void buildRecipientSet_shouldIgnoreNullUser() {
    // Detached session graphs must not NPE when the user association is missing.
    var consultant = new Consultant();
    consultant.setId("consultant-1");
    assertThat(invokeBuildRecipientSet(newService(), sessionWithParticipants(null, consultant)))
        .containsExactly("consultant-1");
  }

  @Test
  void buildRecipientSet_shouldIgnoreNullConsultant() {
    // Sessions awaiting assignment must still notify the asker.
    var user = new User();
    user.setUserId("user-1");
    assertThat(invokeBuildRecipientSet(newService(), sessionWithParticipants(user, null)))
        .containsExactly("user-1");
  }

  // ── registerRoom / unregisterRoom ───────────────────────────────────────────

  @Test
  void registerRoom_shouldPopulateBothMaps_whenRoomIdValid() {
    // UI sync registration must index the room for session lookup and recipient fan-out.
    var service = newService();
    service.registerRoom(99L, "!room:matrix", Set.of("user-a", "user-b"));

    @SuppressWarnings("unchecked")
    var roomToSession =
        (Map<String, Long>) ReflectionTestUtils.getField(service, "roomToSessionMap");
    @SuppressWarnings("unchecked")
    var roomToUsers =
        (Map<String, Set<String>>) ReflectionTestUtils.getField(service, "roomToUsersMap");

    assertThat(roomToSession).containsEntry("!room:matrix", 99L);
    assertThat(roomToUsers).containsEntry("!room:matrix", Set.of("user-a", "user-b"));
  }

  @Test
  void registerRoom_shouldBeNoOp_whenRoomIdBlank() {
    // Invalid room ids must not pollute the in-memory registration indexes.
    var service = newService();
    service.registerRoom(1L, "", Set.of("user-a"));
    service.registerRoom(2L, null, Set.of("user-b"));

    @SuppressWarnings("unchecked")
    var roomToSession =
        (Map<String, Long>) ReflectionTestUtils.getField(service, "roomToSessionMap");
    assertThat(roomToSession).isEmpty();
  }

  @Test
  void unregisterRoom_shouldRemoveFromBothMaps() {
    // Ended sessions must stop generating live events for that Matrix room.
    var service = newService();
    service.registerRoom(5L, "!room:matrix", Set.of("user-a"));
    service.unregisterRoom("!room:matrix");

    @SuppressWarnings("unchecked")
    var roomToSession =
        (Map<String, Long>) ReflectionTestUtils.getField(service, "roomToSessionMap");
    @SuppressWarnings("unchecked")
    var roomToUsers =
        (Map<String, Set<String>>) ReflectionTestUtils.getField(service, "roomToUsersMap");

    assertThat(roomToSession).doesNotContainKey("!room:matrix");
    assertThat(roomToUsers).doesNotContainKey("!room:matrix");
  }

  @Test
  void registerRoom_shouldRemainConsistent_underConcurrentRegistration() throws Exception {
    // Multiple clients registering the same room must not corrupt the shared listener maps.
    var service = newService();
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(2);
    var executor = Executors.newFixedThreadPool(2);

    Runnable register =
        () -> {
          try {
            start.await();
            service.registerRoom(10L, "!room:concurrent", Set.of("user-x"));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        };

    executor.submit(register);
    executor.submit(register);
    start.countDown();
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    executor.shutdownNow();

    @SuppressWarnings("unchecked")
    var roomToSession =
        (ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(service, "roomToSessionMap");
    @SuppressWarnings("unchecked")
    var roomToUsers =
        (ConcurrentHashMap<String, Set<String>>)
            ReflectionTestUtils.getField(service, "roomToUsersMap");

    assertThat(roomToSession).containsEntry("!room:concurrent", 10L);
    assertThat(roomToUsers).containsKey("!room:concurrent");
    assertThat(roomToUsers.get("!room:concurrent")).contains("user-x");
  }

  @Test
  void unregisterRoom_shouldLeaveMapsEmpty_underConcurrentUnregister() throws Exception {
    // Concurrent unregister calls for the same room must not leave stale recipient entries.
    var service = newService();
    service.registerRoom(11L, "!room:gone", Set.of("user-y"));

    var start = new CountDownLatch(1);
    var done = new CountDownLatch(2);
    var executor = Executors.newFixedThreadPool(2);

    Runnable unregister =
        () -> {
          try {
            start.await();
            service.unregisterRoom("!room:gone");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        };

    executor.submit(unregister);
    executor.submit(unregister);
    start.countDown();
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    executor.shutdownNow();

    @SuppressWarnings("unchecked")
    var roomToSession =
        (Map<String, Long>) ReflectionTestUtils.getField(service, "roomToSessionMap");
    @SuppressWarnings("unchecked")
    var roomToUsers =
        (Map<String, Set<String>>) ReflectionTestUtils.getField(service, "roomToUsersMap");

    assertThat(roomToSession).isEmpty();
    assertThat(roomToUsers).isEmpty();
  }

  // ── safeContentLog ───────────────────────────────────────────────────────────

  @Test
  void safeContentLog_shouldNotThrow_whenEnvelopeIsNull() {
    // Privacy logging must tolerate missing metadata during malformed sync events.
    var service = newService();
    assertThatCode(() -> invokeSafeContentLog(service, "matrix.message.received", null))
        .doesNotThrowAnyException();
    assertThat(logAppender.list)
        .anyMatch(e -> e.getFormattedMessage().contains("matrix.message.received room=unknown"));
  }

  @Test
  void safeContentLog_shouldLogEnvelopeFields_whenPopulated() {
    // Operators need structured debug metadata without ever logging message plaintext.
    var service = newService();
    var envelope =
        PrivacyEnvelope.builder()
            .messageId("$evt")
            .roomId("!room:matrix")
            .senderId("@user:matrix")
            .timestamp(99L)
            .hasAttachment(true)
            .contentClass("TEXT")
            .build();

    invokeSafeContentLog(service, "matrix.message.received", envelope);

    assertThat(logAppender.list)
        .anyMatch(
            e ->
                e.getFormattedMessage().contains("room=!room:matrix")
                    && e.getFormattedMessage().contains("messageId=$evt")
                    && e.getFormattedMessage().contains("contentClass=TEXT")
                    && e.getFormattedMessage().contains("hasAttachment=true"));
  }

  // ── bootstrapAdminToken (exception path) ────────────────────────────────────

  @Test
  void bootstrapAdminToken_shouldRecoverAfterException_thenReturnToken() {
    MatrixEventListenerService service =
        new MatrixEventListenerService(
            matrixSynapseService,
            sessionService,
            liveEventNotificationService,
            eventNotificationService,
            Optional.empty(),
            userRepository,
            consultantRepository,
            sessionRepository,
            consultantMessageStatService) {
          @Override
          void sleep(long millis) {
            // no-op
          }
        };
    ReflectionTestUtils.setField(service, "running", true);

    when(matrixSynapseService.getAdminToken())
        .thenThrow(new RuntimeException("transient"))
        .thenReturn("admin-token-after-error");

    boolean acquired = (boolean) ReflectionTestUtils.invokeMethod(service, "bootstrapAdminToken");

    assertThat(acquired).isTrue();
    assertThat(ReflectionTestUtils.getField(service, "adminAccessToken"))
        .isEqualTo("admin-token-after-error");
    verify(matrixSynapseService, atLeast(2)).getAdminToken();
  }

  @Test
  void bootstrapAdminToken_shouldReturnFalse_whenInterruptedDuringBackoff() {
    MatrixEventListenerService service =
        new MatrixEventListenerService(
            matrixSynapseService,
            sessionService,
            liveEventNotificationService,
            eventNotificationService,
            Optional.empty(),
            userRepository,
            consultantRepository,
            sessionRepository,
            consultantMessageStatService) {
          @Override
          void sleep(long millis) throws InterruptedException {
            throw new InterruptedException("shutdown");
          }
        };
    ReflectionTestUtils.setField(service, "running", true);
    when(matrixSynapseService.getAdminToken()).thenReturn(null);

    boolean acquired = (boolean) ReflectionTestUtils.invokeMethod(service, "bootstrapAdminToken");

    assertThat(acquired).isFalse();
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    Thread.interrupted();
  }

  // ── shutdown ───────────────────────────────────────────────────────────────

  @Test
  void shutdown_shouldStopExecutorWithoutThrowing() {
    var service = newService();
    var executor = Executors.newSingleThreadExecutor();
    ReflectionTestUtils.setField(service, "executorService", executor);
    ReflectionTestUtils.setField(service, "running", true);

    assertThatCode(service::shutdown).doesNotThrowAnyException();
    assertThat(executor.isShutdown()).isTrue();
  }

  // ── resolveDomainUserIdFromMatrixUserId ────────────────────────────────────

  @Test
  void resolveDomainUserIdFromMatrixUserId_shouldReturnNull_whenMatrixUserIdBlank() {
    assertThat(invokeResolveDomainUserId(newService(), null)).isNull();
    assertThat(invokeResolveDomainUserId(newService(), "  ")).isNull();
    verifyNoInteractions(userRepository, consultantRepository);
  }

  @Test
  void resolveDomainUserIdFromMatrixUserId_shouldPreferUserRepository() {
    var user = new User();
    user.setUserId(ASKER_DOMAIN_ID);
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(SENDER_MATRIX_ID))
        .thenReturn(Optional.of(user));

    assertThat(invokeResolveDomainUserId(newService(), SENDER_MATRIX_ID))
        .isEqualTo(ASKER_DOMAIN_ID);
    verify(consultantRepository, never()).findByMatrixUserIdAndDeleteDateIsNull(anyString());
  }

  @Test
  void resolveDomainUserIdFromMatrixUserId_shouldFallBackToConsultantRepository() {
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_DOMAIN_ID);
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(consultant));

    assertThat(invokeResolveDomainUserId(newService(), CONSULTANT_MATRIX_ID))
        .isEqualTo(CONSULTANT_DOMAIN_ID);
    verify(consultantRepository, times(1))
        .findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID);
  }

  @Test
  void resolveDomainUserIdFromMatrixUserId_shouldReturnNull_whenUnknownMatrixUser() {
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(SENDER_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(SENDER_MATRIX_ID))
        .thenReturn(Optional.empty());

    assertThat(invokeResolveDomainUserId(newService(), SENDER_MATRIX_ID)).isNull();
  }

  // ── resolveSessionIdForRoom / getRecipientCandidatesForRoom ────────────────

  @Test
  void resolveSessionIdForRoom_shouldReturnCachedValue_whenAlreadyRegistered() {
    var service = newService();
    service.registerRoom(77L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID));

    assertThat(invokeResolveSessionIdForRoom(service, MATRIX_ROOM_ID)).contains(77L);
    verifyNoInteractions(sessionRepository);
  }

  @Test
  void resolveSessionIdForRoom_shouldLoadFromRepositoryAndCache_whenNotRegistered() {
    var service = newService();
    var session = sessionWithParticipants(userWithId(ASKER_DOMAIN_ID), null);
    session.setId(88L);
    when(sessionRepository.findByMatrixRoomId(MATRIX_ROOM_ID)).thenReturn(Optional.of(session));

    assertThat(invokeResolveSessionIdForRoom(service, MATRIX_ROOM_ID)).contains(88L);

    @SuppressWarnings("unchecked")
    var roomToSession =
        (Map<String, Long>) ReflectionTestUtils.getField(service, "roomToSessionMap");
    assertThat(roomToSession).containsEntry(MATRIX_ROOM_ID, 88L);
  }

  @Test
  void resolveSessionIdForRoom_shouldReturnEmpty_whenRoomUnknown() {
    when(sessionRepository.findByMatrixRoomId(MATRIX_ROOM_ID)).thenReturn(Optional.empty());

    assertThat(invokeResolveSessionIdForRoom(newService(), MATRIX_ROOM_ID)).isEmpty();
  }

  @Test
  void getRecipientCandidatesForRoom_shouldReturnCachedRecipients_whenRegistered() {
    var service = newService();
    service.registerRoom(1L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID, CONSULTANT_DOMAIN_ID));

    assertThat(invokeGetRecipientCandidatesForRoom(service, MATRIX_ROOM_ID))
        .containsExactlyInAnyOrder(ASKER_DOMAIN_ID, CONSULTANT_DOMAIN_ID);
    verifyNoInteractions(sessionRepository);
  }

  @Test
  void getRecipientCandidatesForRoom_shouldLoadFromRepository_whenCacheEmpty() {
    var service = newService();
    var session =
        sessionWithParticipants(
            userWithId(ASKER_DOMAIN_ID), consultantWithId(CONSULTANT_DOMAIN_ID));
    session.setId(5L);
    when(sessionRepository.findByMatrixRoomId(MATRIX_ROOM_ID)).thenReturn(Optional.of(session));

    assertThat(invokeGetRecipientCandidatesForRoom(service, MATRIX_ROOM_ID))
        .containsExactlyInAnyOrder(ASKER_DOMAIN_ID, CONSULTANT_DOMAIN_ID);
  }

  @Test
  void getRecipientCandidatesForRoom_shouldReturnEmpty_whenSessionNotFound() {
    when(sessionRepository.findByMatrixRoomId(MATRIX_ROOM_ID)).thenReturn(Optional.empty());

    assertThat(invokeGetRecipientCandidatesForRoom(newService(), MATRIX_ROOM_ID)).isEmpty();
  }

  // ── performMatrixSync ──────────────────────────────────────────────────────

  @Test
  void performMatrixSync_shouldAppendSinceToken_whenSyncTokenPresent() {
    var service = newService();
    ReflectionTestUtils.setField(service, "adminAccessToken", "admin-token");
    ReflectionTestUtils.setField(service, "syncToken", "s0");
    when(matrixSynapseService.getMatrixApiUrl()).thenReturn("https://matrix.example");
    when(matrixSynapseService.makeMatrixRequest(
            eq("https://matrix.example/_matrix/client/r0/sync?since=s0&timeout=30000"),
            eq("GET"),
            eq("admin-token"),
            eq(null)))
        .thenReturn(Map.of("next_batch", "s1"));

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "performMatrixSync");

    assertThat(result).containsEntry("next_batch", "s1");
    assertThat(ReflectionTestUtils.getField(service, "syncToken")).isEqualTo("s1");
  }

  @Test
  void performMatrixSync_shouldOmitSinceToken_whenSyncTokenNull() {
    var service = newService();
    ReflectionTestUtils.setField(service, "adminAccessToken", "admin-token");
    when(matrixSynapseService.getMatrixApiUrl()).thenReturn("https://matrix.example");
    when(matrixSynapseService.makeMatrixRequest(
            eq("https://matrix.example/_matrix/client/r0/sync?timeout=30000"),
            eq("GET"),
            eq("admin-token"),
            eq(null)))
        .thenReturn(Map.of());

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "performMatrixSync");

    assertThat(result).isNotNull();
    assertThat(ReflectionTestUtils.getField(service, "syncToken")).isNull();
  }

  @Test
  void performMatrixSync_shouldReturnNull_whenMatrixRequestThrows() {
    var service = newService();
    ReflectionTestUtils.setField(service, "adminAccessToken", "admin-token");
    when(matrixSynapseService.getMatrixApiUrl()).thenReturn("https://matrix.example");
    when(matrixSynapseService.makeMatrixRequest(anyString(), anyString(), anyString(), eq(null)))
        .thenThrow(new RuntimeException("sync failed"));

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "performMatrixSync");

    assertThat(result).isNull();
    assertThat(logAppender.list)
        .anyMatch(
            e ->
                e.getLevel().toString().equals("ERROR")
                    && e.getFormattedMessage().contains("Matrix sync failed"));
  }

  // ── processMatrixSyncEvents ────────────────────────────────────────────────

  @Test
  void processMatrixSyncEvents_shouldReturnEarly_whenSyncResultNull() {
    assertThatCode(() -> invokeProcessMatrixSyncEvents(newService(), null))
        .doesNotThrowAnyException();
    verifyNoInteractions(liveEventNotificationService);
  }

  @Test
  void processMatrixSyncEvents_shouldReturnEarly_whenRoomsKeyMissing() {
    invokeProcessMatrixSyncEvents(newService(), Map.of("next_batch", "s1"));
    verifyNoInteractions(liveEventNotificationService);
  }

  @Test
  void processMatrixSyncEvents_shouldReturnEarly_whenJoinKeyMissing() {
    invokeProcessMatrixSyncEvents(newService(), Map.of("rooms", Map.of()));
    verifyNoInteractions(liveEventNotificationService);
  }

  @Test
  void processMatrixSyncEvents_shouldSkipRoom_whenSessionCannotBeResolved() {
    var syncResult =
        syncResultWithEvents(
            MATRIX_ROOM_ID, List.of(messageEvent(SENDER_MATRIX_ID, "m.text", "hello", null)));
    when(sessionRepository.findByMatrixRoomId(MATRIX_ROOM_ID)).thenReturn(Optional.empty());

    invokeProcessMatrixSyncEvents(newService(), syncResult);

    verifyNoInteractions(liveEventNotificationService);
  }

  @Test
  void processMatrixSyncEvents_shouldTriggerDirectMessageNotification_onRegisteredRoomMessage() {
    var service = newServiceWithSyncExecutor();
    service.registerRoom(10L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID, CONSULTANT_DOMAIN_ID));

    var sender = consultantWithId(CONSULTANT_DOMAIN_ID);
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(sender));

    var syncResult =
        syncResultWithEvents(
            MATRIX_ROOM_ID,
            List.of(
                messageEvent(CONSULTANT_MATRIX_ID, "m.text", "hello counsellor", "$evt-direct")));

    invokeProcessMatrixSyncEvents(service, syncResult);

    verify(liveEventNotificationService).sendLiveDirectMessageEventToUsers(MATRIX_ROOM_ID);
    verify(eventNotificationService)
        .createMessageNotificationFromRoom(
            eq(MATRIX_ROOM_ID), eq(CONSULTANT_DOMAIN_ID), eq(true), any(PrivacyEnvelope.class));
    verify(eventNotificationService, never())
        .createThreadReplyNotificationFromRoom(
            anyString(), any(), anyString(), anyBoolean(), any());
    verify(consultantMessageStatService).recordMessageSent(CONSULTANT_DOMAIN_ID, 10L);
    verify(consultantRepository, times(1))
        .findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID);
  }

  @Test
  void processMatrixSyncEvents_shouldTriggerThreadReplyNotification_whenThreadRelationPresent() {
    var service = newServiceWithSyncExecutor();
    service.registerRoom(11L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID, CONSULTANT_DOMAIN_ID));

    var user = userWithId(ASKER_DOMAIN_ID);
    user.setMatrixUserId(SENDER_MATRIX_ID);
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(SENDER_MATRIX_ID))
        .thenReturn(Optional.of(user));

    var content = new HashMap<String, Object>();
    content.put("msgtype", "m.text");
    content.put("body", "thread reply");
    content.put("m.relates_to", Map.of("rel_type", "m.thread", "event_id", "$root-thread"));

    var syncResult =
        syncResultWithEvents(
            MATRIX_ROOM_ID,
            List.of(messageEventWithContent(SENDER_MATRIX_ID, content, "$evt-thread")));

    invokeProcessMatrixSyncEvents(service, syncResult);

    verify(eventNotificationService)
        .createThreadReplyNotificationFromRoom(
            eq(MATRIX_ROOM_ID),
            eq(ASKER_DOMAIN_ID),
            eq("$root-thread"),
            eq(true),
            any(PrivacyEnvelope.class));
    verify(eventNotificationService, never())
        .createMessageNotificationFromRoom(anyString(), any(), anyBoolean(), any());
    verify(consultantMessageStatService, never()).recordMessageSent(any(), any());
  }

  @Test
  void processMatrixSyncEvents_shouldMirrorMessage_whenRedisMirrorPresent() {
    var service = newService(Optional.of(redisMessageMirrorService));
    wireSynchronousExecutor(service);
    service.registerRoom(12L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID));

    var user = userWithId(ASKER_DOMAIN_ID);
    user.setMatrixUserId(SENDER_MATRIX_ID);
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(SENDER_MATRIX_ID))
        .thenReturn(Optional.of(user));

    var syncResult =
        syncResultWithEvents(
            MATRIX_ROOM_ID,
            List.of(messageEvent(SENDER_MATRIX_ID, "m.text", "mirror me", "$evt-m")));

    invokeProcessMatrixSyncEvents(service, syncResult);

    verify(redisMessageMirrorService)
        .mirrorOutgoingMessage(
            eq(12L),
            eq(MATRIX_ROOM_ID),
            eq(SENDER_MATRIX_ID),
            eq(false),
            eq("mirror me"),
            eq("$evt-m"));
  }

  @Test
  void processMatrixSyncEvents_shouldNotNotify_whenSenderIsOnlyRecipient() {
    var service = newServiceWithSyncExecutor();
    service.registerRoom(13L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID));

    var user = userWithId(ASKER_DOMAIN_ID);
    user.setMatrixUserId(SENDER_MATRIX_ID);
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(SENDER_MATRIX_ID))
        .thenReturn(Optional.of(user));

    var syncResult =
        syncResultWithEvents(
            MATRIX_ROOM_ID, List.of(messageEvent(SENDER_MATRIX_ID, "m.text", "solo", "$evt-solo")));

    invokeProcessMatrixSyncEvents(service, syncResult);

    verify(liveEventNotificationService, never()).sendLiveDirectMessageEventToUsers(anyString());
    verify(eventNotificationService, never())
        .createMessageNotificationFromRoom(anyString(), any(), anyBoolean(), any());
  }

  @Test
  void processMatrixSyncEvents_shouldNotNotify_whenMessageContentIsNull() {
    var service = newServiceWithSyncExecutor();
    service.registerRoom(14L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID, CONSULTANT_DOMAIN_ID));

    var event = new HashMap<String, Object>();
    event.put("type", "m.room.message");
    event.put("sender", CONSULTANT_MATRIX_ID);
    event.put("event_id", "$evt-empty");
    event.put("content", null);

    invokeProcessMatrixSyncEvents(service, syncResultWithEvents(MATRIX_ROOM_ID, List.of(event)));

    verifyNoInteractions(liveEventNotificationService, eventNotificationService);
  }

  @Test
  void processMatrixSyncEvents_shouldReturnEarly_whenJoinedRoomsNull() {
    var rooms = new HashMap<String, Object>();
    rooms.put("join", null);
    var syncResult = new HashMap<String, Object>();
    syncResult.put("rooms", rooms);

    invokeProcessMatrixSyncEvents(newService(), syncResult);

    verifyNoInteractions(liveEventNotificationService);
  }

  @Test
  void processMatrixSyncEvents_shouldSkipRoom_whenTimelineMissing() {
    var service = newServiceWithSyncExecutor();
    when(sessionRepository.findByMatrixRoomId(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(sessionWithId(15L)));

    var roomData = Map.<String, Object>of();
    var join = new HashMap<String, Object>();
    join.put(MATRIX_ROOM_ID, roomData);
    var rooms = new HashMap<String, Object>();
    rooms.put("join", join);
    var syncResult = new HashMap<String, Object>();
    syncResult.put("rooms", rooms);

    invokeProcessMatrixSyncEvents(service, syncResult);

    verifyNoInteractions(liveEventNotificationService);
  }

  @Test
  void processMatrixSyncEvents_shouldResolveSessionFromRepository_whenRoomNotPreRegistered() {
    var service = newServiceWithSyncExecutor();
    var session =
        sessionWithParticipants(
            userWithId(ASKER_DOMAIN_ID), consultantWithId(CONSULTANT_DOMAIN_ID));
    session.setId(16L);
    when(sessionRepository.findByMatrixRoomId(MATRIX_ROOM_ID)).thenReturn(Optional.of(session));

    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(consultantWithId(CONSULTANT_DOMAIN_ID)));

    var syncResult =
        syncResultWithEvents(
            MATRIX_ROOM_ID,
            List.of(messageEvent(CONSULTANT_MATRIX_ID, "m.text", "from repo", "$evt-repo")));

    invokeProcessMatrixSyncEvents(service, syncResult);

    verify(sessionRepository).findByMatrixRoomId(MATRIX_ROOM_ID);
    verify(liveEventNotificationService).sendLiveDirectMessageEventToUsers(MATRIX_ROOM_ID);
  }

  // ── processMatrixEvent (call events) ───────────────────────────────────────

  @Test
  void processMatrixEvent_shouldIgnoreUnknownEventTypes() {
    var service = newService();
    var event = Map.<String, Object>of("type", "m.room.member", "sender", SENDER_MATRIX_ID);

    assertThatCode(() -> invokeProcessMatrixEvent(service, MATRIX_ROOM_ID, event))
        .doesNotThrowAnyException();
    verifyNoInteractions(liveEventNotificationService);
  }

  @Test
  void processMatrixEvent_shouldReturnEarly_whenEventTypeNull() {
    invokeProcessMatrixEvent(newService(), MATRIX_ROOM_ID, new HashMap<>());
    verifyNoInteractions(liveEventNotificationService);
  }

  @Test
  void handleCallInvite_shouldWarnAboutUnimplementedLiveEvent_whenRecipientsExist() {
    var service = newService();
    service.registerRoom(20L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID, CONSULTANT_DOMAIN_ID));

    var event = new HashMap<String, Object>();
    event.put("type", "m.call.invite");
    event.put("sender", CONSULTANT_MATRIX_ID);
    event.put("content", Map.of("call_id", "call-1", "lifetime", 30_000));

    invokeProcessMatrixEvent(service, MATRIX_ROOM_ID, event);

    assertThat(logAppender.list)
        .anyMatch(
            e ->
                e.getLevel().toString().equals("WARN")
                    && e.getFormattedMessage()
                        .contains("videoCallRequest live event not yet implemented"));
  }

  @Test
  void handleCallInvite_shouldReturnEarly_whenContentNull() {
    var service = newService();
    service.registerRoom(21L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID));

    var event = new HashMap<String, Object>();
    event.put("type", "m.call.invite");
    event.put("sender", CONSULTANT_MATRIX_ID);

    invokeProcessMatrixEvent(service, MATRIX_ROOM_ID, event);

    assertThat(logAppender.list)
        .noneMatch(e -> e.getFormattedMessage().contains("videoCallRequest live event"));
  }

  @Test
  void handleCallInvite_shouldReturnEarly_whenNoRegisteredRecipients() {
    var service = newService();
    var event = new HashMap<String, Object>();
    event.put("type", "m.call.invite");
    event.put("sender", CONSULTANT_MATRIX_ID);
    event.put("content", Map.of("call_id", "call-2", "lifetime", 10_000));

    invokeProcessMatrixEvent(service, MATRIX_ROOM_ID, event);

    assertThat(logAppender.list)
        .noneMatch(e -> e.getFormattedMessage().contains("videoCallRequest live event"));
  }

  @Test
  void handleCallAnswerAndHangup_shouldLogWithoutThrowing() {
    var service = newService();

    var answer = Map.<String, Object>of("type", "m.call.answer", "sender", CONSULTANT_MATRIX_ID);
    var hangup = Map.<String, Object>of("type", "m.call.hangup", "sender", CONSULTANT_MATRIX_ID);

    assertThatCode(() -> invokeProcessMatrixEvent(service, MATRIX_ROOM_ID, answer))
        .doesNotThrowAnyException();
    assertThatCode(() -> invokeProcessMatrixEvent(service, MATRIX_ROOM_ID, hangup))
        .doesNotThrowAnyException();

    assertThat(logAppender.list).anyMatch(e -> e.getFormattedMessage().contains("Call answered"));
    assertThat(logAppender.list).anyMatch(e -> e.getFormattedMessage().contains("Call ended"));
  }

  @Test
  void handleRoomMessage_shouldSwallowNotificationErrors_withoutBreakingSync() {
    var service = newServiceWithSyncExecutor();
    service.registerRoom(30L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID, CONSULTANT_DOMAIN_ID));

    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(consultantWithId(CONSULTANT_DOMAIN_ID)));

    org.mockito.Mockito.doThrow(new RuntimeException("live down"))
        .when(liveEventNotificationService)
        .sendLiveDirectMessageEventToUsers(MATRIX_ROOM_ID);

    var syncResult =
        syncResultWithEvents(
            MATRIX_ROOM_ID,
            List.of(messageEvent(CONSULTANT_MATRIX_ID, "m.text", "boom", "$evt-err")));

    assertThatCode(() -> invokeProcessMatrixSyncEvents(service, syncResult))
        .doesNotThrowAnyException();
    assertThat(logAppender.list)
        .anyMatch(
            e ->
                e.getLevel().toString().equals("ERROR")
                    && e.getFormattedMessage().contains("Failed to send LiveService notification"));
  }

  @Test
  void handleRoomMessage_shouldStillPersistNotificationAndStatistic_whenLiveOrPushSendFails() {
    // The live STOMP push is an optimisation; the persisted feed entry is the
    // source of truth for the Zeitstrahl. A live-send failure (e.g. the
    // request-scoped AuthenticatedUser being unavailable on the sync-loop
    // thread) must not prevent the notification row from being written.
    var service = newServiceWithSyncExecutor();
    service.registerRoom(33L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID, CONSULTANT_DOMAIN_ID));

    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(consultantWithId(CONSULTANT_DOMAIN_ID)));

    org.mockito.Mockito.doThrow(new IllegalStateException("mobile push failed"))
        .when(liveEventNotificationService)
        .sendLiveDirectMessageEventToUsers(MATRIX_ROOM_ID);

    var event = messageEvent(CONSULTANT_MATRIX_ID, "m.text", "hello", "$evt-live-or-push-down");
    invokeProcessMatrixEvent(service, MATRIX_ROOM_ID, event);

    verify(eventNotificationService)
        .createMessageNotificationFromRoom(
            eq(MATRIX_ROOM_ID), eq(CONSULTANT_DOMAIN_ID), eq(true), any(PrivacyEnvelope.class));
    verify(consultantMessageStatService).recordMessageSent(CONSULTANT_DOMAIN_ID, 33L);
  }

  @Test
  void buildRecipientSet_shouldIgnoreParticipantsWithNullIds() {
    var user = mock(User.class);
    when(user.getUserId()).thenReturn(null);
    var consultant = mock(Consultant.class);
    when(consultant.getId()).thenReturn(null);

    assertThat(invokeBuildRecipientSet(newService(), sessionWithParticipants(user, consultant)))
        .isEmpty();
  }

  @Test
  void extractThreadRootId_shouldReturnNull_whenThreadEventIdMissing() {
    var content = contentMap("m.relates_to", Map.of("rel_type", "m.thread"));
    assertThat(invokeExtractThreadRootId(newService(), content)).isNull();
  }

  @Test
  void extractMessageBody_shouldPreferBody_overFormattedBody() {
    var content = new HashMap<String, Object>();
    content.put("body", "plain");
    content.put("formatted_body", "<b>rich</b>");
    assertThat(invokeExtractMessageBody(newService(), content)).isEqualTo("plain");
  }

  @Test
  void buildPrivacyEnvelope_shouldClassifyFileAudioVideoAttachmentTypes() {
    assertThat(
            invokeBuildPrivacyEnvelope(
                newService(),
                Map.of("event_id", "$f"),
                MATRIX_ROOM_ID,
                SENDER_MATRIX_ID,
                "m.file",
                Map.of("body", "doc")))
        .satisfies(
            e -> {
              assertThat(e.getContentClass()).isEqualTo("FILE");
              assertThat(e.isHasAttachment()).isTrue();
            });
    assertThat(
            invokeBuildPrivacyEnvelope(
                newService(),
                Map.of("event_id", "$a"),
                MATRIX_ROOM_ID,
                SENDER_MATRIX_ID,
                "m.audio",
                Map.of("body", "voice")))
        .satisfies(
            e -> {
              assertThat(e.getContentClass()).isEqualTo("AUDIO");
              assertThat(e.isHasAttachment()).isTrue();
            });
  }

  // ── processMatrixEvent (m.room.encrypted — E2EE rooms) ─────────────────────

  @Test
  void processMatrixEvent_shouldTreatEncryptedEventAsMessage_forE2eeRooms() {
    var service = newServiceWithSyncExecutor();
    service.registerRoom(31L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID, CONSULTANT_DOMAIN_ID));

    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(SENDER_MATRIX_ID))
        .thenReturn(Optional.of(userWithId(ASKER_DOMAIN_ID)));

    var event = new HashMap<String, Object>();
    event.put("type", "m.room.encrypted");
    event.put("sender", SENDER_MATRIX_ID);
    event.put("event_id", "$enc-1");
    // Encrypted events carry no msgtype/body — only the opaque Megolm payload.
    event.put(
        "content",
        Map.of(
            "algorithm", "m.megolm.v1.aes-sha2",
            "ciphertext", "opaque-payload",
            "session_id", "megolm-session",
            "device_id", "SENDERDEVICE"));

    invokeProcessMatrixEvent(service, MATRIX_ROOM_ID, event);

    verify(liveEventNotificationService).sendLiveDirectMessageEventToUsers(MATRIX_ROOM_ID);
    verify(eventNotificationService)
        .createMessageNotificationFromRoom(
            eq(MATRIX_ROOM_ID), eq(ASKER_DOMAIN_ID), eq(true), any(PrivacyEnvelope.class));
  }

  @Test
  void processMatrixEvent_shouldIgnoreEncryptedEvent_whenContentIsNull() {
    var service = newServiceWithSyncExecutor();
    service.registerRoom(32L, MATRIX_ROOM_ID, Set.of(ASKER_DOMAIN_ID, CONSULTANT_DOMAIN_ID));

    var event = new HashMap<String, Object>();
    event.put("type", "m.room.encrypted");
    event.put("sender", SENDER_MATRIX_ID);

    invokeProcessMatrixEvent(service, MATRIX_ROOM_ID, event);

    verifyNoInteractions(liveEventNotificationService);
    verifyNoInteractions(eventNotificationService);
  }

  private static Session sessionWithId(long sessionId) {
    var session = new Session();
    session.setId(sessionId);
    return session;
  }

  private static User userWithId(String userId) {
    var user = new User();
    user.setUserId(userId);
    return user;
  }

  private static Consultant consultantWithId(String consultantId) {
    var consultant = new Consultant();
    consultant.setId(consultantId);
    return consultant;
  }

  private static Map<String, Object> messageEvent(
      String sender, String msgtype, String body, String eventId) {
    var content = new HashMap<String, Object>();
    content.put("msgtype", msgtype);
    content.put("body", body);
    return messageEventWithContent(sender, content, eventId);
  }

  private static Map<String, Object> messageEventWithContent(
      String sender, Map<String, Object> content, String eventId) {
    var event = new HashMap<String, Object>();
    event.put("type", "m.room.message");
    event.put("sender", sender);
    event.put("content", content);
    if (eventId != null) {
      event.put("event_id", eventId);
    }
    return event;
  }

  private static Map<String, Object> syncResultWithEvents(
      String roomId, List<Map<String, Object>> events) {
    var timeline = Map.<String, Object>of("events", events);
    var roomData = Map.<String, Object>of("timeline", timeline);
    var join = Map.<String, Object>of(roomId, roomData);
    var rooms = Map.<String, Object>of("join", join);
    return Map.of("rooms", rooms);
  }

  private static void invokeProcessMatrixSyncEvents(
      MatrixEventListenerService service, Map<String, Object> syncResult) {
    ReflectionTestUtils.invokeMethod(service, "processMatrixSyncEvents", syncResult);
  }

  private static void invokeProcessMatrixEvent(
      MatrixEventListenerService service, String roomId, Map<String, Object> event) {
    ReflectionTestUtils.invokeMethod(service, "processMatrixEvent", roomId, event);
  }

  private static Optional<Long> invokeResolveSessionIdForRoom(
      MatrixEventListenerService service, String roomId) {
    return (Optional<Long>)
        ReflectionTestUtils.invokeMethod(service, "resolveSessionIdForRoom", roomId);
  }

  @SuppressWarnings("unchecked")
  private static Set<String> invokeGetRecipientCandidatesForRoom(
      MatrixEventListenerService service, String roomId) {
    return (Set<String>)
        ReflectionTestUtils.invokeMethod(service, "getRecipientCandidatesForRoom", roomId);
  }

  private static String invokeResolveDomainUserId(
      MatrixEventListenerService service, String matrixUserId) {
    return (String)
        ReflectionTestUtils.invokeMethod(
            service, "resolveDomainUserIdFromMatrixUserId", matrixUserId);
  }

  private static Map<String, Object> contentMap(String key, Object value) {
    Map<String, Object> content = new HashMap<>();
    content.put(key, value);
    return content;
  }

  private static String invokeClassifyContent(MatrixEventListenerService service, String msgtype) {
    return (String) ReflectionTestUtils.invokeMethod(service, "classifyContent", msgtype);
  }

  private static String invokeExtractThreadRootId(
      MatrixEventListenerService service, Map<String, Object> content) {
    return (String) ReflectionTestUtils.invokeMethod(service, "extractThreadRootId", content);
  }

  private static String invokeExtractMessageBody(
      MatrixEventListenerService service, Map<String, Object> content) {
    return (String) ReflectionTestUtils.invokeMethod(service, "extractMessageBody", content);
  }

  private static PrivacyEnvelope invokeBuildPrivacyEnvelope(
      MatrixEventListenerService service,
      Map<String, Object> event,
      String roomId,
      String senderId,
      String msgtype,
      Map<String, Object> content) {
    return (PrivacyEnvelope)
        ReflectionTestUtils.invokeMethod(
            service, "buildPrivacyEnvelope", event, roomId, senderId, msgtype, content);
  }

  @SuppressWarnings("unchecked")
  private static Set<String> invokeBuildRecipientSet(
      MatrixEventListenerService service, Session session) {
    return (Set<String>) ReflectionTestUtils.invokeMethod(service, "buildRecipientSet", session);
  }

  private static void invokeSafeContentLog(
      MatrixEventListenerService service, String marker, PrivacyEnvelope envelope) {
    ReflectionTestUtils.invokeMethod(service, "safeContentLog", marker, envelope);
  }
}
