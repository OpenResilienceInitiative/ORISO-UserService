package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class EventNotificationServiceReplicaIT {

  private static final String RECIPIENT = "replica-proof-recipient";
  private static final String DEDUPLICATION_KEY = "group-chat:group_chat.reminder:42:0";

  @Autowired private EventNotificationRepository eventNotificationRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private IdentityTombstoneService identityTombstoneService;
  @Autowired private EventNotificationDeduplicationWriter deduplicationWriter;

  @AfterEach
  void deleteReplicaProofNotification() {
    var notifications =
        eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDescIdDesc(
            RECIPIENT, Pageable.unpaged());
    eventNotificationRepository.deleteAll(notifications);
  }

  @Test
  void twoServiceInstancesPublishOneReminder() throws Exception {
    var firstInstance = newServiceInstance();
    var secondInstance = newServiceInstance();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> publishReminder(firstInstance, ready, start));
      var second = executor.submit(() -> publishReminder(secondInstance, ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    var notifications =
        eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDescIdDesc(
            RECIPIENT, Pageable.unpaged());
    assertThat(notifications)
        .singleElement()
        .extracting(notification -> notification.getDeduplicationKey())
        .isEqualTo(DEDUPLICATION_KEY);
  }

  private EventNotificationService newServiceInstance() {
    return new EventNotificationService(
        eventNotificationRepository,
        sessionRepository,
        userRepository,
        consultantRepository,
        identityTombstoneService,
        deduplicationWriter);
  }

  private void publishReminder(
      EventNotificationService service, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    service.createEventOnce(
        DEDUPLICATION_KEY,
        RECIPIENT,
        "group_chat.reminder",
        EventNotificationService.CATEGORY_SYSTEM,
        "Reminder",
        "Soon",
        "{\"seriesId\":42}",
        null,
        42L,
        null);
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent replica proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
