package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Owned ordinary SELF app enrollment and removal; runtime registration remains separate. */
@RequiredArgsConstructor
public final class SelfAppOtpMaintenance {
  private final @NonNull IdentityClientConfig mode;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull HttpServletRequest request;
  private final @NonNull JdbcTemplate jdbc;
  private final @NonNull PlatformTransactionManager transactions;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull RestTemplate http;
  private final @NonNull Clock clock;
  private final @NonNull String authServer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;

  public boolean isFiveActorMode() {
    return mode.isFiveActorMode();
  }

  public boolean startEmail(String pendingEmail) {
    return execute(null, null, pendingEmail, () -> {}, Operation.EMAIL_START);
  }

  public boolean finishEmail(String code, Consumer<String> applyEmailAndInvite) {
    return execute(code, null, null, () -> {}, Operation.EMAIL_FINISH, applyEmailAndInvite);
  }

  public boolean delete(Runnable markPending) {
    return execute(null, null, null, markPending, Operation.DELETE);
  }

  public boolean setup(String initialCode, String secret, Runnable markActive) {
    return execute(initialCode, secret, null, markActive, Operation.APP);
  }

  private boolean execute(
      String initialCode, String secret, String pendingEmail, Runnable markActive, Operation kind) {
    return execute(initialCode, secret, pendingEmail, markActive, kind, null);
  }

