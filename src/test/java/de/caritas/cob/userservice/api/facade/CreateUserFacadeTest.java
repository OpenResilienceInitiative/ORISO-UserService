package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE;
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
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.email.WelcomeEmailService;
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
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
public class CreateUserFacadeTest {
  private final de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy
      inactivityPolicy =
          new de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy(
              24, 7, java.time.Instant.parse("2026-10-06T00:00:00Z"));

  @org.mockito.Mock
  private de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService
      inactivityEnrollment;

  @org.mockito.Mock private ChatRecoveryEnrollmentPolicyService chatRecoveryEnrollmentPolicyService;

  @org.junit.jupiter.api.BeforeEach
  void recoveryPolicyFixture() {
    org.mockito.Mockito.lenient()
        .when(agencyVerifier.getVerifiedAgency(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(
            call -> new AgencyDTO().id(call.getArgument(0)).consultingType(call.getArgument(1)));
    org.mockito.Mockito.lenient()
        .when(userHelper.getDummyEmail(anyString()))
        .thenAnswer(call -> call.getArgument(0) + "@beratungcaritas.de");
    createUserFacade =
        new CreateUserFacade(
            chatRecoveryEnrollmentPolicyService,
            inactivityEnrollment,
            de.caritas.cob.userservice.api.testHelper.PermittingDpaOwnerFixture.policy(),
            userVerifier,
            localCompletion,
            identityProvisioning,
            userHelper,
            userService,
            consultingTypeManager,
            agencyVerifier,
            createNewSessionFacade,
            statisticsService,
            topicService,
            welcomeEmailService,
            matrixSynapseService,
            sessionService,
            provisioningCompensator,
            tenantService,
            agencyService,
            applicationSettingsService,
            groupInviteRegistration);
    org.mockito.Mockito.lenient()
        .when(consultingTypeManager.getConsultingTypeSettings(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ExtendedConsultingTypeResponseDTO());
    org.mockito.Mockito.lenient()
        .when(chatRecoveryEnrollmentPolicyService.forNewAsker(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));
    org.mockito.Mockito.lenient()
        .when(
            chatRecoveryEnrollmentPolicyService.forNewConsultant(
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));
    org.mockito.Mockito.lenient()
        .when(
            chatRecoveryEnrollmentPolicyService.forExistingIdentity(
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(new RecoveryPolicySnapshot("RECOVERY_KEY", 0));
    org.mockito.Mockito.lenient()
        .when(
            inactivityEnrollment.capture(
                org.mockito.ArgumentMatchers.nullable(Long.class),
                org.mockito.ArgumentMatchers.any(
                    de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Group
                        .class)))
        .thenReturn(inactivityPolicy);
  }

  private CreateUserFacade createUserFacade;

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCreationLocalCompletion
      localCompletion;

  @Mock
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityAccountProvisioning
      identityProvisioning;

  @Mock private de.caritas.cob.userservice.api.helper.UserHelper userHelper;
  @Mock private IdentityClient identityClient;
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
  @Mock private WelcomeEmailService welcomeEmailService;
  @Mock private GroupInviteRegistration groupInviteRegistration;

  @Spy
  private ProvisioningCompensator provisioningCompensator =
      new ProvisioningCompensator(new SimpleMeterRegistry());

  @Test
  void unavailableRecoveryPolicyStopsBeforeProvisioning() {
    org.mockito.Mockito.doThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
        .when(chatRecoveryEnrollmentPolicyService)
        .forNewAsker(any());
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));
    org.mockito.Mockito.verifyNoInteractions(identityClient, matrixSynapseService, userService);
  }

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
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(null));

    assertThrows(
        IdentityProvisioningException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateIdentity_When_DatabaseUserCreationFails()
          throws Exception {
    PlainCredentialsHolder.set("plain-user", "plain-password");
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenThrow(new IllegalArgumentException("database write failed"));

    assertThrows(
        IllegalArgumentException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    org.mockito.ArgumentCaptor<RecoveryPolicySnapshot> snapshotCaptor =
        org.mockito.ArgumentCaptor.forClass(RecoveryPolicySnapshot.class);
    verify(userService, times(1))
        .createUser(any(), any(), any(), any(), anyBoolean(), any(), snapshotCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3), snapshotCaptor.getValue());
    var failedCreationOrder = org.mockito.Mockito.inOrder(identityProvisioning, userService);
    failedCreationOrder.verify(identityProvisioning).prepareLocalRollback(USER_ID);
    failedCreationOrder
        .verify(identityProvisioning)
        .compensate(
            org.mockito.ArgumentMatchers.argThat(
                r -> java.util.Objects.equals(r.accountId(), USER_ID)),
            any());
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
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));

    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(mock(NewRegistrationResponseDto.class));
    givenAFullyPersistedUser();
    givenMatrixProvisioningSucceeds();

    PlainCredentialsHolder.set("plain-user", "plain-password");
    try {
      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_KREUZBUND);

      verify(identityProvisioning).create(any(), any(), any());
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
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));

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
    verify(identityProvisioning, times(1)).create(any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(identityClient, identityPasswordUpdater);
    verify(createNewSessionFacade, times(1))
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(statisticsService, times(1)).fireEvent(any());
    verify(matrixSynapseService, never()).deactivateUser(anyString());
    verify(sessionService, never()).deleteSession(any(Session.class));
    verify(userService, never()).deleteUser(any(User.class));
    verify(identityProvisioning, never()).compensate(any(), any());
    verify(welcomeEmailService, times(1)).sendWelcomeEmail(any(User.class), anyString());
  }

