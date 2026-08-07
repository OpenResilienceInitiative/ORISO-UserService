package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.model.NewSessionValidationConstraint.ONE_SESSION_PER_CONSULTING_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateEnquiryMessageResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.EnquiryMessageDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.MagicLinkConsumeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MagicLinkRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MagicLinkSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationDto;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetApplication;
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
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.EnquiryData;
import de.caritas.cob.userservice.api.model.NewSessionValidationConstraint;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.service.archive.SessionDeleteService;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService;
import de.caritas.cob.userservice.api.service.auth.PasswordResetService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserRegistrationControllerDelegateTest {

  private static final String USER_ID = "user-id";
  private static final String USERNAME = "username";
  private static final Long SESSION_ID = 1L;

  @Mock private UserAccountService userAccountProvider;
  @Mock private SessionService sessionService;
  @Mock private CreateEnquiryMessageFacade createEnquiryMessageFacade;
  @Mock private AssignEnquiryFacade assignEnquiryFacade;
  @Mock private CreateUserFacade createUserFacade;
  @Mock private CreateNewSessionFacade createNewSessionFacade;
  @Mock private Messaging messenger;
  @Mock private ConsultantDtoMapper consultantDtoMapper;
  @Mock private UserHelper userHelper;
  @Mock private IdentityManaging identityManager;
  @Mock private MagicLinkLoginService magicLinkLoginService;
  @Mock private PasswordResetService passwordResetService;
  @Mock private SessionDeleteService sessionDeleteService;

  @InjectMocks private UserRegistrationControllerDelegate delegate;

  @Test
  void userExistsShouldReturnOkWhenUsernameExists() {
    when(identityManager.isUsernameAvailable(USERNAME)).thenReturn(false);

    var response = delegate.userExists(USERNAME);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void usernameAvailabilityShouldReturnConflictWhenUsernameIsTaken() {
    when(identityManager.isUsernameAvailable(USERNAME)).thenReturn(false);

    var response = delegate.usernameAvailability(USERNAME);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void requestMagicLinkShouldReturnForbiddenWhenMagicLinkIsNotEnabled() {
    var request = new MagicLinkRequestDTO();
    request.setUsername(USERNAME);
    when(magicLinkLoginService.requestMagicLink(USERNAME))
        .thenReturn(MagicLinkLoginService.MagicLinkRequestResult.NOT_ENABLED);

    var response = delegate.requestMagicLink(request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void consumeMagicLinkShouldReturnOkWhenTokenIsValid() {
    var consume = new MagicLinkConsumeDTO();
    consume.setToken("token");
    var identitySession =
        new IdentitySession(
            "access-token", 300, 600, "refresh-token", "Bearer", "session-state", "openid profile");
    when(magicLinkLoginService.consumeMagicLink("token")).thenReturn(Optional.of(identitySession));

    var response = delegate.consumeMagicLink(consume);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(MagicLinkSessionResponseDTO.from(identitySession));
  }

  @Test
  void registerUserShouldKeepDeserializedPasswordSetNewUserAndReturnCreated() {
    var user = newUserDto();
    user.setPassword("pa%20ss");
    when(userHelper.isUsernameValid(USERNAME)).thenReturn(true);
    when(createUserFacade.createUserAccountWithInitializedConsultingType(user))
        .thenReturn(SESSION_ID);

    var response = delegate.registerUser(user);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(user.getPassword()).isEqualTo("pa%20ss");
    assertThat(user.isNewUserAccount()).isTrue();
  }

  @Test
  void registerUserShouldRejectMissingTopicWhenTopicsFeatureIsEnabled() {
    ReflectionTestUtils.setField(delegate, "featureTopicsEnabled", true);
    var user = newUserDto();

    assertThatThrownBy(() -> delegate.registerUser(user)).isInstanceOf(BadRequestException.class);

    verifyNoInteractions(userHelper, createUserFacade);
  }

  @Test
  void registerUserShouldReturnInternalServerErrorWhenDirectConsultantMarkFails() {
    var user = newUserDto();
    user.setConsultantId("consultant-id");
    when(userHelper.isUsernameValid(USERNAME)).thenReturn(true);
    when(createUserFacade.createUserAccountWithInitializedConsultingType(user))
        .thenReturn(SESSION_ID);
    when(messenger.markAsDirectConsultant(SESSION_ID)).thenReturn(false);

    var response = delegate.registerUser(user);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void registerNewConsultingTypeShouldUseOneSessionPerConsultingTypeConstraint() {
    var registration = new NewRegistrationDto();
    var user = newUser();
    var registrationResponse = new NewRegistrationResponseDto().status(HttpStatus.CREATED);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user);
    when(createNewSessionFacade.initializeNewSession(any(), any(), anyList()))
        .thenReturn(registrationResponse);

    var response = delegate.registerNewConsultingType(registration);

    var constraintsCaptor = constraintsCaptor();
    verify(createNewSessionFacade)
        .initializeNewSession(eq(registration), eq(user), constraintsCaptor.capture());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isSameAs(registrationResponse);
    assertThat(constraintsCaptor.getValue()).containsExactly(ONE_SESSION_PER_CONSULTING_TYPE);
  }

  @Test
  void registerNewSessionShouldUseEmptyConstraintList() {
    var registration = new NewRegistrationDto();
    var user = newUser();
    var registrationResponse = new NewRegistrationResponseDto().status(HttpStatus.ACCEPTED);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user);
    when(createNewSessionFacade.initializeNewSession(any(), any(), anyList()))
        .thenReturn(registrationResponse);

    var response = delegate.registerNewSession(registration);

    var constraintsCaptor = constraintsCaptor();
    verify(createNewSessionFacade)
        .initializeNewSession(eq(registration), eq(user), constraintsCaptor.capture());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isSameAs(registrationResponse);
    assertThat(constraintsCaptor.getValue()).isEmpty();
  }

  @Test
  void acceptEnquiryShouldReturnInternalServerErrorWhenSessionIsMissing() {
    when(sessionService.getSessionForUpdate(SESSION_ID)).thenReturn(Optional.empty());

    var response = delegate.acceptEnquiry(SESSION_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    verify(assignEnquiryFacade, never()).assignRegisteredEnquiry(any(), any());
  }

  @Test
  void acceptEnquiryShouldAssignRegisteredEnquiry() {
    var session = new Session();
    var consultant = new Consultant();
    when(sessionService.getSessionForUpdate(SESSION_ID)).thenReturn(Optional.of(session));
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);

    var response = delegate.acceptEnquiry(SESSION_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(assignEnquiryFacade).assignRegisteredEnquiry(session, consultant);
  }

  @Test
  void createEnquiryMessageShouldBuildEnquiryDataAndReturnCreated() {
    var user = newUser();
    var enquiryMessage = org.mockito.Mockito.mock(EnquiryMessageDTO.class);
    var messageResponse =
        new CreateEnquiryMessageResponseDTO()
            .sessionId(SESSION_ID)
            .matrixRoomId("!room-id:matrix.example");
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user);
    when(enquiryMessage.getMessage()).thenReturn("message");
    when(enquiryMessage.getLanguage()).thenReturn(LanguageCode.EN);
    when(enquiryMessage.getT()).thenReturn("text");
    when(consultantDtoMapper.languageOf(LanguageCode.EN)).thenReturn("en");
    when(createEnquiryMessageFacade.createEnquiryMessage(any())).thenReturn(messageResponse);

    var response = delegate.createEnquiryMessage(SESSION_ID, enquiryMessage);

    var enquiryDataCaptor = ArgumentCaptor.forClass(EnquiryData.class);
    verify(createEnquiryMessageFacade).createEnquiryMessage(enquiryDataCaptor.capture());
    var enquiryData = enquiryDataCaptor.getValue();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isSameAs(messageResponse);
    assertThat(enquiryData.getUser()).isSameAs(user);
    assertThat(enquiryData.getSessionId()).isEqualTo(SESSION_ID);
    assertThat(enquiryData.getMessage()).isEqualTo("message");
    assertThat(enquiryData.getLanguage()).isEqualTo("en");
    assertThat(enquiryData.getType()).isEqualTo("text");
  }

  @Test
  void createEnquiryMessageRejectsPlaintextAlongsideEncryptedEventReference() {
    var enquiryMessage = org.mockito.Mockito.mock(EnquiryMessageDTO.class);
    when(enquiryMessage.getMatrixEventId()).thenReturn("$encrypted-event");
    when(enquiryMessage.getMessage()).thenReturn("plaintext must not cross this boundary");

    assertThatThrownBy(() -> delegate.createEnquiryMessage(SESSION_ID, enquiryMessage))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("must not include plaintext");

    verifyNoInteractions(createEnquiryMessageFacade);
  }

  @Test
  void deleteSessionAndInactiveUserShouldDelegateSessionDeletion() {
    var response = delegate.deleteSessionAndInactiveUser(SESSION_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(sessionDeleteService).deleteSession(SESSION_ID);
  }

  @Test
  void userExists_usernameAvailable_returnsNotFound() {
    // Available usernames indicate the account does not exist yet.
    when(identityManager.isUsernameAvailable(USERNAME)).thenReturn(true);

    var response = delegate.userExists(USERNAME);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void usernameAvailability_available_returnsNoContent() {
    // Available usernames confirm the handle can be registered.
    when(identityManager.isUsernameAvailable(USERNAME)).thenReturn(true);

    var response = delegate.usernameAvailability(USERNAME);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void requestMagicLink_enabled_returnsNoContent() {
    // Enabled magic-link login accepts the request without a response body.
    var request = new MagicLinkRequestDTO();
    request.setUsername(USERNAME);
    when(magicLinkLoginService.requestMagicLink(USERNAME))
        .thenReturn(MagicLinkLoginService.MagicLinkRequestResult.ACCEPTED);

    var response = delegate.requestMagicLink(request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void consumeMagicLink_invalidToken_returnsBadRequest() {
    // Invalid or expired magic-link tokens are rejected.
    var consume = new MagicLinkConsumeDTO();
    consume.setToken("invalid-token");
    when(magicLinkLoginService.consumeMagicLink("invalid-token")).thenReturn(Optional.empty());

    var response = delegate.consumeMagicLink(consume);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void registerUser_invalidUsername_throwsBadRequestException() {
    // Registration rejects usernames that fail validation rules.
    var user = newUserDto();
    when(userHelper.isUsernameValid(USERNAME)).thenReturn(false);

    assertThatThrownBy(() -> delegate.registerUser(user)).isInstanceOf(BadRequestException.class);

    verifyNoInteractions(createUserFacade);
  }

  @Test
  void registerUser_consultantSetAndDirectConsultantSuccess_returnsCreated() {
    // Direct-consultant registration succeeds when chat marks the session accordingly.
    var user = newUserDto();
    user.setConsultantId("consultant-id");
    when(userHelper.isUsernameValid(USERNAME)).thenReturn(true);
    when(createUserFacade.createUserAccountWithInitializedConsultingType(user))
        .thenReturn(SESSION_ID);
    when(messenger.markAsDirectConsultant(SESSION_ID)).thenReturn(true);

    var response = delegate.registerUser(user);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    verify(messenger).markAsDirectConsultant(SESSION_ID);
  }

  @Test
  void registerUser_topicsEnabledWithValidMainTopicId_returnsCreated() {
    // Topic selection is mandatory when the topics feature flag is enabled.
    ReflectionTestUtils.setField(delegate, "featureTopicsEnabled", true);
    var user = newUserDto();
    user.setMainTopicId(99L);
    when(userHelper.isUsernameValid(USERNAME)).thenReturn(true);
    when(createUserFacade.createUserAccountWithInitializedConsultingType(user))
        .thenReturn(SESSION_ID);

    var response = delegate.registerUser(user);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    verify(createUserFacade).createUserAccountWithInitializedConsultingType(user);
  }

  @Test
  void requestPasswordReset_returnsNoContent_and_delegatesToService() {
    var request = new PasswordResetRequestDTO();
    request.setUsername(USERNAME);
    request.setLocale("de");

    var response = delegate.requestPasswordReset(request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(passwordResetService).requestPasswordReset(USERNAME, "de", PasswordResetApplication.APP);
  }

  @Test
  void requestPasswordReset_delegatesAdminApplicationToService() {
    var request = new PasswordResetRequestDTO();
    request.setUsername(USERNAME);
    request.setLocale("en");
    request.setApplication(PasswordResetApplication.ADMIN);

    var response = delegate.requestPasswordReset(request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(passwordResetService)
        .requestPasswordReset(USERNAME, "en", PasswordResetApplication.ADMIN);
  }

  @Test
  void confirmPasswordReset_returnsNoContent_When_TokenValid() {
    var confirm = new PasswordResetConfirmDTO();
    confirm.setToken("valid-token");
    confirm.setNewPassword("NewPassw0rd!");
    when(passwordResetService.confirmPasswordReset("valid-token", "NewPassw0rd!")).thenReturn(true);

    var response = delegate.confirmPasswordReset(confirm);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(passwordResetService).confirmPasswordReset("valid-token", "NewPassw0rd!");
  }

  @Test
  void confirmPasswordReset_returnsBadRequest_When_TokenInvalid() {
    var confirm = new PasswordResetConfirmDTO();
    confirm.setToken("bad-token");
    confirm.setNewPassword("NewPassw0rd!");
    when(passwordResetService.confirmPasswordReset("bad-token", "NewPassw0rd!")).thenReturn(false);

    var response = delegate.confirmPasswordReset(confirm);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verify(passwordResetService).confirmPasswordReset("bad-token", "NewPassw0rd!");
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<List<NewSessionValidationConstraint>> constraintsCaptor() {
    return ArgumentCaptor.forClass(List.class);
  }

  private UserDTO newUserDto() {
    return new UserDTO(USERNAME, "12345", 1L, "password", "user@example.org", "true", "0");
  }

  private User newUser() {
    return new User(USER_ID, null, USERNAME, "user@example.org", false);
  }
}
