package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ConversationTypeApiContractTest {

  private static final String CONVERSATION_TYPE_REFERENCE = "#/components/schemas/ConversationType";

  @Test
  void responseConversationTypesShouldReferenceTheCanonicalEnum() throws IOException {
    Map<String, Object> specification =
        new Yaml().load(Files.readString(Path.of("api/userservice.yaml")));
    Map<String, Object> schemas = map(map(specification.get("components")).get("schemas"));

    assertThat(map(schemas.get("ConversationType")))
        .containsEntry("type", "string")
        .containsEntry(
            "enum", List.of("AGENCY_COUNSELLING", "LIVE_CHAT", "INTERNAL_GROUP", "SELF_HELP"));

    assertNullableConversationTypeReference(schemas, "SessionDTO");
    assertNullableConversationTypeReference(schemas, "UserChatDTO");
  }

  private void assertNullableConversationTypeReference(
      Map<String, Object> schemas, String responseSchema) {
    Map<String, Object> properties = map(map(schemas.get(responseSchema)).get("properties"));
    Map<String, Object> conversationType = map(properties.get("conversationType"));
    List<Map<String, Object>> allOf = listOfMaps(conversationType.get("allOf"));

    assertThat(allOf)
        .singleElement()
        .satisfies(ref -> assertThat(ref.get("$ref")).isEqualTo(CONVERSATION_TYPE_REFERENCE));
    assertThat(conversationType).containsEntry("nullable", true);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> listOfMaps(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
