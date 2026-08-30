package de.caritas.cob.userservice.api.adapters.keycloak;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.EMAIL_NOT_AVAILABLE;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.PASSWORD_NOT_VALID;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE;
import static java.lang.Boolean.TRUE;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
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
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailAddressUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwner;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityReactivator;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Service for Keycloak REST API calls. */
@Service
@Slf4j
@RequiredArgsConstructor
public class KeycloakService
    implements IdentityAccountRemover,
        IdentityAuthentication,
        IdentityClient,
        IdentityDeactivator,
        IdentityDummyEmailUpdater,
        IdentityEmailAddressUpdater,
        IdentityEmailOwnerLookup,
        IdentityPasswordUpdater,
        IdentityProfileLookup,
        IdentityProfileUpdater,
        IdentityReactivator,
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
    UserResource userResource = keycloakClient.getUsersResource().get(userId);
    var user = userResource.toRepresentation();

    changeLanguageForTheUser(locale, userResource, user);
  }

  protected void changeLanguageForTheUser(
      String locale, UserResource userResource, UserRepresentation user) {
    if (needToUpdateLocale(locale, user)) {
      user.getAttributes().put(LOCALE, Lists.newArrayList(locale));
      userResource.update(user);
    } else {
      log.debug("Skipping language update in keycloak");
    }
  }

  private boolean needToUpdateLocale(String locale, UserRepresentation userRepresentation) {
    return !userRepresentation.getAttributes().containsKey(LOCALE)
        || !userRepresentation.getAttributes().get(LOCALE).contains(locale);
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
  public boolean verifyPasswordIgnoringSecondFactor(String username, String password) {
    return keycloakAuthClient.verifyIgnoringOtp(username, password);
  }

  @Override
  public boolean logout(final String refreshToken) {
    return keycloakAuthClient.logoutUser(refreshToken);
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
    var lowerEmailAddress = emailAddress.toLowerCase(Locale.ROOT);
    var usersResource = keycloakClient.getUsersResource();
    var userRepresentation = usersResource.search(username).get(0);
    if (!lowerEmailAddress.equals(userRepresentation.getEmail())) {
      userRepresentation.setEmail(lowerEmailAddress);
      usersResource.get(userRepresentation.getId()).update(userRepresentation);
    }
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
    return keycloakClient.getUsersResource().search(email, 0, Integer.MAX_VALUE).stream()
        .filter(userRepresentation -> email.equalsIgnoreCase(userRepresentation.getEmail()))
        .findFirst()
        .map(userRepresentation -> new IdentityEmailOwner(userRepresentation.getUsername()));
  }

  @Override
  public IdentityOtpCredential getOtpCredential(String userName) {
    var requestUrl = getOtpUrl(ENDPOINT_OTP_INFO, userName);
    var response =
        withFreshAdminTokenOnUnauthorized(
            "otp-fetch",
            () ->
                keycloakClient.get(keycloakClient.getBearerToken(), requestUrl, OtpInfoDTO.class));

    var body = response.getBody();
    return body == null
        ? IdentityOtpCredential.empty()
        : keycloakMapper.identityOtpCredentialOf(body);
  }

  @Override
  public boolean setUpOtpCredential(String userName, String initialCode, String secret) {
    var otpSetupDTO = keycloakMapper.otpSetupDtoOf(initialCode, secret, null);
    var requestUrl = getOtpUrl(ENDPOINT_OTP_SETUP, userName);

    try {
      withFreshAdminTokenOnUnauthorized(
          "otp-setup",
          () ->
              keycloakClient.putForEntity(
                  keycloakClient.getBearerToken(), requestUrl, otpSetupDTO, OtpInfoDTO.class));
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
    withFreshAdminTokenOnUnauthorized(
        "otp-delete",
        () -> keycloakClient.delete(keycloakClient.getBearerToken(), requestUrl, Void.class));
  }

  @Override
  public IdentityEmailVerificationStart initiateEmailVerification(String username, String email) {
    var otpSetupDTO = keycloakMapper.otpSetupDtoOf(null, null, email);
    var requestUrl = getOtpUrl(ENDPOINT_OTP_VERIFY_EMAIL, username);

    try {
      withFreshAdminTokenOnUnauthorized(
          "email-verification-start",
          () ->
              keycloakClient.putForEntity(
                  keycloakClient.getBearerToken(), requestUrl, otpSetupDTO, Success.class));
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
      var response =
          withFreshAdminTokenOnUnauthorized(
              "email-verification-finish",
              () ->
                  keycloakClient.postForEntity(
                      keycloakClient.getBearerToken(),
                      requestUrl,
                      otpSetupDTO,
                      SuccessWithEmail.class));
      return keycloakMapper.identityEmailVerificationOf(response);
    } catch (HttpClientErrorException exception) {
      return keycloakMapper.identityEmailVerificationOf(exception);
    }
  }

  private String getOtpUrl(String endpoint, String username) {
    var decodedUsername = usernameTranscoder.decodeUsername(username);
    return identityClientConfig.getOtpUrl(
        endpoint, java.util.regex.Matcher.quoteReplacement(decodedUsername));
  }

  private <T> T withFreshAdminTokenOnUnauthorized(String operation, Supplier<T> request) {
    try {
      return request.get();
    } catch (HttpClientErrorException exception) {
      if (!exception.getStatusCode().equals(HttpStatus.UNAUTHORIZED)) {
        throw exception;
      }

      log.warn(
          "Keycloak admin session was unauthorized for {} request, forcing token refresh and"
              + " retrying once",
          operation);
      recordRetry(operation);
      keycloakClient.refreshAdminSession();
      return request.get();
    }
  }

  /**
   * Creates a user in Keycloak and returns its Keycloak user ID.
   *
   * @param user {@link UserDTO}
   * @return provider-neutral created identity
   */
  public CreatedIdentity createUser(final UserDTO user) {
    return createUser(user, null, null);
  }

  /**
   * Creates a user with firstname and lastname in Keycloak and returns its Keycloak user ID.
   *
   * @param user {@link UserDTO}
   * @param firstName first name of user
   * @param lastName last name of user
   * @return provider-neutral created identity
   */
  public CreatedIdentity createUser(
      final UserDTO user, final String firstName, final String lastName) {
    var locale =
        isNull(user.getPreferredLanguage()) ? "de" : user.getPreferredLanguage().toString();
    var kcUser = getUserRepresentation(user, firstName, lastName, locale);
    for (int attempt = 0; attempt < 2; attempt++) {
      try (var response = keycloakClient.getUsersResource().create(kcUser)) {
        if (response.getStatus() == HttpStatus.UNAUTHORIZED.value() && attempt == 0) {
          log.warn(
              "Keycloak admin session was unauthorized while creating a user, forcing token refresh and retrying once");
          recordRetry("admin-session-refresh");
          keycloakClient.refreshAdminSession();
          continue;
        }
        if (response.getStatus() == HttpStatus.CREATED.value()) {
          final String createdUserId = getCreatedUserId(response.getLocation());
          try {
            updateIdentityAttributesAfterCreate(user, createdUserId);
          } catch (Exception exception) {
            log.error(
                "Failed to set mandatory attributes for created keycloak user {}. Rolling back user creation.",
                createdUserId,
                exception);
            rollbackUser(createdUserId);
            throw new InternalServerErrorException(
                String.format(
                    "Could not persist mandatory keycloak user attributes for user %s",
                    createdUserId),
                exception);
          }
          return new CreatedIdentity(createdUserId);
        }
        handleCreateKeycloakUserError(response);
        throw new InternalServerErrorException(genericKeycloakError);
      }
    }
    throw new IllegalStateException("Unreachable Keycloak create-user retry state");
  }

  private void handleCreateKeycloakUserError(Response response) {
    final int status = response.getStatus();
    String rawResponse = "";

    try {
      // Read once from response stream; this is the most stable source across Keycloak versions.
      rawResponse = Optional.ofNullable(response.readEntity(String.class)).orElse("");
    } catch (Exception e) {
      log.warn("Could not read raw Keycloak error response: {}", e.getMessage());
    }

    String combinedError = rawResponse.toLowerCase();

    if (errorMatchesMarker(combinedError, identityClientConfig.getErrorMessageDuplicatedEmail())
        || (status == HttpStatus.CONFLICT.value() && combinedError.contains("email"))) {
      throw new CustomValidationHttpStatusException(EMAIL_NOT_AVAILABLE, HttpStatus.CONFLICT);
    }

    if (errorMatchesMarker(combinedError, identityClientConfig.getErrorMessageDuplicatedUsername())
        || (status == HttpStatus.CONFLICT.value() && combinedError.contains("username"))) {
      throw new CustomValidationHttpStatusException(USERNAME_NOT_AVAILABLE, HttpStatus.CONFLICT);
    }

    log.warn("Keycloak create-user failed. status={}", status);
  }

  /**
   * Null-safe check whether the (lower-cased) Keycloak error response contains the configured
   * duplicate-account marker. A missing/blank marker simply does not match (the status-based
   * fallback still applies) instead of throwing an NPE that would mask a 409 CONFLICT as a 500.
   */
  private boolean errorMatchesMarker(String combinedError, String marker) {
    return marker != null && !marker.isBlank() && combinedError.contains(marker.toLowerCase());
  }

  /**
   * Returns true if the given username does not exist in Keycloak yet or false if it already
   * exists.
   *
   * @param username (decoded or encoded)
   * @return true if does not exist, else false
   */
  public boolean isUsernameAvailable(String username) {
    List<UserRepresentation> keycloakDecodedUserList =
        findByUsername(usernameTranscoder.decodeUsername(username));
    List<UserRepresentation> keycloakEncodedUserList =
        findByUsername(usernameTranscoder.encodeUsername(username));

    return Stream.concat(keycloakDecodedUserList.stream(), keycloakEncodedUserList.stream())
        .noneMatch(user -> doesUsernameMatch(username, user));
  }

  private boolean doesUsernameMatch(String username, UserRepresentation user) {
    return user.getUsername().equalsIgnoreCase(usernameTranscoder.decodeUsername(username))
        || user.getUsername().equalsIgnoreCase(usernameTranscoder.encodeUsername(username));
  }

  @Synchronized
  private boolean isEmailNotAvailable(String email) {
    return keycloakClient.getUsersResource().search(email, 0, Integer.MAX_VALUE).stream()
        .anyMatch(userRepresentation -> userRepresentation.getEmail().equals(email));
  }

  private CredentialRepresentation getCredentialRepresentation(final String password) {
    var credentials = new CredentialRepresentation();
    credentials.setType(CredentialRepresentation.PASSWORD);
    credentials.setValue(password);
    credentials.setTemporary(false);

    return credentials;
  }

  private UserRepresentation getUserRepresentation(
      final UserDTO user, final String firstName, final String lastName) {
    return getUserRepresentation(user, firstName, lastName, null);
  }

  private UserRepresentation getUserRepresentation(
      final UserDTO user, final String firstName, final String lastName, final String locale) {
    return getUserRepresentation(
        user.getUsername(), user.getEmail(), user.getTenantId(), firstName, lastName, locale);
  }

  private UserRepresentation getUserRepresentation(final IdentityProfileUpdate profile) {
    return getUserRepresentation(
        profile.username(),
        profile.email(),
        profile.tenantId(),
        profile.firstName(),
        profile.lastName(),
        null);
  }

  private UserRepresentation getUserRepresentation(
      final String username,
      final String email,
      final Long tenantId,
      final String firstName,
      final String lastName,
      final String locale) {
    var kcUser = new UserRepresentation();
    // Decode the username before setting it in Keycloak (Keycloak expects original username, not
    // encoded)
    kcUser.setUsername(usernameTranscoder.decodeUsername(username));
    kcUser.setEmail(email);
    kcUser.setEmailVerified(true);
    if (nonNull(firstName)) {
      kcUser.setFirstName(firstName);
    }
    if (nonNull(lastName)) {
      kcUser.setLastName(lastName);
    }
    if (nonNull(locale)) {
      kcUser.singleAttribute(LOCALE, locale);
    }
    kcUser.setEnabled(true);

    putUsernameAttributes(username, kcUser);
    updateTenantId(tenantId, kcUser);

    return kcUser;
  }

  private void putUsernameAttributes(String username, UserRepresentation kcUser) {
    Map<String, List<String>> attributes =
        kcUser.getAttributes() == null ? new HashMap<>() : new HashMap<>(kcUser.getAttributes());
    var decodedUsername = usernameTranscoder.decodeUsername(username);
    attributes.put(USERNAME_ATTRIBUTE, Collections.singletonList(decodedUsername));
    attributes.put(LEGACY_USERNAME_ATTRIBUTE, Collections.singletonList(decodedUsername));
    kcUser.setAttributes(attributes);
  }

  private void updateTenantId(Long configuredTenantId, UserRepresentation kcUser) {
    if (TRUE.equals(multiTenancyEnabled)) {
      Map<String, List<String>> attributes =
          kcUser.getAttributes() == null ? new HashMap<>() : new HashMap<>(kcUser.getAttributes());
      var tenantId = resolveTenantId(configuredTenantId);
      if (tenantId != null) {
        attributes.put(TENANT_ID_ATTRIBUTE, Collections.singletonList(tenantId.toString()));
      }
      kcUser.setAttributes(attributes);
    }
  }

  private void updateIdentityAttributesAfterCreate(UserDTO userDTO, String keycloakUserId) {
    var userResource = keycloakClient.getUsersResource().get(keycloakUserId);
    var representation = userResource.toRepresentation();
    Map<String, List<String>> attributes =
        representation.getAttributes() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(representation.getAttributes());

    attributes.put(USER_ID_ATTRIBUTE, Collections.singletonList(keycloakUserId));
    var decodedUsername = usernameTranscoder.decodeUsername(userDTO.getUsername());
    attributes.put(USERNAME_ATTRIBUTE, Collections.singletonList(decodedUsername));
    attributes.put(LEGACY_USERNAME_ATTRIBUTE, Collections.singletonList(decodedUsername));
    var tenantId = resolveTenantId(userDTO);
    if (tenantId != null) {
      attributes.put(TENANT_ID_ATTRIBUTE, Collections.singletonList(tenantId.toString()));
    }

    representation.setAttributes(attributes);
    userResource.update(representation);
  }

  private Long resolveTenantId(UserDTO userDTO) {
    return resolveTenantId(userDTO.getTenantId());
  }

  private Long resolveTenantId(Long configuredTenantId) {
    if (configuredTenantId != null) {
      return configuredTenantId;
    }
    if (TRUE.equals(multiTenancyEnabled) && TenantContext.getCurrentTenant() != null) {
      return TenantContext.getCurrentTenant();
    }
    return null;
  }

  private String getCreatedUserId(final URI location) {
    if (nonNull(location)) {
      String path = location.getPath();
      return path.substring(path.lastIndexOf('/') + 1);
    }

    return null;
  }

  /**
   * Assigns the role "user" to the given user ID.
   *
   * @param userId Keycloak user ID
   */
  public void updateUserRole(final String userId) {
    updateRole(userId, "user");
  }

  @Override
  public void ensureRoles(final String userId, final Collection<String> roleNames) {
    var requestedRoles = new LinkedHashSet<>(roleNames);
    if (requestedRoles.isEmpty()) {
      return;
    }

    try {
      ensureRolesOnce(userId, requestedRoles);
    } catch (NotAuthorizedException e) {
      log.warn(
          "Keycloak admin session was unauthorized while ensuring {} roles for user {}, forcing"
              + " token refresh and retrying once",
          requestedRoles.size(),
          userId);
      recordRetry("admin-session-refresh");
      keycloakClient.refreshAdminSession();
      ensureRolesOnce(userId, requestedRoles);
    }
  }

  private void ensureRolesOnce(final String userId, final Collection<String> requestedRoles) {
    var assignedRoles =
        getUserRoles(userId).stream()
            .map(RoleRepresentation::getName)
            .filter(roleName -> nonNull(roleName))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    var missingRoles = new LinkedHashSet<>(requestedRoles);
    missingRoles.removeAll(assignedRoles);
    if (!missingRoles.isEmpty()) {
      updateRolesOnce(userId, missingRoles);
    }
  }

  /**
   * Assigns the given {@link UserRole} to the given user ID.
   *
   * @param userId Keycloak user ID
   * @param role {@link UserRole}
   */
  public void updateRole(final String userId, final UserRole role) {
    this.updateRole(userId, role.getValue());
  }

  @Override
  public void removeRoleIfPresent(final String userId, final String roleName) {
    // Get realm and user resources
    var realmResource = keycloakClient.getRealmResource();
    UsersResource userRessource = realmResource.users();
    UserResource user = userRessource.get(userId);
    // Remove role
    var optionalRole = findRole(user, roleName);
    if (optionalRole.isPresent()) {
      RoleRepresentation roleRepresentation =
          realmResource.roles().get(optionalRole.get()).toRepresentation();
      if (roleRepresentation != null) {
        user.roles().realmLevel().remove(Collections.singletonList(roleRepresentation));
      }
    }
  }

  Optional<String> findRole(UserResource user, String roleName) {

    List<RoleRepresentation> userRoles = user.roles().realmLevel().listAll();
    if (userRoles != null) {
      return userRoles.stream()
          .filter(role -> role.getName() != null && role.getName().equals(roleName))
          .map(RoleRepresentation::getName)
          .findFirst();
    }
    return Optional.empty();
  }

  /**
   * Assigns the role with the given name to the given user ID.
   *
   * @param userId Keycloak user ID
   * @param roleName Keycloak role name
   */
  public void updateRole(final String userId, final String roleName) {
    try {
      updateRolesOnce(userId, Collections.singletonList(roleName));
    } catch (NotAuthorizedException e) {
      log.warn(
          "Keycloak admin session was unauthorized while assigning role {} to user {}, forcing"
              + " token refresh and retrying once",
          roleName,
          userId);
      recordRetry("admin-session-refresh");
      keycloakClient.refreshAdminSession();
      updateRolesOnce(userId, Collections.singletonList(roleName));
    }
  }

  private void updateRolesOnce(final String userId, final Collection<String> roleNames) {
    // Get realm and user resources
    var realmResource = keycloakClient.getRealmResource();
    UsersResource userRessource = realmResource.users();
    UserResource user = userRessource.get(userId);

    var roleRepresentations =
        roleNames.stream()
            .map(roleName -> realmResource.roles().get(roleName).toRepresentation())
            .peek(
                roleRepresentation -> {
                  if (isNull(roleRepresentation.getAttributes())) {
                    roleRepresentation.setAttributes(new LinkedHashMap<>());
                  }
                })
            .toList();
    user.roles().realmLevel().add(roleRepresentations);

    if (areRolesAssigned(user, roleNames)) {
      log.debug("Added {} roles to {}", roleNames.size(), userId);
      return;
    }

    for (int attempt = 0; attempt < 3; attempt++) {
      recordRetry("role-visibility");
      try {
        Thread.sleep(100L);
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
        throw new KeycloakException(
            "Interrupted while verifying role assignment for user " + userId);
      }
      if (areRolesAssigned(user, roleNames)) {
        log.debug("Added {} roles to {} after retry {}", roleNames.size(), userId, attempt + 1);
        return;
      }
    }

    throw new KeycloakException("Could not update user role");
  }

  private void recordRetry(String operation) {
    if (outboundHttpMetrics != null) {
      outboundHttpMetrics.recordRetry("keycloak", operation);
    }
  }

  private boolean areRolesAssigned(UserResource user, Collection<String> roleNames) {
    Set<String> assignedRoleNames =
        user.roles().realmLevel().listAll().stream()
            .map(RoleRepresentation::getName)
            .filter(roleName -> nonNull(roleName))
            .map(roleName -> roleName.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    return roleNames.stream()
        .map(roleName -> roleName.toLowerCase(Locale.ROOT))
        .allMatch(assignedRoleNames::contains);
  }

  /**
   * Updates the Keycloak password for a user.
   *
   * @param userId Keycloak user ID
   * @param password user password
   */
  @Override
  public void updatePassword(final String userId, final String password) {
    var newCredentials = getCredentialRepresentation(password);
    var userResource = keycloakClient.getUsersResource().get(userId);

    try {
      userResource.resetPassword(newCredentials);
      log.debug("Updated user credentials for {}", userId);
    } catch (Exception exception) {
      if (isPasswordPolicyViolation(exception)) {
        log.warn("Keycloak rejected password for user {} due to password policy", userId);
        throw new CustomValidationHttpStatusException(PASSWORD_NOT_VALID, HttpStatus.BAD_REQUEST);
      }
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
  public String updateDummyEmail(
      final String userId, final IdentityDummyEmailUpdate identityUpdate) {
    var dummyEmail = userHelper.getDummyEmail(userId);
    var user = new UserDTO();
    user.setUsername(identityUpdate.username());
    user.setEmail(dummyEmail);
    user.setTenantId(identityUpdate.tenantId());
    var userResource = keycloakClient.getUsersResource().get(userId);
    userResource.update(getUserRepresentation(user, null, null));
    log.debug("Set email dummy for {} to {}", userId, dummyEmail);
    return dummyEmail;
  }

  /**
   * Updates first name, last name and email address of user with given id in keycloak.
   *
   * @param userId Keycloak user ID
   * @param profile provider-neutral profile values to persist
   */
  @Override
  public void updateProfile(final String userId, final IdentityProfileUpdate profile) {
    var userResource = keycloakClient.getUsersResource().get(userId);
    verifyEmail(userResource.toRepresentation(), profile.email());
    userResource.update(getUserRepresentation(profile));
  }

  private void verifyEmail(UserRepresentation userRepresentation, String email) {
    if (hasEmailAddressChanged(userRepresentation, email)) {
      verifyEmailAvailable(email);
    }
  }

  private void verifyEmailAvailable(String email) {
    if (isEmailNotAvailable(email)) {
      throw new CustomValidationHttpStatusException(EMAIL_NOT_AVAILABLE, HttpStatus.CONFLICT);
    }
  }

  private boolean hasEmailAddressChanged(UserRepresentation userRepresentation, String email) {
    if (userRepresentation != null && userRepresentation.getEmail() != null) {
      return !userRepresentation.getEmail().equals(email);
    } else {
      return !ObjectUtils.isEmpty(email);
    }
  }

  /**
   * Updates the email address of user with given id in keycloak.
   *
   * @param userId Keycloak user ID
   * @param emailAddress the email address to set
   */
  private void updateEmail(String userId, String emailAddress) {
    var userResource = keycloakClient.getUsersResource().get(userId);
    UserRepresentation representation = userResource.toRepresentation();
    if (!hasEmailAddressChanged(representation, emailAddress)) {
      return;
    }
    verifyEmailAvailable(emailAddress);
    representation.setEmail(emailAddress);
    userResource.update(representation);
  }

  /**
   * Delete the user if something went wrong during the registration process.
   *
   * @param userId Keycloak user ID
   */
  @Override
  public void rollbackUser(String userId) {
    try {
      deleteUser(userId);
      log.debug("User {} has been removed due to rollback", userId);
    } catch (Exception e) {
      log.error("Keycloak error: User could not be removed/rolled back: {}", userId);
    }
  }

  /**
   * Deletes the user with the given user id in keycloak.
   *
   * @param userId the userId
   */
  @Override
  public void deleteUser(String userId) {
    try {
      removeUserIfPresent(userId);
    } catch (NotAuthorizedException e) {
      log.warn(
          "Keycloak admin session was unauthorized for deleting user {}, forcing token refresh"
              + " and retrying once",
          userId);
      keycloakClient.refreshAdminSession();
      removeUserIfPresent(userId);
    }
  }

  private void removeUserIfPresent(String userId) {
    try {
      keycloakClient.getUsersResource().get(userId).remove();
    } catch (NotFoundException e) {
      log.warn("User {} not found in Keycloak, skipping deletion.", userId);
    }
  }

  /**
   * Returns the names of all realm roles currently assigned to the given user.
   *
   * @param userId Keycloak user ID
   * @return the realm role names assigned to the user
   */
  @Override
  public List<String> findAllByUserId(String userId) {
    try {
      return getUserRoles(userId).stream()
          .map(RoleRepresentation::getName)
          .collect(Collectors.toList());
    } catch (Exception ex) {
      var error = String.format("Could not get roles for user id %s", userId);
      log.error("Keycloak error: " + error, ex);
      throw new KeycloakException(error);
    }
  }

  private Optional<UserRole> toUserRole(RoleRepresentation roleRepresentation) {
    return UserRole.getRoleByValue(roleRepresentation.getName());
  }

  private List<RoleRepresentation> getUserRoles(String userId) {
    return keycloakClient.getUsersResource().get(userId).roles().realmLevel().listAll();
  }

  /**
   * Returns a list of {@link UserRepresentation} containing all users that match the given search
   * string.
   *
   * @param username Keycloak user name
   * @return {@link List} of found users
   */
  public List<UserRepresentation> findByUsername(String username) {
    try {
      return keycloakClient.getUsersResource().search(username);
    } catch (NotAuthorizedException e) {
      log.warn(
          "Keycloak admin session was unauthorized while searching for username, forcing token"
              + " refresh and retrying once");
      keycloakClient.refreshAdminSession();
      return keycloakClient.getUsersResource().search(username);
    }
  }

  @Override
  public Optional<IdentityProfile> findById(String userId) {
    try {
      UserResource userResource = keycloakClient.getUsersResource().get(userId);
      if (userResource == null) {
        return Optional.empty();
      }
      var user = userResource.toRepresentation();
      if (user == null) {
        return Optional.empty();
      }
      return Optional.of(
          new IdentityProfile(
              user.getId(),
              user.getUsername(),
              user.getFirstName(),
              user.getLastName(),
              user.getEmail()));
    } catch (NotFoundException ex) {
      return Optional.empty();
    }
  }

  /**
   * Deactivates the user account.
   *
   * @param userId the user id to be deactivated
   */
  @Override
  public void deactivateUser(String userId) {
    var userResource = keycloakClient.getUsersResource().get(userId);
    var userRepresentation = userResource.toRepresentation();
    userRepresentation.setEnabled(false);
    userResource.update(userRepresentation);
  }

  @Override
  public void reactivateUser(
      String userId,
      String expectedUsername,
      String expectedEmail,
      Long expectedTenantId,
      String password) {
    var userResource = keycloakClient.getUsersResource().get(userId);
    final UserRepresentation identity;
    try {
      identity = userResource.toRepresentation();
    } catch (NotFoundException exception) {
      throw new de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException(
          "Identity for soft-deleted asker does not exist");
    }
    if (!matchesReactivationIdentity(
        identity, userId, expectedUsername, expectedEmail, expectedTenantId)) {
      throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
          "Keycloak identity does not match the soft-deleted asker exactly");
    }

    updatePassword(userId, password);
    identity.setEnabled(true);
    userResource.update(identity);
  }

  private boolean matchesReactivationIdentity(
      UserRepresentation identity,
      String userId,
      String expectedUsername,
      String expectedEmail,
      Long expectedTenantId) {
    if (identity == null
        || !userId.equals(identity.getId())
        || !expectedUsername.equalsIgnoreCase(identity.getUsername())
        || !expectedEmail.equalsIgnoreCase(identity.getEmail())) {
      return false;
    }
    if (!TRUE.equals(multiTenancyEnabled)) {
      return true;
    }
    var attributes = identity.getAttributes();
    var tenantIds = attributes == null ? null : attributes.get(TENANT_ID_ATTRIBUTE);
    return tenantIds != null
        && tenantIds.size() == 1
        && expectedTenantId.toString().equals(tenantIds.getFirst());
  }
}
