package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static de.caritas.cob.userservice.api.config.auth.UserRole.CONSULTANT;
import static de.caritas.cob.userservice.api.config.auth.UserRole.GROUP_CHAT_CONSULTANT;
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
import de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.MatrixRealNameGuard;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot;
import de.caritas.cob.userservice.api.service.ConsultantService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrantConsultantIdentityServiceTest {
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

  private static final String ADMIN_ID = "admin-uuid-1";
  private static final String ADMIN_USERNAME = "adminUsername";
  private static final String MATRIX_USER_ID = "@adminUsername:matrix";

  @InjectMocks private GrantConsultantIdentityService grantConsultantIdentityService;

  @Mock private AdminRepository adminRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private de.caritas.cob.userservice.api.port.out.IdentityClient identityClient;
  @Mock private IdentityRoleUpdater identityRoleUpdater;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private ConsultantService consultantService;
  @Mock private ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;
  @Mock private UserHelper userHelper;

  @Mock private AdminScope adminScope;

  @Mock
  private ConsultantTopicAgencyCompatibilityValidator consultantTopicAgencyCompatibilityValidator;

  // The real rule, not a mock: ConsultantDisplayNameResolver is the single place that decides
  // which name may reach Matrix (ADR-002 §2).
  @Spy
  private ConsultantDisplayNameResolver consultantDisplayNameResolver =
      new ConsultantDisplayNameResolver();

  private GrantConsultantIdentityDTO dto;

  @BeforeEach
  void setUp() {
    dto = new GrantConsultantIdentityDTO();
    dto.setAgencyIds(List.of(1L, 2L));
    dto.setGroupchatConsultant(false);
  }

  private Admin validAdmin() {
    return Admin.builder()
        .id(ADMIN_ID)
        .username(ADMIN_USERNAME)
        .firstName("First")
        .lastName("Last")
        .email("admin@example.com")
        .tenantId(1L)
        .build();
  }

  private void stubHappyMatrix() throws Exception {
    when(matrixSynapseService.createUserId(anyString(), anyString(), anyString()))
        .thenReturn(MATRIX_USER_ID);
    when(userHelper.getRandomPassword()).thenReturn("randomPw");
  }

  @Test
  void throwBadRequest_When_adminNotFound() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.empty());

    assertThrows(
        BadRequestException.class,
        () -> grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto));

    verify(identityRoleUpdater, never()).ensureRoles(anyString(), any());
    verify(consultantService, never()).saveConsultant(any());
  }

  @Test
  void refuseBeforeConflictLookup_When_callerMayNotActOnAdmin() {
    var admin = validAdmin();
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
    doThrow(new ForbiddenException("out of scope"))
        .when(adminScope)
        .assertMay(Target.admin(ADMIN_ID));

    assertThrows(
        ForbiddenException.class,
        () -> grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto));

    verifyNoInteractions(
        consultantRepository, identityRoleUpdater, consultantService, matrixSynapseService);
  }

  @Test
  void refuseBeforeConflictLookup_When_callerMayNotUseAgencies() {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    org.mockito.Mockito.lenient()
        .doThrow(new ForbiddenException("out of scope"))
        .when(adminScope)
        .assertMay(Target.agencies(dto.getAgencyIds()));

    assertThrows(
        ForbiddenException.class,
        () -> grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto));

    verifyNoInteractions(
        consultantRepository, identityRoleUpdater, consultantService, matrixSynapseService);
  }

  @Test
  void throwConflict_When_alreadyAConsultant() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID))
        .thenReturn(Optional.of(new Consultant()));

    var ex =
        assertThrows(
            CustomValidationHttpStatusException.class,
            () -> grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto));

    assertThat(
        ex.getCustomHttpHeaders().get("X-Reason").get(0),
        is("CONSULTANT_IDENTITY_ALREADY_GRANTED"));
    verify(identityRoleUpdater, never()).ensureRoles(anyString(), any());
    verify(consultantService, never()).saveConsultant(any());
  }

  @Test
  void throwConflict_When_priorPartialAttemptHasConsultantUsername() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.of(new Consultant()));

    var ex =
        assertThrows(
            CustomValidationHttpStatusException.class,
            () -> grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto));

    assertThat(
        ex.getCustomHttpHeaders().get("X-Reason").get(0),
        is("CONSULTANT_IDENTITY_ALREADY_GRANTED"));
    verify(identityRoleUpdater, never()).ensureRoles(anyString(), any());
  }

  @Test
  void assignConsultantRole_And_persistConsultant_When_validAdmin() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var response = grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto);

    verify(identityRoleUpdater).ensureRoles(ADMIN_ID, Set.of(CONSULTANT.getValue()));

    ArgumentCaptor<Consultant> consultantCaptor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(consultantCaptor.capture());
    Consultant saved = consultantCaptor.getValue();
    assertThat(saved.getId(), is(ADMIN_ID));
    assertThat(saved.getChatRecoveryMode(), is("LOGIN_PASSWORD"));
    assertThat(saved.getChatRecoveryPolicyRevision(), is(3L));
    assertThat(saved.getEmail(), is("admin@example.com"));
    // create/update dates must be set explicitly: liquibase-created schemas have
    // no column default and reject NULL (found on the local clean-slate stack).
    assertThat(saved.getCreateDate(), notNullValue());
    assertThat(saved.getUpdateDate(), notNullValue());
    assertThat(saved.getMatrixUserId(), is(MATRIX_USER_ID));

    assertThat(response, notNullValue());
    assertThat(response.getEmbedded(), notNullValue());
    assertThat(response.getEmbedded().getId(), is(ADMIN_ID));
    verify(chatRecoveryEnrollmentPolicyService).forNewConsultant(1L);
    verify(chatRecoveryEnrollmentPolicyService, never()).forExistingIdentity(anyString());
  }

  @Test
  void storeTopicsPerSelectedCentre_When_validatorDistributesThem() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    dto.setTopicIds(List.of(7L));
    when(consultantTopicAgencyCompatibilityValidator.validateGrantTopicsAgainstSelectedAgencies(
            any(), any(), any()))
        .thenReturn(java.util.Map.of(1L, Set.of(7L), 2L, Set.of(7L)));

    grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto);

    ArgumentCaptor<Consultant> consultantCaptor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(consultantCaptor.capture());
    assertThat(
        consultantCaptor.getValue().getConsultantTopics().stream()
            .map(ct -> ct.getAgencyId() + ":" + ct.getTopicId())
            .collect(java.util.stream.Collectors.toSet()),
        is(Set.of("1:7", "2:7")));
  }

  @Test
  void requireASecondFactor_When_anAdminIsPromotedToConsultant() throws Exception {
    // The create path marks every admin-provisioned counsellor as owing a second factor
    // (CreateConsultantDTOCreationInputAdapter). This path grants the same role over the same
    // kind of account, so leaving twoFactorRequired at its builder default would mean a
    // counsellor who logs in without one while a freshly created colleague cannot.
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto);

    ArgumentCaptor<Consultant> consultantCaptor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(consultantCaptor.capture());
    assertThat(consultantCaptor.getValue().getTwoFactorRequired(), is(true));
  }

  @Test
  void leaveThePasswordChangeRequirementUnset_When_anAdminIsPromoted() throws Exception {
    // No new password is chosen on this path, so demanding a replacement would ask the admin to
    // replace a password that is already theirs.
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto);

    ArgumentCaptor<Consultant> consultantCaptor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(consultantCaptor.capture());
    assertThat(consultantCaptor.getValue().getPasswordChangeRequired(), is(false));
  }

  @Test
  void throwBadRequestBeforeRoles_When_topicsAreNotCoveredBySelectedAgencies() throws Exception {
    dto.setTopicIds(List.of(99L));
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    doThrow(new BadRequestException("topic not covered"))
        .when(consultantTopicAgencyCompatibilityValidator)
        .validateGrantTopicsAgainstSelectedAgencies(
            eq(dto.getTopicIds()), eq(dto.getAgencyIds()), eq(1L));

    assertThrows(
        BadRequestException.class,
        () -> grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto));

    verify(identityRoleUpdater, never()).ensureRoles(anyString(), any());
    verify(consultantService, never()).saveConsultant(any());
  }

  @Test
  void addGroupChatRole_When_flagSet() throws Exception {
    dto.setGroupchatConsultant(true);
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto);

    verify(identityRoleUpdater)
        .ensureRoles(ADMIN_ID, Set.of(CONSULTANT.getValue(), GROUP_CHAT_CONSULTANT.getValue()));
  }

  // ---------------------------------------------------------------------------
  // ADR-002 §2 / #1200: granting a consultant identity must not publish the admin's real name.
  // This class's javadoc says it "mirrors CreateConsultantSaga" — it mirrored the defect too.
  // ---------------------------------------------------------------------------

  @Test
  void provisionMatrixWithTheUsername_And_neverTheRealName() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto);

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(matrixSynapseService)
        .createUserId(eq(ADMIN_USERNAME), anyString(), displayName.capture());
    MatrixRealNameGuard.assertNoRealNameReachedMatrix(matrixSynapseService, "First", "Last");
    // An Admin has no public display name, so the Matrix ID's own username is the only source.
    assertThat(displayName.getValue(), is(ADMIN_USERNAME));
    assertThat(displayName.getValue(), is(not("First Last")));
  }

  @Test
  void continueDespiteMatrixFailure() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    when(userHelper.getRandomPassword()).thenReturn("randomPw");
    when(matrixSynapseService.createUserId(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("matrix down"));
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto);

    ArgumentCaptor<Consultant> consultantCaptor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(consultantCaptor.capture());
    assertThat(consultantCaptor.getValue().getMatrixUserId(), nullValue());
  }

  @Test
  void rollbackKeycloakRole_When_consultantSaveFails() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenThrow(new RuntimeException("db down"));

    assertThrows(
        DistributedTransactionException.class,
        () -> grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto));

    verify(identityClient).removeRoleIfPresent(ADMIN_ID, CONSULTANT.getValue());
  }

  @Test
  void assignAgencies_After_consultantCreated() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto);

    verify(consultantAgencyRelationCreatorService, times(dto.getAgencyIds().size()))
        .createNewConsultantAgency(eq(ADMIN_ID), any());
  }

  @Test
  void throwAndRollbackRoles_When_agencyAssignmentFails() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doThrow(new BadRequestException("invalid agency assignment"))
        .when(consultantAgencyRelationCreatorService)
        .createNewConsultantAgency(eq(ADMIN_ID), any());

    assertThrows(
        BadRequestException.class,
        () -> grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto));

    verify(identityClient).removeRoleIfPresent(ADMIN_ID, CONSULTANT.getValue());
  }

  @Test
  void skipAgencyAssignment_When_agencyIdsNull() throws Exception {
    dto.setAgencyIds(null);
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto);

    verify(consultantAgencyRelationCreatorService, never())
        .createNewConsultantAgency(anyString(), any());
  }

  @Test
  void continueWithoutMatrixUserId_When_matrixResponseMissingUserId() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    when(userHelper.getRandomPassword()).thenReturn("randomPw");
    when(matrixSynapseService.createUserId(anyString(), anyString(), anyString())).thenReturn(null);
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto);

    ArgumentCaptor<Consultant> consultantCaptor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(consultantCaptor.capture());
    assertThat(consultantCaptor.getValue().getMatrixUserId(), nullValue());
  }

  @Test
  void rollbackGroupChatRole_When_consultantSaveFailsAndGroupChatEnabled() throws Exception {
    dto.setGroupchatConsultant(true);
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenThrow(new RuntimeException("db down"));

    assertThrows(
        DistributedTransactionException.class,
        () -> grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto));

    verify(identityClient).removeRoleIfPresent(ADMIN_ID, CONSULTANT.getValue());
    verify(identityClient).removeRoleIfPresent(ADMIN_ID, GROUP_CHAT_CONSULTANT.getValue());
  }

  @Test
  void rollbackGroupChatRole_When_agencyAssignmentFailsAndGroupChatEnabled() throws Exception {
    dto.setGroupchatConsultant(true);
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doThrow(new BadRequestException("invalid agency assignment"))
        .when(consultantAgencyRelationCreatorService)
        .createNewConsultantAgency(eq(ADMIN_ID), any());

    assertThrows(
        BadRequestException.class,
        () -> grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto));

    verify(identityClient).removeRoleIfPresent(ADMIN_ID, GROUP_CHAT_CONSULTANT.getValue());
  }
}
