package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CurrentDocumentationContractTest {

  private static final Path DOCUMENTATION = Path.of("documentation");
  private static final List<String> OBSOLETE_DIAGRAMS =
      List.of(
          "Enquiry-flowchart",
          "Get-list-of-team-sessions-flowchart",
          "Group-chat-flowchart",
          "Registration-login-enquiry",
          "RocketChat-Raumzuordnungen-Beratung-annehmen-und-zuordnen-Workflow",
          "RocketChat-Raumzuordnungen_Erstanfragen-Workflow",
          "RocketChatCredentialsProvider",
          "UserService-architecture",
          "User_Account_Deletion_Workflow",
          "User_Anonymous_Deactivation_Workflow");

  @Test
  void currentDocumentationMustNotRetainObsoleteTransportDiagrams() {
    for (var diagram : OBSOLETE_DIAGRAMS) {
      assertThat(DOCUMENTATION.resolve(diagram + ".graphml")).doesNotExist();
      assertThat(DOCUMENTATION.resolve(diagram + ".puml")).doesNotExist();
      assertThat(DOCUMENTATION.resolve(diagram + ".png")).doesNotExist();
    }
  }

  @Test
  void documentationIndexMustPointToCurrentMatrixContracts() throws IOException {
    assertThat(Files.readString(DOCUMENTATION.resolve("README.md")))
        .contains(
            "ADR-SECURITY-02-unified-crypto-boundary.md",
            "MATRIX_SYNC_OBSERVABILITY.md",
            "USER_SERVICE_REPLICA_SAFETY.md",
            "GROUP_CHAT_DEACTIVATION_REPLICA_SAFETY.md")
        .doesNotContain("RocketChatCredentialsProvider", "UserService-architecture.graphml");

    assertThat(Files.readString(DOCUMENTATION.resolve("USER_SERVICE_STABILITY.md")))
        .doesNotContain("historic architecture diagrams");
  }
}
