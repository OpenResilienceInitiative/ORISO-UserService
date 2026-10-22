package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedAccounts.PendingOwnedAccount;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Private causal pending-recipient OTP workflow. No public tuple or ciphertext grants authority.
 */
public final class ProtectedOtpBootstrap {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> METADATA =
      Set.of(
          "v",
          "realmId",
          "approvalId",
          "workflowId",
          "inviteId",
          "authorizationRevision",
          "claimId",
          "journalVersion",
          "attemptId",
          "executionId",
          "accountId",
          "operationId",
          "command",
          "ownerClient",
          "ownerSubject",
          "requestDigest",
          "originDigest",
          "authorityValidUntil",
          "representation");
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final AccountLifecycleCodec codec;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;
  private final RestTemplate http;
  private final HttpServletRequest request;
  private final Clock clock;
  private final URI server;
  private final String realmName;
  private final String realmId;

  public ProtectedOtpBootstrap(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      AccountLifecycleCodec codec,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      RestTemplate http,
      HttpServletRequest request,
      Clock clock,
      String authServer,
      String realmName,
      String realmId) {
    ProtectedInvitationSqlOwner.require(jdbc, manager);
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.codec = Objects.requireNonNull(codec);
    this.actors = Objects.requireNonNull(actors);
    this.broker = Objects.requireNonNull(broker);
    this.http = Objects.requireNonNull(http);
    this.request = Objects.requireNonNull(request);
    this.clock = Objects.requireNonNull(clock);
    if (realmName == null || realmName.isBlank() || realmId == null || realmId.isBlank())
      throw unavailable();
    this.realmName = realmName;
    this.realmId = realmId;
    try {
      server = URI.create(authServer);
      if (!Set.of("http", "https").contains(server.getScheme())
          || server.getHost() == null
          || server.getRawUserInfo() != null
          || server.getRawQuery() != null
          || server.getRawFragment() != null) throw unavailable();
    } catch (RuntimeException rejected) {
      throw unavailable();
    }
  }

