package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.*;

import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.model.InquiryAcceptanceNotice.DeliveryState;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  SessionOwnershipService.class,
  InquiryAcceptanceNoticeStore.class,
  ConsultantDisplayNameResolver.class
})
class InquiryAcceptanceNoticeTransactionIT {
  @Autowired SessionRepository sessions;
  @Autowired ConsultantRepository consultants;
  @Autowired UserRepository users;
  @Autowired InquiryAcceptanceNoticeRepository notices;
  @Autowired SessionOwnershipService ownership;
  @Autowired InquiryAcceptanceNoticeStore store;
  @Autowired PlatformTransactionManager transactions;
  Long sessionId;
  Consultant consultant;

  @BeforeEach
  void fixture() {
    consultant =
        Consultant.builder()
            .id(UUID.randomUUID().toString())
            .username("public-advisor")
            .firstName("Private")
            .lastName("Name")
            .email(UUID.randomUUID() + "@example.test")
            .matrixUserId("@c:matrix.test")
            .encourage2fa(false)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(false)
            .notifyNewChatMessageFromAdviceSeeker(false)
            .languageCode(com.neovisionaries.i18n.LanguageCode.de)
            .build();
    consultants.save(consultant);
    var user = users.findAll().iterator().next();
    var session = new Session(user, 1, "10965", 1L, Session.SessionStatus.NEW, false);
    session.setConversationType(ConversationType.AGENCY_COUNSELLING);
    session.setIsConsultantDirectlySet(false);
    session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    sessionId = sessions.save(session).getId();
  }

  @AfterEach
  void cleanup() {
    if (sessionId != null) {
      notices.deleteById(sessionId);
      sessions.deleteById(sessionId);
    }
    consultants.deleteById(consultant.getId());
  }

  Session expected() {
    return sessions.findById(sessionId).orElseThrow();
  }

  @Test
  void realTransactionRollbackRemovesBothOwnershipAndPreparedFact() {
    var expected = expected();
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        tx -> {
                          ownership.updateOwnerAndStatus(
                              expected, consultant, Session.SessionStatus.IN_PROGRESS);
                          assertThat(notices.findById(sessionId)).isPresent();
                          throw new IllegalStateException(
                              "simulated database failure after ownership update");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(expected().getStatus()).isEqualTo(Session.SessionStatus.NEW);
    assertThat(expected().getConsultant()).isNull();
    assertThat(notices.findById(sessionId)).isEmpty();
  }

  @Test
  void committedOwnershipHasOnePreparedFactAndCompensationCancelsIt() {
    var expected = expected();
    var token =
        ownership.updateOwnerAndStatus(expected, consultant, Session.SessionStatus.IN_PROGRESS);
    var fact = notices.findById(sessionId).orElseThrow();
    assertThat(fact.getDeliveryState()).isEqualTo(DeliveryState.PREPARING);
    assertThat(fact.getOwnershipRevision()).isEqualTo(token.revision());
    assertThat(fact.getPublicAdvisorName()).isEqualTo("public-advisor");
    assertThat(
            ownership.compensateOwnerChange(
                sessionId, token, null, Session.SessionStatus.NEW, null))
        .isTrue();
    assertThat(notices.findById(sessionId)).isEmpty();
    assertThat(expected().getConsultant()).isNull();
  }

  @Test
  void twoConcurrentInitialAssignmentsCommitExactlyOneAcceptanceFact() throws Exception {
    var a = expected();
    var b = expected();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> first =
          () -> {
            start.await();
            try {
              ownership.updateOwnerAndStatus(a, consultant, Session.SessionStatus.IN_PROGRESS);
              return true;
            } catch (
                de.caritas.cob.userservice.api.exception.httpresponses.ConflictException conflict) {
              return false;
            }
          };
      Callable<Boolean> second =
          () -> {
            start.await();
            try {
              ownership.updateOwnerAndStatus(b, consultant, Session.SessionStatus.IN_PROGRESS);
              return true;
            } catch (
                de.caritas.cob.userservice.api.exception.httpresponses.ConflictException conflict) {
              return false;
            }
          };
      var fa = pool.submit(first);
      var fb = pool.submit(second);
      start.countDown();
      assertThat(java.util.List.of(fa.get(15, TimeUnit.SECONDS), fb.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(notices.findById(sessionId)).isPresent();
    assertThat(expected().getOwnershipRevision()).isEqualTo(1);
  }

  @Test
  void requiresNewClaimIsDurableEvenWhenCallingTransactionRollsBack() {
    var expected = expected();
    ownership.updateOwnerAndStatus(expected, consultant, Session.SessionStatus.IN_PROGRESS);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              var notice = notices.findById(sessionId).orElseThrow();
              notice.setDeliveryState(DeliveryState.PENDING);
              notices.save(notice);
            });
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        tx -> {
                          assertThat(store.claimForSend(sessionId)).isTrue();
                          throw new IllegalStateException("caller rolled back");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(notices.findById(sessionId).orElseThrow().getDeliveryState())
        .isEqualTo(DeliveryState.UNCERTAIN);
    assertThat(store.claimForSend(sessionId)).isFalse();
    store.acknowledge(sessionId, "$real-event");
    assertThat(notices.findById(sessionId).orElseThrow().getDeliveryState())
        .isEqualTo(DeliveryState.SENT);
  }

  @Test
  void twoConcurrentDeliveriesCommitOnlyOneClaimAndSend() throws Exception {
    ownership.updateOwnerAndStatus(expected(), consultant, Session.SessionStatus.IN_PROGRESS);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              var notice = notices.findById(sessionId).orElseThrow();
              notice.setDeliveryState(DeliveryState.PENDING);
              notice.setMatrixRoomId("!r:test");
              notice.setSenderMatrixId("@c:test");
              notices.save(notice);
            });
    var matrix =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.class);
    var bothReadyToClaim = new CyclicBarrier(2);
    org.mockito.Mockito.when(matrix.loginAsUserAccessToken("@c:test"))
        .thenAnswer(
            invocation -> {
              // Both workers have read PENDING before either enters the database claim.
              bothReadyToClaim.await(15, TimeUnit.SECONDS);
              return "test-token";
            });
    org.mockito.Mockito.when(
            matrix.sendMessage(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(java.util.Map.of("event_id", "$one-acceptance"));
    var firstDelivery = new InquiryAcceptanceNoticeDelivery(notices, store, matrix);
    var secondDelivery = new InquiryAcceptanceNoticeDelivery(notices, store, matrix);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> firstDelivery.dispatch(sessionId));
      var second = pool.submit(() -> secondDelivery.dispatch(sessionId));
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
    }
    org.mockito.Mockito.verify(matrix, org.mockito.Mockito.times(1))
        .sendMessage(
            org.mockito.ArgumentMatchers.eq("!r:test"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("test-token"),
            org.mockito.ArgumentMatchers.eq("inquiry-accepted-" + sessionId));
    var notice = notices.findById(sessionId).orElseThrow();
    assertThat(notice.getDeliveryState()).isEqualTo(DeliveryState.SENT);
    assertThat(notice.getMatrixEventId()).isEqualTo("$one-acceptance");
  }

