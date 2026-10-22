package de.caritas.cob.userservice.api.service.identity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.Authority;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport.FrozenProfile;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual SQL authorization, private native scope and exact profile roll-forward after unknowns. */
public final class HumanAdminAccountMaintenance {
  private final IdentityClientConfig mode;
  private final AuthenticatedUser actor;
  private final AdminScope scope;
  private final JdbcTemplate jdbc;
  private final HttpServletRequest request;
  private final HumanAdminMaintenanceTransport transport;
  private final Clock clock;
  private final String publicIssuer;
  private final TransactionTemplate transactions;
  private final ObjectMapper json =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);

  public HumanAdminAccountMaintenance(
      IdentityClientConfig mode,
      AuthenticatedUser actor,
      AdminScope scope,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      HttpServletRequest request,
      HumanAdminMaintenanceTransport transport,
      Clock clock,
      String publicIssuer) {
    this.mode = Objects.requireNonNull(mode);
    this.actor = Objects.requireNonNull(actor);
    this.scope = Objects.requireNonNull(scope);
    this.jdbc = Objects.requireNonNull(jdbc);
    this.request = Objects.requireNonNull(request);
    this.transport = Objects.requireNonNull(transport);
    this.clock = Objects.requireNonNull(clock);
    this.publicIssuer = Objects.requireNonNull(publicIssuer);
    javax.sql.DataSource owner = null;
    if (transactions instanceof JpaTransactionManager jpa) owner = jpa.getDataSource();
    else if (transactions instanceof DataSourceTransactionManager sql) owner = sql.getDataSource();
    if (owner == null || owner != jdbc.getDataSource()) throw unavailable();
    this.transactions = new TransactionTemplate(transactions);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public boolean isFiveActorMode() {
    return mode.isFiveActorMode();
  }

  public HumanAdminMaintenanceTransport.Projection read(String accountId) {
    requireOutsideSql();
    Human human = currentHuman();
    AdminRow row = authorizedRow(accountId, false);
    try (var checked = new CheckedAccount(row, row, human, UUID.randomUUID(), null)) {
      var result = transport.read(checked);
      requireProjection(row, result);
      return result;
    }
  }

  public de.caritas.cob.userservice.api.model.Admin updateAdminProfile(
      String accountId, IdentityProfileUpdate profile) {
    requireOutsideSql();
    if (!Set.of("PATCH", "PUT").contains(request.getMethod())) throw forbidden();
    Human human = currentHuman();
    UUID operation = operationId();
    AdminRow current = authorizedRow(accountId, false);
    // Consultant-only read scopes never authorize the Admin profile writer.
    if ("CONSULTANT".equals(current.type)) throw forbidden();
    validateProfile(current, profile);
    Intent prior = intent(operation, false);
    AdminRow original = prior == null ? current : decode(prior.original, AdminRow.class);
    if (!current.sameIdentity(original)) throw conflict();
    try (var checked = new CheckedAccount(current, original, human, operation, profile)) {
      String digest = transport.profileRequestDigest(checked, profile, operation);
      if (digest == null || !digest.matches("[0-9a-f]{64}")) throw unavailable();
      String originalJson = encode(original);
      String desiredJson = encode(profile);
      Intent intent =
          recoverCommit(
              () -> transaction(() -> prepare(checked, digest, originalJson, desiredJson)),
              () -> transaction(() -> checkedIntent(checked, digest, originalJson, desiredJson)));
      if ("APPLIED".equals(intent.status)) {
        requireDesired(authorizedRow(accountId, false), original, profile);
        transport.verifyProfileReceipt(checked, frozen(intent), intent.receipt);
        return profileResult(checked);
      }
      if ("PREPARED".equals(intent.status)) {
        var state = transport.read(checked);
        requireProjection(current, state);
        FrozenProfile frozen = transport.freezeProfile(checked, profile, operation, state.state());
        String body = encode(frozen);
        intent =
            recoverCommit(
                () -> transaction(() -> freeze(checked, digest, originalJson, desiredJson, body)),
                () ->
                    transaction(
                        () -> {
                          var found = checkedIntent(checked, digest, originalJson, desiredJson);
                          if (found == null
                              || !Set.of("FROZEN", "ACKNOWLEDGED", "APPLIED").contains(found.status)
                              || !body.equals(found.body)) throw unavailable();
                          return found;
                        }));
      }
      FrozenProfile frozen = frozen(intent);
      String receipt;
      if ("ACKNOWLEDGED".equals(intent.status)) {
        receipt = transport.verifyProfileReceipt(checked, frozen, intent.receipt);
      } else if ("FROZEN".equals(intent.status)) {
        receipt = transport.updateProfile(checked, frozen);
        final Intent originalIntent = intent;
        intent =
            recoverCommit(
                () ->
                    transaction(
                        () ->
                            acknowledge(
                                checked,
                                digest,
                                originalJson,
                                desiredJson,
                                originalIntent.body,
                                receipt)),
                () ->
                    transaction(
                        () -> {
                          var found = checkedIntent(checked, digest, originalJson, desiredJson);
                          if (found == null
                              || !Set.of("ACKNOWLEDGED", "APPLIED").contains(found.status)
                              || !Objects.equals(receipt, found.receipt)
                              || !Objects.equals(originalIntent.body, found.body))
                            throw unavailable();
                          return found;
                        }));
      } else throw unavailable();
      // No other legitimate admin-profile issuer may pass this account's pending SQL intent.
      // Confirm that the actual native fields still describe this acknowledged request.
      transport.verifyProfileReceipt(checked, frozen, receipt);
      var currentNative = transport.read(checked);
      requireProjection(checked.actual, currentNative);
      if (!Objects.equals(currentNative.firstName(), profile.firstName())
          || !Objects.equals(currentNative.lastName(), profile.lastName())
          || !Objects.equals(currentNative.email(), profile.email())) throw conflict();
      try {
        transaction(
            () -> {
              apply(checked, digest, originalJson, desiredJson, receipt);
              return null;
            });
      } catch (ConflictException | ForbiddenException refusal) {
        throw refusal;
      } catch (RuntimeException uncertain) {
        var found = transaction(() -> checkedIntent(checked, digest, originalJson, desiredJson));
        if (found == null
            || !"APPLIED".equals(found.status)
            || !Objects.equals(receipt, found.receipt)) throw unavailable();
        requireDesired(authorizedRow(accountId, false), original, profile);
      }
      return profileResult(checked);
    }
  }

  private de.caritas.cob.userservice.api.model.Admin profileResult(CheckedAccount checked) {
    return transaction(
        () -> {
          requireDesired(
              authorizedRow(checked.actual.account, true), checked.original, checked.profile);
          return jdbc.queryForObject(
              "select admin_id,tenant_id,username,type,first_name,last_name,email,managed_paused,id_old,create_date,update_date from admin where admin_id=?",
              (r, index) ->
                  de.caritas.cob.userservice.api.model.Admin.builder()
                      .id(r.getString("admin_id"))
                      .tenantId(nullableLong(r, "tenant_id"))
                      .username(r.getString("username"))
                      .type(
                          de.caritas.cob.userservice.api.model.Admin.AdminType.valueOf(
                              r.getString("type")))
                      .firstName(r.getString("first_name"))
                      .lastName(r.getString("last_name"))
                      .email(r.getString("email"))
                      .managedPaused(r.getBoolean("managed_paused"))
                      .oldId(nullableLong(r, "id_old"))
                      .createDate(
                          r.getTimestamp("create_date") == null
                              ? null
                              : r.getTimestamp("create_date").toLocalDateTime())
                      .updateDate(
                          r.getTimestamp("update_date") == null
                              ? null
                              : r.getTimestamp("update_date").toLocalDateTime())
                      .build(),
              checked.actual.account);
        });
  }

  private Intent prepare(CheckedAccount checked, String digest, String original, String desired) {
    var row = authorizedRow(checked.actual.account, true);
    var found = checkedIntent(checked, digest, original, desired);
    if (found != null) {
      requireOwnedRow(row, checked.original, checked.profile, found.status);
      return found;
    }
    if (!row.equals(checked.original)) throw conflict();
    if (!jdbc.queryForList(
            "select operation_id from general_admin_identity_intent where account_id=? and operation_id<>? and status<>'APPLIED' for update",
            String.class,
            row.account,
            checked.workflow.toString())
        .isEmpty()) throw conflict();
    Instant now = clock.instant();
    if (jdbc.update(
            "insert into general_admin_identity_intent(operation_id,account_id,tenant_id,human_subject,human_issuer,human_client,request_digest,original_json,desired_json,status,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,'PREPARED',?,?)",
            checked.workflow.toString(),
            row.account,
            row.tenant,
            checked.human.subject,
            checked.human.issuer,
            checked.human.client,
            digest,
            original,
            desired,
            Timestamp.from(now),
            Timestamp.from(now))
        != 1) throw unavailable();
    return Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
  }

  private Intent freeze(
      CheckedAccount checked, String digest, String original, String desired, String body) {
    var row = authorizedRow(checked.actual.account, true);
    var found = Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
    requireOwnedRow(row, checked.original, checked.profile, found.status);
    if (!"PREPARED".equals(found.status)) {
      if (!body.equals(found.body)) throw conflict();
      return found;
    }
    if (jdbc.update(
            "update general_admin_identity_intent set native_body=?,status='FROZEN',updated_at=? where operation_id=? and status='PREPARED' and native_body is null and native_receipt is null",
            body,
            Timestamp.from(clock.instant()),
            checked.workflow.toString())
        != 1) throw conflict();
    return Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
  }

  private Intent acknowledge(
      CheckedAccount checked,
      String digest,
      String original,
      String desired,
      String body,
      String receipt) {
    var row = authorizedRow(checked.actual.account, true);
    var found = Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
    requireOwnedRow(row, checked.original, checked.profile, found.status);
    if (!body.equals(found.body)) throw conflict();
    if (Set.of("ACKNOWLEDGED", "APPLIED").contains(found.status)) {
      if (!Objects.equals(receipt, found.receipt)) throw conflict();
      return found;
    }
    if (!"FROZEN".equals(found.status)
        || receipt == null
        || jdbc.update(
                "update general_admin_identity_intent set native_receipt=?,status='ACKNOWLEDGED',updated_at=? where operation_id=? and status='FROZEN' and native_receipt is null",
                receipt,
                Timestamp.from(clock.instant()),
                checked.workflow.toString())
            != 1) throw conflict();
    return Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
  }

  private void apply(
      CheckedAccount checked, String digest, String original, String desired, String receipt) {
    var row = authorizedRow(checked.actual.account, true);
    var found = Objects.requireNonNull(checkedIntent(checked, digest, original, desired));
    if (!receipt.equals(found.receipt)) throw unavailable();
    if ("APPLIED".equals(found.status)) {
      requireDesired(row, checked.original, checked.profile);
      return;
    }
    if (!"ACKNOWLEDGED".equals(found.status) || !row.equals(checked.original)) throw conflict();
    var profile = checked.profile;
    if (jdbc.update(
            "update admin set first_name=?,last_name=?,email=?,update_date=? where admin_id=? and username=? and type=? and ((tenant_id=?) or (tenant_id is null and ? is null)) and first_name=? and last_name=? and email=? and ((update_date=?) or (update_date is null and ? is null))",
            profile.firstName(),
            profile.lastName(),
            profile.email(),
            Timestamp.from(clock.instant()),
            row.account,
            row.username,
            row.type,
            row.tenant,
            row.tenant,
            row.firstName,
            row.lastName,
            row.email,
            timestamp(row.updated),
            timestamp(row.updated))
        != 1) throw conflict();
    if (row.secondary != null) {
      var secondary = row.secondary;
      if (jdbc.update(
              "update consultant set first_name=?,last_name=?,email=?,update_date=? where consultant_id=? and username=? and ((tenant_id=?) or (tenant_id is null and ? is null)) and delete_date is null and first_name=? and last_name=? and email=? and ((update_date=?) or (update_date is null and ? is null))",
              profile.firstName(),
              profile.lastName(),
              profile.email(),
              Timestamp.from(clock.instant()),
              secondary.account,
              secondary.username,
              secondary.tenant,
              secondary.tenant,
              secondary.firstName,
              secondary.lastName,
              secondary.email,
              timestamp(secondary.updated),
              timestamp(secondary.updated))
          != 1) throw conflict();
    }
    requireDesired(authorizedRow(row.account, true), row, profile);
    if (jdbc.update(
            "update general_admin_identity_intent set status='APPLIED',updated_at=? where operation_id=? and status='ACKNOWLEDGED' and native_receipt=?",
            Timestamp.from(clock.instant()),
            checked.workflow.toString(),
            receipt)
        != 1) throw conflict();
  }

  private Intent checkedIntent(
      CheckedAccount checked, String digest, String original, String desired) {
    currentHumanMatching(checked.human);
    var found = intent(checked.workflow, true);
    if (found == null) return null;
    if (!checked.actual.account.equals(found.account)
        || !Objects.equals(checked.actual.tenant, found.tenant)
        || !checked.human.subject.equals(found.humanSubject)
        || !checked.human.issuer.equals(found.humanIssuer)
        || !checked.human.client.equals(found.humanClient)
        || !digest.equals(found.digest)
        || !original.equals(found.original)
        || !desired.equals(found.desired)) throw conflict();
    if (!Set.of("PREPARED", "FROZEN", "ACKNOWLEDGED", "APPLIED").contains(found.status)
        || "PREPARED".equals(found.status) && (found.body != null || found.receipt != null)
        || !"PREPARED".equals(found.status) && found.body == null
        || Set.of("ACKNOWLEDGED", "APPLIED").contains(found.status) && found.receipt == null
        || "FROZEN".equals(found.status) && found.receipt != null) throw unavailable();
    return found;
  }

  private Intent intent(UUID operation, boolean lock) {
    var rows =
        jdbc.query(
            "select * from general_admin_identity_intent where operation_id=?"
                + (lock ? " for update" : ""),
            (r, index) ->
                new Intent(
                    r.getString("account_id"),
                    nullableLong(r, "tenant_id"),
                    r.getString("human_subject"),
                    r.getString("human_issuer"),
                    r.getString("human_client"),
                    r.getString("request_digest"),
                    r.getString("original_json"),
                    r.getString("desired_json"),
                    r.getString("native_body"),
                    r.getString("native_receipt"),
                    r.getString("status")),
            operation.toString());
    if (rows.size() > 1) throw unavailable();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private AdminRow authorizedRow(String account, boolean lock) {
    canonicalUuid(account);
    var rows =
        jdbc.query(
            "select a.admin_id,a.tenant_id,a.username,a.type,a.first_name,a.last_name,a.email,a.update_date,c.consultant_id,c.tenant_id as consultant_tenant,c.username as consultant_username,c.first_name as consultant_first,c.last_name as consultant_last,c.email as consultant_email,c.delete_date as consultant_deleted,c.update_date as consultant_updated,u.user_id as user_collision from (select cast(? as char(36)) as requested_id) owned left join admin a on a.admin_id=owned.requested_id left join consultant c on c.consultant_id=owned.requested_id left join user u on u.user_id=owned.requested_id"
                + (lock ? " for update" : ""),
            (r, index) -> {
              if (r.getString("user_collision") != null) throw forbidden();
              ConsultantRow secondary =
                  r.getString("consultant_id") == null
                      ? null
                      : new ConsultantRow(
                          r.getString("consultant_id"), nullableLong(r, "consultant_tenant"),
                          r.getString("consultant_username"), r.getString("consultant_first"),
                          r.getString("consultant_last"), r.getString("consultant_email"),
                          instant(r, "consultant_deleted"), instant(r, "consultant_updated"));
              if (r.getString("admin_id") == null) {
                if (secondary == null) return null;
                if (secondary.deleted != null) throw forbidden();
                return new AdminRow(secondary.account, secondary.tenant, secondary.username,
                    "CONSULTANT", secondary.firstName, secondary.lastName, secondary.email,
                    secondary.updated, null);
              }
              return new AdminRow(
                  r.getString("admin_id"),
                  nullableLong(r, "tenant_id"),
                  r.getString("username"),
                  r.getString("type"),
                  r.getString("first_name"),
                  r.getString("last_name"),
                  r.getString("email"),
                  instant(r, "update_date"),
                  secondary);
            },
            account);
    if (rows.isEmpty() || rows.size() == 1 && rows.getFirst() == null)
      throw new NotFoundException("Managed account was not found");
    if (rows.size() != 1) throw forbidden();
    var row = rows.getFirst();
    var authorities = actor.getGrantedAuthorities();
    if (authorities == null
        || !("CONSULTANT".equals(row.type)
                && authorities.contains(Authority.AuthorityValue.USER_ADMIN)
            || "TENANT".equals(row.type)
                && authorities.contains(Authority.AuthorityValue.TENANT_ADMIN)
            || "AGENCY".equals(row.type)
                && authorities.contains(Authority.AuthorityValue.USER_ADMIN)
            || "SUPER".equals(row.type)
                && actor.isPlatformAdmin()
                && authorities.contains(Authority.AuthorityValue.TENANT_ADMIN))) throw forbidden();
    var secondary = row.secondary;
    if (secondary != null
        && (secondary.deleted != null
            || !row.account.equals(secondary.account)
            || !Objects.equals(row.tenant, secondary.tenant)
            || !decodedUsername(row.username).equals(decodedUsername(secondary.username))))
      throw forbidden();
    scope.assertMay("CONSULTANT".equals(row.type)
        ? AdminScope.Target.counsellor(account) : AdminScope.Target.admin(account));
    if (secondary != null) scope.assertMay(AdminScope.Target.counsellor(account));
    return row;
  }

  private Human currentHuman() {
    if (!mode.isFiveActorMode()
        || !(request.getUserPrincipal() instanceof JwtAuthenticationToken authentication)
        || !authentication.isAuthenticated()) throw forbidden();
    var jwt = authentication.getToken();
    Object azp = jwt.getClaims().get("azp");
    String raw = request.getHeader("Authorization");
    if (raw == null
        || raw.length() > 32775
        || !raw.regionMatches(true, 0, "Bearer ", 0, 7)
        || !jwt.getTokenValue().equals(raw.substring(7))
        || !jwt.getTokenValue().equals(actor.getAccessToken())
        || !Objects.equals(jwt.getSubject(), actor.getUserId())
        || jwt.getIssuer() == null
        || !publicIssuer.equals(jwt.getIssuer().toString())
        || !(azp instanceof String client)
        || client.isBlank()
        || client.length() > 255
        || jwt.getIssuedAt() == null
        || jwt.getIssuedAt().getEpochSecond() <= 0
        || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(5))
        || jwt.getExpiresAt() == null
        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
        || !jwt.getExpiresAt().isAfter(clock.instant())) throw forbidden();
    canonicalUuid(jwt.getSubject());
    Object suppliedTenant = jwt.getClaims().get("tenantId");
    Long tenant = null;
    if (jwt.getClaims().containsKey("tenantId")) {
      if (!(suppliedTenant instanceof String value) || !value.matches("0|[1-9][0-9]{0,18}"))
        throw forbidden();
      try {
        tenant = Long.valueOf(value);
      } catch (NumberFormatException invalid) {
        throw forbidden();
      }
    }
    if (!Objects.equals(tenant, actor.getTenantId())) throw forbidden();
    return new Human(jwt.getSubject(), publicIssuer, client, raw, jwt.getExpiresAt());
  }

  private void currentHumanMatching(Human expected) {
    if (!expected.equals(currentHuman())) throw forbidden();
  }

  private void requireOutsideSql() {
    if (!mode.isFiveActorMode()
        || TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.hasResource(
            Objects.requireNonNull(jdbc.getDataSource()))) throw unavailable();
  }

  private UUID operationId() {
    String supplied = request.getHeader("Idempotency-Key");
    try {
      return canonicalUuid(supplied);
    } catch (RuntimeException invalid) {
      throw new BadRequestException(
          "A canonical Idempotency-Key is required for this account change");
    }
  }

  private static UUID canonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
      return parsed;
    } catch (RuntimeException invalid) {
      throw forbidden();
    }
  }

  private static void requireProjection(
      AdminRow row, HumanAdminMaintenanceTransport.Projection projection) {
    if (projection == null
        || !row.account.equals(projection.id())
        || !Objects.equals(row.tenant, projection.tenantId())
        || !decodedUsername(row.username).equals(projection.username())) throw unavailable();
  }

  private static void validateProfile(AdminRow row, IdentityProfileUpdate profile) {
    if (profile == null
        || !Objects.equals(row.username, profile.username())
        || !Objects.equals(row.tenant, profile.tenantId())) throw forbidden();
    for (String value :
        List.of(
            profile.firstName() == null ? "" : profile.firstName(),
            profile.lastName() == null ? "" : profile.lastName(),
            profile.email() == null ? "" : profile.email()))
      if (value.isBlank()
          || value.length() > 255
          || value.chars().anyMatch(Character::isISOControl))
        throw new BadRequestException("Managed profile fields are invalid");
  }

  private static void requireOwnedRow(
      AdminRow row, AdminRow original, IdentityProfileUpdate desired, String status) {
    if ("APPLIED".equals(status)) requireDesired(row, original, desired);
    else if (!row.equals(original)) throw conflict();
  }

  private static void requireDesired(
      AdminRow row, AdminRow original, IdentityProfileUpdate desired) {
    if (!row.sameIdentity(original)
        || !Objects.equals(row.firstName, desired.firstName())
        || !Objects.equals(row.lastName, desired.lastName())
        || !Objects.equals(row.email, desired.email())
        || row.secondary != null
            && (!Objects.equals(row.secondary.firstName, desired.firstName())
                || !Objects.equals(row.secondary.lastName, desired.lastName())
                || !Objects.equals(row.secondary.email, desired.email()))) throw conflict();
  }

  private static String decodedUsername(String value) {
    if (value == null) throw forbidden();
    return new de.caritas.cob.userservice.api.helper.UsernameTranscoder().decodeUsername(value);
  }

  private FrozenProfile frozen(Intent intent) {
    if (intent == null || intent.body == null) throw unavailable();
    return decode(intent.body, FrozenProfile.class);
  }

  private <T> T transaction(Supplier<T> action) {
    return transactions.execute(ignored -> action.get());
  }

  private <T> T recoverCommit(Supplier<T> action, Supplier<T> resolve) {
    try {
      return action.get();
    } catch (ConflictException | ForbiddenException | BadRequestException refusal) {
      throw refusal;
    } catch (RuntimeException unknown) {
      T saved = resolve.get();
      if (saved == null) throw unavailable();
      return saved;
    }
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception invalid) {
      throw unavailable();
    }
  }

  private <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception invalid) {
      throw unavailable();
    }
  }

  private static Long nullableLong(ResultSet row, String name) throws SQLException {
    long value = row.getLong(name);
    return row.wasNull() ? null : value;
  }

  private static String instant(ResultSet row, String name) throws SQLException {
    Timestamp value = row.getTimestamp(name);
    return value == null ? null : value.toInstant().toString();
  }

  private static Timestamp timestamp(String value) {
    return value == null ? null : Timestamp.from(Instant.parse(value));
  }

  private record Human(
      String subject, String issuer, String client, String rawBearer, Instant expires) {
    @Override
    public String toString() {
      return "Human[authorization=redacted]";
    }
  }

  private record AdminRow(
      String account,
      Long tenant,
      String username,
      String type,
      String firstName,
      String lastName,
      String email,
      String updated,
      ConsultantRow secondary) {
    boolean sameIdentity(AdminRow other) {
      return account.equals(other.account)
          && Objects.equals(tenant, other.tenant)
          && username.equals(other.username)
          && type.equals(other.type)
          && (secondary == null
              ? other.secondary == null
              : other.secondary != null && secondary.sameIdentity(other.secondary));
    }

    @Override
    public String toString() {
      return "AdminRow[profile=redacted]";
    }
  }

  private record ConsultantRow(
      String account,
      Long tenant,
      String username,
      String firstName,
      String lastName,
      String email,
      String deleted,
      String updated) {
    boolean sameIdentity(ConsultantRow other) {
      return account.equals(other.account)
          && Objects.equals(tenant, other.tenant)
          && Objects.equals(username, other.username)
          && Objects.equals(deleted, other.deleted);
    }

    @Override
    public String toString() {
      return "ConsultantRow[profile=redacted]";
    }
  }

  private record Intent(
      String account,
      Long tenant,
      String humanSubject,
      String humanIssuer,
      String humanClient,
      String digest,
      String original,
      String desired,
      String body,
      String receipt,
      String status) {
    @Override
    public String toString() {
      return "Intent[ownership=redacted]";
    }
  }

  /** Only the actual SQL/human issuer creates this live frame. DTOs and saved tuples cannot. */
  public final class CheckedAccount implements AutoCloseable {
    private final AdminRow actual, original;
    private final Human human;
    private final UUID workflow;
    private final IdentityProfileUpdate profile;
    private final String method = request.getMethod();
    private final String path = request.getRequestURI();
    private final Thread owner = Thread.currentThread();
    private final Instant deadline = clock.instant().plusSeconds(60);
    private boolean active = true;

    private CheckedAccount(
        AdminRow actual,
        AdminRow original,
        Human human,
        UUID workflow,
        IdentityProfileUpdate profile) {
      this.actual = actual;
      this.original = original;
      this.human = human;
      this.workflow = workflow;
      this.profile = profile;
    }

    public String accountId() {
      return actual.account;
    }

    public Long tenantId() {
      return actual.tenant;
    }

    public String humanSubject() {
      return human.subject;
    }

    public String humanIssuer() {
      return human.issuer;
    }

    public String humanClient() {
      return human.client;
    }

    public String rawHumanBearer() {
      requireCurrent();
      return human.rawBearer;
    }

    public UUID workflowId() {
      return workflow;
    }

    public String requestMethod() {
      return method;
    }

    public String requestPath() {
      return path;
    }

    public String originalSqlState() {
      return encode(original);
    }

    public IdentityProfileUpdate desiredProfile() {
      return profile;
    }

    public void requireCurrent() {
      if (!active
          || owner != Thread.currentThread()
          || !clock.instant().isBefore(deadline)
          || !clock.instant().isBefore(human.expires)
          || !Objects.equals(method, request.getMethod())
          || !Objects.equals(path, request.getRequestURI())) throw forbidden();
      requireOutsideSql();
      currentHumanMatching(human);
      if (!actual.equals(authorizedRow(actual.account, false))) throw conflict();
    }

    @Override
    public void close() {
      active = false;
    }

    @Override
    public String toString() {
      return "CheckedAccount[authorization=redacted]";
    }
  }

  private static ConflictException conflict() {
    return new ConflictException("The account operation conflicts with current state");
  }

  private static ForbiddenException forbidden() {
    return new ForbiddenException("Managed account operation is not authorized");
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Checked admin maintenance is unavailable");
  }
}
