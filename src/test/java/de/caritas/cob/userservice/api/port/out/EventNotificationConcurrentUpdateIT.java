package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.EventNotification;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ORISO-UserService#1510: the person can read a message row in the moment the mention marks it. Two
 * real transactions, each with its own copy of the row: neither may undo the other's change.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventNotificationConcurrentUpdateIT {

  private static final LocalDateTime READ_AT = LocalDateTime.of(2026, 11, 2, 12, 0);
  private static final String MARKED = "{\"mentioned\":true}";

  @Autowired private EventNotificationRepository repository;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private Long savedRow() {
    return repository
        .saveAndFlush(
            EventNotification.builder()
                .recipientUserId("colleague")
                .eventType("message.new")
                .category("message")
                .title("New message")
                .text("text")
                .params("{}")
                .createDate(LocalDateTime.of(2026, 11, 2, 11, 0))
                .deduplicationKey("message.new:$event-" + System.nanoTime())
                .build())
        .getId();
  }

  private void assertBothChangesSurvive(Long id) {
    var row = repository.findById(id).orElseThrow();
    assertThat(row.getReadDate()).isEqualTo(READ_AT);
    assertThat(row.getParams()).isEqualTo(MARKED);
    repository.deleteById(id);
  }

  @Test
  void aMarkDoesNotUndoAReadThatCommittedFirst() {
    Long id = savedRow();
    var marking = entityManagerFactory.createEntityManager();
    var reading = entityManagerFactory.createEntityManager();
    try {
      marking.getTransaction().begin();
      var toMark = marking.find(EventNotification.class, id);
      reading.getTransaction().begin();
      reading.find(EventNotification.class, id).setReadDate(READ_AT);
      reading.getTransaction().commit();

      toMark.setParams(MARKED);
      marking.getTransaction().commit();
    } finally {
      marking.close();
      reading.close();
    }

    assertBothChangesSurvive(id);
  }

  @Test
  void aReadDoesNotUndoAMarkThatCommittedFirst() {
    Long id = savedRow();
    var marking = entityManagerFactory.createEntityManager();
    var reading = entityManagerFactory.createEntityManager();
    try {
      reading.getTransaction().begin();
      var toRead = reading.find(EventNotification.class, id);
      marking.getTransaction().begin();
      marking.find(EventNotification.class, id).setParams(MARKED);
      marking.getTransaction().commit();

      toRead.setReadDate(READ_AT);
      reading.getTransaction().commit();
    } finally {
      marking.close();
      reading.close();
    }

    assertBothChangesSurvive(id);
  }
}
