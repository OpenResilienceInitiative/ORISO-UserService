package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyRuntimeConfigurationContractTest {

  private static final Path RESOURCES = Path.of("src/main/resources");
  private static final Path APPLICATION =
      Path.of("src/main/java/de/caritas/cob/userservice/api/UserServiceApplication.java");
  private static final Path LOCAL_RUN_EXAMPLE = Path.of("run-local-remote-db.sh.example");

  @Test
  void releaseWorkflowMustPublishImmutableMultiPlatformImagesWithEvidence() throws IOException {
    final var buildAction =
        Files.readString(Path.of(".github/actions/docker-build-push/action.yml"));
    final var mainWorkflow = Files.readString(Path.of(".github/workflows/ci-main.yml"));

    assertThat(buildAction)
        .contains("linux/amd64,linux/arm64")
        .contains("provenance: mode=max")
        .contains("sbom: true")
        .contains("value: ${{ steps.build.outputs.digest }}")
        .contains("aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25");

    // The vulnerability scan has to sit ahead of the publish. Scanning after
    // `push: true` can only redden the run; the image is already in GHCR and the
    // deploy scripts resolve a tag to a digest without reading workflow results
    // (OpenResilienceInitiative/ORISO-Docs#88).
    final var scanIndex = buildAction.indexOf("aquasecurity/trivy-action@");
    final var publishIndex = buildAction.indexOf("push: ${{ inputs.push_to_ghcr }}");
    assertThat(scanIndex).isGreaterThan(-1);
    assertThat(publishIndex).isGreaterThan(-1);
    assertThat(scanIndex)
        .as("Trivy must run before the image is pushed to the registry")
        .isLessThan(publishIndex);

    assertThat(mainWorkflow)
        .contains("id-token: write")
        .contains("attestations: write")
        .contains("actions/attest@f7c74d28b9d84cb8768d0b8ca14a4bac6ef463e6")
        .contains("subject-digest: ${{ steps.image.outputs.digest }}");
  }

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
    assertThat(Files.readString(LOCAL_RUN_EXAMPLE))
        .as(LOCAL_RUN_EXAMPLE.toString())
        .doesNotContain(
            "ROCKET_CHAT_BASE_URL",
            "ROCKET_CHAT_MONGO_URL",
            "ROCKET_TECHNICAL_USERNAME",
            "ROCKET_TECHNICAL_PASSWORD");

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

  @Test
  void matrixOnlyRuntimeMustNotShipTheRetiredMongoDbStack() throws IOException {
    assertThat(Files.readString(Path.of("pom.xml")))
        .doesNotContain("spring-boot-starter-data-mongodb");
    assertThat(Files.readString(APPLICATION))
        .doesNotContain("MongoAutoConfiguration", "DataMongoAutoConfiguration");
    assertThat(Files.readString(LOCAL_RUN_EXAMPLE))
        .as(LOCAL_RUN_EXAMPLE.toString())
        .doesNotContain("SPRING_DATA_MONGODB_URI", "mongodb://");

    try (var propertyFiles = Files.list(RESOURCES)) {
      for (var propertyFile :
          propertyFiles
              .filter(path -> path.getFileName().toString().endsWith(".properties"))
              .toList()) {
        assertThat(Files.readString(propertyFile))
            .as(propertyFile.toString())
            .doesNotContain(
                "spring.data.mongodb",
                "SPRING_DATA_MONGODB_URI",
                "mongodb://",
                "MongoAutoConfiguration",
                "MongoDataAutoConfiguration",
                "MongoRepositoriesAutoConfiguration");
      }
    }
  }
}
