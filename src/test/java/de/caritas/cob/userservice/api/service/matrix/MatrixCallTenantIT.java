package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.controller.MatrixCallStateController;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.mobilepushmessage.MobilePushNotificationService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationDeduplicationWriter;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.ConsultantMessageStatService;
import de.caritas.cob.userservice.api.tenant.TenantAspect;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Real domain repositories and tenant AOP exercised through the background sync loop. */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  TenantAspect.class,
  MatrixCallTenantIT.AopConfiguration.class,
  EventNotificationService.class,
  EventNotificationDeduplicationWriter.class,
  MatrixCallBindingService.class,
  MatrixCallBindingWriter.class,
  MatrixCallLifecycleService.class,
  MatrixCallConversationResolver.class,
  MatrixCallStateService.class,
  MatrixCallInviteNotificationService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatrixCallTenantIT {
  @TestConfiguration
  @EnableAspectJAutoProxy
  static class AopConfiguration {}

  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private SessionRepository sessions;
  @Autowired private EventNotificationRepository notifications;
  @Autowired private MatrixCallBindingRepository bindings;
  @Autowired private MatrixCallInviteNotificationService invites;
  @Autowired private MatrixCallStateService callStates;
  @Autowired private EventNotificationService notificationService;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private MatrixSynapseService matrix;
  @MockitoBean private IdentityTombstoneService tombstones;

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true"})
  void backgroundCallCompletesWithActualTenantFilteredDomainQueries(
      boolean transientMembershipFailure, boolean invitationAlreadyExpired) throws Exception {
    var transaction = new TransactionTemplate(transactionManager);
    String source = "!tenant-source:example";
    String media = "!tenant-media:example";
    String caller = "@tenant-caller:example";
    String receiver = "@tenant-receiver:example";
    TenantContext.setCurrentTenant(0L);
    Session session =
        transaction.execute(
            status -> {
              var owner = users.save(user("tenant-caller", caller));
              users.save(user("tenant-receiver", receiver));
              var outsider = user("tenant-outsider", "@tenant-outsider:example");
              outsider.setTenantId(8L);
              users.save(outsider);
              var conversation =
                  new Session(owner, 1, "12345", 1L, Session.SessionStatus.IN_PROGRESS, false);
              conversation.setTenantId(7L);
              conversation.setMatrixRoomId(source);
              conversation.setLanguageCode(LanguageCode.de);
              conversation.setIsConsultantDirectlySet(false);
              return sessions.save(conversation);
            });
    var listener =
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
            invites);
    try {
      // Control: this fixture must really enforce tenant isolation before testing the worker.
      TenantContext.setCurrentTenant(7L);
      assertThat(
              transaction.<Boolean>execute(
                  status -> sessions.findByMatrixRoomId(source).isPresent()))
          .isTrue();
      TenantContext.setCurrentTenant(8L);
      assertThat(
              transaction.<Boolean>execute(status -> sessions.findByMatrixRoomId(source).isEmpty()))
          .isTrue();
      TenantContext.setCurrentTenant(0L);
      when(matrix.getAdminToken()).thenReturn("synthetic-token");
      when(matrix.getMatrixApiUrl()).thenReturn("https://matrix.example");
      var memberReads = new java.util.concurrent.atomic.AtomicInteger();
      when(matrix.getRoomMembers(source))
          .thenAnswer(
              ignored -> {
                if (transientMembershipFailure && memberReads.incrementAndGet() == 2) {
                  throw new IllegalStateException("synthetic temporary membership outage");
                }
                return Optional.of(List.of(caller, receiver, "@tenant-outsider:example"));
              });
      when(matrix.getCallRoomBinding(media, caller))
          .thenReturn(Optional.of(Map.of("call_id", "tenant-call", "source_room_id", source)));
      when(matrix.ensureAdminInRoom(media, caller)).thenReturn(true);
      long now = System.currentTimeMillis();
      Map<String, Object> invite =
          Map.of(
              "type",
              "org.oriso.call.invite",
              "sender",
              caller,
              "event_id",
              "$tenant-invite",
              "origin_server_ts",
              invitationAlreadyExpired ? now - 90000 : now,
              "content",
              Map.of(
                  "call_id",
                  "tenant-call",
                  "call_room_id",
                  media,
                  "lifetime",
                  60000,
                  "is_video",
                  true));
      var response =
          new AtomicReference<Map<String, Object>>(
              Map.of(
                  "next_batch",
                  "joined",
                  "rooms",
                  Map.of(
                      "join",
                      Map.of(
                          source, Map.of("timeline", Map.of("events", List.of(invite))),
                          media,
                              Map.of(
                                  "state",
                                  Map.of(
                                      "events",
                                      List.of(
                                          member(caller, now, false),
                                          member(receiver, now, false))))))));
      when(matrix.makeMatrixRequest(any(), any(), any(), any()))
          .thenAnswer(
              call -> {
                String url = call.getArgument(0);
                if (transientMembershipFailure
                    && "joined".equals(response.get().get("next_batch"))
                    && url.contains("since=joined")) {
                  return Map.of("next_batch", "joined", "rooms", Map.of("join", Map.of()));
                }
                return response.get();
              });
      // The worker is a fresh thread: HTTP/main-thread tenant context is not inherited.
      listener.initialize();
      if (!invitationAlreadyExpired)
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(
                () ->
                    assertThat(notifications.findAll())
                        .anySatisfy(
                            event -> assertThat(event.getEventType()).isEqualTo("call.invited")));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(
                          bindings
                              .findBySourceRoomIdAndCallId(source, "tenant-call")
                              .map(binding -> binding.getStartedAt())
                              .orElse(null))
                      .isNotNull());
      if (invitationAlreadyExpired) assertThat(notifications.findAll()).isEmpty();
      response.set(
          Map.of(
              "next_batch",
              "left",
              "rooms",
              Map.of(
                  "join",
                  Map.of(
                      media,
                      Map.of(
                          "timeline",
                          Map.of(
                              "events",
                              List.of(
                                  member(caller, now + 1, true),
                                  member(receiver, now + 1, true))))))));
      await()
          .during(Duration.ofMillis(300))
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                var ended =
                    notifications.findAll().stream()
                        .filter(event -> "call.ended".equals(event.getEventType()))
                        .toList();
                assertThat(ended).hasSize(2);
                assertThat(notifications.findAll())
                    .extracting(event -> event.getRecipientUserId())
                    .doesNotContain("tenant-outsider");
                assertThat(ended)
                    .extracting(event -> event.getRecipientUserId())
                    .containsExactlyInAnyOrder("tenant-caller", "tenant-receiver");
                assertThat(ended)
                    .allSatisfy(
                        event -> {
                          assertThat(event.getTenantId()).isEqualTo(7L);
                          assertThat(event.getSourceSessionId()).isEqualTo(session.getId());
                        });
              });
      listener.shutdown();
      TenantContext.setCurrentTenant(7L);
      var actor = new AuthenticatedUser();
      actor.setUserId("tenant-receiver");
      actor.setTenantId(7L);
      for (int reload = 0; reload < 2; reload++) {
        var mvc =
            MockMvcBuilders.standaloneSetup(new MatrixCallStateController(callStates, actor))
                .build();
        mvc.perform(
                get("/matrix/calls/state")
                    .param("sourceRoomId", source)
                    .param("callId", "tenant-call"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.callId").value("tenant-call"))
            .andExpect(jsonPath("$.callRoomId").value(media))
            .andExpect(jsonPath("$.state").value("ended"))
            .andExpect(jsonPath("$.participantMatrixIds.length()").value(2))
            .andExpect(jsonPath("$.participantMatrixIds[0]").value(caller))
            .andExpect(jsonPath("$.participantMatrixIds[1]").value(receiver))
            .andExpect(jsonPath("$.startedAt").value(now))
            .andExpect(jsonPath("$.endedAt").value(now + 1))
            .andExpect(jsonPath("$.durationSeconds").value(0));
      }
      TenantContext.setCurrentTenant(8L);
      actor.setUserId("tenant-outsider");
      actor.setTenantId(8L);
      MockMvcBuilders.standaloneSetup(new MatrixCallStateController(callStates, actor))
          .build()
          .perform(
              get("/matrix/calls/state")
                  .param("sourceRoomId", source)
                  .param("callId", "tenant-call"))
          .andExpect(status().isNotFound());
    } finally {
      listener.shutdown();
      TenantContext.setCurrentTenant(0L);
      transaction.executeWithoutResult(
          status -> {
            notifications.deleteAll();
            bindings.deleteAll();
            sessions.deleteById(session.getId());
            users.deleteById("tenant-caller");
            users.deleteById("tenant-receiver");
            users.deleteById("tenant-outsider");
          });
      TenantContext.clear();
    }
  }

  private User user(String id, String matrixId) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@synthetic.oriso.test")
        .matrixUserId(matrixId)
        .tenantId(7L)
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .languageCode(LanguageCode.de)
        .build();
  }

  private Map<String, Object> member(String sender, long timestamp, boolean left) {
    return Map.of(
        "type",
        "org.matrix.msc3401.call.member",
        "sender",
        sender,
        "state_key",
        "_" + sender + "_device",
        "event_id",
        "$" + sender + timestamp,
        "origin_server_ts",
        timestamp,
        "content",
        left
            ? Map.of()
            : Map.of(
                "application",
                "m.call",
                "scope",
                "m.room",
                "call_id",
                "",
                "device_id",
                "device",
                "expires",
                60000,
                "focus_active",
                Map.of("type", "livekit"),
                "foci_preferred",
                List.of()));
  }
}
