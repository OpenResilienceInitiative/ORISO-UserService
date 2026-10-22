package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.AccountCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;

/** Finite protected wire only; ownership values do not authorize SQL, OTP or activation. */
public final class ProtectedAccountCodec {
  private final byte[] key;
  private final Clock clock;
  private final ProtectedInvitationCodec approvals;
  private final String realmName;
  private final String realmId;
  private final String realmIssuer;
  private final AccountLifecycleCodec json;
  private final ObjectMapper nodes = new ObjectMapper();

  public ProtectedAccountCodec(
      byte[] key,
      Clock clock,
      ProtectedInvitationCodec approvals,
      String realmName,
      String realmId,
      String realmIssuer) {
    json = new AccountLifecycleCodec(key);
    if (clock == null
        || approvals == null
        || !bounded(realmName, 255)
        || !bounded(realmId, 255)
        || !bounded(realmIssuer, 512)
        || !realmName.equals(approvals.realmName())) throw invalid();
    this.key = key.clone();
    this.clock = clock;
    this.approvals = approvals;
    this.realmName = realmName;
    this.realmId = realmId;
    this.realmIssuer = realmIssuer;
  }

  String realmName() {
    return realmName;
  }

  public PreparedCreation prepare(
      AcceptanceClaim claim, JsonNode registration, AccountCreation account) {
    try {
      if (claim == null
          || account == null
          || registration == null
          || !registration.isObject()
          || claim.frozen() == null
          || claim.approval() == null
          || claim.claimId() == null
          || claim.attemptId() == null
          || claim.executionId() == null
          || claim.journalVersion() != 1
          || !realmId.equals(claim.frozen().issuerRealm())) throw invalid();
      var frozen = claim.frozen();
      var supplied = claim.approval();
      var verified = approvals.verifyAuthorized(frozen, supplied.responseJson());
      if (!verified.approvalId().equals(supplied.approvalId())
          || !equal(verified.scopeDigest(), supplied.scopeDigest())
          || !equal(verified.requestDigest(), supplied.requestDigest())
          || !equal(verified.receiptJson(), supplied.receiptJson())) throw invalid();
      JsonNode scope = json.read(frozen.requestJson(), JsonNode.class).path("scope");
      String inviteId = text(scope, "inviteId", 255);
      long numericInvite = Long.parseLong(inviteId);
      if (numericInvite < 1 || !Long.toString(numericInvite).equals(inviteId)) throw invalid();
      JsonNode exactRegistration = json.read(json.canonical(registration), JsonNode.class);
      String registrationDigest =
          json.digest(
              "protected-recipient-registration/v1",
              Map.of(
                  "inviteId",
                  inviteId,
                  "approvalId",
                  frozen.approvalId().toString(),
                  "approvalRequestDigest",
                  frozen.requestDigest(),
                  "registration",
                  exactRegistration));
      if (!equal(registrationDigest, claim.registrationDigest())) throw invalid();
      String recipient = text(scope, "recipient", 320);
      String username =
          recipient.startsWith("enc.")
              ? recipient
              : "enc."
                  + new Base32()
                      .encodeAsString(recipient.getBytes(StandardCharsets.UTF_8))
                      .replace('=', '.');
      if (!recipient.equals(account.email()) || !username.equals(account.username()))
        throw invalid();
      JsonNode receipt = json.read(verified.receiptJson(), JsonNode.class);
      ObjectNode receiptPayload = receipt.deepCopy();
      receiptPayload.remove("receipt");
      if (!receiptPayload.equals(
          decode(text(receipt, "receipt", 16384), "protected-approval-receipt/v1", true)))
        throw invalid();
      UUID workflow = uuid(text(scope, "workflowId", 36));
      long revision = number(scope, "authorizationRevision");
      if (revision < 1) throw invalid();
      ObjectNode acceptance = nodes.createObjectNode();
      acceptance.put("workflowId", workflow.toString());
      acceptance.put("inviteId", inviteId);
      acceptance.put("authorizationRevision", revision);
      acceptance.put("claimId", claim.claimId().toString());
      acceptance.put("journalVersion", 1);
      ObjectNode body = nodes.createObjectNode();
      body.set("approvalReceipt", receipt);
      body.put("scopeDigest", verified.scopeDigest());
      body.put("authorizationRevision", revision);
      body.set("acceptanceClaim", acceptance);
      body.set("execution", execution(claim.executionId()));
      body.set("account", json.read(json.canonical(account), JsonNode.class));
      String fingerprint = json.digest("protected-account-creation/v1", body);
      body.put("creationFingerprint", fingerprint);
      return new PreparedCreation(
          this,
          frozen.approvalId(),
          claim.claimId(),
          claim.attemptId(),
          claim.executionId(),
          scope,
          acceptance,
          receipt,
          frozen.issuerSubject(),
          verified.scopeDigest(),
          fingerprint,
          account,
          json.canonical(body),
          Instant.parse(text(scope, "validUntil", 30)));
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  public byte[] createBody(PreparedCreation prepared) {
    require(prepared);
    return prepared.body.getBytes(StandardCharsets.UTF_8);
  }

  public String createProof(PreparedCreation prepared, TaskActorCredentials actor) {
    require(prepared);
    if (!prepared.validUntil.isAfter(clock.instant())) throw invalid();
    return proof(prepared, actor, "create", "PUT", json.read(prepared.body, JsonNode.class));
  }

  public VerifiedCreated verifyCreated(PreparedCreation prepared, byte[] response) {
    try {
      require(prepared);
      JsonNode result = result(prepared, response, "OPEN", null);
      String accountId = text(result, "accountId", 255);
      String creationProof = text(result, "creationProof", 16384);
      JsonNode authenticated = decode(creationProof, "creation-proof", false);
      fields(
          authenticated,
          "v",
          "realmName",
          "realmId",
          "attemptId",
          "accountId",
          "ownerClient",
          "ownerSubject",
          "scope",
          "initialGroupId",
          "initialRoleFingerprint",
          "creationFingerprint");
      if (number(authenticated, "v") != 1
          || !realmName.equals(text(authenticated, "realmName", 255))
          || !realmId.equals(text(authenticated, "realmId", 255))
          || !prepared.attemptId.equals(uuid(text(authenticated, "attemptId", 36)))
          || !accountId.equals(text(authenticated, "accountId", 255))
          || !text(prepared.scope, "creatorClient", 255)
              .equals(text(authenticated, "ownerClient", 255))
          || !text(prepared.scope, "creatorSubject", 36)
              .equals(text(authenticated, "ownerSubject", 36))
          || !equal(prepared.fingerprint, text(authenticated, "creationFingerprint", 64)))
        throw invalid();
      checkedInternalScope(prepared, authenticated.path("scope"));
      text(authenticated, "initialGroupId", 512);
      hex(text(authenticated, "initialRoleFingerprint", 64));
      return new VerifiedCreated(
          this, prepared, accountId, creationProof, json.canonical(authenticated));
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  public String checkpoint(VerifiedCreated created) {
    require(created);
    var prepared = created.prepared;
    ObjectNode saved = nodes.createObjectNode();
    saved.put("v", 1);
    saved.put("realmName", realmName);
    saved.put("realmId", realmId);
    saved.put("approvalId", prepared.approvalId.toString());
    saved.put("claimId", prepared.claimId.toString());
    saved.put("attemptId", prepared.attemptId.toString());
    saved.put("executionId", prepared.executionId.toString());
    saved.set("scope", prepared.scope.deepCopy());
    saved.set("acceptance", prepared.acceptance.deepCopy());
    saved.set("approvalReceipt", prepared.receipt.deepCopy());
    saved.put("issuerSubject", prepared.issuerSubject);
    saved.put("scopeDigest", prepared.scopeDigest);
    saved.put("creationFingerprint", prepared.fingerprint);
    ObjectNode profile = nodes.createObjectNode();
    profile.put("username", prepared.account.username());
    profile.put("email", prepared.account.email());
    profile.put("firstName", prepared.account.firstName());
    profile.put("lastName", prepared.account.lastName());
    profile.put("preferredLanguage", prepared.account.preferredLanguage());
    profile.put("passwordTemporary", prepared.account.passwordTemporary());
    saved.set("profile", profile);
    saved.put("validUntil", prepared.validUntil.toString());
    saved.put("accountId", created.accountId);
    saved.put("creationProof", created.creationProof);
    String encoded = encode(json.canonical(saved).getBytes(StandardCharsets.UTF_8));
    String checkpoint =
        encoded
            + "."
            + HexFormat.of().formatHex(mac("protected-created-checkpoint/v1\n" + encoded));
    if (checkpoint.length() > 16384) throw invalid();
    return checkpoint;
  }

  public VerifiedCreated restore(AcceptanceClaim claim, String checkpoint) {
    try {
      if (claim == null || claim.frozen() == null || claim.approval() == null) throw invalid();
      JsonNode saved = decode(checkpoint, "protected-created-checkpoint/v1", true);
      fields(
          saved,
          "v",
          "realmName",
          "realmId",
          "approvalId",
          "claimId",
          "attemptId",
          "executionId",
          "scope",
          "acceptance",
          "approvalReceipt",
          "issuerSubject",
          "scopeDigest",
          "creationFingerprint",
          "profile",
          "validUntil",
          "accountId",
          "creationProof");
      JsonNode original =
          json.read(approvals.approvalBody(claim.frozen()), JsonNode.class).path("scope");
      if (number(saved, "v") != 1
          || !realmName.equals(text(saved, "realmName", 255))
          || !realmId.equals(text(saved, "realmId", 255))
          || !realmId.equals(claim.frozen().issuerRealm())
          || !claim.frozen().approvalId().equals(uuid(text(saved, "approvalId", 36)))
          || !claim.claimId().equals(uuid(text(saved, "claimId", 36)))
          || !claim.attemptId().equals(uuid(text(saved, "attemptId", 36)))
          || !claim.executionId().equals(uuid(text(saved, "executionId", 36)))
          || claim.journalVersion() != 1
          || !original.equals(saved.path("scope"))
          || !claim.frozen().issuerSubject().equals(text(saved, "issuerSubject", 36))
          || !equal(claim.approval().scopeDigest(), text(saved, "scopeDigest", 64))
          || !json.canonical(saved.path("approvalReceipt")).equals(claim.approval().receiptJson())
          || !text(original, "validUntil", 30).equals(text(saved, "validUntil", 30)))
        throw invalid();
      JsonNode acceptance = saved.path("acceptance");
      fields(
          acceptance,
          "workflowId",
          "inviteId",
          "authorizationRevision",
          "claimId",
          "journalVersion");
      if (!text(original, "workflowId", 36).equals(text(acceptance, "workflowId", 36))
          || !text(original, "inviteId", 255).equals(text(acceptance, "inviteId", 255))
          || number(original, "authorizationRevision")
              != number(acceptance, "authorizationRevision")
          || !claim.claimId().equals(uuid(text(acceptance, "claimId", 36)))
          || number(acceptance, "journalVersion") != 1) throw invalid();
      JsonNode profile = saved.path("profile");
      fields(
          profile,
          "username",
          "email",
          "firstName",
          "lastName",
          "preferredLanguage",
          "passwordTemporary");
      AccountCreation account =
          new AccountCreation(
              text(profile, "username", 255),
              text(profile, "email", 255),
              optionalText(profile, "firstName", 255),
              optionalText(profile, "lastName", 255),
              optionalText(profile, "preferredLanguage", 20),
              null,
              bool(profile, "passwordTemporary"));
      if (!account.email().equals(text(original, "recipient", 320))) throw invalid();
      var prepared =
          new PreparedCreation(
              this,
              claim.frozen().approvalId(),
              claim.claimId(),
              claim.attemptId(),
              claim.executionId(),
              original,
              acceptance,
              saved.path("approvalReceipt"),
              claim.frozen().issuerSubject(),
              text(saved, "scopeDigest", 64),
              hex(text(saved, "creationFingerprint", 64)),
              account,
              null,
              Instant.parse(text(saved, "validUntil", 30)));
      ObjectNode response = nodes.createObjectNode();
      response.put("attemptId", prepared.attemptId.toString());
      response.put("accountId", text(saved, "accountId", 255));
      response.put("state", "OPEN");
      response.set("execution", execution(prepared.executionId));
      response.put("creationProof", text(saved, "creationProof", 16384));
      response.putNull("recoveryReceipt");
      response.putNull("terminalReceipt");
      return verifyCreated(prepared, json.canonical(response).getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private static String optionalText(JsonNode value, String name, int limit) {
    JsonNode field = value.path(name);
    if (field.isNull()) return null;
    if (!field.isTextual() || field.textValue().length() > limit) throw invalid();
    return field.textValue();
  }

  public byte[] ownedReadBody(VerifiedCreated created) {
    require(created);
    return json.canonical(existingBody(created)).getBytes(StandardCharsets.UTF_8);
  }

  public String ownedReadProof(VerifiedCreated created, TaskActorCredentials actor) {
    require(created);
    return proof(created.prepared, actor, "owned-read", "POST", existingBody(created));
  }

  /** Pure comparison only; the actual HTTP bridge owns the pending-owned account handle. */
  public void checkOwnedProjection(VerifiedCreated created, byte[] projection) {
    try {
      require(created);
      JsonNode value = json.read(json.utf8(projection), JsonNode.class);
      fields(
          value,
          "id",
          "username",
          "email",
          "firstName",
          "lastName",
          "tenantId",
          "preferredLanguage",
          "enabled",
          "emailVerified",
          "effectiveRoles",
          "passwordChangeRequired");
      AccountCreation account = created.prepared.account;
      if (!created.accountId.equals(text(value, "id", 255))
          || !account.username().equalsIgnoreCase(text(value, "username", 255))
          || !account.email().equals(text(value, "email", 255))
          || !nullableEquals(value.path("firstName"), account.firstName())
          || !nullableEquals(value.path("lastName"), account.lastName())
          || !Long.toString(created.tenantId()).equals(text(value, "tenantId", 30))
          || !(account.preferredLanguage() == null ? "de" : account.preferredLanguage())
              .equals(text(value, "preferredLanguage", 20))
          || bool(value, "enabled")
          || !bool(value, "emailVerified")
          || !value.path("effectiveRoles").isArray()
          || !value.path("effectiveRoles").isEmpty()
          || bool(value, "passwordChangeRequired") != account.passwordTemporary()) throw invalid();
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  public byte[] commitBody(VerifiedCreated created, LocalOutcome local) {
    require(created);
    return json.canonical(commit(created, local)).getBytes(StandardCharsets.UTF_8);
  }

  public String commitProof(
      VerifiedCreated created, LocalOutcome local, TaskActorCredentials actor) {
    require(created);
    return proof(created.prepared, actor, "commit", "POST", commit(created, local));
  }

  public VerifiedCommitted verifyCommitted(
      VerifiedCreated created, LocalOutcome local, byte[] response) {
    try {
      require(created);
      JsonNode result = result(created.prepared, response, "COMMITTED", created);
      String receipt = text(result, "terminalReceipt", 16384);
      JsonNode terminal = decode(receipt, "terminal-receipt", true);
      fields(
          terminal,
          "realmId",
          "attemptId",
          "accountId",
          "ownerClient",
          "ownerSubject",
          "scope",
          "creationFingerprint",
          "groupId",
          "roleFingerprint",
          "state",
          "generation",
          "executionId",
          "operation",
          "commandDigest");
      JsonNode authenticated = json.read(created.authenticated, JsonNode.class);
      if (!realmId.equals(text(terminal, "realmId", 255))
          || !created.attemptId().equals(uuid(text(terminal, "attemptId", 36)))
          || !created.accountId.equals(text(terminal, "accountId", 255))
          || !text(authenticated, "ownerClient", 255).equals(text(terminal, "ownerClient", 255))
          || !text(authenticated, "ownerSubject", 36).equals(text(terminal, "ownerSubject", 36))
          || !json.canonical(authenticated.path("scope")).equals(text(terminal, "scope", 16384))
          || !equal(created.creationFingerprint(), text(terminal, "creationFingerprint", 64))
          || !created.initialGroupId().equals(text(terminal, "groupId", 512))
          || !equal(created.initialRoleFingerprint(), text(terminal, "roleFingerprint", 64))
          || !"COMMITTED".equals(text(terminal, "state", 20))
          || number(terminal, "generation") != 0
          || !created.executionId().equals(uuid(text(terminal, "executionId", 36)))
          || !"commit".equals(text(terminal, "operation", 20))
          || !equal(
              json.digest("protected-invitation-payload/v1", commit(created, local)),
              text(terminal, "commandDigest", 64))) throw invalid();
      return new VerifiedCommitted(created, local, receipt);
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private void checkedInternalScope(PreparedCreation prepared, JsonNode value) {
    fields(value, "tenantId", "registrationKind", "initialGroup", "origin", "approvalBinding");
    JsonNode original = prepared.scope;
    JsonNode target = original.path("targetTenantIds");
    if (!Long.toString(target.get(0).longValue()).equals(text(value, "tenantId", 30))
        || !text(original, "protectedKind", 30).equals(text(value, "registrationKind", 30))
        || !text(value, "initialGroup", 512).startsWith("/")) throw invalid();
    JsonNode origin = value.path("origin");
    fields(
        origin,
        "kind",
        "workflowId",
        "provenance",
        "humanIssuer",
        "humanSubject",
        "humanAuthority",
        "agencyIds");
    if (!"INVITATION".equals(text(origin, "kind", 20))
        || !text(original, "workflowId", 36).equals(text(origin, "workflowId", 36))
        || !("protected-approval-v1:" + prepared.approvalId).equals(text(origin, "provenance", 512))
        || !realmIssuer.equals(text(origin, "humanIssuer", 512))
        || !prepared.issuerSubject.equals(text(origin, "humanSubject", 36))) throw invalid();
    text(origin, "humanAuthority", 255);
    var agencies = nodes.createArrayNode();
    if (!original.path("agencyBinding").isNull())
      agencies.add(Long.toString(number(original.path("agencyBinding"), "agencyId")));
    if (!agencies.equals(origin.path("agencyIds"))) throw invalid();
    JsonNode binding = value.path("approvalBinding");
    fields(binding, "approvalId", "scopeDigest", "authorizationRevision", "acceptanceClaim");
    if (!prepared.approvalId.toString().equals(text(binding, "approvalId", 36))
        || !equal(prepared.scopeDigest, text(binding, "scopeDigest", 64))
        || number(original, "authorizationRevision") != number(binding, "authorizationRevision")
        || !json.canonical(prepared.acceptance)
            .equals(json.canonical(binding.path("acceptanceClaim")))) throw invalid();
  }

  private JsonNode result(
      PreparedCreation prepared, byte[] response, String state, VerifiedCreated created) {
    JsonNode value = json.read(json.utf8(response), JsonNode.class);
    fields(
        value,
        "attemptId",
        "accountId",
        "state",
        "execution",
        "creationProof",
        "recoveryReceipt",
        "terminalReceipt");
    if (!prepared.attemptId.equals(uuid(text(value, "attemptId", 36)))
        || !state.equals(text(value, "state", 20))
        || !execution(prepared.executionId).equals(value.path("execution"))
        || !value.path("recoveryReceipt").isNull()) throw invalid();
    text(value, "accountId", 255);
    text(value, "creationProof", 16384);
    if (created == null) {
      if (!value.path("terminalReceipt").isNull()) throw invalid();
    } else if (!created.accountId.equals(text(value, "accountId", 255))
        || !equal(created.creationProof, text(value, "creationProof", 16384))) throw invalid();
    return value;
  }

  private ObjectNode commonBody(PreparedCreation prepared) {
    ObjectNode body = nodes.createObjectNode();
    body.set("approvalReceipt", prepared.receipt.deepCopy());
    body.put("scopeDigest", prepared.scopeDigest);
    body.put("authorizationRevision", number(prepared.scope, "authorizationRevision"));
    body.put("creationFingerprint", prepared.fingerprint);
    body.set("execution", execution(prepared.executionId));
    return body;
  }

  private ObjectNode existingBody(VerifiedCreated created) {
    ObjectNode body = commonBody(created.prepared);
    body.put("accountId", created.accountId);
    body.put("creationProof", created.creationProof);
    return body;
  }

  private ObjectNode commit(VerifiedCreated created, LocalOutcome local) {
    if (local == null) throw invalid();
    ObjectNode body = existingBody(created);
    body.set("localCommit", json.read(json.canonical(local), JsonNode.class));
    return body;
  }

  private ObjectNode execution(UUID id) {
    ObjectNode value = nodes.createObjectNode();
    value.put("executionId", id.toString());
    value.put("generation", 0);
    return value;
  }

  private String proof(
      PreparedCreation prepared,
      TaskActorCredentials actor,
      String operation,
      String method,
      JsonNode body) {
    if (actor == null
        || actor.actor() != TaskActor.ACCOUNT_CREATE
        || !text(prepared.scope, "creatorClient", 255).equals(actor.clientId())
        || !text(prepared.scope, "creatorSubject", 36).equals(actor.serviceSubject()))
      throw invalid();
    long now = clock.instant().getEpochSecond();
    Map<String, Object> claims = new TreeMap<>();
    claims.put("iss", "userservice");
    claims.put("aud", "oriso-protected-invitations");
    claims.put("purpose", "ACCOUNT_PROVISIONING");
    claims.put("actor", "ACCOUNT_CREATE");
    claims.put("operation", "protected-account." + operation);
    claims.put("method", method);
    claims.put("realm", realmName);
    claims.put(
        "target",
        "realm/"
            + realmId
            + "/protected-invitation/"
            + prepared.approvalId
            + "/account-attempt/"
            + prepared.attemptId);
    claims.put("taskClient", actor.clientId());
    claims.put("taskSubject", actor.serviceSubject());
    claims.put("payloadDigest", json.digest("protected-invitation-payload/v1", body));
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("nonce", UUID.randomUUID().toString().replace("-", ""));
    claims.put("iat", now);
    claims.put("exp", now + 60);
    String encoded = encode(json.canonical(claims).getBytes(StandardCharsets.UTF_8));
    return encoded + "." + encode(mac("protected-invitation-proof/v1\n" + encoded));
  }

  private JsonNode decode(String receipt, String domain, boolean hexSignature) {
    if (!bounded(receipt, 16384)) throw invalid();
    String[] parts = receipt.split("\\.", -1);
    if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]+")) throw invalid();
    byte[] raw = Base64.getUrlDecoder().decode(parts[0]);
    if (!encode(raw).equals(parts[0])) throw invalid();
    byte[] signature;
    if (hexSignature) {
      hex(parts[1]);
      signature = HexFormat.of().parseHex(parts[1]);
    } else {
      if (!parts[1].matches("[A-Za-z0-9_-]+")) throw invalid();
      signature = Base64.getUrlDecoder().decode(parts[1]);
      if (!encode(signature).equals(parts[1])) throw invalid();
    }
    if (!MessageDigest.isEqual(signature, mac(domain + "\n" + parts[0]))) throw invalid();
    String text = json.utf8(raw);
    JsonNode value = json.read(text, JsonNode.class);
    if (!text.equals(json.canonical(value))) throw invalid();
    return value;
  }

  private void require(PreparedCreation value) {
    if (value == null || value.owner != this) throw invalid();
  }

  private void require(VerifiedCreated value) {
    if (value == null || value.owner != this) throw invalid();
  }

  private static boolean bounded(String value, int limit) {
    return value != null && !value.isBlank() && value.length() <= limit;
  }

  private static void fields(JsonNode value, String... names) {
    if (value == null || !value.isObject()) throw invalid();
    Set<String> actual = new HashSet<>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(Set.of(names))) throw invalid();
  }

  private static String text(JsonNode value, String name, int limit) {
    JsonNode field = value.path(name);
    if (!field.isTextual() || !bounded(field.textValue(), limit)) throw invalid();
    return field.textValue();
  }

  private static long number(JsonNode value, String name) {
    JsonNode field = value.path(name);
    if (!field.isIntegralNumber() || !field.canConvertToLong()) throw invalid();
    return field.longValue();
  }

  private static boolean bool(JsonNode value, String name) {
    if (!value.path(name).isBoolean()) throw invalid();
    return value.path(name).booleanValue();
  }

  private static boolean nullableEquals(JsonNode value, String expected) {
    return expected == null
        ? value.isNull()
        : value.isTextual() && expected.equals(value.textValue());
  }

  private static UUID uuid(String value) {
    UUID id = UUID.fromString(value);
    if (!id.toString().equals(value)) throw invalid();
    return id;
  }

  private static String hex(String value) {
    if (!value.matches("[0-9a-f]{64}")) throw invalid();
    return value;
  }

  private static boolean equal(String left, String right) {
    return left != null
        && right != null
        && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private byte[] mac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("Protected account HMAC unavailable");
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Protected account result is unavailable");
  }

  public static final class PreparedCreation {
    private final ProtectedAccountCodec owner;
    private final UUID approvalId, claimId, attemptId, executionId;
    private final JsonNode scope, acceptance, receipt;
    private final String issuerSubject, scopeDigest, fingerprint, body;
    private final AccountCreation account;
    private final Instant validUntil;

    private PreparedCreation(
        ProtectedAccountCodec owner,
        UUID approvalId,
        UUID claimId,
        UUID attemptId,
        UUID executionId,
        JsonNode scope,
        JsonNode acceptance,
        JsonNode receipt,
        String issuerSubject,
        String scopeDigest,
        String fingerprint,
        AccountCreation account,
        String body,
        Instant validUntil) {
      this.owner = owner;
      this.approvalId = approvalId;
      this.claimId = claimId;
      this.attemptId = attemptId;
      this.executionId = executionId;
      this.scope = scope.deepCopy();
      this.acceptance = acceptance.deepCopy();
      this.receipt = receipt.deepCopy();
      this.issuerSubject = issuerSubject;
      this.scopeDigest = scopeDigest;
      this.fingerprint = fingerprint;
      this.account = account;
      this.body = body;
      this.validUntil = validUntil;
    }

    public UUID approvalId() {
      return approvalId;
    }

    public UUID claimId() {
      return claimId;
    }

    public UUID attemptId() {
      return attemptId;
    }

    public UUID executionId() {
      return executionId;
    }

    public String creationFingerprint() {
      return fingerprint;
    }

    @Override
    public String toString() {
      return "ProtectedPreparedCreation[redacted]";
    }
  }

  /** Authenticated creation ownership only; never an OTP/local-account authority handle. */
  public static final class VerifiedCreated {
    private final ProtectedAccountCodec owner;
    private final PreparedCreation prepared;
    private final String accountId, creationProof, authenticated;

    private VerifiedCreated(
        ProtectedAccountCodec owner,
        PreparedCreation prepared,
        String accountId,
        String creationProof,
        String authenticated) {
      this.owner = owner;
      this.prepared = prepared;
      this.accountId = accountId;
      this.creationProof = creationProof;
      this.authenticated = authenticated;
    }

    private JsonNode authenticated() {
      return owner.json.read(authenticated, JsonNode.class);
    }

    public String realmId() {
      return owner.realmId;
    }

    public UUID approvalId() {
      return prepared.approvalId;
    }

    public UUID workflowId() {
      return uuid(text(prepared.scope, "workflowId", 36));
    }

    public String inviteId() {
      return text(prepared.scope, "inviteId", 255);
    }

    public long authorizationRevision() {
      return number(prepared.scope, "authorizationRevision");
    }

    public long tenantId() {
      return prepared.scope.path("targetTenantIds").get(0).longValue();
    }

    public String registrationKind() {
      return text(prepared.scope, "protectedKind", 30);
    }

    public String profileId() {
      return text(prepared.scope, "protectedProfileId", 255);
    }

    public String initialGroupId() {
      return text(authenticated(), "initialGroupId", 512);
    }

    public String initialGroupPath() {
      return text(authenticated().path("scope"), "initialGroup", 512);
    }

    public String initialRoleFingerprint() {
      return text(authenticated(), "initialRoleFingerprint", 64);
    }

    public UUID claimId() {
      return prepared.claimId;
    }

    public UUID attemptId() {
      return prepared.attemptId;
    }

    public UUID executionId() {
      return prepared.executionId;
    }

    public String email() {
      return prepared.account.email();
    }

    public String firstName() {
      return prepared.account.firstName();
    }

    public String lastName() {
      return prepared.account.lastName();
    }

    public String accountId() {
      return accountId;
    }

    public String creationFingerprint() {
      return prepared.fingerprint;
    }

    public String creationProof() {
      return creationProof;
    }

    @Override
    public String toString() {
      return "ProtectedVerifiedCreated[redacted]";
    }
  }

  public static final class VerifiedCommitted {
    private final VerifiedCreated created;
    private final LocalOutcome localOutcome;
    private final String receipt;

    private VerifiedCommitted(VerifiedCreated created, LocalOutcome localOutcome, String receipt) {
      this.created = created;
      this.localOutcome = localOutcome;
      this.receipt = receipt;
    }

    public LocalOutcome localOutcome() {
      return localOutcome;
    }

    public UUID claimId() {
      return created.claimId();
    }

    public UUID attemptId() {
      return created.attemptId();
    }

    public String accountId() {
      return created.accountId();
    }

    public String terminalReceipt() {
      return receipt;
    }

    @Override
    public String toString() {
      return "ProtectedVerifiedCommitted[redacted]";
    }
  }
}
