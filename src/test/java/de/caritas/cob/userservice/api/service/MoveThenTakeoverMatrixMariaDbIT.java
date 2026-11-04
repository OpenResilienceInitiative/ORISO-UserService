package de.caritas.cob.userservice.api.service;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.FakeSynapseHomeserver;
import de.caritas.cob.userservice.api.adapters.matrix.FakeSynapseWithSelfLeave;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverMatrixRepairTaskRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverReasonPolicyRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService.RoomRef;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.matrix.InquiryAcceptanceNoticeStore;
import de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.CaseHandoverEmailNotification;
import de.caritas.cob.userservice.api.service.notification.EventNotificationDeduplicationWriter;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.BooleanPermissionPolicy;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverConsentValue;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverReasonPolicy;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.ConsentPermissionPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MariaDB proof that #1421 and #1422 work together: a counsellor who moved to another Träger leaves
 * the room through the durable LEAVE task, which hands the technical Matrix admin the owner power
 * level first, and a colleague who then takes the case over ends up at the owner power level. The
 * persistence is real (Liquibase schema, JPA, row locks); Matrix is a homeserver double that
 * remembers membership and power levels and refuses what Synapse would refuse.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Import({
  SessionOwnershipService.class,
  InquiryAcceptanceNoticeStore.class,
  ConsultantDisplayNameResolver.class,
  EventNotificationService.class,
  EventNotificationDeduplicationWriter.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MoveThenTakeoverMatrixMariaDbIT {

  private static final long TENANT_ID = 7L;
  private static final long AGENCY_ID = 14211422L;
  private static final String USER_ID = "move-takeover-asker";
  private static final String PREVIOUS_ID = "move-takeover-previous";
  private static final String COLLEAGUE_ID = "move-takeover-colleague";

  private static final String ASKER_MXID = FakeSynapseHomeserver.mxid("moveasker");
  private static final String PREVIOUS_MXID = FakeSynapseHomeserver.mxid("moveprevious");
  private static final String COLLEAGUE_MXID = FakeSynapseHomeserver.mxid("movecolleague");
  private static final String ADMIN_MXID = FakeSynapseHomeserver.adminMxid();

  @Autowired private SessionOwnershipService ownershipService;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private CaseHandoverRequestRepository caseHandoverRequestRepository;
  @Autowired private EventNotificationRepository eventNotificationRepository;
  @Autowired private CaseHandoverReasonPolicyRepository caseHandoverReasonPolicyRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private CaseHandoverMatrixRepairTaskRepository tasks;
  @Autowired private SessionSupervisorRepository supervisors;
  @Autowired private EventNotificationService eventNotificationService;
  @MockitoBean private IdentityTombstoneService identityTombstoneService;

  @MockitoBean private MatrixFeedUpdateSignalService matrixFeedUpdateSignalService;

  private final List<Long> sessionIds = new ArrayList<>();
  private TransactionTemplate transactions;
  private FakeSynapseHomeserver homeserver;
  private CaseHandoverMatrixRepairService repairService;
  private CaseHandoverService handovers;
  private CaseHandoverEmailNotification handoverEmails;

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
  void createCaseWorld() {
    TenantContext.setCurrentTenant(TENANT_ID);
    transactions = new TransactionTemplate(transactionManager);
    homeserver = new FakeSynapseHomeserver();
    repairService = mock(CaseHandoverMatrixRepairService.class);
    handoverEmails = mock(CaseHandoverEmailNotification.class);
    transactions.executeWithoutResult(
        ignored -> {
          LocalDateTime now = LocalDateTime.now();
          userRepository.save(
              User.builder()
                  .userId(USER_ID)
                  .username(USER_ID)
                  .email(USER_ID + "@example.invalid")
                  .matrixUserId(ASKER_MXID)
                  .languageFormal(false)
                  .encourage2fa(true)
                  .magicLinkLoginEnabled(false)
                  .languageCode(LanguageCode.de)
                  .tenantId(TENANT_ID)
                  .notificationsEnabled(false)
                  .createDate(now)
                  .updateDate(now)
                  .build());
          consultantRepository.save(consultant(PREVIOUS_ID, PREVIOUS_MXID, now));
          Consultant colleague =
              consultantRepository.save(consultant(COLLEAGUE_ID, COLLEAGUE_MXID, now));
          consultantAgencyRepository.save(
              ConsultantAgency.builder()
                  .consultant(colleague)
                  .agencyId(AGENCY_ID)
                  .tenantId(TENANT_ID)
                  .createDate(now)
                  .updateDate(now)
                  .build());
        });
    homeserver.account(COLLEAGUE_MXID);
    handovers = handoverService();
  }

  @AfterEach
  void removeCaseWorld() {
    TenantContext.clear();
    transactions.executeWithoutResult(
        ignored -> {
          for (Long sessionId : sessionIds) {
            caseHandoverRequestRepository.deleteAllBySessionId(sessionId);
            sessionRepository.deleteById(sessionId);
          }
          tasks.deleteAll(
              tasks.findByActionAndMemberId(CaseHandoverMatrixRepairAction.LEAVE, PREVIOUS_MXID));
          eventNotificationRepository.deleteByRecipientUserId(COLLEAGUE_ID);
          consultantAgencyRepository.deleteAll(
              consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(COLLEAGUE_ID));
          consultantRepository.deleteAllById(List.of(PREVIOUS_ID, COLLEAGUE_ID));
          userRepository.deleteById(USER_ID);
        });
  }

  @Test
  void movedCounsellorLeaves_thenColleagueTakesOverAndEndsAtOwnerPower() {
    Long sessionId = givenAcceptedCase(false);
    String room = roomOf(sessionId);
    var queue =
        new CaseHandoverMatrixRepairService(
            tasks,
            caseHandoverRequestRepository,
            new FakeSynapseWithSelfLeave(homeserver).gateway(),
            sessionRepository,
            supervisors,
            Clock.systemUTC(),
            transactionManager,
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(queue, "maxAttempts", 100);
    ReflectionTestUtils.setField(queue, "retryBackoff", Duration.ZERO);
    transactions.executeWithoutResult(
        ignored ->
            queue.enqueueLeaves(PREVIOUS_MXID, PREVIOUS_ID, List.of(new RoomRef(sessionId, room))));
    for (Long taskId : queue.pendingTaskIds()) {
      queue.process(taskId);
    }
    assertThat(homeserver.isMember(room, PREVIOUS_MXID)).isFalse();
    assertThat(homeserver.powerOf(room, ADMIN_MXID)).isEqualTo(100);
    assertThat(tasks.findByActionAndMemberId(CaseHandoverMatrixRepairAction.LEAVE, PREVIOUS_MXID))
        .isEmpty();

    var status =
        transactions.execute(
            ignored -> handovers.requestAccess(sessionId, "ASSIGNMENT_ENDED", "Moved on."));

    assertThat(status.getStatus()).isEqualTo("GRANTED");
    assertThat(readSession(sessionId).getConsultant().getId()).isEqualTo(COLLEAGUE_ID);
    assertThat(homeserver.isMember(room, COLLEAGUE_MXID)).isTrue();
    assertThat(homeserver.canPost(room, COLLEAGUE_MXID)).isTrue();
    assertThat(homeserver.powerOf(room, COLLEAGUE_MXID)).isEqualTo(100);
  }

  // ── world building ────────────────────────────────────────────────────────

  /**
   * An accepted case owned by the previous counsellor, with the Matrix room as ORISO provisions it:
   * owner at power 100, the advice seeker and the technical admin. A normal enquiry-origin case
   * additionally has the department's colleagues as silent members; a directly assigned one never
   * does.
   */
  private Long givenAcceptedCase(boolean directlyAssigned) {
    String room = homeserver.createRoom(PREVIOUS_MXID);
    homeserver.member(room, ASKER_MXID, 0).member(room, ADMIN_MXID, 0);
    Long sessionId =
        transactions.execute(
            ignored -> {
              LocalDateTime now = LocalDateTime.now();
              return sessionRepository
                  .save(
                      Session.builder()
                          .user(userRepository.findById(USER_ID).orElseThrow())
                          .consultant(consultantRepository.findById(PREVIOUS_ID).orElseThrow())
                          .tenantId(TENANT_ID)
                          .agencyId(AGENCY_ID)
                          .consultingTypeId(0)
                          .registrationType(REGISTERED)
                          .conversationType(ConversationType.AGENCY_COUNSELLING)
                          .postcode("00000")
                          .languageCode(LanguageCode.de)
                          .status(IN_PROGRESS)
                          .teamSession(false)
                          .isConsultantDirectlySet(directlyAssigned)
                          .matrixRoomId(room)
                          .createDate(now)
                          .updateDate(now)
                          .ownershipRevision(0L)
                          .rowVersion(0L)
                          .build())
                  .getId();
            });
    sessionIds.add(sessionId);
    return sessionId;
  }

  private String roomOf(Long sessionId) {
    return readSession(sessionId).getMatrixRoomId();
  }

  private Session readSession(Long sessionId) {
    return transactions.execute(
        ignored -> {
          Session session = sessionRepository.findById(sessionId).orElseThrow();
          session.getConsultant().getId();
          return session;
        });
  }

  private CaseHandoverRequest storedRequest(Long sessionId) {
    return transactions.execute(
        ignored ->
            caseHandoverRequestRepository
                .findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
                    sessionId, COLLEAGUE_ID)
                .get(0));
  }

  private CaseHandoverService handoverService() {
    var accounts = mock(UserAccountService.class);
    when(accounts.retrieveValidatedConsultant())
        .thenAnswer(
            ignored ->
                transactions.execute(
                    tx -> consultantRepository.findById(COLLEAGUE_ID).orElseThrow()));
    var caller = mock(AuthenticatedUser.class);
    when(caller.getUserId()).thenReturn(COLLEAGUE_ID);
    when(caller.isConsultant()).thenReturn(true);

    var cache = mock(CaseHandoverPolicyCacheService.class);
    var policy =
        new CaseHandoverReasonPolicy()
            .code(CaseHandoverReasonPolicy.CodeEnum.COUNSELLOR_LEFT)
            .enabled(new BooleanPermissionPolicy(null).value(true))
            .accessAllowed(new BooleanPermissionPolicy(null).value(true))
            .clientConsent(new ConsentPermissionPolicy(null).value(CaseHandoverConsentValue.NONE));
    var policies = new CaseHandoverPolicies().reasons(Map.of("COUNSELLOR_LEFT", policy));
    when(cache.getEffective(TENANT_ID)).thenReturn(policies);
    when(cache.getCurrent(TENANT_ID))
        .thenReturn(new CaseHandoverPolicyCacheService.CurrentPolicy(true, policies));

    return new CaseHandoverService(
        caseHandoverRequestRepository,
        mock(SessionSupervisorFacade.class),
        caseHandoverReasonPolicyRepository,
        cache,
        sessionRepository,
        ownershipService,
        consultantAgencyRepository,
        accounts,
        eventNotificationService,
        handoverEmails,
        homeserver.gateway(),
        repairService,
        mock(MatrixSessionSystemMessageService.class),
        mock(ConsultantService.class),
        caller,
        new ConsultantDisplayNameResolver(),
        mock(ScheduledTaskClaimService.class),
        Clock.systemUTC(),
        transactionManager,
        mock(AgencyService.class),
        mock(AdminScope.class),
        mock(MembersOnlyChatAccess.class));
  }

  private static Consultant consultant(String id, String matrixUserId, LocalDateTime now) {
    return Consultant.builder()
        .id(id)
        .matrixUserId(matrixUserId)
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
        .tenantId(TENANT_ID)
        .status(ConsultantStatus.IN_PROGRESS)
        .createDate(now)
        .updateDate(now)
        .build();
  }
}
