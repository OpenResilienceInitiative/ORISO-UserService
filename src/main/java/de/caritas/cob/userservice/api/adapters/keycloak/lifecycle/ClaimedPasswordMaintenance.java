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

  /** Retention maintenance only; it cannot issue, rebind or acknowledge any native command. */
  public int expireSecretCustody() {
    requireSupport();
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    return transaction(
        () -> {
          long cutoff = clock.instant().minusSeconds(7 * 86400L).getEpochSecond();
          var rows =
              jdbc.query(
                  "select claim_id,claim_version,status from identity_claimed_password_intent where intent_envelope is not null and authority_valid_until<=? order by authority_valid_until,claim_id,claim_version limit 100 for update",
                  (rs, index) ->
                      new Object[] {
                        rs.getString("claim_id"),
                        rs.getLong("claim_version"),
                        rs.getString("status")
                      },
                  cutoff);
          int erased = 0;
          for (var row : rows) {
            String terminal =
                Set.of("ACKNOWLEDGED", "VERIFIED", "REJECTED", "REFUSED").contains((String) row[2])
                    ? "EXPIRED"
                    : "EXPIRED_UNKNOWN";
            erased +=
                jdbc.update(
                    "update identity_claimed_password_intent set intent_envelope=null,status=?,updated_at=? where claim_id=? and claim_version=? and intent_envelope is not null and authority_valid_until<=?",
                    terminal,
                    java.sql.Timestamp.from(clock.instant()),
                    row[0],
                    row[1],
                    cutoff);
          }
          return erased;
        });
  }

  /**
   * Only this adapter issues a refusal after strict proof verification and a committed tombstone.
   */
  public static final class Refusal extends RuntimeException {
    private final Object handle;
    private final UUID operationId;
    private final int status;

    private Refusal(Object handle, AccountLifecycleCodec.VerifiedMaintenanceNoEffect proof) {
      super("Verified password command refused");
      if (!(handle instanceof ExistingAccountSetupService.ClaimedSetup)
          && !(handle instanceof PasswordResetService.ClaimedReset)) throw unavailable();
      if (!Set.of(403, 409).contains(proof.status()) || !"password-reset".equals(proof.command()))
        throw unavailable();
      this.handle = handle;
      this.operationId = proof.operationId();
      this.status = proof.status();
    }

    public boolean matches(ExistingAccountSetupService.ClaimedSetup candidate) {
      if (candidate != handle) return false;
      candidate.checkedRecoveryClaim();
      return true;
    }

    public boolean matches(PasswordResetService.ClaimedReset candidate) {
      if (candidate != handle) return false;
      candidate.checkedRecoveryClaim();
      return true;
    }

    public UUID operationId() {
      return operationId;
    }

    public int status() {
      return status;
    }

    public RuntimeException publicResponse() {
      return status == 403
          ? new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException(
              "Password command refused")
          : new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
              "Password command refused");
    }

    @Override
    public String toString() {
      return "ClaimedPasswordRefusal[redacted]";
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
              || (!accountKind.equals(local.kind())
                  && !("CONSULTANT".equals(accountKind)
                      && Set.of("AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN")
                          .contains(local.kind())
                      && local.consultantVersion() != null))
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
    String digest = codec.existingSetupPasswordRequestDigest(checked.binding(), password);
    if (!digest.equals(checked.requestDigest())) throw unavailable();
    var binding = existingSetupBinding(checked);
    execute(binding, password, claim::checkedClaim, claim::checkedRecoveryClaim, claim);
  }

  /** Same owned SQL transaction only; a verified old effect cannot complete a newer incarnation. */
  public boolean requireSetupCompletionWithinOwner(ExistingAccountSetupService.ClaimedSetup claim) {
    requireSupport();
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || jdbc.getDataSource() == null
        || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
      throw unavailable();
    var b = existingSetupBinding(claim.checkedRecoveryClaim());
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var row = find(b, actor);
    if (row == null || !"VERIFIED".equals(row.status()) || row.receipt() == null)
      throw unavailable();
    var body = open(b, actor, row).path("body");
    if (!row.receipt()
        .equals(codec.verifyMaintenancePasswordResetReceipt(row.receipt(), realmId, actor, body)))
      throw unavailable();
    var local = localAccounts().lock(b.account());
    checkLocal(b, local);
    if (!codec.canonical(local.state()).equals(row.local())) throw unavailable();
    return local.consultantVersion() != null;
  }

  private Bound existingSetupBinding(ExistingSetupPasswordClaims.Claim checked) {
    var b = checked.binding();
    String kind =
        switch (b.role()) {
          case COUNSELLOR -> "CONSULTANT";
          case AGENCY_ADMIN -> "AGENCY_ADMIN";
          case TENANT_ADMIN -> "TENANT_ADMIN";
          default -> throw unavailable();
        };
    return new Bound(
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
        checked.requestDigest());
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
    execute(binding, password, claim::checkedClaim, claim::checkedRecoveryClaim, claim);
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
      String intentDigest,
      String status,
      String receipt) {}

  private void execute(
      Bound b, String password, Supplier<?> fresh, Supplier<?> recovery, Object handle) {
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
    if (Set.of("REJECTED", "REFUSED").contains(row.status())) {
      final Intent rejected = row;
      var body = transaction(() -> open(b, actor, rejected)).path("body");
      policy(b, actor, rejected, body, codec.read(rejected.receipt(), JsonNode.class), handle);
    }
    if ("PREPARED".equals(row.status())) {
      fresh.get();
      var selected = select(b, actor, row, fresh);
      final Intent prepared = row;
      row = transaction(() -> freeze(b, actor, prepared, selected, password));
    }
    if (!"FROZEN".equals(row.status())) throw unavailable();
    final Intent frozen = row;
    JsonNode body = transaction(() -> open(b, actor, frozen)).path("body");
    recovery.get();
    JsonNode response = read(b, actor, frozen);
    String verified;
    if (response.has("receipt")) {
      if ("NO_EFFECT".equals(response.path("result").textValue()))
        policy(b, actor, frozen, body, response, handle);
      verified =
          codec.verifyMaintenancePasswordResetReceipt(
              codec.canonical(response), realmId, actor, body);
    } else {
      fresh.get();
      String state = liveProjection(b, response, frozen);
      if (!body.path("expectedState").textValue().equals(state)) throw unavailable();
      transaction(
          () -> {
            requireSameLocal(b, frozen);
            return null;
          });
      String reply = call(b, actor, body, false);
      var result = codec.read(reply, JsonNode.class);
      if ("NO_EFFECT".equals(result.path("result").textValue()))
        policy(b, actor, frozen, body, result, handle);
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

  private void policy(
      Bound b,
      TaskActorCredentials actor,
      Intent frozen,
      JsonNode body,
      JsonNode result,
      Object handle) {
    String reply = codec.canonical(result);
    var proof =
        codec.verifyMaintenanceNoEffectReceipt(reply, realmId, actor, body, "password-reset");
    String terminal = proof.status() == 400 ? "REJECTED" : "REFUSED";
    transaction(
        () -> {
          var current = find(b, actor);
          if (current == null
              || !Objects.equals(current.envelope(), frozen.envelope())
              || !Objects.equals(current.metadata(), frozen.metadata())) throw unavailable();
          if (terminal.equals(current.status())) {
            if (!reply.equals(current.receipt())) throw unavailable();
            return null;
          }
          if (!"FROZEN".equals(current.status())) throw unavailable();
          if (jdbc.update(
                  "update identity_claimed_password_intent set status=?,receipt=?,updated_at=? where claim_id=? and claim_version=? and status='FROZEN'",
                  terminal,
                  reply,
                  java.sql.Timestamp.from(clock.instant()),
                  b.claim().toString(),
                  b.version())
              != 1) throw unavailable();
          return null;
        });
    if (proof.status() != 400) throw new Refusal(handle, proof);
    throw new de.caritas.cob.userservice.api.exception.httpresponses
        .CustomValidationHttpStatusException(
        de.caritas.cob.userservice.api.exception.httpresponses.customheader
            .HttpStatusExceptionReason.PASSWORD_NOT_VALID);
  }

  private Intent prepare(Bound b, TaskActorCredentials actor) {
    var existing = find(b, actor);
    if (existing != null) return existing;
    if (b.expires() <= clock.instant().getEpochSecond()) throw unavailable();
    var local = localAccounts().lock(b.account());
    checkLocal(b, local);
    String operation = UUID.randomUUID().toString();
    var target = target(b);
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
        null,
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
                  rs.getString("intent_digest"),
                  rs.getString("status"),
                  rs.getString("receipt"));
            },
            b.claim().toString(),
            b.version());
    if (found.size() > 1) throw unavailable();
    if (found.isEmpty()) return null;
    var row = found.getFirst();
    if (!codec.canonical(target(b)).equals(row.target())) throw unavailable();
    if ("PREPARED".equals(row.status())) {
      if (row.workflow() != null
          || row.envelope() != null
          || row.metadata() != null
          || row.intentDigest() != null
          || row.receipt() != null) throw unavailable();
      return row;
    }
    var workflow = codec.read(row.workflow(), JsonNode.class);
    var profile =
        new ProfileSelector(
            workflow.path("profileKind").textValue(),
            workflow.path("profileId").textValue(),
            workflow.path("profileVersion").longValue());
    if (!profiles.get(b.accountKind()).contains(profile)
        || !codec.canonical(workflow(b, profile)).equals(row.workflow())
        || row.intentDigest() == null
        || !row.intentDigest().matches("[0-9a-f]{64}")) throw unavailable();
    return row;
  }

  private Intent freeze(
      Bound b, TaskActorCredentials actor, Intent prepared, Selected selected, String password) {
    var row = find(b, actor);
    if (row == null) throw unavailable();
    if (!"PREPARED".equals(row.status())) return row;
    requireSameLocal(b, row);
    if (!row.operation().equals(prepared.operation()) || row.workflow() != null)
      throw unavailable();
    var checked =
        new Intent(
            row.operation(),
            row.target(),
            codec.canonical(selected.workflow()),
            row.local(),
            null,
            null,
            null,
            row.status(),
            null);
    String state = selected.state();
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", codec.read(row.target(), JsonNode.class));
    body.set("workflow", selected.workflow());
    body.put("operationId", row.operation()).put("expectedState", state);
    body.putObject("change").put("password", password).put("temporary", false);
    var metadata = metadata(b, actor, checked, state);
    var wrapper = codec.read("{}", ObjectNode.class);
    wrapper.set("body", body);
    wrapper.set("localState", codec.read(row.local(), JsonNode.class));
    String envelope =
        codec.sealClaimedPasswordIntent(
            metadata, codec.canonical(wrapper).getBytes(StandardCharsets.UTF_8));
    if (jdbc.update(
            "update identity_claimed_password_intent set workflow_json=?,metadata_json=?,intent_envelope=?,intent_digest=?,status='FROZEN',updated_at=? where claim_id=? and claim_version=? and status='PREPARED'",
            checked.workflow(),
            codec.canonical(metadata),
            envelope,
            codec.digest("maintenance-intent", body),
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
    if (!codec.digest("maintenance-intent", wrapper.path("body")).equals(row.intentDigest())
        || !codec.canonical(wrapper.path("localState")).equals(row.local())
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

  private record Selected(ObjectNode workflow, String state) {}

  private Selected select(Bound b, TaskActorCredentials actor, Intent row, Supplier<?> fresh) {
    Selected selected = null;
    for (var candidate : profiles.get(b.accountKind())) {
      fresh.get();
      var workflow = workflow(b, candidate);
      var body = codec.read("{}", ObjectNode.class);
      body.set("target", codec.read(row.target(), JsonNode.class));
      body.set("workflow", workflow);
      JsonNode response;
      try {
        response = codec.read(call(b, actor, body, true), JsonNode.class);
      } catch (ReadRefused noAuthority) {
        continue;
      }
      String state = liveProjection(b, response, row);
      if (selected != null) throw unavailable();
      selected = new Selected(workflow, state);
    }
    if (selected == null) throw unavailable();
    return selected;
  }

  private ObjectNode target(Bound b) {
    var value = codec.read("{}", ObjectNode.class);
    value.put("accountId", b.account());
    if (b.tenant() == null) value.putNull("tenantId");
    else value.put("tenantId", b.tenant().toString());
    var origin = value.putObject("origin");
    origin.put("kind", "PASSWORD_RESET").put("workflowId", b.claim().toString());
    for (String field : List.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority"))
      origin.putNull(field);
    return value;
  }

  private ObjectNode workflow(Bound b, ProfileSelector profile) {
    var value = codec.read("{}", ObjectNode.class);
    value
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
    return value;
  }

  private static final class ReadRefused extends RuntimeException {}

  private JsonNode read(Bound b, TaskActorCredentials actor, Intent row) {
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", codec.read(row.target(), JsonNode.class));
    body.set("workflow", codec.read(row.workflow(), JsonNode.class));
    return codec.read(call(b, actor, body, true), JsonNode.class);
  }

  private String liveProjection(Bound b, JsonNode read, Intent row) {
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
    if (!read.path("emailVerified").isBoolean()
        || !read.path("effectiveRoles").isArray()
        || read.path("effectiveRoles").size() > 64) throw unavailable();
    var roles = new HashSet<String>();
    for (var role : read.path("effectiveRoles")) {
      if (!role.isTextual()
          || role.textValue().isBlank()
          || role.textValue().length() > 255
          || role.textValue().chars().anyMatch(c -> c < 32 || c == 127)
          || !roles.add(role.textValue())) throw unavailable();
    }
    if (!Set.of("TENANT_ADMIN", "PLATFORM_ADMIN").contains(b.accountKind())) {
      String expectedKind =
          switch (b.accountKind()) {
            case "USER" -> "ASKER";
            case "CONSULTANT" -> "CONSULTANT";
            case "AGENCY_ADMIN" ->
                codec.read(row.local(), JsonNode.class).path("consultantVersion").isNull()
                    ? "AGENCY_ADMIN"
                    : "CONSULTANT_AGENCY_ADMIN";
            default -> throw unavailable();
          };
      if (!expectedKind.equals(OrdinaryAccountCreation.kind(roles))) throw unavailable();
    }
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
      var response =
          http.postForEntity(url, new HttpEntity<>(codec.canonical(body), headers), String.class);
      if (response.getStatusCode().value() != 200) throw unavailable();
      return checkedReply(response.getBody());
    } catch (HttpStatusCodeException unknown) {
      int status = unknown.getStatusCode().value();
      if (Set.of(400, 403, 409).contains(status)) {
        String reply = unknown.getResponseBodyAsString();
        if (reply != null && !reply.isBlank() && reply.length() <= 32768) {
          JsonNode value = codec.read(reply, JsonNode.class);
          // This is transport data only. The fixed Root verifier checks the exact frozen command.
          if ("NO_EFFECT".equals(value.path("result").textValue())
              && value.path("status").isIntegralNumber()
              && value.path("status").canConvertToInt()
              && value.path("status").intValue() == status) return reply;
        }
      }
      if (read && status == 403) throw new ReadRefused();
      throw unavailable();
    }
  }

  private static String checkedReply(String reply) {
    if (reply == null || reply.isBlank() || reply.length() > 32768) throw unavailable();
    return reply;
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