  /** Freeze the exact private request before the actual original OTP GET, outside owning SQL. */
  public PreparedBootstrap prepare(AcceptanceClaim claim, PendingOwnedAccount pending) {
    outsideSql();
    if (claim == null
        || pending == null
        || pending.claim() == null
        || pending.created() == null
        || !claim.claimId().equals(pending.claim().claimId())
        || !claim.attemptId().equals(pending.created().attemptId())
        || !claim.executionId().equals(pending.created().executionId())
        || !claim.frozen().approvalId().equals(pending.created().approvalId())
        || !realmId.equals(pending.realmId())
        || !realmName.equals(pending.realmName())) throw unavailable();
    var actor = actors.require(TaskPurpose.OTP);
    var machine = broker.issueCheckedMaintenance(TaskPurpose.OTP);
    if (actor.actor() != TaskActor.ACCOUNT_ADMIN
        || machine.actor() != TaskActor.ACCOUNT_ADMIN
        || !actor.clientId().equals(machine.clientId())
        || !actor.serviceSubject().equals(machine.subject())) throw unavailable();
    String requestDigest = publicIntent(claim);
    long deadline =
        Math.min(
            clock.instant().getEpochSecond() + 600,
            Math.min(
                machine.validatedJwtExpiresAt(),
                Instant.parse(scope(claim).path("validUntil").asText()).getEpochSecond()));
    var frozen =
        transactions.execute(
            tx -> {
              checkClaim(claim);
              var existing = row(claim);
              if (existing != null) {
                var metadata = checkedMetadata(claim, existing.metadata());
                if (!requestDigest.equals(metadata.path("requestDigest").asText())
                    || !actor.clientId().equals(metadata.path("ownerClient").asText())
                    || !actor.serviceSubject().equals(metadata.path("ownerSubject").asText())
                    || !pending.created().accountId().equals(metadata.path("accountId").asText()))
                  throw unavailable();
                return existing;
              }
              if (deadline <= clock.instant().getEpochSecond()) throw unavailable();
              var created = pending.created();
              var target = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
              target.put("accountId", created.accountId());
              target.put("tenantId", Long.toString(created.tenantId()));
              var origin = target.putObject("bootstrap");
              origin.put("v", 1);
              origin.put("kind", "PROTECTED_ACCEPTANCE");
              origin.put("approvalId", created.approvalId().toString());
              origin.put("scopeDigest", claim.approval().scopeDigest());
              origin.put("authorizationRevision", created.authorizationRevision());
              var acceptance = origin.putObject("acceptanceClaim");
              acceptance.put("workflowId", created.workflowId().toString());
              acceptance.put("inviteId", created.inviteId());
              acceptance.put("authorizationRevision", created.authorizationRevision());
              acceptance.put("claimId", claim.claimId().toString());
              acceptance.put("journalVersion", claim.journalVersion());
              origin.put("attemptId", created.attemptId().toString());
              var execution = origin.putObject("execution");
              execution.put("executionId", created.executionId().toString());
              execution.put("generation", 0);
              origin.put("creationFingerprint", created.creationFingerprint());
              origin.put("creationProof", created.creationProof());
              origin.put("authorityValidUntil", deadline);
              var command = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
              command.set("target", target);
              command.put("username", pending.username());
              command.putNull("otp");
              var metadata = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
              metadata.put("v", 1);
              metadata.put("realmId", realmId);
              metadata.put("approvalId", created.approvalId().toString());
              metadata.put("workflowId", created.workflowId().toString());
              metadata.put("inviteId", created.inviteId());
              metadata.put("authorizationRevision", created.authorizationRevision());
              metadata.put("claimId", claim.claimId().toString());
              metadata.put("journalVersion", claim.journalVersion());
              metadata.put("attemptId", created.attemptId().toString());
              metadata.put("executionId", created.executionId().toString());
              metadata.put("accountId", created.accountId());
              metadata.put("operationId", UUID.randomUUID().toString());
              metadata.put("command", "otp.bootstrap-read");
              metadata.put("ownerClient", actor.clientId());
              metadata.put("ownerSubject", actor.serviceSubject());
              metadata.put("requestDigest", requestDigest);
              metadata.put("originDigest", codec.digest("otp-bootstrap-origin/v1", target));
              metadata.put("authorityValidUntil", deadline);
              metadata.put("representation", "REQUEST");
              checkedMetadata(claim, codec.canonical(metadata));
              var record =
                  new Row(
                      codec.canonical(metadata),
                      seal(metadata, codec.canonical(command)),
                      null,
                      "PREPARED");
              var now = Timestamp.from(clock.instant());
              jdbc.update(
                  "INSERT INTO protected_otp_bootstrap(invite_id,claim_id,metadata_json,request_envelope,result_envelope,state,created_at,updated_at) VALUES(?,?,?,?,NULL,'PREPARED',?,?)",
                  invite(claim),
                  claim.claimId().toString(),
                  record.metadata(),
                  record.request(),
                  now,
                  now);
              return record;
            });
    if (frozen == null) throw unavailable();
    var metadata = checkedMetadata(claim, frozen.metadata());
    if ("FROZEN".equals(frozen.state()))
      return prepared(claim, metadata, open(resultMetadata(metadata), frozen.result()));
    if (!"PREPARED".equals(frozen.state())
        || frozen.result() != null
        || metadata.path("authorityValidUntil").asLong() <= clock.instant().getEpochSecond())
      throw unavailable();
    var command = codec.read(open(metadata, frozen.request()), JsonNode.class);
    var headers = new HttpHeaders();
    headers.setBearerAuth(machine.accessToken());
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    headers.set("X-ORISO-Account-Target", codec.canonical(command.path("target")));
    headers.set(
        "X-ORISO-Task-Context",
        codec.protectedBootstrapReadProof(
            realmName,
            realmId,
            metadata.path("accountId").asText(),
            actor,
            command,
            metadata.path("authorityValidUntil").asLong()));
    byte[] response;
    try {
      var reply =
          http.exchange(
              UriComponentsBuilder.fromUri(server)
                  .pathSegment(
                      "realms",
                      realmName,
                      "otp-config",
                      "fetch-otp-setup-info",
                      command.path("username").asText())
                  .build()
                  .encode()
                  .toUri(),
              HttpMethod.GET,
              new HttpEntity<>(headers),
              byte[].class);
      if (reply.getStatusCode().value() != 200
          || reply.getBody() == null
          || reply.getBody().length > 262144) throw unavailable();
      response = reply.getBody();
    } catch (RestClientException rejected) {
      throw unavailable();
    }
    return prepared(claim, metadata, codec.utf8(response));
  }

