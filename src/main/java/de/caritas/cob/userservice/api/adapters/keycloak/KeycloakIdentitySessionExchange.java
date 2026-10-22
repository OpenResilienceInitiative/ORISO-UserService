package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentitySessionExchange;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService.ClaimedMagicLink;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/** Keycloak token-exchange adapter for trusted identity subjects. */
@Component
@RequiredArgsConstructor
@Slf4j
public class KeycloakIdentitySessionExchange implements IdentitySessionExchange {

  private static final String TOKEN_ENDPOINT_PATH = "/token";
  private static final String TOKEN_GRANT_CLIENT_CREDENTIALS = "client_credentials";
  private static final String TOKEN_GRANT_EXCHANGE =
      "urn:ietf:params:oauth:grant-type:token-exchange";
  private static final Set<String> REQUIRED_ADMIN_MANAGEMENT_ROLES =
      Set.of("manage-users", "view-users", "query-users", "view-realm");
  private static final Set<String> ALLOWED_ADMIN_MANAGEMENT_ROLES =
      Set.of("manage-users", "view-users", "query-users", "view-realm", "query-groups");

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull JwtDecoder jwtDecoder;
  private final @NonNull TaskActorConfiguration taskActors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull ObjectProvider<AccountLifecycleCodec> lifecycleCodec;

  @Value("${keycloak.realm:}")
  private String realmName;

  @Value("${KEYCLOAK_REALM_INTERNAL_ID:}")
  private String realmId;

  @Override
  public Optional<IdentitySession> exchangeMagicLink(ClaimedMagicLink claim) {
    return exchangeMagicLink(claim, null);
  }

