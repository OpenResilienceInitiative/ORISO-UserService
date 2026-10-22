package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.accountinvite.ExistingAccountSetupService;
import de.caritas.cob.userservice.api.service.auth.PasswordResetBinding;
import de.caritas.cob.userservice.api.service.auth.PasswordResetService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Only actual service-private setup/reset continuations may reach this finite adapter. */
@RequiredArgsConstructor
public final class ClaimedPasswordMaintenance {
  private final @NonNull IdentityClientConfig mode;
  private final @NonNull JdbcTemplate jdbc;
  private final @NonNull PlatformTransactionManager transactions;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull RestTemplate http;
  private final @NonNull Clock clock;
  private final @NonNull String authServer;
  private final @NonNull String publicIssuer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;
  private final @NonNull Map<String, List<ProfileSelector>> profiles;

  /**
   * Reviewed mounted selectors are data; the native receiver independently checks actual
   * provenance.
   */
  public record ProfileSelector(String kind, String id, long version) {
    public ProfileSelector {
      if (!Set.of("ORDINARY", "PROTECTED").contains(kind)
          || id == null
          || id.isBlank()
          || id.length() > 255
          || version <= 0) throw new IllegalArgumentException("Invalid password profile selector");
    }
  }

  public void requireSupport() {
    if (!mode.isFiveActorMode()
        || !profiles
            .keySet()
            .equals(Set.of("USER", "CONSULTANT", "AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN"))
        || actors.require(TaskPurpose.ACCOUNT_MAINTENANCE).actor() != TaskActor.ACCOUNT_ADMIN)
      throw new ScopedIdentityCommandRequiredException();
    for (var entry : profiles.entrySet()) {
      var candidates = entry.getValue();
      if (candidates == null
          || candidates.isEmpty()
          || candidates.size() > 20
          || new HashSet<>(candidates).size() != candidates.size()) throw unavailable();
      String expectedKind =
          Set.of("TENANT_ADMIN", "PLATFORM_ADMIN").contains(entry.getKey())
              ? "PROTECTED"
              : "ORDINARY";
      if (candidates.stream()
          .anyMatch(candidate -> candidate == null || !expectedKind.equals(candidate.kind())))
        throw unavailable();
    }
  }

  public String existingSetupRequestDigest(
      ExistingSetupPasswordClaims.Binding binding, String password) {
    requireSupport();
    return codec.existingSetupPasswordRequestDigest(binding, password);
  }

  public String passwordResetRequestDigest(PasswordResetBinding binding, String password) {
    requireSupport();
    return codec.passwordResetRequestDigest(binding, password);
  }

  public PasswordResetBinding issuance(
      UUID workflowId,
      String accountId,
      Long tenantId,
      String username,
      String recipient,
      String accountKind,
      Instant expiresAt) {
    requireSupport();
    if (workflowId == null || expiresAt == null || !expiresAt.isAfter(clock.instant()))
      throw unavailable();
    return transaction(
        () -> {
          var local = localAccounts().lock(accountId);
          if (!Objects.equals(tenantId, local.tenant())
              || !accountKind.equals(local.kind())
              || !ClaimedPasswordLocalAccounts.canonicalUsername(username).equals(local.username())
              || !recipient.equals(local.email())) throw unavailable();
          return new PasswordResetBinding(
              workflowId,
              accountId,
              tenantId,
              local.username(),
              local.kind(),
              local.version(),
              codec.passwordResetRecipientDigest(local.email()),
              expiresAt);
        });
  }

  public void setup(ExistingAccountSetupService.ClaimedSetup claim, String password) {
    requireSupport();
    var checked = claim.checkedRecoveryClaim();
    var b = checked.binding();
    String kind =
        switch (b.role()) {
          case COUNSELLOR -> "CONSULTANT";
          case AGENCY_ADMIN -> "AGENCY_ADMIN";
          case TENANT_ADMIN -> "TENANT_ADMIN";
          default -> throw unavailable();
        };
    String digest = codec.existingSetupPasswordRequestDigest(b, password);
    if (!digest.equals(checked.requestDigest())) throw unavailable();
    var binding =
        new Bound(
            "EXISTING_SETUP",
            checked.id(),
            checked.version(),
            b.accountId(),
            b.tenantId(),
            ClaimedPasswordLocalAccounts.canonicalUsername(b.username()),
            codec.passwordResetRecipientDigest(b.email()),
            kind,
            null,
            b.expiresAt().atZone(ZoneId.systemDefault()).toInstant().getEpochSecond(),
            digest);
    execute(binding, password, claim::checkedClaim, claim::checkedRecoveryClaim);
  }

