package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE;
import static de.caritas.cob.userservice.api.testHelper.KeycloakConstants.CREATED_IDENTITY_WITHOUT_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.KeycloakConstants.CREATED_IDENTITY_WITH_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_SETTINGS_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ERROR;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_DTO_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_DTO_SUCHT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_ID;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.identity.IdentityProvisioningException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.helper.AgencyVerifier;
import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import de.caritas.cob.userservice.api.helper.UserVerifier;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.RegistrationStatisticsEvent;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.LocalDateTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
public class CreateUserFacadeTest {

  @InjectMocks private CreateUserFacade createUserFacade;
  @Mock private KeycloakService keycloakService;
  @Mock private UserService userService;
  @Mock private RollbackFacade rollbackFacade;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Mock private AgencyVerifier agencyVerifier;
  @Mock private CreateNewSessionFacade createNewSessionFacade;
  @Mock private UserVerifier userVerifier;
  @Mock private StatisticsService statisticsService;
  @Mock private TopicService topicService;

  @Mock private TenantService tenantService;

  @Mock private AgencyService agencyService;

  @Mock private ApplicationSettingsService applicationSettingsService;

  @Mock private MatrixSynapseService matrixSynapseService;

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_throwExpectedStatusException_When_UsernameIsAlreadyExisting() {
    doThrow(new CustomValidationHttpStatusException(USERNAME_NOT_AVAILABLE))
        .when(userVerifier)
        .checkIfUsernameIsAvailable(any());

    try {
      this.createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);
    } catch (CustomValidationHttpStatusException e) {
      assertThat(e.getCustomHttpHeaders(), notNullValue());
      assertThat(
          e.getCustomHttpHeaders().get("X-Reason").get(0),
          Matchers.is(USERNAME_NOT_AVAILABLE.name()));
    }
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_ThrowBadRequest_When_ProvidedConsultingTypeDoesNotMatchAgency() {
    assertThrows(
        BadRequestException.class,
        () -> {
          doNothing().when(userVerifier).checkIfUsernameIsAvailable(any());
          doThrow(new BadRequestException(ERROR))
              .when(agencyVerifier)
              .checkIfConsultingTypeMatchesToAgency(any());

          createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);
        });
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_throwConflictException_When_usernameIsNotAvailable() {
    doThrow(new CustomValidationHttpStatusException(USERNAME_NOT_AVAILABLE, HttpStatus.CONFLICT))
        .when(userVerifier)
        .checkIfUsernameIsAvailable(any());

    try {
      this.createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);
    } catch (CustomValidationHttpStatusException e) {
      assertThat(e.getCustomHttpHeaders(), notNullValue());
      assertThat(
          e.getCustomHttpHeaders().get("X-Reason").get(0),
          Matchers.is(USERNAME_NOT_AVAILABLE.name()));
      assertThat(e.getHttpStatus(), is(HttpStatus.CONFLICT));
    }
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_RejectIdentityResponseWithoutUserId() {
    when(keycloakService.createUser(any())).thenReturn(CREATED_IDENTITY_WITHOUT_USER_ID);

    assertThrows(
        IdentityProvisioningException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any());
    verify(rollbackFacade, times(0)).rollBackUserAccount(any());
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_Complete_When_ConsultingTypeIsKreuzbund() {

    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(keycloakService.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    doNothing().when(keycloakService).updatePassword(anyString(), anyString());

    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(mock(NewRegistrationResponseDto.class));

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);

    verify(rollbackFacade, times(0)).rollBackUserAccount(any());
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_CallNecessaryMethods_When_EverythingSucceeds() {
    TenantContext.setCurrentTenant(1L);
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(keycloakService.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    doNothing().when(keycloakService).updatePassword(anyString(), anyString());

    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(mock(NewRegistrationResponseDto.class));
    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO());
    when(agencyService.getAgencyWithoutCaching(Mockito.anyLong())).thenReturn(new AgencyDTO());

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);
    TenantContext.clear();
    verify(keycloakService, times(1)).createUser(any(UserDTO.class));
    verify(keycloakService, times(1)).updateRole(any(), any(UserRole.class));
    verify(keycloakService, times(1)).updatePassword(anyString(), anyString());
    verify(createNewSessionFacade, times(1))
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(rollbackFacade, times(0)).rollBackUserAccount(any());
    verify(statisticsService, times(1)).fireEvent(any());
  }

  @Test
  public void
      updateKeycloakAccountAndCreateDatabaseUserAccount_Should_CallNecessaryMethods_When_EverythingSucceeds() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    doNothing().when(keycloakService).updatePassword(anyString(), anyString());

    createUserFacade.updateIdentityAndCreateAccount(USER_ID, USER_DTO_SUCHT, UserRole.USER);

    verify(keycloakService, times(1)).updateRole(any(), any(UserRole.class));
    verify(keycloakService, times(1)).updatePassword(anyString(), anyString());
    verify(rollbackFacade, times(0)).rollBackUserAccount(any());
  }

