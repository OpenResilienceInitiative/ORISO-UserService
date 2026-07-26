package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyRuntimeConfigurationContractTest {

  private static final Path RESOURCES = Path.of("src/main/resources");

  @Test
  void releaseContainerBaseMustBePinnedByDigest() throws IOException {
    var fromLines =
        Files.readAllLines(Path.of("Dockerfile")).stream()
            .filter(line -> line.startsWith("FROM "))
            .toList();

    assertThat(fromLines).isNotEmpty().allMatch(line -> line.matches(".*@sha256:[a-f0-9]{64}.*"));
  }

  @Test
  void runtimeConfigurationMustNotExposeRocketChatPropertiesOrCaches() throws IOException {
    var configurationValidator =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/config/ConfigurationValidator.java"));
    var cacheManager =
        Files.readString(
            Path.of("src/main/java/de/caritas/cob/userservice/api/config/CacheManagerConfig.java"));

    assertThat(configurationValidator).doesNotContain("rocket", "Rocket");
    assertThat(cacheManager).doesNotContain("rocket", "Rocket");

    try (var propertyFiles = Files.list(RESOURCES)) {
      for (var propertyFile :
          propertyFiles
              .filter(path -> path.getFileName().toString().endsWith(".properties"))
              .toList()) {
        assertThat(Files.readString(propertyFile))
            .as(propertyFile.toString())
            .doesNotContain("rocket-chat.", "rocket.technical.", "rocket.systemuser.")
            .doesNotContain("cache.rocketchat.");
      }
    }
  }
}
