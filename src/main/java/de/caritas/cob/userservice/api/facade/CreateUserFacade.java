package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.service.provisioning.ProvisioningResource.CHAT_IDENTITY;
import static de.caritas.cob.userservice.api.service.provisioning.ProvisioningResource.DATABASE_USER;
import static de.caritas.cob.userservice.api.service.provisioning.ProvisioningResource.IDENTITY_USER;
import static de.caritas.cob.userservice.api.service.provisioning.ProvisioningResource.SESSION;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.helper.AgencyVerifier;
import de.caritas.cob.userservice.api.helper.UserVerifier;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.provisioning.CompensationResult;
import de.caritas.cob.userservice.api.service.provisioning.ProvisioningAttempt;
import de.caritas.cob.userservice.api.service.provisioning.ProvisioningCompensator;
import de.caritas.cob.userservice.api.service.provisioning.ProvisioningWorkflow;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.RegistrationStatisticsEvent;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsDTO;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.SettingDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/** Facade to encapsulate the steps to initialize a user account. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateUserFacade {
  private final @NonNull UserVerifier userVerifier;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull UserService userService;
  private final @NonNull ConsultingTypeManager consultingTypeManager;
  private final @NonNull AgencyVerifier agencyVerifier;
  private final @NonNull CreateNewSessionFacade createNewSessionFacade;
  private final @NonNull StatisticsService statisticsService;
  private final @NonNull TopicService topicService;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull SessionService sessionService;
  private final @NonNull ProvisioningCompensator provisioningCompensator;

  private final @NonNull TenantService tenantService;

  private final @NonNull AgencyService agencyService;

  private final @NonNull ApplicationSettingsService applicationSettingsService;

  @Value("${feature.multitenancy.with.single.domain.enabled:false}")
  private boolean multitenancyWithSingleDomain;

  /**
   * Creates a user in Keycloak and MariaDB. Then creates a session or chat account depending on the
   * provided consulting ID.
   *
   * @param userDTO {@link UserDTO}
   */
  public Long createUserAccountWithInitializedConsultingType(final UserDTO userDTO) {

    initializeTenantContextForRegistration(userDTO);

    // MATRIX MIGRATION: Get plain credentials from ThreadLocal (captured during JSON
    // deserialization)
    de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.PlainCredentials plainCreds =
        de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.get();
    ProvisioningAttempt provisioningAttempt = null;
    AtomicReference<User> provisionedUser = new AtomicReference<>();

    try {
      log.debug(
          "Chat provisioning credentials available={}",
          plainCreds != null && plainCreds.getUsername() != null);

      userVerifier.checkIfAllRequiredAttributesAreCorrectlyFilled(userDTO);
      userVerifier.checkIfUsernameIsAvailable(userDTO);
      agencyVerifier.checkIfConsultingTypeMatchesToAgency(userDTO);

      CreatedIdentity response = identityClient.createUser(userDTO);
      String identityUserId = CreatedIdentity.requireUserId(response);
      provisioningAttempt = provisioningCompensator.begin(ProvisioningWorkflow.REGISTERED_USER);
      ProvisioningAttempt activeAttempt = provisioningAttempt;
      activeAttempt.register(
          IDENTITY_USER, identityUserId, () -> identityClient.rollBackUser(identityUserId));
      activeAttempt.register(
          DATABASE_USER,
          identityUserId,
          () -> deleteDatabaseUser(identityUserId, provisionedUser.get()));

      User user = updateIdentityAndCreateAccount(identityUserId, userDTO, UserRole.USER);
      provisionedUser.set(user);
      User savedUser = userService.saveUser(user);
      if (savedUser != null) {
        user = savedUser;
        provisionedUser.set(savedUser);
      }

      String plainUsername;
      if (plainCreds != null && plainCreds.getUsername() != null) {
        plainUsername = plainCreds.getUsername();
      } else if (user != null && user.getUsername() != null) {
        plainUsername = new UsernameTranscoder().decodeUsername(user.getUsername());
      } else {
        plainUsername = null;
      }
      provisionMatrixUser(user, plainUsername, activeAttempt);

      var consultingTypeSettings = obtainConsultingTypeSettings(userDTO);
      activeAttempt.register(
          SESSION, identityUserId, () -> deleteSessionsForUser(provisionedUser.get()));
      NewRegistrationResponseDto registration =
          createNewSessionFacade.initializeNewSession(userDTO, user, consultingTypeSettings);

      try {
        RegistrationStatisticsEvent registrationEvent =
            new RegistrationStatisticsEvent(
                userDTO,
                user,
                registration.getSessionId(),
                topicService.findTopicInternalIdentifier(userDTO.getMainTopicId()),
                topicService.findTopicsInternalAttributes(userDTO.getTopicIds()),
                getTenantName(),
                getAgencyName(userDTO));
        statisticsService.fireEvent(registrationEvent);
      } catch (Exception e) {
        log.error("Could not create registration statistics event", e);
      }

      activeAttempt.complete();
      return registration.getSessionId();
    } finally {
      compensateProvisioning(provisioningAttempt);
      de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.clear();
    }
  }

  /** Provisions and persists the Matrix identity needed by browser token bootstrap. */
  public void provisionMatrixUser(User user, String plainUsername) {
    provisionMatrixUser(user, plainUsername, null);
  }

  private void provisionMatrixUser(
      User user, String plainUsername, ProvisioningAttempt provisioningAttempt) {
    try {
      if (user == null || isBlank(plainUsername)) {
        throw new IllegalArgumentException("Plain username or user not resolvable");
      }

      String matrixPassword = java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();
      var matrixResponse =
          matrixSynapseService.createUser(plainUsername, matrixPassword, plainUsername);

      log.debug(
          "Chat identity provisioning response statusCode={} hasBody={}",
          matrixResponse.getStatusCode(),
          matrixResponse.getBody() != null);

      if (matrixResponse.getBody() != null && matrixResponse.getBody().getUserId() != null) {
        String matrixUserId = matrixResponse.getBody().getUserId();
        if (provisioningAttempt != null) {
          provisioningAttempt.register(
              CHAT_IDENTITY,
              matrixUserId,
              () -> {
                if (!matrixSynapseService.deactivateUser(matrixUserId)) {
                  throw new IllegalStateException(
                      "Chat identity deactivation was not acknowledged");
                }
              });
        }
        user.setMatrixUserId(matrixUserId);
        userService.saveUser(user);
        log.info("Chat identity provisioned successfully");
      } else {
        throw new IllegalStateException("Matrix user creation response is missing user_id");
      }
    } catch (Exception e) {
      throw new InternalServerErrorException("Could not provision chat identity", e);
    }
  }

  private void deleteSessionsForUser(User user) {
    if (user == null) {
      return;
    }
    List<Session> sessions = sessionService.getSessionsForUser(user);
    if (sessions != null) {
      sessions.forEach(sessionService::deleteSession);
    }
  }

  private void deleteDatabaseUser(String identityUserId, User user) {
    if (user != null) {
      userService.deleteUser(user);
      return;
    }
    userService.getUser(identityUserId).ifPresent(userService::deleteUser);
  }

  private void compensateProvisioning(ProvisioningAttempt provisioningAttempt) {
    if (provisioningAttempt == null) {
      return;
    }
    CompensationResult result = provisioningAttempt.compensateIfIncomplete();
    if (!result.successful()) {
      log.warn(
          "Provisioning compensation incomplete operationId={} failedResources={}",
          result.operationId(),
          result.failedResources());
    }
  }

  private String getTenantName() {
    Long currentTenant = TenantContext.getCurrentTenant();
    if (currentTenant == null) {
      // Multitenancy is disabled, return default tenant name
      return "Default Tenant";
    }
    de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO tenant =
        tenantService.getRestrictedTenantData(currentTenant);
    return tenant != null ? tenant.getName() : "Default Tenant";
  }

  private String getAgencyName(UserDTO userDTO) {
    if (userDTO.getAgencyId() != null) {
      AgencyDTO agencyWithoutCaching = agencyService.getAgencyWithoutCaching(userDTO.getAgencyId());
      return agencyWithoutCaching.getName();
    } else {
      log.warn(
          "AgencyId is null for user during registration. Will not send agency name to statistics");
      return StringUtils.EMPTY;
    }
  }

  /**
   * Updates Keycloak role and password and creates a user account in MariaDB.
   *
   * @param userId Keycloak user ID
   * @param userDTO {@link UserDTO}
   * @return {@link User}
   */
  public User updateIdentityAndCreateAccount(String userId, UserDTO userDTO, UserRole role) {

    try {
      updateKeycloakRoleAndPassword(userId, userDTO, role);
    } catch (RuntimeException ex) {
      if (role == UserRole.ANONYMOUS) {
        log.error(
            "Identity operations failed for anonymous account; aborting account creation", ex);
        throw new InternalServerErrorException("Identity operations failed for anonymous user", ex);
      }
      log.error("Identity operations failed; aborting database user creation", ex);
      throw ex;
    }

    var extendedConsultingTypeResponseDTO =
        consultingTypeManager.getConsultingTypeSettings(userDTO.getConsultingType());
    var language =
        isNull(userDTO.getPreferredLanguage()) ? null : userDTO.getPreferredLanguage().toString();
    User user =
        userService.createUser(
            userId,
            null,
            userDTO.getUsername(),
            returnDummyEmailIfNoneGiven(userDTO, userId),
            isTrue(extendedConsultingTypeResponseDTO.getLanguageFormal()),
            language);

    if (shouldClearPrivacyConfirmations(role, userDTO) && nonNull(user)) {
      user.setTermsAndConditionsConfirmation(null);
      user.setDataPrivacyConfirmation(null);
      user = userService.saveUser(user);
    }

    return user;
  }

  private boolean shouldClearPrivacyConfirmations(UserRole role, UserDTO userDTO) {
    if (role == UserRole.ANONYMOUS) {
      return true;
    }

    // Anonymous chat currently registers through /users/askers/new with role USER.
    // Detect that path by its synthetic postcode / username and clear confirmations
    // so the first in-chat privacy gate is shown and persisted only after explicit acceptance.
    return StringUtils.equals("00000", userDTO.getPostcode())
        || StringUtils.startsWith(userDTO.getUsername(), "Anonymous-");
  }

  private ExtendedConsultingTypeResponseDTO obtainConsultingTypeSettings(UserDTO userDTO) {
    return consultingTypeManager.getConsultingTypeSettings(userDTO.getConsultingType());
  }

  private void updateKeycloakRoleAndPassword(String userId, UserDTO userDTO, UserRole role) {
    checkIfUserIdNotNull(userId);
    identityClient.updateRole(userId, role);
    identityClient.updatePassword(userId, userDTO.getPassword());
  }

  private void checkIfUserIdNotNull(String userId) {
    if (isNull(userId)) {
      throw new InternalServerErrorException("Could not create identity account");
    }
  }

  private String returnDummyEmailIfNoneGiven(UserDTO userDTO, String userId) {
    if (isBlank(userDTO.getEmail())) {
      return identityClient.updateDummyEmail(userId, userDTO);
    }

    return userDTO.getEmail();
  }

  private void initializeTenantContextForRegistration(UserDTO userDTO) {
    if (TenantContext.contextIsSet()) {
      return;
    }

    if (userDTO.getAgencyId() != null) {
      try {
        AgencyDTO agency = agencyService.getAgencyWithoutCaching(userDTO.getAgencyId());
        if (agency != null && agency.getTenantId() != null) {
          TenantContext.setCurrentTenant(agency.getTenantId());
          return;
        }
      } catch (RestClientException exception) {
        log.warn(
            "Could not resolve tenant from registration agencyId {}. Falling back to main tenant.",
            userDTO.getAgencyId(),
            exception);
      }
    }

    resolveMainTenantIdFromApplicationSettings()
        .ifPresent(
            tenantId -> {
              log.debug("Using main tenant {} for registration", tenantId);
              TenantContext.setCurrentTenant(tenantId);
            });
  }

  private Optional<Long> resolveMainTenantIdFromApplicationSettings() {
    if (!multitenancyWithSingleDomain) {
      return Optional.empty();
    }

    ApplicationSettingsDTO applicationSettings =
        applicationSettingsService.getApplicationSettings();
    SettingDTO mainTenantSubdomainForSingleDomainMultitenancy =
        applicationSettings.getMainTenantSubdomainForSingleDomainMultitenancy();
    if (mainTenantSubdomainForSingleDomainMultitenancy == null
        || mainTenantSubdomainForSingleDomainMultitenancy.getValue() == null
        || mainTenantSubdomainForSingleDomainMultitenancy.getValue().isBlank()) {
      log.warn("Main tenant subdomain not available in application settings.");
      return Optional.empty();
    }
    return Optional.of(
        tenantService
            .getRestrictedTenantData(mainTenantSubdomainForSingleDomainMultitenancy.getValue())
            .getId());
  }
}
