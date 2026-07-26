package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyEnquiryAssignmentContractTest {

  @Test
  void registeredAndAnonymousEnquiriesMustBeAssignedThroughMatrix() throws Exception {
    var source =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/facade/assignsession/"
                    + "AssignEnquiryFacade.java"));

    assertThat(source)
        .contains("SessionRoomGateway")
        .doesNotContain(
            "RocketChat",
            "getRocketChatId",
            "getGroupId",
            "SessionAssignmentChatGateway",
            "UnauthorizedMembersProvider");
  }
}
