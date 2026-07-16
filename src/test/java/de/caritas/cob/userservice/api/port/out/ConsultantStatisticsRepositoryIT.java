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
 * Executes every native query of {@link ConsultantStatisticsRepository} against the H2 testing
 * schema. The goal is SQL syntax and parameter-mapping validation; result correctness is covered by
 * the service unit tests.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.ANY)
class ConsultantStatisticsRepositoryIT {

  private static final String CONSULTANT_ID = "consultant-user-id";
  private static final Long TENANT_ID = 1L;
  private static final LocalDateTime FROM = LocalDateTime.of(2026, 6, 1, 0, 0);
  private static final LocalDateTime TO = LocalDateTime.of(2026, 8, 1, 0, 0);

  @Autowired private ConsultantStatisticsRepository underTest;

  @Test
  void allConsultantScopedQueriesShouldExecute() {
    assertThat(underTest.countAssignedSessionsCreatedInPeriod(CONSULTANT_ID, TENANT_ID, FROM, TO))
        .isZero();
    assertThat(underTest.countActiveSessionsInPeriod(CONSULTANT_ID, TENANT_ID, FROM, TO)).isZero();
  }
}
