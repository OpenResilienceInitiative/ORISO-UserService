package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static de.caritas.cob.userservice.api.config.auth.UserRole.CONSULTANT;
import static de.caritas.cob.userservice.api.config.auth.UserRole.GROUP_CHAT_CONSULTANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.ConsultantService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class GrantConsultantIdentityServiceTest {

  private static final String ADMIN_ID = "admin-uuid-1";
  private static final String ADMIN_USERNAME = "adminUsername";
  private static final String ADMIN_RC_ID = "existingRcUserId";
  private static final String MATRIX_USER_ID = "@adminUsername:matrix";

  @InjectMocks private GrantConsultantIdentityService grantConsultantIdentityService;

  @Mock private AdminRepository adminRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private de.caritas.cob.userservice.api.port.out.IdentityClient identityClient;
  @Mock private RocketChatService rocketChatService;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private ConsultantService consultantService;
  @Mock private ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;
  @Mock private UserHelper userHelper;

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
        .rcUserId(ADMIN_RC_ID)
        .tenantId(1L)
        .build();
  }

  private void stubHappyMatrix() throws Exception {
    var matrixResponse = new MatrixCreateUserResponseDTO();
    matrixResponse.setUserId(MATRIX_USER_ID);
    when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(matrixResponse));
    when(userHelper.getRandomPassword()).thenReturn("randomPw");
  }

  @Test
  void throwBadRequest_When_adminNotFound() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.empty());

    assertThrows(
        BadRequestException.class,
        () -> grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto));

    verify(identityClient, never()).ensureRole(anyString(), anyString());
    verify(consultantService, never()).saveConsultant(any());
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
    verify(identityClient, never()).ensureRole(anyString(), anyString());
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
    verify(identityClient, never()).ensureRole(anyString(), anyString());
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

    verify(identityClient).ensureRole(ADMIN_ID, CONSULTANT.getValue());

    ArgumentCaptor<Consultant> consultantCaptor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(consultantCaptor.capture());
    Consultant saved = consultantCaptor.getValue();
    assertThat(saved.getId(), is(ADMIN_ID));
    assertThat(saved.getEmail(), is("admin@example.com"));

    assertThat(response, notNullValue());
    assertThat(response.getEmbedded(), notNullValue());
    assertThat(response.getEmbedded().getId(), is(ADMIN_ID));
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

    verify(identityClient).ensureRole(ADMIN_ID, CONSULTANT.getValue());
    verify(identityClient).ensureRole(ADMIN_ID, GROUP_CHAT_CONSULTANT.getValue());
  }

  @Test
  void reuseAdminRcUserId_When_present() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    stubHappyMatrix();
    when(consultantService.saveConsultant(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    grantConsultantIdentityService.grantConsultantIdentityToAdmin(ADMIN_ID, dto);

    verify(rocketChatService, never()).getUserID(anyString(), anyString(), anyBoolean());

    ArgumentCaptor<Consultant> consultantCaptor = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantService).saveConsultant(consultantCaptor.capture());
    assertThat(consultantCaptor.getValue().getRocketChatId(), is(ADMIN_RC_ID));
  }

  @Test
  void continueDespiteMatrixFailure() throws Exception {
    when(adminRepository.findById(ADMIN_ID)).thenReturn(Optional.of(validAdmin()));
    when(consultantRepository.findByIdAndDeleteDateIsNull(ADMIN_ID)).thenReturn(Optional.empty());
    when(consultantRepository.findByUsernameAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    when(userHelper.getRandomPassword()).thenReturn("randomPw");
    when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
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
}
