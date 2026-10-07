package de.caritas.cob.userservice.api.conversation.facade;

import static org.apache.commons.lang3.BooleanUtils.isFalse;

import de.caritas.cob.userservice.api.adapters.keycloak.commands.*;
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

  private final @NonNull de.caritas.cob.userservice.api.adapters.keycloak.commands
          .IdentityCreationLocalCompletion
      localCompletion;
  private final @NonNull IdentityAccountProvisioning identityProvisioning;
  private final @NonNull IdentityCreationLocalTransactions localTransactions;
  private final @NonNull IdentityAnonymousBootstrapFailure bootstrapFailure;
  private final @NonNull de.caritas.cob.userservice.api.facade.rollback.RollbackFacade
      rollbackFacade;
  private final @NonNull AnonymousUserCreatorService anonymousUserCreatorService;
  private final @NonNull de.caritas.cob.userservice.api.service.dpa.NewCounsellingDpaPolicy
      dpaPolicy;
  private final @NonNull AnonymousConversationCreatorService anonymousConversationCreatorService;
  private final @NonNull AnonymousUsernameRegistry usernameRegistry;
  private final @NonNull UserHelper userHelper;
  private final @NonNull ConsultingTypeManager consultingTypeManager;
  private final @NonNull TenantContextProvider tenantContextProvider;

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

    if (!skipConsultingTypeAnonymousCheck) {
      checkIfConsultingTypeHasAnonymousConsulting(createAnonymousEnquiryDTO.getConsultingType());
    }

    dpaPolicy.requireForTenant(TenantContext.getCurrentTenant());
    var userDto = buildUserDto(createAnonymousEnquiryDTO);
    var origin = IdentityCreationOrigin.checkedAnonymous(userDto, TenantContext.getCurrentTenant());
    var completedLocal = new java.util.concurrent.atomic.AtomicReference<CreatedAnonymous>();
    CreatedAnonymous created;
    try {
      created =
          localTransactions.execute(
              () -> {
                var result = createLocalAnonymousSaga(userDto, origin);
                completedLocal.set(result);
                return result;
              });
    } catch (RuntimeException failure) {
      var result = completedLocal.get();
      if (result != null) {
        try {
          bootstrapFailure.record(
              result.credentials().getUserId(), result.session().getId(), failure);
        } catch (RuntimeException checkpointFailure) {
          failure.addSuppressed(checkpointFailure);
        }
      }
      throw failure;
    }
    // execute() commits local state and invokes native activation before returning.
    AnonymousUserCredentials credentials;
    try {
      credentials =
          anonymousUserCreatorService.authenticateCreatedUser(userDto, created.credentials());
    } catch (RuntimeException failure) {
      try {
        bootstrapFailure.record(
            created.credentials().getUserId(), created.session().getId(), failure);
      } catch (RuntimeException lifecycleFailure) {
        failure.addSuppressed(lifecycleFailure);
      }
      throw failure;
    }
    bootstrapFailure.complete(created.credentials().getUserId(), created.session().getId());
    return new CreateAnonymousEnquiryResponseDTO()
        .userName(userDto.getUsername())
        .accessToken(credentials.getAccessToken())
        .refreshToken(credentials.getRefreshToken())
        .expiresIn(credentials.getExpiresIn())
        .refreshExpiresIn(credentials.getRefreshExpiresIn())
        .sessionId(created.session().getId());
  }

  private record CreatedAnonymous(
      de.caritas.cob.userservice.api.model.Session session, AnonymousUserCredentials credentials) {}

  private CreatedAnonymous createLocalAnonymousSaga(
      UserDTO userDto, IdentityCreationOrigin origin) {
    var credentials = anonymousUserCreatorService.createAnonymousUser(userDto, origin);
    de.caritas.cob.userservice.api.model.Session session = null;
    try {
      session =
          anonymousConversationCreatorService.createAnonymousConversation(userDto, credentials);
      localCompletion.anonymousSession(credentials.getUserId(), session);
      return new CreatedAnonymous(session, credentials);
    } catch (RuntimeException failure) {
      try {
        if (session != null)
          rollbackFacade.rollBackUserAccount(
              de.caritas.cob.userservice.api.facade.rollback.RollbackUserAccountInformation
                  .builder()
                  .userId(credentials.getUserId())
                  .user(session.getUser())
                  .session(session)
                  .rollBackUserAccount(true)
                  .build());
        else identityProvisioning.compensateCreatedAccount(credentials.getUserId());
      } catch (RuntimeException compensationFailure) {
        failure.addSuppressed(compensationFailure);
      }
      throw failure;
    }
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
