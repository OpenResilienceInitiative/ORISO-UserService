package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatrixOnlyEnquiryCreationContractTest {

  private static final List<String> MATRIX_ONLY_FILES =
      List.of(
          "src/main/java/de/caritas/cob/userservice/api/facade/CreateEnquiryMessageFacade.java",
          "src/main/java/de/caritas/cob/userservice/api/facade/CreateNewSessionFacade.java",
          "src/main/java/de/caritas/cob/userservice/api/facade/CreateUserChatRelationFacade.java",
          "src/main/java/de/caritas/cob/userservice/api/model/EnquiryData.java",
          "src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/"
              + "UserRegistrationControllerDelegate.java",
          "src/main/java/de/caritas/cob/userservice/api/conversation/service/"
              + "AnonymousConversationCreatorService.java");

  @Test
  void enquiryCreationMustNotDependOnTheRocketChatAdapterOrFeatureFlag() throws IOException {
    for (String file : MATRIX_ONLY_FILES) {
      String source = Files.readString(Path.of(file));

      assertThat(source)
          .as(file)
          .doesNotContain(
              "api.adapters.rocketchat",
              "api.exception.rocketchat",
              "rocket-chat.enabled",
              "RocketChatService",
              "RocketChatCredentials");
    }
  }
}
