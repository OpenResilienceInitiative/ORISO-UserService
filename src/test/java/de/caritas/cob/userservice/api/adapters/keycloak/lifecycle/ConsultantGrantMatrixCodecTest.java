package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ConsultantGrantMatrixCodecTest {
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);

  @Test
  void exactSevenFieldDataUsesOnlyTheFixedDomainAndSoleKey() throws Exception {
    for (String phase : List.of("STARTED", "ACKNOWLEDGED", "NO_EFFECT")) {
      ObjectNode original = data(phase);
      String saved = codec.canonical(original);
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      String expected = HexFormat.of().formatHex(mac.doFinal(
          ("claimed-consultant-grant-matrix/v1\n" + saved).getBytes(StandardCharsets.UTF_8)));
      String actual = assertDoesNotThrow(() -> codec.claimedConsultantGrantMatrixDigest(original));
      assertEquals(expected, actual);
      assertEquals(saved, codec.canonical(original));
    }
  }

  @Test
  void wrongShapeTypesPhaseResultAndWholeCapsuleBoundsFailBeforeMac() {
    List<Consumer<ObjectNode>> invalid = List.of(
        node -> node.put("rawBearer", "synthetic"),
        node -> node.remove("result"),
        node -> node.put("representation", "SELF_PASSWORD"),
        node -> node.put("operationId", "AA000000-0000-4000-8000-000000000001"),
        node -> node.put("accountId", 1),
        node -> node.put("requestDigest", "A".repeat(64)),
        node -> node.put("localpart", "Owned"),
        node -> node.put("localpart", ""),
        node -> node.put("localpart", "foreign:server"),
        node -> node.put("localpart", "@foreign"),
        node -> node.put("localpart", "line\nbreak"),
        node -> node.put("phase", "APPLIED"),
        node -> node.put("phase", "ACKNOWLEDGED"),
        node -> node.put("result", "@owned-counsellor:fixture.invalid"),
        node -> { node.put("phase", "NO_EFFECT"); node.put("result", "@owned-counsellor:fixture.invalid"); },
        node -> { node.put("phase", "ACKNOWLEDGED"); node.put("result", "@foreign:fixture.invalid"); },
        node -> { node.put("phase", "ACKNOWLEDGED"); node.put("result", "@owned-counsellor:"); },
        node -> { node.put("phase", "ACKNOWLEDGED"); node.put("result", true); },
        node -> { node.put("phase", "ACKNOWLEDGED"); node.put("localpart", "a".repeat(220)); node.put("result", "@" + "a".repeat(220) + ":" + "b".repeat(280)); });
    for (var change : invalid) {
      ObjectNode value = data("STARTED");
      change.accept(value);
      assertThrows(IllegalArgumentException.class, () -> codec.claimedConsultantGrantMatrixDigest(value));
    }
    assertThrows(IllegalArgumentException.class,
        () -> codec.claimedConsultantGrantMatrixDigest(codec.read("[]", JsonNode.class)));
    assertThrows(IllegalArgumentException.class, () -> codec.claimedConsultantGrantMatrixDigest(null));
  }

  private ObjectNode data(String phase) {
    var value = new TreeMap<String, Object>();
    value.put("representation", "CLAIMED_CONSULTANT_GRANT_MATRIX_V1");
    value.put("operationId", "aa000000-0000-4000-8000-000000000001");
    value.put("accountId", "aa000000-0000-4000-8000-000000000002");
    value.put("requestDigest", "a".repeat(64));
    value.put("localpart", "owned-counsellor");
    value.put("phase", phase);
    value.put("result", phase.equals("ACKNOWLEDGED") ? "@owned-counsellor:fixture.invalid" : null);
    return (ObjectNode) codec.read(codec.canonical(value), JsonNode.class);
  }
}
