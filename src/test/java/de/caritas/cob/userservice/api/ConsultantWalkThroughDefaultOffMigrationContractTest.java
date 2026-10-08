package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * #1526: only the default flips; existing counsellors keep their tour setting (review on #1236).
 */
class ConsultantWalkThroughDefaultOffMigrationContractTest {

  private static final String FOLDER =
      "db/changelog/changeset/20260923_consultant_walk_through_default_off/";

  @Test
  void migrationShouldOnlyChangeTheColumnDefault() throws Exception {
    String statements =
        Arrays.stream(read(FOLDER + "migrate.sql").split("\n"))
            .filter(line -> !line.trim().startsWith("--"))
            .collect(Collectors.joining("\n"))
            .toLowerCase();

    assertThat(statements)
        .contains("alter table consultant alter column walk_through_enabled set default 0")
        .doesNotContain("update ")
        .doesNotContain("delete ")
        .doesNotContain("insert ");
  }

  @Test
  void rollbackShouldRestoreTheOldDefault() throws Exception {
    assertThat(read(FOLDER + "changeSet.xml"))
        .contains("ALTER TABLE consultant ALTER COLUMN walk_through_enabled SET DEFAULT 1;");
  }

  private String read(String path) throws Exception {
    try (InputStream input = new ClassPathResource(path).getInputStream()) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
