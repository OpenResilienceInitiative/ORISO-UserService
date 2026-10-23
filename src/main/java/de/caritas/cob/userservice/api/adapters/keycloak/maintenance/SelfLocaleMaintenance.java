package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAccountOwner;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * First ordinary SELF locale intent. Runtime registration and native paired acceptance are
 * separate.
 */
@RequiredArgsConstructor
public final class SelfLocaleMaintenance {
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

  public SelfLocaleMaintenance(
      IdentityClientConfig mode,
      AuthenticatedUser authenticatedUser,
      HttpServletRequest request,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      RestTemplate http,
      Clock clock,
      String authServer,
      String realmName,
      String realmId) {
    this(
        mode,
        authenticatedUser,
        request,
        jdbc,
        transactions,
        actors,
        broker,
        codec,
        http,
        clock,
        authServer,
        authServer.replaceAll("/+$", "") + "/realms/" + realmName,
        realmName,
        realmId);
  }

  public boolean isFiveActorMode() {
    return mode.isFiveActorMode();
  }

  public Optional<Map<String, Object>> patch(
      String locale,
      Map<String, Object> completePatch,
      Supplier<Optional<Map<String, Object>>> domainPatch) {
    if (!mode.isFiveActorMode() || TransactionSynchronizationManager.isActualTransactionActive())
      throw unavailable();
    var self = actualSelf();
    String operation = operationId();
    if (!self.account.equals(completePatch.get("id"))
        || !Objects.equals(locale, completePatch.get("preferredLanguage"))
        || locale == null
        || locale.isBlank()
        || locale.length() > 20) throw forbidden();
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var binding = new TreeMap<String, Object>();
    binding.put("operation", "account.locale");
    binding.put("method", request.getMethod());
    binding.put("route", request.getRequestURI());
    binding.put("issuer", self.issuer);
    binding.put("client", self.client);
    binding.put("tenantId", self.tenant);
    binding.put("patch", completePatch);
    String digest = codec.digest("self-locale-request", binding);
    Intent intent;
    try {
      intent = transaction(() -> prepare(self, operation, digest, actor));
    } catch (RuntimeException uncertain) {
      intent = transaction(() -> findChecked(self, operation, digest, actor));
      if (intent == null) throw unavailable();
    }
    if ("ACKNOWLEDGED".equals(intent.status)) return acknowledged(self);
    if ("PREPARED".equals(intent.status)) {
      String state = readState(self, intent, actor);
      var body = codec.read("{}", ObjectNode.class);
      body.set("target", codec.read(intent.target, JsonNode.class));
      body.put("operationId", operation);
      body.put("expectedState", state);
      body.putObject("change").put("locale", locale);
      String frozen = codec.canonical(body);
      try {
        intent =
            transaction(
                () -> applyDomainAndFreeze(self, operation, digest, actor, frozen, domainPatch));
      } catch (RuntimeException uncertain) {
        // A fresh current lock/marker is the only commit witness. Never infer commit or rollback.
        intent = transaction(() -> findChecked(self, operation, digest, actor));
        if (intent == null
            || !intent.domainApplied
            || !Set.of("FROZEN", "ACKNOWLEDGED").contains(intent.status)) throw unavailable();
      }
    }
    if ("ACKNOWLEDGED".equals(intent.status)) return acknowledged(self);
    if (!"FROZEN".equals(intent.status) || !intent.domainApplied || intent.body == null)
      throw unavailable();
    final Intent original = intent;
    Intent ready = transaction(() -> {
      var current = findChecked(self, operation, digest, actor);
      if (current == null || !current.domainApplied || !Objects.equals(original.body, current.body)
          || !Set.of("FROZEN", "ACKNOWLEDGED").contains(current.status)) throw unavailable();
      return current;
    });
    if ("ACKNOWLEDGED".equals(ready.status)) return acknowledged(self);
    final Intent frozen = ready;
    String reply = request("locale", self, frozen.body, actor);
    String verified;
    try {
      verified =
          codec.verifyMaintenanceLocaleReceipt(
              reply, realmId, actor, codec.read(frozen.body, JsonNode.class));
    } catch (RuntimeException rejected) {
      throw unavailable();
    }
    transaction(
        () -> {
          var current = findChecked(self, operation, digest, actor);
          if (current == null
              || !current.domainApplied
              || !Objects.equals(frozen.body, current.body)) throw unavailable();
          if ("ACKNOWLEDGED".equals(current.status)) {
            if (!verified.equals(current.receipt)) throw unavailable();
            return null;
          }
          if (!"FROZEN".equals(current.status)) throw unavailable();
          if (jdbc.update(
                  "update identity_account_operation_intent set status='ACKNOWLEDGED',receipt=?,updated_at=? where operation_id=? and operation_kind='SELF_LOCALE' and status='FROZEN' and request_digest=?",
                  verified,
                  java.sql.Timestamp.from(clock.instant()),
                  operation,
                  digest)
              != 1) throw unavailable();
          return null;
        });
    return acknowledged(self);
  }

