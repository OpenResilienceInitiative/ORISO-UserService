package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.CommunicationStat;
import de.caritas.cob.userservice.api.model.CommunicationStat.Kind;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression test for the Pre-Dev incident (correlationId 2cbc3e28-de31-4772-89fa-06e1b24a5318):
 * {@code GET /useradmin/statistics/dashboard} threw {@code UnsupportedOperationException: Cannot
 * project java.time.LocalDate to java.sql.Date} against real MariaDB.
 *
 * <p>The plain H2 {@code @DataJpaTest} ({@link AdminStatisticsRepositoryIT}) cannot catch this: it
 * asserts against an empty {@code session} table, so the {@code CAST(... AS DATE)} projection
 * getter is never actually invoked, and even with rows H2's JDBC driver hands Spring's projection
 * proxy a value that converts cleanly to the (previously) declared {@code java.sql.Date} getter.
 * MariaDB's JDBC driver returns a {@code java.time.LocalDate} for the same native cast, and Spring
 * Data has no registered converter from {@code LocalDate} to the concrete {@code java.sql.Date}
 * class, so the projection proxy blows up.
 *
 * <p>This test runs {@link AdminStatisticsRepository#countDailyNewSessionsByTenant} against a real
 * MariaDB instance, on a fully Liquibase-built {@code userservice} schema, with an actual {@code
 * session} row so the {@code day} projection getter is genuinely exercised end-to-end.
 *
 * <p>Follows the same env-var-provided-database pattern as {@link
 * de.caritas.cob.userservice.api.DatabaseChangelogDriftIT} (this project's Testcontainers 1.17.6
 * dependency predates current Docker Desktop API versions and cannot bootstrap containers).
 *
 * <pre>
 *   docker run -d --name stats-mariadb-it -p 3314:3306 -e MARIADB_ROOT_PASSWORD=root \
 *     -e MARIADB_DATABASE=userservice mariadb:11.0.6
 *   LIQUIBASE_IT_DB_URL=jdbc:mariadb://127.0.0.1:3314/userservice \
 *     ./mvnw test -Dtest=AdminStatisticsRepositoryMariaDbIT -DfailIfNoTests=true
 * </pre>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class AdminStatisticsRepositoryMariaDbIT {

  private static final long TENANT_ID = 4242L;
  private static final long AGENCY_ID = 777L;

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

  @Autowired private AdminStatisticsRepository underTest;
  @Autowired private EntityManager entityManager;
  @Autowired private CommunicationStatRepository communicationStats;

  @Test
  void communicationCountsShouldRunOnTheLiquibaseSchemaAndRejectASecondRowPerCall() {
    var now = LocalDateTime.now().withNano(0);
    communicationStats.save(communicationRow(Kind.ADVICE_SEEKER_MESSAGE, now, null));
    communicationStats.save(communicationRow(Kind.AUDIO_CALL, now, "mariadb-call"));
    entityManager
        .createNativeQuery(
            "INSERT INTO consultant_message_stat (consultant_hmac, tenant_id, agency_id, "
                + "sent_date) VALUES ('h', :tenantId, :agencyId, :sentDate)")
        .setParameter("tenantId", TENANT_ID)
        .setParameter("agencyId", AGENCY_ID)
        .setParameter("sentDate", now)
        .executeUpdate();
    entityManager.flush();

    var from = now.minusDays(1);
    var to = now.plusDays(1);
    assertThat(underTest.countCommunicationByAgency(TENANT_ID, from, to))
        .extracting(r -> r.getGroupId() + ":" + r.getKind() + ":" + r.getTotal())
        .containsExactlyInAnyOrder(
            AGENCY_ID + ":ADVICE_SEEKER_MESSAGE:1", AGENCY_ID + ":AUDIO_CALL:1");
    assertThat(underTest.countCommunicationByTenant(from, to))
        .extracting(r -> r.getGroupId() + ":" + r.getKind() + ":" + r.getTotal())
        .contains(TENANT_ID + ":AUDIO_CALL:1");
    assertThat(underTest.countCounsellorMessagesByAgency(TENANT_ID, from, to))
        .extracting(r -> r.getGroupId() + ":" + r.getTotal())
        .containsExactly(AGENCY_ID + ":1");
    assertThat(underTest.countCounsellorMessagesByTenant(from, to))
        .extracting(r -> r.getGroupId() + ":" + r.getTotal())
        .contains(TENANT_ID + ":1");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> {
              communicationStats.save(communicationRow(Kind.AUDIO_CALL, now, "mariadb-call"));
              entityManager.flush();
            })
        .as("the unique call key counts one call once")
        .isInstanceOf(Exception.class);
  }

  private static CommunicationStat communicationRow(
      Kind kind, LocalDateTime occurredAt, String eventKey) {
    return CommunicationStat.builder()
        .tenantId(TENANT_ID)
        .agencyId(AGENCY_ID)
        .sessionId(1L)
        .kind(kind)
        .occurredAt(occurredAt)
        .eventKey(eventKey)
        .build();
  }

  @Test
  void countDailyNewSessionsByTenantShouldProjectDayWithoutConversionError() {
    var today = LocalDate.now();
    seedSingleSession(today);

    // Before the fix, this line threw:
    // java.lang.UnsupportedOperationException: Cannot project java.time.LocalDate to
    // java.sql.Date; Target type is not an interface and no matching Converter found
    var rows = underTest.countDailyNewSessionsByTenant(today.atStartOfDay().minusDays(1));

    assertThat(rows).hasSize(1);
    var row = rows.get(0);
    assertThat(row.getGroupId()).isEqualTo(TENANT_ID);
    assertThat(row.getTotal()).isEqualTo(1L);
    assertThat(row.getDay()).isEqualTo(today);
  }

  @Test
  void countHourlyNewSessionsByTenantShouldProjectDayAndHour() {
    var today = LocalDate.now();
    seedSingleSession(today);

    var rows =
        underTest.countHourlyNewSessionsByTenant(
            today.atStartOfDay(), today.plusDays(1).atStartOfDay());

    assertThat(rows).hasSize(1);
    var row = rows.get(0);
    assertThat(row.getGroupId()).isEqualTo(TENANT_ID);
    assertThat(row.getDay()).isEqualTo(today);
    assertThat(row.getHourOfDay().intValue()).isEqualTo(9);
    assertThat(row.getTotal()).isEqualTo(1L);
    assertThat(
            underTest.countHourlyNewSessionsByTenant(
                today.minusDays(2).atStartOfDay(), today.atStartOfDay()))
        .as("the end of the range is exclusive")
        .isEmpty();
  }

  @Test
  void countHourlyNewSessionsByAgencyShouldProjectAgencyDayAndHour() {
    var today = LocalDate.now();
    seedSingleSession(today, AGENCY_ID);

    var rows =
        underTest.countHourlyNewSessionsByAgency(
            TENANT_ID, today.atStartOfDay(), today.plusDays(1).atStartOfDay());

    assertThat(rows).hasSize(1);
    var row = rows.get(0);
    assertThat(row.getGroupId()).isEqualTo(AGENCY_ID);
    assertThat(row.getDay()).isEqualTo(today);
    assertThat(row.getHourOfDay().intValue()).isEqualTo(9);
    assertThat(row.getTotal()).isEqualTo(1L);
    assertThat(
            underTest.countHourlyNewSessionsByAgency(
                TENANT_ID, today.minusDays(2).atStartOfDay(), today.atStartOfDay()))
        .as("the end of the range is exclusive")
        .isEmpty();
  }

  @Test
  void activeCounsellorCountsShouldRunOnMariaDbAndCountTheSamePeople() {
    seedConsultant("active", false, "ACTIVE", "CREATED");
    seedConsultant("absent", true, "ACTIVE", "CREATED");
    seedConsultant("leaving", false, "PENDING_DELETION", "CREATED");
    seedConsultant("joining", false, "ACTIVE", "IN_PROGRESS");

    assertThat(underTest.countConsultantsForTenant(TENANT_ID)).isEqualTo(1);
    assertThat(underTest.countConsultantsByAgency(TENANT_ID))
        .extracting(row -> row.getGroupId() + ":" + row.getTotal())
        .containsExactly(AGENCY_ID + ":1");
    assertThat(underTest.countConsultantsByTenant())
        .extracting(row -> row.getGroupId() + ":" + row.getTotal())
        .contains(TENANT_ID + ":1");
  }

  private void seedConsultant(
      String name, boolean absent, String lifecycleState, String linkStatus) {
    var id = "stats-1560-" + name;
    entityManager
        .createNativeQuery(
            "INSERT INTO `consultant` (`consultant_id`, `username`, `first_name`, `last_name`, "
                + "`email`, `is_absent`, `is_team_consultant`, `language_formal`, `tenant_id`, "
                + "`status`, `deletion_lifecycle_state`) "
                + "VALUES (:id, :id, 'A', 'B', :email, :absent, 0, 1, :tenantId, 'CREATED', "
                + ":lifecycle)")
        .setParameter("id", id)
        .setParameter("email", id + "@example.test")
        .setParameter("absent", absent ? 1 : 0)
        .setParameter("tenantId", TENANT_ID)
        .setParameter("lifecycle", lifecycleState)
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "INSERT INTO `consultant_agency` (`id`, `consultant_id`, `agency_id`, `tenant_id`, "
                + "`status`) VALUES (:relationId, :id, :agencyId, :tenantId, :status)")
        .setParameter("relationId", 990_000L + Math.abs(name.hashCode() % 1000))
        .setParameter("id", id)
        .setParameter("agencyId", AGENCY_ID)
        .setParameter("tenantId", TENANT_ID)
        .setParameter("status", linkStatus)
        .executeUpdate();
    entityManager.flush();
  }

  private void seedSingleSession(LocalDate day) {
    seedSingleSession(day, null);
  }

  private void seedSingleSession(LocalDate day, Long agencyId) {
    var userId = "stats-it-user-" + TENANT_ID;
    entityManager
        .createNativeQuery(
            "INSERT INTO `user` (`user_id`, `tenant_id`, `username`, `email`) "
                + "VALUES (:userId, :tenantId, :username, :email)")
        .setParameter("userId", userId)
        .setParameter("tenantId", TENANT_ID)
        .setParameter("username", "stats-it-user")
        .setParameter("email", "stats-it-user@example.test")
        .executeUpdate();

    entityManager
        .createNativeQuery(
            "INSERT INTO `session` "
                + "(`id`, `tenant_id`, `user_id`, `consulting_type`, `postcode`, `status`, "
                + "`create_date`, `update_date`) "
                + "VALUES (:id, :tenantId, :userId, 0, '12345', 1, :createDate, :createDate)")
        .setParameter("id", 999_000_001L)
        .setParameter("tenantId", TENANT_ID)
        .setParameter("userId", userId)
        .setParameter("createDate", LocalDateTime.of(day, java.time.LocalTime.of(9, 0)))
        .executeUpdate();

    if (agencyId != null) {
      entityManager
          .createNativeQuery("UPDATE `session` SET `agency_id` = :agencyId WHERE `id` = :id")
          .setParameter("agencyId", agencyId)
          .setParameter("id", 999_000_001L)
          .executeUpdate();
    }

    entityManager.flush();
  }
}
