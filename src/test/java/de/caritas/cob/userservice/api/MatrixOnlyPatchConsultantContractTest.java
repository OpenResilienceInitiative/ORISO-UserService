package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyPatchConsultantContractTest {

  @Test
  void consultantPatchMustNotCallOrRollbackRocketChat() throws IOException {
    var source =
        Files.readString(
            Path.of("src/main/java/de/caritas/cob/userservice/api/PatchConsultantSaga.java"));
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
            "PatchConsultantSagaRollbackHandler",
            "encodedDisplayNameOf");
    assertThat(transactionalSteps)
        .doesNotContain(
            "UPDATE_ROCKET_CHAT_USER_DISPLAY_NAME",
            "ROLLBACK_UPDATE_ROCKET_CHAT_USER_DISPLAY_NAME");
    assertThat(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/"
                    + "PatchConsultantSagaRollbackHandler.java"))
        .doesNotExist();
  }
}
