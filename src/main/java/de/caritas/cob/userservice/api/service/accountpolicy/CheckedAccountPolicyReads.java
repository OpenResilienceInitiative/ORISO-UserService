package de.caritas.cob.userservice.api.service.accountpolicy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.ConsultantImportService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyFacts;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Policy authority exists only inside the checked, live precreation owner callback. */
@Component
@ConditionalOnProperty(name = "TASK_IDENTITY_MODEL", havingValue = "FIVE_ACTORS")
public final class CheckedAccountPolicyReads {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public record Policy(Long id, Integer allowedNumberOfUsers) {}

  private final AccountInviteRepository invites;
  private final AgencyFacts agencies;
  private final TaskActorTokenBroker broker;
  private final JwtDecoder decoder;
  private final RestTemplate http;
  private final byte[] key;
  private final String base;
  private final ThreadLocal<Origin> current = new ThreadLocal<>();
  @PersistenceContext private EntityManager entityManager;

  public CheckedAccountPolicyReads(
      AccountInviteRepository invites,
      AgencyFacts agencies,
      TaskActorTokenBroker broker,
      JwtDecoder decoder,
      @Qualifier("restTemplate") RestTemplate http,
      Environment environment,
      @Value("${tenant.service.api.url}") String base) {
    this.invites = invites;
    this.agencies = agencies;
    this.broker = broker;
    this.decoder = decoder;
    this.http = http;
    this.key = TaskContextProof.key(environment);
    this.base = base.replaceAll("/+$", "");
  }

  /** A raw verified link resolves the actual persisted locked row, never a supplied entity. */
  public <T> T withInvitation(String rawLink, String profile, Supplier<T> action) {
    Object transaction = transaction();
    if (rawLink == null || rawLink.isBlank() || rawLink.length() > 4096) throw denied();
    AccountInvite held =
        invites
            .findByTokenHash(AccountInviteService.hash(rawLink))
            .orElseThrow(CheckedAccountPolicyReads::denied);
    requireInvitation(held, profile);
    String revision = revision(invitationSnapshot(held));
    Origin origin =
        new Origin(
            "INVITATION",
            held.getTenantId(),
            List.of(held.getAgencyId()),
            profile,
            "invite:" + held.getId(),
            revision,
            null,
            transaction,
            Thread.currentThread(),
            () -> {
              AccountInvite fresh =
                  invites
                      .findByIdForUpdate(held.getId())
                      .orElseThrow(CheckedAccountPolicyReads::denied);
              requireInvitation(fresh, profile);
              if (!revision.equals(revision(invitationSnapshot(fresh)))) throw denied();
            });
    return within(origin, action);
  }

  /** The final class has a private constructor owned by the actual configured CSV loop. */
  public <T> T withConfiguredImport(
      ConsultantImportService.CheckedPolicyOrigin checked, Supplier<T> action) {
    if (checked == null) throw denied();
    checked.requireLive();
    var snapshot = checked.snapshot();
    Origin origin =
        new Origin(
            "IMPORT",
            checked.tenantId(),
            checked.agencyIds(),
            checked.profile(),
            checked.reference(),
            revision(snapshot),
            checked.sourceDigest(),
            null,
            Thread.currentThread(),
            checked::requireLive);
    return within(origin, action);
  }

  private <T> T within(Origin origin, Supplier<T> action) {
    if (current.get() != null) throw denied();
    current.set(origin);
    try {
      return action.get();
    } finally {
      origin.open = false;
      current.remove();
    }
  }

