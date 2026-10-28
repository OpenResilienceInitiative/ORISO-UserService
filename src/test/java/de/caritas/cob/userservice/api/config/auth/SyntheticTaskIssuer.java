package de.caritas.cob.userservice.api.config.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Local synthetic issuer exercises real HTTP and RSA/JWKS decoding, not native Keycloak policy. */
final class SyntheticTaskIssuer implements AutoCloseable {
  final AtomicInteger calls = new AtomicInteger();
  final HttpServer server;
  final KeyPair keys;
  String variant = "valid";
  String adminSubject = "native-account-admin-subject";
  Map<String, String> lastForm = Map.of();

  SyntheticTaskIssuer() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keys = generator.generateKeyPair();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/certs",
        exchange -> {
          var publicKey =
              new RSAKey.Builder((RSAPublicKey) keys.getPublic()).keyID("synthetic").build();
          reply(exchange, 200, new JWKSet(publicKey).toString());
        });
    server.createContext(
        "/token",
        exchange -> {
          calls.incrementAndGet();
          String form =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          lastForm =
              java.util.Arrays.stream(form.split("&"))
                  .map(pair -> pair.split("=", 2))
                  .collect(
                      Collectors.toMap(
                          pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                          pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
          if ("provider-error".equals(variant)) {
            reply(exchange, 500, "private synthetic reply");
            return;
          }
          try {
            String token = token(lastForm.get("client_id"));
            reply(
                exchange,
                200,
                "{\"access_token\":\""
                    + token
                    + "\",\"expires_in\":300,\"refresh_token\":\"discard-synthetic-refresh\"}");
          } catch (Exception failure) {
            reply(exchange, 500, "synthetic issuer failure");
          }
        });
    server.start();
  }

  String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  String issuer() {
    return base() + "/realms/synthetic";
  }

  private String token(String client) throws Exception {
    List<String> roles;
    List<String> audiences;
    String subject;
    switch (client) {
      case "backend-setup" -> {
        roles =
            List.of("config-wizard", "invitation-reservations", "consultant-import", "smtp-sync");
        audiences =
            List.of(
                "tenantservice",
                "agencyservice",
                "consultingtypeservice",
                "userservice",
                "oriso-task-commands");
        subject = "native-setup-subject";
      }
      case "backend-account-create" -> {
        roles = List.of("account-provisioning", "account-read");
        audiences = List.of("userservice", "oriso-account-lifecycle");
        subject = "native-account-create-subject";
      }
      case "backend-account-admin" -> {
        roles =
            List.of("account-maintenance", "account-read", "otp-config-admin", "session-exchange");
        audiences = List.of("userservice", "oriso-account-lifecycle", "oriso-otp");
        subject = adminSubject;
      }
      case "backend-mail" -> {
        roles =
            List.of("system-email-delivery", "notification-dispatch", "notifications-technical");
        audiences =
            List.of("tenantservice", "agencyservice", "consultingtypeservice", "userservice");
        subject = "native-mail-subject";
      }
      case "backend-runtime" -> {
        roles =
            List.of(
                "runtime-policy",
                "matrix-agency",
                "matrix-agency-provision",
                "appointment-sync",
                "appointment-participant-cleanup");
        audiences = List.of("tenantservice", "agencyservice", "appointmentservice");
        subject = "native-runtime-subject";
      }
      default -> throw new IllegalArgumentException("Unpinned synthetic client");
    }
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer())
            .subject(subject)
            .claim("azp", client)
            .audience(audiences)
            .claim("realm_access", Map.of("roles", roles))
            .issueTime(Date.from(Instant.now().minusSeconds(1)))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    switch (variant) {
      case "mixed-roles" ->
          claims.claim("realm_access", Map.of("roles", List.of("config-wizard", "smtp-sync")));
      case "extra-role" ->
          claims.claim(
              "realm_access",
              Map.of(
                  "roles",
                  List.of(
                      "config-wizard",
                      "invitation-reservations",
                      "consultant-import",
                      "realm-admin")));
      case "missing-role" ->
          claims.claim("realm_access", Map.of("roles", List.of("config-wizard")));
      case "create-extra-role" ->
          claims.claim(
              "realm_access",
              Map.of(
                  "roles", List.of("account-provisioning", "account-read", "account-maintenance")));
      case "create-missing-role" ->
          claims.claim("realm_access", Map.of("roles", List.of("account-read")));
      case "create-extra-audience" ->
          claims.audience(List.of("userservice", "oriso-account-lifecycle", "oriso-otp"));
      case "create-missing-audience" -> claims.audience(List.of("oriso-account-lifecycle"));
      case "wrong-audience" -> claims.audience(List.of("account"));
      case "extra-audience" ->
          claims.audience(
              List.of(
                  "tenantservice",
                  "agencyservice",
                  "consultingtypeservice",
                  "userservice",
                  "account"));
      case "mail-five-audiences" ->
          claims.audience(
              List.of(
                  "tenantservice",
                  "agencyservice",
                  "consultingtypeservice",
                  "userservice",
                  "oriso-task-commands"));
      case "mail-four-audiences" ->
          claims.audience(
              List.of("tenantservice", "agencyservice", "consultingtypeservice", "userservice"));
      case "mail-three-audiences" ->
          claims.audience(List.of("tenantservice", "agencyservice", "userservice"));
      case "mail-proof-audience" ->
          claims.audience(
              List.of(
                  "tenantservice",
                  "agencyservice",
                  "consultingtypeservice",
                  "userservice",
                  "oriso-task-commands",
                  "oriso-smtp-control"));
      case "admin-extra-role" ->
          claims.claim(
              "realm_access",
              Map.of(
                  "roles",
                  List.of(
                      "account-maintenance",
                      "account-read",
                      "otp-config-admin",
                      "session-exchange",
                      "realm-admin")));
      case "admin-missing-role" ->
          claims.claim(
              "realm_access",
              Map.of("roles", List.of("account-maintenance", "account-read", "otp-config-admin")));
      case "admin-extra-audience" ->
          claims.audience(
              List.of("userservice", "oriso-account-lifecycle", "oriso-otp", "realm-management"));
      case "admin-missing-audience" ->
          claims.audience(List.of("userservice", "oriso-account-lifecycle"));
      case "missing-issued-at" -> claims.issueTime(null);
      case "zero-issued-at" -> claims.issueTime(new Date(0));
      case "negative-issued-at" -> claims.issueTime(new Date(-1000));
      case "future-issued-at" -> claims.issueTime(Date.from(Instant.now().plusSeconds(120)));
      case "unordered-native-bounds" -> {
        var same = Date.from(Instant.now().plusSeconds(120));
        claims.issueTime(same).expirationTime(same);
      }
      case "wrong-subject" -> claims.subject("human-subject");
      case "wrong-client" -> claims.claim("azp", "app");
      case "wrong-issuer" -> claims.issuer(base() + "/realms/other");
      case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(600)));
      case "missing-expiry" -> claims.expirationTime(null);
      case "future" -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(600)));
      case "native-admin" ->
          claims.claim(
              "resource_access",
              Map.of("realm-management", Map.of("roles", List.of("manage-users"))));
    }
    var signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic").build(), claims.build());
    KeyPair signing = keys;
    if ("wrong-signature".equals(variant)) {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      signing = generator.generateKeyPair();
    }
    signed.sign(new RSASSASigner((RSAPrivateKey) signing.getPrivate()));
    return signed.serialize();
  }

  static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws java.io.IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
