package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import java.util.HashSet;
import java.util.Set;

/** Closed native read decoders; decoded data never grants target authority. */
final class SelfOtpReadWire {
  private SelfOtpReadWire() {}

  static JsonNode projection(AccountLifecycleCodec codec, String reply, String account, Long tenant) {
    if (reply == null || reply.length() > 65536) throw unavailable();
    var value = codec.read(reply, JsonNode.class);
    if (!names(value).equals(Set.of(
            "id", "username", "email", "firstName", "lastName", "enabled", "emailVerified",
            "tenantId", "preferredLanguage", "effectiveRoles", "passwordChangeRequired", "state"))
        || !account.equals(value.path("id").textValue())
        || !(tenant == null ? value.path("tenantId").isNull()
            : tenant.toString().equals(value.path("tenantId").textValue()))
        || !value.path("enabled").isBoolean() || !value.path("enabled").booleanValue()
        || !text(value.path("username").textValue(), 255)
        || !value.path("state").isTextual()
        || !value.path("state").textValue().matches("[a-f0-9]{64}")) throw unavailable();
    return value;
  }

  static IdentityOtpCredential credential(AccountLifecycleCodec codec, String reply) {
    if (reply == null || reply.length() > 262144) throw unavailable();
    var value = codec.read(reply, JsonNode.class);
    if (!Set.of("otpSetup", "otpSecret", "otpSecretQrCode", "otpType").containsAll(names(value))
        || !value.path("otpSetup").isBoolean()) throw unavailable();
    boolean setup = value.path("otpSetup").booleanValue();
    String secret = nullableText(value.get("otpSecret"), 4096);
    String qr = nullableText(value.get("otpSecretQrCode"), 200000);
    String kind = nullableText(value.get("otpType"), 8);
    if ((secret == null) != (qr == null)
        || (!setup && (secret == null || kind != null))
        || (setup && !Set.of("APP", "EMAIL").contains(kind == null ? "" : kind))
        || (setup && "APP".equals(kind) && secret != null)) throw unavailable();
    return new IdentityOtpCredential(setup, secret, qr,
        kind == null ? null : IdentityOtpType.valueOf(kind));
  }

  private static Set<String> names(JsonNode value) {
    if (!value.isObject()) throw unavailable();
    var names = new HashSet<String>();
    value.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static String nullableText(JsonNode value, int maximum) {
    if (value == null || value.isNull()) return null;
    if (!value.isTextual() || !text(value.textValue(), maximum)) throw unavailable();
    return value.textValue();
  }

  private static boolean text(String value, int maximum) {
    return value != null && !value.isBlank() && value.length() <= maximum
        && value.chars().noneMatch(c -> c < 32 || c == 127);
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Scoped OTP read unavailable");
  }
}
