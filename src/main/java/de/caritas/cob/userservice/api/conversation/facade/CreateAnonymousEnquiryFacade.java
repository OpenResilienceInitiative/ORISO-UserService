package de.caritas.cob.userservice.api.conversation.facade;

import static org.apache.commons.lang3.BooleanUtils.isFalse;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.conversation.model.AnonymousUserCredentials;
import de.caritas.cob.userservice.api.conversation.service.AnonymousConversationCreatorService;
import de.caritas.cob.userservice.api.conversation.service.user.anonymous.AnonymousUserCreatorService;
import de.caritas.cob.userservice.api.conversation.service.user.anonymous.AnonymousUsernameRegistry;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Facade to encapsulate the steps of creating a new anonymous conversation. */
@Service
@RequiredArgsConstructor
public class CreateAnonymousEnquiryFacade {

  private final @NonNull AnonymousUserCreatorService anonymousUserCreatorService;
  private final @NonNull de.caritas.cob.userservice.api.service.dpa.NewCounsellingDpaPolicy
      dpaPolicy;
  private final @NonNull AnonymousConversationCreatorService anonymousConversationCreatorService;
  private final @NonNull AnonymousUsernameRegistry usernameRegistry;
  private final @NonNull UserHelper userHelper;
  private final @NonNull ConsultingTypeManager consultingTypeManager;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull de.caritas.cob.userservice.api.admin.service.tenant.TenantService
      tenantService;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.core.env.Environment lifecycleEnvironment;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.beans.factory.ObjectProvider<
          de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation>
      ordinaryAccountCreation;

  @org.springframework.beans.factory.annotation.Autowired
  private de.caritas.cob.userservice.api.service.user.UserService lifecycleUsers;

  @org.springframework.beans.factory.annotation.Autowired
  private de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService
      lifecycleRecovery;

  @org.springframework.beans.factory.annotation.Autowired
  private de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService
      lifecycleInactivity;

  @org.springframework.beans.factory.annotation.Autowired
  private de.caritas.cob.userservice.api.admin.service.consultant.validation
          .UserAccountInputValidator
      lifecycleInputs;

  @org.springframework.beans.factory.annotation.Autowired
  private de.caritas.cob.userservice.api.port.out.IdentityAuthentication lifecycleLogin;

  private boolean fiveActors() {
    return lifecycleEnvironment != null
        && "FIVE_ACTORS".equals(lifecycleEnvironment.getProperty("TASK_IDENTITY_MODEL", "LEGACY"));
  }

  private static final String DEFAULT_ANONYMOUS_POSTCODE = "00000";

  /**
   * Creates an anonymous user account and its corresponding anonymous conversation resp. session
   * and returns all needed user credentials.
   *
   * @param createAnonymousEnquiryDTO {@link CreateAnonymousEnquiryDTO}
   * @return {@link CreateAnonymousEnquiryResponseDTO}
   */
  public CreateAnonymousEnquiryResponseDTO createAnonymousEnquiry(
      CreateAnonymousEnquiryDTO createAnonymousEnquiryDTO) {
    // Without a tenant the new user and session would be written without one.
    if (tenantContextProvider.isMultiTenancyEnabled() && !TenantContext.contextIsSet()) {
      throw new ForbiddenException("An anonymous enquiry needs a tenant");
    }
    return createAnonymousEnquiry(createAnonymousEnquiryDTO, false);
  }

