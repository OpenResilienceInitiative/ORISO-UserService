package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #458. Pre-Dev ran with {@code spring.liquibase.enabled=false}, so changeset 0067 shipped in
 * the image but never reached the database. Every consultant query answered 500 with {@code Unknown
 * column 'pending_public_slug'} — consultant listing, login enrichment and deletion at once, and
 * the cause was invisible until someone read the schema by hand.
 *
 * <p>Both mechanisms that would have caught it exist and are configured correctly today. What did
 * not exist is anything stopping a deployment from switching them off again.
 */
class SchemaMigrationGuardTest {

  private SchemaMigrationGuard guard;

  @BeforeEach
  void setUp() {
    guard = new SchemaMigrationGuard();
    givenMigrationsAreExecutedOnStartup();
  }

  @Test
  void shouldPassWhenLiquibaseRunsAndHibernateValidates() {
    assertThatCode(guard::validateSchemaMigrationSetup).doesNotThrowAnyException();
  }

  @Test
  void shouldRejectDisabledLiquibase() {
    setField(guard, "liquibaseEnabled", false);

    assertThatThrownBy(guard::validateSchemaMigrationSetup)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.liquibase.enabled")
        .hasMessageContaining("SPRING_LIQUIBASE_ENABLED");
  }

  @Test
  void shouldRejectHibernateSchemaValidationBeingTurnedOff() {
    setField(guard, "hibernateDdlAuto", "none");

    assertThatThrownBy(guard::validateSchemaMigrationSetup)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.jpa.hibernate.ddl-auto")
        .hasMessageContaining("none");
  }

  @Test
  void shouldRejectSchemaMutatingHibernateModes() {
    setField(guard, "hibernateDdlAuto", "update");

    assertThatThrownBy(guard::validateSchemaMigrationSetup)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.jpa.hibernate.ddl-auto");
  }

  @Test
  void shouldReportEveryProblemAtOnce() {
    setField(guard, "liquibaseEnabled", false);
    setField(guard, "hibernateDdlAuto", "none");

    assertThatThrownBy(guard::validateSchemaMigrationSetup)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.liquibase.enabled")
        .hasMessageContaining("spring.jpa.hibernate.ddl-auto");
  }

  /**
   * The escape hatch has to be deliberate. A setup where a DBA or a pre-deploy job owns the
   * migrations is legitimate; forgetting to switch Liquibase back on is not, and the two must not
   * look the same from the outside.
   */
  @Test
  void shouldAllowDisabledLiquibaseWhenMigrationsAreDeclaredExternallyManaged() {
    setField(guard, "liquibaseEnabled", false);
    setField(guard, "migrationsExternallyManaged", true);

    assertThatCode(guard::validateSchemaMigrationSetup).doesNotThrowAnyException();
  }

  @Test
  void shouldStillRequireHibernateValidationWhenMigrationsAreExternallyManaged() {
    setField(guard, "migrationsExternallyManaged", true);
    setField(guard, "hibernateDdlAuto", "none");

    assertThatThrownBy(guard::validateSchemaMigrationSetup)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.jpa.hibernate.ddl-auto");
  }

  private void givenMigrationsAreExecutedOnStartup() {
    setField(guard, "liquibaseEnabled", true);
    setField(guard, "hibernateDdlAuto", "validate");
    setField(guard, "migrationsExternallyManaged", false);
  }
}
