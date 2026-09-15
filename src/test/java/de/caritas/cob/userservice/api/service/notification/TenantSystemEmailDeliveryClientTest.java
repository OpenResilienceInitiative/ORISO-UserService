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
  void springSelectsTheStandardHeaderSupplierWhenTrackingSupplierAlsoExists() {
    var trackingHeaders = mock(SecurityHeaderSupplier.class);
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withPropertyValues("tenant.service.api.url=https://tenant.example.org")
        .withBean(RestTemplate.class, () -> rest)
        .withBean(IdentityAuthentication.class, () -> identity)
        .withBean(IdentityClientConfig.class, () -> config)
        .withBean("securityHeaderSupplier", SecurityHeaderSupplier.class, () -> headers)
        .withBean(
            "trackingSecurityHeaderSupplier", SecurityHeaderSupplier.class, () -> trackingHeaders)
        .withBean(TenantSystemEmailDeliveryClient.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              server.expect(anything()).andRespond(withSuccess());
              assertThat(
                      context
                          .getBean(TenantSystemEmailDeliveryClient.class)
                          .send(
                              40,
                              TenantSystemEmailDeliveryClient.Purpose.EMAIL_ADDRESS_CHANGED,
                              "recipient@example.org",
                              email))
                  .isTrue();
              server.verify();
              verifyNoInteractions(trackingHeaders, requestUser);
            });
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

  @Test
  void aFailedDeliveryRecordsTheFailureShapeWithoutTheDownstreamBody() {
    // Discarding the cause entirely made a TenantService outage, an expired technical user and a
    // serialization bug indistinguishable in production. The type and status must survive; the
    // response body, which can quote the recipient address, must not.
    server
        .expect(requestTo("https://tenant.example.org/tenant/7/internal/system-email-deliveries"))
        .andRespond(
            withServerError()
                .contentType(MediaType.TEXT_PLAIN)
                .body("synthetic-body-quoting advice.seeker@example.org"));

    try (var logs =
        de.caritas.cob.userservice.testutils.LogbackCaptor.forClass(
            TenantSystemEmailDeliveryClient.class)) {
      assertThatThrownBy(
              () ->
                  client.send(
                      7L,
                      TenantSystemEmailDeliveryClient.Purpose.SUPERVISOR_ADDED,
                      "advice.seeker@example.org",
                      email))
          .isInstanceOf(IllegalStateException.class)
          .hasNoCause();

      assertThat(logs.messages(ch.qos.logback.classic.Level.ERROR)).hasSize(1);
      var message = logs.messages(ch.qos.logback.classic.Level.ERROR).get(0);
      assertThat(message).contains("HTTP 500").contains("SUPERVISOR_ADDED");
      assertThat(message).doesNotContain("synthetic-body-quoting");
      assertThat(logs.events()).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }
  }
}
