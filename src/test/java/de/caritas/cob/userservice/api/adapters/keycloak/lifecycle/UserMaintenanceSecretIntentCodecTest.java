package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Independent fixed bytes exercise data protection only, never native or caller authority. */
class UserMaintenanceSecretIntentCodecTest {
  @Test
  void integerTenantMatchesIndependentCanonicalAndCryptoVector() throws Exception {
    independentVector("ordinary_tenant_7");
  }

  @Test
  void explicitNullTenantMatchesIndependentCanonicalAndCryptoVector() throws Exception {
    independentVector("explicitly_permitted_null_tenant");
  }

  private void independentVector(String name) throws Exception {
    var reader = new AccountLifecycleCodec(new byte[32]);
    JsonNode fixture;
    try (var input = getClass().getResourceAsStream("/user-maintenance-secret-intent-v1.json")) {
      assertNotNull(input);
      fixture =
          reader.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
    }
    JsonNode vector = null;
    for (var candidate : fixture.path("crypto_vectors"))
      if (name.equals(candidate.path("name").textValue())) vector = candidate;
    assertNotNull(vector);
    byte[] nonce = HexFormat.of().parseHex(vector.path("nonce_hex").textValue());
    var fixed =
        new SecureRandom() {
          @Override
          public void nextBytes(byte[] bytes) {
            assertEquals(12, bytes.length);
            System.arraycopy(nonce, 0, bytes, 0, bytes.length);
          }
        };
    var codec =
        new AccountLifecycleCodec(
            HexFormat.of().parseHex(fixture.path("synthetic_root_key_hex").textValue()),
            Clock.systemUTC(),
            fixed);
    for (var canonical : fixture.path("canonical_vectors"))
      assertEquals(
          canonical.path("canonical_utf8").textValue(), codec.canonical(canonical.path("input")));
    var metadata = vector.path("metadata");
    assertTrue(metadata.has("tenantId"));
    assertEquals(vector.path("canonical_metadata_utf8").textValue(), codec.canonical(metadata));
    byte[] plain =
        vector.path("canonical_plaintext_utf8").textValue().getBytes(StandardCharsets.UTF_8);
    String expected = vector.path("canonical_envelope_utf8").textValue();
    assertEquals(expected, codec.sealSelfAppIntent(metadata, plain));
    assertArrayEquals(plain, codec.openSelfAppIntent(metadata, expected));
  }
}
