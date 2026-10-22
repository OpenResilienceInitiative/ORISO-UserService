package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
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
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Private immutable password intent. Public assertions never authorize a native command. */
@RequiredArgsConstructor
public final class SelfPasswordMaintenance {
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
  private final @NonNull String publicIssuer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;

  public boolean change(String currentPassword, String password, Runnable acknowledged) {
    if (!mode.isFiveActorMode() || TransactionSynchronizationManager.isActualTransactionActive())
      throw unavailable();
    if (currentPassword == null
        || currentPassword.isBlank()
        || currentPassword.length() > 4096
        || password == null
        || password.isBlank()
        || password.length() > 4096) throw new BadRequestException("Both passwords are required");
    if (currentPassword.equals(password))
      throw new ConflictException("The new password must differ from the current one");
    var self = actualSelf();
    String operation = operationId();
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var originalMachine = checkedMachine(actor);
    var binding = new TreeMap<String, Object>();
    binding.put("account", self.account);
    binding.put("tenantId", self.tenant);
    binding.put("issuer", self.issuer);
    binding.put("client", self.client);
    binding.put("method", request.getMethod());
    binding.put("route", request.getRequestURI());
    binding.put(
        "change",
        Map.of("currentPassword", currentPassword, "password", password, "temporary", false));
    String digest = codec.digest("self-password-request/v1", binding);
    Intent intent;
    try {
      intent = transaction(() -> prepare(self, operation, digest, actor, originalMachine));
    } catch (RuntimeException uncertain) {
      intent = transaction(() -> findChecked(self, operation, digest, actor));
      if (intent == null) throw unavailable();
    }
    if ("ACKNOWLEDGED".equals(intent.status)) return true;
    if ("REJECTED".equals(intent.status)) return false;
    if ("PREPARED".equals(intent.status)) {
      if (intent.expires <= clock.instant().getEpochSecond()) {
        reject(self, operation, digest, actor);
        return false;
      }
      String state = readState(self, intent, actor);
      final Intent prepared = intent;
      try {
        intent =
            transaction(
                () -> freeze(self, prepared, digest, actor, state, currentPassword, password));
      } catch (RuntimeException uncertain) {
        intent = transaction(() -> findChecked(self, operation, digest, actor));
        if (intent == null || !Set.of("FROZEN", "ACKNOWLEDGED").contains(intent.status))
          throw unavailable();
      }
    }
    if ("ACKNOWLEDGED".equals(intent.status)) return true;
    if (!"FROZEN".equals(intent.status)) throw unavailable();
    final Intent frozen = intent;
    // Only independently checked locked metadata may open a saved envelope.
    JsonNode wrapper = transaction(() -> openChecked(self, frozen, digest, actor));
    JsonNode command = wrapper.path("body");
    String verified;
    try {
      String reply = call("self-password", self, codec.canonical(command), actor);
      verified = codec.verifyMaintenanceSelfPasswordReceipt(reply, realmId, actor, command);
    } catch (DefiniteRejection refused) {
      reject(self, operation, digest, actor);
      return false;
    } catch (ConflictException | ForbiddenException refused) {
      throw refused;
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
    try {
      transaction(
          () -> {
            acknowledge(self, frozen, digest, actor, wrapper, verified, acknowledged);
            return null;
          });
    } catch (RuntimeException uncertain) {
      var resolved = transaction(() -> findChecked(self, operation, digest, actor));
      if (resolved == null
          || !"ACKNOWLEDGED".equals(resolved.status)
          || !verified.equals(resolved.receipt)) throw unavailable();
    }
    return true;
  }

  private Intent prepare(
      Self self,
      String operation,
      String digest,
      TaskActorCredentials actor,
      TaskActorTokenBroker.VerifiedMaintenanceToken originalMachine) {
    lockedDomain(self);
    var prior = find(operation);
    if (prior != null) return checked(self, prior, digest, actor);
    long expiry =
        Math.min(
            Math.min(self.expires.getEpochSecond(), originalMachine.validatedJwtExpiresAt()),
            clock.instant().getEpochSecond() + 600);
    if (expiry <= clock.instant().getEpochSecond()) throw unavailable();
    jdbc.update(
        "insert into identity_self_password_intent(operation_id,realm_id,account_id,tenant_id,owner_client,owner_subject,request_digest,target_json,authority_valid_until,status,updated_at) values(?,?,?,?,?,?,?,?,?,'PREPARED',?)",
        operation,
        realmId,
        self.account,
        self.tenant,
        actor.clientId(),
        actor.serviceSubject(),
        digest,
        codec.canonical(target(self, operation)),
        expiry,
        now());
    return checked(self, Objects.requireNonNull(find(operation)), digest, actor);
  }

  private Intent freeze(
      Self self,
      Intent prepared,
      String digest,
      TaskActorCredentials actor,
      String state,
      String currentPassword,
      String password) {
    JsonNode local = lockedDomain(self);
    var row = find(prepared.operation);
    if (row == null) throw unavailable();
    row = checked(self, row, digest, actor);
    if (!"PREPARED".equals(row.status)) return row;
    if (row.expires <= clock.instant().getEpochSecond()) throw unavailable();
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", codec.read(row.target, JsonNode.class));
    body.put("operationId", row.operation);
    body.put("expectedState", state);
    body.put("authorityValidUntil", row.expires);
    body.putObject("change")
        .put("currentPassword", currentPassword)
        .put("password", password)
        .put("temporary", false);
    var wrapper = codec.read("{}", ObjectNode.class);
    wrapper.set("body", body);
    wrapper.set("localSetup", local);
    var metadata = metadata(row, state);
    String envelope =
        codec.sealSelfPasswordIntent(
            metadata, codec.canonical(wrapper).getBytes(StandardCharsets.UTF_8));
    if (jdbc.update(
            "update identity_self_password_intent set metadata_json=?,intent_envelope=?,status='FROZEN',updated_at=? where operation_id=? and status='PREPARED'",
            codec.canonical(metadata),
            envelope,
            now(),
            row.operation)
        != 1) throw unavailable();
    return checked(self, Objects.requireNonNull(find(row.operation)), digest, actor);
  }

  private JsonNode openChecked(
      Self self, Intent frozen, String digest, TaskActorCredentials actor) {
    var current = findChecked(self, frozen.operation, digest, actor);
    if (current == null
        || !Set.of("FROZEN", "ACKNOWLEDGED").contains(current.status)
        || !Objects.equals(current.envelope, frozen.envelope)
        || !Objects.equals(current.metadata, frozen.metadata)) throw unavailable();
    return codec.read(
        codec.utf8(
            codec.openSelfPasswordIntent(
                codec.read(current.metadata, JsonNode.class), current.envelope)),
        JsonNode.class);
  }

  private void acknowledge(
      Self self,
      Intent frozen,
      String digest,
      TaskActorCredentials actor,
      JsonNode wrapper,
      String verified,
      Runnable callback) {
    JsonNode local = lockedDomain(self);
    var current = findChecked(self, frozen.operation, digest, actor);
    if (current == null
        || !Objects.equals(frozen.metadata, current.metadata)
        || !Objects.equals(frozen.envelope, current.envelope)) throw unavailable();
    if ("ACKNOWLEDGED".equals(current.status)) {
      if (!verified.equals(current.receipt)) throw unavailable();
      return;
    }
    if (!"FROZEN".equals(current.status)) throw unavailable();
    // Historical success records its receipt but cannot clear a newer local setup incarnation.
    if (codec.canonical(local).equals(codec.canonical(wrapper.path("localSetup")))) callback.run();
    if (jdbc.update(
            "update identity_self_password_intent set status='ACKNOWLEDGED',receipt=?,updated_at=? where operation_id=? and status='FROZEN'",
            verified,
            now(),
            current.operation)
        != 1) throw unavailable();
  }

  private void reject(Self self, String operation, String digest, TaskActorCredentials actor) {
    transaction(
        () -> {
          var row = findChecked(self, operation, digest, actor);
          if (row == null) throw unavailable();
          if ("REJECTED".equals(row.status)) return null;
          if ("ACKNOWLEDGED".equals(row.status)) throw unavailable();
          if (jdbc.update(
                  "update identity_self_password_intent set status='REJECTED',updated_at=? where operation_id=? and status in ('PREPARED','FROZEN')",
                  now(),
                  operation)
              != 1) throw unavailable();
          return null;
        });
  }

  private Intent findChecked(
      Self self, String operation, String digest, TaskActorCredentials actor) {
    lockedDomain(self);
    var row = find(operation);
    return row == null ? null : checked(self, row, digest, actor);
  }

  private Intent checked(Self self, Intent row, String digest, TaskActorCredentials actor) {
    if (!realmId.equals(row.realm)
        || !self.account.equals(row.account)
        || !Objects.equals(self.tenant, row.tenant)
        || !actor.clientId().equals(row.ownerClient)
        || !actor.serviceSubject().equals(row.ownerSubject)
        || !digest.equals(row.digest)
        || !codec.canonical(target(self, row.operation)).equals(row.target))
      throw new ConflictException("Password intent does not match its original request");
    if (row.expires <= 0
        || !Set.of("PREPARED", "FROZEN", "ACKNOWLEDGED", "REJECTED").contains(row.status))
      throw unavailable();
    if ("PREPARED".equals(row.status)
        && (row.metadata != null || row.envelope != null || row.receipt != null))
      throw unavailable();
    if (Set.of("FROZEN", "ACKNOWLEDGED").contains(row.status)) {
      if (row.metadata == null || row.envelope == null) throw unavailable();
      var stored = codec.read(row.metadata, JsonNode.class);
      if (!row.metadata.equals(
          codec.canonical(metadata(row, stored.path("expectedState").asText()))))
        throw unavailable();
      codec.openSelfPasswordIntent(stored, row.envelope);
      if ("ACKNOWLEDGED".equals(row.status) && row.receipt == null) throw unavailable();
    }
    return row;
  }

  private Intent find(String operation) {
    var rows =
        jdbc.query(
            "select * from identity_self_password_intent where operation_id=? for update",
            (r, i) -> {
              long tenant = r.getLong("tenant_id");
              Long actual = r.wasNull() ? null : tenant;
              return new Intent(
                  r.getString("operation_id"),
                  r.getString("realm_id"),
                  r.getString("account_id"),
                  actual,
                  r.getString("owner_client"),
                  r.getString("owner_subject"),
                  r.getString("request_digest"),
                  r.getString("target_json"),
                  r.getLong("authority_valid_until"),
                  r.getString("metadata_json"),
                  r.getString("intent_envelope"),
                  r.getString("status"),
                  r.getString("receipt"));
            },
            operation);
    if (rows.size() > 1) throw unavailable();
    return rows.isEmpty() ? null : rows.get(0);
  }

  private JsonNode lockedDomain(Self self) {
    var users =
        jdbc.queryForList(
            "select tenant_id,delete_date from user where user_id=? for update", self.account);
    var consultants =
        jdbc.queryForList(
            "select tenant_id,delete_date,update_date,password_change_required from consultant where consultant_id=? for update",
            self.account);
    if (users.size() + consultants.size() != 1) throw forbidden();
    var row = users.isEmpty() ? consultants.get(0) : users.get(0);
    Object tenant = row.get("tenant_id");
    Long actual = tenant == null ? null : ((Number) tenant).longValue();
    if (row.get("delete_date") != null || !Objects.equals(self.tenant, actual)) throw forbidden();
    var local = codec.read("{}", ObjectNode.class);
    if (!users.isEmpty()) {
      local.put("kind", "USER");
      local.putNull("updateDate");
      local.putNull("passwordChangeRequired");
    } else {
      local.put("kind", "CONSULTANT");
      Object updated = row.get("update_date"), required = row.get("password_change_required");
      if (updated == null || !(required instanceof Boolean || required instanceof Number))
        throw unavailable();
      local.put("updateDate", updated.toString());
      local.put(
          "passwordChangeRequired",
          required instanceof Boolean b ? b : ((Number) required).intValue() != 0);
    }
    return local;
  }

  private ObjectNode metadata(Intent row, String state) {
    var value = codec.read("{}", ObjectNode.class);
    value.put("v", 1);
    value.put("realmId", realmId);
    value.put("operationId", row.operation);
    value.put("command", "account.self-password");
    value.put("method", "POST");
    value.put("route", "oriso-account-maintenance/v1/accounts/self-password");
    value.put("accountId", row.account);
    if (row.tenant == null) value.putNull("tenantId");
    else value.put("tenantId", row.tenant);
    value.put("ownerClient", row.ownerClient);
    value.put("ownerSubject", row.ownerSubject);
    value.put(
        "originDigest",
        codec.digest("maintenance-origin", codec.read(row.target, JsonNode.class).path("origin")));
    value.put("workflowId", row.operation);
    value.put("requestDigest", row.digest);
    value.put("expectedState", state);
    value.put("authorityValidUntil", row.expires);
    value.put("representation", "FROZEN_PASSWORD_HTTP_V1");
    return value;
  }

  private ObjectNode target(Self self, String operation) {
    var value = codec.read("{}", ObjectNode.class);
    value.put("accountId", self.account);
    if (self.tenant == null) value.putNull("tenantId");
    else value.put("tenantId", self.tenant.toString());
    var origin = value.putObject("origin");
    origin.put("kind", "SELF");
    origin.put("workflowId", operation);
    origin.put("humanSubject", self.account);
    origin.put("humanIssuer", self.issuer);
    origin.put("humanClient", self.client);
    origin.putNull("humanAuthority");
    return value;
  }

  private String readState(Self self, Intent row, TaskActorCredentials actor) {
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", codec.read(row.target, JsonNode.class));
    var read = codec.read(call("read", self, codec.canonical(body), actor), JsonNode.class);
    var fields = new HashSet<String>();
    read.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(
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
        || !self.account.equals(read.path("id").textValue())
        || !read.path("enabled").isBoolean()
        || !read.path("enabled").booleanValue()
        || !Objects.equals(
            self.tenant == null ? null : self.tenant.toString(),
            read.path("tenantId").isNull() ? null : read.path("tenantId").textValue())
        || !read.path("state").isTextual()
        || !read.path("state").textValue().matches("[a-f0-9]{64}")) throw unavailable();
    return read.path("state").textValue();
  }

  private String call(String command, Self self, String body, TaskActorCredentials actor) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || !self.expires.isAfter(clock.instant())) throw unavailable();
    try {
      var machine = checkedMachine(actor);
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(machine.accessToken());
      headers.set("X-ORISO-Human-Authorization", "Bearer " + self.bearer);
      headers.set(
          "X-ORISO-Task-Context",
          codec.maintenanceProof(
              command, realmName, realmId, self.account, actor, codec.read(body, JsonNode.class)));
      var uri =
          UriComponentsBuilder.fromUriString(authServer)
              .pathSegment(
                  "realms",
                  realmName,
                  "oriso-account-maintenance",
                  "v1",
                  "accounts",
                  self.account,
                  command)
              .build()
              .encode()
              .toUri();
      var reply =
          http.exchange(uri, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
      if (reply.getStatusCode().value() != 200
          || reply.getBody() == null
          || reply.getBody().length() > 32768) throw unavailable();
      return reply.getBody();
    } catch (HttpStatusCodeException refused) {
      if ("self-password".equals(command) && refused.getStatusCode().value() == 400)
        throw new DefiniteRejection();
      if (refused.getStatusCode().value() == 409)
        throw new ConflictException("Current password intent conflicts");
      if (Set.of(401, 403).contains(refused.getStatusCode().value())) throw forbidden();
      throw unavailable();
    } catch (DefiniteRejection | ConflictException | ForbiddenException refused) {
      throw refused;
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
  }

  private TaskActorTokenBroker.VerifiedMaintenanceToken checkedMachine(TaskActorCredentials actor) {
    var token = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    long now = clock.instant().getEpochSecond();
    if (token == null
        || token.actor() != TaskActor.ACCOUNT_ADMIN
        || token.actor() != actor.actor()
        || token.purpose() != TaskPurpose.ACCOUNT_MAINTENANCE
        || !actor.clientId().equals(token.clientId())
        || !actor.serviceSubject().equals(token.subject())
        || !publicIssuer.equals(token.issuer())
        || token.validatedJwtIssuedAt() <= 0
        || token.validatedJwtExpiresAt() <= token.validatedJwtIssuedAt()
        || token.validatedJwtExpiresAt() <= now) throw unavailable();
    return token;
  }

  private Self actualSelf() {
    if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken auth)
        || !auth.isAuthenticated()
        || !"PUT".equals(request.getMethod())
        || !Set.of("/service/users/password/change", "/users/password/change")
            .contains(request.getRequestURI())) throw forbidden();
    var jwt = auth.getToken();
    String account = jwt.getSubject(), client = jwt.getClaimAsString("azp");
    if (!uuid(account)
        || !account.equals(authenticatedUser.getUserId())
        || !jwt.getTokenValue().equals(authenticatedUser.getAccessToken())
        || jwt.getIssuer() == null
        || !publicIssuer.equals(jwt.getIssuer().toString())
        || jwt.getIssuedAt() == null
        || jwt.getExpiresAt() == null
        || jwt.getIssuedAt().getEpochSecond() <= 0
        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
        || !jwt.getExpiresAt().isAfter(clock.instant())
        || client == null
        || client.isBlank()
        || client.length() > 255
        || request.getHeader("Authorization") == null
        || !request.getHeader("Authorization").regionMatches(true, 0, "Bearer ", 0, 7)
        || !jwt.getTokenValue().equals(request.getHeader("Authorization").substring(7)))
      throw forbidden();
    Long tenant = null;
    Object claim = jwt.getClaims().get("tenantId");
    if (claim == null && !jwt.getClaims().containsKey("tenantId")) tenant = null;
    else if (claim instanceof String text && text.matches("0|[1-9][0-9]{0,18}")) {
      try {
        tenant = Long.valueOf(text);
      } catch (NumberFormatException invalid) {
        throw forbidden();
      }
    } else throw forbidden();
    if (!Objects.equals(tenant, authenticatedUser.getTenantId())) throw forbidden();
    return new Self(account, tenant, publicIssuer, client, jwt.getTokenValue(), jwt.getExpiresAt());
  }

  private String operationId() {
    String id = request.getHeader("Idempotency-Key");
    if (!uuid(id))
      throw new BadRequestException("A canonical Idempotency-Key is required for password changes");
    return id;
  }

  private static boolean uuid(String value) {
    try {
      return value != null
          && value.length() == 36
          && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private <T> T transaction(Supplier<T> action) {
    var tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setTimeout(15);
    return tx.execute(
        status -> {
          if (!TransactionSynchronizationManager.isActualTransactionActive()
              || jdbc.getDataSource() == null
              || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw unavailable();
          return action.get();
        });
  }

  private java.sql.Timestamp now() {
    return java.sql.Timestamp.from(clock.instant());
  }

  private static ForbiddenException forbidden() {
    return new ForbiddenException("Current self password authority is required");
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Password change could not complete");
  }

  private static final class DefiniteRejection extends RuntimeException {
    private DefiniteRejection() {
      super("Password intent refused");
    }
  }

  private record Self(
      String account, Long tenant, String issuer, String client, String bearer, Instant expires) {
    @Override
    public String toString() {
      return "PasswordSelf[redacted]";
    }
  }

  private record Intent(
      String operation,
      String realm,
      String account,
      Long tenant,
      String ownerClient,
      String ownerSubject,
      String digest,
      String target,
      long expires,
      String metadata,
      String envelope,
      String status,
      String receipt) {
    @Override
    public String toString() {
      return "PasswordIntent[redacted]";
    }
  }
}
