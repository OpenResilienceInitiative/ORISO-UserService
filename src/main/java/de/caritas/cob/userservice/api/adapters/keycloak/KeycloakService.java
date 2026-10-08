package de.caritas.cob.userservice.api.adapters.keycloak;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.EMAIL_NOT_AVAILABLE;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.PASSWORD_NOT_VALID;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.keycloak.KeycloakException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerification;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerificationStart;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.model.Success;
import de.caritas.cob.userservice.api.model.SuccessWithEmail;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityAccountStatusLookup;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailAddressUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwner;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordChangeRequirement;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.ws.rs.BadRequestException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Service for Keycloak REST API calls. */
@Service
@Slf4j
@RequiredArgsConstructor
public class KeycloakService
    implements IdentityAccountRemover,
        IdentityAccountStatusLookup,
        IdentityAuthentication,
        IdentityClient,
        IdentityDeactivator,
        IdentityDummyEmailUpdater,
        IdentityEmailAddressUpdater,
        IdentityEmailOwnerLookup,
        IdentityLocaleLookup,
        IdentityPasswordUpdater,
        IdentityPasswordChangeRequirement,
        IdentityProfileLookup,
        IdentityProfileUpdater,
        IdentityRoleLookup,
        IdentityRoleUpdater,
        IdentitySecondFactor,
        IdentityUsernameAvailability {

  private static final String ENDPOINT_OTP_INFO = "/fetch-otp-setup-info/{username}";
  private static final String ENDPOINT_OTP_SETUP = "/setup-otp/{username}";
  private static final String ENDPOINT_OTP_TEARDOWN = "/delete-otp/{username}";
  private static final String ENDPOINT_OTP_VERIFY_EMAIL = "/send-verification-mail/{username}";
  private static final String ENDPOINT_OTP_FINISH_EMAIL = "/setup-otp-mail/{username}";
  private static final String LOCALE = "locale";
  private static final String TENANT_ID_ATTRIBUTE = "tenantId";
  private static final String USER_ID_ATTRIBUTE = "userId";
  private static final String USERNAME_ATTRIBUTE = "username";
  private static final String LEGACY_USERNAME_ATTRIBUTE = "userName";

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull UserAccountInputValidator userAccountInputValidator;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull KeycloakClient keycloakClient;
  private final @NonNull KeycloakMapper keycloakMapper;
  private final @NonNull UserHelper userHelper;
  private final @NonNull KeycloakAuthClient keycloakAuthClient;
  private final @NonNull de.caritas.cob.userservice.api.config.auth.TaskIdentityTokenVerifier
      taskIdentityTokenVerifier;

  @Autowired @org.springframework.context.annotation.Lazy
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
      taskCommands;

  @Autowired @org.springframework.context.annotation.Lazy
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityMaintenanceOrigins
      commandOrigins;

  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  private OutboundHttpMetrics outboundHttpMetrics;

  @Value("${api.error.keycloakError}")
  private String genericKeycloakError;

  @Value("${multitenancy.enabled}")
  private Boolean multiTenancyEnabled;

  @Autowired(required = false)
  void setOutboundHttpMetrics(OutboundHttpMetrics outboundHttpMetrics) {
    this.outboundHttpMetrics = outboundHttpMetrics;
  }

  /**
   * Changes the (Keycloak) password of a user and returns true on success.
   *
   * @param userId Keycloak user ID
   * @param password Keycloak password
   * @return true if password change was successful
   */
  public boolean changePassword(final String userId, final String password) {
    try {
      updatePassword(userId, password);
    } catch (Exception ex) {
      log.info("Could not change password for user with id {}", userId);
      return false;
    }

    return true;
  }

  public void changeLanguage(final String userId, final String locale) {
    var authorization = commandOrigins.current(userId, "account.profile", List.of());
    taskCommands.profile(userId, Map.of("preferredLanguage", locale), authorization);
  }

  /** Retired test/source compatibility seam: arbitrary native representations are forbidden. */
  @Deprecated
  protected void changeLanguageForTheUser(
      String locale, UserResource ignored, UserRepresentation user) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Native account maintenance has been retired");
  }

  @Override
  public IdentityLogin login(final String userName, final String password) {
    KeycloakLoginResponseDTO response = keycloakAuthClient.loginUser(userName, password);
    return new IdentityLogin(
        response.getAccessToken(),
        response.getExpiresIn(),
        response.getRefreshExpiresIn(),
        response.getRefreshToken());
  }

  @Override
  public IdentityLogin loginService(String clientId, String clientSecret) {
    var response = keycloakAuthClient.loginService(clientId, clientSecret);
    // Service accounts have no human refresh session; never propagate a provider refresh token.
    return new IdentityLogin(response.getAccessToken(), response.getExpiresIn(), 0, null);
  }

  @Override
  public IdentityLogin loginTask(
      de.caritas.cob.userservice.api.config.auth.TaskIdentityCredentials identity) {
    var grant = loginService(identity.getClientId(), identity.getClientSecret());
    taskIdentityTokenVerifier.verify(identity, grant.accessToken());
    return grant;
  }

  @Override
  public boolean verifyPasswordIgnoringSecondFactor(String username, String password) {
    return keycloakAuthClient.verifyIgnoringOtp(username, password);
  }

  @Override
  public boolean logout(final String refreshToken) {
    return keycloakAuthClient.logoutUser(refreshToken);
  }

  @Override
  public boolean logout(final String refreshToken, final String accessToken) {
    return keycloakAuthClient.logoutUser(refreshToken, accessToken);
  }

  /**
   * Updates the email address of user with given id in keycloak.
   *
   * @param emailAddress the email address to set
   */
  @Override
  public void updateCurrentUserEmail(String emailAddress) {
    this.userAccountInputValidator.validateEmailAddress(emailAddress);
    String userId = this.authenticatedUser.getUserId();
    updateEmail(userId, emailAddress.toLowerCase(Locale.ROOT));
  }

  @Override
  public void updateEmailByUsername(String username, String emailAddress) {
    var existing = findByUsername(username);
    if (existing.isEmpty())
      throw new KeycloakException("Account not found for email synchronization");
    updateEmail(existing.getFirst().getId(), emailAddress.toLowerCase(Locale.ROOT));
  }

  @Override
  public void deleteCurrentUserEmail() {
    var userId = authenticatedUser.getUserId();
    updateEmail(userId, userHelper.getDummyEmail(userId));
  }

  /**
   * Exact-owner lookup on top of Keycloak's fuzzy user search, which also matches on username,
   * first and last name — hence the re-filter on the e-mail field itself.
   *
   * <p>The comparison ignores case: callers normalize the probe to lower case, but a stored record
   * need not be lower-cased (imported or externally federated users routinely are not). A
   * case-sensitive comparison would discard exactly the hit that Keycloak's own case-insensitive
   * search just returned and report the address as free — the same duplicate-address defect the
   * callers use this method to prevent.
   */
  @Override
  public Optional<IdentityEmailOwner> findByEmail(String email) {
    try {
      return taskCommands
          .provisioningSearch(
              "email",
              email,
              de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
                  .registrationAvailability(email, TenantContext.getCurrentTenant()))
          .stream()
          .filter(user -> email.equalsIgnoreCase(user.email()))
          .findFirst()
          .map(user -> new IdentityEmailOwner(user.username()));
    } catch (HttpClientErrorException.Forbidden foreignOwner) {
      // A foreign exact match is occupied, but its identity must not be disclosed.
      return Optional.of(new IdentityEmailOwner(null));
    }
  }

  @Override
  public IdentityOtpCredential getOtpCredential(String userName) {
    var requestUrl = getOtpUrl(ENDPOINT_OTP_INFO, userName);
    var response =
        withFreshOtpTokenOnUnauthorized(
            "otp-fetch", () -> keycloakClient.get(otpBearerToken(), requestUrl, OtpInfoDTO.class));

    var body = response.getBody();
    if (body == null) {
      throw new KeycloakException("OTP credential lookup returned an empty response");
    }
    return keycloakMapper.identityOtpCredentialOf(body);
  }

  @Override
  public boolean setUpOtpCredential(String userName, String initialCode, String secret) {
    var otpSetupDTO = keycloakMapper.otpSetupDtoOf(initialCode, secret, null);
    var requestUrl = getOtpUrl(ENDPOINT_OTP_SETUP, userName);

    try {
      withFreshOtpTokenOnUnauthorized(
          "otp-setup",
          () ->
              keycloakClient.putForEntity(
                  otpBearerToken(), requestUrl, otpSetupDTO, OtpInfoDTO.class));
      return true;
    } catch (HttpClientErrorException exception) {
      if (exception.getStatusCode().equals(HttpStatus.UNAUTHORIZED)) {
        return false;
      } else {
        throw exception;
      }
    }
  }

  @Override
  public void deleteOtpCredential(String userName) {
    var requestUrl = getOtpUrl(ENDPOINT_OTP_TEARDOWN, userName);
    withFreshOtpTokenOnUnauthorized(
        "otp-delete", () -> keycloakClient.delete(otpBearerToken(), requestUrl, Void.class));
  }

  @Override
  public IdentityEmailVerificationStart initiateEmailVerification(String username, String email) {
    var otpSetupDTO = keycloakMapper.otpSetupDtoOf(null, null, email);
    var requestUrl = getOtpUrl(ENDPOINT_OTP_VERIFY_EMAIL, username);

    try {
      withFreshOtpTokenOnUnauthorized(
          "email-verification-start",
          () ->
              keycloakClient.putForEntity(
                  otpBearerToken(), requestUrl, otpSetupDTO, Success.class));
      return IdentityEmailVerificationStart.success();
    } catch (RestClientException exception) {
      return IdentityEmailVerificationStart.failure(
          "Identity provider answered: " + exception.getMessage());
    }
  }

  @Override
  public IdentityEmailVerification finishEmailVerification(String username, String initialCode) {
    var otpSetupDTO = keycloakMapper.otpSetupDtoOf(initialCode, null, null);
    var requestUrl = getOtpUrl(ENDPOINT_OTP_FINISH_EMAIL, username);

    try {
      // The OTP SPI also returns 401 for an invalid or expired code. Only a bearer challenge
      // identifies a failed service session; repeating a rejected code consumes another attempt.
      var response =
          withFreshOtpTokenOnUnauthorized(
              "email-verification-finish",
              () ->
                  keycloakClient.postForEntity(
                      otpBearerToken(), requestUrl, otpSetupDTO, SuccessWithEmail.class),
              KeycloakService::isBearerChallenge);
      return keycloakMapper.identityEmailVerificationOf(response);
    } catch (HttpClientErrorException exception) {
      if (exception.getStatusCode().equals(HttpStatus.UNAUTHORIZED)
          && isBearerChallenge(exception)) {
        throw new ServiceUnavailableException(
            "OTP verification service authentication unavailable");
      }
      return keycloakMapper.identityEmailVerificationOf(exception);
    }
  }

  private String otpBearerToken() {
    var identity =
        identityClientConfig.getTaskIdentity(
            de.caritas.cob.userservice.api.config.auth.TaskIdentity.OTP);
    return loginTask(identity).accessToken();
  }

  private String getOtpUrl(String endpoint, String username) {
    var decodedUsername = usernameTranscoder.decodeUsername(username);
    return identityClientConfig.getOtpUrl(
        endpoint, java.util.regex.Matcher.quoteReplacement(decodedUsername));
  }

  private <T> T withFreshOtpTokenOnUnauthorized(String operation, Supplier<T> request) {
    return withFreshOtpTokenOnUnauthorized(operation, request, exception -> true);
  }

  private <T> T withFreshOtpTokenOnUnauthorized(
      String operation, Supplier<T> request, Predicate<HttpClientErrorException> retryable) {
    try {
      return request.get();
    } catch (HttpClientErrorException exception) {
      if (!exception.getStatusCode().equals(HttpStatus.UNAUTHORIZED)
          || !retryable.test(exception)) {
        throw exception;
      }

      log.warn(
          "Keycloak OTP service grant was unauthorized for {} request, forcing token refresh and"
              + " retrying once",
          operation);
      recordRetry(operation);
      // Each retry obtains a fresh grant for the dedicated OTP service account.
      return request.get();
    }
  }

  private static boolean isBearerChallenge(HttpClientErrorException exception) {
    return WwwAuthenticateChallenges.containsScheme(exception.getResponseHeaders(), "Bearer");
  }

  /**
   * Creates a user in Keycloak and returns its Keycloak user ID.
   *
   * @param user {@link UserDTO}
   * @return provider-neutral created identity
   */
  public CreatedIdentity createUser(final UserDTO user) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Account creation requires a verified typed provisioning origin");
  }

  @Override
  public CreatedIdentity createUser(final UserDTO user, String firstName, String lastName) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Account creation requires a verified typed provisioning origin");
  }

  @Override
  public boolean isUsernameAvailable(String username) {
    String decoded = usernameTranscoder.decodeUsername(username);
    String encoded = usernameTranscoder.encodeUsername(username);
    return availability(decoded) && (decoded.equals(encoded) || availability(encoded));
  }

  private boolean availability(String exactUsername) {
    try {
      return taskCommands
          .provisioningSearch(
              "username",
              exactUsername,
              de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
                  .registrationAvailability(exactUsername, TenantContext.getCurrentTenant()))
          .isEmpty();
    } catch (HttpClientErrorException.Forbidden foreignOwner) {
      return false;
    }
  }

  @Override
  public void updateUserRole(String userId) {
    updateRole(userId, "user");
  }

  @Override
  public void ensureRoles(String userId, Collection<String> names) {
    var wanted = new LinkedHashSet<>(findAllByUserId(userId));
    if (!wanted.addAll(names)) return;
    taskCommands.roles(userId, wanted, commandOrigins.current(userId, "account.roles", wanted));
  }

  @Override
  public void ensureRoles(
      String userId,
      Collection<String> names,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          readOrigin,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          roleOrigin) {
    var wanted = new LinkedHashSet<>(findAllByUserId(userId, readOrigin));
    if (!wanted.addAll(names)) return;
    taskCommands.roles(userId, wanted, roleOrigin);
  }

  @Override
  public void updateRole(String userId, UserRole role) {
    updateRole(userId, role.getValue());
  }

  @Override
  public void updateRole(String userId, String role) {
    ensureRoles(userId, List.of(role));
  }

  @Override
  public void removeRoleIfPresent(String userId, String role) {
    var wanted = new LinkedHashSet<>(findAllByUserId(userId));
    if (wanted.remove(role))
      taskCommands.roles(userId, wanted, commandOrigins.current(userId, "account.roles", wanted));
  }

  @Deprecated
  Optional<String> findRole(UserResource ignored, String roleName) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Native account role resources have been retired");
  }

  private void recordRetry(String operation) {
    if (outboundHttpMetrics != null) outboundHttpMetrics.recordRetry("keycloak", operation);
  }

  @Override
  public void updatePassword(String userId, String password) {
    updatePassword(userId, password, commandOrigins.current(userId, "account.password", List.of()));
  }

  @Override
  public void updatePassword(
      String userId,
      String password,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          authorization) {
    resetPassword(userId, password, false, authorization);
  }

  @Override
  public void updateTemporaryPassword(String userId, String password) {
    resetPassword(
        userId, password, true, commandOrigins.current(userId, "account.password", List.of()));
  }

  private void resetPassword(
      String id,
      String password,
      boolean temporary,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          authorization) {
    try {
      taskCommands.password(id, password, temporary, authorization);
    } catch (Exception exception) {
      if (isPasswordPolicyViolation(exception))
        throw new CustomValidationHttpStatusException(PASSWORD_NOT_VALID, HttpStatus.BAD_REQUEST);
      throw exception;
    }
  }

  private boolean isPasswordPolicyViolation(Exception exception) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof BadRequestException) {
        return true;
      }
      if (current instanceof RestClientResponseException) {
        RestClientResponseException restClientResponseException =
            (RestClientResponseException) current;
        if (restClientResponseException.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()
            && isPasswordPolicyMessage(restClientResponseException.getResponseBodyAsString())) {
          return true;
        }
      }
      if (isPasswordPolicyMessage(current.getMessage())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private boolean isPasswordPolicyMessage(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String lowerMessage = message.toLowerCase();
    return lowerMessage.contains("password")
        && (lowerMessage.contains("policy")
            || lowerMessage.contains("invalid")
            || lowerMessage.contains("not met")
            || lowerMessage.contains("does not match"));
  }

  /**
   * Replaces a blank email with the configured dummy address.
   *
   * @param userId identity-provider user ID
   * @param identityUpdate provider-neutral identity metadata
   * @return the dummy email address
   */
  @Override
  public String updateDummyEmail(String id, IdentityDummyEmailUpdate update) {
    String email = userHelper.getDummyEmail(id);
    taskCommands.profile(
        id, Map.of("email", email), commandOrigins.current(id, "account.profile", List.of()));
    return email;
  }

  @Override
  public void updateProfile(String id, IdentityProfileUpdate profile) {
    var patch = new LinkedHashMap<String, Object>();
    patch.put("username", usernameTranscoder.decodeUsername(profile.username()));
    patch.put("email", profile.email());
    var caller =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    boolean ownAccount =
        caller
                instanceof
                org.springframework.security.oauth2.server.resource.authentication
                            .JwtAuthenticationToken
                        verified
            && id.equals(verified.getToken().getSubject());
    if (multiTenancyEnabled && !ownAccount && profile.tenantId() != null)
      patch.put("tenantId", profile.tenantId().toString());
    if (profile.firstName() != null) patch.put("firstName", profile.firstName());
    if (profile.lastName() != null) patch.put("lastName", profile.lastName());
    try {
      taskCommands.profile(id, patch, commandOrigins.current(id, "account.profile", List.of()));
    } catch (HttpClientErrorException.Conflict duplicate) {
      throw new CustomValidationHttpStatusException(EMAIL_NOT_AVAILABLE, HttpStatus.CONFLICT);
    }
  }

  private void updateEmail(String id, String email) {
    try {
      taskCommands.profile(
          id, Map.of("email", email), commandOrigins.current(id, "account.profile", List.of()));
    } catch (HttpClientErrorException.Conflict duplicate) {
      throw new CustomValidationHttpStatusException(EMAIL_NOT_AVAILABLE, HttpStatus.CONFLICT);
    }
  }

  @Override
  public void rollbackUser(String id) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Rollback requires its owned creation attempt and receipt");
  }

  @Override
  public void deleteUser(String id) {
    deleteUser(id, commandOrigins.current(id, "account.delete", List.of()));
  }

  @Override
  public void deleteUser(
      String id,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    taskCommands.delete(id, origin);
  }

  @Override
  public List<String> findAllByUserId(String id) {
    return findAllByUserId(id, commandOrigins.current(id, "account.read", List.of()));
  }

  @Override
  public List<String> findAllByUserId(
      String id,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    return read(id, origin).map(user -> user.roles()).orElse(List.of());
  }

  public List<UserRepresentation> findByUsername(String username) {
    try {
      return taskCommands
          .provisioningSearch(
              "username",
              username,
              de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
                  .registrationAvailability(username, TenantContext.getCurrentTenant()))
          .stream()
          .map(
              user -> {
                var representation = new UserRepresentation();
                representation.setId(user.id());
                representation.setUsername(user.username());
                representation.setEmail(user.email());
                return representation;
              })
          .toList();
    } catch (HttpClientErrorException.Forbidden foreignOwner) {
      return List.of();
    }
  }

  private Optional<
          de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
              .AccountProjection>
      read(
          String id,
          de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
              origin) {
    try {
      return Optional.ofNullable(taskCommands.read(id, origin));
    } catch (HttpClientErrorException.NotFound absent) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<Boolean> findEnabledById(String id) {
    if (commandOrigins.protectedPlatformStatusUnavailable(id)) return Optional.empty();
    return read(id, commandOrigins.current(id, "account.read", List.of()))
        .map(user -> user.enabled());
  }

  @Override
  public Optional<IdentityProfile> findById(String id) {
    return findById(id, commandOrigins.current(id, "account.read", List.of()));
  }

  @Override
  public Optional<IdentityProfile> findById(
      String id,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    return read(id, origin)
        .map(
            user ->
                new IdentityProfile(
                    user.id(), user.username(), user.firstName(), user.lastName(), user.email()));
  }

  @Override
  public boolean requiresPasswordChange(String id) {
    return requiresPasswordChange(id, commandOrigins.current(id, "account.read", List.of()));
  }

  @Override
  public boolean requiresPasswordChange(
      String id,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    return read(id, origin).map(user -> user.passwordChangeRequired()).orElse(false);
  }

  @Override
  public Optional<String> findLocaleById(String id) {
    return findLocaleById(id, commandOrigins.current(id, "account.read", List.of()));
  }

  @Override
  public Optional<String> findLocaleById(
      String id,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    return read(id, origin)
        .map(user -> user.preferredLanguage())
        .filter(locale -> !locale.isBlank());
  }

  @Override
  public void deactivateUser(String id) {
    deactivateUser(id, commandOrigins.current(id, "account.deactivate", List.of()));
  }

  @Override
  public void deactivateUser(
      String id,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    taskCommands.deactivate(id, origin);
  }
}
