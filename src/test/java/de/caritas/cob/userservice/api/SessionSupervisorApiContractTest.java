package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SessionSupervisorApiContractTest {

  @Test
  void activeSupervisorsEndpointDocumentsTheControllerResponse() throws IOException {
    Map<String, Object> specification =
        new Yaml().load(Files.readString(Path.of("api/userservice.yaml")));
    Map<String, Object> paths = map(specification.get("paths"));
    Map<String, Object> operation =
        map(map(paths.get("/users/sessions/{sessionId}/supervisors")).get("get"));

    assertThat(operation.get("operationId")).isEqualTo("getSessionSupervisors");
    assertThat(operation.get("tags")).isEqualTo(List.of("session-supervisor-controller"));
    assertThat(operation.get("x-internal")).isEqualTo(true);
    assertThat(
            map(operation.get("responses")).keySet().containsAll(List.of(200, 401, 403, 404, 500)))
        .isTrue();
    assertThat(operation.get("security")).isEqualTo(List.of(Map.of("Bearer", List.of())));

    Map<String, Object> okResponse = map(map(operation.get("responses")).get(200));
    Map<String, Object> responseSchema =
        map(map(map(okResponse.get("content")).get("application/json")).get("schema"));
    assertThat(responseSchema.get("type")).isEqualTo("array");
    assertThat(map(responseSchema.get("items")).get("$ref"))
        .isEqualTo("#/components/schemas/SessionSupervisorResponseDTO");

    Map<String, Object> schemas = map(map(specification.get("components")).get("schemas"));
    Map<String, Object> supervision = map(schemas.get("SessionSupervisionDTO"));
    Map<String, Object> supervisionProperties = map(supervision.get("properties"));
    assertThat(map(supervisionProperties.get("sideRoomId"))).containsEntry("nullable", true);
    assertThat(((List<?>) supervision.get("required")).contains("sideRoomId")).isFalse();

    Map<String, Object> response = map(schemas.get("SessionSupervisorResponseDTO"));
    Map<String, Object> properties = map(response.get("properties"));
    assertThat(properties.keySet())
        .containsExactlyInAnyOrder(
            "id",
            "sessionId",
            "supervisorConsultantId",
            "supervisorUsername",
            "supervisorMatrixUserId",
            "addedByConsultantId",
            "addedDate",
            "matrixRoomId",
            "notes",
            "reasonCode",
            "justification",
            "consent");
    assertThat(map(properties.get("supervisorMatrixUserId"))).containsEntry("nullable", true);
    assertThat(map(properties.get("addedDate"))).containsEntry("format", "date-time");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }
}