  private Intent prepare(
      CheckedSelf self, String operation, String digest, TaskActorCredentials actor) {
    var owner = lockedDomain(self);
    var prior = findIntent(operation);
    if (prior != null) return checked(self, prior, digest, actor, owner);
    String target = codec.canonical(target(self, operation, owner));
    jdbc.update(
        "insert into identity_account_operation_intent(operation_id,operation_kind,realm_id,realm_name,account_id,tenant_id,owner_client,owner_subject,request_digest,target_json,source_json,status,domain_applied,updated_at) values(?,'SELF_LOCALE',?,?,?,?,?,?,?,?,?,'PREPARED',false,?)",
        operation,
        realmId,
        realmName,
        self.account,
        self.tenant,
        actor.clientId(),
        actor.serviceSubject(),
        digest,
        target,
        owner.source,
        java.sql.Timestamp.from(clock.instant()));
    return checked(self, Objects.requireNonNull(findIntent(operation)), digest, actor, owner);
  }

  private Intent applyDomainAndFreeze(
      CheckedSelf self,
      String operation,
      String digest,
      TaskActorCredentials actor,
      String body,
      Supplier<Optional<Map<String, Object>>> domainPatch) {
    var intent = findChecked(self, operation, digest, actor);
    if (intent == null) throw unavailable();
    if (!"PREPARED".equals(intent.status)) return intent;
    if (intent.domainApplied || intent.body != null) throw unavailable();
    // Actual AccountManager/repositories join this owning transaction. The marker cannot commit
    // alone.
    var changed = domainPatch.get();
    if (changed == null || changed.isEmpty())
      throw new NotFoundException("Current account was not found");
    if (jdbc.update(
            "update identity_account_operation_intent set intent_body=?,status='FROZEN',domain_applied=true,updated_at=? where operation_id=? and operation_kind='SELF_LOCALE' and status='PREPARED' and domain_applied=false",
            body,
            java.sql.Timestamp.from(clock.instant()),
            operation)
        != 1) throw unavailable();
    return findChecked(self, operation, digest, actor);
  }

  private Intent findChecked(
      CheckedSelf self, String operation, String digest, TaskActorCredentials actor) {
    var owner = lockedDomain(self);
    var intent = findIntent(operation);
    return intent == null ? null : checked(self, intent, digest, actor, owner);
  }

  private Intent checked(
      CheckedSelf self, Intent intent, String digest, TaskActorCredentials actor, LocalOwner owner) {
    if (!realmId.equals(intent.realm)
        || !realmName.equals(intent.realmName)
        || !self.account.equals(intent.account)
        || !Objects.equals(self.tenant, intent.tenant)
        || !actor.clientId().equals(intent.ownerClient)
        || !actor.serviceSubject().equals(intent.ownerSubject)
        || !digest.equals(intent.requestDigest)
        || !owner.source.equals(intent.source)
        || !codec.canonical(target(self, intent.operation, owner)).equals(intent.target))
      throw new ConflictException("The account operation does not match its original request");
    if (!Set.of("PREPARED", "FROZEN", "ACKNOWLEDGED").contains(intent.status)
        || "PREPARED".equals(intent.status) && (intent.domainApplied || intent.body != null)
        || !"PREPARED".equals(intent.status) && (!intent.domainApplied || intent.body == null))
      throw unavailable();
    return intent;
  }

