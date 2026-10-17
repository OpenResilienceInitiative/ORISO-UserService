package de.caritas.cob.userservice.api.admin.service.consultant;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.controller.CaseHandoverController;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdminService;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyDeletionValidationService;
import de.caritas.cob.userservice.api.admin.service.agency.RemoveConsultantFromSessionRoomsService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationFinalizer;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.out.CaseHandoverMatrixRepairTaskRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverReasonPolicyRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTenantChangeAuditRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityTenantUpdater;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairAction;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.CaseHandoverEmailNotification;
import de.caritas.cob.userservice.api.service.notification.EventNotificationDeduplicationWriter;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.ConsultantJoinedAgencyEvent;
import de.caritas.cob.userservice.api.service.session.ConsultantLeftAgencyEvent;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MariaDB proof for #1419 with the real tenant filter switched on: the counsellor is moved by the
 * real service, their cases stay with the old Träger, they lose access, and a colleague of the old
 * Träger takes the cases over with {@code ASSIGNMENT_ENDED}, one by one and as a batch. Only the
 * outside world (identity provider, chat server, agency service) is a test double.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@TestPropertySource(properties = "multitenancy.enabled=true")
@RecordApplicationEvents
@Import({
  SessionOwnershipService.class,
  de.caritas.cob.userservice.api.service.matrix.InquiryAcceptanceNoticeStore.class,
  de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver.class,
  EventNotificationService.class,
  EventNotificationDeduplicationWriter.class,
  ConsultantTenantChangeService.class,
  OldTraegerRoomFinder.class,
  ConsultantAgencyAdminService.class,
  ConsultantAgencyDeletionValidationService.class,
  ConsultantAgencyService.class,
  ConsultantAgencyRelationCreatorService.class,
  ConsultantAgencyRelationFinalizer.class,
  ConsultantTopicAgencyCompatibilityValidator.class,
  CaseHandoverMatrixRepairService.class,
  ConsultantTenantChangeMariaDbIT.ClockConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConsultantTenantChangeMariaDbIT {

  private static final long OLD_TRAEGER = 7L;
  private static final long NEW_TRAEGER = 8L;
  private static final long OLD_AGENCY = 700L;
  private static final long NEW_AGENCY = 800L;
  private static final long TOPIC_ID = 5L;
  private static final String USER_ID = "tenant-change-user";
  private static final String MOVER_ID = "tenant-change-mover";
  private static final String COLLEAGUE_ID = "tenant-change-colleague";
  private static final String ROOM_ID = "!tenant-change:oriso.invalid";
  private static final String SECOND_ROOM_ID = "!tenant-change-2:oriso.invalid";
  private static final String TEAM_ROOM_ID = "!tenant-change-team:oriso.invalid";
  private static final String COLLEAGUE_ROOM_ID = "!tenant-change-colleague:oriso.invalid";
  private static final String DIRECT_ROOM_ID = "!tenant-change-direct:oriso.invalid";
  private static final String SUPERVISED_ROOM_ID = "!tenant-change-supervised:oriso.invalid";
  private static final String SUPERVISED_SIDE_ROOM_ID = "!tenant-change-side-1:oriso.invalid";
  private static final String OWNED_SIDE_ROOM_ID = "!tenant-change-side-2:oriso.invalid";
  private static final String UNRELATED_ROOM_ID = "!not-a-case:oriso.invalid";

  @TestConfiguration
  static class ClockConfig {
    @Bean
    java.time.Clock clock() {
      return java.time.Clock.systemUTC();
    }

    @Bean
    io.micrometer.core.instrument.MeterRegistry meterRegistry() {
      return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }
  }

  @Autowired private SessionRepository sessionRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private CaseHandoverRequestRepository caseHandoverRequestRepository;
  @Autowired private EventNotificationRepository eventNotificationRepository;
  @Autowired private CaseHandoverReasonPolicyRepository caseHandoverReasonPolicyRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private CaseHandoverMatrixRepairTaskRepository repairTaskRepository;
  @Autowired private ConsultantTenantChangeAuditRepository auditRepository;
  @Autowired private SessionSupervisorRepository sessionSupervisorRepository;
  @Autowired private CaseHandoverMatrixRepairService repairService;
  @Autowired private SessionOwnershipService ownershipService;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ConsultantTenantChangeService tenantChangeService;
  @Autowired private ApplicationEvents events;
  @MockitoBean private IdentityTombstoneService identityTombstoneService;

  @MockitoBean
  private de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService
      matrixFeedUpdateSignalService;

  @MockitoBean private AgencyService agencyService;
  @MockitoBean private AgencyAdminService agencyAdminService;
  @MockitoBean private RemoveConsultantFromSessionRoomsService removeFromSessionRoomsService;
  @MockitoBean private AccountManaging accountManaging;
  @MockitoBean private UserDtoMapper userDtoMapper;
  @MockitoBean private IdentityClient identityClient;
  @MockitoBean private IdentityRoleLookup identityRoleLookup;
  @MockitoBean private IdentityRoleUpdater identityRoleUpdater;
  @MockitoBean private ConsultingTypeManager consultingTypeManager;
  @MockitoBean private IdentityTenantUpdater identityTenantUpdater;
  @MockitoBean private MatrixSynapseService matrixSynapseService;

  private TransactionTemplate transactions;
  private Long sessionId;
  private Long secondSessionId;
  private Long teamSessionId;
  private Long colleagueSessionId;
  private Long directSessionId;
  private Long supervisedSessionId;

  /** Who is in which chat room, as the chat server would tell. */
  private final java.util.Map<String, java.util.Set<String>> roomMembers =
      new java.util.LinkedHashMap<>();

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @BeforeEach
  void createCase() {
    transactions = new TransactionTemplate(transactionManager);
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    when(consultingTypeManager.getConsultingTypeSettings(0))
        .thenReturn(new ExtendedConsultingTypeResponseDTO());
    when(agencyService.getAgenciesWithoutCaching(List.of(NEW_AGENCY)))
        .thenReturn(List.of(agency(NEW_AGENCY, NEW_TRAEGER)));
    when(agencyService.getAgency(NEW_AGENCY)).thenReturn(agency(NEW_AGENCY, NEW_TRAEGER));
    when(agencyService.getAgenciesWithoutCaching(List.of(OLD_AGENCY)))
        .thenReturn(List.of(agency(OLD_AGENCY, OLD_TRAEGER)));
    sessionId = null;
    secondSessionId = null;
    teamSessionId = null;
    colleagueSessionId = null;
    directSessionId = null;
    supervisedSessionId = null;
    transactions.executeWithoutResult(
        ignored -> {
          LocalDateTime now = LocalDateTime.now();
          User user =
              userRepository.save(
                  User.builder()
                      .userId(USER_ID)
                      .username(USER_ID)
                      .email(USER_ID + "@example.invalid")
                      .languageFormal(false)
                      .encourage2fa(true)
                      .magicLinkLoginEnabled(false)
                      .languageCode(LanguageCode.de)
                      .tenantId(OLD_TRAEGER)
                      .notificationsEnabled(false)
                      .createDate(now)
                      .updateDate(now)
                      .build());
          Consultant mover = consultantRepository.save(consultant(MOVER_ID, now));
          Consultant colleague = consultantRepository.save(consultant(COLLEAGUE_ID, now));
          consultantAgencyRepository.save(relation(mover, now));
          consultantAgencyRepository.save(relation(colleague, now));
          sessionId = sessionRepository.save(session(user, mover, ROOM_ID, now)).getId();
          secondSessionId =
              sessionRepository.save(session(user, mover, SECOND_ROOM_ID, now)).getId();
          // A team case of the old centre owned by the colleague: the mover reads it as a team
          // member. A private case of the colleague must stay out of the leave list.
          var team = session(user, colleague, TEAM_ROOM_ID, now);
          team.setTeamSession(true);
          teamSessionId = sessionRepository.save(team).getId();
          // An accepted case of a colleague: the mover sits in its room as a silent department
          // member (ADR-002 §1), without owning, supervising or being granted anything.
          colleagueSessionId =
              sessionRepository.save(session(user, colleague, COLLEAGUE_ROOM_ID, now)).getId();
          // A direct assignment: the department fan-out skipped it, the mover never joined.
          directSessionId =
              sessionRepository.save(session(user, colleague, DIRECT_ROOM_ID, now)).getId();
          // The mover supervises a case of the colleague (ADR-008): client room plus side room.
          supervisedSessionId =
              sessionRepository.save(session(user, colleague, SUPERVISED_ROOM_ID, now)).getId();
          sessionSupervisorRepository.save(
              supervision(
                  sessionRepository.findById(supervisedSessionId).orElseThrow(),
                  mover,
                  colleague,
                  SUPERVISED_SIDE_ROOM_ID,
                  now));
          // The colleague supervises a case of the mover: the mover sits in the side room.
          sessionSupervisorRepository.save(
              supervision(
                  sessionRepository.findById(secondSessionId).orElseThrow(),
                  colleague,
                  mover,
                  OWNED_SIDE_ROOM_ID,
                  now));
        });
    chatServerWithMembers();
  }

  @AfterEach
  void removeCase() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    transactions.executeWithoutResult(
        ignored -> {
          for (Long id :
              java.util.Arrays.asList(
                  sessionId,
                  secondSessionId,
                  teamSessionId,
                  colleagueSessionId,
                  directSessionId,
                  supervisedSessionId)) {
            if (id != null) {
              sessionSupervisorRepository.deleteAllBySessionId(id);
              caseHandoverRequestRepository.deleteAllBySessionId(id);
              sessionRepository.deleteById(id);
            }
          }
        });
    transactions.executeWithoutResult(
        ignored -> {
          eventNotificationRepository.deleteByRecipientUserId(MOVER_ID);
          eventNotificationRepository.deleteByRecipientUserId(COLLEAGUE_ID);
          eventNotificationRepository.deleteByRecipientUserId(USER_ID);
          repairTaskRepository.deleteAll(
              repairTaskRepository.findByActionAndMemberId(
                  CaseHandoverMatrixRepairAction.LEAVE, matrixId(MOVER_ID)));
          auditRepository.deleteAll(
              auditRepository.findByConsultantIdOrderByCreateDateAsc(MOVER_ID));
          for (String id : List.of(MOVER_ID, COLLEAGUE_ID)) {
            consultantAgencyRepository.deleteAll(consultantAgencyRepository.findByConsultantId(id));
          }
          consultantRepository.deleteAllById(List.of(MOVER_ID, COLLEAGUE_ID));
          userRepository.deleteById(USER_ID);
        });
    TenantContext.clear();
  }

  @Test
  void theMoveSwitchesTheCounsellorOverAndLeavesTheirCasesWhereTheyAre() {
    var result = move();

    assertThat(result.previousTenantId()).isEqualTo(OLD_TRAEGER);
    assertThat(result.tenantId()).isEqualTo(NEW_TRAEGER);
    assertThat(result.agencyIds()).containsExactly(NEW_AGENCY);
    assertThat(result.casesLeftWithPreviousTenant()).isEqualTo(2);
    assertThat(result.matrixAccessRevocationsQueued()).isEqualTo(7);

    Consultant mover = reload(MOVER_ID);
    assertThat(mover.getTenantId()).isEqualTo(NEW_TRAEGER);
    // The identity provider is told in the same move, before the database commits.
    verify(identityTenantUpdater).updateTenant(MOVER_ID, NEW_TRAEGER);

    // Access by centre: gone in the old Träger, present in the new one (this is what routes new
    // enquiries to the new centre and no longer to the old one).
    assertThat(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(OLD_AGENCY))
        .extracting(relation -> relation.getConsultant().getId())
        .containsExactly(COLLEAGUE_ID);
    assertThat(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(NEW_AGENCY))
        .extracting(
            relation -> relation.getConsultant().getId(), relation -> relation.getTenantId())
        .containsExactly(org.assertj.core.groups.Tuple.tuple(MOVER_ID, NEW_TRAEGER));

    // The cases themselves are untouched: same owner, same Träger, same room.
    for (Long id : List.of(sessionId, secondSessionId)) {
      Session session = sessionRepository.findById(id).orElseThrow();
      assertThat(session.getTenantId()).isEqualTo(OLD_TRAEGER);
      assertThat(sessionConsultantId(id)).isEqualTo(MOVER_ID);
    }

    // The rooms are left through the durable repair queue, not inline.
    assertThat(
            repairTaskRepository.findByActionAndMemberId(
                CaseHandoverMatrixRepairAction.LEAVE, matrixId(MOVER_ID)))
        .extracting("roomId", "sessionId", "requesterConsultantId")
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(ROOM_ID, sessionId, MOVER_ID),
            org.assertj.core.groups.Tuple.tuple(SECOND_ROOM_ID, secondSessionId, MOVER_ID),
            org.assertj.core.groups.Tuple.tuple(TEAM_ROOM_ID, teamSessionId, MOVER_ID),
            // #1421 finding 1: a silent member of an accepted case of a colleague.
            org.assertj.core.groups.Tuple.tuple(COLLEAGUE_ROOM_ID, colleagueSessionId, MOVER_ID),
            org.assertj.core.groups.Tuple.tuple(SUPERVISED_ROOM_ID, supervisedSessionId, MOVER_ID),
            // #1421 finding 2: the staff-only side rooms of supervised and owned cases.
            org.assertj.core.groups.Tuple.tuple(
                SUPERVISED_SIDE_ROOM_ID, supervisedSessionId, MOVER_ID),
            org.assertj.core.groups.Tuple.tuple(OWNED_SIDE_ROOM_ID, secondSessionId, MOVER_ID));

    assertThat(events.stream(ConsultantLeftAgencyEvent.class))
        .containsExactly(new ConsultantLeftAgencyEvent(MOVER_ID, OLD_AGENCY));
    assertThat(events.stream(ConsultantJoinedAgencyEvent.class))
        .containsExactly(new ConsultantJoinedAgencyEvent(MOVER_ID, NEW_AGENCY));

    var audit = auditRepository.findByConsultantIdOrderByCreateDateAsc(MOVER_ID);
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0).getActorId()).isEqualTo("platform-admin");
    assertThat(audit.get(0).getPreviousTenantId()).isEqualTo(OLD_TRAEGER);
    assertThat(audit.get(0).getNewTenantId()).isEqualTo(NEW_TRAEGER);
    assertThat(audit.get(0).getPreviousAgencyIds()).isEqualTo(String.valueOf(OLD_AGENCY));
    assertThat(audit.get(0).getNewAgencyIds()).isEqualTo(String.valueOf(NEW_AGENCY));
    assertThat(audit.get(0).getCasesLeftWithPreviousTenant()).isEqualTo(2);
  }

  @Test
  void theTopicsOfTheOldTraegerNeverCarryOverAndTheNewOnesAreStoredPerCentre() {
    transactions.executeWithoutResult(
        ignored ->
            consultantRepository.findById(MOVER_ID).orElseThrow().replaceTopics(List.of(99L)));
    var newAgency = agency(NEW_AGENCY, NEW_TRAEGER);
    newAgency.setTopicIds(List.of(TOPIC_ID));
    when(agencyService.getAgenciesWithoutCaching(List.of(NEW_AGENCY)))
        .thenReturn(List.of(newAgency));

    tenantChangeService.changeTenant(
        MOVER_ID,
        NEW_TRAEGER,
        List.of(new CreateConsultantAgencyDTO().agencyId(NEW_AGENCY)),
        List.of(TOPIC_ID),
        "platform-admin");

    var rows =
        transactions.execute(
            ignored ->
                consultantRepository.findById(MOVER_ID).orElseThrow().getConsultantTopics().stream()
                    .map(topic -> topic.getAgencyId() + ":" + topic.getTopicId())
                    .toList());
    assertThat(rows).containsExactly(NEW_AGENCY + ":" + TOPIC_ID);
  }

  @Test
  void aCentreOfAnotherTraegerIsRefusedAndNothingChanges() {
    when(agencyService.getAgenciesWithoutCaching(List.of(OLD_AGENCY)))
        .thenReturn(List.of(agency(OLD_AGENCY, OLD_TRAEGER)));

    assertThatThrownBy(
            () ->
                tenantChangeService.changeTenant(
                    MOVER_ID,
                    NEW_TRAEGER,
                    List.of(new CreateConsultantAgencyDTO().agencyId(OLD_AGENCY)),
                    null,
                    "platform-admin"))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            refusal -> {
              assertThat(refusal.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(refusal.getCustomHttpHeaders().getFirst("X-Reason"))
                  .isEqualTo("TENANT_CHANGE_AGENCY_OTHER_TENANT");
            });

    assertNothingChanged();
  }

  @Test
  void aRefusedIdentityProviderRollsTheWholeMoveBack() {
    doThrow(new IllegalStateException("keycloak down"))
        .when(identityTenantUpdater)
        .updateTenant(MOVER_ID, NEW_TRAEGER);

    assertThatThrownBy(this::move).isInstanceOf(ServiceUnavailableException.class);

    assertNothingChanged();
    // The identity provider may have applied the write before it failed: the old tenant is put
    // back, and no role was granted before the refusal.
    verify(identityTenantUpdater).updateTenant(MOVER_ID, OLD_TRAEGER);
    verify(identityRoleUpdater, never()).ensureRoles(anyString(), anyCollection());
  }

  /** #1421 finding 5b: the roles follow the tenant switch; a refused grant undoes the move. */
  @Test
  void aRefusedRoleGrantRollsTheWholeMoveBackAndPutsTheOldTenantBack() {
    givenTheNewCentreGivesTheRole("new-traeger-role");
    doThrow(new IllegalStateException("keycloak down"))
        .when(identityRoleUpdater)
        .ensureRoles(anyString(), anyCollection());

    assertThatThrownBy(this::move).isInstanceOf(ServiceUnavailableException.class);

    assertNothingChanged();
    verify(identityTenantUpdater).updateTenant(MOVER_ID, NEW_TRAEGER);
    verify(identityTenantUpdater).updateTenant(MOVER_ID, OLD_TRAEGER);
  }

  @Test
  void theRolesOfTheNewCentreAreGrantedAfterTheTenantSwitchInTheSameMove() {
    givenTheNewCentreGivesTheRole("new-traeger-role");

    move();

    var order = org.mockito.Mockito.inOrder(identityTenantUpdater, identityRoleUpdater);
    order.verify(identityTenantUpdater).updateTenant(MOVER_ID, NEW_TRAEGER);
    order.verify(identityRoleUpdater).ensureRoles(MOVER_ID, java.util.Set.of("new-traeger-role"));
    verify(identityTenantUpdater, never()).updateTenant(MOVER_ID, OLD_TRAEGER);
  }

  @Test
  void theLastCounsellorOfAStillActiveCentreIsNotMovedAway() {
    transactions.executeWithoutResult(
        ignored ->
            consultantAgencyRepository.deleteAll(
                consultantAgencyRepository.findByConsultantId(COLLEAGUE_ID)));
    when(agencyService.getAgencyWithoutCaching(OLD_AGENCY))
        .thenReturn(agency(OLD_AGENCY, OLD_TRAEGER));

    assertThatThrownBy(this::move)
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            refusal -> {
              assertThat(refusal.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(refusal.getCustomHttpHeaders().getFirst("X-Reason"))
                  .isEqualTo("TENANT_CHANGE_LAST_COUNSELLOR");
            });

    assertNothingChanged();
    verify(identityTenantUpdater, never()).updateTenant(anyString(), any());
  }

  /** Diagnosis (b) of #1419, now on a counsellor moved by the real service. */
  @Test
  void colleagueOfTheOldTraegerTakesOverTheCaseOfACounsellorWhoMovedToAnotherTraeger() {
    move();

    TenantContext.setCurrentTenant(OLD_TRAEGER);
    CaseHandoverService handovers = handoverService();

    var candidates = transactions.execute(ignored -> handovers.searchCandidates("", 0, 15, false));
    assertThat(candidates.getSessions())
        .extracting(candidate -> candidate.getSession().getId())
        .contains(sessionId, secondSessionId);

    var status =
        transactions.execute(
            ignored -> handovers.requestAccess(sessionId, "ASSIGNMENT_ENDED", "Assignment ended"));

    assertThat(status.getStatus()).isEqualTo(CaseHandoverRequest.Status.GRANTED.name());
    TenantContext.setCurrentTenant(OLD_TRAEGER);
    assertThat(sessionConsultantId(sessionId)).isEqualTo(COLLEAGUE_ID);
    assertThat(sessionConsultantId(secondSessionId)).isEqualTo(MOVER_ID);
  }

  @Test
  void colleagueTakesOverSeveralCasesOfTheMovedCounsellorInOneBatch() {
    move();

    TenantContext.setCurrentTenant(OLD_TRAEGER);
    // The real service is a transactional bean in production: every operation of the batch runs in
    // its own transaction. This double gives the hand-built service the same boundary.
    CaseHandoverService handovers = handoverService();
    CaseHandoverService perOperationTransaction = mock(CaseHandoverService.class);
    when(perOperationTransaction.requestAccess(any(), anyString(), anyString(), any(), any()))
        .thenAnswer(
            invocation ->
                transactions.execute(
                    ignored ->
                        handovers.requestAccess(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            invocation.getArgument(4))));
    var controller =
        new CaseHandoverController(perOperationTransaction, mock(CaseHandoverLogsService.class));
    var request = new CaseHandoverController.CaseHandoverBatchRequestDTO();
    request.setReasonCode("ASSIGNMENT_ENDED");
    request.setExplanation("Assignment ended");
    request.setOperations(List.of(batchOperation(sessionId), batchOperation(secondSessionId)));

    var response = controller.requestBatchAccess(request);

    assertThat(response.getBody()).hasSize(2);
    assertThat(response.getBody())
        .allSatisfy(result -> assertThat(result.success).describedAs(result.error).isTrue());
    TenantContext.setCurrentTenant(OLD_TRAEGER);
    assertThat(sessionConsultantId(sessionId)).isEqualTo(COLLEAGUE_ID);
    assertThat(sessionConsultantId(secondSessionId)).isEqualTo(COLLEAGUE_ID);
  }

  /** The tenant filter is what hides the old cases from the moved counsellor. */
  @Test
  void movedCounsellorNoLongerSeesTheCaseThroughTheTenantFilter() {
    move();

    TenantContext.setCurrentTenant(NEW_TRAEGER);

    Optional<Session> byId = transactions.execute(ignored -> sessionRepository.findById(sessionId));
    List<Session> owned =
        transactions.execute(
            ignored ->
                sessionRepository.findByConsultantAndStatusIn(
                    consultantRepository.findById(MOVER_ID).orElseThrow(), List.of(IN_PROGRESS)));
    assertThat(byId).isEmpty();
    assertThat(owned).isEmpty();
  }

  /**
   * #1421 finding 1, end to end: once the queue has run, the mover is in no room of the old Träger.
   */
  @Test
  void onceTheQueueHasRunTheMoverIsInNoRoomOfTheOldTraegerAndColleaguesStayAsTheyWere() {
    move();

    processTheRepairQueue();

    var moverId = matrixId(MOVER_ID);
    assertThat(roomMembers.entrySet())
        .filteredOn(room -> room.getValue().contains(moverId))
        .extracting(java.util.Map.Entry::getKey)
        .containsExactly(UNRELATED_ROOM_ID);
    // Colleagues keep every seat: the move only takes the mover's own.
    assertThat(roomMembers.get(COLLEAGUE_ROOM_ID)).containsExactly(matrixId(COLLEAGUE_ID));
    assertThat(roomMembers.get(SUPERVISED_SIDE_ROOM_ID)).containsExactly(matrixId(COLLEAGUE_ID));
    assertThat(roomMembers.get(OWNED_SIDE_ROOM_ID)).containsExactly(matrixId(COLLEAGUE_ID));
  }

  /** A room the mover never joined needs no task: the membership decides, not the centre. */
  @Test
  void aCaseTheMoverIsNotAMemberOfGetsNoLeaveTask() {
    var result = move();

    assertThat(result.matrixAccessRevocationsQueued()).isEqualTo(7);
    assertThat(
            repairTaskRepository.findByActionAndMemberId(
                CaseHandoverMatrixRepairAction.LEAVE, matrixId(MOVER_ID)))
        .extracting("roomId")
        .doesNotContain(DIRECT_ROOM_ID, UNRELATED_ROOM_ID);
  }

  /** The chat server cannot be asked: leave every room of the old centres rather than none. */
  @Test
  void whenTheChatServerCannotTellWhereTheMoverSitsEveryRoomOfTheOldCentresIsQueued() {
    org.mockito.Mockito.doReturn(Optional.empty())
        .when(matrixSynapseService)
        .findJoinedRoomsForMatrixUser(matrixId(MOVER_ID));

    move();

    assertThat(
            repairTaskRepository.findByActionAndMemberId(
                CaseHandoverMatrixRepairAction.LEAVE, matrixId(MOVER_ID)))
        .extracting("roomId")
        .containsExactlyInAnyOrder(
            ROOM_ID,
            SECOND_ROOM_ID,
            TEAM_ROOM_ID,
            COLLEAGUE_ROOM_ID,
            DIRECT_ROOM_ID,
            SUPERVISED_ROOM_ID,
            SUPERVISED_SIDE_ROOM_ID,
            OWNED_SIDE_ROOM_ID);
  }

  /**
   * #1421 finding 2: the supervisions of the mover end, those of colleagues on mover's cases stay.
   */
  @Test
  void theSupervisionsOfTheMoverEndAndWouldNotComeBackWithTheMove() {
    var before = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1);

    move();

    var moversSupervision = sessionSupervisorRepository.findBySessionId(supervisedSessionId).get(0);
    assertThat(moversSupervision.getSupervisorConsultant().getId()).isEqualTo(MOVER_ID);
    assertThat(moversSupervision.getIsActive()).isFalse();
    assertThat(moversSupervision.getRemovedDate()).isAfter(before);
    assertThat(sessionSupervisorRepository.findActiveSupervisionsByConsultantId(MOVER_ID))
        .isEmpty();

    var colleaguesSupervision = sessionSupervisorRepository.findBySessionId(secondSessionId).get(0);
    assertThat(colleaguesSupervision.getSupervisorConsultant().getId()).isEqualTo(COLLEAGUE_ID);
    assertThat(colleaguesSupervision.getIsActive()).isTrue();
    assertThat(colleaguesSupervision.getRemovedDate()).isNull();
  }

  // --- helpers ---------------------------------------------------------------------------

  private void givenTheNewCentreGivesTheRole(String role) {
    var roleSets = new java.util.LinkedHashMap<String, List<String>>();
    roleSets.put("consultant", List.of(role));
    var consultantRoles =
        new de.caritas.cob.userservice.api.manager.consultingtype.roles.Consultant();
    consultantRoles.setRoleSets(roleSets);
    var roles = new de.caritas.cob.userservice.consultingtypeservice.generated.web.model.RolesDTO();
    roles.setConsultant(consultantRoles);
    var settings = new ExtendedConsultingTypeResponseDTO();
    settings.setRoles(roles);
    when(consultingTypeManager.getConsultingTypeSettings(0)).thenReturn(settings);
  }

  /** Chat server double that remembers memberships: the leave and the room lookups are real. */
  private void chatServerWithMembers() {
    roomMembers.clear();
    var mover = matrixId(MOVER_ID);
    var colleague = matrixId(COLLEAGUE_ID);
    for (String room : List.of(ROOM_ID, SECOND_ROOM_ID, OWNED_SIDE_ROOM_ID)) {
      roomMembers.computeIfAbsent(room, key -> new java.util.LinkedHashSet<>()).add(mover);
    }
    for (String room :
        List.of(TEAM_ROOM_ID, COLLEAGUE_ROOM_ID, SUPERVISED_ROOM_ID, SUPERVISED_SIDE_ROOM_ID)) {
      roomMembers.computeIfAbsent(room, key -> new java.util.LinkedHashSet<>()).add(mover);
    }
    for (String room :
        List.of(
            TEAM_ROOM_ID,
            COLLEAGUE_ROOM_ID,
            DIRECT_ROOM_ID,
            SUPERVISED_ROOM_ID,
            SUPERVISED_SIDE_ROOM_ID,
            OWNED_SIDE_ROOM_ID)) {
      roomMembers.computeIfAbsent(room, key -> new java.util.LinkedHashSet<>()).add(colleague);
    }
    // Something that is no case of ours at all.
    roomMembers
        .computeIfAbsent(UNRELATED_ROOM_ID, key -> new java.util.LinkedHashSet<>())
        .add(mover);

    when(matrixSynapseService.loginAsUserAccessToken(anyString()))
        .thenAnswer(invocation -> "token-of:" + invocation.getArgument(0));
    when(matrixSynapseService.findJoinedRoomsForMatrixUser(anyString()))
        .thenAnswer(
            invocation ->
                Optional.of(
                    roomMembers.entrySet().stream()
                        .filter(room -> room.getValue().contains(invocation.<String>getArgument(0)))
                        .map(java.util.Map.Entry::getKey)
                        .toList()));
    when(matrixSynapseService.getRoomMembers(anyString()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(roomMembers.get(invocation.<String>getArgument(0)))
                    .map(List::copyOf));
    when(matrixSynapseService.leaveRoom(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              var member = invocation.<String>getArgument(1).replace("token-of:", "");
              var room = roomMembers.get(invocation.<String>getArgument(0));
              return room != null && room.remove(member);
            });
  }

  /** What the scheduler does every minute, run until nothing is left. */
  private void processTheRepairQueue() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    for (int round = 0; round < 5; round++) {
      var pending = repairService.pendingTaskIds();
      if (pending.isEmpty()) {
        return;
      }
      pending.forEach(repairService::process);
    }
  }

  private static SessionSupervisor supervision(
      Session session,
      Consultant supervisor,
      Consultant addedBy,
      String sideRoom,
      LocalDateTime now) {
    return SessionSupervisor.builder()
        .session(session)
        .supervisorConsultant(supervisor)
        .addedByConsultant(addedBy)
        .addedDate(now)
        .isActive(true)
        .matrixRoomId(sideRoom)
        .build();
  }

  private ConsultantTenantChangeService.Result move() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    return tenantChangeService.changeTenant(
        MOVER_ID,
        NEW_TRAEGER,
        List.of(new CreateConsultantAgencyDTO().agencyId(NEW_AGENCY).roleSetKey("consultant")),
        null,
        "platform-admin");
  }

  private void assertNothingChanged() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    assertThat(reload(MOVER_ID).getTenantId()).isEqualTo(OLD_TRAEGER);
    assertThat(consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(MOVER_ID))
        .extracting(ConsultantAgency::getAgencyId)
        .containsExactly(OLD_AGENCY);
    assertThat(auditRepository.findByConsultantIdOrderByCreateDateAsc(MOVER_ID)).isEmpty();
    assertThat(
            repairTaskRepository.findByActionAndMemberId(
                CaseHandoverMatrixRepairAction.LEAVE, matrixId(MOVER_ID)))
        .isEmpty();
  }

  private Consultant reload(String id) {
    return transactions.execute(ignored -> consultantRepository.findById(id).orElseThrow());
  }

  private String sessionConsultantId(Long id) {
    return transactions.execute(
        ignored -> sessionRepository.findById(id).orElseThrow().getConsultant().getId());
  }

  private static CaseHandoverController.CaseHandoverBatchOperationDTO batchOperation(Long id) {
    var operation = new CaseHandoverController.CaseHandoverBatchOperationDTO();
    operation.setSessionId(id);
    operation.setExpectedOwnershipRevision(0L);
    operation.setOperationId(java.util.UUID.randomUUID());
    return operation;
  }

  private static AgencyDTO agency(long id, long tenantId) {
    return new AgencyDTO()
        .id(id)
        .tenantId(tenantId)
        .consultingType(0)
        .offline(false)
        .teamAgency(false)
        .topicIds(List.of());
  }

  private static Session session(User user, Consultant owner, String roomId, LocalDateTime now) {
    return Session.builder()
        .user(user)
        .consultant(owner)
        .tenantId(OLD_TRAEGER)
        .agencyId(OLD_AGENCY)
        .consultingTypeId(0)
        .registrationType(REGISTERED)
        .conversationType(ConversationType.AGENCY_COUNSELLING)
        .postcode("00000")
        .languageCode(LanguageCode.de)
        .status(IN_PROGRESS)
        .teamSession(false)
        .isConsultantDirectlySet(false)
        .matrixRoomId(roomId)
        .createDate(now)
        .updateDate(now)
        .ownershipRevision(0L)
        .rowVersion(0L)
        .build();
  }

  private CaseHandoverService handoverService() {
    var cache = mock(de.caritas.cob.userservice.api.service.CaseHandoverPolicyCacheService.class);
    var policy =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .CaseHandoverReasonPolicy()
            .code(
                de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverReasonPolicy.CodeEnum.COUNSELLOR_LEFT)
            .enabled(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .BooleanPermissionPolicy(null)
                    .value(true))
            .accessAllowed(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .BooleanPermissionPolicy(null)
                    .value(true))
            .clientConsent(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .ConsentPermissionPolicy(null)
                    .value(
                        de.caritas.cob.userservice.tenantadminservice.generated.web.model
                            .CaseHandoverConsentValue.NONE));
    when(cache.getEffective(OLD_TRAEGER))
        .thenReturn(
            new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverPolicies()
                .reasons(java.util.Map.of("COUNSELLOR_LEFT", policy)));

    UserAccountService accounts = mock(UserAccountService.class);
    when(accounts.retrieveValidatedConsultant())
        .thenAnswer(
            ignored ->
                transactions.execute(
                    tx -> consultantRepository.findById(COLLEAGUE_ID).orElseThrow()));
    AuthenticatedUser caller = mock(AuthenticatedUser.class);
    when(caller.getUserId()).thenReturn(COLLEAGUE_ID);
    when(caller.isConsultant()).thenReturn(true);

    MatrixSynapseService matrix = mock(MatrixSynapseService.class);
    when(matrix.loginAsUserAccessToken(anyString())).thenReturn("token");
    when(matrix.getRoomMembers(anyString()))
        .thenReturn(Optional.of(List.of(matrixId(MOVER_ID), matrixId(COLLEAGUE_ID))));
    when(matrix.joinRoom(anyString(), any())).thenReturn(true);
    when(matrix.setUserPowerLevel(anyString(), anyString(), any(Integer.class), any()))
        .thenReturn(true);

    return new CaseHandoverService(
        caseHandoverRequestRepository,
        mock(SessionSupervisorFacade.class),
        caseHandoverReasonPolicyRepository,
        cache,
        sessionRepository,
        ownershipService,
        consultantAgencyRepository,
        accounts,
        mock(EventNotificationService.class),
        mock(CaseHandoverEmailNotification.class),
        matrix,
        mock(de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService.class),
        mock(MatrixSessionSystemMessageService.class),
        mock(ConsultantService.class),
        caller,
        new de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver(),
        mock(de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.class),
        java.time.Clock.systemUTC(),
        transactionManager);
  }

  private static String matrixId(String consultantId) {
    return "@" + consultantId + ":oriso.invalid";
  }

  private static ConsultantAgency relation(Consultant consultant, LocalDateTime now) {
    return ConsultantAgency.builder()
        .consultant(consultant)
        .agencyId(OLD_AGENCY)
        .tenantId(OLD_TRAEGER)
        .createDate(now)
        .updateDate(now)
        .build();
  }

  private static Consultant consultant(String id, LocalDateTime now) {
    return Consultant.builder()
        .id(id)
        .matrixUserId(matrixId(id))
        .username(id)
        .firstName(id)
        .lastName("Proof")
        .email(id + "@example.invalid")
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .notifyEnquiriesRepeating(true)
        .notifyNewChatMessageFromAdviceSeeker(true)
        .walkThroughEnabled(true)
        .languageCode(LanguageCode.de)
        .tenantId(OLD_TRAEGER)
        .status(ConsultantStatus.IN_PROGRESS)
        .createDate(now)
        .updateDate(now)
        .build();
  }
}
