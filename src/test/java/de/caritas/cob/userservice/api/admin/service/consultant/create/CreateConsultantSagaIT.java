package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static de.caritas.cob.userservice.api.config.auth.UserRole.CONSULTANT;
import static de.caritas.cob.userservice.api.config.auth.UserRole.GROUP_CHAT_CONSULTANT;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.EMAIL_NOT_VALID;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.PASSWORD_NOT_VALID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hibernate.validator.internal.util.CollectionHelper.asSet;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.ConsultantImportService.ImportRecord;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.Settings;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
public class CreateConsultantSagaIT {

  private static final String VALID_USERNAME = "validUsername";
  private static final String VALID_EMAILADDRESS = "valid@emailaddress.de";
  private static final long TENANT_ID = 1L;

  @Autowired private CreateConsultantSaga createConsultantSaga;

  @MockitoBean private KeycloakService keycloakService;

  @MockitoBean private MatrixSynapseService matrixSynapseService;

  @MockitoBean private TenantAdminService tenantAdminService;

  @MockitoBean private RollbackFacade rollbackFacade;

  @MockitoBean private AppointmentService appointmentService;

  private final EasyRandom easyRandom = new EasyRandom();

  @BeforeEach
  public void setup() {
    ReflectionTestUtils.setField(createConsultantSaga, "appointmentFeatureEnabled", false);
  }

  @Test
  public void createNewConsultant_Should_returnExpectedCreatedConsultant_When_inputDataIsCorrect() {
    when(keycloakService.createKeycloakUser(any(), anyString(), any()))
        .thenReturn(easyRandom.nextObject(KeycloakCreateUserResponseDTO.class));
    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setUsername(VALID_USERNAME);
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setIsGroupchatConsultant(false);

    var consultantAdminResponseDTO =
        this.createConsultantSaga.createNewConsultant(createConsultantDTO);

    ConsultantDTO consultant = consultantAdminResponseDTO.getEmbedded();
    verify(keycloakService).assignRoles(anyString(), eq(asSet(CONSULTANT.getValue())));

    assertThat(consultant, notNullValue());
    assertThat(consultant.getId(), notNullValue());
    assertThat(consultant.getAbsenceMessage(), notNullValue());
    assertThat(consultant.getCreateDate(), notNullValue());
    assertThat(consultant.getUpdateDate(), notNullValue());
    assertThat(consultant.getUsername(), notNullValue());
    assertThat(consultant.getFirstname(), notNullValue());
    assertThat(consultant.getLastname(), notNullValue());
    assertThat(consultant.getEmail(), notNullValue());
  }

  @Test
  public void createNewConsultant_Should_callRollback_When_AppointmentServiceThrowsException() {
    ReflectionTestUtils.setField(createConsultantSaga, "appointmentFeatureEnabled", true);
    doThrow(BadRequestException.class).when(appointmentService).createConsultant(any());
    when(keycloakService.createKeycloakUser(any(), anyString(), any()))
        .thenReturn(easyRandom.nextObject(KeycloakCreateUserResponseDTO.class));
    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setUsername(VALID_USERNAME);
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setIsGroupchatConsultant(false);

    try {
      this.createConsultantSaga.createNewConsultant(createConsultantDTO);
      fail("Exception should be thrown");
    } catch (DistributedTransactionException ex) {
      assertThat(
          ex.getCustomHttpHeaders().get("X-Reason").get(0),
          is(
              "DISTRIBUTED_TRANSACTION_FAILED_ON_STEP_CREATE_ACCOUNT_IN_CALCOM_OR_APPOINTMENTSERVICE"));
      verify(keycloakService).assignRoles(anyString(), eq(asSet(CONSULTANT.getValue())));
      verify(rollbackFacade).rollbackConsultantAccount(Mockito.any(Consultant.class));
    }
  }

  @Test
  public void createNewConsultant_Should_callRollback_When_KeycloakUpdatePasswordThrowsException() {
    when(keycloakService.createKeycloakUser(any(), anyString(), any()))
        .thenReturn(easyRandom.nextObject(KeycloakCreateUserResponseDTO.class));
    doThrow(new CustomValidationHttpStatusException(PASSWORD_NOT_VALID, HttpStatus.BAD_REQUEST))
        .when(keycloakService)
        .updatePassword(any(), any());
    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setUsername(VALID_USERNAME);
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setIsGroupchatConsultant(false);

    try {
      this.createConsultantSaga.createNewConsultant(createConsultantDTO);
      fail("Exception should be thrown");
    } catch (CustomValidationHttpStatusException ex) {
      assertThat(ex.getCustomHttpHeaders().get("X-Reason").get(0), is("PASSWORD_NOT_VALID"));
      verify(keycloakService, Mockito.never()).assignRoles(anyString(), any());
      verify(rollbackFacade).rollbackConsultantAccount(Mockito.any(Consultant.class));
    }
  }