  /** The result and root WAITING_OTP/local effects share this actual transaction. No RPC. */
  public IdentityOtpCredential stagePrepared(AcceptanceClaim claim, PreparedBootstrap prepared) {
    owningSql();
    checkClaim(claim);
    var saved = row(claim);
    if (prepared == null
        || saved == null
        || !claim.claimId().toString().equals(prepared.claimId)
        || !saved.metadata().equals(prepared.metadata)) throw unavailable();
    var metadata = checkedMetadata(claim, saved.metadata());
    var checked = prepared(claim, metadata, prepared.result);
    if ("FROZEN".equals(saved.state())) {
      if (!Objects.equals(prepared.result, open(resultMetadata(metadata), saved.result())))
        throw unavailable();
    } else {
      if (!"PREPARED".equals(saved.state()) || saved.result() != null) throw unavailable();
      if (jdbc.update(
              "UPDATE protected_otp_bootstrap SET result_envelope=?,state='FROZEN',updated_at=? WHERE invite_id=? AND claim_id=? AND state='PREPARED' AND result_envelope IS NULL",
              seal(resultMetadata(metadata), checked.result),
              Timestamp.from(clock.instant()),
              invite(claim),
              claim.claimId().toString())
          != 1) throw unavailable();
    }
    return credential(checked.result);
  }

  /** Only a known committed current same-claim result may reproduce register setup material. */
  public IdentityOtpCredential currentSetup(AcceptanceClaim claim) {
    outsideSql();
    return transactions.execute(
        tx -> {
          checkClaim(claim);
          var saved = row(claim);
          if (saved == null || !"FROZEN".equals(saved.state()) || saved.result() == null)
            throw unavailable();
          var metadata = checkedMetadata(claim, saved.metadata());
          return credential(
              prepared(claim, metadata, open(resultMetadata(metadata), saved.result())).result);
        });
  }

  private PreparedBootstrap prepared(AcceptanceClaim claim, JsonNode metadata, String result) {
    if (metadata.path("authorityValidUntil").asLong() <= clock.instant().getEpochSecond())
      throw unavailable();
    var response = codec.read(result, JsonNode.class);
    fields(response, Set.of("otpInfo", "bootstrapReceipt", "authorityValidUntil"));
    if (!response.path("authorityValidUntil").isIntegralNumber()
        || response.path("authorityValidUntil").asLong()
            != metadata.path("authorityValidUntil").asLong()) throw unavailable();
    var expected = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    expected.put("v", 1);
    for (String name :
        List.of(
            "realmId",
            "approvalId",
            "attemptId",
            "accountId",
            "claimId",
            "executionId",
            "ownerClient",
            "ownerSubject",
            "originDigest",
            "authorityValidUntil")) expected.set(name, metadata.path(name));
    expected.set("bootstrapIncarnation", metadata.path("executionId"));
    expected.put("state", "PREPARED");
    codec.checkProtectedBootstrapPrepareReceipt(
        response.path("bootstrapReceipt").asText(), expected);
    credential(result);
    return new PreparedBootstrap(
        claim.claimId().toString(), codec.canonical(metadata), codec.canonical(response));
  }

  private IdentityOtpCredential credential(String response) {
    var result = codec.read(response, JsonNode.class);
    var setup = SelfOtpReadWire.credential(codec, codec.canonical(result.path("otpInfo")));
    if (!Boolean.FALSE.equals(setup.setup())) throw unavailable();
    return setup;
  }