  private boolean execute(
      String initialCode,
      String secret,
      String pendingEmail,
      Runnable markActive,
      Operation kind,
      Consumer<String> applyEmailAndInvite) {
    if (!mode.isFiveActorMode() || TransactionSynchronizationManager.isActualTransactionActive())
      throw unavailable();
    if ((kind == Operation.APP && (!text(initialCode, 128) || !text(secret, 4096)))
        || (kind == Operation.EMAIL_START && !text(pendingEmail, 320))
        || (kind == Operation.EMAIL_FINISH
            && (!text(initialCode, 8)
                || !initialCode.matches("[0-9]{6,8}")
                || applyEmailAndInvite == null))
        || markActive == null)
      throw new BadRequestException("A complete app OTP request is required");
    var self = actualSelf(kind);
    String operation = operationId();
    var actor = actors.require(TaskPurpose.OTP);
    if (!canonicalUuid(actor.serviceSubject())) throw unavailable();
    String requestDigest =
        codec.digest(
            "oriso/user-maintenance-intent/request/v1",
            kind == Operation.DELETE
                ? Map.of("operation", "otp.delete")
                : kind == Operation.EMAIL_START
                    ? Map.of("email", pendingEmail)
                    : kind == Operation.EMAIL_FINISH
                        ? Map.of("otp", initialCode)
                        : Map.of("otp", initialCode, "secret", secret));
    // Check actual local ownership before even the token HTTP call, outside every RPC lock.
    CheckedIntent checked =
        transaction(() -> findChecked(self, operation, requestDigest, actor, kind));
    if (checked == null) {
      // Only a native-validated original grant can set the immutable first lifetime minimum.
      var originalMachine = machine(self, actor, TaskPurpose.OTP);
      long originalExpiry =
          Math.min(
              Math.min(self.expires, originalMachine.validatedJwtExpiresAt()),
              clock.instant().getEpochSecond() + 600);
      try {
        checked =
            transaction(() -> prepare(self, operation, requestDigest, actor, originalExpiry, kind));
      } catch (RuntimeException uncertain) {
        checked = transaction(() -> findChecked(self, operation, requestDigest, actor, kind));
        if (checked == null) throw unavailable();
      }
    }
    if (terminal(checked.row.status)) return "ACKNOWLEDGED".equals(checked.row.status);
    if ("PREPARED".equals(checked.row.status)) {
      var projection = read(self, checked.row, actor);
      try {
        checked =
            transaction(
                () ->
                    freeze(
                        self,
                        operation,
                        requestDigest,
                        actor,
                        projection,
                        initialCode,
                        secret,
                        pendingEmail,
                        kind));
      } catch (RuntimeException uncertain) {
        checked = transaction(() -> findChecked(self, operation, requestDigest, actor, kind));
        if (checked == null
            || !Set.of("FROZEN", "ACKNOWLEDGED", "TERMINAL_REFUSAL").contains(checked.row.status))
          throw unavailable();
      }
    }
    if (terminal(checked.row.status)) return "ACKNOWLEDGED".equals(checked.row.status);
    // This capability comes only from current locked owned data, never from envelope parsing.
    checked = transaction(() -> findChecked(self, operation, requestDigest, actor, kind));
    if (checked == null || !"FROZEN".equals(checked.row.status)) throw unavailable();
    final CheckedIntent frozen = checked;
    final JsonNode command;
    final AccountLifecycleCodec.VerifiedSelfAppOutcome outcome;
    try {
      command =
          codec.read(
              codec.utf8(codec.openSelfAppIntent(frozen.metadata, frozen.row.envelope)),
              JsonNode.class);
      var delivered =
          kind == Operation.EMAIL_FINISH ? transaction(() -> deliveredEmail(self, actor)) : null;
      if (delivered != null
          && !delivered.operation.equals(
              command.path("body").path("emailStartOperationId").textValue())) throw unavailable();
      var reply = invoke(self, frozen.row, actor, command, kind);
      if (kind == Operation.EMAIL_FINISH) {
        outcome =
            codec.verifySelfLinkedEmailFinishOtpReceipt(
                reply.body, realmId, actor, command, delivered.outcome);
        if (outcome.status() != reply.status) throw unavailable();
      } else if (kind == Operation.EMAIL_START) {
        var first = codec.verifySelfEmailStartOtpReceipt(reply.body, realmId, actor, command);
        if (first.status() != reply.status) throw unavailable();
        if (first.status() == 202) {
          var completed = invoke(self, frozen.row, actor, command, kind);
          outcome = codec.verifySelfEmailStartOtpReceipt(completed.body, realmId, actor, command);
          if (completed.status != 200
              || outcome.status() != 200
              || !first.sameEmailDelivery(outcome)) throw unavailable();
        } else outcome = first;
        if (outcome.status() != 200) throw unavailable();
      } else {
        outcome =
            kind == Operation.DELETE
                ? codec.verifySelfDeleteOtpReceipt(reply.body, realmId, actor, command)
                : codec.verifySelfAppOtpReceipt(reply.body, realmId, actor, command);
        if (outcome.status() != reply.status) throw unavailable();
      }
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
    try {
      return transaction(
          () ->
              acknowledge(
                  self,
                  operation,
                  requestDigest,
                  actor,
                  frozen.row.envelope,
                  outcome,
                  markActive,
                  kind,
                  applyEmailAndInvite));
    } catch (RuntimeException uncertain) {
      // A fresh durable row is the only commit witness. Do not rerun the domain callback here.
      var resolved = transaction(() -> findChecked(self, operation, requestDigest, actor, kind));
      if (resolved == null
          || !terminal(resolved.row.status)
          || !Objects.equals(outcome.receipt(), resolved.row.receipt)
          || !Objects.equals(outcome.status(), resolved.row.nativeStatus)) throw unavailable();
      return "ACKNOWLEDGED".equals(resolved.row.status);
    }
  }

  private CheckedIntent prepare(
      Self self,
      String operation,
      String digest,
      TaskActorCredentials actor,
      long expiry,
      Operation kind) {
    lockedDomain(self);
    var prior = find(operation);
    if (prior != null) return checked(self, prior, digest, actor, kind);
    if (expiry <= clock.instant().getEpochSecond()) throw unavailable();
    jdbc.update(
        "insert into identity_self_app_otp_intent(operation_id,realm_id,realm_name,account_id,tenant_id,owner_client,owner_subject,request_digest,target_json,status,authority_valid_until,pending_marker,updated_at) values(?,?,?,?,?,?,?,?,?,'PREPARED',?,false,?)",
        operation,
        realmId,
        realmName,
        self.account,
        self.tenant,
        actor.clientId(),
        actor.serviceSubject(),
        digest,
        codec.canonical(target(self, operation)),
        expiry,
        now());
    return checked(self, Objects.requireNonNull(find(operation)), digest, actor, kind);
  }

  private CheckedIntent freeze(
      Self self,
      String operation,
      String digest,
      TaskActorCredentials actor,
      JsonNode projection,
      String code,
      String secret,
      String pendingEmail,
      Operation kind) {
    var current = findChecked(self, operation, digest, actor, kind);
    if (current == null) throw unavailable();
    if (!"PREPARED".equals(current.row.status)) return current;
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", codec.read(current.row.target, JsonNode.class));
    body.put("operationId", operation);
    body.put("expectedState", projection.path("state").textValue());
    if (kind == Operation.EMAIL_FINISH) {
      var delivered = deliveredEmail(self, actor);
      body.put("emailStartOperationId", delivered.operation);
    }
    if (kind == Operation.DELETE) body.putNull("otp");
    else {
      var otp = body.putObject("otp");
      otp.put("initialCode", code);
      otp.put("secret", secret);
      if (kind == Operation.EMAIL_START) {
        otp.put("email", pendingEmail);
        body.put("authorityValidUntil", current.row.expiry);
      } else otp.putNull("email");
    }
    var command = codec.read("{}", ObjectNode.class);
    command.put("username", projection.path("username").textValue());
    command.set("body", body);
    command.set("localState", localState(self));
    var metadata = metadata(current.row, projection.path("state").textValue(), kind);
    String envelope =
        codec.sealSelfAppIntent(
            metadata, codec.canonical(command).getBytes(StandardCharsets.UTF_8));
    // The private pending outcome marker and dispatchable bytes have one actual DB commit.
    if (jdbc.update(
            "update identity_self_app_otp_intent set metadata_json=?,envelope=?,pending_marker=true,status='FROZEN',updated_at=? where operation_id=? and status='PREPARED' and pending_marker=false",
            codec.canonical(metadata),
            envelope,
            now(),
            operation)
        != 1) throw unavailable();
    return checked(self, Objects.requireNonNull(find(operation)), digest, actor, kind);
  }

  private boolean acknowledge(
      Self self,
      String operation,
      String digest,
      TaskActorCredentials actor,
      String envelope,
      AccountLifecycleCodec.VerifiedSelfAppOutcome outcome,
      Runnable markActive,
      Operation kind,
      Consumer<String> applyEmailAndInvite) {
    var current = findChecked(self, operation, digest, actor, kind);
    if (current == null || !Objects.equals(envelope, current.row.envelope)) throw unavailable();
    if (terminal(current.row.status)) {
      if (!Objects.equals(outcome.receipt(), current.row.receipt)
          || !Objects.equals(outcome.status(), current.row.nativeStatus)) throw unavailable();
      return "ACKNOWLEDGED".equals(current.row.status);
    }
    if (!"FROZEN".equals(current.row.status)) throw unavailable();
    boolean success = outcome.status() == 200 || outcome.status() == 201;
    if (success) {
      var command =
          codec.read(
              codec.utf8(codec.openSelfAppIntent(current.metadata, current.row.envelope)),
              JsonNode.class);
      var nowLocal = localState(self);
      boolean apply =
          command.has("localState")
              ? codec.canonical(command.path("localState")).equals(codec.canonical(nowLocal))
              : legacyLocalEffect(kind, command, nowLocal);
      // A historical native ACK cannot reapply an older local enrollment or pointer.
      if (apply) {
        if (kind == Operation.EMAIL_START) {
          var pending =
              jdbc.queryForList(
                  "select start_operation_id from identity_self_otp_pending_email where account_id=? for update",
                  String.class,
                  self.account);
          if (pending.size() > 1) throw unavailable();
          if (pending.isEmpty())
            jdbc.update(
                "insert into identity_self_otp_pending_email(account_id,start_operation_id) values(?,?)",
                self.account,
                operation);
          else if (!operation.equals(pending.getFirst()))
            throw new ConflictException("A different email setup is already owned");
        } else if (kind == Operation.EMAIL_FINISH) {
          var delivered = deliveredEmail(self, actor);
          if (!outcome.belongsToEmailStart(delivered.operation)
              || !outcome.sameEmailIdentity(delivered.outcome)
              || applyEmailAndInvite == null) throw unavailable();
          applyEmailAndInvite.accept(delivered.email);
          clearPending(self, delivered.operation);
          appliedEnrollment(self, operation);
        } else if (kind == Operation.DELETE) {
          markActive.run();
          var pending = command.path("localState").path("pendingEmailStartOperationId");
          if (!pending.isNull()) clearPending(self, pending.textValue());
        } else {
          markActive.run();
          appliedEnrollment(self, operation);
        }
      }
    }
    if (jdbc.update(
            "update identity_self_app_otp_intent set status=?,receipt=?,native_status=?,updated_at=? where operation_id=? and status='FROZEN'",
            success ? "ACKNOWLEDGED" : "TERMINAL_REFUSAL",
            outcome.receipt(),
            outcome.status(),
            now(),
            operation)
        != 1) throw unavailable();
    return success;
  }

  private JsonNode localState(Self self) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    var value = codec.read("{}", ObjectNode.class);
    value.put("v", 1);
    var enrollment =
        jdbc.queryForList(
            "select operation_id from identity_self_otp_local_enrollment where account_id=? for update",
            String.class,
            self.account);
    var pending =
        jdbc.queryForList(
            "select start_operation_id from identity_self_otp_pending_email where account_id=? for update",
            String.class,
            self.account);
    if (enrollment.size() > 1 || pending.size() > 1) throw unavailable();
    if (enrollment.isEmpty()) value.putNull("enrollmentOperationId");
    else value.put("enrollmentOperationId", enrollment.getFirst());
    if (pending.isEmpty()) value.putNull("pendingEmailStartOperationId");
    else value.put("pendingEmailStartOperationId", pending.getFirst());
    var invites = value.putArray("invites");
    var rows =
        jdbc.queryForList(
            "select id,two_factor_status from account_invite where accepted_by_user_id=? order by id for update",
            self.account);
    if (rows.size() > 1024) throw unavailable();
    for (var row : rows) {
      var invite = invites.addObject();
      invite.put("id", ((Number) row.get("id")).toString());
      invite.put("status", (String) row.get("two_factor_status"));
    }
    return value;
  }

