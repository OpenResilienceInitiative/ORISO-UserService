package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.config.auth.TaskIdentity;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Typed bounded account commands; native Admin API payloads cannot pass through this adapter. */
@Component
public class KeycloakTaskCommands implements IdentityProvisioningCommands {
  public record AccountCreation(
      String username,
      String email,
      String firstName,
      String lastName,
      String preferredLanguage,
      Long tenantId,
      String password,
      boolean passwordTemporary,
      List<String> roles,
      String registrationKind) {
    public AccountCreation {
      roles = List.copyOf(roles);
    }

    @Override
    public String toString() {
      return "AccountCreation[redacted]";
    }
  }

  public record CreationResult(
      UUID attemptId,
      String accountId,
      String creationProof,
      String status,
      @com.fasterxml.jackson.annotation.JsonIgnore UUID executionClaim) {
    public CreationResult(UUID attemptId, String accountId, String creationProof, String status) {
      this(attemptId, accountId, creationProof, status, null);
    }

    @Override
    public String toString() {
      return "CreationResult[attemptId=" + attemptId + ", status=" + status + "]";
    }
  }

  public record AccountProjection(
      String id,
      String username,
      String email,
      String firstName,
      String lastName,
      Long tenantId,
      String preferredLanguage,
      boolean enabled,
      boolean emailVerified,
      List<String> roles,
      boolean passwordChangeRequired) {}

  private final RestTemplate http;
  private final TaskIdentityConfiguration identities;
  private final TaskIdentityGrant grants;
  private final IdentityOriginProof proof;
  private final String base;

  public KeycloakTaskCommands(
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      TaskIdentityConfiguration identities,
      TaskIdentityGrant grants,
      IdentityOriginProof proof,
      @Value("${keycloak.auth-server-url}") String host,
      @Value("${keycloak.realm}") String realm) {
    this.http = http;
    this.identities = identities;
    this.grants = grants;
    this.proof = proof;
    this.base =
        UriComponentsBuilder.fromUriString(host)
            .pathSegment("realms", realm, "oriso-commands", "v1")
            .build()
            .toUriString();
  }