  private void checkClaim(AcceptanceClaim claim) {
    owningSql();
    if (claim == null
        || claim.frozen() == null
        || claim.approval() == null
        || claim.journalVersion() != 1
        || !realmId.equals(claim.frozen().issuerRealm())) throw unavailable();
    var scope = scope(claim);
    var invites =
        jdbc.queryForList("SELECT * FROM account_invite WHERE id=? FOR UPDATE", invite(claim));
    if (invites.size() != 1) throw unavailable();
    var current = invites.getFirst();
    if (!Set.of("EMAIL_SENT", "ACCEPTED").contains(current.get("status"))
        || !scope.path("protectedKind").asText().equals(current.get("target_role"))
        || !(current.get("tenant_id") instanceof Number tenant)
        || tenant.longValue() != scope.path("targetTenantIds").get(0).asLong()
        || !scope.path("recipient").asText().equals(current.get("recipient_email"))
        || !"INVITE".equals(current.get("purpose"))
        || !(current.get("expires_at") instanceof Timestamp expiry)
        || !expiry.toInstant().equals(Instant.parse(scope.path("validUntil").asText()))
        || !expiry.toInstant().isAfter(clock.instant())) throw unavailable();
    var agency = scope.path("agencyBinding");
    if (current.get("agency_id") == null
        ? !agency.isNull()
        : !(current.get("agency_id") instanceof Number id)
            || agency.path("agencyId").asLong() != id.longValue()
            || agency.path("tenantId").asLong() != ((Number) current.get("tenant_id")).longValue())
      throw unavailable();
    var reservation = current.get("tenant_id_reservation_token");
    if (reservation == null
        ? !"EXISTING".equals(scope.path("tenantMode").asText())
            || !scope.path("reservationBinding").isNull()
        : !"RESERVED_NEW".equals(scope.path("tenantMode").asText())
            || !codec
                .digest(
                    "protected-reservation-capability/v1",
                    Map.of(
                        "workflowId",
                        scope.path("workflowId").asText(),
                        "tenantId",
                        ((Number) current.get("tenant_id")).longValue(),
                        "capability",
                        reservation))
                .equals(scope.path("reservationBinding").path("capabilityDigest").asText()))
      throw unavailable();
    var authorizations =
        jdbc.queryForList(
            "SELECT * FROM protected_invitation_authorization WHERE invite_id=? FOR UPDATE",
            invite(claim));
    if (authorizations.size() != 1) throw unavailable();
    var auth = authorizations.getFirst();
    if (!"READY".equals(auth.get("state"))
        || !claim.frozen().approvalId().toString().equals(auth.get("approval_id"))
        || !claim.frozen().requestDigest().equals(auth.get("request_digest"))
        || !claim.frozen().requestJson().equals(auth.get("request_json"))
        || !claim.frozen().issuerRealm().equals(auth.get("issuer_realm"))
        || !claim.frozen().issuerSubject().equals(auth.get("issuer_subject"))
        || !claim.approval().responseJson().equals(auth.get("response_json"))) throw unavailable();
    var claims =
        jdbc.queryForList(
            "SELECT * FROM protected_invitation_acceptance WHERE invite_id=? FOR UPDATE",
            invite(claim));
    if (claims.size() != 1) throw unavailable();
    var own = claims.getFirst();
    if (!claim.frozen().approvalId().toString().equals(own.get("approval_id"))
        || !claim.claimId().toString().equals(own.get("claim_id"))
        || !claim.attemptId().toString().equals(own.get("attempt_id"))
        || !claim.executionId().toString().equals(own.get("execution_id"))
        || !(own.get("journal_version") instanceof Number version)
        || version.longValue() != 1
        || !claim.registrationDigest().equals(own.get("registration_digest"))) throw unavailable();
  }