  private boolean legacyLocalEffect(Operation kind, JsonNode command, JsonNode current) {
    if (kind == Operation.DELETE || !current.path("enrollmentOperationId").isNull()) return false;
    var pending = current.path("pendingEmailStartOperationId");
    return pending.isNull()
        || (kind == Operation.EMAIL_FINISH
            && pending
                .asText()
                .equals(command.path("body").path("emailStartOperationId").asText()));
  }

  private void clearPending(Self self, String original) {
    if (!canonicalUuid(original)
        || jdbc.update(
                "delete from identity_self_otp_pending_email where account_id=? and start_operation_id=?",
                self.account,
                original)
            != 1) throw unavailable();
  }

  private void appliedEnrollment(Self self, String operation) {
    if (jdbc.update(
            "update identity_self_otp_local_enrollment set operation_id=? where account_id=?",
            operation,
            self.account)
        == 0)
      jdbc.update(
          "insert into identity_self_otp_local_enrollment(account_id,operation_id) values(?,?)",
          self.account,
          operation);
  }

  private CheckedIntent findChecked(
      Self self, String operation, String digest, TaskActorCredentials actor, Operation kind) {
    lockedDomain(self);
    var row = find(operation);
    return row == null ? null : checked(self, row, digest, actor, kind);
  }

