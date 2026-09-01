package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE;
import static de.caritas.cob.userservice.api.testHelper.KeycloakConstants.CREATED_IDENTITY_WITHOUT_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.KeycloakConstants.CREATED_IDENTITY_WITH_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_SETTINGS_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_SETTINGS_SUCHT;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import de.caritas.cob.userservice.api.helper.AgencyVerifier;
import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import de.caritas.cob.userservice.api.helper.UserVerifier;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.provisioning.ProvisioningCompensator;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.RegistrationStatisticsEvent;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
public class CreateUserFacadeTest {

  @InjectMocks private CreateUserFacade createUserFacade;
  @Mock private IdentityClient identityClient;
  @Mock private IdentityRoleUpdater identityRoleUpdater;
  @Mock private IdentityAccountRemover identityAccountRemover;
  @Mock private IdentityPasswordUpdater identityPasswordUpdater;
  @Mock private IdentityDummyEmailUpdater identityDummyEmailUpdater;
  @Mock private UserService userService;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Mock private AgencyVerifier agencyVerifier;
  @Mock private CreateNewSessionFacade createNewSessionFacade;
  @Mock private UserVerifier userVerifier;
  @Mock private StatisticsService statisticsService;
  @Mock private TopicService topicService;

  @Mock private TenantService tenantService;

  @Mock private AgencyService agencyService;

  @Mock private ApplicationSettingsService applicationSettingsService;

  @Mock private CreateSessionFacade createSessionFacade;

  @Mock private SessionService sessionService;

  @Mock private MatrixSynapseService matrixSynapseService;

  @Spy
  private ProvisioningCompensator provisioningCompensator =
      new ProvisioningCompensator(new SimpleMeterRegistry());

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
      createUserAccountWithInitializedConsultingType_Should_AbortBeforeDependentWrites_When_IdentityProviderReturnsNoUserId() {
    PlainCredentialsHolder.set("plain-user", null);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITHOUT_USER_ID);

    assertThrows(
        IdentityProvisioningException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateIdentity_When_DatabaseUserCreationFails()
          throws Exception {
    PlainCredentialsHolder.set("plain-user", "plain-password");
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any()))
        .thenThrow(new IllegalArgumentException("database write failed"));

    assertThrows(
        IllegalArgumentException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(userService, times(1)).createUser(any(), any(), any(), any(), anyBoolean(), any());
    verify(identityAccountRemover).rollbackUser(USER_ID);
    verify(matrixSynapseService, never()).createUser(any(), any(), any());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_ProvisionMatrixAndInitializeSession_When_ConsultingTypeIsKreuzbundAndNoTenantIsSet()
          throws Exception {
    // Was named "..._Should_LogOutFromRocketChat_When_...RocketChatLoginSucceeded"
    // and asserted nothing at all — it only checked that the call did not throw,
    // for a log-out that no longer exists. It covers the Kreuzbund path without a
    // tenant context (the sibling test below covers it with one), so assert what
    // that path is actually supposed to do.
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    doNothing().when(identityPasswordUpdater).updatePassword(anyString(), anyString());

    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(mock(NewRegistrationResponseDto.class));
    givenAFullyPersistedUser();
    givenMatrixProvisioningSucceeds();

    PlainCredentialsHolder.set("plain-user", "plain-password");
    try {
      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);

      verify(identityClient).createUser(any());
      verify(matrixSynapseService).createUser(any(), any(), any());
      verify(createNewSessionFacade)
          .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
      // The plaintext password must not survive the registration.
      assertThat(PlainCredentialsHolder.get(), nullValue());
    } finally {
      PlainCredentialsHolder.clear();
    }
  }

