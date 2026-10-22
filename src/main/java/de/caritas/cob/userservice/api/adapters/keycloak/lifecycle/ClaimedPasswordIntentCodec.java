package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Function;

/** Default-closed scaffold for the distinct fixed causal intent schema. */
final class ClaimedPasswordIntentCodec {
  ClaimedPasswordIntentCodec(AccountLifecycleCodec codec, Function<String, byte[]> mac) {}

  String seal(JsonNode metadata, byte[] command) {
    throw invalid();
  }

  byte[] open(JsonNode metadata, String envelope) {
    throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Unavailable claimed password intent");
  }
}