  /**
   * @param skipConsultingTypeAnonymousCheck when {@code true} the consulting-type-level "anonymous
   *     conversation allowed" gate is skipped. Used by invite-link redeem, where the link itself is
   *     explicitly anonymous and the consulting type may not enable anonymous chats globally.
   */
  public CreateAnonymousEnquiryResponseDTO createAnonymousEnquiry(
      CreateAnonymousEnquiryDTO createAnonymousEnquiryDTO,
      boolean skipConsultingTypeAnonymousCheck) {

    tenantService.requireOperationalTenant(TenantContext.getCurrentTenant());
    if (!skipConsultingTypeAnonymousCheck) {
      checkIfConsultingTypeHasAnonymousConsulting(createAnonymousEnquiryDTO.getConsultingType());
    }

    dpaPolicy.requireForTenant(TenantContext.getCurrentTenant());
    var userDto = buildUserDto(createAnonymousEnquiryDTO);
    if (fiveActors()) return createOwnedAnonymousEnquiry(userDto);
    AnonymousUserCredentials credentials = anonymousUserCreatorService.createAnonymousUser(userDto);
    var session =
        anonymousConversationCreatorService.createAnonymousConversation(userDto, credentials);

    return new CreateAnonymousEnquiryResponseDTO()
        .userName(userDto.getUsername())
        .accessToken(credentials.getAccessToken())
        .refreshToken(credentials.getRefreshToken())
        .expiresIn(credentials.getExpiresIn())
        .refreshExpiresIn(credentials.getRefreshExpiresIn())
        .sessionId(session.getId());
  }

  private CreateAnonymousEnquiryResponseDTO createOwnedAnonymousEnquiry(UserDTO dto) {
    var flow = ordinaryAccountCreation == null ? null : ordinaryAccountCreation.getIfAvailable();
    if (flow == null)
      throw new IllegalStateException("Ordinary anonymous lifecycle activation remains gated");
    lifecycleInputs.validateUserDTO(dto);
    Long tenant = TenantContext.getCurrentTenant();
    var scope = flow.anonymousScope(dto, tenant);
    var recovery = lifecycleRecovery.forNewAsker(tenant);
    var policy =
        lifecycleInactivity.capture(
            tenant,
            de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Group.ASKER);
    var settings = consultingTypeManager.getConsultingTypeSettings(dto.getConsultingType());
    String plain =
        new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
            .decodeUsername(dto.getUsername());
    var account =
        new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos
            .AccountCreation(plain, null, null, null, "de", dto.getPassword(), false);
    var session =
        flow.create(
            scope,
            account,
            pending -> {
              var projection = pending.projection();
              var user =
                  lifecycleUsers.createNewLifecycleUser(
                      pending.accountId(),
                      dto.getUsername(),
                      projection.email(),
                      Boolean.TRUE.equals(settings.getLanguageFormal()),
                      projection.preferredLanguage(),
                      recovery,
                      policy);
              user.setTermsAndConditionsConfirmation(null);
              user.setDataPrivacyConfirmation(null);
              user.setMatrixUserId(flow.createChatAccount(pending, plain, plain));
              lifecycleUsers.saveUser(user);
              return anonymousConversationCreatorService.createOwnedAnonymousConversation(
                  dto, AnonymousUserCredentials.builder().userId(pending.accountId()).build());
            });
    // The bounded workflow has confirmed both local commit and first activation before login.
    var login = lifecycleLogin.login(dto.getUsername(), dto.getPassword());
    if (login == null || login.accessToken() == null)
      throw new de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException(
          "Anonymous account login is unavailable");
    return new CreateAnonymousEnquiryResponseDTO()
        .userName(dto.getUsername())
        .accessToken(login.accessToken())
        .refreshToken(login.refreshToken())
        .expiresIn(login.expiresIn())
        .refreshExpiresIn(login.refreshExpiresIn())
        .sessionId(session.getId());
  }

  private void checkIfConsultingTypeHasAnonymousConsulting(int consultingTypeId) {
    var consultingTypeSettings = consultingTypeManager.getConsultingTypeSettings(consultingTypeId);

    if (isFalse(consultingTypeSettings.getIsAnonymousConversationAllowed())) {
      throw new BadRequestException("Consulting type does not support anonymous conversations.");
    }
  }

  private UserDTO buildUserDto(CreateAnonymousEnquiryDTO createAnonymousEnquiryDTO) {
    return UserDTO.builder()
        .consultingType(String.valueOf(createAnonymousEnquiryDTO.getConsultingType()))
        .username(usernameRegistry.generateUniqueUsername())
        .password(userHelper.getRandomPassword())
        .postcode(DEFAULT_ANONYMOUS_POSTCODE)
        .termsAccepted("true")
        .mainTopicId(createAnonymousEnquiryDTO.getMainTopicId())
        .consultantId(createAnonymousEnquiryDTO.getConsultantId())
        .build();
  }
}
