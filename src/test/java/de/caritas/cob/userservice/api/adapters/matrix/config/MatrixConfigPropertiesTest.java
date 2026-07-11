package de.caritas.cob.userservice.api.adapters.matrix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ADR-005 / DB-M04 regression guard for the production {@code application.properties}.
 *
 * <p>The Matrix homeserver address (finding DB-M04) must never be baked into the service. {@code
 * matrix.serverName} and {@code matrix.apiUrl} must resolve from the {@code MATRIX_SERVER_NAME} /
 * {@code MATRIX_API_URL} environment variables with NO hardcoded, non-empty default — so the
 * homeserver can be moved (e.g. to {@code matrix.oriso.org}) purely via configuration, without a
 * host address leaking into the source tree.
 */
class MatrixConfigPropertiesTest {

  private static final Path APPLICATION_PROPERTIES =
      Path.of("src/main/resources/application.properties");

  @Test
  void matrixServerName_should_resolve_from_env_with_no_hardcoded_default() throws IOException {
    assertEnvDrivenWithEmptyDefault("matrix.serverName", "MATRIX_SERVER_NAME");
  }

  @Test
  void matrixApiUrl_should_resolve_from_env_with_no_hardcoded_default() throws IOException {
    assertEnvDrivenWithEmptyDefault("matrix.apiUrl", "MATRIX_API_URL");
  }

  private void assertEnvDrivenWithEmptyDefault(String propertyKey, String envVar)
      throws IOException {
    String rawValue = loadRawProperty(propertyKey);
    assertThat(rawValue)
        .as("%s must be present in application.properties", propertyKey)
        .isNotNull();

    // Must be a pure ${ENV:default} placeholder referencing the expected environment variable...
    Matcher matcher =
        Pattern.compile("^\\$\\{" + Pattern.quote(envVar) + ":(.*)}$").matcher(rawValue.trim());
    assertThat(matcher.matches())
        .as("%s must resolve from a ${%s:...} placeholder (was: %s)", propertyKey, envVar, rawValue)
        .isTrue();
    // ...with NO hardcoded, non-empty default baked in (the segment after the colon must be blank).
    assertThat(matcher.group(1))
        .as("%s must not carry a hardcoded, non-empty default (was: %s)", propertyKey, rawValue)
        .isBlank();
  }

  /** Reads the raw, unresolved property value straight from the file (no Spring interpolation). */
  private String loadRawProperty(String key) throws IOException {
    assertThat(Files.exists(APPLICATION_PROPERTIES))
        .as(
            "expected %s relative to module root %s",
            APPLICATION_PROPERTIES, Path.of("").toAbsolutePath())
        .isTrue();
    var properties = new Properties();
    try (InputStream in = Files.newInputStream(APPLICATION_PROPERTIES)) {
      properties.load(in);
    }
    return properties.getProperty(key);
  }
}
