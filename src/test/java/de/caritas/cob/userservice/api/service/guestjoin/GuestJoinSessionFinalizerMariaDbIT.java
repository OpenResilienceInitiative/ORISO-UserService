package de.caritas.cob.userservice.api.service.guestjoin;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The atomic local session and replay contracts against real MariaDB locking and migration. */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class GuestJoinSessionFinalizerMariaDbIT extends GuestJoinSessionFinalizerTest {
  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    GuestJoinAttemptMariaDbIT.databaseProperties(registry);
  }
}
