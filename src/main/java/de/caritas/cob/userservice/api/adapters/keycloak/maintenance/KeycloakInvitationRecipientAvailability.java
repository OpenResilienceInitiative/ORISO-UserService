package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ReviewedMaintenanceProfiles;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.port.out.InvitationRecipientAvailability;
import de.caritas.cob.userservice.api.port.out.InvitationRecipientAvailabilityUnavailableException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.ProtectedInvitationProfiles;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.*;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Closed boolean-only invitation read; no stock search or alternative technical identity. */
public final class KeycloakInvitationRecipientAvailability
    implements InvitationRecipientAvailability {
  private final RestTemplate http;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;
  private final AccountLifecycleCodec codec;
  private final String authServer, realmName, realmId;
  private final Supplier<Human> human;
  private final Map<Kind, ReviewedMaintenanceProfiles.Profile> profiles;

  public KeycloakInvitationRecipientAvailability(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      String authServer,
      String realmName,
      String realmId,
      Supplier<Human> human,
      ReviewedMaintenanceProfiles reviewed,
      ProtectedInvitationProfiles protectedProfiles) {
    this.http = Objects.requireNonNull(http);
    this.actors = Objects.requireNonNull(actors);
    this.broker = Objects.requireNonNull(broker);
    this.codec = Objects.requireNonNull(codec);
    try {
      var server = URI.create(authServer);
      if (!Set.of("http", "https").contains(server.getScheme())
          || server.getHost() == null
          || server.getRawUserInfo() != null
          || server.getRawQuery() != null
          || server.getRawFragment() != null) throw unavailable();
      this.authServer = authServer;
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
    this.realmName = Objects.requireNonNull(realmName);
    this.realmId = Objects.requireNonNull(realmId);
    this.human = Objects.requireNonNull(human);
    if (!realmId.equals(protectedProfiles.realmId())) throw unavailable();
    var selected = new EnumMap<Kind, ReviewedMaintenanceProfiles.Profile>(Kind.class);
    Map<Kind, Set<String>> ordinary =
        Map.of(
            Kind.CONSULTANT, Set.of("consultant"),
            Kind.AGENCY_ADMIN, Set.of("restricted-agency-admin", "user-admin"),
            Kind.CONSULTANT_AGENCY_ADMIN,
                Set.of("consultant", "restricted-agency-admin", "user-admin"));
    var all = reviewed.profiles().values().stream().flatMap(List::stream).toList();
    ordinary.forEach(
        (kind, roles) -> {
          var candidates = exactOrdinary(all, kind, roles);
          // Invitation DTOs request groupchat=false. Prefer that exact installed union;
          // only its absence permits the creator's other closed chat union. A duplicate
          // preferred union must refuse, never fall through to another profile or grant.
          if (candidates.isEmpty() && kind != Kind.AGENCY_ADMIN) {
            var chatRoles = new java.util.HashSet<>(roles);
            chatRoles.add("group-chat-consultant");
            candidates = exactOrdinary(all, kind, chatRoles);
          }
          // Missing support refuses that read, without inventing a profile or preventing
          // unrelated installed callers from starting. Ambiguity remains a configuration error.
          if (!candidates.isEmpty()) selected.put(kind, exactlyOne(candidates));
        });
    for (Kind kind : Set.of(Kind.TENANT_ADMIN, Kind.PLATFORM_ADMIN)) {
      String id = protectedProfiles.profileIds().get(AccountInviteTargetRole.valueOf(kind.name()));
      selected.put(
          kind,
          exactlyOne(
              reviewed.profiles().getOrDefault(kind.name(), List.of()).stream()
                  .filter(p -> p.family().equals("PROTECTED") && p.id().equals(id))
                  .toList()));
    }
    profiles = Map.copyOf(selected);
  }

  private static ReviewedMaintenanceProfiles.Profile exactlyOne(
      List<ReviewedMaintenanceProfiles.Profile> candidates) {
    if (candidates.size() != 1) throw unavailable();
    return candidates.getFirst();
  }

  private static List<ReviewedMaintenanceProfiles.Profile> exactOrdinary(
      List<ReviewedMaintenanceProfiles.Profile> profiles, Kind kind, Set<String> roles) {
    return profiles.stream()
        .filter(
            profile ->
                kind.name().equals(profile.ordinaryKind()) && profile.realmRoles().equals(roles))
        .toList();
  }

  @Override
  public boolean available(String normalizedEmail, Target target) {
    try {
      if (normalizedEmail == null
          || normalizedEmail.isBlank()
          || normalizedEmail.length() > 320
          || !normalizedEmail.equals(normalizedEmail.trim().toLowerCase(java.util.Locale.ROOT))
          || normalizedEmail
              .chars()
              .anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
          || normalizedEmail.indexOf('@') <= 0
          || normalizedEmail.indexOf('@') != normalizedEmail.lastIndexOf('@')
          || normalizedEmail.endsWith("@")
          || target == null
          || target.kind() == null
          || target.tenantMode() == null) throw unavailable();
      Long tenant = target.tenantId();
      if (tenant != null && tenant < 0
          || target.tenantMode() == TenantMode.EXISTING
              && (tenant == null || tenant == 0 && target.kind() != Kind.PLATFORM_ADMIN)
          || target.tenantMode() == TenantMode.RESERVED_NEW
              && (target.kind() == Kind.PLATFORM_ADMIN || tenant != null && tenant == 0)
          || target.tenantMode() == TenantMode.UNASSIGNED
              && (target.kind() != Kind.CONSULTANT || tenant != null)) throw unavailable();
      var profile = profiles.get(target.kind());
      if (profile == null) throw unavailable();
      Human current =
          human.get(); // Reuses the actual fresh request boundary, never a target lookup.
      if (current == null) throw unavailable();
      if (!Set.of("ORDINARY_ACCOUNT_OPERATIONS_V1", "PROTECTED_ACCOUNT_OPERATIONS_V1")
          .contains(current.authority())) throw forbidden();
      var origin = new java.util.TreeMap<String, Object>();
      origin.put("kind", "HUMAN_ADMIN");
      origin.put("workflowId", "invitation-email:preflight");
      origin.put("humanSubject", current.subject());
      origin.put("humanIssuer", current.issuer());
      origin.put("humanClient", current.client());
      origin.put("humanAuthority", current.authority());
      var body = new java.util.TreeMap<String, Object>();
      body.put("email", normalizedEmail);
      body.put(
          "profile",
          Map.of(
              "family",
              profile.family(),
              "id",
              profile.id(),
              "version",
              profile.version(),
              "kind",
              target.kind().name()));
      body.put("tenantId", tenant == null ? null : tenant.toString());
      body.put("tenantMode", target.tenantMode().name());
      body.put("origin", origin);
      String encoded = codec.canonical(body);
      if (encoded.getBytes(StandardCharsets.UTF_8).length > 4096) throw unavailable();
      var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
      var token = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
      if (actor.actor() != TaskActor.ACCOUNT_ADMIN
          || token.actor() != actor.actor()
          || token.purpose() != TaskPurpose.ACCOUNT_MAINTENANCE) throw unavailable();
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setAccept(List.of(MediaType.APPLICATION_JSON));
      headers.setCacheControl("no-store");
      headers.setBearerAuth(token.accessToken());
      headers.set("X-ORISO-Human-Authorization", current.rawBearer());
      headers.set(
          "X-ORISO-Task-Context",
          codec.invitationEmailAvailabilityProof(realmName, realmId, actor, body));
      var uri =
          UriComponentsBuilder.fromUriString(authServer)
              .pathSegment(
                  "realms",
                  realmName,
                  "oriso-account-maintenance",
                  "v1",
                  "availability",
                  "invitation-email")
              .build()
              .encode()
              .toUri();
      var response =
          http.exchange(
              uri,
              HttpMethod.POST,
              new HttpEntity<>(encoded.getBytes(StandardCharsets.UTF_8), headers),
              byte[].class);
      byte[] raw = response.getBody();
      if (response.getStatusCode().value() != 200
          || raw == null
          || raw.length > 128
          || !"no-store".equals(response.getHeaders().getCacheControl())) throw unavailable();
      var result = codec.read(codec.utf8(raw), com.fasterxml.jackson.databind.JsonNode.class);
      if (!result.isObject() || result.size() != 1 || !result.path("available").isBoolean())
        throw unavailable();
      return result.path("available").booleanValue();
    } catch (RestClientResponseException rejected) {
      if (Set.of(401, 403).contains(rejected.getStatusCode().value())) throw forbidden();
      throw unavailable();
    } catch (ForbiddenException rejected) {
      throw forbidden();
    } catch (RuntimeException failure) {
      // No provider body, recipient, token or nested exception leaves this boundary.
      throw unavailable();
    }
  }

  private static ForbiddenException forbidden() {
    return new ForbiddenException("Invitation recipient availability is not authorized");
  }

  private static InvitationRecipientAvailabilityUnavailableException unavailable() {
    return new InvitationRecipientAvailabilityUnavailableException();
  }
}
