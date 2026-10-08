package de.caritas.cob.userservice.api.testHelper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant;
import de.caritas.cob.userservice.api.config.auth.TaskIdentity;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestTemplate;

/** Replaces only native provider HTTP/auth seams; real origins, signing and journal stay active. */
public final class BoundedIdentityHttpFixtures {
  private BoundedIdentityHttpFixtures() {}

  public record Command(String operation, String target, Map<String, Object> body) {}

  public record Provider(
      List<Command> commands, Map<String, KeycloakTaskCommands.AccountProjection> projections) {
    public void seed(KeycloakTaskCommands.AccountProjection projection) {
      projections.put(projection.id(), projection);
    }
  }

  /**
   * External Matrix account port for non-Matrix integration suites. Real receipt/effect guards,
   * independent journal transactions and exact cleanup IDs remain active. Adapter ID matching is
   * separately exercised at its HTTP seam; this fixture supplies a known synthetic acknowledgment.
   */
  public static void givenOwnedMatrixUser(
      de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService matrix,
      String syntheticId)
      throws de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException {
    doAnswer(
            call -> {
              assertThat((String) call.getArgument(0)).isNotBlank();
              assertThat((String) call.getArgument(1)).isNotBlank();
              var effect =
                  (de.caritas.cob.userservice.api.port.out.OwnedMatrixEffect) call.getArgument(3);
              effect.started(syntheticId);
              effect.created(syntheticId);
              var body =
                  new de.caritas.cob.userservice.api.adapters.matrix.dto
                      .MatrixCreateUserResponseDTO();
              body.setUserId(syntheticId);
              return ResponseEntity.ok(body);
            })
        .when(matrix)
        .createOwnedUser(anyString(), anyString(), anyString(), any());
  }

  public static Jwt taskJwt(TaskIdentity task, TaskIdentityConfiguration identities) {
    var credential = identities.require(task);
    return Jwt.withTokenValue("synthetic-task-" + task.name())
        .header("alg", "RS256")
        .issuer("http://localhost:8080/auth/realms/test")
        .subject(credential.getServiceSubject())
        .claim("azp", credential.getClientId())
        .claim("realm_access", Map.of("roles", task.roles()))
        .audience(new ArrayList<>(task.audiences()))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
  }

