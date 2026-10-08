package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.exception.SmtpSendException.Category;
import de.caritas.cob.userservice.api.exception.SmtpSendException.DeliveryDisposition;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.RenderedEmail;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsFixture;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailClient;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailDelivery.Purpose;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService;
import java.net.SocketTimeoutException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * #1251: which server a Träger's invite and DPA mails leave through. Real routing, real relay
 * client, TenantService stubbed at the HTTP boundary; the platform SMTP transport is a mock.
 */
@ExtendWith(MockitoExtension.class)
// Each case uses only part of the shared platform/identity setup.
@MockitoSettings(strictness = Strictness.LENIENT)
class InviteMailTenantRoutingTest {
  private static final String TENANT_SERVICE = "http://tenantservice.internal:8081";
  private static final long OWN_TENANT = 40L;
  private static final long PLATFORM_TENANT = 41L;
  private static final String OWN_SETTINGS =
      """
      {"settings":{"featureSystemNotificationEmailsEnabled":%s,"smtpMode":"OWN","smtp":{
        "enabled":true,"host":"mail.traeger-a.example","port":587,"secure":true,
        "username":"relay","from":"einladung@traeger-a.example","passwordSet":%s}}}
      """;
  private static final String PLATFORM_SETTINGS =
      """
      {"settings":{"featureSystemNotificationEmailsEnabled":true,"smtpMode":"PLATFORM",
        "smtp":{"enabled":false}}}
      """;

  @Mock private InviteMailTransport platformTransport;
  @Mock private EmailBrandingResolver brandingResolver;
  @Mock private IdentityAuthentication authentication;
  @Mock private IdentityClientConfig identityConfig;
  @Mock private SecurityHeaderSupplier headerSupplier;

  private MockRestServiceServer tenantService;
  private InviteMailDispatchService dispatch;

  @BeforeEach
  void setUp() {
    RestTemplate restTemplate = new RestTemplate();
    tenantService = MockRestServiceServer.bindTo(restTemplate).build();
    TenantSystemEmailClient relay =
        new TenantSystemEmailClient(restTemplate, authentication, identityConfig, headerSupplier);
    ReflectionTestUtils.setField(relay, "tenantServiceApiUrl", TENANT_SERVICE);
    dispatch =
        new InviteMailDispatchService(
            PlatformSmtpSettingsFixture.configured("smtp-user", "smtp-pass"),
            platformTransport,
            InviteFrameMailRendererFixture.inviteFrameMailRenderer(brandingResolver),
            new TenantSystemEmailRouteService(relay),
            relay);

    var account = new TechnicalUserConfig();
    account.setClientId("technical");
    account.setClientSecret("test-secret");
    lenient().when(identityConfig.getTechnicalUser()).thenReturn(account);
    lenient()
        .when(authentication.loginService("technical", "test-secret"))
        .thenReturn(new IdentityLogin("technical-token", 60, 60, "refresh"));
    var headers = new HttpHeaders();
    headers.setBearerAuth("technical-token");
    lenient().when(headerSupplier.getKeycloakAndCsrfHttpHeaders(any())).thenAnswer(i -> headers);
    lenient()
        .when(brandingResolver.resolvePendingTenant(any()))
        .thenReturn(
            new EmailBranding(
                "Träger",
                null,
                "#a5000a",
                "https://app.example.org/impressum",
                "https://app.example.org/datenschutz"));
    lenient()
        .when(platformTransport.send(any(), any(), any(), any(), any()))
        .thenAnswer(call -> new InviteMailSendReceipt(call.getArgument(1), Instant.now()));
  }

  @Test
  void twoTenants_ownTenantUsesItsRelay_platformTenantUsesThePlatformServerAndFrom() {
    givenTenant(OWN_TENANT, OWN_SETTINGS.formatted(true, true));
    expectRelay(OWN_TENANT, "ACCOUNT_INVITE", "a@example.org").andRespond(withSuccess());
    givenTenant(PLATFORM_TENANT, PLATFORM_SETTINGS);

    var own = invite(OWN_TENANT, "a@example.org");
    var platform = invite(PLATFORM_TENANT, "b@example.org");

    tenantService.verify();
    assertThat(own.recipientEmail()).isEqualTo("a@example.org");
    assertThat(platform.recipientEmail()).isEqualTo("b@example.org");
    // Only the platform tenant's mail reached the platform server, with the platform From.
    verify(platformTransport)
        .send(
            eq(
                new InviteSmtpSettings(
                    "smtp.example.org",
                    587,
                    false,
                    "smtp-user",
                    "smtp-pass",
                    "noreply@example.org")),
            eq("b@example.org"),
            eq("Ihre Einladung"),
            any(),
            any());
    org.mockito.Mockito.verifyNoMoreInteractions(platformTransport);
  }

  @Test
  void tenantlessPlatformAdminInvite_neverAsksTenantService() {
    invite(null, "platform-admin@example.org");

    tenantService.verify();
    verify(platformTransport).send(any(), eq("platform-admin@example.org"), any(), any(), any());
  }

  @Test
  void legacyTenantWithoutSmtpMode_staysOnThePlatformServer() {
    givenTenant(
        PLATFORM_TENANT,
        """
        {"settings":{"featureSystemNotificationEmailsEnabled":true,
          "smtp":{"enabled":true,"host":"mail.legacy.example"}}}
        """);

    invite(PLATFORM_TENANT, "b@example.org");

    tenantService.verify();
    verify(platformTransport).send(any(), eq("b@example.org"), any(), any(), any());
  }

