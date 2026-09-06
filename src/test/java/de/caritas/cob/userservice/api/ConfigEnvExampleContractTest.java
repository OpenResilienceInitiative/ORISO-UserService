package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code config.env.example} able to start the service.
 *
 * <p>Issue #1099: three separate startup guards aborted the Spring context for anyone who followed
 * the repository's own instructions, because the example file was never updated alongside the
 * guards that were added after it. Nothing tied the two together, so each new required variable
 * could slip through the same way.
 *
 * <p>This is that tie. A guard that names an environment variable fails the build until the example
 * declares it.
 */
class ConfigEnvExampleContractTest {

  private static final Path EXAMPLE = Path.of("config.env.example");
  private static final Path MAIN_JAVA = Path.of("src/main/java");
  private static final Path APPLICATION_PROPERTIES =
      Path.of("src/main/resources/application.properties");

  /** The convention every startup guard follows: {@code ... must be set (THE_ENV_VAR)}. */
  private static final Pattern GUARDED_VARIABLE =
      Pattern.compile("must be set \\(([A-Z][A-Z0-9_]*)\\)");

  @Test
  void everyGuardedVariableMustBeDeclaredInTheExample() throws IOException {
    var guarded = new LinkedHashSet<String>();
    try (var sourceFiles = Files.walk(MAIN_JAVA)) {
      for (var sourceFile :
          sourceFiles.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
        var matcher = GUARDED_VARIABLE.matcher(Files.readString(sourceFile));
        while (matcher.find()) {
          guarded.add(matcher.group(1));
        }
      }
    }

    assertThat(guarded)
        .as("the known startup guards must still be findable by the 'must be set (VAR)' convention")
        .contains("STATISTICS_MESSAGE_COUNT_HMAC_SECRET", "MATRIXRTC_CALL_POLICY_HMAC_SECRET");

    assertThat(declaredVariables().keySet())
        .as(
            "a startup guard names an environment variable that config.env.example does not set:"
                + " add a placeholder line for it, and a row in docs/required-environment.md")
        .containsAll(guarded);
  }

  @Test
  void exampleMustSatisfyTheSchemaMigrationGuard() throws IOException {
    var declared = declaredVariables();

    var liquibaseEnabled =
        Boolean.parseBoolean(declared.getOrDefault("SPRING_LIQUIBASE_ENABLED", "true"));
    var externallyManaged =
        Boolean.parseBoolean(declared.getOrDefault("ORISO_MIGRATIONS_EXTERNALLY_MANAGED", "false"));
    assertThat(liquibaseEnabled || externallyManaged)
        .as(
            "config.env.example switches Liquibase off without ORISO_MIGRATIONS_EXTERNALLY_MANAGED,"
                + " so SchemaMigrationGuard refuses to start the service")
        .isTrue();

    assertThat(declared.getOrDefault("SPRING_JPA_HIBERNATE_DDL_AUTO", "validate"))
        .as("SchemaMigrationGuard accepts no ddl-auto mode other than validate")
        .isEqualToIgnoringCase("validate");
  }

  @Test
  void applicationPropertiesMustKeepTheDefaultsTheExampleLeavesUnset() throws IOException {
    // The example sets neither, so a filled-in config.env inherits these two shipped defaults.
    assertThat(Files.readString(APPLICATION_PROPERTIES))
        .contains("spring.jpa.hibernate.ddl-auto=validate")
        .contains("spring.liquibase.enabled=${SPRING_LIQUIBASE_ENABLED:true}");
  }

  @Test
  void guardedSecretsMustShipAsPlaceholders() throws IOException {
    assertThat(declaredVariables())
        .containsEntry("STATISTICS_MESSAGE_COUNT_HMAC_SECRET", "CHANGE_ME")
        .containsEntry("MATRIXRTC_CALL_POLICY_HMAC_SECRET", "CHANGE_ME");
  }

  /** The uncommented {@code KEY=VALUE} lines of the example, blank values excluded. */
  private static Map<String, String> declaredVariables() throws IOException {
    Map<String, String> declared = new HashMap<>();
    for (var line : Files.readAllLines(EXAMPLE)) {
      var trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      var separator = trimmed.indexOf('=');
      if (separator < 1) {
        continue;
      }
      var value = trimmed.substring(separator + 1).strip();
      if (!value.isEmpty()) {
        declared.put(trimmed.substring(0, separator).strip(), value);
      }
    }
    return declared;
  }
}
