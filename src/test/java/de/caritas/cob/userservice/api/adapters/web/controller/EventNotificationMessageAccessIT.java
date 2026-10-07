package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Real controller, permission service, producer and committed storage; only auth/Matrix edges
 * mocked.
 */
@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EventNotificationMessageAccessIT {
  @Autowired private EventNotificationController controller;
  @Autowired private SessionRepository sessions;
  @Autowired private SessionService sessionAccess;
  @Autowired private ConsultantRepository consultants;
  @Autowired private SessionSupervisorRepository supervisors;
  @Autowired private CaseHandoverRequestRepository handovers;
  private final List<Long> supervisionIds = new ArrayList<>();
  private final List<Long> handoverIds = new ArrayList<>();
  private String readActorId;
  private Long originalReadActorTenant;
  private boolean originalReadActorTeam;
  private boolean originalSessionTeam;
  private Long originalSessionTenant;
  private Long originalConsultantTenant;
  @Autowired private EventNotificationRepository notifications;
  @Autowired private ReplyEmailDeliveryRepository mailIntents;
  @MockitoBean private AuthenticatedUser caller;
  @MockitoBean private MatrixSynapseService matrix;
  private String eventId;

  @BeforeEach
  void setUp() {
    TenantContext.setCurrentTenant(1L);
    eventId = "$current-access-" + UUID.randomUUID();
    var session = sessions.findById(1L).orElseThrow();
    originalSessionTenant = session.getTenantId();
    originalSessionTeam = session.isTeamSession();
    originalConsultantTenant = session.getConsultant().getTenantId();
    var actor = consultants.findById(session.getConsultant().getId()).orElseThrow();
    actor.setTenantId(1L);
    consultants.saveAndFlush(actor);
    session.setTenantId(1L);
    sessions.save(session);
    when(caller.getUserId()).thenReturn("236b97bf-6cd7-434a-83f3-0a0b129dd45a");
    when(caller.getRoles()).thenReturn(Set.of("user"));
    when(caller.getTenantId()).thenReturn(1L);
    when(caller.isConsultant()).thenReturn(false);
  }

  @AfterEach
  void cleanOnlyOwnEvidence() {
    handovers.deleteAllById(handoverIds);
    supervisors.deleteAllById(supervisionIds);
    if (readActorId != null) {
      var reader = consultants.findById(readActorId).orElseThrow();
      reader.setTenantId(originalReadActorTenant);
      reader.setTeamConsultant(originalReadActorTeam);
      consultants.saveAndFlush(reader);
    }
    notifications.deleteAll(
        notifications.findAll().stream()
            .filter(
                row ->
                    row.getDeduplicationKey() != null
                        && row.getDeduplicationKey().contains(eventId))
            .toList());
    var session = sessions.findById(1L).orElseThrow();
    var actor = consultants.findById(session.getConsultant().getId()).orElseThrow();
    actor.setTenantId(originalConsultantTenant);
    consultants.saveAndFlush(actor);
    session.setTenantId(originalSessionTenant);
    session.setTeamSession(originalSessionTeam);
    sessions.save(session);
    TenantContext.clear();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unrelatedAuthenticatedAskerCannotPublishPrivateOrThreadEvents(boolean thread) {
    var session = sessions.findById(1L).orElseThrow();
    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId(session.getMatrixRoomId());
    request.setMatrixEventId(eventId);
    request.setMessagePreview("private message");
    if (thread) request.setThreadRootId("$thread-root");

    boolean denied = false;
    try {
      controller.createMessageEventNotification(request);
    } catch (ForbiddenException expected) {
      denied = true;
    }

    assertThat(
            notifications.findAll().stream()
                .filter(
                    row ->
                        row.getDeduplicationKey() != null
                            && row.getDeduplicationKey().contains(eventId)))
        .as("unrelated caller must produce no committed recipient feed rows")
        .isEmpty();
    assertThat(mailIntents.findAll().stream().filter(row -> eventId.equals(row.getSourceEventId())))
        .as("unrelated caller must reserve no mail intent")
        .isEmpty();
    assertThat(denied).as("current session access must reject the unrelated caller").isTrue();
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void currentPrimaryPartiesCanPublishPrivateAndThreadEvents(boolean consultant, boolean thread) {
    var session = sessions.findById(1L).orElseThrow();
    when(caller.getUserId())
        .thenReturn(consultant ? session.getConsultant().getId() : session.getUser().getUserId());
    when(caller.getRoles()).thenReturn(Set.of(consultant ? "consultant" : "user"));
    when(caller.isConsultant()).thenReturn(consultant);
    var request = messageRequest(thread);

    assertThat(controller.createMessageEventNotification(request).getStatusCode().value())
        .isEqualTo(204);
    assertThat(
            notifications.findAll().stream()
                .filter(
                    row ->
                        row.getDeduplicationKey() != null
                            && row.getDeduplicationKey().contains(eventId)))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getRecipientUserId())
                  .isEqualTo(
                      consultant ? session.getUser().getUserId() : session.getConsultant().getId());
              assertThat(row.getEventType()).isEqualTo(thread ? "thread.reply.new" : "message.new");
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void matchingAccountIdCannotPublishUnderAnotherTenant(boolean thread) {
    var session = sessions.findById(1L).orElseThrow();
    when(caller.getUserId()).thenReturn(session.getUser().getUserId());
    when(caller.getTenantId()).thenReturn(2L);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> controller.createMessageEventNotification(messageRequest(thread)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(
            notifications.findAll().stream()
                .filter(
                    row ->
                        row.getDeduplicationKey() != null
                            && row.getDeduplicationKey().contains(eventId)))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void legacyTenantOnePrimaryPartiesRetainTheirExistingWriteAccess(boolean consultant) {
    var session = sessions.findById(1L).orElseThrow();
    var actor = consultants.findById(session.getConsultant().getId()).orElseThrow();
    actor.setTenantId(null);
    consultants.saveAndFlush(actor);
    session.setTenantId(null);
    sessions.save(session);
    when(caller.getUserId()).thenReturn(consultant ? actor.getId() : session.getUser().getUserId());
    when(caller.getRoles()).thenReturn(Set.of(consultant ? "consultant" : "user"));
    when(caller.isConsultant()).thenReturn(consultant);
    assertThat(sessionAccess.assertUserHasAccess(session.getId(), caller).getId())
        .isEqualTo(session.getId());

    org.assertj.core.api.Assertions.assertThatCode(
            () -> controller.createMessageEventNotification(messageRequest(false)))
        .doesNotThrowAnyException();
    assertThat(
            notifications.findAll().stream()
                .filter(
                    row ->
                        row.getDeduplicationKey() != null
                            && row.getDeduplicationKey().contains(eventId)))
        .hasSize(1);
    assertThat(sessions.findById(1L).orElseThrow().getTenantId()).isNull();
    assertThat(consultants.findById(actor.getId()).orElseThrow().getTenantId()).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "SUPERVISOR,false,false",
    "SUPERVISOR,true,false",
    "CO_ACCESS,false,false",
    "CO_ACCESS,true,false",
    "SUPERVISOR,false,true",
    "SUPERVISOR,true,true",
    "CO_ACCESS,false,true",
    "CO_ACCESS,true,true"
  })
  void currentReadAccessDoesNotAuthorizePrimaryRoomNotificationWrites(
      String kind, boolean thread, boolean legacy) {
    var session = sessions.findById(1L).orElseThrow();
    var reader = consultants.findById("88613f5d-0d40-47e0-b323-e792e7fba3ed").orElseThrow();
    readActorId = reader.getId();
    originalReadActorTenant = reader.getTenantId();
    originalReadActorTeam = reader.isTeamConsultant();
    reader.setTenantId(legacy ? null : Long.valueOf(1L));
    if (legacy) {
      session.setTenantId(null);
      sessions.save(session);
    }
    reader.setTeamConsultant(true);
    consultants.saveAndFlush(reader);
    if ("SUPERVISOR".equals(kind)) {
      var row =
          supervisors.saveAndFlush(
              SessionSupervisor.builder()
                  .session(session)
                  .supervisorConsultant(reader)
                  .addedByConsultant(session.getConsultant())
                  .addedDate(LocalDateTime.now())
                  .isActive(true)
                  .matrixRoomId("!protected-" + eventId)
                  .build());
      supervisionIds.add(row.getId());
    } else {
      session.setTeamSession(true);
      sessions.save(session);
      var row =
          handovers.saveAndFlush(
              CaseHandoverRequest.builder()
                  .session(session)
                  .requesterConsultant(reader)
                  .previousConsultant(session.getConsultant())
                  .reasonCode("COUNSELLOR_ASKED_FOR_ADVICE")
                  .reasonLabel("Advice requested")
                  .explanation("Controlled read-only authority fixture")
                  .clientConsentRequired(false)
                  .clientConsent(de.caritas.cob.userservice.api.model.CaseHandoverConsentMode.NONE)
                  .policyAuthority("EXISTING_CO_ACCESS")
                  .auditOutcome("GRANTED")
                  .status(CaseHandoverRequest.Status.GRANTED)
                  .accessType(CaseHandoverRequest.AccessType.CO_ACCESS)
                  .createdAt(LocalDateTime.now())
                  .expiresAt(LocalDateTime.now().plusHours(1))
                  .tenantId(1L)
                  .build());
      handoverIds.add(row.getId());
    }
    when(caller.getUserId()).thenReturn(reader.getId());
    when(caller.getRoles()).thenReturn(Set.of("consultant"));
    when(caller.isConsultant()).thenReturn(true);
    assertThat(sessionAccess.assertUserHasAccess(session.getId(), caller).getId())
        .isEqualTo(session.getId());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> controller.createMessageEventNotification(messageRequest(thread)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(
            notifications.findAll().stream()
                .filter(
                    row ->
                        row.getDeduplicationKey() != null
                            && row.getDeduplicationKey().contains(eventId)))
        .isEmpty();
    assertThat(mailIntents.findAll().stream().filter(row -> eventId.equals(row.getSourceEventId())))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 2})
  void legacyTenantOneOwnershipCannotBeInferredInAnotherContext(long context) {
    var session = sessions.findById(1L).orElseThrow();
    session.setTenantId(null);
    sessions.save(session);
    when(caller.getUserId()).thenReturn(session.getUser().getUserId());
    TenantContext.setCurrentTenant(context);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> controller.createMessageEventNotification(messageRequest(false)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(
            notifications.findAll().stream()
                .filter(
                    row ->
                        row.getDeduplicationKey() != null
                            && row.getDeduplicationKey().contains(eventId)))
        .isEmpty();
  }

  private EventNotificationController.MessageEventRequestDTO messageRequest(boolean thread) {
    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId(sessions.findById(1L).orElseThrow().getMatrixRoomId());
    request.setMatrixEventId(eventId);
    request.setMessagePreview("private message");
    if (thread) request.setThreadRootId("$thread-root");
    return request;
  }
}