  private CheckedIntent checked(
      Self self, Intent row, String digest, TaskActorCredentials actor, Operation kind) {
    if (!realmId.equals(row.realm)
        || !realmName.equals(row.realmName)
        || !self.account.equals(row.account)
        || !Objects.equals(self.tenant, row.tenant)
        || !actor.clientId().equals(row.ownerClient)
        || !actor.serviceSubject().equals(row.ownerSubject)
        || !digest.equals(row.requestDigest)
        || !codec.canonical(target(self, row.operation)).equals(row.target))
      throw new ConflictException("The app OTP operation does not match its original request");
    // A terminal result is owned historical data; expiry never grants another native effect.
    if (row.expiry <= clock.instant().getEpochSecond()
        && !terminal(row.status)
        && !(kind == Operation.EMAIL_START && "FROZEN".equals(row.status))) throw unavailable();
    JsonNode metadata = null;
    if ("PREPARED".equals(row.status)) {
      if (row.pending
          || row.metadata != null
          || row.envelope != null
          || row.receipt != null
          || row.nativeStatus != null) throw unavailable();
    } else if (Set.of("FROZEN", "ACKNOWLEDGED", "TERMINAL_REFUSAL").contains(row.status)) {
      if (!row.pending || row.metadata == null || row.envelope == null) throw unavailable();
      metadata = codec.read(row.metadata, JsonNode.class);
      String state = metadata.path("expectedState").textValue();
      if (state == null
          || !state.matches("[a-f0-9]{64}")
          || !codec.canonical(metadata(row, state, kind)).equals(row.metadata)
          || !row.metadata.equals(codec.canonical(metadata))) throw unavailable();
      if ("FROZEN".equals(row.status)) {
        if (row.receipt != null || row.nativeStatus != null) throw unavailable();
      } else if (row.receipt == null
          || row.nativeStatus == null
          || ("ACKNOWLEDGED".equals(row.status)
              ? !(kind == Operation.APP || kind == Operation.EMAIL_FINISH
                      ? Set.of(200, 201)
                      : Set.of(200))
                  .contains(row.nativeStatus)
              : kind != Operation.APP && kind != Operation.EMAIL_FINISH || row.nativeStatus != 401))
        throw unavailable();
    } else throw unavailable();
    return new CheckedIntent(row, metadata);
  }

