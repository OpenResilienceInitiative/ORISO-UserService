package de.caritas.cob.userservice.api.service.session;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision.Outcome;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.matrix.InquiryAcceptanceNoticeStore;
import de.caritas.cob.userservice.api.service.matrix.OrisoRoomRegistry;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimWriter;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  AgencyEnquiryRevocations.class,
  AgencyEnquiryRoomDecisions.class,
  AgencyEnquiryRoomRetirements.class,
  OrisoRoomRegistry.class,
  SessionOwnershipService.class,
  ScheduledTaskClaimService.class,
  ScheduledTaskClaimWriter.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgencyEnquiryAcceptanceDepartureIT {

  private static final AtomicLong AGENCY_IDS = new AtomicLong(169200L);
  private static final long TENANT_ID = 417L;
  private static final String MEMBER_TOKEN = "member-token";
  private static final Instant SAME_INSTANT = Instant.parse("2026-11-19T12:00:00Z");

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConsultantRepository consultants;
  @Autowired private ConsultantAgencyRepository assignments;
  @Autowired private SessionRepository sessions;
  @Autowired private UserRepository users;
  @Autowired private AgencyEnquiryRevocationTaskRepository tasks;
  @Autowired private SessionOwnershipService ownership;
  @Autowired private AgencyEnquiryRoomRetirements retirements;
  @Autowired private AgencyEnquiryRevocations revocations;
  @Autowired private ScheduledTaskClaimService scheduledClaims;
  @MockitoBean private Clock clock;
  @MockitoBean private AgencyMatrixCredentialClient agencyCredentials;
  @MockitoBean private InquiryAcceptanceNoticeStore acceptanceNotices;
  @MockitoBean private SessionRoomGateway matrix;
  @MockitoSpyBean private AgencyEnquiryRoomDecisions roomDecisions;

  private final long agencyId = AGENCY_IDS.incrementAndGet();
  private final String oldRoom = "!former-" + agencyId + ":example.invalid";
  private final String newRoom = "!replacement-" + agencyId + ":example.invalid";
  private Consultant departed;
  private Consultant owner;
  private Long relationId;
  private Long sessionId;
  private String askerId;

  @DynamicPropertySource
  static void useMariaDbWhenProvided(DynamicPropertyRegistry registry) {
    AgencyEnquiryRoomPublicationDatabaseIT.configureMariaDb(registry);
  }

  @BeforeEach
  void seedOpenRegisteredEnquiry() {
    TenantContext.setCurrentTenant(TENANT_ID);
    when(clock.instant()).thenReturn(SAME_INSTANT);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    readCommitted()
        .executeWithoutResult(
            status -> {
              departed = saveConsultant();
              owner = saveConsultant();
              relationId = addAssignment(departed).getId();
              addAssignment(owner);
              askerId = UUID.randomUUID().toString();
              var now = LocalDateTime.now(clock);
              var asker =
                  users.save(
                      User.builder()
                          .userId(askerId)
                          .username(askerId)
                          .email(askerId + "@example.invalid")
                          .tenantId(TENANT_ID)
                          .languageCode(LanguageCode.de)
                          .encourage2fa(false)
                          .magicLinkLoginEnabled(false)
                          .createDate(now)
                          .updateDate(now)
                          .build());
              sessionId =
                  sessions
                      .save(
                          Session.builder()
                              .user(asker)
                              .agencyId(agencyId)
                              .tenantId(TENANT_ID)
                              .status(SessionStatus.NEW)
                              .registrationType(Session.RegistrationType.REGISTERED)
                              .conversationType(ConversationType.AGENCY_COUNSELLING)
                              .isConsultantDirectlySet(false)
                              .matrixRoomId(oldRoom)
                              .postcode("12345")
                              .languageCode(LanguageCode.de)
                              .createDate(now)
                              .updateDate(now)
                              .build())
                      .getId();
            });
  }

  @AfterEach
  void removeOnlyThisEnquiryAndItsRoster() {
    try {
      if (sessionId != null) {
        jdbc.update("DELETE FROM agency_enquiry_revocation_task WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM agency_enquiry_room_decision WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM agency_enquiry_room_retirement WHERE session_id = ?", sessionId);
        sessions.deleteById(sessionId);
        jdbc.update(
            "DELETE FROM consultant_agency WHERE agency_id = ? AND tenant_id = ?",
            agencyId,
            TENANT_ID);
        consultants.deleteById(departed.getId());
        consultants.deleteById(owner.getId());
        users.deleteById(askerId);
      }
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void purgesSettledDecisionsOnlyAfterCommittedErasure() {
    long decision = addDecision(1, Outcome.REUSED);

    readCommitted()
        .executeWithoutResult(
            status -> {
              eraseCase();
              status.setRollbackOnly();
            });
    TenantContext.runIn(TENANT_ID + 1, revocations::retryDue);
    assertThat(hasDecision(decision)).isTrue();

    eraseCase();
    revocations.retryDue();

    assertThat(hasDecision(decision)).isFalse();
    revocations.retryDue();
    assertThat(hasDecision(decision)).isFalse();
  }

  @Test
  void retainsPendingDecisionsAndBothKindsOfAccessRemovalWork() {
    long pending = addDecision(1, Outcome.PENDING);
    long parked = addDecision(2, Outcome.REPLACED);
    long released = addDecision(3, Outcome.REOPENED);
    addDecisionTask(parked, relationId, AgencyEnquiryRevocationTask.WAITING_FOR_ROOM_DECISION);
    addDecisionTask(released, relationId + 1, LocalDateTime.now(clock).plusMinutes(1));
    eraseCase();

    revocations.retryDue();
    assertThat(hasDecision(pending)).isTrue();
    assertThat(hasDecision(parked)).isTrue();
    assertThat(hasDecision(released)).isTrue();

    jdbc.update("DELETE FROM agency_enquiry_revocation_task WHERE room_decision_id = ?", released);
    revocations.retryDue();
    assertThat(hasDecision(parked)).isTrue();
    assertThat(hasDecision(released)).isFalse();
    assertThat(hasDecision(pending)).isTrue();
  }

  @Test
  @Timeout(15)
  @EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
  void retainsDecisionWhenLockedDepartureAddsAReferenceAfterSelection() throws Exception {
    long decision = addDecision(1, Outcome.REPLACED);
    eraseCase();
    try (var workers = Executors.newSingleThreadExecutor()) {
      var sweep =
          readCommitted()
              .execute(
                  status -> {
                    jdbc.queryForObject(
                        "SELECT id FROM agency_enquiry_room_decision WHERE id = ? FOR UPDATE",
                        Long.class,
                        decision);
                    var started = new CountDownLatch(1);
                    var result =
                        workers.submit(
                            () -> {
                              started.countDown();
                              roomDecisions.purgeErasedBatch(1);
                            });
                    await(started);
                    assertThatThrownBy(() -> result.get(200, MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                    addDecisionTask(decision, relationId, LocalDateTime.now(clock).plusMinutes(1));
                    return result;
                  });
      assertThat(sweep).isNotNull();
      sweep.get(5, SECONDS);
    }
    assertThat(hasDecision(decision)).isTrue();
  }

  @Test
  void limitsCleanupToTheRequestedNumberOfDecisions() {
    long earlier = addDecision(1, Outcome.REPLACED);
    long later = addDecision(2, Outcome.REOPENED);
    eraseCase();

    roomDecisions.purgeErasedBatch(1);

    assertThat(hasDecision(earlier)).isFalse();
    assertThat(hasDecision(later)).isTrue();
    roomDecisions.purgeErasedBatch(1);
    assertThat(hasDecision(later)).isFalse();
  }

  private long addDecision(long revision, Outcome outcome) {
    long id =
        jdbc.queryForObject(
            "SELECT NEXT VALUE FOR " + AgencyEnquiryRoomDecision.EVENT_SEQUENCE, Long.class);
    jdbc.update(
        """
        INSERT INTO agency_enquiry_room_decision
          (id, session_id, agency_id, tenant_id, room_id, ownership_revision, outcome, resolved_order)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        sessionId,
        agencyId,
        TENANT_ID,
        oldRoom,
        revision,
        outcome.name(),
        outcome == Outcome.PENDING ? null : id);
    return id;
  }

  private void addDecisionTask(long decision, long departure, LocalDateTime retryAt) {
    jdbc.update(
        """
        INSERT INTO agency_enquiry_revocation_task
          (consultant_agency_id, consultant_id, agency_id, session_id, room_id, member_id,
           retry_at, room_decision_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        departure,
        departed.getId(),
        agencyId,
        sessionId,
        oldRoom,
        departed.getMatrixUserId(),
        retryAt,
        decision);
  }

  private boolean hasDecision(long id) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM agency_enquiry_room_decision WHERE id = ?", Integer.class, id)
        == 1;
  }

  private void eraseCase() {
    assertThat(jdbc.update("DELETE FROM session WHERE id = ?", sessionId)).isOne();
  }

  @Test
  void separatelyCommittedOwnershipKeepsTheDepartureUntilReplacement() {
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);
    assertAssignedRoom(owner, oldRoom);

    readCommitted().executeWithoutResult(status -> stageDeparture());
    retirements.replaceRoom(accepted, assignment, newRoom);

    assertReplacementKeepsOnlyTheRequiredCorrection();
  }

  @Test
  @Timeout(15)
  void acceptanceInOneTransactionKeepsTheDepartureAfterTheRoomDecisionCommits() throws Exception {
    var acceptanceHoldingSession = new CountDownLatch(1);
    var departureOrderAllocated = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              long order = (long) invocation.callRealMethod();
              departureOrderAllocated.countDown();
              return order;
            })
        .when(AopTestUtils.<AgencyEnquiryRoomDecisions>getUltimateTargetObject(roomDecisions))
        .nextEvent();
    try (var threads = Executors.newFixedThreadPool(2)) {
      var accepting =
          threads.submit(
              () ->
                  TenantContext.runIn(
                      TENANT_ID,
                      () ->
                          readCommitted()
                              .executeWithoutResult(
                                  status -> {
                                    var accepted = sessions.lockFresh(sessionId).orElseThrow();
                                    var assignment =
                                        ownership.updateOwnerAndStatus(
                                            accepted, owner, SessionStatus.IN_PROGRESS);
                                    acceptanceHoldingSession.countDown();
                                    await(departureOrderAllocated);
                                    retirements.replaceRoom(accepted, assignment, newRoom);
                                  })));
      var departing =
          threads.submit(
              () ->
                  TenantContext.runIn(
                      TENANT_ID,
                      () -> {
                        await(acceptanceHoldingSession);
                        readCommitted().executeWithoutResult(status -> stageDeparture());
                      }));
      accepting.get(8, SECONDS);
      departing.get(8, SECONDS);
    }

    assertReplacementKeepsOnlyTheRequiredCorrection();
  }

  @Test
  void reusingTheHoldingRoomPreservesIntentionalSilentMembership() {
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);

    readCommitted().executeWithoutResult(status -> stageDeparture());
    assertThat(tasks.pendingSessionIds(relationId)).containsExactly(sessionId);
    var waitingId =
        jdbc.queryForObject(
            "SELECT id FROM agency_enquiry_revocation_task WHERE session_id = ?",
            Long.class,
            sessionId);
    assertThat(tasks.dueIds(LocalDateTime.now(clock), PageRequest.of(0, 10)))
        .doesNotContain(waitingId);
    revocations.retryDue();
    verifyNoInteractions(matrix);
    assertThat(tasks.pendingSessionIds(relationId)).containsExactly(sessionId);

    retirements.reuseRoom(accepted, assignment);
    revocations.retryDue();

    assertAssignedRoom(owner, oldRoom);
    assertThat(tasks.pendingSessionIds(relationId)).isEmpty();
    verifyNoInteractions(matrix);
  }

  @Test
  void departuresAfterReplacementDoNotScheduleTheFormerRoom() {
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);
    retirements.replaceRoom(accepted, assignment, newRoom);

    readCommitted().executeWithoutResult(status -> stageDeparture());
    revocations.retryDue();

    assertAssignedRoom(owner, newRoom);
    assertThat(tasks.pendingSessionIds(relationId)).isEmpty();
    verifyNoInteractions(matrix);
  }

  @Test
  void deferredWorkRetainsTheCurrentOwner() {
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, departed, SessionStatus.IN_PROGRESS);
    readCommitted().executeWithoutResult(status -> stageDeparture());
    assertThat(tasks.pendingSessionIds(relationId)).containsExactly(sessionId);
    retirements.replaceRoom(accepted, assignment, newRoom);

    revocations.retryDue();

    assertAssignedRoom(departed, newRoom);
    assertThat(tasks.pendingSessionIds(relationId)).isEmpty();
    verifyNoInteractions(matrix);
  }

  @Test
  void validReadditionTakesPrecedenceOverDeferredWork() {
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);
    readCommitted().executeWithoutResult(status -> stageDeparture());
    assertThat(tasks.pendingSessionIds(relationId)).containsExactly(sessionId);
    retirements.replaceRoom(accepted, assignment, newRoom);

    readCommitted()
        .executeWithoutResult(
            status -> {
              consultants.lockConsultant(departed.getId());
              addAssignment(departed);
            });
    revocations.retryDue();

    assertAssignedRoom(owner, newRoom);
    assertThat(tasks.pendingSessionIds(relationId)).isEmpty();
    verifyNoInteractions(matrix);
  }

  @Test
  void workStagedWhileOpenStillReconcilesWhenAcceptanceReusesTheRoom() {
    readCommitted().executeWithoutResult(status -> stageDeparture());
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);
    retirements.reuseRoom(accepted, assignment);
    when(matrix.isConfirmedOutOfRoom(oldRoom, departed.getMatrixUserId())).thenReturn(false, true);
    when(matrix.loginAsUser(departed.getMatrixUserId())).thenReturn(MEMBER_TOKEN);

    revocations.retryDue();

    assertAssignedRoom(owner, oldRoom);
    assertThat(tasks.pendingSessionIds(relationId)).isEmpty();
    verify(matrix).leaveRoom(oldRoom, MEMBER_TOKEN);
  }

  @Test
  void compensationRestoresTheOpenEnquiryAndItsPendingCorrection() {
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);
    readCommitted().executeWithoutResult(status -> stageDeparture());
    assertThat(tasks.pendingSessionIds(relationId)).containsExactly(sessionId);

    assertThat(
            ownership.compensateOwnerChange(
                sessionId, assignment, null, SessionStatus.NEW, LocalDateTime.now(clock)))
        .isTrue();
    when(matrix.isConfirmedOutOfRoom(oldRoom, departed.getMatrixUserId())).thenReturn(true);
    revocations.retryDue();

    var reopened = currentSession();
    assertThat(reopened.getConsultant()).isNull();
    assertThat(reopened.getStatus()).isEqualTo(SessionStatus.NEW);
    assertThat(reopened.getMatrixRoomId()).isEqualTo(oldRoom);
    assertThat(tasks.pendingSessionIds(relationId)).isEmpty();
    verify(matrix).isConfirmedOutOfRoom(oldRoom, departed.getMatrixUserId());
  }

  @ParameterizedTest
  @EnumSource(
      value = SessionStatus.class,
      names = {"NEW", "INITIAL"})
  void expiredPreparationReopensTheSameEnquiryAndUnparksItsDeparture(SessionStatus openingStatus) {
    readCommitted()
        .executeWithoutResult(
            status -> {
              var open = sessions.lockFresh(sessionId).orElseThrow();
              open.setStatus(openingStatus);
            });
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);
    readCommitted().executeWithoutResult(status -> stageDeparture());

    revocations.retryDue();
    assertAssignedRoom(owner, oldRoom);
    assertThat(tasks.dueIds(LocalDateTime.now(clock), PageRequest.of(0, 8))).isEmpty();

    when(clock.instant()).thenReturn(SAME_INSTANT.plusSeconds(300));
    revocations.retryDue();

    var reopened = currentSession();
    assertThat(reopened.getConsultant()).isNull();
    assertThat(reopened.getStatus()).isEqualTo(openingStatus);
    assertThat(reopened.getMatrixRoomId()).isEqualTo(oldRoom);
    assertThat(reopened.getOwnershipRevision()).isGreaterThan(assignment.revision());
    assertThat(tasks.dueIds(LocalDateTime.now(clock), PageRequest.of(0, 8))).hasSize(1);
    verify(acceptanceNotices).cancelPreparation(sessionId, assignment.revision());
    verifyNoInteractions(matrix);
  }

  @Test
  void schedulerRecoversAnExpiredPreparationUnderTheTechnicalTenant() {
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);
    readCommitted().executeWithoutResult(status -> stageDeparture());
    when(clock.instant()).thenReturn(SAME_INSTANT.plusSeconds(300));

    var scheduler =
        new AgencyEnquiryRevocationScheduler(revocations, scheduledClaims, Runnable::run);
    long unrelatedTenant = TENANT_ID + 1;
    TenantContext.runIn(
        unrelatedTenant,
        () -> {
          var visibleSession =
              readCommitted().execute(status -> sessions.findByIdForUpdate(sessionId));
          assertThat(visibleSession).isEmpty();
          scheduler.retryPending();
          assertThat(TenantContext.getCurrentTenant()).isEqualTo(unrelatedTenant);
        });

    assertThat(currentSession().getStatus()).isEqualTo(SessionStatus.NEW);
    assertThat(currentSession().getConsultant()).isNull();
    assertThat(tasks.dueIds(LocalDateTime.now(clock), PageRequest.of(0, 8))).hasSize(1);
    verify(acceptanceNotices).cancelPreparation(sessionId, assignment.revision());
    verifyNoInteractions(matrix);
  }

  @Test
  void schedulerHoldsDeparturesForAnOlderPodDecisionWithoutAPreparation() {
    var accepted = currentSession();
    ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);
    jdbc.update(
        """
        UPDATE agency_enquiry_room_decision
        SET opening_status = NULL, prepared_until = NULL
        WHERE session_id = ?
        """,
        sessionId);
    readCommitted().executeWithoutResult(status -> stageDeparture());
    when(clock.instant()).thenReturn(SAME_INSTANT.plusSeconds(300));

    new AgencyEnquiryRevocationScheduler(revocations, scheduledClaims, Runnable::run)
        .retryPending();

    assertAssignedRoom(owner, oldRoom);
    assertThat(tasks.pendingSessionIds(relationId)).containsExactly(sessionId);
    assertThat(tasks.dueIds(LocalDateTime.now(clock), PageRequest.of(0, 8))).isEmpty();
    verify(acceptanceNotices, never()).cancelPreparation(eq(sessionId), anyLong());
    verifyNoInteractions(matrix);
  }

  @Test
  void completedStartFenceKeepsAmbiguousRoomWorkHeld() {
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);
    retirements.startExternalWork(accepted, assignment);
    readCommitted().executeWithoutResult(status -> stageDeparture());

    when(clock.instant()).thenReturn(SAME_INSTANT.plusSeconds(300));
    revocations.retryDue();

    assertAssignedRoom(owner, oldRoom);
    assertThat(tasks.pendingSessionIds(relationId)).containsExactly(sessionId);
    assertThat(tasks.dueIds(LocalDateTime.now(clock), PageRequest.of(0, 8))).isEmpty();
    verifyNoInteractions(matrix);
  }

  @Test
  void expiredAndReopenedPreparationCannotStartExternalWork() {
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);
    when(clock.instant()).thenReturn(SAME_INSTANT.plusSeconds(300));

    assertThatThrownBy(() -> retirements.startExternalWork(accepted, assignment))
        .isInstanceOf(ConflictException.class);
    revocations.retryDue();
    assertThatThrownBy(() -> retirements.startExternalWork(accepted, assignment))
        .isInstanceOf(ConflictException.class);
    assertThat(currentSession().getStatus()).isEqualTo(SessionStatus.NEW);
    verifyNoInteractions(matrix);
  }

  @Test
  void recoveryDoesNotReopenWhenANewerOwnershipRevisionWon() {
    var accepted = currentSession();
    var assignment = ownership.updateOwnerAndStatus(accepted, owner, SessionStatus.IN_PROGRESS);
    readCommitted().executeWithoutResult(status -> stageDeparture());
    var nextOwner = readCommitted().execute(status -> saveConsultant());
    try {
      var next =
          ownership.updateOwnerAndStatus(currentSession(), nextOwner, SessionStatus.IN_PROGRESS);
      assertThat(next.revision()).isGreaterThan(assignment.revision());
      when(clock.instant()).thenReturn(SAME_INSTANT.plusSeconds(300));
      revocations.retryDue();

      assertAssignedRoom(nextOwner, oldRoom);
      assertThat(tasks.pendingSessionIds(relationId)).containsExactly(sessionId);
      assertThat(tasks.dueIds(LocalDateTime.now(clock), PageRequest.of(0, 8))).isEmpty();
      verifyNoInteractions(matrix);
    } finally {
      readCommitted()
          .executeWithoutResult(
              status -> sessions.lockFresh(sessionId).orElseThrow().setConsultant(owner));
      consultants.deleteById(nextOwner.getId());
    }
  }

  private void assertReplacementKeepsOnlyTheRequiredCorrection() {
    assertAssignedRoom(owner, newRoom);
    assertThat(tasks.pendingSessionIds(relationId)).containsExactly(sessionId);
    verifyNoInteractions(matrix);
    when(matrix.isConfirmedOutOfRoom(oldRoom, departed.getMatrixUserId())).thenReturn(false, true);
    when(matrix.loginAsUser(departed.getMatrixUserId())).thenReturn(MEMBER_TOKEN);

    revocations.retryDue();

    assertThat(tasks.pendingSessionIds(relationId)).isEmpty();
    verify(matrix).leaveRoom(oldRoom, MEMBER_TOKEN);
    verify(matrix, never()).leaveRoom(eq(newRoom), any());
  }

  private void assertAssignedRoom(Consultant expectedOwner, String room) {
    var current = currentSession();
    assertThat(current.getConsultant().getId()).isEqualTo(expectedOwner.getId());
    assertThat(current.getStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
    assertThat(current.getMatrixRoomId()).isEqualTo(room);
  }

  private Session currentSession() {
    return sessions.findById(sessionId).orElseThrow();
  }

  private void stageDeparture() {
    revocations.stage(markDeparted());
  }

  private ConsultantAgency markDeparted() {
    consultants.lockConsultant(departed.getId());
    var relation = assignments.findById(relationId).orElseThrow();
    relation.setDeleteDate(LocalDateTime.now(clock));
    assignments.save(relation);
    entityManager.flush();
    return relation;
  }

  private ConsultantAgency addAssignment(Consultant consultant) {
    var now = LocalDateTime.now(clock);
    return assignments.save(
        ConsultantAgency.builder()
            .consultant(consultant)
            .agencyId(agencyId)
            .tenantId(TENANT_ID)
            .status(ConsultantAgencyStatus.CREATED)
            .createDate(now)
            .updateDate(now)
            .build());
  }

  private Consultant saveConsultant() {
    var id = UUID.randomUUID().toString();
    var now = LocalDateTime.now(clock);
    return consultants.save(
        Consultant.builder()
            .id(id)
            .username(id)
            .firstName("Test")
            .lastName("Counsellor")
            .email(id + "@example.invalid")
            .matrixUserId("@" + id + ":example.invalid")
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

  private TransactionTemplate readCommitted() {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return transaction;
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