  @Test
  public void
      updateKeycloakAccountAndCreateDatabaseUserAccount_Should_CallNecessaryMethods_When_EverythingSucceeds() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);

    createUserFacade.updateIdentityAndCreateAccount(USER_ID, USER_DTO_SUCHT, UserRole.USER);

    org.mockito.Mockito.verifyNoInteractions(identityClient, identityPasswordUpdater);
  }

  @Test
  void localAccountBuilderDoesNotObtainPasswordMaintenance() {
    createUserFacade.updateIdentityAndCreateAccount(USER_ID, USER_DTO_SUCHT, UserRole.USER);
    org.mockito.Mockito.verifyNoInteractions(identityClient, identityPasswordUpdater);
    verify(userService).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
  }

  @Test
  void localAccountBuilderDoesNotObtainRoleMaintenance() {
    createUserFacade.updateIdentityAndCreateAccount(USER_ID, USER_DTO_SUCHT, UserRole.USER);
    org.mockito.Mockito.verifyNoInteractions(identityClient, identityPasswordUpdater);
    verify(userService).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_AbortBeforeLocalWrites_When_AtomicIdentityCommandRejectsRoles() {
    PlainCredentialsHolder.set("plain-user", "plain-password");
    RuntimeException identityFailure = new RuntimeException("role update failed");
    doThrow(identityFailure).when(identityProvisioning).create(any(), any(), any());

    RuntimeException propagated =
        assertThrows(
            RuntimeException.class,
            () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    assertThat(propagated, is(identityFailure));
    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
    verify(identityProvisioning, never()).compensate(any(), any());
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
          when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
              .thenThrow(new IllegalArgumentException());

          createUserFacade.updateIdentityAndCreateAccount(USER_ID, USER_DTO_SUCHT, UserRole.USER);
        });
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-06
  // ---------------------------------------------------------------------------

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_LeaveTheGroupBeforeDeletingTheUser_When_GroupJoinFails()
          throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));
    givenMatrixProvisioningSucceeds();
    User user = givenAFullyPersistedUser();
    Chat group =
        Chat.builder()
            .id(4711L)
            .topic("group")
            .initialStartDate(LocalDateTime.now())
            .startDate(LocalDateTime.now())
            .conversationType(ConversationType.SELF_HELP)
            .build();
    when(groupInviteRegistration.resolveInvitedGroup(any())).thenReturn(Optional.of(group));
    // The membership row may exist although the call failed, so compensation must still leave.
    RuntimeException joinFailure = new RuntimeException("membership write failed");
    doThrow(joinFailure).when(groupInviteRegistration).join(group, user);

    RuntimeException propagated =
        assertThrows(
            RuntimeException.class,
            () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    assertThat(propagated, is(joinFailure));
    // The membership references the user row, and the user row references the identity.
    var compensation = inOrder(groupInviteRegistration, userService, identityProvisioning);
    compensation.verify(groupInviteRegistration).leave(group, user);
    compensation.verify(userService).deleteUser(user);
    compensation
        .verify(identityProvisioning)
        .compensate(
            org.mockito.ArgumentMatchers.argThat(
                r -> java.util.Objects.equals(r.accountId(), USER_ID)),
            any());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_JoinTheGroupWithoutARegistrationEvent_When_InvitedToAGroup()
          throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));
    givenMatrixProvisioningSucceeds();
    User user = givenAFullyPersistedUser();
    Chat group =
        Chat.builder()
            .id(4711L)
            .topic("group")
            .initialStartDate(LocalDateTime.now())
            .startDate(LocalDateTime.now())
            .conversationType(ConversationType.SELF_HELP)
            .build();
    when(groupInviteRegistration.resolveInvitedGroup(any())).thenReturn(Optional.of(group));
    // Without it the event would fail to build and be swallowed, hiding a regression.
    when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());

    Long sessionId =
        createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    assertThat(sessionId, nullValue());
    verify(groupInviteRegistration).join(group, user);
    // The registration event contract requires a session id, which a group join does not have.
    verify(statisticsService, never()).fireEvent(any());
    verify(groupInviteRegistration, never()).leave(any(), any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_RejectATemporaryAccount_When_NoGroupInviteBacksIt() {
    // The deletion job removes temporary accounts, so only the invite flow may ask for one.
    USER_DTO_SUCHT.setTemporary(true);
    try {
      assertThrows(
          BadRequestException.class,
          () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));
    } finally {
      USER_DTO_SUCHT.setTemporary(false);
    }

    verify(identityProvisioning, never()).create(any(), any(), any());
    verify(userService, never()).saveUser(any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_StoreATemporaryAccount_When_RegisteringThroughAGroupInvite()
          throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));
    givenMatrixProvisioningSucceeds();
    User user = givenAFullyPersistedUser();
    Chat group =
        Chat.builder()
            .id(4711L)
            .topic("group")
            .initialStartDate(LocalDateTime.now())
            .startDate(LocalDateTime.now())
            .conversationType(ConversationType.SELF_HELP)
            .build();
    when(groupInviteRegistration.resolveInvitedGroup(any())).thenReturn(Optional.of(group));

    USER_DTO_SUCHT.setTemporary(true);
    try {
      createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);
    } finally {
      USER_DTO_SUCHT.setTemporary(false);
    }

    assertThat(user.isTemporaryAccount(), is(true));
    verify(groupInviteRegistration).join(group, user);
  }

  private static de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
          .CreationResult
      receipt(String id) {
    return new de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
        .CreationResult(java.util.UUID.randomUUID(), id, "captured-own-proof", "OPEN");
  }

  private User givenAFullyPersistedUser() {
    User user = new User();
    user.setUsername("dbUser");
    user.setTenantId(1L);
    user.setCreateDate(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(user);
    when(userService.saveUser(any())).thenReturn(user);
    return user;
  }

  private void givenBasicRegistrationStubs() throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenReturn(mock(NewRegistrationResponseDto.class));
    when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());
    givenMatrixProvisioningSucceeds();
  }

  private void givenMatrixProvisioningSucceeds() throws Exception {
    var matrixResponseBody = new MatrixCreateUserResponseDTO();
    matrixResponseBody.setUserId("@registered:matrix.example.org");
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
    matrixResponseBody.setUserId("@plainuser:matrix.example.org");
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
    assertThat(user.getMatrixUserId(), is("@plainuser:matrix.example.org"));
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
    matrixResponseBody.setUserId("@dbUser:matrix.example.org");
    when(matrixSynapseService.createUser(eq("dbUser"), anyString(), eq("dbUser")))
        .thenReturn(ResponseEntity.ok(matrixResponseBody));

    createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT);

    verify(matrixSynapseService, times(1)).createUser(eq("dbUser"), anyString(), eq("dbUser"));
    assertThat(user.getMatrixUserId(), is("@dbUser:matrix.example.org"));
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateAndAbort_When_PlainUsernameNotResolvable()
          throws Exception {
    PlainCredentialsHolder.clear();
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));
    when(agencyService.getAgencyWithoutCaching(any())).thenReturn(new AgencyDTO());
    // userService.createUser/saveUser left unstubbed -> null user, no username to resolve

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(matrixSynapseService, never()).createUser(any(), any(), any());
    verify(createNewSessionFacade, never())
        .initializeNewSession(any(), any(), any(ExtendedConsultingTypeResponseDTO.class));
    verify(identityProvisioning)
        .compensate(
            org.mockito.ArgumentMatchers.argThat(
                r -> java.util.Objects.equals(r.accountId(), USER_ID)),
            any());
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateAndAbort_When_MatrixUserCreationThrows()
          throws Exception {
    PlainCredentialsHolder.clear();
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));
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
    verify(identityProvisioning)
        .compensate(
            org.mockito.ArgumentMatchers.argThat(
                r -> java.util.Objects.equals(r.accountId(), USER_ID)),
            any());
    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_CompensateAndAbort_When_MatrixResponseBodyIsNull()
          throws Exception {
    PlainCredentialsHolder.clear();
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));
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
    verify(identityProvisioning)
        .compensate(
            org.mockito.ArgumentMatchers.argThat(
                r -> java.util.Objects.equals(r.accountId(), USER_ID)),
            any());
    verify(userService, times(1)).saveUser(any());
  }

  @Test
  void
      createUserAccountWithInitializedConsultingType_Should_Compensate_When_InitializeNewSessionThrows()
          throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));
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
    verify(identityProvisioning)
        .compensate(
            org.mockito.ArgumentMatchers.argThat(
                r -> java.util.Objects.equals(r.accountId(), USER_ID)),
            any());
  }

  @Test
  void failedDatabaseDeletionStillDiscardsTheUncompletedLifecycle() throws Exception {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenThrow(new RuntimeException("Matrix room initialization failed"));
    User user = givenAFullyPersistedUser();
    givenMatrixProvisioningSucceeds();
    doThrow(new IllegalStateException("database unavailable")).when(userService).deleteUser(user);

    assertThrows(
        RuntimeException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    verify(inactivityEnrollment).discardUncompletedCreation(USER_ID, inactivityPolicy);
    verify(identityProvisioning)
        .compensate(
            org.mockito.ArgumentMatchers.argThat(
                r -> java.util.Objects.equals(r.accountId(), USER_ID)),
            any());
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
    matrixResponse.setUserId("@plainuser:matrix.example.org");
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_SUCHT);
    when(identityProvisioning.create(any(), any(), any())).thenReturn(receipt(USER_ID));
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(user);
    when(userService.saveUser(any(User.class))).thenReturn(user);
    when(matrixSynapseService.createUser(eq("plainuser"), anyString(), eq("plainuser")))
        .thenReturn(ResponseEntity.ok(matrixResponse));
    when(matrixSynapseService.deactivateUser("@plainuser:matrix.example.org")).thenReturn(true);
    when(createNewSessionFacade.initializeNewSession(
            any(), any(), any(ExtendedConsultingTypeResponseDTO.class)))
        .thenThrow(new InternalServerErrorException("session initialization failed"));
    when(sessionService.getSessionsForUser(user)).thenReturn(List.of(partialSession));

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.createUserAccountWithInitializedConsultingType(USER_DTO_SUCHT));

    var compensationOrder =
        inOrder(matrixSynapseService, sessionService, userService, identityProvisioning);
    compensationOrder.verify(sessionService).deleteSession(partialSession);
    compensationOrder.verify(matrixSynapseService).deactivateUser("@plainuser:matrix.example.org");
    compensationOrder.verify(userService).deleteUser(user);
    compensationOrder
        .verify(identityProvisioning)
        .compensate(
            org.mockito.ArgumentMatchers.argThat(
                r -> java.util.Objects.equals(r.accountId(), USER_ID)),
            any());
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
    firstMatrixResponse.setUserId("@first:matrix.example.org");
    var replayMatrixResponse = new MatrixCreateUserResponseDTO();
    replayMatrixResponse.setUserId("@replay:matrix.example.org");
    var replayRegistration =
        new NewRegistrationResponseDto().sessionId(99L).status(HttpStatus.CREATED);

    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_SUCHT);
    when(identityProvisioning.create(any(), any(), any()))
        .thenReturn(receipt("first-id"), receipt("replay-id"));
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(firstUser, replayUser);
    when(userService.saveUser(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(
            ResponseEntity.ok(firstMatrixResponse), ResponseEntity.ok(replayMatrixResponse));
    when(matrixSynapseService.deactivateUser("@first:matrix.example.org")).thenReturn(true);
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
    verify(matrixSynapseService).deactivateUser("@first:matrix.example.org");
    verify(matrixSynapseService, never()).deactivateUser("@replay:matrix.example.org");
    verify(userService).deleteUser(firstUser);
    verify(userService, never()).deleteUser(replayUser);
    verify(identityProvisioning)
        .compensate(
            org.mockito.ArgumentMatchers.argThat(
                r -> java.util.Objects.equals(r.accountId(), "first-id")),
            any());
    verify(identityProvisioning, never())
        .compensate(
            org.mockito.ArgumentMatchers.argThat(
                r -> java.util.Objects.equals(r.accountId(), "replay-id")),
            any());
    verify(identityProvisioning, times(2)).create(any(), any(), any());
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
    User user = new User();
    user.setTermsAndConditionsConfirmation(LocalDateTime.now());
    user.setDataPrivacyConfirmation(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(user);
    when(userService.saveUser(any())).thenReturn(user);

    User result =
        createUserFacade.updateIdentityAndCreateAccount(
            USER_ID, USER_DTO_SUCHT, UserRole.ANONYMOUS);

    assertThat(result.getTermsAndConditionsConfirmation(), nullValue());
    assertThat(result.getDataPrivacyConfirmation(), nullValue());
    verify(userService, times(1)).saveUser(any());
  }

  @Test
  void anonymousLocalAccountBuilderDoesNotObtainNativeAdminMaintenance() {
    createUserFacade.updateIdentityAndCreateAccount(USER_ID, USER_DTO_SUCHT, UserRole.USER);
    org.mockito.Mockito.verifyNoInteractions(identityClient, identityPasswordUpdater);
    verify(userService).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_AbortBeforeDatabaseWrite_When_UserIdIsNullAndRoleIsUser() {

    assertThrows(
        InternalServerErrorException.class,
        () -> createUserFacade.updateIdentityAndCreateAccount(null, USER_DTO_SUCHT, UserRole.USER));

    verify(userService, never()).createUser(any(), any(), any(), any(), anyBoolean(), any(), any());
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
  void localAccountUsesTheSameDerivedDummyEmailAsAtomicProviderCreation() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(new User());
    var request =
        UserDTO.builder()
            .email("")
            .username(USER_DTO_SUCHT.getUsername())
            .postcode(USER_DTO_SUCHT.getPostcode())
            .consultingType(USER_DTO_SUCHT.getConsultingType())
            .build();
    createUserFacade.updateIdentityAndCreateAccount(USER_ID, request, UserRole.USER);
    verify(userService)
        .createUser(
            eq(USER_ID),
            any(),
            any(),
            eq(USER_ID + "@beratungcaritas.de"),
            anyBoolean(),
            any(),
            any());
    org.mockito.Mockito.verifyNoInteractions(
        identityDummyEmailUpdater, identityPasswordUpdater, identityClient);
  }

  @Test
  void
      updateIdentityAndCreateAccount_Should_ClearPrivacyConfirmations_When_PostcodeIsAnonymousPlaceholder() {
    when(consultingTypeManager.getConsultingTypeSettings(any()))
        .thenReturn(CONSULTING_TYPE_SETTINGS_KREUZBUND);
    User user = new User();
    user.setTermsAndConditionsConfirmation(LocalDateTime.now());
    user.setDataPrivacyConfirmation(LocalDateTime.now());
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(user);
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
    verify(chatRecoveryEnrollmentPolicyService).forNewAsker(org.mockito.ArgumentMatchers.any());
    verify(userService)
        .createUser(
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            eq(new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3)));

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
    when(userService.createUser(any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(user);
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

    verify(chatRecoveryEnrollmentPolicyService).forNewAsker(org.mockito.ArgumentMatchers.any());
    verify(userService)
        .createUser(
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            eq(new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3)));

    assertThat(result.getTermsAndConditionsConfirmation(), nullValue());
    assertThat(result.getDataPrivacyConfirmation(), nullValue());
  }
}
