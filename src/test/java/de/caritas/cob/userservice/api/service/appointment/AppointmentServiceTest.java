package de.caritas.cob.userservice.api.service.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.config.apiclient.AppointmentAgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.AppointmentAskerServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.AppointmentConsultantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.appointmentservice.generated.ApiClient;
import de.caritas.cob.userservice.appointmentservice.generated.web.AgencyApi;
import de.caritas.cob.userservice.appointmentservice.generated.web.AskerApi;
import de.caritas.cob.userservice.appointmentservice.generated.web.ConsultantApi;
import de.caritas.cob.userservice.appointmentservice.generated.web.model.AgencyConsultantSyncRequestDTO;
import de.caritas.cob.userservice.appointmentservice.generated.web.model.AskerDTO;
import de.caritas.cob.userservice.appointmentservice.generated.web.model.CalcomUser;
import de.caritas.cob.userservice.appointmentservice.generated.web.model.ConsultantDTO;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(
    strictness = Strictness.LENIENT) // To allow "UnnecessaryStubbing" to keep tests clean
class AppointmentServiceTest {

  private static final String FIELD_NAME_APPOINTMENTS_ENABLED = "appointmentFeatureEnabled";
  private static final EasyRandom easyRandom = new EasyRandom();

  private StubConsultantApi appointmentConsultantApi;
  private StubAgencyApi appointmentAgencyApi;
  private StubAskerApi appointmentAskerApi;
  private AppointmentService appointmentService;
  private AppointmentService nonSpiedAppointmentService;

  @Mock SecurityHeaderSupplier securityHeaderSupplier;

  @Mock TenantHeaderSupplier tenantHeaderSupplier;
  @Mock IdentityClient identityClient;

  @SuppressWarnings("unused")
  @Mock
  IdentityClientConfig identityClientConfig;

  @Mock Logger log;

  @Mock ConsultantDTO consultantDTO;

  @Mock ConsultantAdminResponseDTO consultantAdminResponseDTO;

  @Mock IdentityLogin identityLogin;

  @Mock org.springframework.http.HttpHeaders httpHeaders;

  @Mock ObjectMapper objectMapper;

  @Mock HttpClientErrorException httpClientErrorException;

