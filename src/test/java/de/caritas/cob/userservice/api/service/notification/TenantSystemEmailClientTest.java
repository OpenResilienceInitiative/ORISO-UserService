package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.auth.TaskIdentityCredentials;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class TenantSystemEmailClientTest {
  @Mock IdentityAuthentication authentication;
  @Mock IdentityClientConfig identityConfig;
  @Mock SecurityHeaderSupplier headerSupplier;

  RestTemplate restTemplate;
  MockRestServiceServer server;
  TenantSystemEmailClient client;

  @BeforeEach
  void setUp() {
    restTemplate = new RestTemplate();
    server = MockRestServiceServer.bindTo(restTemplate).build();
    client =
        new TenantSystemEmailClient(restTemplate, authentication, identityConfig, headerSupplier);
    ReflectionTestUtils.setField(
        client, "tenantServiceApiUrl", "http://tenantservice.internal:8081");
    var account = new TaskIdentityCredentials();
    account.setClientId("technical");
    account.setClientSecret("test-secret");
    when(identityConfig.getTaskIdentity(org.mockito.ArgumentMatchers.any())).thenReturn(account);
    when(authentication.loginTask(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new IdentityLogin("technical-token", 60, 60, "refresh"));
    var headers = new HttpHeaders();
    headers.setBearerAuth("technical-token");
    when(headerSupplier.getKeycloakAndCsrfHttpHeaders("technical-token")).thenReturn(headers);
  }

  @Test
  void readsFreshRedactedTenantProjectionWithNotificationTask() {
    server
        .expect(
            once(),
            requestTo(
                "http://tenantservice.internal:8081/internal/tenants/40/system-email-context"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer technical-token"))
        .andRespond(
            withSuccess(
                "{\"id\":40,\"settings\":{\"smtpMode\":\"OWN\",\"smtp\":{\"configured\":true}}}",
                MediaType.APPLICATION_JSON));

    Map<String, Object> tenant = client.readTenant(40L);

    assertThat(tenant).containsEntry("id", 40).containsKey("settings");
    assertThat(tenant.toString()).doesNotContain("passwordSet", "password", "host", "username");
    org.mockito.Mockito.verify(identityConfig)
        .getTaskIdentity(
            de.caritas.cob.userservice.api.config.auth.TaskIdentity.NOTIFICATION_DISPATCH);
    server.verify();
  }

  @Test
  void postsExactlyOneBoundedOwnDeliveryWithTechnicalToken() {
    server
        .expect(
            once(),
            requestTo(
                "http://tenantservice.internal:8081/tenant/40/internal/system-email-deliveries"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer technical-token"))
        .andRespond(withSuccess());

    client.deliver(
        40L,
        "SUPERVISOR_ADDED",
        "recipient@example.org",
        new OrisoEmailRenderer.RenderedEmail("Subject", "<p>Body</p>", "Body"));

    server.verify();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"NEW_MESSAGE", "SELF_HELP_APPOINTMENT_REMINDER"})
  void durableDeliveryPassesTheStoredCorrelationIdToTheOwnTransport(String purpose) {
    UUID correlation = UUID.fromString("ab2e5141-2f26-456a-9e46-0ff642918115");
    server
        .expect(
            once(),
            requestTo(
                "http://tenantservice.internal:8081/tenant/40/internal/system-email-deliveries"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.purpose").value(purpose))
        .andExpect(jsonPath("$.correlationId").value(correlation.toString()))
        .andRespond(withSuccess());

    client.deliver(
        40L,
        purpose,
        "recipient@example.org",
        new OrisoEmailRenderer.RenderedEmail("Subject", "<p>Body</p>", "Body"),
        correlation);

    server.verify();
  }

  @Test
  void invalidOwnConfigurationDoesNotRetryOrUseAnotherTransport() {
    server
        .expect(
            once(),
            requestTo(
                "http://tenantservice.internal:8081/tenant/40/internal/system-email-deliveries"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

    assertThatThrownBy(
            () ->
                client.deliver(
                    40L,
                    "SUPERVISOR_ADDED",
                    "recipient@example.org",
                    new OrisoEmailRenderer.RenderedEmail("Subject", "<p>Body</p>", "Body")))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class)
        .hasMessageContaining("OWN tenant SMTP");
    server.verify();
  }

  @Test
  void a422FromTheWireIsAConfigurationErrorToo() {
    server
        .expect(
            once(),
            requestTo(
                "http://tenantservice.internal:8081/tenant/40/internal/system-email-deliveries"))
        .andRespond(withStatus(HttpStatus.valueOf(422)));

    assertThatThrownBy(
            () ->
                client.deliver(
                    40L,
                    "SUPERVISOR_ADDED",
                    "recipient@example.org",
                    new OrisoEmailRenderer.RenderedEmail("Subject", "<p>Body</p>", "Body")))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class);
    server.verify();
  }

  @Test
  void disabledOwnTransportIsNotReportedAsASentMail() {
    server
        .expect(
            once(),
            requestTo(
                "http://tenantservice.internal:8081/tenant/40/internal/system-email-deliveries"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    assertThatThrownBy(
            () ->
                client.deliver(
                    40L,
                    "NEW_MESSAGE",
                    "recipient@example.org",
                    new OrisoEmailRenderer.RenderedEmail("Subject", "<p>Body</p>", "Body")))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class)
        .hasMessageContaining("disabled");
    server.verify();
  }
}
