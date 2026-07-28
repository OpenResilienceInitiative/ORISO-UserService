package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyConsultantRemovalContractTest {

  private static final Path SERVICE_PACKAGE =
      Path.of("src/main/java/de/caritas/cob/userservice/api/admin/service/agency");

  @Test
  void consultantRoomRemovalMustUseTransportNeutralNames() throws IOException {
    var adminService =
        Files.readString(SERVICE_PACKAGE.resolve("ConsultantAgencyAdminService.java"));

    assertThat(adminService).doesNotContain("RocketChat", "rocketChat");
    assertThat(SERVICE_PACKAGE.resolve("RemoveConsultantFromRocketChatService.java"))
        .doesNotExist();
    assertThat(SERVICE_PACKAGE.resolve("RemoveConsultantFromSessionRoomsService.java"))
        .isRegularFile();
  }
}