  @Test
  public void
      updateKeycloakAccountAndCreateDatabaseUserAccount_Should_StillCreateDbUserAndNotRollback_When_UpdateKeycloakPwFailsForRegisteredUser() {
    // Matrix migration: for registered (non-anonymous) users a failing Keycloak password update is
    // logged and swallowed, and the database user account is still created.
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    doThrow(new RuntimeException()).when(keycloakService).updatePassword(anyString(), anyString());

    createUserFacade.updateIdentityAndCreateAccount(USER_ID, USER_DTO_SUCHT, UserRole.USER);

    verify(userService, times(1)).createUser(any(), any(), any(), any(), anyBoolean(), any());
    verify(rollbackFacade, times(0)).rollBackUserAccount(any());
  }

  @Test
  public void
      updateKeycloakAccountAndCreateDatabaseUserAccount_Should_StillCreateDbUserAndNotRollback_When_UpdateKeycloakRoleFailsForRegisteredUser() {
    // Matrix migration: for registered (non-anonymous) users a failing Keycloak role update is
    // logged and swallowed, and the database user account is still created.
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    doThrow(new RuntimeException())
        .when(keycloakService)
        .updateRole(anyString(), any(UserRole.class));

    createUserFacade.updateIdentityAndCreateAccount(USER_ID, USER_DTO_SUCHT, UserRole.USER);

    verify(userService, times(1)).createUser(any(), any(), any(), any(), anyBoolean(), any());
    verify(rollbackFacade, times(0)).rollBackUserAccount(any());
  }

