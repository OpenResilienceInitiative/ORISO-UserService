package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Executes every native query of {@link AdminStatisticsRepository} against the H2 testing schema.
 * The goal is SQL syntax and projection-mapping validation; result correctness is covered by the
 * service unit tests.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.ANY)
class AdminStatisticsRepositoryIT {

  private static final long TENANT_ID = 1L;
  private static final LocalDateTime FROM = LocalDateTime.of(2026, 6, 1, 0, 0);
  private static final LocalDateTime TO = LocalDateTime.of(2026, 8, 1, 0, 0);

  @Autowired private AdminStatisticsRepository underTest;

  @Test
  void allTenantScopedQueriesShouldExecute() {
    assertThat(underTest.countNewSessionsByAgency(TENANT_ID, FROM, TO)).isEmpty();
    assertThat(underTest.countActiveCasesByAgency(TENANT_ID)).isEmpty();
    assertThat(underTest.countActiveSessionsSinceByAgency(TENANT_ID, FROM)).isEmpty();
    assertThat(underTest.countDailyNewSessionsByAgency(TENANT_ID, FROM)).isEmpty();
    assertThat(underTest.countSessionTopicsByAgency(TENANT_ID, FROM, TO)).isEmpty();
    assertThat(underTest.countConsultantsByAgency(TENANT_ID)).isEmpty();
    assertThat(underTest.countConsultantsForTenant(TENANT_ID)).isZero();
    assertThat(underTest.countGroupChatsByAgency(TENANT_ID, FROM, TO)).isEmpty();
    assertThat(underTest.countGroupChatsForTenant(TENANT_ID, FROM, TO)).isZero();
  }

  @Test
  void allPlatformScopedQueriesShouldExecute() {
    assertThat(underTest.countNewSessionsByTenant(FROM, TO)).isEmpty();
    assertThat(underTest.countActiveCasesByTenant()).isEmpty();
    assertThat(underTest.countActiveSessionsSinceByTenant(FROM)).isEmpty();
    assertThat(underTest.countDailyNewSessionsByTenant(FROM)).isEmpty();
    assertThat(underTest.countSessionTopicsByTenant(FROM, TO)).isEmpty();
    assertThat(underTest.countConsultantsByTenant()).isEmpty();
    assertThat(underTest.countGroupChatsByTenant(FROM, TO)).isEmpty();
  }
}
