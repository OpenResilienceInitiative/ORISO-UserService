package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.EventNotification;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Executes the retention queries of {@link EventNotificationRepository} against the H2 testing
 * schema (KDG epic #1010, task 2a).
 *
 * <p>These are bulk JPQL deletes, so nothing but a real execution proves they select the rows they
 * are meant to — and getting the predicate wrong here deletes a user's whole feed.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class EventNotificationRetentionRepositoryIT {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 12, 0);
  private static final LocalDateTime CUTOFF = NOW.minusDays(90);

  @Autowired private EventNotificationRepository underTest;

  @Test
  void deleteReadBefore_removesOnlyReadNotificationsPastTheCutoff() {
    Long staleRead = save("stale-read", NOW.minusDays(200), NOW.minusDays(120));
    Long recentRead = save("recent-read", NOW.minusDays(200), NOW.minusDays(10));
    Long staleUnread = save("stale-unread", NOW.minusDays(200), null);

    int deleted = underTest.deleteReadBefore(CUTOFF);

    assertThat(deleted).isEqualTo(1);
    assertThat(remainingIds()).containsExactlyInAnyOrder(recentRead, staleUnread);
    assertThat(remainingIds()).doesNotContain(staleRead);
  }

  /** An unread notification is not aged out by the read cutoff, however old it is. */
  @Test
  void deleteReadBefore_neverTouchesAnUnreadNotification() {
    save("ancient-unread", NOW.minusDays(3650), null);

    assertThat(underTest.deleteReadBefore(CUTOFF)).isZero();
    assertThat(remainingIds()).hasSize(1);
  }

  @Test
  void deleteCreatedBefore_removesEveryNotificationPastTheCutoff_readOrNot() {
    Long oldUnread = save("old-unread", NOW.minusDays(400), null);
    Long oldRead = save("old-read", NOW.minusDays(400), NOW.minusDays(399));
    Long recent = save("recent", NOW.minusDays(10), null);

    int deleted = underTest.deleteCreatedBefore(NOW.minusDays(365));

    assertThat(deleted).isEqualTo(2);
    assertThat(remainingIds()).containsExactly(recent);
    assertThat(remainingIds()).doesNotContain(oldUnread, oldRead);
  }

  /** Strictly before: a row exactly on the cutoff is still inside its retention period. */
  @Test
  void cutoffsAreExclusive() {
    save("exactly-on-read-cutoff", NOW.minusDays(200), CUTOFF);
    save("exactly-on-create-cutoff", NOW.minusDays(365), null);

    assertThat(underTest.deleteReadBefore(CUTOFF)).isZero();
    assertThat(underTest.deleteCreatedBefore(NOW.minusDays(365))).isZero();
    assertThat(remainingIds()).hasSize(2);
  }

  private Long save(String suffix, LocalDateTime createDate, LocalDateTime readDate) {
    return underTest
        .save(
            EventNotification.builder()
                .recipientUserId("recipient")
                .eventType("case.handover.granted")
                .category("system")
                .title("title")
                .deduplicationKey(suffix)
                .createDate(createDate)
                .readDate(readDate)
                .build())
        .getId();
  }

  private List<Long> remainingIds() {
    return underTest.findAll().stream().map(EventNotification::getId).toList();
  }
}