  private DeliveredEmail deliveredEmail(Self self, TaskActorCredentials actor) {
    lockedDomain(self);
    var pending =
        jdbc.queryForList(
            "select start_operation_id from identity_self_otp_pending_email where account_id=? for update",
            String.class,
            self.account);
    if (pending.size() != 1 || !canonicalUuid(pending.getFirst())) throw unavailable();
    var start = find(pending.getFirst());
    if (start == null
        || !"ACKNOWLEDGED".equals(start.status)
        || !Integer.valueOf(200).equals(start.nativeStatus)) throw unavailable();
    var checked = checked(self, start, start.requestDigest, actor, Operation.EMAIL_START);
    var command =
        codec.read(
            codec.utf8(codec.openSelfAppIntent(checked.metadata, checked.row.envelope)),
            JsonNode.class);
    String email = command.path("body").path("otp").path("email").textValue();
    if (!text(email, 320)) throw unavailable();
    var result = codec.read("{}", ObjectNode.class);
    result.put("operationId", start.operation);
    result.put("accountId", self.account);
    result.put("status", 200);
    result.put("deliveryState", "SENT");
    result.set("result", codec.read("{\"info\":\"OTP mail sent\"}", JsonNode.class));
    result.put("receipt", start.receipt);
    var outcome =
        codec.verifySelfEmailStartOtpReceipt(codec.canonical(result), realmId, actor, command);
    if (outcome.status() != 200) throw unavailable();
    return new DeliveredEmail(start.operation, email, outcome);
  }

  private ObjectNode metadata(Intent row, String state, Operation kind) {
    var value = codec.read("{}", ObjectNode.class);
    value.put("v", 1);
    value.put("realmId", row.realm);
    value.put("operationId", row.operation);
    value.put("command", kind.command);
    value.put("method", kind.method);
    value.put("route", "otp-config/" + kind.route);
    value.put("accountId", row.account);
    if (row.tenant == null) value.putNull("tenantId");
    else value.put("tenantId", row.tenant);
    value.put("ownerClient", row.ownerClient);
    value.put("ownerSubject", row.ownerSubject);
    value.put(
        "originDigest",
        codec.digest("otp-origin", codec.read(row.target, JsonNode.class).path("origin")));
    value.put("workflowId", row.operation);
    value.put("requestDigest", row.requestDigest);
    value.put("expectedState", state);
    value.put("authorityValidUntil", row.expiry);
    value.put("representation", "FROZEN_HTTP_V1");
    return value;
  }

