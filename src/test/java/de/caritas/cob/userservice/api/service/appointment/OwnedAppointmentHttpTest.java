package de.caritas.cob.userservice.api.service.appointment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.config.apiclient.*;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.httpheader.*;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/** Real generated HTTP boundary; this is not live AppointmentService acceptance. */
class OwnedAppointmentHttpTest {
  private static final String ACCOUNT = "72000000-0000-4000-8000-000000000021";
  private static final String URL = "https://appointment.fixture.invalid";
  private AppointmentService service;
  private MockRestServiceServer server;
  private IdentityClientConfig config;
  private IdentityAuthentication legacy;
  private TenantHeaderSupplier ambient;

  @BeforeEach
  void setup() {
    var http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    var factory = new AppointmentConsultantServiceApiControllerFactory();
    ReflectionTestUtils.setField(factory, "restTemplate", http);
    ReflectionTestUtils.setField(factory, "appointmentServiceApiUrl", URL);
    var human = mock(AuthenticatedUser.class);
    when(human.getAccessToken()).thenReturn("unrelated-human-token");
    var headers = new SecurityHeaderSupplier(human);
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-TOKEN");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-TOKEN");
    config = mock(IdentityClientConfig.class);
    legacy = mock(IdentityAuthentication.class);
    ambient = mock(TenantHeaderSupplier.class);
    when(config.isFiveActorMode()).thenReturn(true);
    when(config.taskAccessToken(TaskPurpose.APPOINTMENT_SYNC)).thenReturn("runtime-sync-token");
    when(config.taskAccessToken(TaskPurpose.APPOINTMENT_CLEANUP))
        .thenReturn("runtime-cleanup-token");
    service =
        new AppointmentService(
            factory,
            mock(AppointmentAgencyServiceApiControllerFactory.class),
            mock(AppointmentAskerServiceApiControllerFactory.class),
            headers,
            ambient,
            legacy,
            config);
    ReflectionTestUtils.setField(service, "appointmentFeatureEnabled", true);
    TenantContext.setCurrentTenant(999L);
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    verifyNoInteractions(legacy, ambient);
  }

  private ConsultantAdminResponseDTO payload() {
    return new ConsultantAdminResponseDTO()
        .embedded(
            new ConsultantDTO()
                .id(ACCOUNT)
                .firstname("Ada")
                .lastname("Lovelace")
                .email("ada@fixture.invalid"));
  }

  private org.springframework.test.web.client.ResponseActions expectCreate() {
    return server
        .expect(requestTo(URL + "/consultants"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer runtime-sync-token"))
        .andExpect(header("tenantId", "7"))
        .andExpect(
            header(
                "X-CSRF-TOKEN",
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
        .andExpect(
            content()
                .json(
                    "{\"id\":\""
                        + ACCOUNT
                        + "\",\"firstname\":\"Ada\",\"lastname\":\"Lovelace\",\"email\":\"ada@fixture.invalid\"}"));
  }

  private org.springframework.test.web.client.ResponseActions expectDelete() {
    return server
        .expect(requestTo(URL + "/consultants/" + ACCOUNT))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header("Authorization", "Bearer runtime-cleanup-token"))
        .andExpect(header("tenantId", "7"));
  }

  @Test
  void creationUsesCapturedTenantAccountAndRuntimeSyncPurpose() {
    expectCreate().andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    assertDoesNotThrow(() -> service.createOwnedConsultant(payload(), 7L));
    server.verify();
    verify(config).taskAccessToken(TaskPurpose.APPOINTMENT_SYNC);
    verify(config, never()).taskAccessToken(TaskPurpose.APPOINTMENT_CLEANUP);
    assertEquals(999L, TenantContext.getCurrentTenant());
  }

  @Test
  void creationAcceptedIsUnknownUntilExact200() {
    expectCreate().andRespond(withStatus(HttpStatus.ACCEPTED));
    assertThrows(IllegalStateException.class, () -> service.createOwnedConsultant(payload(), 7L));
    server.verify();
  }

  @Test
  void lostCreationReplyRemainsUnknown() {
    expectCreate()
        .andRespond(
            request -> {
              throw new IOException("Synthetic lost create reply");
            });
    assertThrows(IllegalStateException.class, () -> service.createOwnedConsultant(payload(), 7L));
    server.verify();
  }

  @Test
  void creationConflictIsNotNoEffectOrAcknowledgement() {
    expectCreate().andRespond(withStatus(HttpStatus.CONFLICT));
    assertThrows(IllegalStateException.class, () -> service.createOwnedConsultant(payload(), 7L));
    server.verify();
  }

  @Test
  void cleanupUsesCapturedTenantAndRuntimeCleanupPurpose() {
    expectDelete().andRespond(withSuccess());
    assertDoesNotThrow(() -> service.deleteOwnedCreationConsultant(ACCOUNT, 7L));
    server.verify();
    verify(config).taskAccessToken(TaskPurpose.APPOINTMENT_CLEANUP);
    verify(config, never()).taskAccessToken(TaskPurpose.APPOINTMENT_SYNC);
  }

  @Test
  void cleanup404AcknowledgesAbsenceOfOnlyTheCapturedAccount() {
    expectDelete().andRespond(withStatus(HttpStatus.NOT_FOUND));
    assertDoesNotThrow(() -> service.deleteOwnedCreationConsultant(ACCOUNT, 7L));
    server.verify();
  }

  @Test
  void cleanupAcceptedRemainsUnknown() {
    expectDelete().andRespond(withStatus(HttpStatus.ACCEPTED));
    assertThrows(
        IllegalStateException.class, () -> service.deleteOwnedCreationConsultant(ACCOUNT, 7L));
    server.verify();
  }

  @Test
  void cleanupServerFailureRemainsUnknown() {
    expectDelete().andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
    assertThrows(
        IllegalStateException.class, () -> service.deleteOwnedCreationConsultant(ACCOUNT, 7L));
    server.verify();
  }

  @Test
  void lostCleanupReplyRemainsUnknown() {
    expectDelete()
        .andRespond(
            request -> {
              throw new IOException("Synthetic lost delete reply");
            });
    assertThrows(
        IllegalStateException.class, () -> service.deleteOwnedCreationConsultant(ACCOUNT, 7L));
    server.verify();
  }

  @Test
  void ownedCallsRefuseAmbientSqlBeforeAnyAuthenticationOrHttp() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThrows(IllegalStateException.class, () -> service.createOwnedConsultant(payload(), 7L));
    assertThrows(
        IllegalStateException.class, () -> service.deleteOwnedCreationConsultant(ACCOUNT, 7L));
    verifyNoInteractions(config);
    server.verify();
  }
}