  public void reset(PasswordResetService.ClaimedReset claim, String password) {
    requireSupport();
    var checked = claim.checkedRecoveryClaim();
    var b = checked.binding();
    String digest = codec.passwordResetRequestDigest(b, password);
    if (!digest.equals(checked.requestDigest())) throw unavailable();
    var binding =
        new Bound(
            "PASSWORD_RESET",
            checked.claimId(),
            checked.version(),
            b.subjectId(),
            b.tenantId(),
            b.username(),
            b.recipientDigest(),
            b.accountKind(),
            b.accountVersion(),
            b.expiresAt().getEpochSecond(),
            digest);
    execute(binding, password, claim::checkedClaim, claim::checkedRecoveryClaim);
  }

  private record Bound(
      String kind,
      UUID claim,
      long version,
      String account,
      Long tenant,
      String username,
      String emailDigest,
      String accountKind,
      String accountVersion,
      long expires,
      String digest) {}

  private record Intent(
      String operation,
      String target,
      String workflow,
      String local,
      String metadata,
      String envelope,
      String status,
      String receipt) {}

  private void execute(Bound b, String password, Supplier<?> fresh, Supplier<?> recovery) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    recovery.get();
    Intent row = transaction(() -> find(b, actor));
    if (row == null) {
      fresh.get();
      row = transaction(() -> prepare(b, actor));
    }
    if (Set.of("ACKNOWLEDGED", "VERIFIED").contains(row.status())) {
      verifySaved(b, actor, row);
      return;
    }
    if ("PREPARED".equals(row.status())) {
      fresh.get();
      JsonNode read = read(b, actor, row);
      String state = liveProjection(b, read);
      final Intent prepared = row;
      row = transaction(() -> freeze(b, actor, prepared, state, password));
    }
    if (!"FROZEN".equals(row.status())) throw unavailable();
    final Intent frozen = row;
    JsonNode body = transaction(() -> open(b, actor, frozen)).path("body");
    recovery.get();
    JsonNode response = read(b, actor, frozen);
    String verified;
    if (response.has("receipt")) {
      verified =
          codec.verifyMaintenancePasswordResetReceipt(
              codec.canonical(response), realmId, actor, body);
    } else {
      fresh.get();
      String state = liveProjection(b, response);
      if (!body.path("expectedState").textValue().equals(state)) throw unavailable();
      transaction(
          () -> {
            requireSameLocal(b, frozen);
            return null;
          });
      String reply = call(b, actor, body, false);
      verified = codec.verifyMaintenancePasswordResetReceipt(reply, realmId, actor, body);
    }
    recovery.get();
    final String receipt = verified;
    transaction(
        () -> {
          var current = find(b, actor);
          if (current == null || !Objects.equals(current.envelope(), frozen.envelope()))
            throw unavailable();
          if (Set.of("ACKNOWLEDGED", "VERIFIED").contains(current.status())) {
            if (!receipt.equals(current.receipt())) throw unavailable();
            return null;
          }
          if (!"FROZEN".equals(current.status())) throw unavailable();
          if ("PASSWORD_RESET".equals(b.kind())) {
            // A saved native outcome remains historical when the SQL incarnation has since changed.
            try {
              var local = localAccounts().lock(b.account());
              if (codec.canonical(local.state()).equals(frozen.local())
                  && local.consultantVersion() != null)
                jdbc.update(
                    "update consultant set password_change_required=false,update_date=? where consultant_id=?",
                    java.sql.Timestamp.from(clock.instant()),
                    b.account());
            } catch (ScopedIdentityCommandRequiredException stale) {
              // Recovery cannot change a deleted, moved or replaced local incarnation.
            }
          }
          int changed =
              jdbc.update(
                  "update identity_claimed_password_intent set status=?,receipt=?,updated_at=? where claim_id=? and claim_version=? and status='FROZEN'",
                  "PASSWORD_RESET".equals(b.kind()) ? "ACKNOWLEDGED" : "VERIFIED",
                  receipt,
                  java.sql.Timestamp.from(clock.instant()),
                  b.claim().toString(),
                  b.version());
          if (changed != 1) throw unavailable();
          return null;
        });
  }

  private Intent prepare(Bound b, TaskActorCredentials actor) {
    var existing = find(b, actor);
    if (existing != null) return existing;
    if (b.expires() <= clock.instant().getEpochSecond()) throw unavailable();
    var local = localAccounts().lock(b.account());
    checkLocal(b, local);
    String operation = UUID.randomUUID().toString();
    var target = codec.read("{}", ObjectNode.class);
    target.put("accountId", b.account());
    if (b.tenant() == null) target.putNull("tenantId");
    else target.put("tenantId", b.tenant().toString());
    var origin = target.putObject("origin");
    origin.put("kind", "PASSWORD_RESET").put("workflowId", b.claim().toString());
    for (String field : List.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority"))
      origin.putNull(field);
    var profile = profiles.get(local.kind()).getFirst();
    var workflow = codec.read("{}", ObjectNode.class);
    workflow
        .put("v", 1)
        .put("kind", b.kind())
        .put("claimId", b.claim().toString())
        .put("claimVersion", b.version())
        .put("authorityValidUntil", b.expires())
        .put("expectedUsername", b.username())
        .put("expectedEmailDigest", b.emailDigest())
        .put("profileKind", profile.kind())
        .put("profileId", profile.id())
        .put("profileVersion", profile.version());
    jdbc.update(
        "insert into identity_claimed_password_intent(claim_id,claim_version,operation_id,workflow_kind,realm_id,account_id,tenant_id,owner_client,owner_subject,request_digest,authority_valid_until,target_json,workflow_json,local_state_json,status,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED',?)",
        b.claim().toString(),
        b.version(),
        operation,
        b.kind(),
        realmId,
        b.account(),
        b.tenant(),
        actor.clientId(),
        actor.serviceSubject(),
        b.digest(),
        b.expires(),
        codec.canonical(target),
        codec.canonical(workflow),
        codec.canonical(local.state()),
        java.sql.Timestamp.from(clock.instant()));
    return find(b, actor);
  }

  private Intent find(Bound b, TaskActorCredentials actor) {
    var found =
        jdbc.query(
            "select * from identity_claimed_password_intent where claim_id=? and claim_version=? for update",
            (rs, index) -> {
              Long tenant = rs.getObject("tenant_id") == null ? null : rs.getLong("tenant_id");
              if (!b.kind().equals(rs.getString("workflow_kind"))
                  || !realmId.equals(rs.getString("realm_id"))
                  || !b.account().equals(rs.getString("account_id"))
                  || !Objects.equals(b.tenant(), tenant)
                  || !actor.clientId().equals(rs.getString("owner_client"))
                  || !actor.serviceSubject().equals(rs.getString("owner_subject"))
                  || !b.digest().equals(rs.getString("request_digest"))
                  || b.expires() != rs.getLong("authority_valid_until")) throw unavailable();
              return new Intent(
                  rs.getString("operation_id"),
                  rs.getString("target_json"),
                  rs.getString("workflow_json"),
                  rs.getString("local_state_json"),
                  rs.getString("metadata_json"),
                  rs.getString("intent_envelope"),
                  rs.getString("status"),
                  rs.getString("receipt"));
            },
            b.claim().toString(),
            b.version());
    if (found.size() > 1) throw unavailable();
    if (found.isEmpty()) return null;
    var row = found.getFirst();
    var workflow = codec.read(row.workflow(), JsonNode.class);
    var profile = profiles.get(b.accountKind()).getFirst();
    if (!b.username().equals(workflow.path("expectedUsername").textValue())
        || !b.emailDigest().equals(workflow.path("expectedEmailDigest").textValue())
        || !b.claim().toString().equals(workflow.path("claimId").textValue())
        || b.version() != workflow.path("claimVersion").longValue()
        || b.expires() != workflow.path("authorityValidUntil").longValue()
        || !b.kind().equals(workflow.path("kind").textValue())
        || workflow.path("v").intValue() != 1
        || !profile.kind().equals(workflow.path("profileKind").textValue())
        || !profile.id().equals(workflow.path("profileId").textValue())
        || profile.version() != workflow.path("profileVersion").longValue()) throw unavailable();
    return row;
  }

  private Intent freeze(
      Bound b, TaskActorCredentials actor, Intent prepared, String state, String password) {
    var row = find(b, actor);
    if (row == null) throw unavailable();
    if (!"PREPARED".equals(row.status())) return row;
    requireSameLocal(b, row);
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", codec.read(row.target(), JsonNode.class));
    body.set("workflow", codec.read(row.workflow(), JsonNode.class));
    body.put("operationId", row.operation()).put("expectedState", state);
    body.putObject("change").put("password", password).put("temporary", false);
    var metadata = metadata(b, actor, row, state);
    var wrapper = codec.read("{}", ObjectNode.class);
    wrapper.set("body", body);
    wrapper.set("localState", codec.read(row.local(), JsonNode.class));
    String envelope =
        codec.sealClaimedPasswordIntent(
            metadata, codec.canonical(wrapper).getBytes(StandardCharsets.UTF_8));
    if (jdbc.update(
            "update identity_claimed_password_intent set metadata_json=?,intent_envelope=?,status='FROZEN',updated_at=? where claim_id=? and claim_version=? and status='PREPARED'",
            codec.canonical(metadata),
            envelope,
            java.sql.Timestamp.from(clock.instant()),
            b.claim().toString(),
            b.version())
        != 1) throw unavailable();
    return find(b, actor);
  }

  private JsonNode open(Bound b, TaskActorCredentials actor, Intent row) {
    var saved = codec.read(row.metadata(), JsonNode.class);
    String state = saved.path("expectedState").textValue();
    var checked = metadata(b, actor, row, state);
    if (!codec.canonical(checked).equals(row.metadata())) throw unavailable();
    var wrapper =
        codec.read(
            codec.utf8(codec.openClaimedPasswordIntent(checked, row.envelope())), JsonNode.class);
    if (!codec.canonical(wrapper.path("localState")).equals(row.local())
        || !codec.canonical(wrapper.path("body").path("target")).equals(row.target())
        || !codec.canonical(wrapper.path("body").path("workflow")).equals(row.workflow()))
      throw unavailable();
    return wrapper;
  }

  private ObjectNode metadata(Bound b, TaskActorCredentials actor, Intent row, String state) {
    var value = codec.read("{}", ObjectNode.class);
    value
        .put("v", 1)
        .put("realmId", realmId)
        .put("operationId", row.operation())
        .put("command", "account.password-reset")
        .put("method", "POST")
        .put("route", "oriso-account-maintenance/v1/accounts/password-reset")
        .put("accountId", b.account());
    if (b.tenant() == null) value.putNull("tenantId");
    else value.put("tenantId", b.tenant());
    value
        .put("ownerClient", actor.clientId())
        .put("ownerSubject", actor.serviceSubject())
        .put(
            "originDigest",
            codec.digest(
                "maintenance-origin", codec.read(row.target(), JsonNode.class).path("origin")))
        .put("claimId", b.claim().toString())
        .put("claimVersion", b.version())
        .put("workflowKind", b.kind())
        .put(
            "workflowDigest",
            codec.digest("password-reset-workflow/v1", codec.read(row.workflow(), JsonNode.class)))
        .put("requestDigest", b.digest())
        .put("expectedState", state)
        .put("authorityValidUntil", b.expires())
        .put("representation", "FROZEN_CLAIMED_PASSWORD_HTTP_V1");
    return value;
  }

  private void verifySaved(Bound b, TaskActorCredentials actor, Intent row) {
    var body = transaction(() -> open(b, actor, row)).path("body");
    if (!row.receipt()
        .equals(codec.verifyMaintenancePasswordResetReceipt(row.receipt(), realmId, actor, body)))
      throw unavailable();
  }

  private JsonNode read(Bound b, TaskActorCredentials actor, Intent row) {
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", codec.read(row.target(), JsonNode.class));
    body.set("workflow", codec.read(row.workflow(), JsonNode.class));
    return codec.read(call(b, actor, body, true), JsonNode.class);
  }

  private String liveProjection(Bound b, JsonNode read) {
    var expected =
        Set.of(
            "id",
            "username",
            "email",
            "firstName",
            "lastName",
            "enabled",
            "emailVerified",
            "tenantId",
            "preferredLanguage",
            "effectiveRoles",
            "passwordChangeRequired",
            "state");
    var fields = new HashSet<String>();
    read.fieldNames().forEachRemaining(fields::add);
    if (!read.isObject()
        || !fields.equals(expected)
        || !b.account().equals(read.path("id").textValue())
        || !b.username().equalsIgnoreCase(read.path("username").textValue())
        || !b.emailDigest()
            .equals(codec.passwordResetRecipientDigest(read.path("email").textValue()))
        || !read.path("enabled").isBoolean()
        || !read.path("enabled").booleanValue()
        || !Objects.equals(
            b.tenant() == null ? null : b.tenant().toString(),
            read.path("tenantId").isNull() ? null : read.path("tenantId").textValue())
        || !read.path("passwordChangeRequired").isBoolean()
        || ("EXISTING_SETUP".equals(b.kind())
            && !read.path("passwordChangeRequired").booleanValue())
        || !read.path("state").isTextual()
        || !read.path("state").textValue().matches("[0-9a-f]{64}")) throw unavailable();
    if (b.expires() <= clock.instant().getEpochSecond()) throw unavailable();
    return read.path("state").textValue();
  }

  private String call(Bound b, TaskActorCredentials actor, JsonNode body, boolean read) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || (!read && b.expires() <= clock.instant().getEpochSecond())) throw unavailable();
    var machine = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    long now = clock.instant().getEpochSecond();
    if (machine == null
        || machine.actor() != TaskActor.ACCOUNT_ADMIN
        || machine.purpose() != TaskPurpose.ACCOUNT_MAINTENANCE
        || !actor.clientId().equals(machine.clientId())
        || !actor.serviceSubject().equals(machine.subject())
        || !publicIssuer.equals(machine.issuer())
        || machine.validatedJwtIssuedAt() <= 0
        || machine.validatedJwtIssuedAt() > now
        || machine.validatedJwtExpiresAt() <= now
        || machine.validatedJwtExpiresAt() <= machine.validatedJwtIssuedAt()) throw unavailable();
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(machine.accessToken());
    headers.set(
        "X-ORISO-Task-Context",
        read
            ? codec.passwordResetReadProof(realmName, realmId, b.account(), actor, body)
            : codec.passwordResetMutationProof(realmName, realmId, b.account(), actor, body));
    String url =
        UriComponentsBuilder.fromUriString(authServer)
            .pathSegment(
                "realms",
                realmName,
                "oriso-account-maintenance",
                "v1",
                "accounts",
                b.account(),
                "password-reset")
            .toUriString();
    if (read) url += "/read";
    try {
      return http.postForObject(
          url, new HttpEntity<>(codec.canonical(body), headers), String.class);
    } catch (HttpStatusCodeException unknown) {
      throw unavailable();
    }
  }

  private void checkLocal(Bound b, ClaimedPasswordLocalAccounts.Owned local) {
    if (!b.accountKind().equals(local.kind())
        || !Objects.equals(b.tenant(), local.tenant())
        || !b.username().equals(local.username())
        || !b.emailDigest().equals(codec.passwordResetRecipientDigest(local.email()))
        || (b.accountVersion() != null && !b.accountVersion().equals(local.version())))
      throw unavailable();
  }

  private void requireSameLocal(Bound b, Intent row) {
    if (b.expires() <= clock.instant().getEpochSecond()) throw unavailable();
    var current = localAccounts().lock(b.account());
    checkLocal(b, current);
    if (!codec.canonical(current.state()).equals(row.local())) throw unavailable();
  }

  private ClaimedPasswordLocalAccounts localAccounts() {
    return new ClaimedPasswordLocalAccounts(jdbc, codec);
  }

  private <T> T transaction(Supplier<T> action) {
    var template = new TransactionTemplate(transactions);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    return template.execute(
        status -> {
          if (!TransactionSynchronizationManager.isActualTransactionActive()
              || jdbc.getDataSource() == null
              || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw unavailable();
          return action.get();
        });
  }

  private static ScopedIdentityCommandRequiredException unavailable() {
    return new ScopedIdentityCommandRequiredException();
  }
}
