package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static de.caritas.cob.userservice.api.config.auth.UserRole.CONSULTANT;
import static de.caritas.cob.userservice.api.config.auth.UserRole.GROUP_CHAT_CONSULTANT;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.NUMBER_OF_LICENSES_EXCEEDED;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.PASSWORD_NOT_VALID;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.TENANT_LICENSING_NOT_CONFIGURED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.MatrixRealNameGuard;
import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot;
import de.caritas.cob.userservice.api.service.ConsultantImportService.ImportRecord;
import de.caritas.cob.userservice.api.service.ConsultantPublicSlugService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.Licensing;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.hibernate.validator.internal.util.CollectionHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CreateConsultantSagaTest {
  @Mock
  private de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService
      inactivityEnrollment;

  @org.mockito.Mock private ChatRecoveryEnrollmentPolicyService chatRecoveryEnrollmentPolicyService;

  @org.junit.jupiter.api.BeforeEach
  void recoveryPolicyFixture() {
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
  }

  private static final String KEYCLOAK_USER_ID = "keycloak-user-id";
  private static final String VALID_USERNAME = "validUsername";
  private static final String VALID_EMAIL = "valid@emailaddress.de";
  private static final String VALID_PASSWORD = "ValidPass1!";

  @InjectMocks private CreateConsultantSaga createConsultantSaga;

  @Mock private IdentityClient identityClient;
  @Mock private IdentityPasswordUpdater identityPasswordUpdater;
  @Mock private ConsultantPublicSlugService consultantPublicSlugService;
  @Mock private ConsultantService consultantService;
  @Mock private UserHelper userHelper;

  @Mock
  private de.caritas.cob.userservice.api.admin.service.consultant.validation
          .UserAccountInputValidator
      userAccountInputValidator;

  @Mock private TenantAdminService tenantAdminService;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;

  @Mock
  private ConsultantTopicAgencyCompatibilityValidator consultantTopicAgencyCompatibilityValidator;

  @Mock private RollbackFacade rollbackFacade;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private AppointmentService appointmentService;

  @Mock
  private de.caritas.cob.userservice.api.service.accountpolicy.CheckedAccountPolicyReads
      checkedPolicyReads;

  // The resolver is the single place that decides which name may reach Matrix (ADR-002 §2), so the
  // saga tests exercise the real rule instead of a mock that would answer null.
  @Spy
  private ConsultantDisplayNameResolver consultantDisplayNameResolver =
      new ConsultantDisplayNameResolver();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(createConsultantSaga, "appointmentFeatureEnabled", false);
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", false);
    PlainCredentialsHolder.clear();
    TenantContext.clear();
  }

  @AfterEach
  void tearDown() {
    PlainCredentialsHolder.clear();
    TenantContext.clear();
  }

  @Test
  void fiveActorConsultantCreationNeverFallsBackToNativeIdentityWhenLifecycleIsAbsent() {
    org.springframework.mock.env.MockEnvironment environment =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS");
    ReflectionTestUtils.setField(createConsultantSaga, "lifecycleEnvironment", environment);
    try {
      createConsultantSaga.createNewConsultant(validCreateConsultantDto());
    } catch (RuntimeException expectedUnavailable) {
      /* observe native effect independently */
    }
    verify(identityClient, never()).createUser(any(), anyString(), anyString());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void checkedImportQuotaAndOutageRefuseBeforeCreationWithoutFullTenantFallback(
      boolean unavailable) {
    var workflow =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation
                .class);
    var provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(workflow);
    ReflectionTestUtils.setField(createConsultantSaga, "ordinaryAccountCreation", provider);
    ReflectionTestUtils.setField(
        createConsultantSaga,
        "lifecycleEnvironment",
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS"));
    ReflectionTestUtils.setField(createConsultantSaga, "checkedPolicyReads", checkedPolicyReads);
    var row = new ImportRecord();
    row.setTenantId(7L);
    if (unavailable) {
      when(checkedPolicyReads.read(7))
          .thenThrow(new IllegalStateException("Synthetic unavailable projection"));
    } else {
      when(checkedPolicyReads.read(7))
          .thenReturn(
              new de.caritas.cob.userservice.api.service.accountpolicy.CheckedAccountPolicyReads
                  .Policy(7L, 1));
      when(consultantService.getNumberOfActiveConsultants(7L)).thenReturn(1L);
    }
    var failure =
        assertThrows(
            CustomValidationHttpStatusException.class,
            () ->
                createConsultantSaga.createImportedConsultant(
                    row, Set.of("consultant"), Set.of(10L), "a".repeat(64), 1, ignored -> {}));
    assertThat(
        failure.getCustomHttpHeaders().get("X-Reason").get(0),
        is((unavailable ? TENANT_LICENSING_NOT_CONFIGURED : NUMBER_OF_LICENSES_EXCEEDED).name()));
    verify(workflow, never()).create(any(), any(), any());
    verifyNoInteractions(tenantAdminService, identityClient, identityPasswordUpdater);
  }

  @Test
  void fiveActorConsultantCreationUsesCheckedPendingWorkflowAndKeepsNativeCreationUntouched() {
    var workflow =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation
                .class);
    var provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(workflow);
    ReflectionTestUtils.setField(
        createConsultantSaga,
        "lifecycleEnvironment",
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS"));
    ReflectionTestUtils.setField(createConsultantSaga, "ordinaryAccountCreation", provider);
    ReflectionTestUtils.setField(
        createConsultantSaga,
        "lifecycleAdminScope",
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class));
    var dto = validCreateConsultantDto();
    dto.setTenantId(7L);
    when(workflow.resolveSingleTenant(eq(7L), any())).thenReturn(7L);
    var scope =
        new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos
            .CreationScope(
            "7",
            "CONSULTANT",
            "/human-roles/consultant",
            new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos
                .Origin(
                "HUMAN_ADMIN",
                "human:one",
                "human:verified",
                "https://issuer.example/realms/oriso",
                "human-subject",
                "user-admin",
                List.of()));
    when(workflow.humanScope(any(), eq(7L), any(), eq("CONSULTANT"), eq(Set.of("consultant"))))
        .thenReturn(scope);
    var pending =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation
                .Pending.class);
    when(pending.accountId()).thenReturn(KEYCLOAK_USER_ID);
    when(workflow.create(eq(scope), any(), any()))
        .thenAnswer(
            invocation -> {
              java.util.function.Function<
                      de.caritas.cob.userservice.api.adapters.keycloak.lifecycle
                          .OrdinaryAccountCreation.Pending,
                      Consultant>
                  writes = invocation.getArgument(2);
              return writes.apply(pending);
            });
    when(workflow.createChatAccount(eq(pending), eq(VALID_USERNAME), any()))
        .thenReturn("@validusername:example.test");
    when(consultantService.saveConsultant(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    org.mockito.Mockito.lenient()
        .when(identityClient.createUser(any(), anyString(), anyString()))
        .thenReturn(new CreatedIdentity(KEYCLOAK_USER_ID));
    var result = createConsultantSaga.createNewConsultant(dto);
    assertThat(result.getEmbedded().getId(), is(KEYCLOAK_USER_ID));
    var account =
        ArgumentCaptor.forClass(
            de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos
                .AccountCreation.class);
    verify(workflow).create(eq(scope), account.capture(), any());
    assertThat(account.getValue().password(), is(VALID_PASSWORD));
    assertThat(account.getValue().passwordTemporary(), is(true));
    verify(userAccountInputValidator).validateUserDTO(any());
    verifyNoInteractions(identityClient, identityPasswordUpdater, rollbackFacade);
  }

  @Test
  void unavailableRecoveryPolicyStopsBeforeIdentityCreation() {
    org.mockito.Mockito.doThrow(
            new de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException(
                de.caritas.cob.userservice.api.exception.httpresponses.customheader
                    .HttpStatusExceptionReason.CHAT_RECOVERY_POLICY_UNAVAILABLE,
                org.springframework.http.HttpStatus.BAD_GATEWAY))
        .when(chatRecoveryEnrollmentPolicyService)
        .forNewConsultant(any());
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
            .class,
        () -> createConsultantSaga.createNewConsultant(validCreateConsultantDto()));
    verify(identityClient, never()).createUser(any(), any(), any());
    verify(consultantService, never()).saveConsultant(any());
  }

  @Test
  void createNewConsultant_Should_returnResponse_When_happyPath() throws Exception {
    stubHappyPath();

    var response = createConsultantSaga.createNewConsultant(validCreateConsultantDto());

    ArgumentCaptor<de.caritas.cob.userservice.api.model.Consultant> captured =
        ArgumentCaptor.forClass(de.caritas.cob.userservice.api.model.Consultant.class);
    verify(consultantService).saveConsultant(captured.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "LOGIN_PASSWORD", captured.getValue().getChatRecoveryMode());
    org.junit.jupiter.api.Assertions.assertEquals(
        3L, captured.getValue().getChatRecoveryPolicyRevision());
    assertThat(response, notNullValue());
    assertThat(response.getEmbedded(), notNullValue());
    assertThat(response.getEmbedded().getId(), is(KEYCLOAK_USER_ID));
    verify(identityClient).updateRole(KEYCLOAK_USER_ID, CONSULTANT.getValue());
    verify(appointmentService, never()).createConsultant(any());
  }

  @Test
  void createNewConsultant_Should_persistTheSecondFactorRequirement() throws Exception {
    // The admin API is the "an administrator picks the password and hands it over"
    // path, so the counsellor owes a second factor before the account is usable.
    stubHappyPath();

    createConsultantSaga.createNewConsultant(validCreateConsultantDto());

    ArgumentCaptor<de.caritas.cob.userservice.api.model.Consultant> captured =
        ArgumentCaptor.forClass(de.caritas.cob.userservice.api.model.Consultant.class);
    verify(consultantService).saveConsultant(captured.capture());
    assertThat(captured.getValue().getTwoFactorRequired(), is(true));
  }

  @Test
  void createNewConsultant_Should_startWithProductToursSwitchedOff() throws Exception {
    // #1526: tours are opt-in; the counsellor switches them on under Profile -> Help.
    stubHappyPath();

    createConsultantSaga.createNewConsultant(validCreateConsultantDto());

    ArgumentCaptor<de.caritas.cob.userservice.api.model.Consultant> captured =
        ArgumentCaptor.forClass(de.caritas.cob.userservice.api.model.Consultant.class);
    verify(consultantService).saveConsultant(captured.capture());
    assertThat(captured.getValue().getWalkThroughEnabled(), is(false));
  }

  @Test
  void createNewConsultant_Should_persistThePasswordChangeRequirement() throws Exception {
    // The administrator chose this password and passed it on, so it is a shared
    // secret until the counsellor replaces it.
    stubHappyPath();

    createConsultantSaga.createNewConsultant(validCreateConsultantDto());

    ArgumentCaptor<de.caritas.cob.userservice.api.model.Consultant> captured =
        ArgumentCaptor.forClass(de.caritas.cob.userservice.api.model.Consultant.class);
    verify(consultantService).saveConsultant(captured.capture());
    assertThat(captured.getValue().getPasswordChangeRequired(), is(true));
    verify(identityPasswordUpdater).updateTemporaryPassword(KEYCLOAK_USER_ID, VALID_PASSWORD);
  }

  @Test
  void createNewConsultant_Should_persistTheAvatarChoice() throws Exception {
    stubHappyPath();
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setAvatarKind(CreateConsultantDTO.AvatarKindEnum.ICON);
    dto.setAvatarId("motif-24");

    createConsultantSaga.createNewConsultant(dto);

    ArgumentCaptor<de.caritas.cob.userservice.api.model.Consultant> captured =
        ArgumentCaptor.forClass(de.caritas.cob.userservice.api.model.Consultant.class);
    verify(consultantService).saveConsultant(captured.capture());
    assertThat(
        captured.getValue().getAvatarKind(),
        is(de.caritas.cob.userservice.api.model.ConsultantAvatarKind.ICON));
    assertThat(captured.getValue().getAvatarId(), is("motif-24"));
  }

  @Test
  void createNewConsultant_Should_notPersistAHalfAvatarChoice() throws Exception {
    stubHappyPath();
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setAvatarKind(CreateConsultantDTO.AvatarKindEnum.ICON);
    dto.setAvatarId(null);

    createConsultantSaga.createNewConsultant(dto);

    ArgumentCaptor<de.caritas.cob.userservice.api.model.Consultant> captured =
        ArgumentCaptor.forClass(de.caritas.cob.userservice.api.model.Consultant.class);
    verify(consultantService).saveConsultant(captured.capture());
    assertThat(
        captured.getValue().getAvatarKind(),
        is(de.caritas.cob.userservice.api.model.ConsultantAvatarKind.INITIALS));
    assertThat(captured.getValue().getAvatarId(), nullValue());
  }

  @Test
  void createNewConsultant_Should_dropAMotifIdThatBelongsToNoIcon() throws Exception {
    stubHappyPath();
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setAvatarKind(CreateConsultantDTO.AvatarKindEnum.INITIALS);
    dto.setAvatarId("motif-24");

    createConsultantSaga.createNewConsultant(dto);

    ArgumentCaptor<de.caritas.cob.userservice.api.model.Consultant> captured =
        ArgumentCaptor.forClass(de.caritas.cob.userservice.api.model.Consultant.class);
    verify(consultantService).saveConsultant(captured.capture());
    assertThat(
        captured.getValue().getAvatarKind(),
        is(de.caritas.cob.userservice.api.model.ConsultantAvatarKind.INITIALS));
    assertThat(captured.getValue().getAvatarId(), nullValue());
  }

  @Test
  void createNewConsultant_Should_AssignAllRequestedAgenciesBeforeReturning() throws Exception {
    stubHappyPath();
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setAgencyIds(List.of(5L, 9L));

    createConsultantSaga.createNewConsultant(dto);

    ArgumentCaptor<CreateConsultantAgencyDTO> agencyCaptor =
        ArgumentCaptor.forClass(CreateConsultantAgencyDTO.class);
    verify(consultantAgencyRelationCreatorService, times(2))
        .createNewConsultantAgency(eq(KEYCLOAK_USER_ID), agencyCaptor.capture());
    assertThat(
        agencyCaptor.getAllValues().stream().map(CreateConsultantAgencyDTO::getAgencyId).toList(),
        is(List.of(5L, 9L)));
  }

  @Test
  void createNewConsultant_Should_ValidateAgencyAndTopicTopologyBeforeCreatingIdentity() {
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTenantId(3L);
    dto.setTopicIds(List.of(7L));
    dto.setAgencyIds(List.of(5L));
    doThrow(new BadRequestException("invalid topology"))
        .when(consultantTopicAgencyCompatibilityValidator)
        .validateGrantTopicsAgainstSelectedAgencies(List.of(7L), List.of(5L), 3L);

    assertThrows(BadRequestException.class, () -> createConsultantSaga.createNewConsultant(dto));

    verify(identityClient, never()).createUser(any(), anyString(), anyString());
  }

  @Test
  void createNewConsultant_Should_StoreTopicsPerSelectedCentre() throws Exception {
    stubHappyPath();
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTopicIds(List.of(7L, 8L));
    dto.setAgencyIds(List.of(5L, 9L));
    when(consultantTopicAgencyCompatibilityValidator.validateGrantTopicsAgainstSelectedAgencies(
            any(), any(), any()))
        .thenReturn(java.util.Map.of(5L, java.util.Set.of(7L, 8L), 9L, java.util.Set.of(7L)));

    createConsultantSaga.createNewConsultant(dto);

    ArgumentCaptor<de.caritas.cob.userservice.api.model.Consultant> captured =
        ArgumentCaptor.forClass(de.caritas.cob.userservice.api.model.Consultant.class);
    verify(consultantService).saveConsultant(captured.capture());
    assertThat(
        captured.getValue().getConsultantTopics().stream()
            .map(ct -> ct.getAgencyId() + ":" + ct.getTopicId())
            .collect(java.util.stream.Collectors.toSet()),
        is(java.util.Set.of("5:7", "5:8", "9:7")));
  }

  @Test
  void createNewConsultant_Should_PreserveLegacyTopicOnlyRequestsWithoutAgencyIds()
      throws Exception {
    stubHappyPath();
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTopicIds(List.of(7L));

    createConsultantSaga.createNewConsultant(dto);

    verify(consultantTopicAgencyCompatibilityValidator, never())
        .validateGrantTopicsAgainstSelectedAgencies(any(), any(), any());
  }

  @Test
  void createNewConsultant_Should_RollBackIdentityWhenAgencyAssignmentFails() throws Exception {
    stubKeycloakUserCreation();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setAgencyIds(List.of(5L));
    doThrow(new BadRequestException("relation failed"))
        .when(consultantAgencyRelationCreatorService)
        .createNewConsultantAgency(eq(KEYCLOAK_USER_ID), any(CreateConsultantAgencyDTO.class));

    assertThrows(BadRequestException.class, () -> createConsultantSaga.createNewConsultant(dto));

    verify(rollbackFacade).rollbackConsultantAccount(any(Consultant.class));
  }

  @Test
  void createNewConsultant_Should_callAppointmentService_When_featureEnabled() throws Exception {
    ReflectionTestUtils.setField(createConsultantSaga, "appointmentFeatureEnabled", true);
    stubHappyPath();

    createConsultantSaga.createNewConsultant(validCreateConsultantDto());

    verify(appointmentService).createConsultant(any());
  }

  @Test
  void createNewConsultant_Should_rollback_When_appointmentServiceFails() throws Exception {
    ReflectionTestUtils.setField(createConsultantSaga, "appointmentFeatureEnabled", true);
    stubHappyPath();
    when(authenticatedUser.getUserId()).thenReturn("admin-id");
    when(authenticatedUser.getRoles()).thenReturn(Set.of("admin"));
    doThrow(new RuntimeException("appointment down"))
        .when(appointmentService)
        .createConsultant(any());

    var ex =
        assertThrows(
            DistributedTransactionException.class,
            () -> createConsultantSaga.createNewConsultant(validCreateConsultantDto()));

    assertThat(
        ex.getCustomHttpHeaders().get("X-Reason").get(0),
        is(
            "DISTRIBUTED_TRANSACTION_FAILED_ON_STEP_CREATE_ACCOUNT_IN_CALCOM_OR_APPOINTMENTSERVICE"));
    verify(rollbackFacade).rollbackConsultantAccount(any(Consultant.class));
  }

  @Test
  void createNewConsultant_Should_throwBadRequest_When_passwordMissing() {
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setPassword(null);
    stubKeycloakUserCreation();

    assertThrows(BadRequestException.class, () -> createConsultantSaga.createNewConsultant(dto));
  }

  @Test
  void createNewConsultant_Should_throwCustomValidation_When_passwordUpdateFails() {
    stubKeycloakUserCreation();
    doThrow(new CustomValidationHttpStatusException(PASSWORD_NOT_VALID, HttpStatus.BAD_REQUEST))
        .when(identityPasswordUpdater)
        .updateTemporaryPassword(anyString(), anyString());

    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> createConsultantSaga.createNewConsultant(validCreateConsultantDto()));

    verify(rollbackFacade).rollbackConsultantAccount(any(Consultant.class));
    verify(identityClient, never()).updateRole(anyString(), anyString());
  }

  @Test
  void createNewConsultant_Should_throwDistributedTransaction_When_roleUpdateFails()
      throws Exception {
    stubKeycloakUserCreation();
    doThrow(new RuntimeException("role update failed"))
        .when(identityClient)
        .updateRole(anyString(), anyString());

    assertThrows(
        DistributedTransactionException.class,
        () -> createConsultantSaga.createNewConsultant(validCreateConsultantDto()));

    verify(rollbackFacade).rollbackConsultantAccount(any(Consultant.class));
  }

  @Test
  void createNewConsultant_Should_throwDistributedTransaction_When_saveConsultantFails()
      throws Exception {
    stubKeycloakUserCreation();
    doThrow(new RuntimeException("db down")).when(consultantService).saveConsultant(any());

    assertThrows(
        DistributedTransactionException.class,
        () -> createConsultantSaga.createNewConsultant(validCreateConsultantDto()));

    verify(rollbackFacade).rollbackConsultantAccount(any(Consultant.class));
  }

  @Test
  void createNewConsultant_Should_persistTheConsultant_When_matrixProvisioningFails()
      throws Exception {
    // The tolerance this asserts is deliberate: the integration suite and the E2E suite run
    // without a Synapse, and making a chat outage fatal turned 10 of them red. What the previous
    // version of this test did NOT do was exercise it - and for a sharper reason than the review
    // supposed. The saga reads PlainCredentialsHolder BEFORE createKeycloakUser populates it, so
    // with the holder unset the Matrix branch is skipped entirely and the mock is never touched:
    // the test asserted the tolerance without the saga ever attempting to provision. Seeding the
    // holder the way UserAdminController does is what makes the failure branch reachable at all.
    stubHappyPath();
    PlainCredentialsHolder.set(VALID_USERNAME, null);
    when(matrixSynapseService.createUserId(any(), any(), any()))
        .thenThrow(new MatrixCreateUserException("Synapse is unreachable"));

    var response = createConsultantSaga.createNewConsultant(validCreateConsultantDto());

    assertThat(response.getEmbedded().getId(), is(KEYCLOAK_USER_ID));
    verify(matrixSynapseService).createUserId(any(), any(), any());
    ArgumentCaptor<Consultant> consultantCaptor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(consultantCaptor.capture());
    assertThat(consultantCaptor.getValue().getMatrixUserId(), is((String) null));
    verify(rollbackFacade, never()).rollbackConsultantAccount(any(Consultant.class));
  }

  @Test
  void createNewConsultant_Should_persistTheConsultant_When_matrixAnswersWithoutAUserId()
      throws Exception {
    stubHappyPath();
    PlainCredentialsHolder.set(VALID_USERNAME, null);
    when(matrixSynapseService.createUserId(any(), any(), any())).thenReturn(null);

    var response = createConsultantSaga.createNewConsultant(validCreateConsultantDto());

    assertThat(response.getEmbedded().getId(), is(KEYCLOAK_USER_ID));
    verify(matrixSynapseService).createUserId(any(), any(), any());
    ArgumentCaptor<Consultant> consultantCaptor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(consultantCaptor.capture());
    assertThat(consultantCaptor.getValue().getMatrixUserId(), is((String) null));
  }

  @Test
  void createNewConsultant_Should_addGroupChatRole_When_flagEnabled() throws Exception {
    stubHappyPath();
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setIsGroupchatConsultant(true);

    createConsultantSaga.createNewConsultant(dto);

    verify(identityClient).updateRole(KEYCLOAK_USER_ID, CONSULTANT.getValue());
    verify(identityClient).updateRole(KEYCLOAK_USER_ID, GROUP_CHAT_CONSULTANT.getValue());
  }

  @Test
  void createNewConsultant_Should_generatePassword_When_importRecordHasNoPassword()
      throws Exception {
    ImportRecord importRecord = validImportRecord();
    stubHappyPath();
    when(userHelper.getRandomPassword()).thenReturn("GeneratedPass1!");

    Consultant consultant =
        createConsultantSaga.createNewConsultant(
            importRecord, CollectionHelper.asSet(CONSULTANT.getValue()));

    assertThat(consultant, notNullValue());
    org.junit.jupiter.api.Assertions.assertEquals(
        "LOGIN_PASSWORD", consultant.getChatRecoveryMode());
    org.junit.jupiter.api.Assertions.assertEquals(3L, consultant.getChatRecoveryPolicyRevision());
    verify(identityPasswordUpdater).updatePassword(KEYCLOAK_USER_ID, "GeneratedPass1!");
  }

  @Test
  void createNewConsultant_Should_notRequireASecondFactor_When_importingConsultants()
      throws Exception {
    // Imported counsellors already exist elsewhere; the import is a move, not a new
    // account, so it must not gate a whole migrated tenant at the next login.
    ImportRecord importRecord = validImportRecord();
    stubHappyPath();
    when(userHelper.getRandomPassword()).thenReturn("GeneratedPass1!");

    Consultant consultant =
        createConsultantSaga.createNewConsultant(
            importRecord, CollectionHelper.asSet(CONSULTANT.getValue()));

    assertThat(consultant.getTwoFactorRequired(), is(false));
  }

  @Test
  void createNewConsultant_Should_notRequireAPasswordChange_When_importingConsultants()
      throws Exception {
    ImportRecord importRecord = validImportRecord();
    stubHappyPath();
    when(userHelper.getRandomPassword()).thenReturn("GeneratedPass1!");

    Consultant consultant =
        createConsultantSaga.createNewConsultant(
            importRecord, CollectionHelper.asSet(CONSULTANT.getValue()));

    assertThat(consultant.getPasswordChangeRequired(), is(false));
  }

  @Test
  void rollbackCreateNewConsultant_Should_delegateToRollbackFacade() {
    Consultant consultant = new Consultant();
    consultant.setId(KEYCLOAK_USER_ID);

    createConsultantSaga.rollbackCreateNewConsultant(consultant);

    verify(rollbackFacade).rollbackConsultantAccount(consultant);
  }

  @Test
  void createNewConsultant_Should_throwLicensesExceeded_When_multiTenancyEnabledAndLimitReached() {
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
    TenantContext.setCurrentTenant(1L);
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTenantId(1L);
    var tenant = new TenantDTO().licensing(new Licensing().allowedNumberOfUsers(1));
    when(tenantAdminService.getTenantById(1L)).thenReturn(tenant);
    when(consultantService.getNumberOfActiveConsultants(1L)).thenReturn(1L);

    var ex =
        assertThrows(
            CustomValidationHttpStatusException.class,
            () -> createConsultantSaga.createNewConsultant(dto));

    assertThat(
        ex.getCustomHttpHeaders().get("X-Reason").get(0), is(NUMBER_OF_LICENSES_EXCEEDED.name()));
    verify(identityClient, never()).createUser(any(), anyString(), anyString());
  }

  @Test
  void createNewConsultant_Should_throwBadRequest_When_tenantIdCannotBeResolvedAtAll() {
    // Neither the body, the access token nor the tenant context carries a tenant, and the context
    // is not the global one - so ensureTenantIdResolved returns without setting or throwing.
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTenantId(null);

    assertThrows(BadRequestException.class, () -> createConsultantSaga.createNewConsultant(dto));

    verifyNoInteractions(tenantAdminService);
    verify(identityClient, never()).createUser(any(), anyString(), anyString());
  }

  @Test
  void createNewConsultant_Should_proceedUnlimited_When_tenantCarriesNoLicensingBlock()
      throws Exception {
    // Invite-flow tenants carry no licensing block at all: no configured limit means no limit.
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
    TenantContext.setCurrentTenant(1L);
    stubHappyPath();
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTenantId(1L);
    when(tenantAdminService.getTenantById(1L)).thenReturn(new TenantDTO());

    var response = createConsultantSaga.createNewConsultant(dto);

    assertThat(response, notNullValue());
    verify(consultantService, never()).getNumberOfActiveConsultants(1L);
  }

  @Test
  void createNewConsultant_Should_proceedUnlimited_When_tenantLicensingCarriesNoUserLimit()
      throws Exception {
    // `licensing_allowed_users = NULL` is the invite-flow default, not a configuration error.
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
    TenantContext.setCurrentTenant(1L);
    stubHappyPath();
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTenantId(1L);
    when(tenantAdminService.getTenantById(1L))
        .thenReturn(new TenantDTO().licensing(new Licensing()));

    var response = createConsultantSaga.createNewConsultant(dto);

    assertThat(response, notNullValue());
    verify(consultantService, never()).getNumberOfActiveConsultants(1L);
  }

  @Test
  void createNewConsultant_Should_reportUnconfiguredLicensing_When_tenantServiceIsUnreachable() {
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
    TenantContext.setCurrentTenant(1L);
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTenantId(1L);
    when(tenantAdminService.getTenantById(1L))
        .thenThrow(new org.springframework.web.client.RestClientException("tenant service down"));

    var ex =
        assertThrows(
            CustomValidationHttpStatusException.class,
            () -> createConsultantSaga.createNewConsultant(dto));

    assertThat(
        ex.getCustomHttpHeaders().get("X-Reason").get(0),
        is(TENANT_LICENSING_NOT_CONFIGURED.name()));
    verify(identityClient, never()).createUser(any(), anyString(), anyString());
  }

  @Test
  void createNewConsultant_Should_reportUnconfiguredLicensing_When_tenantLookupYieldsNothing() {
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
    TenantContext.setCurrentTenant(1L);
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTenantId(1L);
    when(tenantAdminService.getTenantById(1L)).thenReturn(null);

    var ex =
        assertThrows(
            CustomValidationHttpStatusException.class,
            () -> createConsultantSaga.createNewConsultant(dto));

    assertThat(
        ex.getCustomHttpHeaders().get("X-Reason").get(0),
        is(TENANT_LICENSING_NOT_CONFIGURED.name()));
    verify(consultantService, never()).getNumberOfActiveConsultants(1L);
  }

  @Test
  void createNewConsultant_Should_throwBadRequest_When_superadminCreatesWithoutTenantId() {
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
    TenantContext.setCurrentTenant(0L);
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTenantId(null);

    assertThrows(BadRequestException.class, () -> createConsultantSaga.createNewConsultant(dto));
  }

  @Test
  void createNewConsultant_Should_throwBadRequest_When_tenantIdDoesNotMatchContext() {
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
    TenantContext.setCurrentTenant(2L);
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTenantId(99L);

    assertThrows(BadRequestException.class, () -> createConsultantSaga.createNewConsultant(dto));
  }

  @Test
  void createNewConsultant_Should_resolveTenantIdFromAccessToken_When_contextMissing()
      throws Exception {
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
    when(authenticatedUser.getAccessToken()).thenReturn(jwtWithTenantId(5L));
    var tenant = new TenantDTO().licensing(new Licensing().allowedNumberOfUsers(10));
    when(tenantAdminService.getTenantById(5L)).thenReturn(tenant);
    when(consultantService.getNumberOfActiveConsultants(5L)).thenReturn(0L);
    stubHappyPath();

    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setTenantId(null);

    createConsultantSaga.createNewConsultant(dto);

    assertThat(dto.getTenantId(), is(5L));
  }

  @Test
  void createNewConsultant_Should_throwDistributedTransaction_When_passwordUpdateThrowsGeneric()
      throws Exception {
    stubKeycloakUserCreation();
    doThrow(new RuntimeException("keycloak down"))
        .when(identityPasswordUpdater)
        .updateTemporaryPassword(anyString(), anyString());

    var ex =
        assertThrows(
            DistributedTransactionException.class,
            () -> createConsultantSaga.createNewConsultant(validCreateConsultantDto()));

    assertThat(
        ex.getCustomHttpHeaders().get("X-Reason").get(0),
        is("DISTRIBUTED_TRANSACTION_FAILED_ON_STEP_UPDATE_USER_PASSWORD_IN_KEYCLOAK"));
    verify(rollbackFacade).rollbackConsultantAccount(any(Consultant.class));
  }

  // ---------------------------------------------------------------------------
  // ADR-002 §2 / #1200: the Matrix displayname is never the counsellor's real name.
  // The advice seeker shares the room and reads every member's displayname from /joined_members.
  // ---------------------------------------------------------------------------

  @Test
  void createNewConsultant_Should_provisionMatrixWithThePublicDisplayName() throws Exception {
    stubHappyPath();
    givenMatrixProvisioningIsReachable();
    CreateConsultantDTO dto = validCreateConsultantDto();
    dto.setDisplayName("Frau M.");

    createConsultantSaga.createNewConsultant(dto);

    MatrixRealNameGuard.assertNoRealNameReachedMatrix(matrixSynapseService, "First", "Last");
    assertThat(capturedMatrixDisplayName(), is("Frau M."));
  }

  @Test
  void createNewConsultant_Should_provisionMatrixWithTheUsername_When_noDisplayNameIsSet()
      throws Exception {
    stubHappyPath();
    givenMatrixProvisioningIsReachable();

    createConsultantSaga.createNewConsultant(validCreateConsultantDto());

    MatrixRealNameGuard.assertNoRealNameReachedMatrix(matrixSynapseService, "First", "Last");
    // The Matrix ID already carries the username, so this adds no new information.
    assertThat(capturedMatrixDisplayName(), is(VALID_USERNAME));
    assertThat(capturedMatrixDisplayName(), is(not("First Last")));
  }

  @Test
  void createNewConsultant_Should_stillSucceed_When_matrixProvisioningFails() throws Exception {
    stubHappyPath();
    PlainCredentialsHolder.set(VALID_USERNAME, null);
    when(userHelper.getRandomPassword()).thenReturn("MatrixPass1!");
    when(matrixSynapseService.createUserId(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("synapse down"));

    var response = createConsultantSaga.createNewConsultant(validCreateConsultantDto());

    assertThat(response.getEmbedded().getId(), is(KEYCLOAK_USER_ID));
    ArgumentCaptor<Consultant> consultantCaptor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(consultantCaptor.capture());
    assertThat(consultantCaptor.getValue().getMatrixUserId(), is(nullValue()));
    verify(rollbackFacade, never()).rollbackConsultantAccount(any(Consultant.class));
  }

  private void givenMatrixProvisioningIsReachable() throws Exception {
    PlainCredentialsHolder.set(VALID_USERNAME, null);
    when(userHelper.getRandomPassword()).thenReturn("MatrixPass1!");
    when(matrixSynapseService.createUserId(anyString(), anyString(), anyString()))
        .thenReturn("@" + VALID_USERNAME + ":matrix.example.org");
  }

  private String capturedMatrixDisplayName() throws Exception {
    ArgumentCaptor<String> displayNameCaptor = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService)
        .createUserId(anyString(), anyString(), displayNameCaptor.capture());
    return displayNameCaptor.getValue();
  }

  private java.util.Map<
          java.util.UUID,
          de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.Receipt>
      trackedRows;

  private java.util.function.Predicate<
          de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.Receipt>
      failTrackedWrite = receipt -> false;

  private void trackedJournalFixture() {
    trackedRows = new java.util.concurrent.ConcurrentHashMap<>();
    var repository =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.port.out.CreationOperationStore.class,
            invocation -> {
              switch (invocation.getMethod().getName()) {
                case "insert":
                  {
                    var receipt =
                        (de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation
                                .Receipt)
                            invocation.getArgument(0);
                    if (trackedRows.values().stream()
                        .anyMatch(r -> r.operationKey().equals(receipt.operationKey())))
                      throw new org.springframework.dao.DuplicateKeyException("collision");
                    trackedRows.put(receipt.operationId(), receipt);
                    return 1;
                  }
                case "findByKey":
                  return trackedRows.values().stream()
                      .filter(r -> r.operationKey().equals(invocation.getArgument(0)))
                      .findFirst();
                case "findById":
                  return java.util.Optional.ofNullable(trackedRows.get(invocation.getArgument(0)));
                case "compareAndSet":
                  {
                    var receipt =
                        (de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation
                                .Receipt)
                            invocation.getArgument(0);
                    if (failTrackedWrite.test(receipt))
                      throw new IllegalStateException("PrivateStorageCanary");
                    var old = trackedRows.get(receipt.operationId());
                    if (old == null || old.version() != (long) invocation.getArgument(1)) return 0;
                    trackedRows.put(receipt.operationId(), receipt);
                    return 1;
                  }
                default:
                  return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
              }
            });
    var writer =
        new de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationWriter(
            repository);
    ReflectionTestUtils.setField(createConsultantSaga, "creationWriter", writer);
    ReflectionTestUtils.setField(
        createConsultantSaga,
        "creationJournal",
        new AccountCreationAttemptJournal(
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.port.out.AccountCreationAttemptRepository.class),
            java.time.Clock.systemUTC(),
            writer));
    ReflectionTestUtils.setField(
        createConsultantSaga,
        "creationAccess",
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationAccess.class));
  }

  @Test
  void validatedClaimOccursBeforeNativeCreate() throws Exception {
    trackedJournalFixture();
    when(consultantService.saveConsultant(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var context =
        new de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationContext(
            new de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.Candidate(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "actual-owner"));
    var nativeClient =
        org.mockito.Mockito.mock(
            IdentityClient.class,
            invocation -> {
              if (invocation.getMethod().getName().equals("createUser")) {
                org.junit.jupiter.api.Assertions.assertTrue(
                    context.persistedOperationId().isPresent(),
                    "The durable claim must exist before any native creation");
                verify(userAccountInputValidator).validateUserDTO(any());
                var identity = new CreatedIdentity();
                identity.setUserId(KEYCLOAK_USER_ID);
                ((de.caritas.cob.userservice.api.port.out.identity.NativeCreationCapture)
                        invocation.getArgument(3))
                    .captured(identity);
                return identity;
              }
              return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
    ReflectionTestUtils.setField(createConsultantSaga, "identityClient", nativeClient);
    var dto = validCreateConsultantDto();
    dto.setTenantId(7L);
    createConsultantSaga.createNewConsultant(dto, context);
  }

  private de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationContext
      trackedContext(java.util.UUID key) {
    return new de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationContext(
        new de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.Candidate(
            java.util.UUID.randomUUID(), key, "actual-owner"));
  }

  private CreateConsultantDTO trackedInput() {
    var dto = validCreateConsultantDto();
    dto.setTenantId(7L);
    return dto;
  }

  private void trackedHappy() throws Exception {
    trackedHappy(true);
  }

  private void trackedHappy(boolean save) throws Exception {
    trackedJournalFixture();
    when(identityClient.createUser(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              var created = new CreatedIdentity(KEYCLOAK_USER_ID);
              ((de.caritas.cob.userservice.api.port.out.identity.NativeCreationCapture)
                      call.getArgument(3))
                  .captured(created);
              return created;
            });
    if (save) when(consultantService.saveConsultant(any())).thenAnswer(call -> call.getArgument(0));
  }

  @Test
  void replayAfterLicenseExhaustionIsReceiptOnlyAndBindsPersistedWinner() throws Exception {
    trackedHappy();
    var key = java.util.UUID.randomUUID();
    var first = trackedContext(key);
    createConsultantSaga.createNewConsultant(trackedInput(), first);
    org.mockito.Mockito.clearInvocations(
        identityClient,
        identityPasswordUpdater,
        matrixSynapseService,
        appointmentService,
        consultantPublicSlugService,
        tenantAdminService,
        userHelper,
        consultantService);
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
    var replay = trackedContext(key);
    var failure =
        assertThrows(
            de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationAccess
                .ExistingOperation.class,
            () -> createConsultantSaga.createNewConsultant(trackedInput(), replay));
    org.junit.jupiter.api.Assertions.assertEquals(
        first.receipt().operationId(), failure.operationId());
    org.junit.jupiter.api.Assertions.assertEquals(
        first.receipt().operationId(), replay.receipt().operationId());
    org.junit.jupiter.api.Assertions.assertFalse(failure.conflict());
    verifyNoInteractions(
        identityClient,
        identityPasswordUpdater,
        matrixSynapseService,
        appointmentService,
        tenantAdminService,
        userHelper,
        consultantService,
        consultantPublicSlugService);
    var changed = trackedInput();
    changed.setFirstname("Changed");
    org.junit.jupiter.api.Assertions.assertTrue(
        assertThrows(
                de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationAccess
                    .ExistingOperation.class,
                () -> createConsultantSaga.createNewConsultant(changed, trackedContext(key)))
            .conflict());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"user", "absence", "slug", "topic", "scope", "license", "tenant"})
  void invalidInputCreatesNoReceipt(String validation) {
    trackedJournalFixture();
    var dto = trackedInput();
    var failure =
        new de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException(
            "PrivateInputCanary");
    switch (validation) {
      case "user" ->
          org.mockito.Mockito.doThrow(failure)
              .when(userAccountInputValidator)
              .validateUserDTO(any());
      case "absence" ->
          org.mockito.Mockito.doThrow(failure)
              .when(userAccountInputValidator)
              .validateAbsence(any());
      case "slug" ->
          org.mockito.Mockito.doThrow(failure)
              .when(consultantPublicSlugService)
              .validateNewAdminSlug(any());
      case "topic" -> {
        dto.setAgencyIds(java.util.List.of(2L));
        org.mockito.Mockito.doThrow(failure)
            .when(consultantTopicAgencyCompatibilityValidator)
            .validateGrantTopicsAgainstSelectedAgencies(any(), any(), any());
      }
      case "scope" ->
          org.mockito.Mockito.doThrow(failure)
              .when(
                  (de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationAccess)
                      ReflectionTestUtils.getField(createConsultantSaga, "creationAccess"))
              .requireScope(any());
      case "license" ->
          ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
      case "tenant" -> {
        dto.setTenantId(null);
        ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
      }
    }
    assertThrows(
        RuntimeException.class,
        () ->
            createConsultantSaga.createNewConsultant(
                dto, trackedContext(java.util.UUID.randomUUID())));
    org.junit.jupiter.api.Assertions.assertTrue(trackedRows.isEmpty());
    verifyNoInteractions(
        identityClient,
        identityPasswordUpdater,
        rollbackFacade,
        matrixSynapseService,
        appointmentService);
  }

  @Test
  void captureFailureSkipsEverySagaCompensationAndLaterMutation() {
    trackedJournalFixture();
    failTrackedWrite = r -> r.nativeId() != null;
    when(identityClient.createUser(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              ((de.caritas.cob.userservice.api.port.out.identity.NativeCreationCapture)
                      call.getArgument(3))
                  .captured(new CreatedIdentity(KEYCLOAK_USER_ID));
              return new CreatedIdentity(KEYCLOAK_USER_ID);
            });
    var context = trackedContext(java.util.UUID.randomUUID());
    var failure =
        assertThrows(
            de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationFailure.class,
            () -> createConsultantSaga.createNewConsultant(trackedInput(), context));
    org.junit.jupiter.api.Assertions.assertEquals(503, failure.status());
    org.junit.jupiter.api.Assertions.assertNull(context.receipt().nativeId());
    org.junit.jupiter.api.Assertions.assertEquals(
        de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.DispatchState
            .DISPATCH_UNKNOWN,
        context.receipt().dispatch());
    verifyNoInteractions(
        identityPasswordUpdater,
        rollbackFacade,
        matrixSynapseService,
        appointmentService,
        consultantService);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void nativeFailureKeepsConflictStatusOnlyWhenKnownAndNeverCompensates(boolean knownConflict) {
    trackedJournalFixture();
    org.mockito.Mockito.doThrow(
            knownConflict
                ? new de.caritas.cob.userservice.api.port.out.identity.NativeCreationConflict()
                : new IllegalStateException("PrivateProviderCanary"))
        .when(identityClient)
        .createUser(any(), any(), any(), any());
    var context = trackedContext(java.util.UUID.randomUUID());
    var failure =
        assertThrows(
            de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationFailure.class,
            () -> createConsultantSaga.createNewConsultant(trackedInput(), context));
    org.junit.jupiter.api.Assertions.assertEquals(knownConflict ? 409 : 424, failure.status());
    org.junit.jupiter.api.Assertions.assertEquals(
        context.receipt().operationId(), failure.operationId());
    org.junit.jupiter.api.Assertions.assertEquals(
        de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.DispatchState
            .DISPATCH_UNKNOWN,
        context.receipt().dispatch());
    verifyNoInteractions(
        identityPasswordUpdater,
        rollbackFacade,
        matrixSynapseService,
        appointmentService,
        consultantService);
  }

  @Test
  void mandatoryAttributeFailureRetainsCapturedIdentityWithoutSecondSagaCompensation() {
    trackedJournalFixture();
    when(identityClient.createUser(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              var capture =
                  (de.caritas.cob.userservice.api.port.out.identity.NativeCreationCapture)
                      call.getArgument(3);
              capture.captured(new CreatedIdentity(KEYCLOAK_USER_ID));
              capture.compensationStarting();
              capture.compensationReturned();
              throw new IllegalStateException("PrivateAttributeCanary");
            });
    var context = trackedContext(java.util.UUID.randomUUID());
    var failure =
        assertThrows(
            de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationFailure.class,
            () -> createConsultantSaga.createNewConsultant(trackedInput(), context));
    org.junit.jupiter.api.Assertions.assertEquals(424, failure.status());
    org.junit.jupiter.api.Assertions.assertEquals(KEYCLOAK_USER_ID, context.receipt().nativeId());
    org.junit.jupiter.api.Assertions.assertEquals(
        de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.CompensationState
            .RETURNED_UNVERIFIED,
        context.receipt().compensation());
    verifyNoInteractions(
        rollbackFacade, identityPasswordUpdater, matrixSynapseService, appointmentService);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"password", "role", "agency", "appointment", "row"})
  void laterFailuresPersistCompensationStartBeforeDeletingAndNeverClaimAbsence(String step)
      throws Exception {
    trackedHappy(step.equals("agency") || step.equals("appointment"));
    var dto = trackedInput();
    var context = trackedContext(java.util.UUID.randomUUID());
    var failure = new IllegalStateException("PrivateStepCanary");
    switch (step) {
      case "password" ->
          org.mockito.Mockito.doThrow(failure)
              .when(identityPasswordUpdater)
              .updateTemporaryPassword(any(), any());
      case "role" ->
          org.mockito.Mockito.doThrow(failure)
              .when(identityClient)
              .updateRole(anyString(), anyString());
      case "agency" -> {
        dto.setAgencyIds(java.util.List.of(2L));
        org.mockito.Mockito.doThrow(failure)
            .when(consultantAgencyRelationCreatorService)
            .createNewConsultantAgency(any(), any());
      }
      case "appointment" -> {
        ReflectionTestUtils.setField(createConsultantSaga, "appointmentFeatureEnabled", true);
        org.mockito.Mockito.doThrow(failure)
            .when(appointmentService)
            .createConsultant(any(), org.mockito.ArgumentMatchers.eq(true));
      }
      case "row" ->
          org.mockito.Mockito.doThrow(failure).when(consultantService).saveConsultant(any());
    }
    org.mockito.Mockito.doAnswer(
            call -> {
              org.junit.jupiter.api.Assertions.assertEquals(
                  de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation
                      .CompensationState.STARTED_OUTCOME_UNKNOWN,
                  trackedRows.get(context.receipt().operationId()).compensation());
              return null;
            })
        .when(rollbackFacade)
        .rollbackConsultantAccount(any(), org.mockito.ArgumentMatchers.eq(true));
    assertThrows(
        de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationFailure.class,
        () -> createConsultantSaga.createNewConsultant(dto, context));
    org.junit.jupiter.api.Assertions.assertEquals(KEYCLOAK_USER_ID, context.receipt().nativeId());
    org.junit.jupiter.api.Assertions.assertEquals(
        de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.CompensationState
            .RETURNED_UNVERIFIED,
        context.receipt().compensation());
    verify(rollbackFacade).rollbackConsultantAccount(any(), org.mockito.ArgumentMatchers.eq(true));
  }

  @Test
  void compensationStartStorageFailurePermitsZeroDelete() throws Exception {
    trackedHappy(false);
    failTrackedWrite =
        r ->
            r.compensation()
                == de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation
                    .CompensationState.STARTED_OUTCOME_UNKNOWN;
    org.mockito.Mockito.doThrow(new IllegalStateException("PrivatePasswordCanary"))
        .when(identityPasswordUpdater)
        .updateTemporaryPassword(any(), any());
    var context = trackedContext(java.util.UUID.randomUUID());
    var failure =
        assertThrows(
            de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationFailure.class,
            () -> createConsultantSaga.createNewConsultant(trackedInput(), context));
    org.junit.jupiter.api.Assertions.assertEquals(503, failure.status());
    verifyNoInteractions(rollbackFacade);
  }

  @Test
  void unavailableJournalBeforeClaimReturns503WithoutReceiptOrNativeEffect() {
    var journal = org.mockito.Mockito.mock(AccountCreationAttemptJournal.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("PrivateJournalCanary"))
        .when(journal)
        .findByKey(any());
    ReflectionTestUtils.setField(createConsultantSaga, "creationJournal", journal);
    ReflectionTestUtils.setField(
        createConsultantSaga,
        "creationAccess",
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationAccess.class));
    var context = trackedContext(java.util.UUID.randomUUID());
    var failure =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> createConsultantSaga.createNewConsultant(trackedInput(), context));
    org.junit.jupiter.api.Assertions.assertEquals(503, failure.getStatusCode().value());
    org.junit.jupiter.api.Assertions.assertTrue(context.persistedOperationId().isEmpty());
    org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
    verifyNoInteractions(
        identityClient,
        identityPasswordUpdater,
        matrixSynapseService,
        rollbackFacade,
        appointmentService);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void lateSlugRaceCompensatesActualCapturedIdentityOnlyAfterDurableStart(boolean failStart)
      throws Exception {
    trackedHappy(false);
    when(matrixSynapseService.createUserIdTracked(any(), any(), any()))
        .thenReturn("@actual-created:matrix.invalid");
    org.mockito.Mockito.doThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "PrivateSlugRaceCanary"))
        .when(consultantPublicSlugService)
        .applyAdminSlug(any(), any());
    if (failStart)
      failTrackedWrite =
          r ->
              r.compensation()
                  == de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation
                      .CompensationState.STARTED_OUTCOME_UNKNOWN;
    var context = trackedContext(java.util.UUID.randomUUID());
    if (!failStart)
      org.mockito.Mockito.doAnswer(
              call -> {
                var rollback = (Consultant) call.getArgument(0);
                org.junit.jupiter.api.Assertions.assertEquals(KEYCLOAK_USER_ID, rollback.getId());
                org.junit.jupiter.api.Assertions.assertEquals(
                    "@actual-created:matrix.invalid", rollback.getMatrixUserId());
                org.junit.jupiter.api.Assertions.assertEquals(7L, rollback.getTenantId());
                org.junit.jupiter.api.Assertions.assertEquals(
                    de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation
                        .CompensationState.STARTED_OUTCOME_UNKNOWN,
                    trackedRows.get(context.receipt().operationId()).compensation());
                return null;
              })
          .when(rollbackFacade)
          .rollbackConsultantAccount(any(), org.mockito.ArgumentMatchers.eq(true));
    var failure =
        assertThrows(
            de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationFailure.class,
            () -> createConsultantSaga.createNewConsultant(trackedInput(), context));
    org.junit.jupiter.api.Assertions.assertEquals(failStart ? 503 : 409, failure.status());
    org.junit.jupiter.api.Assertions.assertEquals(KEYCLOAK_USER_ID, context.receipt().nativeId());
    if (failStart) verifyNoInteractions(rollbackFacade);
    else {
      verify(rollbackFacade)
          .rollbackConsultantAccount(any(), org.mockito.ArgumentMatchers.eq(true));
      org.junit.jupiter.api.Assertions.assertEquals(
          de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.CompensationState
              .RETURNED_UNVERIFIED,
          context.receipt().compensation());
    }
  }

  @Test
  void trackedLicenseFailureNeverLogsProviderCanary() {
    trackedJournalFixture();
    ReflectionTestUtils.setField(createConsultantSaga, "multiTenancyEnabled", true);
    when(tenantAdminService.getTenantById(7L))
        .thenThrow(new org.springframework.web.client.RestClientException("PrivateLicenseCanary"));
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var capture =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              createConsultantSaga.createNewConsultant(
                  trackedInput(), trackedContext(java.util.UUID.randomUUID())));
      for (var event : capture.list) {
        org.junit.jupiter.api.Assertions.assertFalse(
            event.getFormattedMessage().contains("PrivateLicenseCanary"));
        if (event.getThrowableProxy() != null)
          org.junit.jupiter.api.Assertions.assertFalse(
              ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy())
                  .contains("PrivateLicenseCanary"));
      }
      verifyNoInteractions(identityClient, rollbackFacade);
    } finally {
      logger.detachAppender(capture);
      capture.stop();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"returned", "missing", "failed"})
  void trackedMatrixUsesValidatedInputWithoutAmbientCredentialsAndKeepsPartialOutcome(String result)
      throws Exception {
    PlainCredentialsHolder.clear();
    trackedHappy();
    org.mockito.Mockito.doAnswer(
            call -> {
              org.junit.jupiter.api.Assertions.assertEquals(VALID_USERNAME, call.getArgument(0));
              org.junit.jupiter.api.Assertions.assertNull(PlainCredentialsHolder.get());
              if (result.equals("failed")) throw new IllegalStateException("PrivateMatrixCanary");
              return result.equals("returned") ? "@actual:matrix.invalid" : null;
            })
        .when(matrixSynapseService)
        .createUserIdTracked(any(), any(), any());
    var context = trackedContext(java.util.UUID.randomUUID());
    var outcome = createConsultantSaga.createNewConsultant(trackedInput(), context);
    org.junit.jupiter.api.Assertions.assertEquals(
        context.receipt().operationId(), outcome.operationId());
    var saved = org.mockito.ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(saved.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        result.equals("returned") ? "@actual:matrix.invalid" : null,
        saved.getValue().getMatrixUserId());
    org.junit.jupiter.api.Assertions.assertEquals(KEYCLOAK_USER_ID, context.receipt().nativeId());
    org.junit.jupiter.api.Assertions.assertEquals(
        de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.RowState
            .COMMIT_UNKNOWN,
        context.receipt().row());
    verifyNoInteractions(rollbackFacade);
    org.junit.jupiter.api.Assertions.assertNull(PlainCredentialsHolder.get());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 409, 404})
  void insertionRaceBindsOnlyCurrentlyAuthorizedPersistedWinner(int collisionStatus) {
    trackedJournalFixture();
    var key = java.util.UUID.randomUUID();
    var context = trackedContext(key);
    var scope =
        new de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.Scope(
            7, java.util.List.of(), "COUNSELLOR");
    var writer =
        (de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationWriter)
            ReflectionTestUtils.getField(createConsultantSaga, "creationWriter");
    var persisted =
        writer
            .prepare(
                new de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation
                    .Prepared(
                    new de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation
                        .Candidate(java.util.UUID.randomUUID(), key, "actual-owner"),
                    scope,
                    collisionStatus == 409
                        ? "a".repeat(64)
                        : de.caritas.cob.userservice.api.service.creationoutcome
                            .CreationRequestFingerprint.of(trackedInput(), scope)))
            .receipt();
    var journal = org.mockito.Mockito.mock(AccountCreationAttemptJournal.class);
    when(journal.findByKey(key))
        .thenReturn(java.util.Optional.empty(), java.util.Optional.of(persisted));
    if (collisionStatus == 0)
      when(journal.claim(any()))
          .thenReturn(
              new de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.Claim(
                  persisted, false));
    else
      when(journal.claim(any()))
          .thenThrow(
              new de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationFailure(
                  persisted.operationId(),
                  collisionStatus,
                  de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.Reason
                      .NONE));
    ReflectionTestUtils.setField(createConsultantSaga, "creationJournal", journal);
    var access =
        (de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationAccess)
            ReflectionTestUtils.getField(createConsultantSaga, "creationAccess");
    if (collisionStatus == 404) {
      org.mockito.Mockito.doThrow(
              de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationAccess
                  .denied())
          .when(access)
          .requireReceiptAccess(persisted);
      assertThrows(
          org.springframework.web.server.ResponseStatusException.class,
          () -> createConsultantSaga.createNewConsultant(trackedInput(), context));
      org.junit.jupiter.api.Assertions.assertTrue(context.persistedOperationId().isEmpty());
    } else {
      var failure =
          assertThrows(
              de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationAccess
                  .ExistingOperation.class,
              () -> createConsultantSaga.createNewConsultant(trackedInput(), context));
      org.junit.jupiter.api.Assertions.assertEquals(persisted.operationId(), failure.operationId());
      org.junit.jupiter.api.Assertions.assertEquals(
          persisted.operationId(), context.receipt().operationId());
      org.junit.jupiter.api.Assertions.assertNotEquals(
          context.candidate().operationId(), failure.operationId());
      org.junit.jupiter.api.Assertions.assertEquals(collisionStatus == 409, failure.conflict());
    }
    verify(access).requireReceiptAccess(persisted);
    verifyNoInteractions(
        identityClient,
        identityPasswordUpdater,
        matrixSynapseService,
        appointmentService,
        rollbackFacade);
  }

  private void stubHappyPath() throws Exception {
    stubKeycloakUserCreation();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private void stubKeycloakUserCreation() {
    when(identityClient.createUser(any(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              PlainCredentialsHolder.set(VALID_USERNAME, null);
              CreatedIdentity response = new CreatedIdentity();
              response.setUserId(KEYCLOAK_USER_ID);
              return response;
            });
  }

  private CreateConsultantDTO validCreateConsultantDto() {
    CreateConsultantDTO dto = new CreateConsultantDTO();
    dto.setUsername(VALID_USERNAME);
    dto.setEmail(VALID_EMAIL);
    dto.setPassword(VALID_PASSWORD);
    dto.setFirstname("First");
    dto.setLastname("Last");
    dto.setIsGroupchatConsultant(false);
    return dto;
  }

  private ImportRecord validImportRecord() {
    ImportRecord importRecord = new ImportRecord();
    importRecord.setUsername(VALID_USERNAME);
    importRecord.setUsernameEncoded("encoded-" + VALID_USERNAME);
    importRecord.setEmail(VALID_EMAIL);
    importRecord.setFirstName("First");
    importRecord.setLastName("Last");
    return importRecord;
  }

  private String jwtWithTenantId(long tenantId) {
    String header =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                String.format("{\"tenantId\":%d}", tenantId).getBytes(StandardCharsets.UTF_8));
    return header + "." + payload + ".signature";
  }
}
