package de.caritas.cob.userservice.api.service.notification;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class DefaultDpaSigningEmailDispatchServiceTest {

  @Mock private SecurityHeaderSupplier securityHeaderSupplier;
  @Mock private TenantHeaderSupplier tenantHeaderSupplier;

  @Mock
  private de.caritas.cob.userservice.api.port.out.IdentityAuthentication identityAuthentication;

  @Mock private de.caritas.cob.userservice.api.port.out.IdentityClientConfig identityClientConfig;

  private MockRestServiceServer server;
  private DefaultDpaSigningEmailDispatchService service;

  @BeforeEach
  void setUp() {
    RestTemplate restTemplate = new RestTemplate();
    server = MockRestServiceServer.bindTo(restTemplate).build();
    service =
        new DefaultDpaSigningEmailDispatchService(
            restTemplate,
            securityHeaderSupplier,
            tenantHeaderSupplier,
            identityAuthentication,
            identityClientConfig,
            "http://consulting-type.example/service");
  }

  @Test
  void send_forwardsAuthenticatedFixedDpaPayload() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("tenant-admin-token");
    when(securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders()).thenReturn(headers);
    server
        .expect(
            requestTo("http://consulting-type.example/service/settingsadmin/dpa-signing-emails"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer tenant-admin-token"))
        .andExpect(jsonPath("$.recipientEmail").value("bart.simpson@oriso.org"))
        .andExpect(jsonPath("$.tenantName").value("E2E Full Gate 202607191747"))
        .andExpect(
            jsonPath("$.signLink").value("https://app.oriso-dev.site/dpa-sign/single-use-token"))
        .andExpect(jsonPath("$.expiresAt").value("2026-08-03T13:27:28.243207790"))
        .andRespond(withNoContent());

    service.send(
        "bart.simpson@oriso.org",
        "E2E Full Gate 202607191747",
        "https://app.oriso-dev.site/dpa-sign/single-use-token",
        LocalDateTime.parse("2026-08-03T13:27:28.243207790"));

    verify(tenantHeaderSupplier).addTenantHeader(headers);
    server.verify();
  }

  /**
   * The PUBLIC onboarding forward (ORISO-Admin#722) has no session, so the dispatch falls back to
   * the Keycloak technical user instead of sending an unauthenticated request that the dispatch
   * endpoint would reject.
   */
  @Test
  void send_authenticatesAsTheTechnicalUser_When_thereIsNoSession() {
    when(securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders())
        .thenReturn(new HttpHeaders());
    var technicalUser = new de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig();
    technicalUser.setUsername("technical");
    technicalUser.setPassword("secret");
    when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    when(identityAuthentication.login("technical", "secret"))
        .thenReturn(
            new de.caritas.cob.userservice.api.port.out.IdentityLogin("tech-token", 0, 0, null));
    HttpHeaders technicalHeaders = new HttpHeaders();
    technicalHeaders.setBearerAuth("tech-token");
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders("tech-token"))
        .thenReturn(technicalHeaders);
    server
        .expect(
            requestTo("http://consulting-type.example/service/settingsadmin/dpa-signing-emails"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer tech-token"))
        .andRespond(withNoContent());

    service.send(
        "legal@example.org",
        "Träger Nord e.V.",
        "https://app.oriso.org/dpa-sign/single-use-token",
        LocalDateTime.parse("2026-08-29T14:31:07"));

    server.verify();
  }
}
