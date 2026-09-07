package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TenantSystemEmailDeliveryClientTest {
  final RestTemplate rest = new RestTemplate();
  final MockRestServiceServer server = MockRestServiceServer.createServer(rest);
  final IdentityAuthentication identity = mock(IdentityAuthentication.class);
  final IdentityClientConfig config = mock(IdentityClientConfig.class);
  final AuthenticatedUser requestUser = mock(AuthenticatedUser.class);
  final SecurityHeaderSupplier headers = new SecurityHeaderSupplier(requestUser);
  final TenantSystemEmailDeliveryClient client =
      new TenantSystemEmailDeliveryClient(rest, identity, config, headers);
  final OrisoEmailRenderer.RenderedEmail email =
      new OrisoEmailRenderer.RenderedEmail("Subject", "<p>Body</p>", "Body");

  @BeforeEach
  void setup() {
    var technical = new TechnicalUserConfig();
    technical.setUsername("synthetic-service");
    technical.setPassword("synthetic-password");
    when(config.getTechnicalUser()).thenReturn(technical);
    when(identity.login("synthetic-service", "synthetic-password"))
        .thenReturn(new IdentityLogin("synthetic-token", 60, 120, "synthetic-refresh"));
    when(requestUser.getAccessToken()).thenThrow(new IllegalStateException("No request scope"));
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-TOKEN");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-TOKEN");
    ReflectionTestUtils.setField(client, "baseUrl", "https://tenant.example.org/");
  }

  @Test
  void worksWithoutRequestContextAndSendsOnlyConstrainedContent() {
    server
        .expect(requestTo("https://tenant.example.org/tenant/40/internal/system-email-deliveries"))
        .andExpect(header("Authorization", "Bearer synthetic-token"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("EMAIL_ADDRESS_CHANGED"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password")),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("smtp")))))
        .andRespond(withSuccess());
    assertThat(
            client.send(
                40,
                TenantSystemEmailDeliveryClient.Purpose.EMAIL_ADDRESS_CHANGED,
                "recipient@example.org",
                email))
        .isTrue();
    verify(requestUser, never()).getAccessToken();
    verify(identity).logout("synthetic-refresh");
    server.verify();
  }

  @Test
  void disabledTenantIsNotClaimedAsSent() {
    server.expect(anything()).andRespond(withStatus(HttpStatus.NO_CONTENT));
    assertThat(
            client.send(
                40,
                TenantSystemEmailDeliveryClient.Purpose.EMAIL_ADDRESS_CHANGED,
                "recipient@example.org",
                email))
        .isFalse();
    server.verify();
    verify(identity).logout("synthetic-refresh");
  }

  @Test
  void ambiguousFailureIsSanitizedNotRetriedAndSessionClosed() {
    server
        .expect(anything())
        .andRespond(
            withStatus(HttpStatus.BAD_GATEWAY)
                .body("secret-fixture")
                .contentType(MediaType.TEXT_PLAIN));
    assertThatThrownBy(
            () ->
                client.send(
                    40,
                    TenantSystemEmailDeliveryClient.Purpose.EMAIL_ADDRESS_CHANGED,
                    "recipient@example.org",
                    email))
        .hasMessage("Tenant system email delivery unconfirmed")
        .hasNoCause();
    server.verify();
    verify(identity, times(1)).login(anyString(), anyString());
    verify(identity).logout("synthetic-refresh");
  }

  @Test
  void zeroTenantCannotBecomePlatformFallback() {
    assertThatThrownBy(
            () ->
                client.send(
                    0,
                    TenantSystemEmailDeliveryClient.Purpose.EMAIL_ADDRESS_CHANGED,
                    "recipient@example.org",
                    email))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(identity);
    server.verify();
  }
}