  @Test
  public void createNewConsultant_Should_callRollback_When_KeycloakUpdateRoleThrowsException() {
    when(keycloakService.createKeycloakUser(any(), anyString(), any()))
        .thenReturn(easyRandom.nextObject(KeycloakCreateUserResponseDTO.class));
    doThrow(BadRequestException.class).when(keycloakService).assignRoles(anyString(), any());
    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setUsername(VALID_USERNAME);
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setIsGroupchatConsultant(false);

    try {
      this.createConsultantSaga.createNewConsultant(createConsultantDTO);
      fail("Exception should be thrown");
    } catch (DistributedTransactionException ex) {
      assertThat(
          ex.getCustomHttpHeaders().get("X-Reason").get(0),
          is("DISTRIBUTED_TRANSACTION_FAILED_ON_STEP_UPDATE_USER_ROLES_IN_KEYCLOAK"));
      verify(rollbackFacade).rollbackConsultantAccount(Mockito.any(Consultant.class));
    }
  }

  @Test
  public void
      createNewConsultant_Should_addConsultantAndGroupChatConsultantRole_When_isGroupChatConsultantFlagIsEnabled() {
    // given
    when(keycloakService.createKeycloakUser(any(), anyString(), any()))
        .thenReturn(easyRandom.nextObject(KeycloakCreateUserResponseDTO.class));
    var tenant = new TenantDTO().settings(new Settings().featureGroupChatV2Enabled(false));
    when(tenantAdminService.getTenantById((long) TENANT_ID)).thenReturn(tenant);

    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setTenantId(TENANT_ID);
    createConsultantDTO.setUsername(VALID_USERNAME);
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setIsGroupchatConsultant(true);

    // when
    var consultantAdminResponseDTO = createConsultantSaga.createNewConsultant(createConsultantDTO);

    // then
    verify(keycloakService)
        .assignRoles(
            anyString(), eq(asSet(CONSULTANT.getValue(), GROUP_CHAT_CONSULTANT.getValue())));

    assertThat(consultantAdminResponseDTO.getEmbedded(), notNullValue());
    assertThat(consultantAdminResponseDTO.getEmbedded().getId(), notNullValue());
  }

  @Test
  public void
      createNewConsultant_Should_returnExpectedCreatedConsultant_When_inputDataIsCorrectImportRecord() {
    when(keycloakService.createKeycloakUser(any(), anyString(), any()))
        .thenReturn(easyRandom.nextObject(KeycloakCreateUserResponseDTO.class));
    ImportRecord importRecord = this.easyRandom.nextObject(ImportRecord.class);
    importRecord.setUsername(VALID_USERNAME);
    importRecord.setEmail(VALID_EMAILADDRESS);

    Consultant consultant =
        this.createConsultantSaga.createNewConsultant(importRecord, asSet(CONSULTANT.getValue()));

    assertThat(consultant, notNullValue());
    assertThat(consultant.getId(), notNullValue());
    assertThat(consultant.getMatrixUserId(), is((String) null));
    verify(keycloakService).assignRoles(anyString(), eq(asSet(CONSULTANT.getValue())));
    assertThat(consultant.getAbsenceMessage(), notNullValue());
    assertThat(consultant.getCreateDate(), notNullValue());
    assertThat(consultant.getUpdateDate(), notNullValue());
    assertThat(consultant.getUsername(), notNullValue());
    assertThat(consultant.getFirstName(), notNullValue());
    assertThat(consultant.getLastName(), notNullValue());
    assertThat(consultant.getEmail(), notNullValue());
    assertThat(consultant.getFullName(), notNullValue());
  }

  @Test
  public void
      createNewConsultant_Should_throwCustomValidationHttpStatusException_When_keycloakIdIsMissing() {
    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> {
          KeycloakCreateUserResponseDTO keycloakResponse =
              easyRandom.nextObject(KeycloakCreateUserResponseDTO.class);
          keycloakResponse.setUserId(null);
          when(keycloakService.createKeycloakUser(any(), anyString(), any()))
              .thenReturn(keycloakResponse);
          CreateConsultantDTO createConsultantDTO =
              this.easyRandom.nextObject(CreateConsultantDTO.class);

          this.createConsultantSaga.createNewConsultant(createConsultantDTO);
        });
  }

  @Test
  public void createNewConsultant_Should_throwExpectedException_When_emailIsInvalid() {
    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setEmail("invalid");

    try {
      this.createConsultantSaga.createNewConsultant(createConsultantDTO);
      fail("Exception should be thrown");
    } catch (CustomValidationHttpStatusException e) {
      assertThat(e.getCustomHttpHeaders(), notNullValue());
      assertThat(e.getCustomHttpHeaders().get("X-Reason").get(0), is(EMAIL_NOT_VALID.name()));
    }
  }
}
