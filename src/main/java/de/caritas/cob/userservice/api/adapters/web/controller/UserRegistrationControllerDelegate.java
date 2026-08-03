package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.model.NewSessionValidationConstraint.ONE_SESSION_PER_CONSULTING_TYPE;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateEnquiryMessageResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.EnquiryMessageDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MagicLinkConsumeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MagicLinkRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MagicLinkSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationDto;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetConfirmDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.facade.CreateEnquiryMessageFacade;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.assignsession.AssignEnquiryFacade;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.EnquiryData;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.service.archive.SessionDeleteService;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService;
import de.caritas.cob.userservice.api.service.auth.PasswordResetService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import jakarta.transaction.Transactional;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class UserRegistrationControllerDelegate {

  private final @NonNull UserAccountService userAccountProvider;
  private final @NonNull SessionService sessionService;
  private final @NonNull CreateEnquiryMessageFacade createEnquiryMessageFacade;
  private final @NonNull AssignEnquiryFacade assignEnquiryFacade;
  private final @NonNull CreateUserFacade createUserFacade;
  private final @NonNull CreateNewSessionFacade createNewSessionFacade;
  private final @NonNull Messaging messenger;
  private final @NonNull ConsultantDtoMapper consultantDtoMapper;
  private final @NonNull UserHelper userHelper;
  private final @NonNull IdentityManaging identityManager;
  private final @NonNull MagicLinkLoginService magicLinkLoginService;
  private final @NonNull PasswordResetService passwordResetService;
  private final @NonNull SessionDeleteService sessionDeleteService;

  @Value("${feature.topics.enabled}")
  private boolean featureTopicsEnabled;

  ResponseEntity<Void> userExists(String username) {
    val usernameAvailable = identityManager.isUsernameAvailable(username);
    val userExists = !usernameAvailable;
    if (userExists) {
      return ResponseEntity.ok().build();
    }
    return ResponseEntity.notFound().build();
  }

  ResponseEntity<Void> usernameAvailability(String username) {
    val usernameAvailable = identityManager.isUsernameAvailable(username);
    return usernameAvailable
        ? ResponseEntity.noContent().build()
        : ResponseEntity.status(HttpStatus.CONFLICT).build();
  }

  ResponseEntity<Void> requestMagicLink(MagicLinkRequestDTO requestDTO) {
    var result = magicLinkLoginService.requestMagicLink(requestDTO.getUsername());
    if (result == MagicLinkLoginService.MagicLinkRequestResult.NOT_ENABLED) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    return ResponseEntity.noContent().build();
  }

  ResponseEntity<MagicLinkSessionResponseDTO> consumeMagicLink(MagicLinkConsumeDTO consumeDTO) {
    return magicLinkLoginService
        .consumeMagicLink(consumeDTO.getToken())
        .map(MagicLinkSessionResponseDTO::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.badRequest().build());
  }

  ResponseEntity<Void> requestPasswordReset(PasswordResetRequestDTO requestDTO) {
    passwordResetService.requestPasswordReset(
        requestDTO.getUsername(), requestDTO.getLocale(), requestDTO.getApplication());
    return ResponseEntity.noContent().build();
  }

  ResponseEntity<Void> confirmPasswordReset(PasswordResetConfirmDTO confirmDTO) {
    boolean succeeded =
        passwordResetService.confirmPasswordReset(
            confirmDTO.getToken(), confirmDTO.getNewPassword());
    return succeeded ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().build();
  }

  ResponseEntity<Void> registerUser(UserDTO user) {
    validateUserHasChosenTopicIfTopicsFeatureIsEnabled(user);
    if (!userHelper.isUsernameValid(user.getUsername())) {
      throw new BadRequestException("Username is invalid");
    }
    decodePassword(user);
    user.setNewUserAccount(true);
    var sessionId = createUserFacade.createUserAccountWithInitializedConsultingType(user);

    HttpStatus status;
    if (user.isConsultantSet() && !messenger.markAsDirectConsultant(sessionId)) {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
    } else {
      status = HttpStatus.CREATED;
    }

    return ResponseEntity.status(status).build();
  }

  private void decodePassword(UserDTO user) {
    if (user.getPassword() != null) {
      user.setPassword(URLDecoder.decode(user.getPassword(), StandardCharsets.UTF_8));
    }
  }

  private void validateUserHasChosenTopicIfTopicsFeatureIsEnabled(UserDTO user) {
    if (featureTopicsEnabled && user.getMainTopicId() == null) {
      throw new BadRequestException("Main topic id is required");
    }
  }

  ResponseEntity<NewRegistrationResponseDto> registerNewConsultingType(
      NewRegistrationDto newRegistrationDto) {
    var user = this.userAccountProvider.retrieveValidatedUser();

    var registrationResponse =
        createNewSessionFacade.initializeNewSession(
            newRegistrationDto, user, Lists.newArrayList(ONE_SESSION_PER_CONSULTING_TYPE));

    return new ResponseEntity<>(registrationResponse, registrationResponse.getStatus());
  }

  ResponseEntity<NewRegistrationResponseDto> registerNewSession(
      NewRegistrationDto newRegistrationDto) {
    var user = this.userAccountProvider.retrieveValidatedUser();

    /* Additional enquiries from the profile page go through the normal
    enquiry pipeline - the consultant is NOT pre-assigned here. The asker
    lands on the "write first message" screen, the enquiry sits in the
    agency queue, and a consultant picks it up. Direct-chat with a
    specific consultant is a separate flow (QR code / ?cid=... link).
    The empty constraint list keeps this endpoint permissive so existing
    askers can raise new enquiries even when they already had a past
    session for the same topic+agency. */
    var response =
        createNewSessionFacade.initializeNewSession(newRegistrationDto, user, Lists.newArrayList());

    return new ResponseEntity<>(response, response.getStatus());
  }

  @Transactional
  ResponseEntity<Void> acceptEnquiry(Long sessionId) {
    var session = sessionService.getSessionForUpdate(sessionId);

    // The session id is invalid when no persisted session can be loaded.
    if (session.isEmpty()) {
      log.error("Internal Server Error: Session id {} is invalid, session not found.", sessionId);
      return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    var consultant = this.userAccountProvider.retrieveValidatedConsultant();
    this.assignEnquiryFacade.assignRegisteredEnquiry(session.get(), consultant);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<CreateEnquiryMessageResponseDTO> createEnquiryMessage(
      Long sessionId, EnquiryMessageDTO enquiryMessage) {
    var user = this.userAccountProvider.retrieveValidatedUser();
    var language = consultantDtoMapper.languageOf(enquiryMessage.getLanguage());
    var enquiryData =
        new EnquiryData(
            user,
            sessionId,
            enquiryMessage.getMessage(),
            language,
            enquiryMessage.getT(),
            null,
            enquiryMessage.getMatrixEventId());

    var response = createEnquiryMessageFacade.createEnquiryMessage(enquiryData);

    return new ResponseEntity<>(response, HttpStatus.CREATED);
  }

  ResponseEntity<Void> deleteSessionAndInactiveUser(Long sessionId) {
    sessionDeleteService.deleteSession(sessionId);
    return new ResponseEntity<>(HttpStatus.OK);
  }
}