  private Intent findIntent(String operation) {
    var rows =
        jdbc.query(
            "select * from identity_account_operation_intent where operation_id=? and operation_kind='SELF_LOCALE' for update",
            (row, index) -> intent(row),
            operation);
    if (rows.size() > 1) throw unavailable();
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static Intent intent(ResultSet row) throws SQLException {
    long tenant = row.getLong("tenant_id");
    Long actualTenant = row.wasNull() ? null : tenant;
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
        row.getString("source_json"),
        row.getString("intent_body"),
        row.getString("status"),
        row.getBoolean("domain_applied"),
        row.getString("receipt"));
  }

  /** Fixed local DATA only; native policy still checks the actual current human and profile. */
  private LocalOwner lockedDomain(CheckedSelf self) {
    var owner = SelfAccountOwner.inspect(jdbc, self.account, self.tenant);
    if (owner.kind().admin() && !owner.consultant()) throw forbidden();
    if (self.tenant != null && self.tenant == 0
        && (owner.kind() != SelfAccountOwner.Kind.PLATFORM_ADMIN || !owner.consultant()))
      throw forbidden();
    var source = codec.read("{}", ObjectNode.class);
    source.put("v", 1).put("kind", owner.kind().name()).put("consultant", owner.consultant());
    if (self.tenant == null) source.putNull("tenantId");
    else source.put("tenantId", self.tenant);
    source.put("primaryCreateDate", birth(owner.kind(), self.account));
    if (owner.consultant())
      source.put("consultantCreateDate", birth(SelfAccountOwner.Kind.CONSULTANT, self.account));
    else source.putNull("consultantCreateDate");
    return new LocalOwner(owner, codec.canonical(source));
  }

  private String birth(SelfAccountOwner.Kind kind, String account) {
    String sql = switch (kind) {
      case USER -> "select create_date from user where user_id=? for update";
      case CONSULTANT -> "select create_date from consultant where consultant_id=? for update";
      case AGENCY_ADMIN, TENANT_ADMIN, PLATFORM_ADMIN ->
          "select create_date from admin where admin_id=? for update";
    };
    var date = jdbc.queryForObject(sql, java.sql.Timestamp.class, account);
    if (date == null) throw forbidden();
    return date.toLocalDateTime().toString();
  }

  private record LocalOwner(SelfAccountOwner.Owner identity, String source) {
    @Override
    public String toString() {
      return "LocaleOwner[redacted]";
    }
  }