  @Test
  void valid101stNoticeIsNotStarvedBehindOneHundredTokenFailures() {
    var matrix =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.class);
    var ids = new java.util.ArrayList<Long>();
    try {
      var user = users.findAll().iterator().next();
      var at = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(2);
      for (int i = 0; i < 101; i++) {
        var session = new Session(user, 1, "10965", 1L, Session.SessionStatus.IN_PROGRESS, false);
        session.setConversationType(ConversationType.AGENCY_COUNSELLING);
        session.setIsConsultantDirectlySet(false);
        session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
        Long id = sessions.save(session).getId();
        ids.add(id);
        notices.save(
            InquiryAcceptanceNotice.builder()
                .sessionId(id)
                .ownerId(consultant.getId())
                .ownershipRevision(1)
                .acceptedAtUtc(at.plusSeconds(i))
                .nextAttemptAtUtc(at.plusSeconds(i))
                .publicAdvisorName("public-advisor")
                .title("Request accepted")
                .description("The request was accepted.")
                .matrixRoomId("!r:test")
                .senderMatrixId(i == 100 ? "@good:test" : "@blocked:test")
                .deliveryState(DeliveryState.PENDING)
                .build());
      }
      org.mockito.Mockito.when(matrix.loginAsUserAccessToken("@good:test"))
          .thenReturn("test-token");
      org.mockito.Mockito.when(
              matrix.sendMessage(
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.anyString()))
          .thenReturn(java.util.Map.of("event_id", "$good-event"));
      var delivery = new InquiryAcceptanceNoticeDelivery(notices, store, matrix);
      delivery.dispatchPending();
      delivery.dispatchPending();
      assertThat(notices.findById(ids.get(100)).orElseThrow().getDeliveryState())
          .isEqualTo(DeliveryState.SENT);
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.times(1))
          .sendMessage(
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyString());
    } finally {
      ids.forEach(notices::deleteById);
      ids.forEach(sessions::deleteById);
    }
  }
}
