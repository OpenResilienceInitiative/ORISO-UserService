package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The same JSON vectors are consumable by the receiver's ordinary wire decoder. */
class AccountLifecycleScopeBoundsTest {
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(new byte[32]);

  static Stream<Arguments> sharedVectors() throws Exception {
    try (var input =
        AccountLifecycleScopeBoundsTest.class.getResourceAsStream(
            "/identity-lifecycle/ordinary-scope-bounds-v1.json")) {
      var vectors = new ObjectMapper().readTree(input);
      assertEquals("oriso-ordinary-scope-bounds-v1", vectors.path("schema").asText());
      return StreamSupport.stream(vectors.path("cases").spliterator(), false)
          .map(
              vector ->
                  Arguments.of(
                      vector.path("field").asText(),
                      vector.path("length").asInt(),
                      vector.path("accepted").asBoolean(),
                      vector.path("scope")));
    }
  }

  @ParameterizedTest(name = "{0} length {1}, accepted={2}")
  @MethodSource("sharedVectors")
  void strictPublicScopeDecoderPreservesSharedOrdinaryFieldBounds(
      String field, int length, boolean accepted, JsonNode wireScope) {
    var location = "initialGroup".equals(field) ? wireScope : wireScope.path("origin");
    assertEquals(length, location.path(field).asText().length());
    if (accepted) {
      var decoded =
          assertDoesNotThrow(
              () -> codec.read(wireScope.toString(), AccountLifecycleDtos.CreationScope.class));
      assertEquals(
          wireScope,
          assertDoesNotThrow(() -> new ObjectMapper().readTree(codec.canonical(decoded))));
    } else
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.read(wireScope.toString(), AccountLifecycleDtos.CreationScope.class));
  }
}
