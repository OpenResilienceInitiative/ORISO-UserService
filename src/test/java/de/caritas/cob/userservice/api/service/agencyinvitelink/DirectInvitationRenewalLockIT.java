package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ORISO-Frontend#2252, "Neuen Link senden" against a real database and Hibernate's persistence
 * context: a renewal that waits for another one's row lock must work on what that one committed,
 * not on a copy it read before the lock. Two transactions on two threads, committed for real.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({
  DirectInvitationService.class,
  DirectInvitationMemberships.class,
  DirectInvitationRenewalLockIT.TestClock.class
})
class DirectInvitationRenewalLockIT {

  private static final String OWNER_ID = "renewal-lock-owner";

  @Autowired private DirectInvitationService invitations;

  @Autowired private AgencyInviteLinkRepository links;

  @Autowired private SessionAppointmentRepository appointments;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private javax.sql.DataSource dataSource;

  @MockitoBean private DirectInvitationRoomMembership rooms;
  @MockitoBean private EventNotificationService notifications;
  @MockitoBean private MatrixSessionSystemMessageService systemMessages;
  @MockitoBean private SessionArchiveService archive;

  private Long linkId;
  private UUID appointmentId;

  /** Runs whenever somebody asks for an invitation's row lock; set by the test. */
  private static volatile Runnable onLockingRead = () -> {};

  @TestConfiguration
  static class TestClock {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    /** Tells the test when a renewal reaches its locking read, instead of guessing with a sleep. */
    @Bean
    static org.springframework.beans.factory.config.BeanPostProcessor lockingReadSignal() {
      return new org.springframework.beans.factory.config.BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (!(bean instanceof AgencyInviteLinkRepository repository)) {
            return bean;
          }
          return java.lang.reflect.Proxy.newProxyInstance(
              AgencyInviteLinkRepository.class.getClassLoader(),
              new Class<?>[] {AgencyInviteLinkRepository.class},
              (proxy, method, args) -> {
                if (method.getName().equals("findByIdForUpdate")) {
                  onLockingRead.run();
                }
                try {
                  return method.invoke(repository, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                  throw e.getCause();
                }
              });
        }
      };
    }
  }

  @AfterEach
  void removeCommittedRows() {
    onLockingRead = () -> {};
    var tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        status -> {
          if (linkId != null) {
            links.deleteById(linkId);
          }
          if (appointmentId != null) {
            appointments.deleteById(appointmentId);
          }
        });
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void renew_Should_ReplaceTheTokenTheOtherRenewalCommitted_When_ItWaitedForItsLock()
      throws Exception {
    var tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(status -> givenAnInvitationWithAppointment());

    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    // The other renewal: holds the row lock, writes its token, commits when released.
    var other =
        CompletableFuture.runAsync(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      var row = links.findByIdForUpdate(linkId).orElseThrow();
                      row.getReplacedTokens().add(row.getToken());
                      row.setToken("token-of-the-other-renewal");
                      links.save(row);
                      locked.countDown();
                      await(release);
                    }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

    var owner = new Consultant();
    owner.setId(OWNER_ID);
    var asksForTheLock = new CountDownLatch(1);
    // Set after the other renewal took the lock: only the second renewal gets here.
    onLockingRead = asksForTheLock::countDown;
    // This renewal starts while the lock is held; it must wait and then see the other token.
    var renewal = CompletableFuture.supplyAsync(() -> invitations.renew(owner, linkId, null));
    assertThat(asksForTheLock.await(10, TimeUnit.SECONDS))
        .as("the second renewal reached its locking read while the first one holds the row")
        .isTrue();
    // The database itself reports the wait: the locking query runs and waits for the other lock.
    LockWaits.awaitALockWait(dataSource);
    assertThat(renewal).as("it waits for the other renewal's lock").isNotDone();
    release.countDown();
    other.get(10, TimeUnit.SECONDS);
    var renewed = renewal.get(10, TimeUnit.SECONDS);

    var stored =
        tx.execute(
            status -> {
              var row = links.findById(linkId).orElseThrow();
              row.getReplacedTokens().size();
              return row;
            });
    assertThat(stored.getToken()).isEqualTo(renewed.getToken());
    assertThat(stored.getReplacedTokens())
        .as("the other renewal's token still reads as replaced, never lost")
        .contains("first-token", "token-of-the-other-renewal");
  }

  private void givenAnInvitationWithAppointment() {
    var appointment = new SessionAppointment();
    appointment.setStatus(SessionAppointment.Status.SCHEDULED);
    var start = Instant.now().plus(1, ChronoUnit.DAYS);
    appointment.setStartTime(start);
    appointment.setEndTime(start.plus(1, ChronoUnit.HOURS));
    appointmentId = appointments.save(appointment).getId();
    linkId =
        links
            .save(
                AgencyInviteLink.builder()
                    .token("first-token")
                    .tenantId(1L)
                    .linkKind(InviteLinkKind.COUNSELLOR.name())
                    .chatType(InviteLinkChatType.VIDEO.name())
                    .anonymity("NONE")
                    .consultantId(OWNER_ID)
                    .createdByUserId(OWNER_ID)
                    .createDate(LocalDateTime.now())
                    .status(InviteLinkStatus.ACTIVE.name())
                    .appointmentId(appointmentId)
                    .validityAfterEndMinutes(60)
                    .build())
            .getId();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("not released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
