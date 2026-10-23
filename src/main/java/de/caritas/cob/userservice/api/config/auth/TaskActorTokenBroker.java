package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

/** Staged confidential grants; full production activation has a separate preflight gate. */
@Component
public class TaskActorTokenBroker {
  private final TaskActorConfiguration configuration;
  private final KeycloakAuthClient authentication;
  private final JwtDecoder decoder;
  private final KeycloakConfig keycloak;

  public TaskActorTokenBroker(
      TaskActorConfiguration configuration,
      KeycloakAuthClient authentication,
      JwtDecoder decoder,
      KeycloakConfig keycloak) {
    this.configuration = configuration;
    this.authentication = authentication;
    this.decoder = decoder;
    this.keycloak = keycloak;
  }

  /** Only the fixed configured-import HTTP trigger may obtain this live result. */
  public VerifiedConsultantImportTrigger verifyConsultantImportTrigger(String rawToken) {
    try {
      var binding = configuration.require(TaskPurpose.CONSULTANT_IMPORT);
      if (binding.actor() != TaskActor.SETUP
          || !java.util
              .UUID
              .fromString(binding.serviceSubject())
              .toString()
              .equals(binding.serviceSubject())
          || rawToken == null
          || rawToken.isBlank()
          || rawToken.length() > 32768
          || rawToken.chars().anyMatch(c -> Character.isWhitespace(c) || c < 32 || c == 127))
        throw importUnavailable();
      Jwt token = decodeBoundNativeToken(binding, rawToken);
      requireOriginalBounds(token);
      var roles = (Collection<?>) ((Map<?, ?>) token.getClaims().get("realm_access")).get("roles");
      if (roles.size() != TaskActor.SETUP.roles().size()
          || token.getAudience().size() != TaskActor.SETUP.audiences().size())
        throw importUnavailable();
      return new VerifiedConsultantImportTrigger(this, token);
    } catch (RuntimeException invalid) {
      throw importUnavailable();
    }
  }

  public static final class VerifiedConsultantImportTrigger implements AutoCloseable {
    private final TaskActorTokenBroker broker;
    private final Thread owner = Thread.currentThread();
    private final String client, subject;
    private final Instant issuedAt, expiresAt;
    private boolean live = true;

    private VerifiedConsultantImportTrigger(TaskActorTokenBroker broker, Jwt token) {
      this.broker = broker;
      this.client = token.getClaimAsString("azp");
      this.subject = token.getSubject();
      this.issuedAt = token.getIssuedAt();
      this.expiresAt = token.getExpiresAt();
    }

    public void requireLive() {
      try {
        if (!live
            || owner != Thread.currentThread()
            || issuedAt.isAfter(Instant.now())
            || !expiresAt.isAfter(Instant.now())) throw importUnavailable();
        var binding = broker.configuration.require(TaskPurpose.CONSULTANT_IMPORT);
        if (binding.actor() != TaskActor.SETUP
            || !client.equals(binding.clientId())
            || !subject.equals(binding.serviceSubject())) throw importUnavailable();
      } catch (RuntimeException invalid) {
        throw importUnavailable();
      }
    }

    @Override
    public void close() {
      live = false;
    }

    @Override
    public String toString() {
      return "VerifiedConsultantImportTrigger[redacted]";
    }
  }

  private static IllegalStateException importUnavailable() {
    return new IllegalStateException("Consultant import trigger unavailable");
  }

  public TaskToken issue(TaskPurpose purpose) {
    var binding = configuration.require(purpose);
    if (binding.actor() == TaskActor.ACCOUNT_CREATE || binding.actor() == TaskActor.ACCOUNT_ADMIN) {
      throw new IllegalStateException("Native account actor issuance remains gated");
    }
    var issued = requestNativeToken(binding);
    return new TaskToken(binding.actor(), issued.bearer, issued.expiresIn);
  }

  /** Only checked account-creation transports may obtain this private result. */
  public VerifiedProvisioningToken issueCheckedProvisioning(TaskPurpose purpose) {
    if (purpose != TaskPurpose.ACCOUNT_PROVISIONING) throw provisioningUnavailable();
    try {
      var binding = configuration.require(purpose);
      if (binding.actor() != TaskActor.ACCOUNT_CREATE) throw provisioningUnavailable();
      return new VerifiedProvisioningToken(requestBoundNativeToken(binding));
    } catch (RuntimeException failure) {
      throw provisioningUnavailable();
    }
  }

  private static IllegalStateException provisioningUnavailable() {
    return new IllegalStateException("Checked provisioning authentication unavailable");
  }

  public static final class VerifiedProvisioningToken {
    private final NativeToken issued;

    private VerifiedProvisioningToken(NativeToken issued) {
      this.issued = issued;
    }

    public TaskPurpose purpose() {
      return TaskPurpose.ACCOUNT_PROVISIONING;
    }

    public TaskActor actor() {
      return TaskActor.ACCOUNT_CREATE;
    }

    public String accessToken() {
      return issued.bearer;
    }

    public String issuer() {
      return issued.token.getIssuer().toString();
    }

    public String clientId() {
      return issued.token.getClaimAsString("azp");
    }

    public String subject() {
      return issued.token.getSubject();
    }

    public long validatedJwtIssuedAt() {
      return issued.token.getIssuedAt().getEpochSecond();
    }

    public long validatedJwtExpiresAt() {
      return issued.token.getExpiresAt().getEpochSecond();
    }

    @Override
    public String toString() {
      return "VerifiedProvisioningToken[redacted]";
    }
  }