  private JsonNode read(Self self, Intent row, TaskActorCredentials actor) {
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", codec.read(row.target, JsonNode.class));
    var token = machine(self, actor, TaskPurpose.ACCOUNT_MAINTENANCE);
    var headers = headers(self, token);
    headers.set(
        "X-ORISO-Task-Context",
        codec.maintenanceProof("read", realmName, realmId, self.account, actor, body));
    JsonNode projection;
    try {
      outsideLocks(self, row);
      String reply =
          http.exchange(
                  UriComponentsBuilder.fromUriString(authServer)
                      .pathSegment(
                          "realms",
                          realmName,
                          "oriso-account-maintenance",
                          "v1",
                          "accounts",
                          self.account,
                          "read")
                      .build()
                      .encode()
                      .toUri(),
                  HttpMethod.POST,
                  new HttpEntity<>(codec.canonical(body), headers),
                  String.class)
              .getBody();
      if (reply == null || reply.length() > 65536) throw unavailable();
      projection = codec.read(reply, JsonNode.class);
      var names = new java.util.HashSet<String>();
      projection.fieldNames().forEachRemaining(names::add);
      if (!names.equals(
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
                  "state"))
          || !self.account.equals(projection.path("id").textValue())
          || !(self.tenant == null
              ? projection.path("tenantId").isNull()
              : self.tenant.toString().equals(projection.path("tenantId").textValue()))
          || !projection.path("enabled").isBoolean()
          || !projection.path("enabled").booleanValue()
          || !text(projection.path("username").textValue(), 255)
          || !projection.path("state").isTextual()
          || !projection.path("state").textValue().matches("[a-f0-9]{64}")) throw unavailable();
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
    return projection;
  }

