package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.ConsultantMessageStat;
import de.caritas.cob.userservice.api.model.StatisticsSettingChange;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the statistics retention statements (UserService#1561) against a real MariaDB with the
 * Liquibase-built schema: the native {@code DELETE ... LIMIT}, the update of the change log, and
 * the sent-date index added for the purge. Same env-var pattern as {@link
 * AdminStatisticsRepositoryMariaDbIT}.
 *
 * <pre>
 *   LIQUIBASE_IT_DB_URL=jdbc:mariadb://127.0.0.1:3314/userservice \
 *     ./mvnw test -Dtest=StatisticsRetentionMariaDbIT -DfailIfNoTests=true
 * </pre>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class StatisticsRetentionMariaDbIT {

  private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 1, 1, 0, 0);

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @Autowired private ConsultantMessageStatRepository messageStats;
  @Autowired private StatisticsSettingChangeRepository settingChanges;
  @Autowired private EntityManager entityManager;

  @Test
  void deleteBatchSentBeforeShouldDeleteInBatchesAndKeepRowsFromTheCutoffOn() {
    for (int i = 0; i < 5; i++) {
      messageStats.save(stat("a", CUTOFF.minusDays(i + 1)));
    }
    messageStats.save(stat("a", CUTOFF));

    assertThat(messageStats.deleteBatchSentBefore(CUTOFF, 3)).isEqualTo(3);
    assertThat(messageStats.deleteBatchSentBefore(CUTOFF, 3)).isEqualTo(2);
    assertThat(messageStats.deleteBatchSentBefore(CUTOFF, 3)).isZero();
    assertThat(messageStats.count()).isEqualTo(1);
  }

  @Test
  void deleteByConsultantHmacShouldDeleteOnlyThatConsultant() {
    messageStats.save(stat("a", CUTOFF));
    messageStats.save(stat("b", CUTOFF));

    assertThat(messageStats.deleteByConsultantHmac("a")).isEqualTo(1);
    assertThat(messageStats.count()).isEqualTo(1);
  }

  @Test
  void anonymizeActorBeforeShouldKeepTheLinesAndTheSeededIntroductionRows() {
    long before = settingChanges.count();
    settingChanges.save(change("old", CUTOFF.minusDays(1), "admin-1", "Alice"));
    settingChanges.save(change("new", CUTOFF.plusDays(1), "admin-2", "Bob"));

    assertThat(settingChanges.anonymizeActorBefore(CUTOFF)).isEqualTo(1);

    assertThat(settingChanges.count()).isEqualTo(before + 2);
    List<StatisticsSettingChange> all = settingChanges.findAll();
    assertThat(all)
        .filteredOn(c -> "old".equals(c.getNewValue()))
        .singleElement()
        .satisfies(c -> assertThat(c.getChangedByName()).isNull());
    assertThat(all)
        .filteredOn(c -> "new".equals(c.getNewValue()))
        .singleElement()
        .satisfies(c -> assertThat(c.getChangedByName()).isEqualTo("Bob"));
  }

  @Test
  void theSentDateIndexShouldExist() {
    Number indexes =
        (Number)
            entityManager
                .createNativeQuery(
                    "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'consultant_message_stat' "
                        + "AND index_name = 'idx_consultant_message_stat_sent_date'")
                .getSingleResult();
    assertThat(indexes.longValue()).isEqualTo(1);
  }

  private static ConsultantMessageStat stat(String hmac, LocalDateTime sentDate) {
    return ConsultantMessageStat.builder()
        .consultantHmac(hmac)
        .tenantId(1L)
        .sentDate(sentDate)
        .build();
  }

  private static StatisticsSettingChange change(
      String value, LocalDateTime effectiveFrom, String id, String name) {
    return StatisticsSettingChange.builder()
        .settingKey("messageCountTracking")
        .oldValue("ENABLED")
        .newValue(value)
        .changedById(id)
        .changedByName(name)
        .effectiveFrom(effectiveFrom)
        .build();
  }
}