  @Test
  public void
      createUserAccountWithInitializedConsultingType_Should_CallNecessaryMethods_When_EverythingSucceeds()
          throws Exception {
    TenantContext.setCurrentTenant(1L);
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    doNothing().when(identityPasswordUpdater).updatePassword(anyString(), anyString());

    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(mock(NewRegistrationResponseDto.class));
    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO());
    when(agencyService.getAgencyWithoutCaching(Mockito.anyLong())).thenReturn(new AgencyDTO());
    givenAFullyPersistedUser();
    givenMatrixProvisioningSucceeds();

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);
    TenantContext.clear();
    verify(identityClient, times(1)).createUser(any(UserDTO.class));
    verify(identityRoleUpdater, times(1))
        .assignRoles(eq(USER_ID), eq(List.of(UserRole.USER.getValue())));
    verify(identityPasswordUpdater, times(1)).updatePassword(anyString(), anyString());
    verify(createNewSessionFacade, times(1))
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(statisticsService, times(1)).fireEvent(any());
    verify(matrixSynapseService, never()).deactivateUser(anyString());
    verify(sessionService, never()).deleteSession(any(Session.class));
    verify(userService, never()).deleteUser(any(User.class));
    verify(identityAccountRemover, never()).rollbackUser(anyString());
  }

  @Test
  public void
      updateKeycloakAccountAndCreateDatabaseUserAccount_Should_CallNecessaryMethods_When_EverythingSucceeds() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    doNothing().when(identityPasswordUpdater).updatePassword(anyString(), anyString());

    createUserFacade.updateIdentityAndCreateAccount(USER_ID, USER_DTO_SUCHT, UserRole.USER);

    verify(identityRoleUpdater, times(1))
        .assignRoles(eq(USER_ID), eq(List.of(UserRole.USER.getValue())));
    verify(identityPasswordUpdater, times(1)).updatePassword(anyString(), anyString());
  }

  @Test
  public void
      updateIdentityAndCreateAccount_Should_AbortBeforeDatabaseWrite_When_PasswordUpdateFails() {
    RuntimeException identityFailure = new RuntimeException("password update failed");
    doThrow(identityFailure).when(identityPasswordUpdater).updatePassword(anyString(), anyString());

    RuntimeException propagated =
        assertThrows(
            RuntimeException.class,
            () ->
                createUserFacade.updateIdentityAndCreateAccount(
                    USER_ID, USER_DTO_SUCHT, UserRole.USER));

    assertThat(propagated, is(identityFailure));
    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any());
  }

  @Test
  public void
      updateIdentityAndCreateAccount_Should_AbortBeforeDatabaseWrite_When_RoleUpdateFails() {
    RuntimeException identityFailure = new RuntimeException("role update failed");
    doThrow(identityFailure)
        .when(identityRoleUpdater)
        .assignRoles(anyString(), eq(List.of(UserRole.USER.getValue())));

    RuntimeException propagated =
        assertThrows(
            RuntimeException.class,
            () ->
                createUserFacade.updateIdentityAndCreateAccount(
                    USER_ID, USER_DTO_SUCHT, UserRole.USER));

    assertThat(propagated, is(identityFailure));
    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateIdentity_When_RoleUpdateFails() {
    PlainCredentialsHolder.set("plain-user", "plain-password");
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    RuntimeException identityFailure = new RuntimeException("role update failed");
    doThrow(identityFailure)
        .when(identityRoleUpdater)
        .assignRoles(anyString(), eq(List.of(UserRole.USER.getValue())));

    RuntimeException propagated =
        assertThrows(
            RuntimeException.class,
            () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    assertThat(propagated, is(identityFailure));
    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any());
    verify(identityAccountRemover).rollbackUser(USER_ID);
    assertThat(PlainCredentialsHolder.get(), nullValue());
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
          doNothing().when(identityPasswordUpdater).updatePassword(anyString(), anyString());
          when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any()))
              .thenThrow(new IllegalArgumentException());

          createUserFacade.updateIdentityAndCreateAccount(USER_ID, USER_DTO_SUCHT, UserRole.USER);
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

  private void givenBasicRegistrationStubs() throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(mock(NewRegistrationResponseDto.class));
    when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());
    givenMatrixProvisioningSucceeds();
  }

  private void givenMatrixProvisioningSucceeds() throws Exception {
    var matrixResponseBody = new MatrixCreateUserResponseDTO();
    matrixResponseBody.setUserId("@registered:matrix.oriso.org");
    lenient()
        .when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(matrixResponseBody));
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
      createUserAccountWithInitializedConsultingType_Should_CompensateAndAbort_When_PlainUsernameNotResolvable()
          throws Exception {
    PlainCredentialsHolder.clear();
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());
    // userService.createUser/saveUser left unstubbed -> null user, no username to resolve

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(matrixSynapseService, never()).createUser(any(), any(), any());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(identityAccountRemover).rollbackUser(USER_ID);
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateAndAbort_When_MatrixUserCreationThrows()
          throws Exception {
    PlainCredentialsHolder.clear();
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());
    User user = givenAFullyPersistedUser();
    when(matrixSynapseService.createUser(any(), any(), any()))
        .thenThrow(new MatrixCreateUserException("boom"));

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(userService).deleteUser(user);
    verify(identityAccountRemover).rollbackUser(USER_ID);
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateAndAbort_When_MatrixResponseBodyIsNull()
          throws Exception {
    PlainCredentialsHolder.clear();
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());
    User user = givenAFullyPersistedUser();
    when(matrixSynapseService.createUser(any(), any(), any())).thenReturn(ResponseEntity.ok(null));

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    assertThat(user.getMatrixUserId(), nullValue());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(userService).deleteUser(user);
    verify(identityAccountRemover).rollbackUser(USER_ID);
    verify(userService, times(1)).saveUser(any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_Compensate_When_InitializeNewSessionThrows()
          throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenThrow(new RuntimeException("Matrix room initialization failed"));
    User user = givenAFullyPersistedUser();
    givenMatrixProvisioningSucceeds();

    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    assertThat(exception.getMessage(), is("Matrix room initialization failed"));
    verify(userService).deleteUser(user);
    verify(identityAccountRemover).rollbackUser(USER_ID);
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateAllCreatedResources_When_AllSessionPathsFail()
          throws Exception {
    PlainCredentialsHolder.set("plainuser", "plainpw");
    User user =
        new User(USER_ID, null, USER_DTO_SUCHT.getUsername(), USER_DTO_SUCHT.getEmail(), false);
    Session partialSession = new Session();
    partialSession.setId(42L);
    var matrixResponse = new MatrixCreateUserResponseDTO();
    matrixResponse.setUserId("@plainuser:matrix.oriso.org");
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_SUCHT);
    when(identityClient.createUser(any())).thenReturn(CREATED_IDENTITY_WITH_USER_ID);
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(user);
    when(userService.saveUser(any(User.class))).thenReturn(user);
    when(matrixSynapseService.createUser(eq("plainuser"), anyString(), eq("plainuser")))
        .thenReturn(ResponseEntity.ok(matrixResponse));
    when(matrixSynapseService.deactivateUser("@plainuser:matrix.oriso.org")).thenReturn(true);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenThrow(new InternalServerErrorException("session initialization failed"));
    when(sessionService.getSessionsForUser(user)).thenReturn(List.of(partialSession));

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    var compensationOrder =
        inOrder(matrixSynapseService, sessionService, userService, identityAccountRemover);
    compensationOrder.verify(sessionService).deleteSession(partialSession);
    compensationOrder.verify(matrixSynapseService).deactivateUser("@plainuser:matrix.oriso.org");
    compensationOrder.verify(userService).deleteUser(user);
    compensationOrder.verify(identityAccountRemover).rollbackUser(USER_ID);
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_SucceedOnReplayAfterFirstAttemptWasCompensated()
          throws Exception {
    User firstUser = new User("first-id", null, "username", USER_DTO_SUCHT.getEmail(), false);
    User replayUser = new User("replay-id", null, "username", USER_DTO_SUCHT.getEmail(), false);
    Session partialSession = new Session();
    partialSession.setId(42L);
    var firstIdentity =
        new de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity("first-id");
    var replayIdentity =
        new de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity("replay-id");
    var firstMatrixResponse = new MatrixCreateUserResponseDTO();
    firstMatrixResponse.setUserId("@first:matrix.oriso.org");
    var replayMatrixResponse = new MatrixCreateUserResponseDTO();
    replayMatrixResponse.setUserId("@replay:matrix.oriso.org");
    var replayRegistration =
        new NewRegistrationResponseDto().sessionId(99L).status(HttpStatus.CREATED);

    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_SUCHT);
    when(identityClient.createUser(any())).thenReturn(firstIdentity, replayIdentity);
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any()))
        .thenReturn(firstUser, replayUser);
    when(userService.saveUser(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(
            ResponseEntity.ok(firstMatrixResponse), ResponseEntity.ok(replayMatrixResponse));
    when(matrixSynapseService.deactivateUser("@first:matrix.oriso.org")).thenReturn(true);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenThrow(new InternalServerErrorException("first attempt failed"))
        .thenReturn(replayRegistration);
    when(sessionService.getSessionsForUser(firstUser)).thenReturn(List.of(partialSession));

    PlainCredentialsHolder.set("first", "password");
    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));
    PlainCredentialsHolder.set("replay", "password");
    Long replaySessionId =
        createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    assertThat(replaySessionId, is(99L));
    verify(sessionService).deleteSession(partialSession);
    verify(matrixSynapseService).deactivateUser("@first:matrix.oriso.org");
    verify(matrixSynapseService, never()).deactivateUser("@replay:matrix.oriso.org");
    verify(userService).deleteUser(firstUser);
    verify(userService, never()).deleteUser(replayUser);
    verify(identityAccountRemover).rollbackUser("first-id");
    verify(identityAccountRemover, never()).rollbackUser("replay-id");
    verify(identityClient, times(2)).createUser(any(UserDTO.class));
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_UseDefaultTenantName_When_NoCurrentTenantIsSet()
          throws Exception {
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
      createUserAccountWithInitializedConsultingType_Should_UseDefaultTenantName_When_TenantServiceReturnsNull()
          throws Exception {
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
    doNothing().when(identityPasswordUpdater).updatePassword(anyString(), anyString());
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
        .when(identityRoleUpdater)
        .assignRoles(anyString(), eq(List.of(UserRole.ANONYMOUS.getValue())));

    assertThrows(
        InternalServerErrorException.class,
        () ->
            createUserFacade.updateIdentityAndCreateAccount(
                USER_ID, USER_DTO_SUCHT, UserRole.ANONYMOUS));

    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_AbortBeforeDatabaseWrite_When_UserIdIsNullAndRoleIsUser() {

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.updateIdentityAndCreateAccount(null, USER_DTO_SUCHT, UserRole.USER));

    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any());
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
    doNothing()
        .when(identityRoleUpdater)
        .assignRoles(anyString(), eq(List.of(UserRole.USER.getValue())));
    doNothing()
        .when(identityPasswordUpdater)
        .updatePassword(anyString(), org.mockito.ArgumentMatchers.nullable(String.class));
    when(identityDummyEmailUpdater.updateDummyEmail(
            anyString(), any(IdentityDummyEmailUpdate.class)))
        .thenReturn("dummy@example.com");
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any()))
        .thenReturn(new User());
    UserDTO userDtoWithBlankEmail =
        UserDTO.builder()
            .email("")
            .username(USER_DTO_SUCHT.getUsername())
            .postcode(USER_DTO_SUCHT.getPostcode())
            .consultingType(USER_DTO_SUCHT.getConsultingType())
            .build();

    createUserFacade.updateIdentityAndCreateAccount(USER_ID, userDtoWithBlankEmail, UserRole.USER);

    verify(identityDummyEmailUpdater, times(1))
        .updateDummyEmail(eq(USER_ID), any(IdentityDummyEmailUpdate.class));
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_ClearPrivacyConfirmations_When_PostcodeIsAnonymousPlaceholder() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
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