  /** Only the claimed Magic Link transport may obtain the finite session-purpose result. */
  public VerifiedMaintenanceToken issueCheckedSessionExchange() {
    return issueCheckedAdminPurpose(TaskPurpose.SESSION_EXCHANGE);
  }

  /** Only the finite checked maintenance/OTP workflow may obtain this private result. */
  public VerifiedMaintenanceToken issueCheckedMaintenance(TaskPurpose purpose) {
    if (purpose != TaskPurpose.ACCOUNT_MAINTENANCE && purpose != TaskPurpose.OTP)
      throw checkedUnavailable();
    return issueCheckedAdminPurpose(purpose);
  }

  private VerifiedMaintenanceToken issueCheckedAdminPurpose(TaskPurpose purpose) {
    try {
      var binding = configuration.require(purpose);
      if (binding.actor() != TaskActor.ACCOUNT_ADMIN) throw checkedUnavailable();
      return new VerifiedMaintenanceToken(purpose, requestBoundNativeToken(binding));
    } catch (RuntimeException failure) {
      throw checkedUnavailable();
    }
  }

  private NativeToken requestBoundNativeToken(TaskActorCredentials binding) {
    var issued = requestNativeToken(binding);
    requireOriginalBounds(issued.token);
    return issued;
  }

  private void requireOriginalBounds(Jwt token) {
    var issuedAt = token.getIssuedAt();
    var expiresAt = token.getExpiresAt();
    var now = Instant.now();
    if (issuedAt == null
        || expiresAt == null
        || issuedAt.getEpochSecond() <= 0
        || expiresAt.getEpochSecond() <= issuedAt.getEpochSecond()
        || issuedAt.isAfter(now)
        || expiresAt.getEpochSecond() <= now.getEpochSecond()) throw checkedUnavailable();
  }

  private NativeToken requestNativeToken(TaskActorCredentials binding) {
    try {
      var response = authentication.loginService(binding.clientId(), binding.clientSecret());
      Jwt token = decodeBoundNativeToken(binding, response.getAccessToken());
      return new NativeToken(token, response.getAccessToken(), response.getExpiresIn());
    } catch (RuntimeException failure) {
      // Neither tokens, credentials nor provider/decoder diagnostic bodies cross this boundary.
      throw new IllegalStateException("Task actor authentication unavailable");
    }
  }

  private Jwt decodeBoundNativeToken(TaskActorCredentials binding, String rawToken) {
    Jwt token = decoder.decode(rawToken);
    String issuer =
        keycloak.getAuthServerUrl().replaceAll("/+$", "") + "/realms/" + keycloak.getRealm();
    Object clientRoles = token.getClaims().get("resource_access");
    if (token.getIssuer() == null
        || !issuer.equals(token.getIssuer().toString())
        || !binding.clientId().equals(token.getClaimAsString("azp"))
        || !binding.serviceSubject().equals(token.getSubject())
        || token.getExpiresAt() == null
        || !token.getExpiresAt().isAfter(Instant.now())
        || !binding.actor().audiences().equals(new HashSet<>(token.getAudience()))
        || !binding.actor().roles().equals(realmRoles(token))
        || (clientRoles != null
            && (!(clientRoles instanceof Map<?, ?> roles) || !roles.isEmpty()))) {
      throw new IllegalStateException();
    }
    return token;
  }

  private static IllegalStateException checkedUnavailable() {
    return new IllegalStateException("Checked maintenance authentication unavailable");
  }

  private static final class NativeToken {
    private final Jwt token;
    private final String bearer;
    private final int expiresIn;

    private NativeToken(Jwt token, String bearer, int expiresIn) {
      this.token = token;
      this.bearer = bearer;
      this.expiresIn = expiresIn;
    }

    @Override
    public String toString() {
      return "NativeToken[redacted]";
    }
  }

  /** Native-verified original bounds only; never persisted or created from a public tuple. */
  public static final class VerifiedMaintenanceToken {
    private final TaskPurpose purpose;
    private final String bearer;
    private final String issuer;
    private final String clientId;
    private final String subject;
    private final long issuedAt;
    private final long expiresAt;

    private VerifiedMaintenanceToken(TaskPurpose purpose, NativeToken issued) {
      Jwt token = issued.token;
      this.purpose = purpose;
      this.bearer = issued.bearer;
      this.issuer = token.getIssuer().toString();
      this.clientId = token.getClaimAsString("azp");
      this.subject = token.getSubject();
      this.issuedAt = token.getIssuedAt().getEpochSecond();
      this.expiresAt = token.getExpiresAt().getEpochSecond();
    }

    public TaskPurpose purpose() {
      return purpose;
    }

    public TaskActor actor() {
      return TaskActor.ACCOUNT_ADMIN;
    }

    public String accessToken() {
      return bearer;
    }

    public String issuer() {
      return issuer;
    }

    public String clientId() {
      return clientId;
    }

    public String subject() {
      return subject;
    }

    public long validatedJwtIssuedAt() {
      return issuedAt;
    }

    public long validatedJwtExpiresAt() {
      return expiresAt;
    }

    @Override
    public String toString() {
      return "VerifiedMaintenanceToken[redacted]";
    }
  }

  private static Set<String> realmRoles(Jwt token) {
    if (!(token.getClaims().get("realm_access") instanceof Map<?, ?> access)
        || !(access.get("roles") instanceof Collection<?> roles)
        || roles.stream().anyMatch(role -> !(role instanceof String))) {
      return Set.of();
    }
    var result = new HashSet<String>();
    for (Object role : roles) result.add((String) role);
    return result;
  }

  public record TaskToken(TaskActor actor, String accessToken, int expiresIn) {
    @Override
    public String toString() {
      return "TaskToken[actor=" + actor + ", token=redacted]";
    }
  }
}