  /** External token endpoint and decoder seam; the actual task-binding verifier stays active. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void givenConsultingPolicy(
      RestTemplate http,
      de.caritas.cob.userservice.consultingtypeservice.generated.web.model
              .ExtendedConsultingTypeResponseDTO
          policy) {
    when(http.getUriTemplateHandler())
        .thenReturn(new org.springframework.web.util.DefaultUriBuilderFactory());
    doAnswer(
            invocation -> {
              RequestEntity<?> request = invocation.getArgument(0);
              assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
              assertThat(resolvedUrl(request).getPath())
                  .endsWith("/consultingtypes/" + policy.getId() + "/extended");
              return ResponseEntity.ok(policy);
            })
        .when(http)
        .exchange(
            argThat(
                (RequestEntity<?> request) ->
                    request != null
                        && resolvedUrl(request)
                            .getPath()
                            .endsWith("/consultingtypes/" + policy.getId() + "/extended")),
            any(org.springframework.core.ParameterizedTypeReference.class));
  }

  private static java.net.URI resolvedUrl(RequestEntity<?> request) {
    if (request instanceof RequestEntity.UriTemplateRequestEntity<?> template) {
      var handler = new org.springframework.web.util.DefaultUriBuilderFactory();
      return template.getVarsMap() != null
          ? handler.expand(template.getUriTemplate(), template.getVarsMap())
          : handler.expand(template.getUriTemplate(), template.getVars());
    }
    return request.getUrl();
  }

  /** Dedicated OTP HTTP seam. Binding authentication still runs through the real verifier. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void givenOtp(
      RestTemplate http,
      TaskIdentityConfiguration identities,
      de.caritas.cob.userservice.api.model.OtpInfoDTO info) {
    doAnswer(
            invocation -> {
              String url = invocation.getArgument(0);
              HttpMethod method = invocation.getArgument(1);
              HttpEntity<?> request = invocation.getArgument(2);
              assertThat(request.getHeaders().getFirst("Authorization"))
                  .isEqualTo("Bearer " + taskJwt(TaskIdentity.OTP, identities).getTokenValue());
              assertThat(url)
                  .contains(method == HttpMethod.GET ? "/fetch-otp-setup-info/" : "/setup-otp/");
              if (method == HttpMethod.PUT) {
                var setup = (de.caritas.cob.userservice.api.model.OtpSetupDTO) request.getBody();
                assertThat(setup).isNotNull();
                assertThat(setup.getSecret()).isEqualTo(info.getOtpSecret());
                if (!"123456".equals(setup.getInitialCode()))
                  throw org.springframework.web.client.HttpClientErrorException.create(
                      HttpStatus.UNAUTHORIZED,
                      "Invalid code",
                      HttpHeaders.EMPTY,
                      new byte[0],
                      StandardCharsets.UTF_8);
              }
              return ResponseEntity.ok(info);
            })
        .when(http)
        .exchange(
            anyString(),
            any(HttpMethod.class),
            any(HttpEntity.class),
            eq(de.caritas.cob.userservice.api.model.OtpInfoDTO.class));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void givenTaskGrants(
      RestTemplate authHttp,
      org.springframework.security.oauth2.jwt.JwtDecoder decoder,
      TaskIdentityConfiguration identities) {
    for (var task : TaskIdentity.values()) {
      var jwt = taskJwt(task, identities);
      when(decoder.decode(jwt.getTokenValue())).thenReturn(jwt);
    }
    doAnswer(
            invocation -> {
              String url = invocation.getArgument(0);
              assertThat(url).endsWith("/token");
              HttpEntity<org.springframework.util.MultiValueMap<String, String>> request =
                  invocation.getArgument(1);
              var form = request.getBody();
              assertThat(form).isNotNull();
              assertThat(form.getFirst("grant_type")).isEqualTo("client_credentials");
              var task =
                  Arrays.stream(TaskIdentity.values())
                      .filter(
                          candidate ->
                              identities
                                  .require(candidate)
                                  .getClientId()
                                  .equals(form.getFirst("client_id")))
                      .findFirst()
                      .orElseThrow();
              assertThat(form.getFirst("client_secret"))
                  .isEqualTo(identities.require(task).getClientSecret());
              return ResponseEntity.ok(
                  Map.of(
                      "access_token",
                      taskJwt(task, identities).getTokenValue(),
                      "expires_in",
                      300));
            })
        .when(authHttp)
        .postForEntity(anyString(), any(HttpEntity.class), eq(Map.class));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static List<Command> given(
      RestTemplate http,
      TaskIdentityGrant grants,
      TaskIdentityConfiguration identities,
      Environment environment,
      ObjectMapper mapper,
      Consumer<String> createdIds) {
    return givenProvider(http, grants, identities, environment, mapper, createdIds).commands();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Provider givenProvider(
      RestTemplate http,
      TaskIdentityGrant grants,
      TaskIdentityConfiguration identities,
      Environment environment,
      ObjectMapper mapper,
      Consumer<String> createdIds) {
    return givenProvider(
        http,
        grants,
        identities,
        environment,
        mapper,
        body -> UUID.randomUUID().toString(),
        createdIds);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Provider givenProvider(
      RestTemplate http,
      TaskIdentityGrant grants,
      TaskIdentityConfiguration identities,
      Environment environment,
      ObjectMapper mapper,
      java.util.function.Function<Map<String, Object>, String> createdAccountId,
      Consumer<String> createdIds) {
    return provider(
        http, grants, identities, environment, mapper, createdAccountId, createdIds, command -> {});
  }

  /** Full task transport stays real; only external provider/auth HTTP and decoding are replaced. */
  public static Provider givenProvider(
      RestTemplate http,
      TaskIdentityConfiguration identities,
      Environment environment,
      ObjectMapper mapper,
      java.util.function.Function<Map<String, Object>, String> createdAccountId,
      Consumer<String> createdIds,
      Consumer<Command> observed) {
    return provider(
        http, null, identities, environment, mapper, createdAccountId, createdIds, observed);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Provider provider(
      RestTemplate http,
      TaskIdentityGrant grants,
      TaskIdentityConfiguration identities,
      Environment environment,
      ObjectMapper mapper,
      java.util.function.Function<Map<String, Object>, String> createdAccountId,
      Consumer<String> createdIds,
      Consumer<Command> observed) {
    var captured = Collections.synchronizedList(new ArrayList<Command>());
    var accounts =
        Collections.synchronizedMap(
            new LinkedHashMap<String, KeycloakTaskCommands.AccountProjection>());
    var committed = Collections.synchronizedSet(new HashSet<String>());
    if (grants != null)
      for (var task : TaskIdentity.values()) {
        var claims = taskJwt(task, identities);
        when(grants.token(task)).thenReturn(claims.getTokenValue());
        when(grants.verified(task))
            .thenReturn(new TaskIdentityGrant.VerifiedGrant(claims.getTokenValue(), claims));
      }
    org.mockito.stubbing.Answer<Object> answer =
        invocation -> {
          HttpEntity<String> request = invocation.getArgument(2);
          Class<?> responseType = invocation.getArgument(3);
          var proof =
              SignedJWT.parse(request.getHeaders().getFirst("X-ORISO-Origin-Authorization"));
          var claims = proof.getJWTClaimsSet();
          var operation = claims.getStringClaim("operation");
          boolean provisioning =
              claims
                  .getStringClaim("taskClient")
                  .equals(identities.require(TaskIdentity.ACCOUNT_PROVISIONING).getClientId());
          var task =
              provisioning ? TaskIdentity.ACCOUNT_PROVISIONING : TaskIdentity.ACCOUNT_MAINTENANCE;
          var key =
              Base64.getDecoder()
                  .decode(
                      environment.getRequiredProperty(
                          provisioning
                              ? "oriso.commands.provisioning-origin-key"
                              : "oriso.commands.maintenance-origin-key"));
          assertThat(proof.verify(new MACVerifier(key))).isTrue();
          assertThat(claims.getStringClaim("taskClient"))
              .isEqualTo(identities.require(task).getClientId());
          assertThat(claims.getStringClaim("taskSubject"))
              .isEqualTo(identities.require(task).getServiceSubject());
          assertThat(request.getHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer synthetic-task-" + task.name());
          String body = request.getBody() == null ? "{}" : request.getBody();
          if (operation.equals("account.search")) {
            var query =
                org.springframework.web.util.UriComponentsBuilder.fromUriString(
                        invocation.getArgument(0).toString())
                    .build()
                    .getQueryParams();
            var exact = new TreeMap<String, String>();
            query.forEach(
                (name, values) ->
                    exact.put(
                        name,
                        java.net.URLDecoder.decode(values.getFirst(), StandardCharsets.UTF_8)));
            body = mapper.writeValueAsString(exact);
          }
          var mac = Mac.getInstance("HmacSHA256");
          mac.init(new SecretKeySpec(key, "HmacSHA256"));
          assertThat(claims.getStringClaim("payloadDigest"))
              .isEqualTo(
                  Base64.getUrlEncoder()
                      .withoutPadding()
                      .encodeToString(
                          mac.doFinal(("payload\n" + body).getBytes(StandardCharsets.UTF_8))));
          var payload = mapper.readValue(body, Map.class);
          var capturedCommand = new Command(operation, claims.getStringClaim("target"), payload);
          captured.add(capturedCommand);
          observed.accept(capturedCommand);
          Object response = null;
          String target = claims.getStringClaim("target");
          if (responseType == KeycloakTaskCommands.CreationResult.class) {
            UUID attempt = UUID.fromString(claims.getStringClaim("target"));
            String id = createdAccountId.apply(payload);
            accounts.put(
                id,
                new KeycloakTaskCommands.AccountProjection(
                    id,
                    (String) payload.get("username"),
                    payload.get("email") == null || payload.get("email").toString().isBlank()
                        ? id
                            + environment.getProperty(
                                "identity.email-dummy-suffix", "@beratungcaritas.de")
                        : payload.get("email").toString(),
                    (String) payload.get("firstName"),
                    (String) payload.get("lastName"),
                    payload.get("tenantId") == null
                        ? null
                        : Long.valueOf(payload.get("tenantId").toString()),
                    (String) payload.get("preferredLanguage"),
                    false,
                    false,
                    (List<String>) payload.get("roles"),
                    Boolean.TRUE.equals(payload.get("passwordTemporary"))));
            createdIds.accept(id);
            response =
                new KeycloakTaskCommands.CreationResult(
                    attempt, id, "fixture-owned-proof-" + attempt, "OPEN");
          } else if (responseType == KeycloakTaskCommands.AccountProjection.class) {
            response = accounts.get(target);
            if (response == null)
              throw org.springframework.web.client.HttpClientErrorException.create(
                  HttpStatus.NOT_FOUND,
                  "Not found",
                  HttpHeaders.EMPTY,
                  new byte[0],
                  StandardCharsets.UTF_8);
          } else if (responseType == KeycloakTaskCommands.AccountProjection[].class) {
            response =
                accounts.values().stream()
                    .filter(
                        account ->
                            payload.containsKey("email")
                                ? Objects.equals(account.email(), payload.get("email"))
                                : Objects.equals(account.username(), payload.get("username")))
                    .toArray(KeycloakTaskCommands.AccountProjection[]::new);
          }
          if (Set.of("account.profile", "account.password", "account.roles", "account.deactivate")
              .contains(operation)) {
            var prior = accounts.get(target);
            if (prior == null)
              throw org.springframework.web.client.HttpClientErrorException.create(
                  HttpStatus.NOT_FOUND,
                  "Not found",
                  HttpHeaders.EMPTY,
                  new byte[0],
                  StandardCharsets.UTF_8);
            accounts.put(
                target,
                new KeycloakTaskCommands.AccountProjection(
                    prior.id(),
                    prior.username(),
                    payload.containsKey("email") ? (String) payload.get("email") : prior.email(),
                    payload.containsKey("firstName")
                        ? (String) payload.get("firstName")
                        : prior.firstName(),
                    payload.containsKey("lastName")
                        ? (String) payload.get("lastName")
                        : prior.lastName(),
                    prior.tenantId(),
                    payload.containsKey("preferredLanguage")
                        ? (String) payload.get("preferredLanguage")
                        : prior.preferredLanguage(),
                    operation.equals("account.deactivate") ? false : prior.enabled(),
                    payload.containsKey("email") ? false : prior.emailVerified(),
                    payload.containsKey("roles")
                        ? (List<String>) payload.get("roles")
                        : prior.roles(),
                    payload.containsKey("passwordTemporary")
                        ? Boolean.TRUE.equals(payload.get("passwordTemporary"))
                        : prior.passwordChangeRequired()));
          }
          if (operation.equals("account.commit") && committed.add(target)) {
            String accountId = (String) payload.get("accountId");
            var prior = accounts.get(accountId);
            if (prior == null)
              throw org.springframework.web.client.HttpClientErrorException.create(
                  HttpStatus.NOT_FOUND,
                  "Not found",
                  HttpHeaders.EMPTY,
                  new byte[0],
                  StandardCharsets.UTF_8);
            accounts.put(
                accountId,
                new KeycloakTaskCommands.AccountProjection(
                    prior.id(),
                    prior.username(),
                    prior.email(),
                    prior.firstName(),
                    prior.lastName(),
                    prior.tenantId(),
                    prior.preferredLanguage(),
                    true,
                    prior.emailVerified(),
                    prior.roles(),
                    prior.passwordChangeRequired()));
          }
          if (operation.equals("account.delete")) accounts.remove(target);
          if (operation.equals("account.compensate"))
            accounts.remove((String) payload.get("accountId"));
          return responseType == Void.class
              ? ResponseEntity.noContent().build()
              : ResponseEntity.ok(response);
        };
    doAnswer(answer)
        .when(http)
        .exchange(
            contains("/oriso-commands/v1/"),
            any(HttpMethod.class),
            any(HttpEntity.class),
            any(Class.class));
    doAnswer(answer)
        .when(http)
        .exchange(
            argThat(
                (java.net.URI uri) -> uri != null && uri.getPath().contains("/oriso-commands/v1/")),
            any(HttpMethod.class),
            any(HttpEntity.class),
            any(Class.class));
    return new Provider(captured, accounts);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void givenWizardPolicy(
      RestTemplate http,
      TaskIdentityConfiguration identities,
      Environment environment,
      ObjectMapper mapper) {
    doAnswer(
            invocation -> {
              HttpEntity<?> request = invocation.getArgument(2);
              String context = request.getHeaders().getFirst("X-ORISO-Wizard-Policy-Context");
              assertThat(context).isNotBlank();
              String[] parts = context.split("\\.");
              assertThat(parts).hasSize(2);
              var mac = Mac.getInstance("HmacSHA256");
              mac.init(
                  new SecretKeySpec(
                      Base64.getDecoder()
                          .decode(
                              environment.getRequiredProperty(
                                  "oriso.commands.wizard-policy-context-key")),
                      "HmacSHA256"));
              assertThat(Base64.getUrlDecoder().decode(parts[1]))
                  .isEqualTo(mac.doFinal(parts[0].getBytes(StandardCharsets.US_ASCII)));
              Map<String, Object> claims =
                  mapper.readValue(Base64.getUrlDecoder().decode(parts[0]), Map.class);
              assertThat(claims.get("operation")).isEqualTo("wizard.account-policy.read");
              assertThat(claims.get("aud")).isEqualTo("tenantservice");
              assertThat(claims.get("azp"))
                  .isEqualTo(identities.require(TaskIdentity.CONFIG_WIZARD).getClientId());
              assertThat(claims.get("sub"))
                  .isEqualTo(identities.require(TaskIdentity.CONFIG_WIZARD).getServiceSubject());
              long tenant = ((Number) claims.get("tenantId")).longValue();
              assertThat(invocation.getArgument(0, String.class))
                  .endsWith("/internal/tenants/" + tenant + "/account-provisioning-policy");
              assertThat(request.getHeaders().getFirst("Authorization"))
                  .isEqualTo("Bearer synthetic-task-CONFIG_WIZARD");
              return ResponseEntity.ok(
                  new de.caritas.cob.userservice.api.adapters.keycloak.commands
                      .WizardAccountPolicyClient.Policy(tenant, null));
            })
        .when(http)
        .exchange(
            contains("/account-provisioning-policy"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(
                de.caritas.cob.userservice.api.adapters.keycloak.commands.WizardAccountPolicyClient
                    .Policy.class));
  }
}