  @Test
  public void
      updateKeycloakAccountAndCreateDatabaseUserAccount_Should_PropagateException_When_CreateDbUserFails() {
    // The database user creation failure is not wrapped/rolled back on this code path; the original
    // exception propagates to the caller.
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          when(consultingTypeManager.getConsultingTypeSettings(any()))
              .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
          doNothing().when(keycloakService).updatePassword(anyString(), anyString());
          when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any()))
              .thenThrow(new IllegalArgumentException());

          createUserFacade.updateIdentityAndCreateAccount(USER_ID, USER_DTO_SUCHT, UserRole.USER);

          verify(rollbackFacade, times(0)).rollBackUserAccount(any());
        });
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-06
  // ---------------------------------------------------------------------------

  private User givenAFullyPersistedUser() {
    User user = new User();
    user.setUsername("dbUser");
    user.setTenantId(1L);
    user.setCreateDate(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(user);
    when(userService.saveUser(any())).thenReturn(user);
    return user;
  }

  private void givenBasicRegistrationStubs() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(keycloakService.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(mock(NewRegistrationResponseDto.class));
    when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CreateMatrixUser_When_PlainUsernameAvailableFromThreadLocal()
          throws Exception {
    givenBasicRegistrationStubs();
    User user = givenAFullyPersistedUser();
    var matrixResponseBody = new MatrixCreateUserResponseDTO();
    matrixResponseBody.setUserId("@plainuser:matrix.oriso.org");
    try {
      PlainCredentialsHolder.set("plainuser", "plainpw");
      when(matrixSynapseService.createUser(eq("plainuser"), anyString(), eq("plainuser")))
          .thenReturn(ResponseEntity.ok(matrixResponseBody));

      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);
    } finally {
      PlainCredentialsHolder.clear();
    }

    verify(matrixSynapseService, times(1))
        .createUser(eq("plainuser"), anyString(), eq("plainuser"));
    assertThat(user.getMatrixUserId(), is("@plainuser:matrix.oriso.org"));
    verify(userService, times(2)).saveUser(any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_UseDecodedDbUsername_When_PlainCredentialsNotAvailable()
          throws Exception {
    PlainCredentialsHolder.clear();
    givenBasicRegistrationStubs();
    User user = givenAFullyPersistedUser();
    var matrixResponseBody = new MatrixCreateUserResponseDTO();
    matrixResponseBody.setUserId("@dbUser:matrix.oriso.org");
    when(matrixSynapseService.createUser(eq("dbUser"), anyString(), eq("dbUser")))
        .thenReturn(ResponseEntity.ok(matrixResponseBody));

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    verify(matrixSynapseService, times(1)).createUser(eq("dbUser"), anyString(), eq("dbUser"));
    assertThat(user.getMatrixUserId(), is("@dbUser:matrix.oriso.org"));
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_SkipMatrixUserCreation_When_PlainUsernameNotResolvable()
          throws Exception {
    PlainCredentialsHolder.clear();
    givenBasicRegistrationStubs();
    // userService.createUser/saveUser left unstubbed -> null user, no username to resolve

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    verify(matrixSynapseService, never()).createUser(any(), any(), any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_ContinueRegistration_When_MatrixUserCreationThrows()
          throws Exception {
    PlainCredentialsHolder.clear();
    givenBasicRegistrationStubs();
    givenAFullyPersistedUser();
    when(matrixSynapseService.createUser(any(), any(), any()))
        .thenThrow(new MatrixCreateUserException("boom"));

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    verify(createNewSessionFacade, times(1))
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_NotSetMatrixUserId_When_MatrixResponseBodyIsNull()
          throws Exception {
    PlainCredentialsHolder.clear();
    givenBasicRegistrationStubs();
    User user = givenAFullyPersistedUser();
    when(matrixSynapseService.createUser(any(), any(), any())).thenReturn(ResponseEntity.ok(null));

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    assertThat(user.getMatrixUserId(), nullValue());
    verify(userService, times(1)).saveUser(any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_PropagateSessionInitializationFailure() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(keycloakService.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenThrow(new RuntimeException("Matrix room initialization failed"));

    var exception =
        assertThrows(
            RuntimeException.class,
            () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));
    assertThat(exception.getMessage(), is("Matrix room initialization failed"));
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_UseDefaultTenantName_When_NoCurrentTenantIsSet() {
    TenantContext.clear();
    givenBasicRegistrationStubs();
    givenAFullyPersistedUser();
    ArgumentCaptor<RegistrationStatisticsEvent> eventCaptor =
        ArgumentCaptor.forClass(RegistrationStatisticsEvent.class);

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    verify(tenantService, never()).getRestrictedTenantData(any(Long.class));
    verify(statisticsService).fireEvent(eventCaptor.capture());
    assertThat(
        eventCaptor.getValue().getPayload().orElse(""),
        containsString("\"tenantName\":\"Default Tenant\""));
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_UseDefaultTenantName_When_TenantServiceReturnsNull() {
    TenantContext.setCurrentTenant(1L);
    try {
      givenBasicRegistrationStubs();
      givenAFullyPersistedUser();
      when(tenantService.getRestrictedTenantData((Long) 1L)).thenReturn(null);
      ArgumentCaptor<RegistrationStatisticsEvent> eventCaptor =
          ArgumentCaptor.forClass(RegistrationStatisticsEvent.class);

      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

      verify(tenantService, times(1)).getRestrictedTenantData((Long) 1L);
      verify(statisticsService).fireEvent(eventCaptor.capture());
      assertThat(
          eventCaptor.getValue().getPayload().orElse(""),
          containsString("\"tenantName\":\"Default Tenant\""));
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void updateIdentityAndCreateAccount_Should_ClearPrivacyConfirmations_When_RoleIsAnonymous() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    doNothing().when(keycloakService).updatePassword(anyString(), anyString());
    User user = new User();
    user.setTermsAndConditionsConfirmation(LocalDateTime.now());
    user.setDataPrivacyConfirmation(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(user);
    when(userService.saveUser(any())).thenReturn(user);

    User result =
        createUserFacade.updateIdentityAndCreateAccount(
            USER_ID, USER_DTO_SUCHT, UserRole.ANONYMOUS);

    assertThat(result.getTermsAndConditionsConfirmation(), nullValue());
    assertThat(result.getDataPrivacyConfirmation(), nullValue());
    verify(userService, times(1)).saveUser(any());
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_ThrowInternalServerError_When_KeycloakFailsForAnonymousUser() {
    doThrow(new RuntimeException("kc down"))
        .when(keycloakService)
        .updateRole(anyString(), any(UserRole.class));

    assertThrows(
        InternalServerErrorException.class,
        () ->
            createUserFacade.updateIdentityAndCreateAccount(
                USER_ID, USER_DTO_SUCHT, UserRole.ANONYMOUS));

    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_ContinueAndCreateDbUser_When_UserIdIsNullAndRoleIsUser() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);

    createUserFacade.updateIdentityAndCreateAccount(null, USER_DTO_SUCHT, UserRole.USER);

    verify(userService, times(1)).createUser(any(), any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_ThrowInternalServerError_When_UserIdIsNullAndRoleIsAnonymous() {
    assertThrows(
        InternalServerErrorException.class,
        () ->
            createUserFacade.updateIdentityAndCreateAccount(
                null, USER_DTO_SUCHT, UserRole.ANONYMOUS));
  }

  @Test
  void updateIdentityAndCreateAccount_Should_CallUpdateDummyEmail_When_EmailIsBlank() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    doNothing().when(keycloakService).updatePassword(anyString(), anyString());
    when(keycloakService.updateDummyEmail(anyString(), any(UserDTO.class)))
        .thenReturn("dummy@example.com");
    UserDTO userDtoWithBlankEmail =
        UserDTO.builder()
            .email("")
            .username(USER_DTO_SUCHT.getUsername())
            .postcode(USER_DTO_SUCHT.getPostcode())
            .consultingType(USER_DTO_SUCHT.getConsultingType())
            .build();

    createUserFacade.updateIdentityAndCreateAccount(USER_ID, userDtoWithBlankEmail, UserRole.USER);

    verify(keycloakService, times(1)).updateDummyEmail(eq(USER_ID), any(UserDTO.class));
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_ClearPrivacyConfirmations_When_PostcodeIsAnonymousPlaceholder() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    doNothing().when(keycloakService).updatePassword(anyString(), anyString());
    User user = new User();
    user.setTermsAndConditionsConfirmation(LocalDateTime.now());
    user.setDataPrivacyConfirmation(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(user);
    when(userService.saveUser(any())).thenReturn(user);
    UserDTO anonymousPostcodeDto =
        UserDTO.builder()
            .email(USER_DTO_SUCHT.getEmail())
            .username("regularUsername")
            .postcode("00000")
            .consultingType(USER_DTO_SUCHT.getConsultingType())
            .build();

    User result =
        createUserFacade.updateIdentityAndCreateAccount(
            USER_ID, anonymousPostcodeDto, UserRole.USER);

    assertThat(result.getTermsAndConditionsConfirmation(), nullValue());
    assertThat(result.getDataPrivacyConfirmation(), nullValue());
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_ClearPrivacyConfirmations_When_UsernameStartsWithAnonymousPrefix() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    doNothing().when(keycloakService).updatePassword(anyString(), anyString());
    User user = new User();
    user.setTermsAndConditionsConfirmation(LocalDateTime.now());
    user.setDataPrivacyConfirmation(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(user);
    when(userService.saveUser(any())).thenReturn(user);
    UserDTO anonymousUsernameDto =
        UserDTO.builder()
            .email(USER_DTO_SUCHT.getEmail())
            .username("Anonymous-1234")
            .postcode(USER_DTO_SUCHT.getPostcode())
            .consultingType(USER_DTO_SUCHT.getConsultingType())
            .build();

    User result =
        createUserFacade.updateIdentityAndCreateAccount(
            USER_ID, anonymousUsernameDto, UserRole.USER);

    assertThat(result.getTermsAndConditionsConfirmation(), nullValue());
    assertThat(result.getDataPrivacyConfirmation(), nullValue());
  }
}
