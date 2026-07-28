package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyAnonymousEnquiryContractTest {

  private static final String[] FORBIDDEN_LEGACY_TERMS = {
    "RocketChat", "rocketChat", "rcUserId", "rcToken", "rcGroupId", "rocket-chat.enabled"
  };

  @Test
  void anonymousAccountAndEnquiryCreationMustBeMatrixOnly() throws IOException {
    for (var sourcePath :
        new String[] {
          "src/main/java/de/caritas/cob/userservice/api/conversation/service/user/anonymous/"
              + "AnonymousUserCreatorService.java",
          "src/main/java/de/caritas/cob/userservice/api/conversation/model/"
              + "AnonymousUserCredentials.java",
          "src/main/java/de/caritas/cob/userservice/api/conversation/facade/"
              + "CreateAnonymousEnquiryFacade.java"
        }) {
      assertThat(Files.readString(Path.of(sourcePath))).doesNotContain(FORBIDDEN_LEGACY_TERMS);
    }
  }

  @Test
  void anonymousEnquiryAndInviteRedeemResponsesMustNotExposeLegacyChatCredentials()
      throws IOException {
    var api = Files.readString(Path.of("api/conversationservice.yaml"));
    var schema = schemaBlock(api, "CreateAnonymousEnquiryResponseDTO");
    var inviteController =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/"
                    + "AgencyInviteLinkController.java"));

    assertThat(schema).doesNotContain(FORBIDDEN_LEGACY_TERMS);
    assertThat(inviteController).doesNotContain("rcUserId", "rcToken", "rcGroupId");
  }

  private String schemaBlock(String api, String schemaName) {
    var schemaStart = api.indexOf("\n    " + schemaName + ":");
    assertThat(schemaStart).isGreaterThanOrEqualTo(0);
    var schemaEnd = api.indexOf("\n    ", schemaStart + schemaName.length() + 6);
    return api.substring(schemaStart, schemaEnd < 0 ? api.length() : schemaEnd);
  }
}
