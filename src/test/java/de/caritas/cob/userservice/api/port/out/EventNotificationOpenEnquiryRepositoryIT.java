package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.EventNotification;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Executes the open-enquiry cleanup queries of {@link EventNotificationRepository} against the H2
 * testing schema (ORISO-Frontend#1472). A bulk JPQL delete with a wrong predicate would wipe other
 * timeline entries, so only a real execution proves which rows it selects.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class EventNotificationOpenEnquiryRepositoryIT {

  private static final Set<String> OPEN_ENQUIRY_TYPES =
      Set.of("request.new", "waiting_room.client.joined");

  @Autowired private EventNotificationRepository underTest;

  @Test
  void deleteOpenEnquiryEvents_removesOnlyOtherConsultantsEntriesOfThatSession() {
    Long acceptor = save("acceptor", "request.new", 10L);
    Long colleague = save("colleague", "request.new", 10L);
    Long waitingRoom = save("colleague-2", "waiting_room.client.joined", 10L);
    Long otherSession = save("colleague", "request.new", 11L);
    Long otherType = save("colleague", "message.new", 10L);

    assertThat(underTest.findOtherRecipientsOfSessionEvents(10L, OPEN_ENQUIRY_TYPES, "acceptor"))
        .containsExactlyInAnyOrder("colleague", "colleague-2");

    int deleted = underTest.deleteOpenEnquiryEvents(10L, OPEN_ENQUIRY_TYPES, "acceptor");

    assertThat(deleted).isEqualTo(2);
    assertThat(remainingIds()).containsExactlyInAnyOrder(acceptor, otherSession, otherType);
    assertThat(remainingIds()).doesNotContain(colleague, waitingRoom);
  }

  @Test
  void findOtherRecipientsOfSessionEvents_isEmptyWhenOnlyTheAcceptorHoldsAnEntry() {
    save("acceptor", "request.new", 10L);

    assertThat(underTest.findOtherRecipientsOfSessionEvents(10L, OPEN_ENQUIRY_TYPES, "acceptor"))
        .isEmpty();
    assertThat(underTest.deleteOpenEnquiryEvents(10L, OPEN_ENQUIRY_TYPES, "acceptor")).isZero();
  }

  private Long save(String recipient, String eventType, Long sessionId) {
    return underTest
        .save(
            EventNotification.builder()
                .recipientUserId(recipient)
                .eventType(eventType)
                .category("system")
                .title("title")
                .sourceSessionId(sessionId)
                .deduplicationKey(recipient + "-" + eventType + "-" + sessionId)
                .createDate(LocalDateTime.of(2026, 10, 28, 12, 0))
                .build())
        .getId();
  }

  private List<Long> remainingIds() {
    return underTest.findAll().stream().map(EventNotification::getId).toList();
  }
}
