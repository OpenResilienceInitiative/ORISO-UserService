package de.caritas.cob.userservice.api.service.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The installed resources must remain an exact copy of one reviewed Frontend artifact commit. */
class EmailTemplateIntegrityTest {

  private static final Path EMAILS = Path.of("src/main/resources/emails");

  @Test
  void installedTemplatesAndCatalogueMatchPinnedManifest() throws Exception {
    JsonNode manifest = new ObjectMapper().readTree(EMAILS.resolve("manifest.json").toFile());
    assertEquals(1, manifest.path("schemaVersion").asInt());
    assertTrue(manifest.path("frontendCommit").asText().matches("[0-9a-f]{40}"));

    Map<String, String> expected = new TreeMap<>();
    manifest
        .path("files")
        .fields()
        .forEachRemaining(entry -> expected.put(entry.getKey(), entry.getValue().asText()));
    assertEquals(expected, hashes(EMAILS));
    assertTrue(expected.containsKey("catalogue.json"));
    assertTrue(expected.keySet().stream().anyMatch(name -> name.endsWith("/willkommen.html")));
  }

  @Test
  void changingOneCopiedTemplateIsDetected(@TempDir Path temp) throws Exception {
    Path mail = temp.resolve("de-sie/willkommen.txt");
    Files.createDirectories(mail.getParent());
    Files.writeString(mail, "reviewed");
    Map<String, String> reviewed = hashes(temp);

    Files.writeString(mail, "changed");
    assertNotEquals(reviewed, hashes(temp));
  }

  @Test
  void conditionalMarkupMatchesTheFrontendKitArtifacts() throws Exception {
    Map<String, String> fragments =
        Map.of(
            "CTA_BLOCK_HTML", "cta.html",
            "CTA_BLOCK_TEXT", "cta.txt",
            "ASSURANCE_BLOCK_HTML", "assurance.html",
            "ASSURANCE_BLOCK_TEXT", "assurance.txt");
    for (var fragment : fragments.entrySet()) {
      Field field = OrisoEmailRenderer.class.getDeclaredField(fragment.getKey());
      field.setAccessible(true);
      assertEquals(
          Files.readString(EMAILS.resolve("fragments").resolve(fragment.getValue())),
          field.get(null),
          fragment.getKey());
    }
  }

  private static Map<String, String> hashes(Path root) throws Exception {
    Map<String, String> hashes = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        if (file.getFileName().toString().equals("manifest.json")) {
          continue;
        }
        byte[] content = Files.readAllBytes(file);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        hashes.put(
            root.relativize(file).toString().replace('\\', '/'), HexFormat.of().formatHex(digest));
      }
    }
    return hashes;
  }
}
