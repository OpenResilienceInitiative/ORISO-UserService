package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlySessionArchiveContractTest {

  @Test
  void sessionArchivingMustNotDependOnRocketChat() throws Exception {
    var source =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/service/archive/"
                    + "SessionArchiveService.java"));

    assertThat(source).doesNotContain("RocketChat", "getGroupId", "setRoomReadOnly");
  }
}
