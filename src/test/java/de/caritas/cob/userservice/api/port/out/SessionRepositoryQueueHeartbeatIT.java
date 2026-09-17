package de.caritas.cob.userservice.api.port.out;

import static com.neovisionaries.i18n.LanguageCode.de;
import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_OFFENDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.testConfig.ConsultingTypeManagerTestConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises queue heartbeats against committed rows and competing database transactions. */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(ConsultingTypeManagerTestConfig.class)
class SessionRepositoryQueueHeartbeatIT {
  @Autowired private SessionRepository sessions;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private Messaging messenger;
  @Autowired private PlatformTransactionManager transactionManager;

  private Long sessionId;
  private String consultantId;

  @BeforeEach
  void createWaitingSession() {
    var user = users.findAll().iterator().next();
    consultantId = consultants.findAll().iterator().next().getId();
    var session = new Session(user, CONSULTING_TYPE_ID_OFFENDER, "12345", null, NEW, false);
    session.setRegistrationType(ANONYMOUS);
    session.setIsConsultantDirectlySet(false);
    session.setLanguageCode(de);
    session.setCreateDate(nowInUtc().minusMinutes(10));
    session.setUpdateDate(nowInUtc().minusMinutes(2));
    sessionId = sessions.save(session).getId();
  }

  @AfterEach
  void removeWaitingSession() {
    if (sessionId != null) {
      sessions.deleteById(sessionId);
    }
  }

  @Test
  void heartbeatMustNotOverwriteConcurrentAssignment() throws Exception {
    var locked = new CountDownLatch(1);
    var releaseAssignment = new CountDownLatch(1);
    var heartbeatStarted = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var assignment =
          workers.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          ignored -> {
                            // AcceptAnonymousEnquiryFacade uses this same row lock before
                            // assigning.
                            var session = sessions.findByIdForUpdate(sessionId).orElseThrow();
                            locked.countDown();
                            await(releaseAssignment);
                            session.setConsultant(consultants.findById(consultantId).orElseThrow());
                            session.setStatus(IN_PROGRESS);
                          }));
      await(locked);
      var heartbeat =
          workers.submit(
              () -> {
                heartbeatStarted.countDown();
                messenger.touchLiveChatQueueHeartbeat(sessionId);
              });
      await(heartbeatStarted);
      assertThrows(TimeoutException.class, () -> heartbeat.get(250, TimeUnit.MILLISECONDS));
      releaseAssignment.countDown();
      assignment.get(5, TimeUnit.SECONDS);
      heartbeat.get(5, TimeUnit.SECONDS);

      var actual = sessions.findById(sessionId).orElseThrow();
      assertThat(actual.getStatus()).isEqualTo(IN_PROGRESS);
      assertThat(actual.getConsultant().getId()).isEqualTo(consultantId);
    } finally {
      releaseAssignment.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void concurrentHeartbeatsRefreshOnlyOnceWithinTheThrottleWindow() throws Exception {
    var now = nowInUtc().withNano(0);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<Integer> poll =
          () -> {
            ready.countDown();
            await(start);
            return sessions.touchLiveChatQueueHeartbeat(sessionId, NEW, now, now.minusSeconds(30));
          };
      var first = workers.submit(poll);
      var second = workers.submit(poll);
      await(ready);
      start.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(sessions.findById(sessionId).orElseThrow().getUpdateDate()).isEqualTo(now);
    } finally {
      start.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void heartbeatRefreshesAnEntryWithoutAPreviousTimestamp() {
    changeSession(session -> session.setUpdateDate(null));
    var now = nowInUtc().withNano(0);
    assertThat(sessions.touchLiveChatQueueHeartbeat(sessionId, NEW, now, now.minusSeconds(30)))
        .isEqualTo(1);
    assertThat(sessions.findById(sessionId).orElseThrow().getUpdateDate()).isEqualTo(now);
  }

  @Test
  void heartbeatDoesNotRefreshAtTheThrottleBoundary() {
    var now = nowInUtc().withNano(0);
    var cutoff = now.minusSeconds(30);
    changeSession(session -> session.setUpdateDate(cutoff));
    assertThat(sessions.touchLiveChatQueueHeartbeat(sessionId, NEW, now, cutoff)).isZero();
    assertThat(sessions.findById(sessionId).orElseThrow().getUpdateDate()).isEqualTo(cutoff);
  }

  @Test
  void heartbeatDoesNotRefreshAnAssignedWaitingSession() {
    changeSession(
        session -> session.setConsultant(consultants.findById(consultantId).orElseThrow()));
    assertHeartbeatLeavesTimestampUnchanged();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Session.SessionStatus.class,
      names = "NEW",
      mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
  void heartbeatDoesNotRefreshOtherStatuses(Session.SessionStatus status) {
    changeSession(session -> session.setStatus(status));
    assertHeartbeatLeavesTimestampUnchanged();
  }

  @Test
  void heartbeatIgnoresAMissingSession() {
    sessions.deleteById(sessionId);
    var now = nowInUtc();
    assertThat(sessions.touchLiveChatQueueHeartbeat(sessionId, NEW, now, now.minusSeconds(30)))
        .isZero();
    sessionId = null;
  }

  private void assertHeartbeatLeavesTimestampUnchanged() {
    var before = sessions.findById(sessionId).orElseThrow().getUpdateDate();
    var now = nowInUtc();
    assertThat(sessions.touchLiveChatQueueHeartbeat(sessionId, NEW, now, now.minusSeconds(30)))
        .isZero();
    assertThat(sessions.findById(sessionId).orElseThrow().getUpdateDate()).isEqualTo(before);
  }

  private void changeSession(java.util.function.Consumer<Session> change) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(ignored -> change.accept(sessions.findById(sessionId).orElseThrow()));
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