  private Row row(AcceptanceClaim claim) {
    var rows =
        jdbc.query(
            "SELECT metadata_json,request_envelope,result_envelope,state,claim_id FROM protected_otp_bootstrap WHERE invite_id=? FOR UPDATE",
            (rs, index) -> {
              if (!claim.claimId().toString().equals(rs.getString("claim_id"))) throw unavailable();
              return new Row(
                  rs.getString("metadata_json"),
                  rs.getString("request_envelope"),
                  rs.getString("result_envelope"),
                  rs.getString("state"));
            },
            invite(claim));
    if (rows.size() > 1) throw unavailable();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private JsonNode checkedMetadata(AcceptanceClaim claim, String raw) {
    var metadata = codec.read(raw, JsonNode.class);
    fields(metadata, METADATA);
    for (String name : METADATA) {
      if (Set.of("v", "authorizationRevision", "journalVersion", "authorityValidUntil")
          .contains(name)) {
        if (!metadata.path(name).isIntegralNumber()
            || !metadata.path(name).canConvertToLong()
            || metadata.path(name).asLong() < 1) throw unavailable();
      } else if (!metadata.path(name).isTextual() || metadata.path(name).asText().isBlank())
        throw unavailable();
    }
    if (!raw.equals(codec.canonical(metadata))
        || metadata.path("v").asInt() != 1
        || !realmId.equals(metadata.path("realmId").asText())
        || !claim.frozen().approvalId().toString().equals(metadata.path("approvalId").asText())
        || !scope(claim).path("workflowId").equals(metadata.path("workflowId"))
        || !scope(claim).path("inviteId").equals(metadata.path("inviteId"))
        || !scope(claim)
            .path("authorizationRevision")
            .equals(metadata.path("authorizationRevision"))
        || !claim.claimId().toString().equals(metadata.path("claimId").asText())
        || !claim.attemptId().toString().equals(metadata.path("attemptId").asText())
        || !claim.executionId().toString().equals(metadata.path("executionId").asText())
        || metadata.path("journalVersion").asLong() != 1
        || !"REQUEST".equals(metadata.path("representation").asText())
        || !"otp.bootstrap-read".equals(metadata.path("command").asText())
        || !metadata.path("authorityValidUntil").isIntegralNumber()
        || metadata.path("authorityValidUntil").asLong() <= 0) throw unavailable();
    for (String name : List.of("accountId", "operationId", "ownerSubject"))
      uuid(metadata.path(name).asText());
    for (String name : List.of("requestDigest", "originDigest"))
      if (!metadata.path(name).asText().matches("[0-9a-f]{64}")) throw unavailable();
    if (!"backend-account-admin".equals(metadata.path("ownerClient").asText())) throw unavailable();
    return metadata;
  }

  private String publicIntent(AcceptanceClaim claim) {
    String key = request.getHeader("Idempotency-Key");
    uuid(key);
    String method = request.getMethod(), path = request.getRequestURI();
    if (!"POST".equals(method) || path == null || path.isBlank() || path.length() > 1024)
      throw unavailable();
    return codec.digest(
        "protected-otp-bootstrap-public-intent/v1",
        Map.of(
            "subject",
            "protected-recipient/" + claim.claimId(),
            "key",
            key,
            "method",
            method,
            "path",
            path,
            "requestDigest",
            claim.registrationDigest()));
  }

  private JsonNode scope(AcceptanceClaim claim) {
    return codec.read(claim.frozen().requestJson(), JsonNode.class).path("scope");
  }

  private long invite(AcceptanceClaim claim) {
    try {
      long value = Long.parseLong(scope(claim).path("inviteId").asText());
      if (value < 1) throw unavailable();
      return value;
    } catch (RuntimeException rejected) {
      throw unavailable();
    }
  }

  private static void uuid(String value) {
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw unavailable();
    } catch (RuntimeException rejected) {
      throw unavailable();
    }
  }

  private static void fields(JsonNode value, Set<String> expected) {
    if (!value.isObject()) throw unavailable();
    var names = new HashSet<String>();
    value.fieldNames().forEachRemaining(names::add);
    if (!names.equals(expected)) throw unavailable();
  }

  private static JsonNode resultMetadata(JsonNode metadata) {
    var result = metadata.deepCopy();
    ((ObjectNode) result).put("representation", "PREPARED_RESULT");
    return result;
  }

