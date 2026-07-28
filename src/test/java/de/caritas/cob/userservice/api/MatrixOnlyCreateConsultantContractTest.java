package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyCreateConsultantContractTest {

  @Test
  void consultantCreationMustNotProvisionOrAssignRocketChat() throws IOException {
    var source =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/admin/service/consultant/create/"
                    + "CreateConsultantSaga.java"));
    var transactionalSteps =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/admin/service/consultant/"
                    + "TransactionalStep.java"));

    assertThat(source)
        .doesNotContain(
            "MessageClient",
            "RocketChat",
            "rocketChat",
            "SessionService",
            "createRocketChatUserOrRollback",
            "tryAssignConsultantToExistingSessions");
    assertThat(transactionalSteps).doesNotContain("CREATE_ACCOUNT_IN_ROCKETCHAT");
  }
}