  @Override
  public Optional<IdentitySession> exchangeMagicLink(ClaimedMagicLink claim, String otp) {
    try {
      if (!taskActors.isEnabled()
          || claim == null
          || otp != null && !otp.matches("[0-9]{6}")
          || !"app".equals(keycloakAppClientId)
          || org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()) return Optional.empty();
      claim.requireCurrent();
      var actor = broker.issueCheckedSessionExchange();
      claim.requireCurrent();
      var codec = lifecycleCodec.getObject();
      var body = new java.util.TreeMap<String, Object>();
      body.put("version", 1);
      body.put("workflowId", claim.workflowId().toString());
      body.put("targetUserId", claim.targetUserId());
      body.put("tenantId", claim.tenantId());
      body.put("clientId", "app");
      body.put("expiresAt", claim.expiresAt().getEpochSecond());
      body.put("claimDigest", claim.claimDigest());
      var command =
          codec.read(codec.canonical(body), com.fasterxml.jackson.databind.JsonNode.class);
      String url = identityClientConfig.getOpenIdConnectUrl(TOKEN_ENDPOINT_PATH);
      var endpoint = java.net.URI.create(url);
      if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
          || endpoint.getHost() == null
          || endpoint.getUserInfo() != null
          || endpoint.getFragment() != null
          || !url.equals(actor.issuer() + "/protocol/openid-connect/token"))
        return Optional.empty();
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      headers.setBearerAuth(actor.accessToken());
      headers.set("X-ORISO-Task-Context", codec.magicLinkProof(actor, realmName, realmId, command));
      MultiValueMap<String, String> form = new KeycloakAuthClient.SensitiveKeycloakFormData();
      form.add("client_id", "app");
      form.add("grant_type", "password");
      form.add("oriso_magic_command", codec.canonical(command));
      if (otp != null) form.add("otp", otp);
      claim.requireCurrent();
      ResponseEntity<KeycloakLoginResponseDTO> response;
      try {
        response = restTemplate.postForEntity(
            url, new HttpEntity<>(form, headers), KeycloakLoginResponseDTO.class);
      } catch (org.springframework.web.client.RestClientResponseException refused) {
        claim.requireCurrent();
        var challenge = checkedChallenge(refused, codec);
        if (challenge != null) throw challenge;
        return Optional.empty();
      }
      if (response.getStatusCode().value() != 200 || response.getBody() == null)
        return Optional.empty();
      var value = response.getBody();
      if (isBlank(value.getAccessToken())
          || isBlank(value.getRefreshToken())
          || !"Bearer".equals(value.getTokenType())
          || value.getExpiresIn() <= 0
          || value.getRefreshExpiresIn() < 0) return Optional.empty();
      var jwt = jwtDecoder.decode(value.getAccessToken());
      Object subject = jwt.getClaims().get("sub"),
          client = jwt.getClaims().get("azp"),
          sid = jwt.getClaims().get("sid");
      if (!(subject instanceof String)
          || !claim.targetUserId().equals(subject)
          || !"app".equals(client)
          || !(sid instanceof String sessionId)
          || !java.util.UUID.fromString(sessionId).toString().equals(sessionId)
          || !sessionId.equals(value.getSessionState())
          || jwt.getIssuer() == null
          || !actor.issuer().equals(jwt.getIssuer().toString())
          || jwt.getIssuedAt() == null
          || jwt.getIssuedAt().isAfter(Instant.now())
          || jwt.getExpiresAt() == null
          || !jwt.getExpiresAt().isAfter(Instant.now())) return Optional.empty();
      Object tenant = jwt.getClaims().get("tenantId");
      if (claim.tenantId() == null
          ? jwt.getClaims().containsKey("tenantId")
          : !(tenant instanceof String) || !Long.toString(claim.tenantId()).equals(tenant))
        return Optional.empty();
      claim.requireCurrent();
      return Optional.of(toIdentitySession(value));
    } catch (MagicLinkChallenge challenge) {
      throw challenge;
    } catch (RuntimeException unavailable) {
      // Decoder/provider diagnostics may contain private native bodies. No fallback or raw logging.
      return Optional.empty();
    }
  }

  private static MagicLinkChallenge checkedChallenge(
      org.springframework.web.client.RestClientResponseException refused, AccountLifecycleCodec codec) {
    try {
      int status = refused.getStatusCode().value();
      if (status != 400 && status != 429 || refused.getResponseHeaders() == null
          || refused.getResponseHeaders().getContentType() == null
          || !MediaType.APPLICATION_JSON.isCompatibleWith(refused.getResponseHeaders().getContentType())
          || refused.getResponseBodyAsByteArray().length > 4096) return null;
      var body = codec.read(refused.getResponseBodyAsString(java.nio.charset.StandardCharsets.UTF_8),
          com.fasterxml.jackson.databind.JsonNode.class);
      var allowed = Set.of("error", "error_description", "otpType", "resendAvailableInSeconds");
      if (!body.isObject() || !body.has("otpType") || !body.get("otpType").isTextual()) return null;
      var names = body.fieldNames();
      while (names.hasNext()) if (!allowed.contains(names.next())) return null;
      if (body.has("error") && (!body.get("error").isTextual()
          || !"invalid_grant".equals(body.get("error").textValue()))) return null;
      if (body.has("error_description") && !body.get("error_description").isTextual()) return null;
      String type = body.get("otpType").textValue();
      if (!Set.of("APP", "EMAIL").contains(type)) return null;
      var retry = body.get("resendAvailableInSeconds");
      Integer seconds = null;
      if (retry != null && !retry.isNull()) {
        if (!retry.isIntegralNumber() || !retry.canConvertToInt() || retry.intValue() < 0) return null;
        seconds = retry.intValue();
      }
      if ("APP".equals(type) && (seconds != null || status != 400)) return null;
      return new MagicLinkChallenge(type, seconds, status);
    } catch (RuntimeException malformed) {
      return null;
    }
  }

  @Value("${keycloak.config.admin-client-id}")
  private String keycloakAdminClientId;

  @Value("${keycloak.config.admin-client-secret}")
  private String keycloakAdminClientSecret;

  @Value("${keycloak.config.admin-service-subject}")
  private String keycloakAdminServiceSubject;

  @Value("${keycloak.config.app-client-id:app}")
  private String keycloakAppClientId;

  @Override
  public Optional<IdentitySession> exchangeForUser(String identityUserId) {
    if (taskActors.isEnabled()) return Optional.empty();
    String adminToken = loginAdminForToken();
    if (isBlank(adminToken)) {
      return Optional.empty();
    }

    try {
      MultiValueMap<String, String> form = new KeycloakAuthClient.SensitiveKeycloakFormData();
      form.add("grant_type", TOKEN_GRANT_EXCHANGE);
      form.add("client_id", keycloakAppClientId);
      form.add("subject_token", adminToken);
      form.add("requested_subject", identityUserId);
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
      String tokenUrl = identityClientConfig.getOpenIdConnectUrl(TOKEN_ENDPOINT_PATH);
      var response =
          restTemplate.postForEntity(tokenUrl, entity, KeycloakLoginResponseDTO.class).getBody();
      return Optional.ofNullable(response).map(KeycloakIdentitySessionExchange::toIdentitySession);
    } catch (Exception exchangeFailure) {
      log.warn("Identity session exchange failed ({})", exchangeFailure.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private String loginAdminForToken() {
    try {
      MultiValueMap<String, String> form = new KeycloakAuthClient.SensitiveKeycloakFormData();
      if (isBlank(keycloakAdminClientId)
          || isBlank(keycloakAdminClientSecret)
          || keycloakAdminClientId.equals(keycloakAppClientId)) {
        return null;
      }
      form.add("grant_type", TOKEN_GRANT_CLIENT_CREDENTIALS);
      form.add("client_id", keycloakAdminClientId);
      form.add("client_secret", keycloakAdminClientSecret);
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
      String tokenUrl = identityClientConfig.getOpenIdConnectUrl(TOKEN_ENDPOINT_PATH);
      var response = restTemplate.postForEntity(tokenUrl, entity, Map.class);
      if (response.getBody() == null) {
        return null;
      }
      Object token = response.getBody().get("access_token");
      if (!(token instanceof String accessToken)
          || isBlank(accessToken)
          || !isExpectedAdminToken(accessToken)) {
        return null;
      }
      return accessToken;
    } catch (Exception loginFailure) {
      log.warn("Identity admin-session login failed ({})", loginFailure.getClass().getSimpleName());
      return null;
    }
  }

  private boolean isExpectedAdminToken(String accessToken) {
    var jwt = jwtDecoder.decode(accessToken);
    var realmAccess = jwt.getClaimAsMap("realm_access");
    Object rolesValue = realmAccess == null ? null : realmAccess.get("roles");
    if (!(rolesValue instanceof Collection<?> roles)) {
      return false;
    }
    return Set.of("otp-config-admin").equals(new HashSet<>(roles))
        && !isBlank(keycloakAdminServiceSubject)
        && keycloakAdminServiceSubject.equals(jwt.getSubject())
        && keycloakAdminClientId.equals(jwt.getClaimAsString("azp"))
        && jwt.getExpiresAt() != null
        && jwt.getExpiresAt().isAfter(Instant.now())
        && hasExpectedAdminResources(jwt.getClaimAsMap("resource_access"));
  }

  private static boolean hasExpectedAdminResources(Map<String, Object> resourceAccess) {
    if (resourceAccess == null
        || !resourceAccess.keySet().equals(Set.of("realm-management"))
        || !(resourceAccess.get("realm-management") instanceof Map<?, ?> realmManagement)
        || !(realmManagement.get("roles") instanceof Collection<?> roles)
        || roles.stream().anyMatch(role -> !(role instanceof String))) {
      return false;
    }
    var actualRoles = new HashSet<>(roles);
    return actualRoles.containsAll(REQUIRED_ADMIN_MANAGEMENT_ROLES)
        && ALLOWED_ADMIN_MANAGEMENT_ROLES.containsAll(actualRoles);
  }

  private static IdentitySession toIdentitySession(KeycloakLoginResponseDTO response) {
    return new IdentitySession(
        response.getAccessToken(),
        response.getExpiresIn(),
        response.getRefreshExpiresIn(),
        response.getRefreshToken(),
        response.getTokenType(),
        response.getSessionState(),
        response.getScope());
  }
}
