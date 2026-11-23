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
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.tenant.TenantContext;
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
  private final @NonNull de.caritas.cob.userservice.api.admin.service.tenant.TenantService
      tenantService;

  private static final String DEFAULT_ANONYMOUS_POSTCODE = "00000";

  /**
   * @param skipConsultingTypeAnonymousCheck when {@code true} the consulting-type-level "anonymous
   *     conversation allowed" gate is skipped. Used by invite-link redeem, where the link itself is
   *     explicitly anonymous and the consulting type may not enable anonymous chats globally.
   */
  public CreateAnonymousEnquiryResponseDTO createAnonymousEnquiry(
      CreateAnonymousEnquiryDTO createAnonymousEnquiryDTO,
      boolean skipConsultingTypeAnonymousCheck) {
    return createAnonymousEnquiry(
        createAnonymousEnquiryDTO, skipConsultingTypeAnonymousCheck, null);
  }

  /**
   * @param chosenUsername the animal handle the guest rolled on the entry page, or {@code null} to
   *     let the server roll one. A handle the generator cannot produce is refused (400), a taken
   *     one reported (409) — in both cases before anything is created.
   */
  public CreateAnonymousEnquiryResponseDTO createAnonymousEnquiry(
      CreateAnonymousEnquiryDTO createAnonymousEnquiryDTO,
      boolean skipConsultingTypeAnonymousCheck,
      String chosenUsername) {

    tenantService.requireOperationalTenant(TenantContext.getCurrentTenant());
    if (!skipConsultingTypeAnonymousCheck) {
      checkIfConsultingTypeHasAnonymousConsulting(createAnonymousEnquiryDTO.getConsultingType());
    }

    dpaPolicy.requireForTenant(TenantContext.getCurrentTenant());
    var userDto = buildUserDto(createAnonymousEnquiryDTO, chosenUsername);
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

  private void checkIfConsultingTypeHasAnonymousConsulting(int consultingTypeId) {
    var consultingTypeSettings = consultingTypeManager.getConsultingTypeSettings(consultingTypeId);

    if (isFalse(consultingTypeSettings.getIsAnonymousConversationAllowed())) {
      throw new BadRequestException("Consulting type does not support anonymous conversations.");
    }
  }

  private UserDTO buildUserDto(
      CreateAnonymousEnquiryDTO createAnonymousEnquiryDTO, String chosenUsername) {
    var username =
        chosenUsername == null
            ? usernameRegistry.generateUniqueUsername()
            : usernameRegistry.claimChosenUsername(chosenUsername);
    return UserDTO.builder()
        .consultingType(String.valueOf(createAnonymousEnquiryDTO.getConsultingType()))
        .username(username)
        .password(userHelper.getRandomPassword())
        .postcode(DEFAULT_ANONYMOUS_POSTCODE)
        .termsAccepted("true")
        .mainTopicId(createAnonymousEnquiryDTO.getMainTopicId())
        .consultantId(createAnonymousEnquiryDTO.getConsultantId())
        .build();
  }
}