  /** An unscoped call, a foreign tenant, or use after the owner callback is refused locally. */
  public Policy read(long tenantId) {
    Origin origin = current.get();
    if (origin == null
        || !origin.open
        || origin.thread != Thread.currentThread()
        || origin.tenantId != tenantId
        || tenantId < 1
        || (origin.transaction != null && origin.transaction != transaction())) throw denied();
    origin.recheck.run();
    TaskPurpose purpose =
        "INVITATION".equals(origin.kind)
            ? TaskPurpose.INVITE_RESERVATIONS
            : TaskPurpose.CONSULTANT_IMPORT;
    var grant = broker.issue(purpose);
    // The same explicitly verified SETUP token supplies the current agency read and policy proof.
    try {
      for (Long agencyId : origin.agencyIds) {
        var agency =
            TechnicalAccessTokenContext.callWith(grant.accessToken(), () -> agencies.find(agencyId))
                .filter(
                    value ->
                        !value.deleted()
                            && Objects.equals(value.id(), agencyId)
                            && Objects.equals(value.tenantId(), tenantId))
                .orElseThrow(CheckedAccountPolicyReads::denied);
        if (agency.id() == null) throw denied();
      }
    } catch (RestClientException providerFailure) {
      throw unavailable();
    }
    origin.recheck.run();
    var caller = decoder.decode(grant.accessToken());
    if (caller.getIssuer() == null) throw denied();
    long now = Instant.now().getEpochSecond();
    Map<String, Object> checkedOrigin = origin.claims();
    Map<String, Object> claims = new TreeMap<>();
    claims.put("v", 2);
    claims.put("iss", "userservice");
    claims.put("aud", "tenantservice");
    claims.put("actor", "SETUP");
    claims.put("purpose", purpose.name());
    claims.put("operation", "wizard.account-policy.read");
    claims.put("method", "GET");
    claims.put("azp", caller.getClaimAsString("azp"));
    claims.put("sub", caller.getSubject());
    claims.put("tokenIssuer", caller.getIssuer().toString());
    claims.put("tenantId", tenantId);
    claims.put("target", "tenant/" + tenantId + "/account-provisioning-policy");
    claims.put("origin", checkedOrigin);
    claims.put("iat", now);
    claims.put("exp", now + 60);
    claims.put("nonce", UUID.randomUUID().toString().replace("-", ""));
    try {
      claims.put(
          "payloadDigest",
          TaskContextProof.digest(
              key,
              Map.of(
                  "tenantId",
                  tenantId,
                  "origin",
                  checkedOrigin,
                  "projection",
                  "account-provisioning-policy")));
      var headers = new HttpHeaders();
      headers.setBearerAuth(grant.accessToken());
      headers.set("X-ORISO-Wizard-Policy-Context", TaskContextProof.sign(key, claims));
      String url =
          UriComponentsBuilder.fromUriString(base)
              .pathSegment(
                  "internal", "tenants", Long.toString(tenantId), "account-provisioning-policy")
              .build()
              .toUriString();
      JsonNode result =
          JSON.readTree(
              http.exchange(url, HttpMethod.GET, new HttpEntity<Void>(headers), String.class)
                  .getBody());
      if (result == null
          || !result.isObject()
          || result.size() != 2
          || !result.has("id")
          || !result.has("allowedNumberOfUsers")) throw unavailable();
      JsonNode id = result.get("id");
      JsonNode cap = result.get("allowedNumberOfUsers");
      if (!id.isIntegralNumber()
          || !id.canConvertToLong()
          || id.longValue() < 1
          || id.longValue() != tenantId
          || (!cap.isNull()
              && (!cap.isIntegralNumber() || !cap.canConvertToInt() || cap.intValue() < 0)))
        throw unavailable();
      return new Policy(id.longValue(), cap.isNull() ? null : cap.intValue());
    } catch (Exception failure) {
      // Provider bodies, raw links and credentials never cross this boundary.
      throw unavailable();
    }
  }