  private String seal(JsonNode metadata, String payload) {
    if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 65536)
      throw unavailable();
    var nonce = new byte[12];
    RANDOM.nextBytes(nonce);
    byte[] cipher =
        crypt(Cipher.ENCRYPT_MODE, metadata, nonce, payload.getBytes(StandardCharsets.UTF_8));
    return codec.canonical(Map.of("v", 1, "nonce", encode(nonce), "ciphertext", encode(cipher)));
  }

  private String open(JsonNode independentlyCheckedMetadata, String envelope) {
    if (envelope == null || envelope.length() > 87500) throw unavailable();
    var value = codec.read(envelope, JsonNode.class);
    fields(value, Set.of("v", "nonce", "ciphertext"));
    if (!envelope.equals(codec.canonical(value)) || value.path("v").asInt() != 1)
      throw unavailable();
    var nonce = decode(value.path("nonce").asText());
    var cipher = decode(value.path("ciphertext").asText());
    if (nonce.length != 12 || cipher.length < 16 || cipher.length > 65552) throw unavailable();
    byte[] plain = crypt(Cipher.DECRYPT_MODE, independentlyCheckedMetadata, nonce, cipher);
    try {
      return codec.utf8(plain);
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  private byte[] crypt(int mode, JsonNode metadata, byte[] nonce, byte[] value) {
    byte[] key =
        HexFormat.of()
            .parseHex(codec.digest("oriso/protected-otp-bootstrap/encryption/v1", metadata));
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(codec.canonical(metadata).getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(value);
    } catch (java.security.GeneralSecurityException rejected) {
      throw unavailable();
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] decode(String value) {
    try {
      var bytes = Base64.getUrlDecoder().decode(value);
      if (!value.equals(encode(bytes))) throw unavailable();
      return bytes;
    } catch (RuntimeException rejected) {
      throw unavailable();
    }
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
  }

  private static void owningSql() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
  }

  private record Row(String metadata, String request, String result, String state) {
    @Override
    public String toString() {
      return "ProtectedOtpBootstrapRow[redacted]";
    }
  }

  /** All three methods capture actual servlet key/method/path/body inside this adapter. */
  public BootstrapAttempt setupApp(AcceptanceClaim currentClaim, String initialCode) {
    throw unavailable();
  }

  public BootstrapAttempt startEmail(AcceptanceClaim currentClaim) {
    throw unavailable();
  }

  public BootstrapAttempt finishEmail(AcceptanceClaim currentClaim, String initialCode) {
    throw unavailable();
  }

  /** Reconstruct opaque success only from the independently checked committed private journal. */
  public Completion completed(AcceptanceClaim currentClaim) {
    throw unavailable();
  }

  public enum AttemptState {
    COMPLETE,
    INVALID,
    PENDING,
    UNKNOWN
  }

  public static final class PreparedBootstrap {
    private final String claimId;
    private final String metadata;
    private final String result;

    private PreparedBootstrap(String claimId, String metadata, String result) {
      this.claimId = claimId;
      this.metadata = metadata;
      this.result = result;
    }

    @Override
    public String toString() {
      return "PreparedProtectedOtp[redacted]";
    }
  }

  public static final class BootstrapAttempt {
    private BootstrapAttempt() {}

    public AttemptState state() {
      throw unavailable();
    }

    /** Available only for COMPLETE; private constructor of Completion supplies no raw-tuple API. */
    public Completion completion() {
      throw unavailable();
    }

    @Override
    public String toString() {
      return "ProtectedOtpAttempt[redacted]";
    }
  }

  public static final class Completion {
    private Completion() {}

    public UUID claimId() {
      throw unavailable();
    }

    public UUID attemptId() {
      throw unavailable();
    }

    public UUID executionId() {
      throw unavailable();
    }

    public UUID bootstrapIncarnation() {
      throw unavailable();
    }

    public String accountId() {
      throw unavailable();
    }

    /** Metadata-only MAC-bound native completion receipt; never setup material/code/JWT/address. */
    public String receipt() {
      throw unavailable();
    }

    @Override
    public String toString() {
      return "ProtectedOtpCompletion[redacted]";
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Protected OTP bootstrap unavailable");
  }
}
