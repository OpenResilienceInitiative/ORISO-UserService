package de.caritas.cob.userservice.api.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when this deployment would run application code against a database nobody
 * migrates.
 *
 * <p>Issue #458: Pre-Dev ran with {@code spring.liquibase.enabled=false}. Changeset 0067 shipped
 * inside the image and never reached the database, so from the moment that image was deployed every
 * consultant query answered 500 with {@code Unknown column 'pending_public_slug'} — listing, login
 * enrichment and deletion at once. Nothing failed at deploy time; the drift only surfaced as
 * runtime errors, and remediation was hand-written SQL.
 *
 * <p>Two mechanisms would each have caught it before a single request was served: Liquibase running
 * on startup, and Hibernate validating the mapped entities against the live schema. Both are
 * configured correctly today. This guard is what keeps them that way — a deployment that switches
 * either off fails during a rolling update, while the previous replica set keeps serving traffic.
 *
 * <p>{@code oriso.migrations.externally-managed=true} is the deliberate escape hatch for setups
 * where a pre-deploy job or a DBA owns the migrations. Hibernate validation stays required either
 * way: it is the check that catches code shipped without a matching changeset at all.
 */
@Component
@Profile("!testing")
public class SchemaMigrationGuard {

  /**
   * Anything that lets Hibernate create or alter tables is unsafe next to Liquibase, and anything
   * that skips the comparison defeats the purpose. Only {@code validate} does what is wanted here.
   */
  private static final Set<String> ACCEPTED_DDL_AUTO_MODES = Set.of("validate");

  @Value("${spring.liquibase.enabled:true}")
  private boolean liquibaseEnabled;

  @Value("${spring.jpa.hibernate.ddl-auto:}")
  private String hibernateDdlAuto;

  @Value("${oriso.migrations.externally-managed:false}")
  private boolean migrationsExternallyManaged;

  @PostConstruct
  public void validateSchemaMigrationSetup() {
    List<String> problems = new ArrayList<>();

    if (!liquibaseEnabled && !migrationsExternallyManaged) {
      problems.add(
          "spring.liquibase.enabled (SPRING_LIQUIBASE_ENABLED) is false, so changesets shipped in "
              + "this image will never be applied. Enable it, or declare that something else "
              + "applies them by setting oriso.migrations.externally-managed=true.");
    }

    String ddlAuto =
        hibernateDdlAuto == null ? "" : hibernateDdlAuto.trim().toLowerCase(Locale.ROOT);
    if (!ACCEPTED_DDL_AUTO_MODES.contains(ddlAuto)) {
      problems.add(
          "spring.jpa.hibernate.ddl-auto is '"
              + ddlAuto
              + "', expected 'validate'. Without it the service starts against a schema that does "
              + "not match its entities and fails later with 'Unknown column' at request time.");
    }

    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "CRITICAL: unsafe database migration setup — this deployment would run against a schema "
              + "nobody migrates or verifies (see issue #458):\n"
              + String.join("\n", problems.stream().map(p -> "  - " + p).toList()));
    }
  }
}