  public CreationResult create(
      UUID attemptId, AccountCreation command, IdentityCommandAuthorization authorization) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("username", command.username());
    payload.put("email", command.email());
    payload.put("firstName", command.firstName());
    payload.put("lastName", command.lastName());
    payload.put("preferredLanguage", command.preferredLanguage());
    payload.put("tenantId", command.tenantId() == null ? null : command.tenantId().toString());
    payload.put("password", command.password());
    payload.put("passwordTemporary", command.passwordTemporary());
    payload.put("roles", command.roles());
    payload.put("registrationKind", command.registrationKind());
    return execute(
        TaskIdentity.ACCOUNT_PROVISIONING,
        "account.create",
        attemptId.toString(),
        "/account-creations/" + attemptId,
        HttpMethod.PUT,
        payload,
        authorization,
        CreationResult.class);
  }

  public record RecoveryResult(
      UUID attemptId, String accountId, String creationProof, String status) {
    @Override
    public String toString() {
      return "RecoveryResult[attemptId=" + attemptId + ", status=" + status + "]";
    }
  }

  public RecoveryResult recover(
      UUID attemptId, String registrationKind, IdentityCommandAuthorization authorization) {
    return execute(
        TaskIdentity.ACCOUNT_PROVISIONING,
        "account.creation-recover",
        attemptId.toString(),
        "/account-creations/" + attemptId + "/recovery-claims",
        HttpMethod.POST,
        Map.of("registrationKind", registrationKind),
        authorization,
        RecoveryResult.class);
  }

  public void commit(CreationResult creation, IdentityCommandAuthorization authorization) {
    finish("account.commit", "commit", creation, authorization);
  }

  public void compensate(CreationResult creation, IdentityCommandAuthorization authorization) {
    finish("account.compensate", "compensations", creation, authorization);
  }

  private void finish(
      String operation,
      String route,
      CreationResult creation,
      IdentityCommandAuthorization authorization) {
    execute(
        TaskIdentity.ACCOUNT_PROVISIONING,
        operation,
        creation.attemptId().toString(),
        "/account-creations/" + creation.attemptId() + "/" + route,
        HttpMethod.POST,
        Map.of("accountId", creation.accountId(), "creationProof", creation.creationProof()),
        authorization,
        Void.class);
  }

  public void password(
      String id, String password, boolean temporary, IdentityCommandAuthorization authorization) {
    execute(
        TaskIdentity.ACCOUNT_MAINTENANCE,
        "account.password",
        id,
        "/accounts/" + segment(id) + "/password",
        HttpMethod.PUT,
        Map.of("password", password, "passwordTemporary", temporary),
        authorization,
        Void.class);
  }

  public void profile(
      String id,
      String username,
      String email,
      String firstName,
      String lastName,
      Long tenantId,
      String preferredLanguage,
      IdentityCommandAuthorization authorization) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("username", username);
    payload.put("email", email);
    payload.put("firstName", firstName);
    payload.put("lastName", lastName);
    payload.put("tenantId", tenantId == null ? null : tenantId.toString());
    payload.put("preferredLanguage", preferredLanguage);
    execute(
        TaskIdentity.ACCOUNT_MAINTENANCE,
        "account.profile",
        id,
        "/accounts/" + segment(id) + "/profile",
        HttpMethod.PATCH,
        payload,
        authorization,
        Void.class);
  }

  public void profile(String id, Map<String, ?> patch, IdentityCommandAuthorization authorization) {
    if (patch.isEmpty()
        || !java.util.Set.of(
                "username", "email", "firstName", "lastName", "tenantId", "preferredLanguage")
            .containsAll(patch.keySet()))
      throw new AccessDeniedException("Profile command exceeds its bounded fields");
    execute(
        TaskIdentity.ACCOUNT_MAINTENANCE,
        "account.profile",
        id,
        "/accounts/" + segment(id) + "/profile",
        HttpMethod.PATCH,
        patch,
        authorization,
        Void.class);
  }

  @Override
  public AccountProjection ownedRead(String id, IdentityCommandAuthorization authorization) {
    return provisioningRead(id, authorization);
  }

  public AccountProjection provisioningRead(String id, IdentityCommandAuthorization authorization) {
    return execute(
        TaskIdentity.ACCOUNT_PROVISIONING,
        "account.read",
        id,
        "/accounts/" + segment(id),
        HttpMethod.GET,
        Map.of(),
        authorization,
        AccountProjection.class);
  }

  public List<AccountProjection> provisioningSearch(
      String field, String exactValue, IdentityCommandAuthorization authorization) {
    if (!List.of("email", "username").contains(field))
      throw new IllegalArgumentException("Only exact account lookups are supported");
    String path =
        UriComponentsBuilder.fromPath("/accounts/search")
            .queryParam(field, exactValue)
            .build()
            .encode()
            .toUriString();
    var found =
        execute(
            TaskIdentity.ACCOUNT_PROVISIONING,
            "account.search",
            exactValue,
            path,
            HttpMethod.GET,
            Map.of(field, exactValue),
            authorization,
            AccountProjection[].class);
    return found == null ? List.of() : List.of(found);
  }

  public void roles(
      String id, Collection<String> roles, IdentityCommandAuthorization authorization) {
    execute(
        TaskIdentity.ACCOUNT_MAINTENANCE,
        "account.roles",
        id,
        "/accounts/" + segment(id) + "/roles",
        HttpMethod.PUT,
        Map.of("roles", List.copyOf(roles)),
        authorization,
        Void.class);
  }

  public void deactivate(String id, IdentityCommandAuthorization authorization) {
    execute(
        TaskIdentity.ACCOUNT_MAINTENANCE,
        "account.deactivate",
        id,
        "/accounts/" + segment(id) + "/deactivation",
        HttpMethod.POST,
        Map.of(),
        authorization,
        Void.class);
  }

  public void delete(String id, IdentityCommandAuthorization authorization) {
    execute(
        TaskIdentity.ACCOUNT_MAINTENANCE,
        "account.delete",
        id,
        "/accounts/" + segment(id),
        HttpMethod.DELETE,
        Map.of(),
        authorization,
        Void.class);
  }

  public AccountProjection read(String id, IdentityCommandAuthorization authorization) {
    return execute(
        TaskIdentity.ACCOUNT_MAINTENANCE,
        "account.read",
        id,
        "/accounts/" + segment(id),
        HttpMethod.GET,
        Map.of(),
        authorization,
        AccountProjection.class);
  }

  public List<AccountProjection> search(
      String field, String exactValue, IdentityCommandAuthorization authorization) {
    if (!List.of("email", "username").contains(field))
      throw new IllegalArgumentException("Only exact account lookups are supported");
    String path =
        UriComponentsBuilder.fromPath("/accounts/search")
            .queryParam(field, exactValue)
            .build()
            .encode()
            .toUriString();
    var found =
        execute(
            TaskIdentity.ACCOUNT_MAINTENANCE,
            "account.search",
            exactValue,
            path,
            HttpMethod.GET,
            Map.of(field, exactValue),
            authorization,
            AccountProjection[].class);
    return found == null ? List.of() : List.of(found);
  }

  public de.caritas.cob.userservice.api.port.out.IdentityInactivityInventory.Page inventory(
      java.time.Instant cutoff, int first, int max, IdentityCommandAuthorization authorization) {
    return execute(
        TaskIdentity.ACCOUNT_MAINTENANCE,
        "account.inventory",
        "cutoff:" + cutoff + "/first:" + first + "/max:" + max,
        "/account-inventory",
        HttpMethod.POST,
        Map.of("cutoff", cutoff.toString(), "first", first, "max", max),
        authorization,
        de.caritas.cob.userservice.api.port.out.IdentityInactivityInventory.Page.class);
  }

  public de.caritas.cob.userservice.api.port.out.IdentityInactivityLifecycle.State lifecycleStatus(
      String id, IdentityCommandAuthorization authorization) {
    return execute(
        TaskIdentity.ACCOUNT_MAINTENANCE,
        "account.lifecycle-status",
        id,
        "/accounts/" + segment(id) + "/lifecycle-status",
        HttpMethod.GET,
        Map.of(),
        authorization,
        de.caritas.cob.userservice.api.port.out.IdentityInactivityLifecycle.State.class);
  }

  public void suspend(String id, IdentityCommandAuthorization authorization) {
    execute(
        TaskIdentity.ACCOUNT_MAINTENANCE,
        "account.suspend",
        id,
        "/accounts/" + segment(id) + "/suspension",
        HttpMethod.POST,
        Map.of(),
        authorization,
        Void.class);
  }

  public void restoreAccess(
      String id, boolean enabled, IdentityCommandAuthorization authorization) {
    execute(
        TaskIdentity.ACCOUNT_MAINTENANCE,
        "account.restore",
        id,
        "/accounts/" + segment(id) + "/access-restoration",
        HttpMethod.POST,
        Map.of("enabled", enabled),
        authorization,
        Void.class);
  }

  private <T> T execute(
      TaskIdentity task,
      String operation,
      String target,
      String path,
      HttpMethod method,
      Map<String, ?> command,
      IdentityCommandAuthorization authorization,
      Class<T> response) {
    if (!operation.equals(authorization.operation()) || !target.equals(authorization.target())) {
      throw new AccessDeniedException("Identity command is outside its originating authorization");
    }
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(grants.token(task));
    headers.set(
        "X-ORISO-Origin-Authorization",
        proof.issue(identities.require(task), authorization, command));
    return http.exchange(
            base + path,
            method,
            new HttpEntity<>(
                method == HttpMethod.GET ? null : proof.canonicalJson(command), headers),
            response)
        .getBody();
  }

  private static String segment(String id) {
    return UriComponentsBuilder.fromPath("/")
        .pathSegment(id)
        .build()
        .encode()
        .toUriString()
        .substring(1);
  }
}
