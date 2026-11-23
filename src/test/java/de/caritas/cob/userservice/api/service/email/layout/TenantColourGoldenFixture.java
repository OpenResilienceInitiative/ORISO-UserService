package de.caritas.cob.userservice.api.service.email.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code email/tenant-colour-golden.json}, the copy of the golden fixture owned by
 * ORISO-Frontend ({@code src/utils/theme/__fixtures__/tenant-colour-golden.json}). Never edit the
 * copy: refresh it with {@code scripts/sync-tenant-colour-golden.sh}.
 */
final class TenantColourGoldenFixture {

  static final String RESOURCE = "/email/tenant-colour-golden.json";

  record Case(
      String seed,
      boolean accepted,
      String primary,
      String onPrimary,
      Boolean labelIsWhite,
      String primaryHover) {}

  private TenantColourGoldenFixture() {}

  static List<Case> cases() {
    try (InputStream in = TenantColourGoldenFixture.class.getResourceAsStream(RESOURCE)) {
      JsonNode root = new ObjectMapper().readTree(in);
      List<Case> cases = new ArrayList<>();
      for (JsonNode node : root.get("cases")) {
        cases.add(
            new Case(
                text(node, "seed"),
                node.get("accepted").asBoolean(),
                text(node, "primary"),
                text(node, "onPrimary"),
                node.get("labelIsWhite").isNull() ? null : node.get("labelIsWhite").asBoolean(),
                text(node, "primaryHover")));
      }
      return cases;
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + RESOURCE, exception);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
