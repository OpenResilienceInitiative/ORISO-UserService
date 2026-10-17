package de.caritas.cob.userservice.api.service;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.FakeSynapseHomeserver;
import de.caritas.cob.userservice.api.adapters.matrix.FakeSynapseWithSelfLeave;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverMatrixRepairTaskRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService.RoomRef;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MariaDB proof for #1421, finding from #1424: the durable LEAVE task of a counsellor who moved to
 * another Träger hands the technical Matrix admin the owner power level before the counsellor
 * leaves. The queue is real (Liquibase schema, JPA, row locks, retry bookkeeping); the chat server
 * is a homeserver double that remembers membership and power levels and refuses what Synapse would
 * refuse.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseHandoverLeaveAdminPowerMariaDbIT {

  private static final long TENANT_ID = 7L;
  private static final long AGENCY_ID = 1421L;
  private static final String USER_ID = "leave-power-1421-asker";
  private static final String MOVER_ID = "leave-power-1421-mover";

  private static final String ASKER_MXID = FakeSynapseHomeserver.mxid("asker1421it");
  private static final String MOVER_MXID = FakeSynapseHomeserver.mxid("mover1421it");
  private static final String ADMIN_MXID = FakeSynapseHomeserver.adminMxid();

  @Autowired private SessionRepository sessionRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private CaseHandoverMatrixRepairTaskRepository repairTaskRepository;
  @Autowired private CaseHandoverRequestRepository caseHandoverRequestRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private final List<Long> sessionIds = new java.util.ArrayList<>();
  private TransactionTemplate transactions;
  private FakeSynapseHomeserver homeserver;
  private MatrixSynapseService gateway;
  private CaseHandoverMatrixRepairService repairService;

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
  void createWorld() {
    TenantContext.setCurrentTenant(TENANT_ID);
    transactions = new TransactionTemplate(transactionManager);
    homeserver = new FakeSynapseHomeserver();
    gateway = spy(new FakeSynapseWithSelfLeave(homeserver).gateway());
    repairService =
        new CaseHandoverMatrixRepairService(
            repairTaskRepository,
            caseHandoverRequestRepository,
            gateway,
            sessionRepository,
            Clock.systemUTC(),
            transactionManager,
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(repairService, "maxAttempts", 100);
    ReflectionTestUtils.setField(repairService, "retryBackoff", Duration.ZERO);
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
          consultantRepository.save(
              Consultant.builder()
                  .id(MOVER_ID)
                  .matrixUserId(MOVER_MXID)
                  .username(MOVER_ID)
                  .firstName(MOVER_ID)
                  .lastName("Proof")
                  .email(MOVER_ID + "@example.invalid")
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
                  .build());
        });
  }

  @AfterEach
  void removeWorld() {
    TenantContext.clear();
    transactions.executeWithoutResult(
        ignored -> {
          repairTaskRepository.deleteAll(
              repairTaskRepository.findByActionAndMemberId(
                  CaseHandoverMatrixRepairAction.LEAVE, MOVER_MXID));
          for (Long sessionId : sessionIds) {
            sessionRepository.deleteById(sessionId);
          }
          consultantRepository.deleteById(MOVER_ID);
          userRepository.deleteById(USER_ID);
        });
  }

  @Test
  void theAdminHoldsPower100WhenTheMovedCounsellorsDurableLeaveIsDone() {
    Long sessionId = givenCaseOfTheMover();
    String room = roomOf(sessionId);
    queueLeaves(new RoomRef(sessionId, room));

    drainQueue();

    assertThat(homeserver.isMember(room, MOVER_MXID)).isFalse();
    assertThat(homeserver.powerOf(room, ADMIN_MXID)).isEqualTo(100);
    assertThat(homeserver.members(room)).containsExactlyInAnyOrder(ASKER_MXID, ADMIN_MXID);
    assertThat(leaveTasks()).isEmpty();
  }

  @Test
  void aFailedHandOverKeepsTheCounsellorAndTheDurableTaskUntilTheNextTrySucceeds() {
    Long sessionId = givenCaseOfTheMover();
    String room = roomOf(sessionId);
    queueLeaves(new RoomRef(sessionId, room));
    doReturn(false).doCallRealMethod().when(gateway).ensureAdminHoldsRoomPower(any(), any(), any());

    drainQueue();

    assertThat(homeserver.isMember(room, MOVER_MXID)).isTrue();
    assertThat(homeserver.powerOf(room, ADMIN_MXID)).isZero();
    var pending = leaveTasks();
    assertThat(pending).hasSize(1);
    assertThat(pending.get(0).getAttemptCount()).isEqualTo(1);

    drainQueue();

    assertThat(homeserver.isMember(room, MOVER_MXID)).isFalse();
    assertThat(homeserver.powerOf(room, ADMIN_MXID)).isEqualTo(100);
    assertThat(leaveTasks()).isEmpty();
  }

  @Test
  void everyRoomOfTheMoveGetsItsAdminPowerNotJustTheFirst() {
    Long first = givenCaseOfTheMover();
    Long second = givenCaseOfTheMover();
    queueLeaves(new RoomRef(first, roomOf(first)), new RoomRef(second, roomOf(second)));

    drainQueue();

    for (Long sessionId : List.of(first, second)) {
      assertThat(homeserver.powerOf(roomOf(sessionId), ADMIN_MXID)).isEqualTo(100);
      assertThat(homeserver.isMember(roomOf(sessionId), MOVER_MXID)).isFalse();
    }
    assertThat(leaveTasks()).isEmpty();
  }

  // ── world building ────────────────────────────────────────────────────────

  private void queueLeaves(RoomRef... rooms) {
    transactions.executeWithoutResult(
        ignored -> repairService.enqueueLeaves(MOVER_MXID, MOVER_ID, List.of(rooms)));
  }

  private void drainQueue() {
    for (Long taskId : repairService.pendingTaskIds()) {
      repairService.process(taskId);
    }
  }

  private List<de.caritas.cob.userservice.api.model.CaseHandoverMatrixRepairTask> leaveTasks() {
    return transactions.execute(
        ignored ->
            repairTaskRepository.findByActionAndMemberId(
                CaseHandoverMatrixRepairAction.LEAVE, MOVER_MXID));
  }

  /** An accepted case of the mover, with the room as ORISO provisions it. */
  private Long givenCaseOfTheMover() {
    String room = homeserver.createRoom(MOVER_MXID);
    homeserver.member(room, ASKER_MXID, 0).member(room, ADMIN_MXID, 0);
    Long sessionId =
        transactions.execute(
            ignored -> {
              LocalDateTime now = LocalDateTime.now();
              return sessionRepository
                  .save(
                      Session.builder()
                          .user(userRepository.findById(USER_ID).orElseThrow())
                          .consultant(consultantRepository.findById(MOVER_ID).orElseThrow())
                          .tenantId(TENANT_ID)
                          .agencyId(AGENCY_ID)
                          .consultingTypeId(0)
                          .registrationType(REGISTERED)
                          .conversationType(ConversationType.AGENCY_COUNSELLING)
                          .postcode("00000")
                          .languageCode(LanguageCode.de)
                          .status(IN_PROGRESS)
                          .teamSession(false)
                          .isConsultantDirectlySet(false)
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
    return transactions.execute(
        ignored -> sessionRepository.findById(sessionId).orElseThrow().getMatrixRoomId());
  }
}
