package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.ConsultantMessageStat;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Executes {@link ConsultantMessageStatRepository} against the H2 testing schema: persistence,
 * tenant/hmac scoping, and the period boundary of the count query.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ConsultantMessageStatRepositoryIT {

  private static final String HMAC_A = "hmac-a";
  private static final String HMAC_B = "hmac-b";

  @Autowired private ConsultantMessageStatRepository underTest;

  @Test
  void countShouldOnlyMatchSameHmacTenantAndPeriod() {
    save(HMAC_A, 1L, LocalDateTime.of(2026, 7, 15, 10, 0)); // in scope
    save(HMAC_A, 1L, LocalDateTime.of(2026, 7, 20, 10, 0)); // in scope
    save(HMAC_B, 1L, LocalDateTime.of(2026, 7, 15, 10, 0)); // different consultant
    save(HMAC_A, 2L, LocalDateTime.of(2026, 7, 15, 10, 0)); // different tenant
    save(HMAC_A, 1L, LocalDateTime.of(2026, 6, 30, 23, 59)); // before period
    save(HMAC_A, 1L, LocalDateTime.of(2026, 8, 1, 0, 0)); // on/after exclusive upper bound

    var count =
        underTest.countByConsultantHmacAndTenantIdInPeriod(
            HMAC_A, 1L, LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 0));

    assertThat(count).isEqualTo(2L);
  }

  @Test
  void deleteBatchSentBeforeShouldRemoveOnlyRowsBeforeTheCutoff_AndNoMoreThanTheBatch() {
    var cutoff = LocalDateTime.of(2026, 7, 1, 0, 0);
    save(HMAC_A, 1L, cutoff.minusDays(3));
    save(HMAC_B, 2L, cutoff.minusDays(2));
    save(HMAC_A, 1L, cutoff.minusSeconds(1));
    save(HMAC_A, 1L, cutoff); // on the cutoff: still inside the retention period
    save(HMAC_A, 1L, cutoff.plusDays(1));

    assertThat(underTest.deleteBatchSentBefore(cutoff, 2)).isEqualTo(2);
    assertThat(underTest.count()).isEqualTo(3);
    assertThat(underTest.deleteBatchSentBefore(cutoff, 2)).isEqualTo(1);
    assertThat(underTest.deleteBatchSentBefore(cutoff, 2)).isZero();
    assertThat(underTest.findAll())
        .extracting(ConsultantMessageStat::getSentDate)
        .containsExactlyInAnyOrder(cutoff, cutoff.plusDays(1));
  }

  @Test
  void deleteByConsultantHmacShouldRemoveEveryRowOfThatConsultantInAllTenants() {
    save(HMAC_A, 1L, LocalDateTime.of(2026, 7, 15, 10, 0));
    save(HMAC_A, 2L, LocalDateTime.of(2026, 7, 16, 10, 0));
    save(HMAC_B, 1L, LocalDateTime.of(2026, 7, 15, 10, 0));

    assertThat(underTest.deleteByConsultantHmac(HMAC_A)).isEqualTo(2);

    assertThat(underTest.findAll())
        .extracting(ConsultantMessageStat::getConsultantHmac)
        .containsExactly(HMAC_B);
  }

  private void save(String hmac, Long tenantId, LocalDateTime sentDate) {
    underTest.save(
        ConsultantMessageStat.builder()
            .consultantHmac(hmac)
            .tenantId(tenantId)
            .sentDate(sentDate)
            .build());
  }
}
