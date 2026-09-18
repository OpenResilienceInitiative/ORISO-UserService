package de.caritas.cob.userservice.api.service.guestjoin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The public Join, authentication-retry and request-binding contracts against real MariaDB locking
 * and migration.
 */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class GuestJoinServiceMariaDbIT extends GuestJoinServiceTest {
  @Autowired JdbcTemplate jdbc;

  @Test
  void firstSessionFromTheMigratedZeroBasedSequenceCanBeJoinedAndResumed() {
    assertThat(sessions.count()).isZero();
    // The production migration starts sequence_session at zero. Keep this edge deterministic
    // even when another test has already consumed the first value.
    jdbc.execute("ALTER SEQUENCE sequence_session RESTART WITH 0");
    var first = join();
    var replay = join();
    assertThat(first.sessionId()).isZero();
    assertThat(replay.sessionId()).isEqualTo(first.sessionId());
    assertThat(sessions.count()).isEqualTo(1);
    assertThat(users.count()).isEqualTo(1);
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    GuestJoinAttemptMariaDbIT.databaseProperties(registry);
  }
}
