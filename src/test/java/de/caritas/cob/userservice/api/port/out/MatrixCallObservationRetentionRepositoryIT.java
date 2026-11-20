package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/** Runs the bulk retention delete of {@link MatrixCallObservationRepository} on the test schema. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class MatrixCallObservationRetentionRepositoryIT {
  private static final long CUTOFF = 1_000_000L;

  @Autowired private MatrixCallObservationRepository underTest;

  @Test
  void removesOnlyRowsWhoseLeaseAndEndAreBothBeforeTheCutoff() {
    save("old-ended", CUTOFF - 500, CUTOFF - 900L);
    save("old-expired-never-ended", CUTOFF - 500, null);
    long liveButAncientRegistration = save("still-leased", CUTOFF + 10, null);
    long endedLongAfterExpiry = save("late-end", CUTOFF - 500, CUTOFF + 5);
    long recentEnd = save("recent", CUTOFF + 100, CUTOFF + 50);

    int deleted = underTest.deleteFinishedBefore(CUTOFF);

    assertThat(deleted).isEqualTo(2);
    assertThat(remaining())
        .containsExactlyInAnyOrder(liveButAncientRegistration, endedLongAfterExpiry, recentEnd);
  }

  @Test
  void cutoffIsExclusive() {
    save("on-the-line", CUTOFF, CUTOFF);

    assertThat(underTest.deleteFinishedBefore(CUTOFF)).isZero();
  }

  private long save(String suffix, long expiresAt, Long endedAt) {
    return underTest
        .save(
            MatrixCallObservation.builder()
                .origin(MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION)
                .sourceRoomId("!source-" + suffix + ":example")
                .callId("call-" + suffix)
                .mediaRoomId("!media-" + suffix + ":example")
                .creatorMatrixId("@creator:example")
                .tenantId(7L)
                .chatId(45L)
                .video(true)
                .registeredAt(1L)
                .lastSeenAt(1L)
                .expiresAt(expiresAt)
                .endedAt(endedAt)
                .build())
        .getId();
  }

  private List<Long> remaining() {
    return underTest.findAll().stream().map(MatrixCallObservation::getId).toList();
  }
}
