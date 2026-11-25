package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.service.provisioning.ProvisioningResource.CHAT_IDENTITY;
import static de.caritas.cob.userservice.api.service.provisioning.ProvisioningResource.DATABASE_USER;
import static de.caritas.cob.userservice.api.service.provisioning.ProvisioningResource.GROUP_CHAT_MEMBERSHIP;
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
import de.caritas.cob.userservice.api.conversation.service.AnonymousConversationCreatorService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.helper.AgencyVerifier;
import de.caritas.cob.userservice.api.helper.UserVerifier;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.service.AskerEmailCollectionPolicy;
import de.caritas.cob.userservice.api.service.AskerIdentityPolicy;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkKind;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.email.WelcomeEmailService;
import de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher;
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
  private final ChatRecoveryEnrollmentPolicyService chatRecoveryEnrollmentPolicyService;
  private final de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService
      inactivityEnrollment;
  private final @NonNull de.caritas.cob.userservice.api.service.dpa.NewCounsellingDpaPolicy
      dpaPolicy;
  private final @NonNull UserVerifier userVerifier;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull IdentityAccountRemover identityAccountRemover;
  private final @NonNull IdentityPasswordUpdater identityPasswordUpdater;
  private final @NonNull IdentityDummyEmailUpdater identityDummyEmailUpdater;
  private final @NonNull UserService userService;
  private final @NonNull ConsultingTypeManager consultingTypeManager;
  private final @NonNull AgencyVerifier agencyVerifier;
  private final @NonNull CreateNewSessionFacade createNewSessionFacade;
  private final @NonNull StatisticsService statisticsService;
  private final @NonNull TopicService topicService;
  private final @NonNull WelcomeEmailService welcomeEmailService;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull SessionService sessionService;
  private final @NonNull ProvisioningCompensator provisioningCompensator;

  private final @NonNull TenantService tenantService;

  private final @NonNull AgencyService agencyService;

  private final @NonNull ApplicationSettingsService applicationSettingsService;

  private final @NonNull GroupInviteRegistration groupInviteRegistration;
  private final @NonNull AskerEmailCollectionPolicy emailCollectionPolicy;
  private final @NonNull SessionDataService sessionDataService;

  private final @NonNull de.caritas.cob.userservice.api.service.agencyinvitelink
          .TemporaryAccountLinkResolver
      temporaryAccountLinkResolver;

  private final @NonNull de.caritas.cob.userservice.api.service.temporaryaccount
          .TemporaryAccountSettingsService
      temporaryAccountSettings;

  private final @NonNull LifecycleEventPublisher lifecycleEvents;
  private final @NonNull AnonymousConversationCreatorService anonymousConversationCreatorService;
  private final @NonNull IdentityAuthentication identityAuthentication;

  private static final String GUEST_POSTCODE = "00000";

  @Value("${feature.multitenancy.with.single.domain.enabled:false}")
  private boolean multitenancyWithSingleDomain;

  /**
   * What the one registration entry created. {@code login} is set for a live-chat guest only: the
   * guest never typed a password, so the entry signs the guest in before it reports success.
   */
  public record CreatedAccount(String userId, Long sessionId, IdentityLogin login) {}

  /**
   * Registers an advice seeker for e-mail counselling (Mailberatung), a group or a direct link.
   *
   * @param userDTO {@link UserDTO}
   * @return the id of the enquiry session, or {@code null} when the registration opens none
   */
  public Long createUserAccountWithInitializedConsultingType(final UserDTO userDTO) {
    return createUserAccountWithInitializedConsultingType(userDTO, RegistrationType.REGISTERED)
        .sessionId();
  }

  /**
   * The one entry that creates an advice seeker (ADR-038, #1745): identity, role, database row,
   * chat identity and the first conversation, with one compensation for all of them. A live-chat
   * guest is {@link RegistrationType#ANONYMOUS}; nothing else separates a guest from a registered
   * advice seeker here.
   *
   * @param userDTO {@link UserDTO}
   * @param registrationType how the advice seeker enters
   */
  public CreatedAccount createUserAccountWithInitializedConsultingType(
      final UserDTO userDTO, final RegistrationType registrationType) {
    boolean guest = registrationType == RegistrationType.ANONYMOUS;

    initializeTenantContextForRegistration(userDTO);
    tenantService.requireOperationalTenant(TenantContext.getCurrentTenant());
    if (userDTO.getEmail() != null && !userDTO.getEmail().isBlank()) {
      AskerIdentityPolicy.requireEmailCollectionAllowed(
          tenantService, TenantContext.getCurrentTenant());
    }

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

      if (guest) {
        // A guest enters a topic queue: no agency, no demographics, no invitation to resolve.
        requireGuestRegistration(userDTO);
      } else {
        userVerifier.checkIfAllRequiredAttributesAreCorrectlyFilled(userDTO);
        userVerifier.checkIfUsernameIsAvailable(userDTO);
        agencyVerifier.checkIfConsultingTypeMatchesToAgency(userDTO);
      }
      var consultingTypeSettings = obtainConsultingTypeSettings(userDTO);
      var linkEntry =
          guest
              ? Optional
                  .<de.caritas.cob.userservice.api.service.agencyinvitelink
                          .TemporaryAccountLinkResolver.Entry>
                      empty()
              : temporaryAccountLinkResolver.resolveEntry(userDTO);
      var directLink =
          linkEntry.map(
              de.caritas.cob.userservice.api.service.agencyinvitelink.TemporaryAccountLinkResolver
                      .Entry
                  ::link);
      boolean callGuest =
          directLink
              .filter(link -> InviteLinkKind.CALL.name().equals(link.getLinkKind()))
              .isPresent();
      if (!guest
          && (callGuest
              || consultingTypeSettings.getGroupChat() == null
              || !isTrue(consultingTypeSettings.getGroupChat().getIsGroupChat()))) {
        // A guest has no agency yet; its tenant's declaration is checked before the name is
        // claimed, and the agency's when a counsellor accepts.
        dpaPolicy.requireForAgency(userDTO.getAgencyId());
      }
      var invitation =
          guest
              ? Optional.<GroupInviteRegistration.Invitation>empty()
              : groupInviteRegistration.resolveInvitation(userDTO);
      Optional<Chat> invitedGroup = invitation.map(GroupInviteRegistration.Invitation::group);
      var conversationType =
          guest
              ? ConversationType.LIVE_CHAT
              : invitedGroup.isPresent()
                  ? ConversationType.SELF_HELP
                  : ConversationType.AGENCY_COUNSELLING;
      if (userDTO.isTemporary() && invitedGroup.isEmpty() && directLink.isEmpty()) {
        // The deletion job removes temporary accounts; only a valid group invite or direct link
        // may ask for one. Checked before the e-mail rule
        // (#1662), so a form mistake stays a plain 400.
        throw new BadRequestException(
            "A temporary account requires a group invite or a direct link.");
      }
      Long tenantId = TenantContext.getCurrentTenant();
      if (userDTO.getEmail() != null && !userDTO.getEmail().isBlank()) {
        emailCollectionPolicy.requireForRegistration(
            tenantId, conversationType, userDTO.isTemporary());
      }
      var displayName =
          guest
              ? null
              : sessionDataService.validateRegistrationNames(
                  userDTO.getUsername(), userDTO.getDisplayName(), tenantId, conversationType);
      if (guest) {
        // The rolled User-ID is the only name a guest has.
        userDTO.setDisplayName(null);
      } else if (invitedGroup.isEmpty() && directLink.isEmpty()) {
        assertDisplayNameHoldsNoPassword(displayName, userDTO.getPassword());
        userDTO.setDisplayName(displayName);
      } else {
        // Group/direct-link signup creates no personal Session here; no shared/global alias.
        userDTO.setDisplayName(null);
      }

      RecoveryPolicySnapshot snapshot =
          chatRecoveryEnrollmentPolicyService.forNewAsker(TenantContext.getCurrentTenant());
      var inactivityPolicy =
          inactivityEnrollment.capture(
              TenantContext.getCurrentTenant(),
              de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Group
                  .ASKER);
      CreatedIdentity response = identityClient.createUser(userDTO);
      String identityUserId = CreatedIdentity.requireUserId(response);
      provisioningAttempt =
          provisioningCompensator.begin(
              guest ? ProvisioningWorkflow.LIVE_CHAT_GUEST : ProvisioningWorkflow.REGISTERED_USER);
      ProvisioningAttempt activeAttempt = provisioningAttempt;
      activeAttempt.register(
          IDENTITY_USER, identityUserId, () -> identityAccountRemover.rollbackUser(identityUserId));
      activeAttempt.register(
          DATABASE_USER,
          identityUserId,
          () -> {
            try {
              deleteDatabaseUser(identityUserId, provisionedUser.get());
            } finally {
              inactivityEnrollment.discardUncompletedCreation(identityUserId, inactivityPolicy);
            }
          });

      inactivityEnrollment.enroll(
          identityUserId, TenantContext.getCurrentTenant(), inactivityPolicy);
      User user =
          updateIdentityAndCreateAccount(identityUserId, userDTO, registrationType, snapshot);
      if (user != null) {
        user.setTemporaryAccount(userDTO.isTemporary());
        if (userDTO.isTemporary()) {
          // #1662: "Konto behalten" is judged by the mode the guest entered under.
          user.setGuestAccountModeAtEntry(
              linkEntry
                  .map(
                      de.caritas.cob.userservice.api.service.agencyinvitelink
                              .TemporaryAccountLinkResolver.Entry
                          ::mode)
                  .or(() -> invitation.map(GroupInviteRegistration.Invitation::mode))
                  .orElse(null));
        }
        // ADR-029 §4: the account keeps the lifetimes valid at its creation (#1476).
        if (directLink.isPresent() && userDTO.isTemporary()) {
          var link = directLink.get();
          user.setTemporaryAccountSnapshot(
              temporaryAccountSettings.snapshotForNewAccount(
                  de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType
                      .valueOf(link.getChatType()),
                  link.getId()));
        }
      }
      provisionedUser.set(user);
      User savedUser = userService.saveUser(user);
      if (savedUser != null) {
        user = savedUser;
        provisionedUser.set(savedUser);
      }

      String plainUsername;
      if (guest) {
        // The server built this registration, so no request body left plain credentials behind.
        plainUsername = new UsernameTranscoder().decodeUsername(userDTO.getUsername());
      } else if (plainCreds != null && plainCreds.getUsername() != null) {
        plainUsername = plainCreds.getUsername();
      } else if (user != null && user.getUsername() != null) {
        plainUsername = new UsernameTranscoder().decodeUsername(user.getUsername());
      } else {
        plainUsername = null;
      }
      provisionMatrixUser(user, plainUsername, activeAttempt);

      Long sessionId;
      IdentityLogin login = null;
      if (guest) {
        // Sign the guest in before the waiting place exists: a guest without tokens could never
        // enter, so a failed login undoes the account instead of leaving it queued.
        login = identityAuthentication.login(userDTO.getUsername(), userDTO.getPassword());
        activeAttempt.register(
            SESSION, identityUserId, () -> deleteSessionsForUser(provisionedUser.get()));
        sessionId =
            anonymousConversationCreatorService.createAnonymousConversation(userDTO, user).getId();
      } else if (invitedGroup.isPresent()) {
        // Joining a self-help group is not a request for counselling: no enquiry (FE#1499).
        Chat group = invitedGroup.get();
        activeAttempt.register(
            GROUP_CHAT_MEMBERSHIP,
            identityUserId,
            () -> groupInviteRegistration.leave(group, provisionedUser.get()));
        groupInviteRegistration.join(group, user);
        sessionId = null;
      } else if (directLink.isPresent()) {
        // Link redemption handles admission. Registration itself never creates an enquiry.
        sessionId = null;
      } else {
        activeAttempt.register(
            SESSION, identityUserId, () -> deleteSessionsForUser(provisionedUser.get()));
        NewRegistrationResponseDto registration =
            createNewSessionFacade.initializeNewSession(userDTO, user, consultingTypeSettings);
        sessionId = registration.getSessionId();
      }

      // The registration event contract requires a session id; a group join opens none. A guest
      // has not registered: the live chat is counted when a counsellor accepts it.
      if (sessionId != null && !guest) {
        try {
          RegistrationStatisticsEvent registrationEvent =
              new RegistrationStatisticsEvent(
                  userDTO,
                  user,
                  sessionId,
                  topicService.findTopicInternalIdentifier(userDTO.getMainTopicId()),
                  topicService.findTopicsInternalAttributes(userDTO.getTopicIds()),
                  getTenantName(),
                  getAgencyName(userDTO));
          statisticsService.fireEvent(registrationEvent);
        } catch (Exception e) {
          log.error("Could not create registration statistics event", e);
        }
      }

      activeAttempt.complete();
      announceRegistration(tenantId, user, userDTO.getRegistrationRef());

      // The welcome mail carries the generated user name, which ORISO cannot
      // recover. Sent after the attempt is marked complete and guarded here:
      // @Async submission throws synchronously when the executor queue is
      // full, and neither that nor a later bounce may undo a registration
      // that has already succeeded.
      if (!guest) {
        try {
          welcomeEmailService.sendWelcomeEmail(user, plainUsername);
        } catch (Exception e) {
          log.error("Could not schedule welcome mail", e);
        }
      }

      return new CreatedAccount(identityUserId, sessionId, login);
    } finally {
      compensateProvisioning(provisioningAttempt);
      de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.clear();
    }
  }

  private void announceRegistration(Long tenantId, User user, String registrationRef) {
    if (registrationRef != null && user != null && user.getUserId() != null) {
      lifecycleEvents.userRegistered(tenantId, user.getUserId(), registrationRef);
    }
  }

  /** Provisions and persists the Matrix identity needed by browser token bootstrap. */
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
        provisioningAttempt.register(
            CHAT_IDENTITY,
            matrixUserId,
            () -> {
              if (!matrixSynapseService.deactivateUser(matrixUserId)) {
                throw new IllegalStateException("Chat identity deactivation was not acknowledged");
              }
            });
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
   * Assigns the role and password in the identity provider and creates the user row in MariaDB.
   * Every advice seeker gets the {@code user} realm role; the realm defines no {@code anonymous}
   * role, and a guest is recognised by the registration type instead (ADR-038).
   */
  User updateIdentityAndCreateAccount(
      String userId,
      UserDTO userDTO,
      RegistrationType registrationType,
      RecoveryPolicySnapshot snapshot) {

    if (userDTO.getEmail() != null && !userDTO.getEmail().isBlank()) {
      AskerIdentityPolicy.requireEmailCollectionAllowed(
          tenantService, TenantContext.getCurrentTenant());
    }

    try {
      updateKeycloakRoleAndPassword(userId, userDTO);
    } catch (RuntimeException ex) {
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
            language,
            snapshot);

    if (shouldClearPrivacyConfirmations(registrationType, userDTO) && nonNull(user)) {
      user.setTermsAndConditionsConfirmation(null);
      user.setDataPrivacyConfirmation(null);
      user = userService.saveUser(user);
    }

    return user;
  }

  private boolean shouldClearPrivacyConfirmations(
      RegistrationType registrationType, UserDTO userDTO) {
    if (registrationType == RegistrationType.ANONYMOUS) {
      return true;
    }

    // The app can also register an anonymous chat through /users/askers/new. Detect that by its
    // synthetic postcode, never by the User-ID text (#1697), so the first in-chat privacy gate is
    // shown and persisted only after explicit acceptance.
    return StringUtils.equals(GUEST_POSTCODE, userDTO.getPostcode());
  }

  /**
   * The User-ID rule for a guest: only a name the generator can roll, never a typed one. Whether
   * the name is still free is decided where it is claimed, before this entry is called.
   */
  private void requireGuestRegistration(UserDTO userDTO) {
    sessionDataService.requireGeneratedUsername(userDTO.getUsername());
    if (userDTO.isTemporary() || !isBlank(userDTO.getEmail())) {
      throw new BadRequestException("A live-chat guest has neither an e-mail nor a kept account.");
    }
  }

  /** The alias is stored and shown to counsellors in plain text, so it must not hold a secret. */
  private void assertDisplayNameHoldsNoPassword(String displayName, String password) {
    if (displayName != null && !isBlank(password) && displayName.contains(password.strip())) {
      throw new BadRequestException("A session alias must not contain the password");
    }
  }

  private ExtendedConsultingTypeResponseDTO obtainConsultingTypeSettings(UserDTO userDTO) {
    return consultingTypeManager.getConsultingTypeSettings(userDTO.getConsultingType());
  }

  private void updateKeycloakRoleAndPassword(String userId, UserDTO userDTO) {
    checkIfUserIdNotNull(userId);
    identityClient.updateRole(userId, UserRole.USER);
    identityPasswordUpdater.updatePassword(userId, userDTO.getPassword());
  }

  private void checkIfUserIdNotNull(String userId) {
    if (isNull(userId)) {
      throw new InternalServerErrorException("Could not create identity account");
    }
  }

  private String returnDummyEmailIfNoneGiven(UserDTO userDTO, String userId) {
    if (isBlank(userDTO.getEmail())) {
      return identityDummyEmailUpdater.updateDummyEmail(
          userId, new IdentityDummyEmailUpdate(userDTO.getUsername(), userDTO.getTenantId()));
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
