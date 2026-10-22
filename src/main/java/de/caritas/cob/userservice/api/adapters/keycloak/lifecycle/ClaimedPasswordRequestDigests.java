package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.port.out.ExistingSetupPasswordClaims;
import de.caritas.cob.userservice.api.service.auth.PasswordResetBinding;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Fixed request data only. Producer, SQL owner and native receiver independently authorize. */
final class ClaimedPasswordRequestDigests {
  private final AccountLifecycleCodec codec;

  ClaimedPasswordRequestDigests(AccountLifecycleCodec codec) {
    this.codec = codec;
  }

  String existingSetup(ExistingSetupPasswordClaims.Binding b, String password) {
    if (b == null
        || b.inviteId() <= 0
        || b.expiresAt() == null
        || b.role() == null
        || !Set.of("TENANT_ADMIN", "AGENCY_ADMIN", "COUNSELLOR").contains(b.role().name()))
      throw invalid();
    uuid(b.accountId());
    digest(b.tokenHash());
    tenant(b.tenantId());
    text(b.username(), 255);
    text(b.email(), 255);
    text(password, 4096);
    var binding = new TreeMap<String, Object>();
    binding.put("inviteId", b.inviteId());
    binding.put("accountId", b.accountId());
    binding.put("tokenHash", b.tokenHash());
    binding.put("expiresAt", b.expiresAt().toString());
    binding.put("role", b.role().name());
    binding.put("tenantId", b.tenantId());
    binding.put("username", b.username());
    binding.put("email", b.email());
    return request(
        "existing-setup-password-request/v1",
        "EXISTING_SETUP",
        "account-invites/setup",
        binding,
        password);
  }

  String reset(PasswordResetBinding b, String password) {
    if (b == null
        || b.workflowId() == null
        || b.expiresAt() == null
        || !Set.of("USER", "CONSULTANT", "PLATFORM_ADMIN", "TENANT_ADMIN", "AGENCY_ADMIN")
            .contains(b.accountKind())) throw invalid();
    uuid(b.subjectId());
    tenant(b.tenantId());
    text(b.username(), 255);
    text(b.accountVersion(), 512);
    digest(b.recipientDigest());
    text(password, 4096);
    var binding = new TreeMap<String, Object>();
    binding.put("workflowId", b.workflowId().toString());
    binding.put("subjectId", b.subjectId());
    binding.put("tenantId", b.tenantId());
    binding.put("username", b.username());
    binding.put("accountKind", b.accountKind());
    binding.put("accountVersion", b.accountVersion());
    binding.put("recipientDigest", b.recipientDigest());
    binding.put("expiresAt", b.expiresAt().toString());
    return request(
        "password-reset-request/v1", "PASSWORD_RESET", "password-reset/confirm", binding, password);
  }

  String recipient(String exactEmail) {
    text(exactEmail, 255);
    return codec.digest("maintenance-workflow-email/v1", exactEmail);
  }

  String localAccounts(JsonNode rows) {
    fields(rows, Set.of("user", "consultant", "admin"));
    if (rows.path("user").isNull()
        && rows.path("consultant").isNull()
        && rows.path("admin").isNull()) throw invalid();
    if (!rows.path("user").isNull()
        && (!rows.path("consultant").isNull() || !rows.path("admin").isNull())) throw invalid();
    String account = null;
    JsonNode tenantId = null;
    for (String kind : new String[] {"user", "consultant", "admin"}) {
      JsonNode row = rows.get(kind);
      if (row.isNull()) continue;
      var expected =
          new java.util.HashSet<>(
              Set.of("accountId", "tenantId", "username", "email", "createDate", "updateDate"));
      if ("admin".equals(kind)) expected.addAll(Set.of("type", "managedPaused"));
      else {
        expected.addAll(Set.of("deleteDate", "lifecycle"));
        if ("consultant".equals(kind))
          expected.addAll(Set.of("managedPaused", "passwordChangeRequired"));
      }
      fields(row, expected);
      String id = nodeText(row, "accountId", 36);
      uuid(id);
      JsonNode tenant = row.get("tenantId");
      if (!tenant.isNull()
          && (!tenant.isIntegralNumber() || !tenant.canConvertToLong() || tenant.longValue() < 0))
        throw invalid();
      if (account != null && (!account.equals(id) || !tenant.equals(tenantId))) throw invalid();
      account = id;
      tenantId = tenant;
      nodeText(row, "username", 255);
      nodeText(row, "email", 255);
      date(row.get("createDate"), false);
      date(row.get("updateDate"), false);
      if ("admin".equals(kind)) {
        if (!Set.of("SUPER", "TENANT", "AGENCY").contains(nodeText(row, "type", 16)))
          throw invalid();
      } else {
        date(row.get("deleteDate"), true);
        JsonNode lifecycle = row.get("lifecycle");
        if (!lifecycle.isNull()) nodeText(row, "lifecycle", 32);
      }
      if (!"user".equals(kind) && !row.path("managedPaused").isBoolean()) throw invalid();
      if ("consultant".equals(kind) && !row.path("passwordChangeRequired").isBoolean())
        throw invalid();
    }
    return codec.digest("claimed-password-local-account/v1", rows);
  }

  private static void fields(JsonNode value, Set<String> expected) {
    if (value == null || !value.isObject() || value.size() != expected.size()) throw invalid();
    var actual = new java.util.HashSet<String>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw invalid();
  }

  private static String nodeText(JsonNode value, String field, int max) {
    var node = value.get(field);
    if (node == null || !node.isTextual()) throw invalid();
    text(node.textValue(), max);
    return node.textValue();
  }

  private static void date(JsonNode value, boolean nullable) {
    if (value != null && value.isNull() && nullable) return;
    if (value == null || !value.isTextual()) throw invalid();
    try {
      if (!java.time.LocalDateTime.parse(value.textValue()).toString().equals(value.textValue()))
        throw invalid();
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private String request(
      String domain, String kind, String route, Object binding, String password) {
    return codec.digest(
        domain,
        Map.of(
            "v",
            1,
            "workflowKind",
            kind,
            "method",
            "POST",
            "route",
            route,
            "binding",
            binding,
            "password",
            password,
            "temporary",
            false));
  }

  private static void tenant(Long value) {
    if (value != null && value < 0) throw invalid();
  }

  private static void uuid(String value) {
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw invalid();
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private static void digest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid();
  }

  private static void text(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.codePoints().anyMatch(c -> c < 32 || c == 127)) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid claimed password binding");
  }
}
