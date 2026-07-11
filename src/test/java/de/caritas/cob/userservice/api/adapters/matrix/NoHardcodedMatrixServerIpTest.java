package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-005 / DB-M04 source guard.
 *
 * <p>The Matrix {@code server_name} used to be a bare Hetzner IP (finding DB-M04), baking the host
 * address into every MXID ({@code @alice:91.99.219.182}) and room ID. This test scans {@code
 * src/main} and fails if any source file hardcodes an IPv4 address as a Matrix server name /
 * homeserver URL, so the bare-IP class can never silently come back.
 *
 * <p>The guard is deliberately precise, not a blunt "no IPv4 anywhere": it only flags an IPv4 that
 * (a) sits on a line referencing Matrix / Synapse / homeserver configuration, or (b) is one of the
 * known former homeserver IPs. It tolerates loopback / wildcard addresses and the explanatory
 * {@code "NOT hardcoded IPs like ..."} documentation comments (e.g. {@code application.properties}
 * around line 316), which intentionally mention the old IP to warn future editors off it.
 */
class NoHardcodedMatrixServerIpTest {

  private static final Path SOURCE_ROOT = Path.of("src/main");

  private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

  /** Loopback / wildcard addresses are never a homeserver identity — allowed everywhere. */
  private static final Set<String> ALLOWED_IPS = Set.of("0.0.0.0", "127.0.0.1");

  /** Former Matrix homeserver IPs (finding DB-M04) — must never reappear as an identity. */
  private static final Set<String> KNOWN_FORMER_HOMESERVER_IPS =
      Set.of("91.99.219.182", "91.99.183.160");

  /** Markers meaning an IPv4 on the same line is being used as a Matrix host / server name. */
  private static final List<String> MATRIX_CONTEXT_MARKERS =
      List.of(
          "matrix", "synapse", "homeserver", "server_name", "servername", "mxid", ":8008", ":8448");

  private static final Set<String> TEXT_EXTENSIONS =
      Set.of(
          ".java",
          ".properties",
          ".yml",
          ".yaml",
          ".xml",
          ".json",
          ".sql",
          ".conf",
          ".factories",
          ".txt",
          ".md");

  @Test
  void noSourceFileHardcodesAnIpv4AsMatrixServerName() throws IOException {
    assertThat(Files.isDirectory(SOURCE_ROOT))
        .as("expected %s relative to module root %s", SOURCE_ROOT, Path.of("").toAbsolutePath())
        .isTrue();

    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
      paths
          .filter(Files::isRegularFile)
          .filter(this::isTextFile)
          .forEach(path -> scanFile(path, violations));
    }

    assertThat(violations)
        .as(
            "hardcoded Matrix homeserver IPv4 found (ADR-005 / DB-M04) — use the configured "
                + "matrix.serverName / matrix.apiUrl instead of a literal IP:%n%s",
            String.join(System.lineSeparator(), violations))
        .isEmpty();
  }

  private boolean isTextFile(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith);
  }

  private void scanFile(Path file, List<String> violations) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + file, e);
    }
    for (int i = 0; i < lines.size(); i++) {
      collectViolations(file, i + 1, lines.get(i), violations);
    }
  }

  private void collectViolations(Path file, int lineNumber, String line, List<String> violations) {
    if (!IPV4.matcher(line).find()) {
      return;
    }
    // Sanctioned documentation: a full-line comment that explicitly flags the IP as NOT hardcoded.
    if (isExplanatoryNotHardcodedComment(line)) {
      return;
    }
    String lower = line.toLowerCase(Locale.ROOT);
    boolean matrixContext = MATRIX_CONTEXT_MARKERS.stream().anyMatch(lower::contains);

    Matcher matcher = IPV4.matcher(line);
    while (matcher.find()) {
      String ip = matcher.group();
      if (ALLOWED_IPS.contains(ip)) {
        continue;
      }
      if (matrixContext || KNOWN_FORMER_HOMESERVER_IPS.contains(ip)) {
        violations.add(file + ":" + lineNumber + " -> " + ip + "    | " + line.trim());
      }
    }
  }

  private boolean isExplanatoryNotHardcodedComment(String line) {
    String trimmed = line.trim();
    boolean isComment =
        trimmed.startsWith("#")
            || trimmed.startsWith("//")
            || trimmed.startsWith("*")
            || trimmed.startsWith("/*");
    return isComment && trimmed.toLowerCase(Locale.ROOT).contains("not hardcoded");
  }
}