  @BeforeEach
  public void beforeEach() throws JsonProcessingException {
    appointmentConsultantApi = new StubConsultantApi();
    appointmentAgencyApi = new StubAgencyApi();
    appointmentAskerApi = new StubAskerApi();
    appointmentService = spy(createAppointmentService());
    nonSpiedAppointmentService = createAppointmentService();

    when(identityClient.loginUser(any(), any())).thenReturn(identityLogin);
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(any())).thenReturn(httpHeaders);
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders()).thenReturn(httpHeaders);
    when(consultantDTO.getId()).thenReturn("testId");
    when(objectMapper.readValue(
            nullable(String.class), ArgumentMatchers.<Class<ConsultantDTO>>any()))
        .thenReturn(consultantDTO);
    when(appointmentService.getObjectMapper(anyBoolean())).thenReturn(objectMapper);
  }

  private AppointmentService createAppointmentService() {
    return new AppointmentService(
        new StubAppointmentConsultantServiceApiControllerFactory(appointmentConsultantApi),
        new StubAppointmentAgencyServiceApiControllerFactory(appointmentAgencyApi),
        new StubAppointmentAskerServiceApiControllerFactory(appointmentAskerApi),
        securityHeaderSupplier,
        tenantHeaderSupplier,
        identityClient,
        identityClientConfig);
  }

  @Test
  void createConsultant_Should_NotCallAppointmentService_WhenAppointmentsIsDisabled() {
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, false);
    appointmentService.createConsultant(consultantAdminResponseDTO);
    assertThat(appointmentConsultantApi.createConsultantCallCount.get()).isZero();
    assertThat(appointmentConsultantApi.createConsultantWithHttpInfoCallCount.get()).isZero();
  }

  @Test
  void updateConsultant_Should_NotCallAppointmentService_WhenAppointmentsIsDisabled() {
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, false);
    appointmentService.updateConsultant(consultantAdminResponseDTO);
    assertThat(appointmentConsultantApi.updateConsultantCallCount.get()).isZero();
    assertThat(appointmentConsultantApi.updateConsultantWithHttpInfoCallCount.get()).isZero();
  }

  @Test
  void deleteConsultant_Should_NotCallAppointmentService_WhenAppointmentsIsDisabled() {
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, false);
    appointmentService.deleteConsultant("testId");
    assertThat(appointmentConsultantApi.deleteConsultantCallCount.get()).isZero();
    assertThat(appointmentConsultantApi.deleteConsultantWithHttpInfoCallCount.get()).isZero();
  }

  @Test
  void
      deleteConsultant_Should_ProceedWithDeletion_WhenAppointmentsIsEnabledAndConsultantNotFoundInAppointmentService() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    when(httpClientErrorException.getStatusCode()).thenReturn(HttpStatus.NOT_FOUND);
    appointmentConsultantApi.deleteConsultantException = httpClientErrorException;
    appointmentService.deleteConsultant("testId");
    assertThat(appointmentConsultantApi.deleteConsultantCallCount.get()).isEqualTo(1);
  }

  @Test
  void
      deleteConsultant_Should_ProceedWithDeletion_WhenAppointmentsIsEnabledAndAppointmentServiceThrowsExceptionOtherThan404() {
    var identityClientConfig = easyRandom.nextObject(IdentityConfig.class);
    setField(nonSpiedAppointmentService, "identityClientConfig", identityClientConfig);
    setField(nonSpiedAppointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    when(httpClientErrorException.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
    appointmentConsultantApi.deleteConsultantException = httpClientErrorException;
    assertThrows(
        HttpClientErrorException.class,
        () -> nonSpiedAppointmentService.deleteConsultant("testId"));
  }

  @Test
  void syncAgencies_Should_NotCallAppointmentService_WhenAppointmentsIsDisabled() {
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, false);
    appointmentService.syncAgencies("testId", new LinkedList<>());
    assertThat(appointmentAgencyApi.agencyConsultantsSyncCallCount.get()).isZero();
  }

  @Test
  void createConsultant_Should_CallAppointmentService_WhenAppointmentsIsDisabled() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    appointmentService.createConsultant(consultantAdminResponseDTO);
    assertThat(appointmentConsultantApi.createConsultantCallCount.get()).isEqualTo(1);
  }

  @Test
  void updateConsultant_Should_CallAppointmentService_WhenAppointmentsIsDisabled() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    appointmentService.updateConsultant(consultantAdminResponseDTO);
    assertThat(appointmentConsultantApi.updateConsultantCallCount.get()).isEqualTo(1);
  }

  @Test
  void deleteConsultant_Should_CallAppointmentService_WhenAppointmentsIsDisabled() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    appointmentService.deleteConsultant("testId");
    assertThat(appointmentConsultantApi.deleteConsultantCallCount.get()).isEqualTo(1);
  }

  @Test
  void syncAgencies_Should_CallAppointmentService_WhenAppointmentsIsDisabled() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    appointmentService.syncAgencies("testId", new LinkedList<>());
    assertThat(appointmentAgencyApi.agencyConsultantsSyncCallCount.get()).isEqualTo(1);
  }

  private void givenAnIdentityClientConfig() {
    var identityClientConfig = easyRandom.nextObject(IdentityConfig.class);
    setField(appointmentService, "identityClientConfig", identityClientConfig);
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-06
  // ---------------------------------------------------------------------------

  @Test
  void createConsultant_Should_NotCallAppointmentService_WhenConsultantAdminResponseDtoIsNull() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    appointmentService.createConsultant(null);
    assertThat(appointmentConsultantApi.createConsultantCallCount.get()).isZero();
  }

  @Test
  void syncConsultantData_Should_CallUpdateConsultant_When_ConsultantIsGiven() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    var consultant = new Consultant();
    consultant.setId("consultantId");
    consultant.setFirstName("Firstname");
    consultant.setLastName("Lastname");
    consultant.setEmail("mail@example.com");
    consultant.setAbsent(true);

    appointmentService.syncConsultantData(consultant);

    assertThat(appointmentConsultantApi.updateConsultantCallCount.get()).isEqualTo(1);
  }

  @Test
  void updateConsultant_Should_SwallowException_When_MapperThrows() throws JsonProcessingException {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    when(objectMapper.readValue(
            nullable(String.class), ArgumentMatchers.<Class<ConsultantDTO>>any()))
        .thenThrow(new JsonProcessingException("boom") {});

    appointmentService.updateConsultant(consultantAdminResponseDTO);

    assertThat(appointmentConsultantApi.updateConsultantCallCount.get()).isZero();
  }

  @Test
  void deleteConsultant_Should_NotCallAppointmentService_When_ConsultantIdIsNull() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    appointmentService.deleteConsultant(null);
    assertThat(appointmentConsultantApi.deleteConsultantCallCount.get()).isZero();
  }

  @Test
  void deleteConsultant_Should_NotCallAppointmentService_When_ConsultantIdIsEmpty() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    appointmentService.deleteConsultant("");
    assertThat(appointmentConsultantApi.deleteConsultantCallCount.get()).isZero();
  }

  @Test
  void deleteAsker_Should_NotCallAppointmentService_WhenAppointmentsIsDisabled() {
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, false);
    appointmentService.deleteAsker("askerId");
    assertThat(appointmentAskerApi.deleteAskerDataCallCount.get()).isZero();
  }

  @Test
  void deleteAsker_Should_CallAppointmentService_WhenAppointmentsIsEnabled() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);

    appointmentService.deleteAsker("askerId");

    assertThat(appointmentAskerApi.deleteAskerDataCallCount.get()).isEqualTo(1);
  }

  @Test
  void updateAskerEmail_Should_NotCallAppointmentService_WhenAppointmentsIsDisabled() {
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, false);
    appointmentService.updateAskerEmail("askerId", "mail@example.com");
    assertThat(appointmentAskerApi.updateAskerEmailCallCount.get()).isZero();
  }

  @Test
  void updateAskerEmail_Should_CallAppointmentService_WhenAppointmentsIsEnabled() {
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);

    appointmentService.updateAskerEmail("askerId", "mail@example.com");

    assertThat(appointmentAskerApi.updateAskerEmailCallCount.get()).isEqualTo(1);
    assertThat(appointmentAskerApi.lastUpdateAskerId).isEqualTo("askerId");
  }

  @Test
  void updateAskerEmail_Should_SwallowException_When_ApiThrows() {
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    appointmentAskerApi.updateAskerEmailException = new RuntimeException("boom");

    appointmentService.updateAskerEmail("askerId", "mail@example.com");

    assertThat(appointmentAskerApi.updateAskerEmailCallCount.get()).isEqualTo(1);
  }

  @Test
  void patchConsultant_Should_NotCallAppointmentService_WhenAppointmentsIsDisabled() {
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, false);
    appointmentService.patchConsultant("consultantId", "New Name");
    assertThat(appointmentConsultantApi.patchConsultantCallCount.get()).isZero();
  }

  @Test
  void patchConsultant_Should_NotCallAppointmentService_When_ConsultantIdIsEmpty() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    appointmentService.patchConsultant("", "New Name");
    assertThat(appointmentConsultantApi.patchConsultantCallCount.get()).isZero();
  }

  @Test
  void patchConsultant_Should_CallAppointmentService_WhenAppointmentsIsEnabled() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);

    appointmentService.patchConsultant("consultantId", "New Name");

    assertThat(appointmentConsultantApi.patchConsultantCallCount.get()).isEqualTo(1);
    assertThat(appointmentConsultantApi.lastPatchConsultantId).isEqualTo("consultantId");
  }

  @Test
  void patchConsultant_Should_ProceedSilently_When_AppointmentServiceThrows404() {
    givenAnIdentityClientConfig();
    setField(appointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    when(httpClientErrorException.getStatusCode()).thenReturn(HttpStatus.NOT_FOUND);
    appointmentConsultantApi.patchConsultantException = httpClientErrorException;

    appointmentService.patchConsultant("consultantId", "New Name");

    assertThat(appointmentConsultantApi.patchConsultantCallCount.get()).isEqualTo(1);
  }

  @Test
  void patchConsultant_Should_RethrowException_When_AppointmentServiceThrowsNon404() {
    var identityClientConfig = easyRandom.nextObject(IdentityConfig.class);
    setField(nonSpiedAppointmentService, "identityClientConfig", identityClientConfig);
    setField(nonSpiedAppointmentService, FIELD_NAME_APPOINTMENTS_ENABLED, true);
    when(httpClientErrorException.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
    appointmentConsultantApi.patchConsultantException = httpClientErrorException;

    assertThrows(
        HttpClientErrorException.class,
        () -> nonSpiedAppointmentService.patchConsultant("consultantId", "New Name"));
  }

  static final class StubConsultantApi extends ConsultantApi {

    final AtomicInteger createConsultantCallCount = new AtomicInteger();
    final AtomicInteger createConsultantWithHttpInfoCallCount = new AtomicInteger();
    final AtomicInteger updateConsultantCallCount = new AtomicInteger();
    final AtomicInteger updateConsultantWithHttpInfoCallCount = new AtomicInteger();
    final AtomicInteger deleteConsultantCallCount = new AtomicInteger();
    final AtomicInteger deleteConsultantWithHttpInfoCallCount = new AtomicInteger();
    final AtomicInteger patchConsultantCallCount = new AtomicInteger();
    HttpClientErrorException deleteConsultantException;
    HttpClientErrorException patchConsultantException;
    String lastPatchConsultantId;

    StubConsultantApi() {
      super(new ApiClient());
    }

    @Override
    public CalcomUser createConsultant(ConsultantDTO consultantDTO) {
      createConsultantCallCount.incrementAndGet();
      return new CalcomUser();
    }

    @Override
    public ResponseEntity<CalcomUser> createConsultantWithHttpInfo(ConsultantDTO consultantDTO) {
      createConsultantWithHttpInfoCallCount.incrementAndGet();
      return ResponseEntity.ok(new CalcomUser());
    }

    @Override
    public CalcomUser updateConsultant(String consultantId, ConsultantDTO consultantDTO) {
      updateConsultantCallCount.incrementAndGet();
      return new CalcomUser();
    }

    @Override
    public ResponseEntity<CalcomUser> updateConsultantWithHttpInfo(
        String consultantId, ConsultantDTO consultantDTO) {
      updateConsultantWithHttpInfoCallCount.incrementAndGet();
      return ResponseEntity.ok(new CalcomUser());
    }

    @Override
    public void deleteConsultant(String consultantId) {
      deleteConsultantCallCount.incrementAndGet();
      if (deleteConsultantException != null) {
        throw deleteConsultantException;
      }
    }

    @Override
    public ResponseEntity<Void> deleteConsultantWithHttpInfo(String consultantId) {
      deleteConsultantWithHttpInfoCallCount.incrementAndGet();
      return ResponseEntity.noContent().build();
    }

    @Override
    public void patchConsultant(String consultantId, ConsultantDTO consultantDTO) {
      patchConsultantCallCount.incrementAndGet();
      lastPatchConsultantId = consultantId;
      if (patchConsultantException != null) {
        throw patchConsultantException;
      }
    }
  }

  static final class StubAgencyApi extends AgencyApi {

    final AtomicInteger agencyConsultantsSyncCallCount = new AtomicInteger();

    StubAgencyApi() {
      super(new ApiClient());
    }

    @Override
    public void agencyConsultantsSync(AgencyConsultantSyncRequestDTO request) {
      agencyConsultantsSyncCallCount.incrementAndGet();
    }
  }

  static final class StubAskerApi extends AskerApi {

    final AtomicInteger deleteAskerDataCallCount = new AtomicInteger();
    final AtomicInteger updateAskerEmailCallCount = new AtomicInteger();
    RuntimeException updateAskerEmailException;
    String lastUpdateAskerId;

    StubAskerApi() {
      super(new ApiClient());
    }

    @Override
    public void deleteAskerData(String askerId) {
      deleteAskerDataCallCount.incrementAndGet();
    }

    @Override
    public void updateAskerEmail(String askerId, AskerDTO askerDTO) {
      updateAskerEmailCallCount.incrementAndGet();
      lastUpdateAskerId = askerId;
      if (updateAskerEmailException != null) {
        throw updateAskerEmailException;
      }
    }
  }

  static final class StubAppointmentConsultantServiceApiControllerFactory
      extends AppointmentConsultantServiceApiControllerFactory {

    private final ConsultantApi api;

    StubAppointmentConsultantServiceApiControllerFactory(ConsultantApi api) {
      this.api = api;
    }

    @Override
    public ConsultantApi createControllerApi() {
      return api;
    }
  }

  static final class StubAppointmentAgencyServiceApiControllerFactory
      extends AppointmentAgencyServiceApiControllerFactory {

    private final AgencyApi api;

    StubAppointmentAgencyServiceApiControllerFactory(AgencyApi api) {
      this.api = api;
    }

    @Override
    public AgencyApi createControllerApi() {
      return api;
    }
  }

  static final class StubAppointmentAskerServiceApiControllerFactory
      extends AppointmentAskerServiceApiControllerFactory {

    private final AskerApi api;

    StubAppointmentAskerServiceApiControllerFactory(AskerApi api) {
      this.api = api;
    }

    @Override
    public AskerApi createControllerApi() {
      return api;
    }
  }
}