  @Test
  void ownTenant_staysOnItsServer_whenNotificationMailsAreSwitchedOff() {
    givenTenant(OWN_TENANT, OWN_SETTINGS.formatted(false, true));
    expectRelay(OWN_TENANT, "ACCOUNT_INVITE", "a@example.org").andRespond(withSuccess());

    invite(OWN_TENANT, "a@example.org");

    tenantService.verify();
    verifyNoInteractions(platformTransport);
  }

  @Test
  void dpaMails_carryTheirOwnPurposeAndTheRenderedPartsUnchanged() {
    var mail = new RenderedEmail("Vertragsunterlagen", "<p>AVV</p>", "AVV");
    givenTenant(OWN_TENANT, OWN_SETTINGS.formatted(true, true));
    expectRelay(OWN_TENANT, "DPA_SIGNING_REQUEST", "legal@example.org")
        .andExpect(jsonPath("$.subject").value("Vertragsunterlagen"))
        .andExpect(jsonPath("$.html").value("<p>AVV</p>"))
        .andExpect(jsonPath("$.text").value("AVV"))
        .andRespond(withSuccess());
    givenTenant(OWN_TENANT, OWN_SETTINGS.formatted(true, true));
    expectRelay(OWN_TENANT, "DPA_SIGNED_NOTICE", "toni@example.org").andRespond(withSuccess());

    dispatch.sendRendered(
        "legal@example.org", mail, InviteMailOrigin.of(OWN_TENANT, Purpose.DPA_SIGNING_REQUEST));
    dispatch.send(
        "toni@example.org",
        "Unterzeichnet",
        "Body",
        null,
        OWN_TENANT,
        "de",
        InviteMailOrigin.of(OWN_TENANT, Purpose.DPA_SIGNED_NOTICE));

    tenantService.verify();
    verifyNoInteractions(platformTransport);
  }

  @Test
  void incompleteOwnSettings_failExplicitlyWithoutAnyFallback() {
    givenTenant(OWN_TENANT, OWN_SETTINGS.formatted(true, false));

    assertThatThrownBy(() -> invite(OWN_TENANT, "a@example.org"))
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            failure -> {
              assertThat(failure.getCategory()).isEqualTo(Category.SMTP_DISABLED_OR_INCOMPLETE);
              assertThat(failure.isConfirmedNotSent()).isTrue();
            });
    tenantService.verify();
    verifyNoInteractions(platformTransport);
  }

  @Test
  void unreadableTenantSettings_failWithoutFallingBackToThePlatform() {
    tenantService
        .expect(once(), requestTo(TENANT_SERVICE + "/tenant/" + OWN_TENANT))
        .andRespond(withServerError());

    assertThatThrownBy(() -> invite(OWN_TENANT, "a@example.org"))
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            failure -> {
              assertThat(failure.getCategory()).isEqualTo(Category.SMTP_SETTINGS_UNAVAILABLE);
              assertThat(failure.isConfirmedNotSent()).isTrue();
            });
    verifyNoInteractions(platformTransport);
  }

  @ParameterizedTest
  @CsvSource({
    "204, SMTP_DISABLED_OR_INCOMPLETE, CONFIRMED_NOT_SENT",
    "422, SMTP_DISABLED_OR_INCOMPLETE, CONFIRMED_NOT_SENT",
    "400, SMTP_SETTINGS_UNAVAILABLE, CONFIRMED_NOT_SENT",
    "502, SMTP_TRANSPORT_FAILED, DELIVERY_UNCERTAIN",
    "500, SMTP_TRANSPORT_FAILED, DELIVERY_UNCERTAIN"
  })
  void relayAnswers_mapOntoTheStrictInviteContract(
      int status, Category category, DeliveryDisposition disposition) {
    givenTenant(OWN_TENANT, OWN_SETTINGS.formatted(true, true));
    expectRelay(OWN_TENANT, "ACCOUNT_INVITE", "a@example.org")
        .andRespond(withStatus(HttpStatus.valueOf(status)));

    assertThatThrownBy(() -> invite(OWN_TENANT, "a@example.org"))
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            failure -> {
              assertThat(failure.getCategory()).isEqualTo(category);
              assertThat(failure.getDeliveryDisposition()).isEqualTo(disposition);
            });
    verifyNoInteractions(platformTransport);
  }

  @Test
  void relayTimeout_keepsTheDeliveryUncertain() {
    givenTenant(OWN_TENANT, OWN_SETTINGS.formatted(true, true));
    expectRelay(OWN_TENANT, "ACCOUNT_INVITE", "a@example.org")
        .andRespond(
            request -> {
              throw new SocketTimeoutException("read timed out");
            });

    assertThatThrownBy(() -> invite(OWN_TENANT, "a@example.org"))
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            failure ->
                assertThat(failure.getDeliveryDisposition())
                    .isEqualTo(DeliveryDisposition.DELIVERY_UNCERTAIN));
  }

  private InviteMailSendReceipt invite(Long tenantId, String recipient) {
    return dispatch.send(
        recipient,
        "Ihre Einladung",
        "Hallo, bitte bestätigen Sie Ihr Konto.",
        "https://app.example.org/account-invite/tok",
        tenantId,
        "de",
        InviteMailOrigin.of(tenantId, Purpose.ACCOUNT_INVITE));
  }

  private void givenTenant(long tenantId, String body) {
    tenantService
        .expect(once(), requestTo(TENANT_SERVICE + "/tenant/" + tenantId))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  private org.springframework.test.web.client.ResponseActions expectRelay(
      long tenantId, String purpose, String recipient) {
    return tenantService
        .expect(
            once(),
            requestTo(TENANT_SERVICE + "/tenant/" + tenantId + "/internal/system-email-deliveries"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.purpose").value(purpose))
        .andExpect(jsonPath("$.recipient").value(recipient))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }
}
