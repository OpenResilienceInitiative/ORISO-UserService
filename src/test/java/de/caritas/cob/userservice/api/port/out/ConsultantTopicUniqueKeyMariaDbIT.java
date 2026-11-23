package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * #1264: consultant_topic is unique per (consultant, topic, centre). MariaDB treats NULLs in a
 * unique key as distinct, so a row without a centre ("every centre", legacy) must still be unique
 * per (consultant, topic). Runs against the real MariaDB schema built by Liquibase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConsultantTopicUniqueKeyMariaDbIT {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    r.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    r.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    r.add("spring.liquibase.enabled", () -> "true");
    r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    r.add("spring.liquibase.contexts", () -> "dev,seed");
    r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    r.add("multitenancy.enabled", () -> "false");
  }

  private static final long TOPIC = 4711L;

  @Autowired JdbcTemplate jdbc;
  String consultantId;

  @BeforeEach
  void givenAConsultant() {
    consultantId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO consultant (consultant_id, username, first_name, last_name, email, tenant_id)"
            + " VALUES (?, ?, 'Synthetic', 'Topics', ?, 1)",
        consultantId,
        consultantId,
        consultantId + "@example.invalid");
  }

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM consultant_topic WHERE consultant_id = ?", consultantId);
    jdbc.update("DELETE FROM consultant WHERE consultant_id = ?", consultantId);
  }

  @Test
  void aSecondRowWithoutCentre_isRejected_forTheSameConsultantAndTopic() {
    insert(null);

    assertThatThrownBy(() -> insert(null)).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void theSameTopic_mayBeStoredOncePerCentre_andOnceWithoutCentre() {
    insert(null);
    insert(1L);
    insert(2L);

    assertThatThrownBy(() -> insert(1L)).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM consultant_topic WHERE consultant_id = ?",
                Integer.class,
                consultantId))
        .isEqualTo(3);
  }

  @Test
  void aRowWithoutCentre_andARowForCentreZero_areDistinct() {
    insert(null);
    insert(0L);
  }

  private void insert(Long agencyId) {
    jdbc.update(
        "INSERT INTO consultant_topic (id, consultant_id, topic_id, agency_id, create_date,"
            + " update_date) VALUES (NEXT VALUE FOR sequence_consultant_topic, ?, ?, ?, NOW(),"
            + " NOW())",
        consultantId,
        TOPIC,
        agencyId);
  }
}
