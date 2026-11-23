package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomAttempt.InitialContact;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomAttempt.RequesterJoinPlan;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRoomAttemptRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.InitialRoomContact;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@Import({
  AgencyEnquiryRoomPublication.class,
  AgencyEnquiryRevocations.class,
  AgencyEnquiryRoomAttempts.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgencyEnquiryRoomPublicationDatabaseIT {

  private static final long TENANT_ID = 47L;
  private static final long AGENCY_ID = 471179L;
  private static final String ROOM = "!new:example.invalid";
  private static final String MEMBER_ID = "@member:example.invalid";

  @Autowired private AgencyEnquiryRoomPublication publication;
  @Autowired private AgencyEnquiryRevocations revocations;
  @Autowired private AgencyEnquiryRevocationTaskRepository tasks;
  @Autowired private AgencyEnquiryRoomAttempts roomAttempts;
  @Autowired private AgencyEnquiryRoomAttemptRepository attempts;
  @Autowired private ConsultantRepository consultants;
  @Autowired private ConsultantAgencyRepository assignments;
  @Autowired private SessionRepository sessions;
  @Autowired private UserRepository users;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @MockitoBean private Clock clock;
  @MockitoBean private SessionService sessionService;
  @MockitoBean private AgencyEnquiryRoomRetirements retiredRooms;
  @MockitoBean private AgencyEnquiryRoomDecisions roomDecisions;
  @MockitoBean private AgencyMatrixCredentialClient agencyCredentials;
  @MockitoBean private SessionRoomGateway matrix;

  private Session enquiry;
  private String consultantId;
  private String userId;
  private Long relationId;
  private final List<String> extraConsultantIds = new ArrayList<>();
  private final List<Long> attemptIds = new ArrayList<>();

  @DynamicPropertySource
  static void configureMariaDb(DynamicPropertyRegistry registry) {
    var url = System.getenv("LIQUIBASE_IT_DB_URL");
    if (url == null || url.isBlank()) {
      return;
    }
    registry.add("spring.datasource.url", () -> url);
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
  }

  @BeforeEach
  void seedUnboundEnquiryAndCurrentMember() {
    TenantContext.setCurrentTenant(TENANT_ID);
    when(clock.instant()).thenReturn(Instant.parse("2026-11-19T12:00:00Z"));
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              var now = LocalDateTime.now(clock);
              consultantId = UUID.randomUUID().toString();
              userId = UUID.randomUUID().toString();
              var consultant = registerConsultant(consultantId, MEMBER_ID);
              relationId = registerAssignment(consultant, null).getId();
              var user =
                  users.save(
                      User.builder()
                          .userId(userId)
                          .username(userId)
                          .email(userId + "@example.invalid")
                          .matrixUserId("@requester:example.invalid")
                          .tenantId(TENANT_ID)
                          .languageCode(LanguageCode.de)
                          .encourage2fa(false)
                          .magicLinkLoginEnabled(false)
                          .createDate(now)
                          .updateDate(now)
                          .build());
              enquiry =
                  sessions.save(
                      Session.builder()
                          .user(user)
                          .agencyId(AGENCY_ID)
                          .tenantId(TENANT_ID)
                          .status(SessionStatus.INITIAL)
                          .isConsultantDirectlySet(false)
                          .postcode("12345")
                          .languageCode(LanguageCode.de)
                          .registrationType(Session.RegistrationType.REGISTERED)
                          .createDate(now)
                          .updateDate(now)
                          .build());
            });
  }

  @AfterEach
  void removeOnlyTheTestData() {
    try {
      attempts.deleteAllById(attemptIds);
      if (enquiry != null) {
        tasks.findAll().stream()
            .filter(task -> enquiry.getId().equals(task.getSessionId()))
            .forEach(tasks::delete);
        sessions.deleteById(enquiry.getId());
        users.deleteById(userId);
        deleteConsultantAndAssignments(consultantId);
        extraConsultantIds.forEach(this::deleteConsultantAndAssignments);
      }
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void roomAttemptSurvivesBindingRollbackAndCompletesWithACommittedBinding() {
    var attempt = recordInvitationAttempt(ROOM);
    var binding = new TransactionTemplate(transactionManager);
    binding.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

    binding.executeWithoutResult(
        status -> {
          assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);
          status.setRollbackOnly();
        });

    assertThat(sessions.findById(enquiry.getId()).orElseThrow().getMatrixRoomId()).isNull();
    assertThat(attempts.findById(attempt).orElseThrow().isRequesterInvitationStarted()).isTrue();

    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);

    assertThat(sessions.findById(enquiry.getId()).orElseThrow().getMatrixRoomId()).isEqualTo(ROOM);
    assertThat(attempts.findById(attempt)).isEmpty();
  }

  @Test
  void keepsTheAttemptWhenAnotherRoomAlreadyHoldsTheBinding() {
    var losingAttempt = recordInvitationAttempt(ROOM);
    var winningRoom = "!winning:example.invalid";
    var winningAttempt = recordInvitationAttempt(winningRoom);

    assertThat(publication.publish(enquiry, winningRoom, contacts())).contains(winningRoom);
    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(winningRoom);

    assertThat(sessions.findById(enquiry.getId()).orElseThrow().getMatrixRoomId())
        .isEqualTo(winningRoom);
    assertThat(attempts.findById(winningAttempt)).isEmpty();
    assertThat(attempts.findById(losingAttempt).orElseThrow().isRequesterInvitationStarted())
        .isTrue();
  }

  @Test
  void clearsOnlyTheMatchingAttemptWhenTheSameRoomIsAlreadyBound() {
    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);
    var matchingAttempt = recordInvitationAttempt(ROOM);
    var differentAttempt = recordInvitationAttempt("!other:example.invalid");

    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);

    assertThat(sessions.findById(enquiry.getId()).orElseThrow().getMatrixRoomId()).isEqualTo(ROOM);
    assertThat(attempts.findById(matchingAttempt)).isEmpty();
    assertThat(attempts.findById(differentAttempt).orElseThrow().isRequesterInvitationStarted())
        .isTrue();
  }

  @Test
  void usesThePersistedJoinAfterItsFullDelayAndRejectsAnExpiredClaim() {
    var attemptId = recordInvitationAttempt(ROOM);
    var requester = enquiry.getUser();
    var plan =
        new RequesterJoinPlan(
            requester.getUserId(),
            requester.getMatrixUserId(),
            "@agency:example.invalid",
            false,
            List.of(new InitialContact(consultantId, MEMBER_ID)),
            List.of(consultantId));
    var first = roomAttempts.recordConfirmedInvitation(attemptId, plan);
    roomAttempts.deferRequesterJoin(first, 43_200L);

    var beforeDue = roomAttempts.claimRequesterJoin(enquiry, requester);
    assertThat(beforeDue.pending()).isTrue();
    assertThat(beforeDue.claim()).isEmpty();
    assertThat(attempts.findById(attemptId).orElseThrow().getRequesterJoinPlan()).isEqualTo(plan);

    when(clock.instant()).thenReturn(Instant.parse("2026-11-19T12:00:43.199Z"));
    assertThat(roomAttempts.claimRequesterJoin(enquiry, requester).claim()).isEmpty();

    when(clock.instant()).thenReturn(Instant.parse("2026-11-19T12:00:43.200Z"));
    var resumed = roomAttempts.claimRequesterJoin(enquiry, requester).claim().orElseThrow();
    assertThat(resumed.token()).isNotEqualTo(first.token());
    assertThat(resumed.roomId()).isEqualTo(ROOM);
    assertThat(resumed.plan()).isEqualTo(plan);
    assertThat(roomAttempts.claimRequesterJoin(enquiry, requester).claim()).isEmpty();
    assertThat(publication.publishPrepared(enquiry, first, contacts())).isEmpty();
    assertThat(publication.publishPrepared(enquiry, resumed, contacts())).contains(ROOM);
    assertThat(attempts.findById(attemptId)).isEmpty();
  }

  @Test
  void anEarlierClaimCannotFinishANewerLeaseAndAnUnconfirmedInviteIsNeverResumed() {
    recordInvitationAttempt("!legacy:example.invalid");
    var requester = enquiry.getUser();
    assertThat(roomAttempts.claimRequesterJoin(enquiry, requester).pending()).isFalse();

    var attemptId = recordInvitationAttempt(ROOM);
    var plan =
        new RequesterJoinPlan(
            requester.getUserId(),
            requester.getMatrixUserId(),
            "@agency:example.invalid",
            false,
            List.of(),
            List.of());
    var first = roomAttempts.recordConfirmedInvitation(attemptId, plan);
    when(clock.instant()).thenReturn(Instant.parse("2026-11-19T12:00:31Z"));
    var reclaimed = roomAttempts.claimRequesterJoin(enquiry, requester).claim().orElseThrow();

    roomAttempts.finishRequesterJoin(first);

    assertThat(attempts.findById(attemptId).orElseThrow().getRequesterJoinClaim())
        .isEqualTo(reclaimed.token());
  }

  @Test
  void aDelayOutsideTheDatabaseDateRangeKeepsTheSameRoomWaiting() {
    var attemptId = recordInvitationAttempt(ROOM);
    var requester = enquiry.getUser();
    var plan =
        new RequesterJoinPlan(
            requester.getUserId(),
            requester.getMatrixUserId(),
            "@agency:example.invalid",
            false,
            List.of(),
            List.of());
    var first = roomAttempts.recordConfirmedInvitation(attemptId, plan);

    roomAttempts.deferRequesterJoin(first, Long.MAX_VALUE);

    var stored = attempts.findById(attemptId).orElseThrow();
    assertThat(stored.getRequesterJoinReadyAt())
        .isEqualTo(LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000));
    assertThat(stored.getRequesterJoinClaim()).isNull();
    assertThat(stored.getRequesterJoinPlan()).isEqualTo(plan);
    assertThat(stored.getRoomId()).isEqualTo(ROOM);
    var recovery = roomAttempts.claimRequesterJoin(enquiry, requester);
    assertThat(recovery.pending()).isTrue();
    assertThat(recovery.claim()).isEmpty();
  }

  @Test
  void concurrentReopensClaimExactlyOnePersistedAttempt() throws Exception {
    var attemptId = recordInvitationAttempt(ROOM);
    var requester = enquiry.getUser();
    var plan =
        new RequesterJoinPlan(
            requester.getUserId(),
            requester.getMatrixUserId(),
            "@agency:example.invalid",
            false,
            List.of(),
            List.of());
    var first = roomAttempts.recordConfirmedInvitation(attemptId, plan);
    roomAttempts.deferRequesterJoin(first, 1_000L);
    when(clock.instant()).thenReturn(Instant.parse("2026-11-19T12:00:01Z"));

    // Keep the first claim in an explicit transaction so the second reaches the real lock while
    // it is held. Invoking the target directly keeps REQUIRES_NEW from committing it early.
    AgencyEnquiryRoomAttempts target = AopTestUtils.getTargetObject(roomAttempts);
    var firstClaimed = new CountDownLatch(1);
    var contenderConnected = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setTimeout(8);
    var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
    var owner =
        executor.submit(
            () -> {
              TenantContext.setCurrentTenant(TENANT_ID);
              try {
                return transaction.execute(
                    status -> {
                      var claim = target.claimRequesterJoin(enquiry, requester).claim();
                      firstClaimed.countDown();
                      awaitClaimFixture(release);
                      return claim;
                    });
              } finally {
                TenantContext.clear();
              }
            });
    try {
      awaitClaimFixture(firstClaimed);
      var contender =
          executor.submit(
              () -> {
                TenantContext.setCurrentTenant(TENANT_ID);
                try {
                  return transaction.execute(
                      status -> {
                        jdbc.queryForObject("SELECT 1", Integer.class);
                        contenderConnected.countDown();
                        return target.claimRequesterJoin(enquiry, requester);
                      });
                } finally {
                  TenantContext.clear();
                }
              });
      awaitClaimFixture(contenderConnected);
      assertThatThrownBy(() -> contender.get(250, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      release.countDown();

      assertThat(owner.get(5, TimeUnit.SECONDS))
          .hasValueSatisfying(claim -> assertThat(claim.attemptId()).isEqualTo(attemptId));
      var recovery = contender.get(5, TimeUnit.SECONDS);
      assertThat(recovery.pending()).isTrue();
      assertThat(recovery.claim()).isEmpty();
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(9, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void awaitClaimFixture(CountDownLatch signal) {
    try {
      if (!signal.await(4, TimeUnit.SECONDS)) {
        throw new IllegalStateException("The claim fixture did not reach its checkpoint");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("The claim fixture was interrupted", interrupted);
    }
  }

  private Long recordInvitationAttempt(String room) {
    var attempt = roomAttempts.recordCreated(enquiry, room);
    attemptIds.add(attempt);
    roomAttempts.markRequesterInvitationStarted(attempt);
    return attempt;
  }

  @Test
  void departureFirstIsCorrectedWhenTheRoomIsPublished() {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> detach());
    assertThat(pending()).isEmpty();

    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);

    assertThat(sessions.findById(enquiry.getId()).orElseThrow().getMatrixRoomId()).isEqualTo(ROOM);
    assertOneCorrection();
  }

  @Test
  void departureAfterPublicationCapturesTheFirstRoom() {
    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);
    assertThat(pending()).isEmpty();

    new TransactionTemplate(transactionManager).executeWithoutResult(status -> detach());

    assertOneCorrection();
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
  void publicationFirstIsVisibleToADepartureWithAnOlderMariaDbSnapshot() {
    var departure = new TransactionTemplate(transactionManager);
    departure.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    departure.executeWithoutResult(
        status -> {
          // Establish an older snapshot before the competing transaction commits its binding.
          assertThat(sessions.findById(enquiry.getId()).orElseThrow().getMatrixRoomId()).isNull();
          var publicationTransaction = new TransactionTemplate(transactionManager);
          publicationTransaction.setPropagationBehavior(
              TransactionDefinition.PROPAGATION_REQUIRES_NEW);
          publicationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
          publicationTransaction.executeWithoutResult(
              ignored -> assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM));
          detach();
        });

    assertOneCorrection();
    assertAgencyLeadingIndexIsAvailable();
  }

  @Test
  void aValidReassignmentBeforePublicationIsKept() {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(status -> detach());
    transaction.executeWithoutResult(
        status -> {
          consultants.lockConsultant(consultantId);
          var relation = assignments.findById(relationId).orElseThrow();
          relation.setDeleteDate(null);
        });

    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);
    assertThat(pending()).isEmpty();
    verifyNoInteractions(matrix);
  }

  @Test
  void publicationAndCorrectionRollBackTogether() {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> detach());
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);
          status.setRollbackOnly();
        });

    assertThat(sessions.findById(enquiry.getId()).orElseThrow().getMatrixRoomId()).isNull();
    assertThat(pending()).isEmpty();
    verifyNoInteractions(matrix);
  }

  @Test
  void assignmentReadUnderPublicationLocksIsConstantForTenActiveContacts() {
    var active = new ArrayList<>(contacts());
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          for (int index = 1; index < 10; index++) {
            var id = UUID.randomUUID().toString();
            var member = "@member-" + index + ":example.invalid";
            extraConsultantIds.add(id);
            registerAssignment(registerConsultant(id, member), null);
            active.add(new InitialRoomContact(id, member));
          }
        });

    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    var wasEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      transaction.executeWithoutResult(
          status -> {
            active.stream()
                .map(InitialRoomContact::consultantId)
                .sorted()
                .forEach(consultants::lockConsultant);
            var locked = sessions.lockFresh(enquiry.getId()).orElseThrow();
            var before = statistics.getPrepareStatementCount();
            revocations.stageDuringPublication(locked, active);
            assertThat(statistics.getPrepareStatementCount() - before).isEqualTo(1);
          });
    } finally {
      statistics.setStatisticsEnabled(wasEnabled);
    }
    assertThat(pending()).isEmpty();
  }

  @Test
  void choosesLatestDepartureThenHighestIdAndKeepsTheFirstExactMember() {
    var transaction = new TransactionTemplate(transactionManager);
    var latestId =
        transaction.execute(
            status -> {
              var relation = assignments.findById(relationId).orElseThrow();
              var now = LocalDateTime.now(clock);
              relation.setDeleteDate(now);
              var latest = registerAssignment(relation.getConsultant(), now);
              registerAssignment(relation.getConsultant(), now.minusMinutes(1));
              return latest.getId();
            });

    var contacted =
        List.of(
            new InitialRoomContact(consultantId, MEMBER_ID),
            new InitialRoomContact(consultantId, "@other:example.invalid"));
    assertThat(publication.publish(enquiry, ROOM, contacted)).contains(ROOM);
    transaction.executeWithoutResult(
        status -> {
          consultants.lockConsultant(consultantId);
          var locked = sessions.lockFresh(enquiry.getId()).orElseThrow();
          revocations.stageDuringPublication(locked, contacted);
        });

    assertThat(
            tasks.findAll().stream()
                .filter(task -> enquiry.getId().equals(task.getSessionId()))
                .toList())
        .singleElement()
        .satisfies(
            task -> {
              assertThat(task.getConsultantAgencyId()).isEqualTo(latestId);
              assertThat(task.getConsultantId()).isEqualTo(consultantId);
              assertThat(task.getMemberId()).isEqualTo(MEMBER_ID);
              assertThat(task.getRoomId()).isEqualTo(ROOM);
            });
    verifyNoInteractions(matrix);
  }

  @Test
  void publicationKeepsSeparateTasksForExactRooms() {
    var otherRoom = "!NEW:example.invalid";
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              var relation = assignments.findById(relationId).orElseThrow();
              var now = LocalDateTime.now(clock);
              relation.setDeleteDate(now);
              tasks.saveAndFlush(
                  AgencyEnquiryRevocationTask.builder()
                      .consultantAgencyId(relationId)
                      .consultantId(consultantId)
                      .agencyId(AGENCY_ID)
                      .sessionId(enquiry.getId())
                      .roomId(otherRoom)
                      .memberId(MEMBER_ID)
                      .retryAt(now)
                      .build());
            });

    assertThat(
            assignments.findPublicationRevocationCandidates(
                AGENCY_ID, List.of(consultantId), enquiry.getId(), otherRoom))
        .isEmpty();

    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);

    assertThat(pending())
        .extracting(AgencyEnquiryRevocationTask::getRoomId)
        .containsExactlyInAnyOrder(otherRoom, ROOM);
    verifyNoInteractions(matrix);
  }

  @Test
  void anActiveRegrantSuppressesCorrectionEvenWithRemovedHistory() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              var relation = assignments.findById(relationId).orElseThrow();
              relation.setDeleteDate(LocalDateTime.now(clock));
              registerAssignment(relation.getConsultant(), null);
            });

    assertThat(publication.publish(enquiry, ROOM, contacts())).contains(ROOM);
    assertThat(
            tasks.findAll().stream()
                .filter(task -> enquiry.getId().equals(task.getSessionId()))
                .toList())
        .isEmpty();
    verifyNoInteractions(matrix);
  }

  private Consultant registerConsultant(String id, String memberId) {
    var now = LocalDateTime.now(clock);
    return consultants.save(
        Consultant.builder()
            .id(id)
            .username(id)
            .firstName("Test")
            .lastName("Counsellor")
            .email(id + "@example.invalid")
            .matrixUserId(memberId)
            .tenantId(TENANT_ID)
            .languageCode(LanguageCode.de)
            .encourage2fa(false)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(false)
            .notifyNewChatMessageFromAdviceSeeker(false)
            .createDate(now)
            .updateDate(now)
            .build());
  }

  private ConsultantAgency registerAssignment(Consultant consultant, LocalDateTime deleteDate) {
    var now = LocalDateTime.now(clock);
    return assignments.save(
        ConsultantAgency.builder()
            .consultant(consultant)
            .status(ConsultantAgencyStatus.CREATED)
            .agencyId(AGENCY_ID)
            .tenantId(TENANT_ID)
            .createDate(now)
            .updateDate(now)
            .deleteDate(deleteDate)
            .build());
  }

  private void deleteConsultantAndAssignments(String id) {
    assignments.deleteAll(assignments.findByConsultantId(id));
    consultants.deleteById(id);
  }

  private void detach() {
    consultants.lockConsultant(consultantId);
    var relation = assignments.findById(relationId).orElseThrow();
    relation.setDeleteDate(LocalDateTime.now(clock));
    revocations.stage(relation);
  }

  private List<InitialRoomContact> contacts() {
    return List.of(new InitialRoomContact(consultantId, MEMBER_ID));
  }

  private void assertAgencyLeadingIndexIsAvailable() {
    var plan =
        jdbc.queryForMap(
            """
            EXPLAIN SELECT id, matrix_room_id FROM session
            WHERE tenant_id = ? AND agency_id = ?
              AND status IN (?, ?) AND consultant_id IS NULL
              AND (is_consultant_directly_set IS NULL OR is_consultant_directly_set = FALSE)
              AND matrix_room_id IS NOT NULL AND TRIM(matrix_room_id) <> ''
            LOCK IN SHARE MODE
            """,
            TENANT_ID,
            AGENCY_ID,
            SessionStatus.INITIAL.getValue(),
            SessionStatus.NEW.getValue());
    assertThat((String) plan.get("possible_keys")).contains("idx_session_agency_consultant_status");
  }

  private List<AgencyEnquiryRevocationTask> pending() {
    return tasks.findAll().stream()
        .filter(task -> relationId.equals(task.getConsultantAgencyId()))
        .toList();
  }

  private void assertOneCorrection() {
    assertThat(pending())
        .singleElement()
        .satisfies(
            task -> {
              assertThat(task.getRoomId()).isEqualTo(ROOM);
              assertThat(task.getMemberId()).isEqualTo(MEMBER_ID);
              assertThat(task.getSessionId()).isEqualTo(enquiry.getId());
            });
    verifyNoInteractions(matrix);
  }
}