  private Object transaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive() || entityManager == null)
      throw denied();
    Object owner =
        TransactionSynchronizationManager.getResource(entityManager.getEntityManagerFactory());
    if (owner == null) throw denied();
    return owner;
  }

  private void requireInvitation(AccountInvite invite, String profile) {
    if (!entityManager.contains(invite)
        || invite.getId() == null
        || invite.getId() < 1
        || invite.getTenantId() == null
        || invite.getTenantId() < 1
        || invite.getAgencyId() == null
        || invite.getAgencyId() < 1
        || invite.getPurpose() != AccountInvitePurpose.INVITE
        || invite.getStatus() != AccountInviteStatus.EMAIL_SENT
        || invite.getExpiresAt() == null
        || !invite.getExpiresAt().isAfter(LocalDateTime.now())
        || !Set.of("CONSULTANT", "CONSULTANT_AGENCY_ADMIN").contains(profile)) throw denied();
    boolean compound = "CONSULTANT_AGENCY_ADMIN".equals(profile);
    if (invite.getTargetRole() != AccountInviteTargetRole.COUNSELLOR
        && !(compound && invite.getTargetRole() == AccountInviteTargetRole.AGENCY_ADMIN))
      throw denied();
    if (compound
        && invite.getTargetRole() == AccountInviteTargetRole.COUNSELLOR
        && !IdAllocationMode.reservesAnId(invite.getAgencyIdAllocationMode())) throw denied();
  }

  private static Map<String, Object> invitationSnapshot(AccountInvite invite) {
    Map<String, Object> snapshot = new TreeMap<>();
    snapshot.put("id", invite.getId());
    snapshot.put("tenant", invite.getTenantId());
    snapshot.put("agency", invite.getAgencyId());
    snapshot.put("targetRole", invite.getTargetRole());
    snapshot.put("purpose", invite.getPurpose());
    snapshot.put("status", invite.getStatus());
    snapshot.put("expiresAt", Objects.toString(invite.getExpiresAt(), ""));
    snapshot.put("updatedAt", Objects.toString(invite.getUpdateDate(), ""));
    snapshot.put("tokenHash", invite.getTokenHash());
    snapshot.put("provisioningStatus", invite.getProvisioningStatus());
    snapshot.put("agencyAllocation", invite.getAgencyIdAllocationMode());
    snapshot.put("unitCreatedAt", Objects.toString(invite.getUnitCreatedAt(), ""));
    snapshot.put("tenantAllocation", invite.getTenantIdAllocationMode());
    snapshot.put("tenantReservation", invite.getTenantIdReservationToken());
    snapshot.put("alsoCounsellor", invite.getAlsoCounsellor());
    snapshot.put("recipientEmail", invite.getRecipientEmail());
    snapshot.put("firstName", invite.getFirstName());
    snapshot.put("lastName", invite.getLastName());
    snapshot.put("departmentId", invite.getDepartmentId());
    return snapshot;
  }

  private String revision(Object snapshot) {
    try {
      return TaskContextProof.digest(key, snapshot);
    } catch (Exception invalid) {
      throw denied();
    }
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Checked live account policy origin required");
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Account provisioning policy is unavailable");
  }

  private static final class Origin {
    private final String kind;
    private final long tenantId;
    private final List<Long> agencyIds;
    private final String profile;
    private final String reference;
    private final String revision;
    private final String sourceDigest;
    private final Object transaction;
    private final Thread thread;
    private final Runnable recheck;
    private boolean open = true;

    private Origin(
        String kind,
        long tenantId,
        List<Long> agencies,
        String profile,
        String reference,
        String revision,
        String sourceDigest,
        Object transaction,
        Thread thread,
        Runnable recheck) {
      this.kind = kind;
      this.tenantId = tenantId;
      this.agencyIds = List.copyOf(agencies);
      this.profile = profile;
      this.reference = reference;
      this.revision = revision;
      this.sourceDigest = sourceDigest;
      this.transaction = transaction;
      this.thread = thread;
      this.recheck = recheck;
    }

    private Map<String, Object> claims() {
      var fields = new TreeMap<String, Object>();
      fields.put("kind", kind);
      fields.put("tenantId", tenantId);
      fields.put("agencyIds", agencyIds);
      fields.put("profile", profile);
      fields.put("reference", reference);
      fields.put("revision", revision);
      if (sourceDigest != null) fields.put("sourceDigest", sourceDigest);
      return fields;
    }
  }
}
