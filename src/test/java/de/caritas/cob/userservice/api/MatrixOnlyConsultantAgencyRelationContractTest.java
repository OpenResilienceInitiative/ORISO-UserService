package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyConsultantAgencyRelationContractTest {

  @Test
  void consultantAgencyAssignmentMustNotBranchToRocketChat() throws IOException {
    var source =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/admin/service/consultant/create/"
                    + "agencyrelation/ConsultantAgencyRelationCreatorService.java"));

    assertThat(source)
        .doesNotContain(
            "rocket-chat.enabled",
            "RocketChatAsyncHelper",
            "addConsultantToSessions",
            "rocketChatEnabled");
    assertThat(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/admin/service/consultant/create/"
                    + "agencyrelation/RocketChatAsyncHelper.java"))
        .doesNotExist();
  }
}
