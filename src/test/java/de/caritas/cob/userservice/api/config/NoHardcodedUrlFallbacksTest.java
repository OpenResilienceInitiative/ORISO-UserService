package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Team rule (ORISO-Helm#368): a deployed service never invents a URL. A missing origin must fail
 * startup, and the production host must not appear at all. Only the local and testing profiles may
 * default.
 */
class NoHardcodedUrlFallbacksTest {

  private static final Path RESOURCES = Path.of("src/main/resources");
  private static final Path MAIN_JAVA = Path.of("src/main/java");

  /** {@code ${X:http…}} — a literal URL as a placeholder default. */
  private static final Pattern URL_DEFAULT = Pattern.compile("\\$\\{[^}:]+:\\s*https?://");

  /** {@code ${X:${app.base.url}}} — a URL that silently borrows another environment's origin. */
  private static final Pattern URL_CHAIN =
      Pattern.compile("\\$\\{[^}:]+:\\s*\\$\\{[^}]*(url|base-url)\\}");

  /** {@code some.base-url=http://…} — a literal URL where an env var belongs. */
  private static final Pattern LITERAL_BASE_URL =
      Pattern.compile("^[\\w.-]*(base[.-]url|base-url|server\\.host)\\s*=\\s*https?://");

  /** A hardcoded loopback URL in production code. */
  private static final Pattern LOOPBACK_LITERAL =
      Pattern.compile("\"https?://(localhost|127\\.0\\.0\\.1)");

  /** An internal service host baked into production code, e.g. {@code "http://synapse:8008"}. */
  private static final Pattern SERVICE_HOST_LITERAL = Pattern.compile("\"https?://[a-z0-9-]+:\\d+");

  private static final Pattern ORISO_HOST = Pattern.compile("oriso\\.org");

  @Test
  void deployedProfiles_declareNoUrlDefaults_andNeverNameTheProductionHost() throws IOException {
    List<String> violations;
    try (Stream<Path> files = Files.list(RESOURCES)) {
      violations =
          files
              .filter(p -> p.getFileName().toString().matches("application(-[a-z]+)?\\.properties"))
              .filter(p -> !p.getFileName().toString().matches("application-(local|testing)\\..*"))
              .flatMap(
                  file -> violationsIn(file, URL_DEFAULT, URL_CHAIN, LITERAL_BASE_URL, ORISO_HOST))
              .toList();
    }
    assertThat(violations).isEmpty();
  }

  @Test
  void productionCode_bakesInNoUrlDefaults_andNeverNamesTheProductionHost() throws IOException {
    List<String> violations;
    try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
      violations =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .flatMap(
                  file ->
                      violationsIn(
                          file,
                          URL_DEFAULT,
                          URL_CHAIN,
                          LOOPBACK_LITERAL,
                          SERVICE_HOST_LITERAL,
                          ORISO_HOST))
              .toList();
    }
    assertThat(violations).isEmpty();
  }

  private static Stream<String> violationsIn(Path file, Pattern... patterns) {
    try {
      List<String> lines = Files.readAllLines(file);
      return IntStream.range(0, lines.size())
          .filter(i -> Stream.of(patterns).anyMatch(p -> p.matcher(lines.get(i)).find()))
          .mapToObj(i -> file + ":" + (i + 1) + " " + lines.get(i).strip());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
