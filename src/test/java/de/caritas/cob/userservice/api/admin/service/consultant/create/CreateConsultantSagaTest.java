package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static de.caritas.cob.userservice.api.config.auth.UserRole.CONSULTANT;
import static de.caritas.cob.userservice.api.config.auth.UserRole.GROUP_CHAT_CONSULTANT;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.NUMBER_OF_LICENSES_EXCEEDED;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.PASSWORD_NOT_VALID;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.TENANT_LICENSING_NOT_CONFIGURED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CreateConsultantSagaTest {

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
  void createNewConsultant_Should_returnResponse_When_happyPath() throws Exception {
    stubHappyPath();

    var response = createConsultantSaga.createNewConsultant(validCreateConsultantDto());

    assertThat(response, notNullValue());
    assertThat(response.getEmbedded(), notNullValue());
    assertThat(response.getEmbedded().getId(), is(KEYCLOAK_USER_ID));
    verify(identityClient).updateRole(KEYCLOAK_USER_ID, CONSULTANT.getValue());
    verify(appointmentService, never()).createConsultant(any());
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
        .updatePassword(anyString(), anyString());

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
  void
      createNewConsultant_Should_continueWithoutMatrixIdentity_When_plainCredentialsAreUnavailable()
          throws Exception {
    stubHappyPath();

    var response = createConsultantSaga.createNewConsultant(validCreateConsultantDto());

    assertThat(response.getEmbedded().getId(), is(KEYCLOAK_USER_ID));
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
    verify(identityPasswordUpdater).updatePassword(KEYCLOAK_USER_ID, "GeneratedPass1!");
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
        .updatePassword(anyString(), anyString());

    var ex =
        assertThrows(
            DistributedTransactionException.class,
            () -> createConsultantSaga.createNewConsultant(validCreateConsultantDto()));

    assertThat(
        ex.getCustomHttpHeaders().get("X-Reason").get(0),
        is("DISTRIBUTED_TRANSACTION_FAILED_ON_STEP_UPDATE_USER_PASSWORD_IN_KEYCLOAK"));
    verify(rollbackFacade).rollbackConsultantAccount(any(Consultant.class));
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
