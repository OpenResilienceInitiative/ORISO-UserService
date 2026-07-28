package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LegacyAskerImportRemovedContractTest {

  @Test
  void rocketChatBackedAskerImportMustStayDeleted() throws Exception {
    var api = Files.readString(Path.of("api/userservice.yaml"));
    var applicationProperties =
        Files.readString(Path.of("src/main/resources/application.properties"));

    assertThat(api)
        .doesNotContain(
            "/users/askers/import",
            "/users/askersWithoutSession/import",
            "operationId: importAskers");
    assertThat(applicationProperties).doesNotContain("asker.import.");
    assertThat(
            Path.of("src/main/java/de/caritas/cob/userservice/api/service/AskerImportService.java"))
        .doesNotExist();
  }
}
