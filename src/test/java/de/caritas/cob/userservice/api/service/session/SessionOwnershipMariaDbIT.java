package de.caritas.cob.userservice.api.service.session;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverReasonPolicyRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.CaseHandoverEmailNotification;
import de.caritas.cob.userservice.api.service.notification.EventNotificationDeduplicationWriter;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** MariaDB proof for ownership locking, operation revisions, and stale JPA row versions. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Import({
  SessionOwnershipService.class,
  EventNotificationService.class,
  EventNotificationDeduplicationWriter.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionOwnershipMariaDbIT {

  private static final String USER_ID = "ownership-proof-user";
  private static final String OWNER_A_ID = "ownership-proof-owner-a";
  private static final String OWNER_B_ID = "ownership-proof-owner-b";
  private static final String OWNER_C_ID = "ownership-proof-owner-c";

  @Autowired private SessionOwnershipService ownershipService;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private CaseHandoverRequestRepository caseHandoverRequestRepository;
  @Autowired private EventNotificationRepository eventNotificationRepository;
  @Autowired private CaseHandoverReasonPolicyRepository caseHandoverReasonPolicyRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private EventNotificationService eventNotificationService;
  @MockitoBean private IdentityTombstoneService identityTombstoneService;

  private TransactionTemplate transactions;
  private Long sessionId;
  private Consultant ownerA;
  private Consultant ownerB;
  private Consultant ownerC;

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
  void createProofSession() {
    transactions = new TransactionTemplate(transactionManager);
    sessionId =
        transactions.execute(
            ignored -> {
              LocalDateTime now = LocalDateTime.now();
              User user =
                  userRepository.save(
                      User.builder()
                          .userId(USER_ID)
                          .username(USER_ID)
                          .email("ownership-proof-user@example.invalid")
                          .languageFormal(false)
                          .encourage2fa(true)
                          .magicLinkLoginEnabled(false)
                          .languageCode(LanguageCode.de)
                          .tenantId(7L)
                          .notificationsEnabled(false)
                          .createDate(now)
                          .updateDate(now)
                          .build());
              ownerA = consultantRepository.save(consultant(OWNER_A_ID, now));
              ownerB = consultantRepository.save(consultant(OWNER_B_ID, now));
              ownerC = consultantRepository.save(consultant(OWNER_C_ID, now));
              consultantAgencyRepository.save(
                  ConsultantAgency.builder()
                      .consultant(ownerB)
                      .agencyId(700L)
                      .tenantId(7L)
                      .createDate(now)
                      .updateDate(now)
                      .build());
              return sessionRepository
                  .save(
                      Session.builder()
                          .user(user)
                          .consultant(ownerA)
                          .tenantId(7L)
                          .agencyId(700L)
                          .consultingTypeId(0)
                          .registrationType(REGISTERED)
                          .conversationType(ConversationType.AGENCY_COUNSELLING)
                          .postcode("00000")
                          .languageCode(LanguageCode.de)
                          .status(IN_PROGRESS)
                          .teamSession(false)
                          .isConsultantDirectlySet(false)
                          .createDate(now)
                          .updateDate(now)
                          .ownershipRevision(0L)
                          .rowVersion(0L)
                          .build())
                  .getId();
            });
  }

  @AfterEach
  void removeProofSession() {
    if (sessionId != null) {
      transactions.executeWithoutResult(
          ignored -> {
            caseHandoverRequestRepository.deleteAllBySessionId(sessionId);
            sessionRepository.deleteById(sessionId);
          });
    }
    transactions.executeWithoutResult(
        ignored -> {
          eventNotificationRepository.deleteByRecipientUserId(OWNER_B_ID);
          consultantAgencyRepository.deleteAll(
              consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(OWNER_B_ID));
          consultantRepository.deleteAllById(List.of(OWNER_A_ID, OWNER_B_ID, OWNER_C_ID));
          userRepository.deleteById(USER_ID);
        });
  }

  @Test
  void competingTransactionsProduceOneWinnerAndRejectAStaleWholeEntitySave() throws Exception {
    Session firstExpected = readSession();
    Session secondExpected = readSession();
    Session staleWholeEntity = readSession();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> updateWhenReleased(firstExpected, ownerB, ready, start));
      var second = executor.submit(() -> updateWhenReleased(secondExpected, ownerC, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    staleWholeEntity.setPostcode("11111");
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored -> {
                      sessionRepository.save(staleWholeEntity);
                      entityManager.flush();
                    }))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(readSession().getOwnershipRevision()).isOne();
  }

  @Test
  void oldCompensationCannotUndoANewerAtoBtoAOwnershipPeriod() {
    var assignment = ownershipService.updateOwnerAndStatus(readSession(), ownerB, IN_PROGRESS);
    ownershipService.updateOwnerAndStatus(readSession(), ownerA, IN_PROGRESS);

    boolean compensated =
        ownershipService.compensateOwnerChange(
            sessionId, assignment, ownerA, IN_PROGRESS, LocalDateTime.now());

    assertThat(compensated).isFalse();
    Session current = readSession();
    assertThat(current.getConsultant().getId()).isEqualTo(ownerA.getId());
    assertThat(current.getOwnershipRevision()).isEqualTo(2L);
  }

  @Test
  void staleManagedSessionIsRejectedBeforeItCanOverwriteANewerOwner() {
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored -> {
                      Session staleManaged = sessionRepository.findById(sessionId).orElseThrow();
                      var newerTransaction = new TransactionTemplate(transactionManager);
                      newerTransaction.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
                      newerTransaction.executeWithoutResult(
                          inner ->
                              ownershipService.updateOwnerAndStatus(
                                  sessionRepository.findById(sessionId).orElseThrow(),
                                  ownerB,
                                  IN_PROGRESS));

                      ownershipService.updateOwnerAndStatus(staleManaged, ownerC, IN_PROGRESS);
                    }))
        .isInstanceOf(OptimisticLockException.class);

    assertThat(readSession().getConsultant().getId()).isEqualTo(ownerB.getId());
    assertThat(readSession().getOwnershipRevision()).isOne();
  }

  @Test
  void ownershipUpdateCannotBlessAStaleForeignFieldForALaterSave() {
    Session staleCaller = readSession();
    transactions.executeWithoutResult(
        ignored -> {
          Session foreignUpdate = sessionRepository.findById(sessionId).orElseThrow();
          foreignUpdate.setPostcode("54321");
          sessionRepository.save(foreignUpdate);
        });

    assertThatThrownBy(
            () -> ownershipService.updateOwnerAndStatus(staleCaller, ownerB, IN_PROGRESS))
        .isInstanceOf(ConflictException.class);

    Session current = readSession();
    assertThat(current.getPostcode()).isEqualTo("54321");
    assertThat(current.getConsultant().getId()).isEqualTo(ownerA.getId());
    assertThat(current.getOwnershipRevision()).isZero();
  }

  @Test
  void declineWinsBeforeWaitingApprovalReadsTerminalRequestAndDoesNotGrant() throws Exception {
    Long requestId =
        transactions.execute(
            ignored ->
                caseHandoverRequestRepository
                    .save(
                        CaseHandoverRequest.builder()
                            .session(sessionRepository.findById(sessionId).orElseThrow())
                            .requesterConsultant(ownerB)
                            .initiatorConsultant(ownerB)
                            .previousConsultant(ownerA)
                            .direction(CaseHandoverRequest.Direction.PULL)
                            .expectedOwnershipRevision(0L)
                            .operationId(
                                java.util.UUID.fromString("057f42f7-e865-402d-9c04-04953b4e7592"))
                            .reasonCode("COUNSELLOR_ASKED_FOR_ADVICE")
                            .reasonLabel("Counsellor asked for advice")
                            .explanation("Concurrency proof")
                            .status(CaseHandoverRequest.Status.PENDING_CLIENT_CONSENT)
                            .clientConsentRequired(true)
                            .policyAuthority("test")
                            .auditOutcome("PENDING_CLIENT_CONSENT")
                            .createdAt(LocalDateTime.now())
                            .tenantId(7L)
                            .build())
                    .getId());

    var declineHasSessionLock = new CountDownLatch(1);
    var releaseDecline = new CountDownLatch(1);
    var approveReadRequest = new CountDownLatch(1);
    SessionRepository serializedSessions = mock(SessionRepository.class);
    CaseHandoverRequestRepository observedRequests = mock(CaseHandoverRequestRepository.class);
    when(serializedSessions.findByIdForUpdate(sessionId))
        .thenAnswer(
            invocation -> {
              var result = sessionRepository.findByIdForUpdate(sessionId);
              if (Thread.currentThread().getName().contains("decline")) {
                declineHasSessionLock.countDown();
                await(releaseDecline);
              }
              return result;
            });
    when(observedRequests.findByIdAndSessionId(requestId, sessionId))
        .thenAnswer(
            invocation -> {
              var result = caseHandoverRequestRepository.findByIdAndSessionId(requestId, sessionId);
              if (Thread.currentThread().getName().contains("approve")) {
                approveReadRequest.countDown();
              }
              return result;
            });
    when(observedRequests.save(any(CaseHandoverRequest.class)))
        .thenAnswer(invocation -> caseHandoverRequestRepository.save(invocation.getArgument(0)));

    UserAccountService accounts = mock(UserAccountService.class);
    when(accounts.retrieveValidatedUser())
        .thenAnswer(
            ignored -> transactions.execute(tx -> userRepository.findById(USER_ID).orElseThrow()));
    MatrixSessionSystemMessageService matrixMessages =
        mock(MatrixSessionSystemMessageService.class);
    CaseHandoverService handovers =
        new CaseHandoverService(
            observedRequests,
            mock(SessionSupervisorFacade.class),
            caseHandoverReasonPolicyRepository,
            serializedSessions,
            ownershipService,
            consultantAgencyRepository,
            accounts,
            mock(EventNotificationService.class),
            mock(MatrixSynapseService.class),
            matrixMessages,
            mock(CaseHandoverEmailNotification.class),
            mock(ConsultantService.class),
            mock(AuthenticatedUser.class));

    var executor = Executors.newFixedThreadPool(2);
    try {
      var decline =
          executor.submit(
              () -> {
                Thread.currentThread().setName("handover-decline");
                return transactions.execute(
                    ignored -> handovers.resolveClientConsent(sessionId, requestId, false));
              });
      assertThat(declineHasSessionLock.await(5, TimeUnit.SECONDS)).isTrue();
      var approve =
          executor.submit(
              () -> {
                Thread.currentThread().setName("handover-approve");
                return transactions.execute(
                    ignored -> handovers.resolveClientConsent(sessionId, requestId, true));
              });
      approveReadRequest.await(1, TimeUnit.SECONDS);
      releaseDecline.countDown();

      assertThat(decline.get(15, TimeUnit.SECONDS).getStatus())
          .isEqualTo(CaseHandoverRequest.Status.CLIENT_CONSENT_DECLINED.name());
      assertThat(approve.get(15, TimeUnit.SECONDS).getStatus())
          .isEqualTo(CaseHandoverRequest.Status.CLIENT_CONSENT_DECLINED.name());
    } finally {
      releaseDecline.countDown();
      executor.shutdownNow();
    }

    CaseHandoverRequest.Status storedStatus =
        transactions.execute(
            ignored ->
                caseHandoverRequestRepository
                    .findByIdAndSessionId(requestId, sessionId)
                    .orElseThrow()
                    .getStatus());
    assertThat(storedStatus).isEqualTo(CaseHandoverRequest.Status.CLIENT_CONSENT_DECLINED);
    assertThat(readSession().getConsultant().getId()).isEqualTo(ownerA.getId());
    verify(matrixMessages, never()).postCaseHandoverGrantedMessage(any(), any(), any());
  }

  @Test
  void recipientDeclineSerializesBeforeCompetingAcceptanceAndPreventsGrant() throws Exception {
    Long requestId =
        transactions.execute(
            ignored ->
                caseHandoverRequestRepository
                    .save(
                        CaseHandoverRequest.builder()
                            .session(sessionRepository.findById(sessionId).orElseThrow())
                            .requesterConsultant(ownerB)
                            .initiatorConsultant(ownerA)
                            .previousConsultant(ownerA)
                            .direction(CaseHandoverRequest.Direction.PUSH)
                            .expectedOwnershipRevision(0L)
                            .operationId(
                                java.util.UUID.fromString("38a811c2-1ac1-48b1-b44e-b84a3dd3fa6c"))
                            .reasonCode("OTHER_EMERGENCY")
                            .reasonLabel("Other emergency")
                            .explanation("")
                            .status(CaseHandoverRequest.Status.PENDING_RECIPIENT_ACCEPTANCE)
                            .clientConsentRequired(false)
                            .policyAuthority("test")
                            .auditOutcome("PENDING_RECIPIENT_ACCEPTANCE")
                            .createdAt(LocalDateTime.now())
                            .tenantId(7L)
                            .build())
                    .getId());

    var declineHasSessionLock = new CountDownLatch(1);
    var releaseDecline = new CountDownLatch(1);
    SessionRepository serializedSessions = mock(SessionRepository.class);
    when(serializedSessions.findByIdForUpdate(sessionId))
        .thenAnswer(
            invocation -> {
              var result = sessionRepository.findByIdForUpdate(sessionId);
              if (Thread.currentThread().getName().contains("recipient-decline")) {
                declineHasSessionLock.countDown();
                await(releaseDecline);
              }
              return result;
            });
    CaseHandoverRequestRepository observedRequests = mock(CaseHandoverRequestRepository.class);
    when(observedRequests.findByIdAndSessionIdForUpdate(requestId, sessionId))
        .thenAnswer(
            ignored ->
                caseHandoverRequestRepository.findByIdAndSessionIdForUpdate(requestId, sessionId));
    when(observedRequests.save(any(CaseHandoverRequest.class)))
        .thenAnswer(invocation -> caseHandoverRequestRepository.save(invocation.getArgument(0)));
    UserAccountService accounts = mock(UserAccountService.class);
    when(accounts.retrieveValidatedConsultant())
        .thenAnswer(
            ignored ->
                transactions.execute(
                    tx -> consultantRepository.findById(OWNER_B_ID).orElseThrow()));
    MatrixSessionSystemMessageService matrixMessages =
        mock(MatrixSessionSystemMessageService.class);
    AuthenticatedUser caller = mock(AuthenticatedUser.class);
    when(caller.getUserId()).thenReturn(OWNER_B_ID);
    CaseHandoverService handovers =
        new CaseHandoverService(
            observedRequests,
            mock(SessionSupervisorFacade.class),
            caseHandoverReasonPolicyRepository,
            serializedSessions,
            ownershipService,
            consultantAgencyRepository,
            accounts,
            mock(EventNotificationService.class),
            mock(MatrixSynapseService.class),
            matrixMessages,
            mock(CaseHandoverEmailNotification.class),
            mock(ConsultantService.class),
            caller);

    var executor = Executors.newFixedThreadPool(2);
    try {
      var decline =
          executor.submit(
              () -> {
                Thread.currentThread().setName("recipient-decline");
                return transactions.execute(
                    ignored -> handovers.resolveRecipientDecision(sessionId, requestId, false));
              });
      assertThat(declineHasSessionLock.await(5, TimeUnit.SECONDS)).isTrue();
      var approve =
          executor.submit(
              () -> {
                Thread.currentThread().setName("recipient-approve");
                return transactions.execute(
                    ignored -> handovers.resolveRecipientDecision(sessionId, requestId, true));
              });
      releaseDecline.countDown();

      assertThat(decline.get(15, TimeUnit.SECONDS).getStatus())
          .isEqualTo(CaseHandoverRequest.Status.RECIPIENT_DECLINED.name());
      assertThat(approve.get(15, TimeUnit.SECONDS).getStatus())
          .isEqualTo(CaseHandoverRequest.Status.RECIPIENT_DECLINED.name());
    } finally {
      releaseDecline.countDown();
      executor.shutdownNow();
    }

    assertThat(readSession().getConsultant().getId()).isEqualTo(ownerA.getId());
    assertThat(readSession().getOwnershipRevision()).isZero();
    verify(matrixMessages, never()).postCaseHandoverGrantedMessage(any(), any(), any());
  }

  @Test
  void offerNotificationCommitsAfterOfferButNotRollbackAndReplayDoesNotDuplicate() {
    UserAccountService accounts = mock(UserAccountService.class);
    when(accounts.retrieveValidatedConsultant())
        .thenAnswer(
            ignored ->
                transactions.execute(
                    tx -> consultantRepository.findById(OWNER_A_ID).orElseThrow()));
    ConsultantService consultants = mock(ConsultantService.class);
    when(consultants.getConsultant(OWNER_B_ID))
        .thenAnswer(
            ignored -> transactions.execute(tx -> consultantRepository.findById(OWNER_B_ID)));
    AuthenticatedUser actor = mock(AuthenticatedUser.class);
    when(actor.getUserId()).thenReturn(OWNER_A_ID);
    CaseHandoverService handovers =
        new CaseHandoverService(
            caseHandoverRequestRepository,
            mock(SessionSupervisorFacade.class),
            caseHandoverReasonPolicyRepository,
            sessionRepository,
            ownershipService,
            consultantAgencyRepository,
            accounts,
            eventNotificationService,
            mock(MatrixSynapseService.class),
            mock(MatrixSessionSystemMessageService.class),
            mock(CaseHandoverEmailNotification.class),
            consultants,
            actor);
    var committedOperation = java.util.UUID.fromString("de949f0d-d1d5-48bb-8694-cbd786863623");

    transactions.execute(
        ignored ->
            handovers.createOffer(
                sessionId, OWNER_B_ID, "OTHER_EMERGENCY", null, 0L, committedOperation));

    assertThat(offerNotifications()).hasSize(1);

    transactions.execute(
        ignored ->
            handovers.createOffer(
                sessionId, OWNER_B_ID, "OTHER_EMERGENCY", null, 0L, committedOperation));
    assertThat(offerNotifications()).hasSize(1);

    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored -> {
                      handovers.createOffer(
                          sessionId,
                          OWNER_B_ID,
                          "OTHER_EMERGENCY",
                          null,
                          0L,
                          java.util.UUID.fromString("0d91227b-9390-4643-984e-cd3b7c035657"));
                      throw new IllegalStateException("force rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(offerNotifications()).hasSize(1);
  }

  private List<de.caritas.cob.userservice.api.model.EventNotification> offerNotifications() {
    return transactions.execute(
        ignored ->
            eventNotificationRepository
                .findByRecipientUserIdOrderByCreateDateDescIdDesc(OWNER_B_ID, PageRequest.of(0, 20))
                .stream()
                .filter(event -> "case.handover.offer.received".equals(event.getEventType()))
                .toList());
  }

  private boolean updateWhenReleased(
      Session expected, Consultant owner, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    try {
      ownershipService.updateOwnerAndStatus(expected, owner, IN_PROGRESS);
      return true;
    } catch (ConflictException conflict) {
      return false;
    }
  }

  private Session readSession() {
    return transactions.execute(ignored -> sessionRepository.findById(sessionId).orElseThrow());
  }

  private static Consultant consultant(String id, LocalDateTime now) {
    return Consultant.builder()
        .id(id)
        .matrixUserId("@" + id + ":oriso.invalid")
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
        .tenantId(7L)
        .status(ConsultantStatus.IN_PROGRESS)
        .createDate(now)
        .updateDate(now)
        .build();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for MariaDB ownership contender");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
