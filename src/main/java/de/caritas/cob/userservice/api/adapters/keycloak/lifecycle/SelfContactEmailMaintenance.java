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
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

/** One current SELF contact-email operation, using the existing journal and strict native ACK. */
@RequiredArgsConstructor
public final class SelfContactEmailMaintenance {
  private final @NonNull IdentityClientConfig mode;
  private final @NonNull AuthenticatedUser human;
  private final @NonNull HttpServletRequest request;
  private final @NonNull JdbcTemplate jdbc;
  private final @NonNull PlatformTransactionManager transactions;
  private final @NonNull EntityManager entities;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull RestTemplate http;
  private final @NonNull Clock clock;
  private final @NonNull String server;
  private final @NonNull String issuer;
  private final @NonNull String realm;
  private final @NonNull String realmId;

  /** The fixed public service supplies only its own local callback; remote effects run afterwards. */
  public boolean change(String email, boolean removal, Runnable localApply) {
    if (!mode.isFiveActorMode() || TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    Self self = actualSelf(removal);
    if (email == null || email.isBlank() || email.length() > 255
        || removal && !email.equals(self.account + mode.getEmailDummySuffix())) throw forbidden();
    String operation = operation();
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    checkedMachine(actor);
    String digest = codec.digest("self-contact-email-request/v1", Map.of(
        "account", self.account, "issuer", issuer, "client", self.client,
        "method", request.getMethod(), "route", request.getRequestURI(),
        "change", Map.of("email", email, "remove", removal)));
    Intent intent;
    try {
      intent = tx(() -> prepare(self, operation, digest, actor));
    } catch (ConflictException | ForbiddenException refused) { throw refused; }
    catch (RuntimeException uncertain) {
      intent = tx(() -> findChecked(self, operation, digest, actor));
      if (intent == null) throw unavailable();
    }
    if ("REFUSED".equals(intent.status)) throw refused(intent, actor);
    if ("PREPARED".equals(intent.status)) {
      var projection = codec.read(invoke("read", self, "{\"target\":" + intent.target + "}", actor), JsonNode.class);
      String state = state(self, projection);
      intent = tx(() -> freeze(self, operation, digest, actor, state, email));
    }
    if (!Set.of("FROZEN", "APPLIED").contains(intent.status)) throw unavailable();
    Intent frozen = intent;
    tx(() -> { requireSame(frozen, findChecked(self, operation, digest, actor)); return null; });
    String verified;
    try {
      verified = codec.verifyMaintenanceEmailReceipt(
          invoke("email", self, frozen.body, actor), realmId, actor, codec.read(frozen.body, JsonNode.class));
    } catch (SignedRefusal rejection) {
      tx(() -> {
        var current = findChecked(self, operation, digest, actor);
        requireSame(frozen, current);
        if (!"FROZEN".equals(current.status)) throw unavailable();
        var terminal = new Intent(current.operation, current.account, current.tenant, current.client,
            current.subject, current.digest, current.target, current.body, "REFUSED", false,
            rejection.reply, current.source, current.kind);
        String source = seal(terminal, source(current).path("original"), null);
        if (jdbc.update("update identity_account_operation_intent set status='REFUSED',receipt=?,source_json=?,updated_at=? where operation_id=? and operation_kind='SELF_CONTACT_EMAIL' and status='FROZEN' and domain_applied=false and request_digest=?", rejection.reply, source, now(), operation, digest) != 1) throw unavailable();
        return null;
      });
      throw refused(tx(() -> findChecked(self, operation, digest, actor)), actor);
    } catch (RuntimeException unknown) { throw unavailable(); }
    try {
      return tx(() -> {
        var current = findChecked(self, operation, digest, actor);
        requireSame(frozen, current);
        if ("APPLIED".equals(current.status)) {
          if (!verified.equals(current.receipt)) throw unavailable();
          return false;
        }
        var owner = SelfAccountOwner.inspect(jdbc, self.account, self.tenant);
        if (owner.kind().admin()) new SelfAccountOwner(jdbc).replaceAdminEmail(self.account, self.tenant, owner, email);
        entities.clear();
        localApply.run();
        entities.flush();
        var post = SelfContactEmailSource.capture(jdbc, codec, self.account, self.tenant);
        var updated = new Intent(current.operation, current.account, current.tenant, current.client,
            current.subject, current.digest, current.target, current.body, "APPLIED", true, verified,
            current.source, current.kind);
        String source = seal(updated, source(current).path("original"), post);
        if (jdbc.update("update identity_account_operation_intent set status='APPLIED',domain_applied=true,receipt=?,source_json=?,updated_at=? where operation_id=? and operation_kind='SELF_CONTACT_EMAIL' and status='FROZEN' and domain_applied=false and request_digest=?", verified, source, now(), operation, digest) != 1) throw unavailable();
        return true;
      });
    } catch (RuntimeException uncertain) {
      var resolved = tx(() -> findChecked(self, operation, digest, actor));
      if (resolved == null || !"APPLIED".equals(resolved.status) || !verified.equals(resolved.receipt)) throw unavailable();
      // Do not repeat a local callback or a best-effort external notification after uncertain commit.
      return false;
    }
  }

  private Intent prepare(Self self, String operation, String digest, TaskActorCredentials actor) {
    JsonNode original = SelfContactEmailSource.capture(jdbc, codec, self.account, self.tenant);
    Intent prior = find(operation);
    if (prior != null) return checked(self, prior, digest, actor);
    var target = codec.read("{}", ObjectNode.class);
    target.put("accountId", self.account);
    if (self.tenant == null) target.putNull("tenantId"); else target.put("tenantId", self.tenant.toString());
    var origin = target.putObject("origin");
    origin.put("kind", "SELF").put("workflowId", operation).put("humanSubject", self.account)
        .put("humanIssuer", issuer).put("humanClient", self.client);
    if (SelfAccountOwner.inspect(jdbc, self.account, self.tenant).kind().protectedTarget()) origin.put("humanAuthority", "PROTECTED_ACCOUNT_OPERATIONS_V1");
    else origin.putNull("humanAuthority");
    var row = new Intent(operation, self.account, self.tenant, actor.clientId(), actor.serviceSubject(),
        digest, codec.canonical(target), null, "PREPARED", false, null, null, "SELF_CONTACT_EMAIL");
    String source = seal(row, original, null);
    jdbc.update("insert into identity_account_operation_intent(operation_id,operation_kind,realm_id,realm_name,account_id,tenant_id,owner_client,owner_subject,request_digest,target_json,source_json,status,domain_applied,updated_at) values(?,'SELF_CONTACT_EMAIL',?,?,?,?,?,?,?,?,?,'PREPARED',false,?)", operation, realmId, realm, self.account, self.tenant, row.client, row.subject, digest, row.target, source, now());
    return findChecked(self, operation, digest, actor);
  }

  private Intent freeze(Self self, String operation, String digest, TaskActorCredentials actor, String state, String email) {
    var current = findChecked(self, operation, digest, actor);
    if (current == null) throw unavailable();
    if (!"PREPARED".equals(current.status)) return current;
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", codec.read(current.target, JsonNode.class));
    body.put("operationId", operation).put("expectedState", state);
    body.putObject("change").put("email", email).put("preserveEmailVerified", true);
    var updated = new Intent(operation, current.account, current.tenant, current.client, current.subject,
        digest, current.target, codec.canonical(body), "FROZEN", false, null, current.source, current.kind);
    String source = seal(updated, source(current).path("original"), null);
    if (jdbc.update("update identity_account_operation_intent set intent_body=?,source_json=?,status='FROZEN',updated_at=? where operation_id=? and operation_kind='SELF_CONTACT_EMAIL' and status='PREPARED' and domain_applied=false and request_digest=?", updated.body, source, now(), operation, digest) != 1) throw unavailable();
    return findChecked(self, operation, digest, actor);
  }

  private Intent findChecked(Self self, String operation, String digest, TaskActorCredentials actor) {
    var row = find(operation);
    return row == null ? null : checked(self, row, digest, actor);
  }

  private Intent checked(Self self, Intent row, String digest, TaskActorCredentials actor) {
    if (!"SELF_CONTACT_EMAIL".equals(row.kind) || !self.account.equals(row.account)
        || !Objects.equals(self.tenant, row.tenant) || !actor.clientId().equals(row.client)
        || !actor.serviceSubject().equals(row.subject) || !digest.equals(row.digest)) throw conflict();
    var data = source(row);
    if (!seal(row, data.path("original"), data.path("post").isNull() ? null : data.path("post")).equals(row.source)) throw unavailable();
    if (!Set.of("PREPARED", "FROZEN", "APPLIED", "REFUSED").contains(row.status)
        || row.applied != "APPLIED".equals(row.status)
        || "PREPARED".equals(row.status) != (row.body == null)
        || Set.of("APPLIED", "REFUSED").contains(row.status) != (row.receipt != null)
        || row.applied == data.path("post").isNull()) throw unavailable();
    JsonNode current = SelfContactEmailSource.capture(jdbc, codec, self.account, self.tenant);
    if (!current.equals(data.path(row.applied ? "post" : "original"))) throw conflict();
    if (row.applied) codec.verifyMaintenanceEmailReceipt(row.receipt, realmId, actor, codec.read(row.body, JsonNode.class));
    if ("REFUSED".equals(row.status)) refused(row, actor);
    return row;
  }

  private RuntimeException refused(Intent row, TaskActorCredentials actor) {
    if (row == null || !"REFUSED".equals(row.status)) throw unavailable();
    int status = codec.verifyMaintenanceNoEffectReceipt(row.receipt, realmId, actor,
        codec.read(row.body, JsonNode.class), "email").status();
    return switch (status) {
      case 400 -> new BadRequestException("Contact email change was refused");
      case 403 -> new ForbiddenException("Contact email change was refused");
      case 409 -> new ConflictException("Contact email change was refused");
      default -> throw unavailable();
    };
  }

  private ObjectNode source(Intent row) {
    var data = codec.read(row.source, ObjectNode.class);
    var keys = new HashSet<String>(); data.fieldNames().forEachRemaining(keys::add);
    if (!keys.equals(Set.of("v", "original", "post", "mac")) || !data.path("v").isIntegralNumber()
        || data.path("v").intValue() != 1 || !data.path("original").isObject()
        || !(data.path("post").isNull() || data.path("post").isObject())
        || !data.path("mac").isTextual() || !data.path("mac").asText().matches("[a-f0-9]{64}")) throw unavailable();
    return data;
  }

  private String seal(Intent row, JsonNode original, JsonNode post) {
    var data = codec.read("{}", ObjectNode.class); data.put("v", 1); data.set("original", original);
    if (post == null) data.putNull("post"); else data.set("post", post);
    var binding = new TreeMap<String, Object>();
    binding.put("realmId", realmId); binding.put("realm", realm); binding.put("operationId", row.operation);
    binding.put("kind", row.kind); binding.put("account", row.account); binding.put("tenant", row.tenant);
    binding.put("client", row.client); binding.put("subject", row.subject); binding.put("requestDigest", row.digest);
    binding.put("target", row.target); binding.put("body", row.body); binding.put("status", row.status);
    binding.put("applied", row.applied); binding.put("receipt", row.receipt); binding.put("source", data);
    String mac = codec.digest("self-contact-email-intent/v1", binding); data.put("mac", mac);
    return codec.canonical(data);
  }

  private Intent find(String operation) {
    var rows = jdbc.query("select * from identity_account_operation_intent where operation_id=? for update", (r, i) -> {
      if (!realmId.equals(r.getString("realm_id")) || !realm.equals(r.getString("realm_name"))) throw conflict();
      return row(r);
    }, operation);
    if (rows.size() > 1) throw unavailable();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static Intent row(ResultSet r) throws SQLException {
    long tenant = r.getLong("tenant_id"); Long value = r.wasNull() ? null : tenant;
    return new Intent(r.getString("operation_id"), r.getString("account_id"), value,
        r.getString("owner_client"), r.getString("owner_subject"), r.getString("request_digest"),
        r.getString("target_json"), r.getString("intent_body"), r.getString("status"),
        r.getBoolean("domain_applied"), r.getString("receipt"), r.getString("source_json"), r.getString("operation_kind"));
  }

  private String state(Self self, JsonNode read) {
    var fields = new HashSet<String>(); read.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(Set.of("id", "username", "email", "firstName", "lastName", "enabled", "emailVerified", "tenantId", "preferredLanguage", "effectiveRoles", "passwordChangeRequired", "state"))
        || !self.account.equals(read.path("id").textValue()) || !read.path("enabled").isBoolean()
        || !read.path("enabled").booleanValue() || !read.path("emailVerified").isBoolean()
        || !read.path("passwordChangeRequired").isBoolean() || !read.path("effectiveRoles").isArray()
        || !Objects.equals(self.tenant == null ? null : self.tenant.toString(), read.path("tenantId").isNull() ? null : read.path("tenantId").textValue())
        || !read.path("state").isTextual() || !read.path("state").asText().matches("[a-f0-9]{64}")) throw unavailable();
    for (String name : List.of("username", "email", "firstName", "lastName", "preferredLanguage")) {
      var value = read.path(name);
      if (!(value.isNull() || value.isTextual() && value.textValue().length() <= 255)) throw unavailable();
    }
    var roles = new HashSet<String>();
    for (var role : read.path("effectiveRoles")) if (!role.isTextual() || role.textValue().isBlank() || !roles.add(role.textValue())) throw unavailable();
    return read.path("state").textValue();
  }

  private String invoke(String command, Self self, String body, TaskActorCredentials actor) {
    if (TransactionSynchronizationManager.isActualTransactionActive() || !self.expires.isAfter(clock.instant())) throw unavailable();
    try {
      var machine = checkedMachine(actor); var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON); headers.setBearerAuth(machine.accessToken());
      headers.set("X-ORISO-Human-Authorization", "Bearer " + self.bearer);
      headers.set("X-ORISO-Task-Context", codec.maintenanceProof(command, realm, realmId, self.account, actor, codec.read(body, JsonNode.class)));
      var uri = UriComponentsBuilder.fromUriString(server).pathSegment("realms", realm, "oriso-account-maintenance", "v1", "accounts", self.account, command).build().encode().toUri();
      var reply = http.exchange(uri, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
      if (reply.getStatusCode().value() != 200 || reply.getBody() == null || reply.getBody().length() > 32768) throw unavailable();
      return reply.getBody();
    } catch (org.springframework.web.client.HttpStatusCodeException rejected) {
      if ("email".equals(command) && Set.of(400, 403, 409).contains(rejected.getStatusCode().value())) {
        String reply = rejected.getResponseBodyAsString();
        try {
          if (reply == null || reply.length() > 32768) throw unavailable();
          var verified = codec.verifyMaintenanceNoEffectReceipt(reply, realmId, actor, codec.read(body, JsonNode.class), "email");
          if (verified.status() != rejected.getStatusCode().value()) throw unavailable();
          throw new SignedRefusal(codec.canonical(codec.read(reply, JsonNode.class)));
        } catch (SignedRefusal valid) { throw valid; }
        catch (RuntimeException invalid) { throw unavailable(); }
      }
      throw unavailable();
    } catch (RuntimeException unknown) { throw unavailable(); }
  }

  private TaskActorTokenBroker.VerifiedMaintenanceToken checkedMachine(TaskActorCredentials actor) {
    var machine = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    long now = clock.instant().getEpochSecond();
    if (machine == null || actor.actor() != TaskActor.ACCOUNT_ADMIN || machine.actor() != actor.actor()
        || machine.purpose() != TaskPurpose.ACCOUNT_MAINTENANCE || !issuer.equals(machine.issuer())
        || !actor.clientId().equals(machine.clientId()) || !actor.serviceSubject().equals(machine.subject())
        || machine.validatedJwtIssuedAt() <= 0 || machine.validatedJwtIssuedAt() > now
        || machine.validatedJwtExpiresAt() <= now || machine.validatedJwtExpiresAt() <= machine.validatedJwtIssuedAt()) throw unavailable();
    return machine;
  }

  private Self actualSelf(boolean removal) {
    if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken auth) || !auth.isAuthenticated()
        || !(removal ? "DELETE" : "PUT").equals(request.getMethod())
        || !Set.of("/service/users/email", "/users/email").contains(request.getRequestURI())) throw forbidden();
    var jwt = auth.getToken(); String account = jwt.getSubject(), client = jwt.getClaimAsString("azp");
    if (!uuid(account) || !account.equals(human.getUserId()) || !jwt.getTokenValue().equals(human.getAccessToken())
        || jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())
        || jwt.getIssuedAt() == null || jwt.getExpiresAt() == null || jwt.getIssuedAt().getEpochSecond() <= 0
        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt()) || !jwt.getExpiresAt().isAfter(clock.instant())
        || client == null || client.isBlank() || client.length() > 255
        || request.getHeader("Authorization") == null || !request.getHeader("Authorization").regionMatches(true, 0, "Bearer ", 0, 7)
        || !jwt.getTokenValue().equals(request.getHeader("Authorization").substring(7))) throw forbidden();
    Object claim = jwt.getClaims().get("tenantId"); Long tenant = null;
    if (!(claim == null && !jwt.getClaims().containsKey("tenantId"))) {
      if (!(claim instanceof String text) || !text.matches("0|[1-9][0-9]{0,18}")) throw forbidden();
      try { tenant = Long.valueOf((String) claim); } catch (NumberFormatException invalid) { throw forbidden(); }
    }
    if (!Objects.equals(tenant, human.getTenantId())) throw forbidden();
    return new Self(account, tenant, client, jwt.getTokenValue(), jwt.getExpiresAt());
  }

  private String operation() {
    String key = request.getHeader("Idempotency-Key");
    if (!uuid(key)) throw new BadRequestException("A canonical Idempotency-Key is required for this account change");
    return key;
  }

  private static boolean uuid(String text) { try { return text != null && text.length() == 36 && UUID.fromString(text).toString().equals(text); } catch (IllegalArgumentException invalid) { return false; } }
  private static void requireSame(Intent first, Intent second) { if (second == null || !first.equals(second)) throw unavailable(); }
  private <T> T tx(Supplier<T> work) {
    var tx = new TransactionTemplate(transactions); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setTimeout(15);
    return tx.execute(s -> {
      if (!TransactionSynchronizationManager.isActualTransactionActive() || jdbc.getDataSource() == null
          || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()) || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
      return work.get();
    });
  }
  private java.sql.Timestamp now() { return java.sql.Timestamp.from(clock.instant()); }
  private static ServiceUnavailableException unavailable() { return new ServiceUnavailableException("Contact email change could not complete"); }
  private static ForbiddenException forbidden() { return new ForbiddenException("Current self contact-email account is required"); }
  private static ConflictException conflict() { return new ConflictException("Contact email attempt no longer matches its original account"); }
  private static final class SignedRefusal extends RuntimeException {
    private final String reply;
    private SignedRefusal(String reply) { super("Native contact email was refused"); this.reply = reply; }
  }
  private record Self(String account, Long tenant, String client, String bearer, Instant expires) { @Override public String toString() { return "SelfContact[redacted]"; } }
  private record Intent(String operation, String account, Long tenant, String client, String subject, String digest, String target, String body, String status, boolean applied, String receipt, String source, String kind) { @Override public String toString() { return "ContactEmailIntent[redacted]"; } }
}