  private CheckedSelf actualSelf() {
    if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken authentication)
        || !authentication.isAuthenticated()
        || !"PATCH".equals(request.getMethod())
        || !Set.of("/service/users/data", "/users/data").contains(request.getRequestURI()))
      throw forbidden();
    var jwt = authentication.getToken();
    String account = jwt.getSubject();
    if (!canonicalUuid(account)
        || !account.equals(authenticatedUser.getUserId())
        || !jwt.getTokenValue().equals(authenticatedUser.getAccessToken())
        || request.getHeader("Authorization") == null
        || !request.getHeader("Authorization").regionMatches(true, 0, "Bearer ", 0, 7)
        || !jwt.getTokenValue().equals(request.getHeader("Authorization").substring(7))
        || jwt.getIssuer() == null
        || jwt.getIssuedAt() == null
        || jwt.getExpiresAt() == null
        || jwt.getIssuedAt().getEpochSecond() <= 0
        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
        || !jwt.getExpiresAt().isAfter(clock.instant())) throw forbidden();
    String issuer = jwt.getIssuer().toString();
    String expected = publicIssuer;
    String client = jwt.getClaimAsString("azp");
    if (!expected.equals(issuer) || client == null || client.isBlank() || client.length() > 255)
      throw forbidden();
    Object tenant = jwt.getClaims().get("tenantId");
    Long actualTenant;
    if (tenant == null && !jwt.getClaims().containsKey("tenantId")) actualTenant = null;
    else if (tenant instanceof String value && value.matches("0|[1-9][0-9]{0,18}")) {
      try {
        actualTenant = Long.valueOf(value);
      } catch (NumberFormatException invalid) {
        throw forbidden();
      }
    } else throw forbidden();
    if (!Objects.equals(actualTenant, authenticatedUser.getTenantId())) throw forbidden();
    return new CheckedSelf(
        account, actualTenant, issuer, client, jwt.getTokenValue(), jwt.getExpiresAt());
  }

  private static boolean canonicalUuid(String value) {
    try {
      return value != null
          && value.length() == 36
          && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException rejected) {
      return false;
    }
  }

  private String operationId() {
    String value = request.getHeader("Idempotency-Key");
    try {
      if (value == null || value.length() != 36 || !UUID.fromString(value).toString().equals(value))
        throw new IllegalArgumentException();
      return value;
    } catch (IllegalArgumentException rejected) {
      throw new BadRequestException(
          "A canonical Idempotency-Key is required for this account change");
    }
  }

  private ObjectNode target(CheckedSelf self, String operation, LocalOwner owner) {
    var target = codec.read("{}", ObjectNode.class);
    target.put("accountId", self.account);
    if (self.tenant == null) target.putNull("tenantId");
    else target.put("tenantId", self.tenant.toString());
    var origin = target.putObject("origin");
    origin.put("kind", "SELF");
    origin.put("workflowId", operation);
    origin.put("humanSubject", self.account);
    origin.put("humanIssuer", self.issuer);
    origin.put("humanClient", self.client);
    if (owner.identity.kind().protectedTarget())
      origin.put("humanAuthority", "PROTECTED_ACCOUNT_OPERATIONS_V1");
    else origin.putNull("humanAuthority");
    return target;
  }

  private String readState(CheckedSelf self, Intent intent, TaskActorCredentials actor) {
    var read = codec.read("{}", ObjectNode.class);
    read.set("target", codec.read(intent.target, JsonNode.class));
    var reply = codec.read(request("read", self, codec.canonical(read), actor), JsonNode.class);
    var fields = new java.util.HashSet<String>();
    reply.fieldNames().forEachRemaining(fields::add);
    JsonNode tenant = reply.path("tenantId");
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
        || !self.account.equals(reply.path("id").textValue())
        || !Objects.equals(
            self.tenant == null ? null : self.tenant.toString(),
            tenant.isNull() ? null : tenant.textValue())
        || !reply.path("state").isTextual()
        || !reply.path("state").textValue().matches("[a-f0-9]{64}")) throw unavailable();
    return reply.path("state").textValue();
  }

  private String request(
      String command, CheckedSelf self, String frozen, TaskActorCredentials actor) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || !self.expires.isAfter(clock.instant())) throw unavailable();
    try {
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      var machine = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
      long now = clock.instant().getEpochSecond();
      if (machine == null
          || machine.actor() != actor.actor()
          || machine.purpose() != TaskPurpose.ACCOUNT_MAINTENANCE
          || !actor.clientId().equals(machine.clientId())
          || !actor.serviceSubject().equals(machine.subject())
          || !publicIssuer.equals(machine.issuer())
          || machine.validatedJwtIssuedAt() <= 0
          || machine.validatedJwtExpiresAt() <= machine.validatedJwtIssuedAt()
          || machine.validatedJwtExpiresAt() <= now) throw unavailable();
      headers.setBearerAuth(machine.accessToken());
      headers.set("X-ORISO-Human-Authorization", "Bearer " + self.token);
      headers.set(
          "X-ORISO-Task-Context",
          codec.maintenanceProof(
              command,
              realmName,
              realmId,
              self.account,
              actor,
              codec.read(frozen, JsonNode.class)));
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
      return Objects.requireNonNull(
          http.exchange(uri, HttpMethod.POST, new HttpEntity<>(frozen, headers), String.class)
              .getBody());
    } catch (RuntimeException unknown) {
      // Preserve exact frozen intent. Provider bodies/tokens and speculative retry state never
      // escape.
      throw unavailable();
    }
  }

  private <T> T transaction(Supplier<T> operation) {
    var transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(15);
    return transaction.execute(status -> {
      if (!TransactionSynchronizationManager.isActualTransactionActive()
          || jdbc.getDataSource() == null
          || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())
          || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
      return operation.get();
    });
  }

  private static Optional<Map<String, Object>> acknowledged(CheckedSelf self) {
    return Optional.of(Map.of("id", self.account));
  }

  private static ForbiddenException forbidden() {
    return new ForbiddenException("Current self account with language data is required");
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Account change could not complete");
  }

  private record CheckedSelf(
      String account,
      Long tenant,
      String issuer,
      String client,
      String token,
      java.time.Instant expires) {
    @Override
    public String toString() {
      return "CheckedSelf[redacted]";
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
      String source,
      String body,
      String status,
      boolean domainApplied,
      String receipt) {
    @Override
    public String toString() {
      return "SelfLocaleIntent[redacted]";
    }
  }
}