  private Reply invoke(
      Self self, Intent row, TaskActorCredentials actor, JsonNode command, Operation kind) {
    boolean historicalSent = kind == Operation.EMAIL_START
        && "FROZEN".equals(row.status) && row.expiry <= clock.instant().getEpochSecond();
    var body = command.path("body");
    if (historicalSent) {
      // Fresh authentication may read only the original outcome: this immutable expired body
      // cannot prepare or send in the native receiver, including absent/READY/unknown states.
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || self.expires <= clock.instant().getEpochSecond()
          || !body.path("authorityValidUntil").isIntegralNumber()
          || body.path("authorityValidUntil").longValue() != row.expiry) throw unavailable();
    } else outsideLocks(self, row);
    var token = machine(self, actor, TaskPurpose.OTP);
    var materialized = ((ObjectNode) body).deepCopy();
    materialized.put("username", command.path("username").textValue());
    var headers = headers(self, token);
    long expiry = Math.min(historicalSent ? self.expires : Math.min(row.expiry, self.expires),
        token.validatedJwtExpiresAt());
    if (expiry <= clock.instant().getEpochSecond()) throw unavailable();
    headers.set(
        "X-ORISO-Task-Context",
        kind == Operation.DELETE
            ? codec.selfDeleteOtpProof(
                realmName, realmId, self.account, actor, materialized, expiry)
            : kind == Operation.EMAIL_START
                ? codec.selfEmailStartOtpProof(
                    realmName, realmId, self.account, actor, materialized, expiry)
                : kind == Operation.EMAIL_FINISH
                    ? codec.selfLinkedEmailFinishOtpProof(
                        realmName, realmId, self.account, actor, materialized, expiry)
                    : codec.selfAppOtpProof(
                        realmName, realmId, self.account, actor, materialized, expiry));
    var uri =
        UriComponentsBuilder.fromUriString(authServer)
            .pathSegment(
                "realms", realmName, "otp-config", kind.route, command.path("username").textValue())
            .build()
            .encode()
            .toUri();
    try {
      var response =
          http.exchange(
              uri,
              HttpMethod.valueOf(kind.method),
              new HttpEntity<>(codec.canonical(body), headers),
              String.class);
      if (historicalSent && response.getStatusCode().value() != 200) throw unavailable();
      return new Reply(response.getStatusCode().value(), response.getBody());
    } catch (RestClientResponseException failure) {
      if (historicalSent || failure.getStatusCode().value() != 401
          || failure.getResponseBodyAsByteArray().length > 16384) throw unavailable();
      return new Reply(401, codec.utf8(failure.getResponseBodyAsByteArray()));
    }
  }

  private TaskActorTokenBroker.VerifiedMaintenanceToken machine(
      Self self, TaskActorCredentials actor, TaskPurpose purpose) {
    try {
      var token = broker.issueCheckedMaintenance(purpose);
      if (token.purpose() != purpose
          || !actor.clientId().equals(token.clientId())
          || !actor.serviceSubject().equals(token.subject())
          || !self.issuer.equals(token.issuer())) throw unavailable();
      return token;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private HttpHeaders headers(Self self, TaskActorTokenBroker.VerifiedMaintenanceToken machine) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(machine.accessToken());
    headers.set("X-ORISO-Human-Authorization", "Bearer " + self.bearer);
    return headers;
  }

  private void outsideLocks(Self self, Intent row) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || self.expires <= clock.instant().getEpochSecond()
        || row.expiry <= clock.instant().getEpochSecond()) throw unavailable();
  }

  private void lockedDomain(Self self) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    var rows =
        jdbc.queryForList(
            "select tenant_id,delete_date from user where user_id=? for update", self.account);
    if (rows.size() != 1) throw forbidden();
    var row = rows.getFirst();
    Object tenant = row.get("tenant_id");
    Long actual = tenant == null ? null : ((Number) tenant).longValue();
    if (row.get("delete_date") != null || !Objects.equals(self.tenant, actual)) throw forbidden();
  }

  private Intent find(String operation) {
    var rows =
        jdbc.query(
            "select * from identity_self_app_otp_intent where operation_id=? for update",
            (rs, index) -> intent(rs),
            operation);
    if (rows.size() > 1) throw unavailable();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static Intent intent(ResultSet row) throws SQLException {
    long tenant = row.getLong("tenant_id");
    Long actualTenant = row.wasNull() ? null : tenant;
    int status = row.getInt("native_status");
    Integer nativeStatus = row.wasNull() ? null : status;
    return new Intent(
        row.getString("operation_id"),
        row.getString("realm_id"),
        row.getString("realm_name"),
        row.getString("account_id"),
        actualTenant,
        row.getString("owner_client"),
        row.getString("owner_subject"),
        row.getString("request_digest"),
        row.getString("target_json"),
        row.getString("status"),
        row.getLong("authority_valid_until"),
        row.getString("metadata_json"),
        row.getString("envelope"),
        row.getBoolean("pending_marker"),
        row.getString("receipt"),
        nativeStatus);
  }

  private Self actualSelf(Operation kind) {
    if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken authentication)
        || !authentication.isAuthenticated()
        || !kind.method.equals(request.getMethod())
        || !(kind == Operation.EMAIL_START || kind == Operation.EMAIL_FINISH
                ? Set.of("/service/users/2fa/email", "/users/2fa/email")
                : Set.of("/service/users/2fa/app", "/users/2fa/app"))
            .contains(request.getRequestURI())) throw forbidden();
    var jwt = authentication.getToken();
    String authorization = request.getHeader("Authorization");
    String account = jwt.getSubject();
    long now = clock.instant().getEpochSecond();
    if (!canonicalUuid(account)
        || !account.equals(authenticatedUser.getUserId())
        || !jwt.getTokenValue().equals(authenticatedUser.getAccessToken())
        || authorization == null
        || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
        || !jwt.getTokenValue().equals(authorization.substring(7))
        || jwt.getIssuer() == null
        || jwt.getIssuedAt() == null
        || jwt.getExpiresAt() == null
        || jwt.getIssuedAt().getEpochSecond() <= 0
        || jwt.getIssuedAt().getEpochSecond() > now
        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
        || jwt.getExpiresAt().getEpochSecond() <= now) throw forbidden();
    String issuer = jwt.getIssuer().toString();
    String client = jwt.getClaimAsString("azp");
    if (!(authServer.replaceAll("/+$", "") + "/realms/" + realmName).equals(issuer)
        || !text(client, 255)) throw forbidden();
    Object tenant = jwt.getClaims().get("tenantId");
    Long tenantId;
    if (tenant == null && !jwt.getClaims().containsKey("tenantId")) tenantId = null;
    else if (tenant instanceof String value && value.matches("0|[1-9][0-9]{0,18}")) {
      try {
        tenantId = Long.parseLong(value);
      } catch (NumberFormatException malformed) {
        throw forbidden();
      }
    } else if (tenant instanceof Long value && value >= 0) tenantId = value;
    else if (tenant instanceof Integer value && value >= 0) tenantId = value.longValue();
    else throw forbidden();
    if (!Objects.equals(tenantId, authenticatedUser.getTenantId())) throw forbidden();
    return new Self(
        account,
        issuer,
        client,
        tenantId,
        jwt.getTokenValue(),
        jwt.getExpiresAt().getEpochSecond());
  }

  private String operationId() {
    String id = request.getHeader("Idempotency-Key");
    if (!canonicalUuid(id))
      throw new BadRequestException("A canonical Idempotency-Key is required for app OTP setup");
    return id;
  }

  private ObjectNode target(Self self, String operation) {
    var value = codec.read("{}", ObjectNode.class);
    value.put("accountId", self.account);
    if (self.tenant == null) value.putNull("tenantId");
    else value.put("tenantId", self.tenant.toString());
    var origin = value.putObject("origin");
    origin.put("kind", "SELF");
    origin.put("humanSubject", self.account);
    origin.put("humanIssuer", self.issuer);
    origin.put("humanClient", self.client);
    origin.put("workflowId", operation);
    origin.putNull("humanAuthority");
    return value;
  }

  private <T> T transaction(Supplier<T> work) {
    var tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx.execute(status -> work.get());
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private static boolean terminal(String status) {
    return Set.of("ACKNOWLEDGED", "TERMINAL_REFUSAL").contains(status);
  }

  private static boolean text(String value, int limit) {
    return value != null
        && !value.isBlank()
        && value.length() <= limit
        && value.chars().noneMatch(c -> c < 32 || c == 127);
  }

  private static boolean canonicalUuid(String value) {
    try {
      return value != null
          && value.length() == 36
          && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Scoped app OTP maintenance unavailable");
  }

  private static ForbiddenException forbidden() {
    return new ForbiddenException("Current self account is not permitted");
  }

  private enum Operation {
    APP("otp.app-setup", "PUT", "setup-otp"),
    DELETE("otp.delete", "DELETE", "delete-otp"),
    EMAIL_START("otp.email-start", "PUT", "send-verification-mail"),
    EMAIL_FINISH("otp.email-finish", "POST", "setup-otp-mail");

    private final String command;
    private final String method;
    private final String route;

    Operation(String command, String method, String route) {
      this.command = command;
      this.method = method;
      this.route = route;
    }
  }

  private record DeliveredEmail(
      String operation, String email, AccountLifecycleCodec.VerifiedSelfAppOutcome outcome) {
    @Override
    public String toString() {
      return "DeliveredEmail[redacted]";
    }
  }

  private record Self(
      String account, String issuer, String client, Long tenant, String bearer, long expires) {
    @Override
    public String toString() {
      return "Self[redacted]";
    }
  }

  private record Intent(
      String operation,
      String realm,
      String realmName,
      String account,
      Long tenant,
      String ownerClient,
      String ownerSubject,
      String requestDigest,
      String target,
      String status,
      long expiry,
      String metadata,
      String envelope,
      boolean pending,
      String receipt,
      Integer nativeStatus) {
    @Override
    public String toString() {
      return "Intent[redacted]";
    }
  }

  private static final class CheckedIntent {
    private final Intent row;
    private final JsonNode metadata;

    private CheckedIntent(Intent row, JsonNode metadata) {
      this.row = row;
      this.metadata = metadata;
    }

    @Override
    public String toString() {
      return "CheckedIntent[redacted]";
    }
  }

  private record Reply(int status, String body) {
    @Override
    public String toString() {
      return "Reply[redacted]";
    }
  }
}
