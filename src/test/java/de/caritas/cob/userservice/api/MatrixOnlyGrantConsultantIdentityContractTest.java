package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyGrantConsultantIdentityContractTest {

  @Test
  void grantingConsultantIdentityMustNotProvisionRocketChat() throws IOException {
    var source =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/admin/service/consultant/create/"
                    + "GrantConsultantIdentityService.java"));

    assertThat(source)
        .doesNotContain(
            "MessageClient",
            "RocketChat",
            "rocketChat",
            "resolveRocketChatUserId",
            "dummy-rc",
            ".rcUserId(");
  }
}
