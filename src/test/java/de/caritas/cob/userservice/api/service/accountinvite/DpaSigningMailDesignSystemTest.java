package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService.DpaForwardEmailCommand;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteFrameMailRendererFixture;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailTransport;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.notification.DefaultDpaSigningEmailDispatchService;
import de.caritas.cob.userservice.api.service.notification.DpaSigningMailRenderer;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Theming;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * The DPA ("AVV") signing mail, observed where the Admin wizard sees it: the preview and the mail
 * that reaches the transport. Everything between those two points is the production object — the
 * design-system template, the brand values, the tenant branding resolver, the SMTP dispatcher — and
 * only the tenant lookup, the SMTP settings read and the wire are stubbed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DpaSigningMailDesignSystemTest {

  private static final String APP_ORIGIN = "https://app.example.org";
  private static final long TENANT_ID = 84L;
  private static final String TENANT_NAME = "Träger Nord & Söhne e.V.";
  private static final String SIGN_LINK = APP_ORIGIN + "/dpa-sign/single-use-token";

  /** 08:15 UTC on a summer-time day is 10:15 in Germany. */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-23T08:15:00Z"), ZoneOffset.UTC);

  /** 22:43 UTC on 06.10. is already 00:43 on 07.10. in Germany. */
  private static final LocalDateTime EXPIRES_AT_UTC = LocalDateTime.parse("2026-10-06T22:43:00");

  private static final Pattern URL = Pattern.compile("https?://[^\"'<>\\s]+");

  @Mock private TenantService tenantService;
  @Mock private TenantTemplateSupplier tenantTemplateSupplier;
  @Mock private RestTemplate restTemplate;
  @Mock private ApplicationSettingsService applicationSettingsService;
  @Mock private InviteMailTransport inviteMailTransport;

  private DefaultDpaSigningEmailDispatchService dispatch;
  private DpaForwardEmailService forward;

  @BeforeEach
  void setUp() {
    EmailBrandingResolver brandingResolver =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, "Online-Beratung", "", APP_ORIGIN);
    DpaSigningMailRenderer renderer =
        new DpaSigningMailRenderer(
            brandingResolver,
            new TenantEmailBrandValues(InviteFrameMailRendererFixture.platformBrand(), APP_ORIGIN),
            new OrisoEmailRenderer());
    InviteMailDispatchService mailDispatch =
        new InviteMailDispatchService(
            restTemplate,
            applicationSettingsService,
            inviteMailTransport,
            InviteFrameMailRendererFixture.inviteFrameMailRenderer(brandingResolver),
            "http://consultingtypeservice:8080/service",
            "smtp-user",
            "smtp-pass");
    dispatch = new DefaultDpaSigningEmailDispatchService(renderer, mailDispatch, CLOCK);
    forward = new DpaForwardEmailService(tenantService, dispatch, APP_ORIGIN);

    when(restTemplate.getForObject(anyString(), any())).thenReturn(completeSmtpSettings());
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("legal@example.org", Instant.now()));
    when(tenantTemplateSupplier.getTenantBaseUrl(any(RestrictedTenantDTO.class))).thenReturn("");
  }

  @Test
  void preview_isTheOrisoDesignSystemFrame_notTheHandBuiltCard() {
    givenRegisteredTenant();

    String html = forward.previewSigningMail(TENANT_ID).html();

    assertThat(html)
        .startsWith("<!DOCTYPE html>")
        .contains("background-color:#f2efef")
        .contains("width=\"600\"")
        .contains("font-family:Inter")
        .contains(" ist ein Angebot von ORISO.")
        .contains("lässt sich nicht abbestellen")
        .contains("Träger Nord &amp; Söhne e.V.")
        .doesNotContain("#f3f2f2")
        .doesNotContain("#cbc8c8")
        .doesNotContain("font-family:Arial,sans-serif")
        .doesNotContain("{{");
  }

  @Test
  void preview_offersTheSignLinkAsButtonAndAsCopyLinkFallback() {
    givenRegisteredTenant();
    String sampleLink = APP_ORIGIN + "/dpa-sign/" + DpaForwardEmailService.SAMPLE_SIGN_TOKEN;

    String html = forward.previewSigningMail(TENANT_ID).html();

    assertThat(html)
        .contains(">Vertrag öffnen</a>")
        .contains("Falls der Button nicht funktioniert, kopieren Sie diesen Link in Ihren Browser:")
        .contains(">" + sampleLink + "</a>");
    assertThat(html.split(Pattern.quote("href=\"" + sampleLink + "\""), -1)).hasSize(3);
  }

  @Test
  void subject_namesTheTraeger() {
    givenRegisteredTenant();

    assertThat(forward.previewSigningMail(TENANT_ID).subject())
        .isEqualTo("Vertragsunterlagen für Träger Nord & Söhne e.V.");
  }

  @Test
  void subject_fallsBackToIhreOrganisation_When_theTenantIsOnlyReserved() {
    when(tenantService.getRestrictedTenantData(TENANT_ID))
        .thenThrow(
            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

    assertThat(forward.previewSigningMail(TENANT_ID).subject())
        .isEqualTo("Vertragsunterlagen für Ihre Organisation");
  }

  @Test
  void send_showsProvisionAndExpiryInGermanLocalTime_inBothParts() {
    givenRegisteredTenant();

    SentMail mail = sendSigningLink();

    assertThat(mail.html()).contains("23.09.2026, 10:15 Uhr").contains("07.10.2026, 00:43 Uhr");
    assertThat(mail.text()).contains("23.09.2026, 10:15 Uhr").contains("07.10.2026, 00:43 Uhr");
  }

  @Test
  void send_transmitsBothMimePartsWithTheSubjectAndTheSignLink() {
    givenRegisteredTenant();

    SentMail mail = sendSigningLink();

    assertThat(mail.subject()).isEqualTo("Vertragsunterlagen für Träger Nord & Söhne e.V.");
    assertThat(mail.html()).startsWith("<!DOCTYPE html>").contains("href=\"" + SIGN_LINK + "\"");
    assertThat(mail.text())
        .isNotBlank()
        .doesNotContain("<")
        .contains(SIGN_LINK)
        .contains("Träger: " + TENANT_NAME);
  }

  @Test
  void preview_isByteIdenticalToTheMailThatIsSent() {
    givenRegisteredTenant();

    var preview =
        dispatch.preview(TENANT_ID, "legal@example.org", TENANT_NAME, SIGN_LINK, EXPIRES_AT_UTC);
    dispatch.send(TENANT_ID, "legal@example.org", TENANT_NAME, SIGN_LINK, EXPIRES_AT_UTC);

    SentMail sent = captureSentMail();
    assertThat(sent.subject()).isEqualTo(preview.subject());
    assertThat(sent.html()).isEqualTo(preview.html());
  }

  @Test
  void send_carriesTheTenantBrandingLikeTheInviteMail() {
    givenRegisteredTenant();

    String html = sendSigningLink().html();

    assertThat(html)
        .contains("src=\"" + APP_ORIGIN + "/service/tenant/public/branding/84/logo\"")
        .contains("background-color:#0a5c36");
  }

  @Test
  void send_linksOnlyIntoTheConfiguredOrigin_neverAHardCodedHost() {
    givenRegisteredTenant();

    SentMail mail = sendSigningLink();

    List<String> urls = urlsIn(mail.html() + "\n" + mail.text());
    assertThat(urls).isNotEmpty().allMatch(url -> url.startsWith(APP_ORIGIN + "/"));
  }

  private SentMail sendSigningLink() {
    forward.sendSigningLink(
        new DpaForwardEmailCommand(TENANT_ID, "legal@example.org", SIGN_LINK, EXPIRES_AT_UTC));
    return captureSentMail();
  }

  private SentMail captureSentMail() {
    ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(inviteMailTransport)
        .send(any(), eq("legal@example.org"), subject.capture(), html.capture(), text.capture());
    return new SentMail(subject.getValue(), html.getValue(), text.getValue());
  }

  private void givenRegisteredTenant() {
    when(tenantService.getRestrictedTenantData(TENANT_ID))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT_ID)
                .name(TENANT_NAME)
                .theming(
                    new Theming()
                        .logo("data:image/png;base64,iVBORw0KGgo=")
                        .primaryColor("#0a5c36")));
  }

  private static Map<String, Object> completeSmtpSettings() {
    return Map.of(
        "globalFeatureSystemNotificationEmailsEnabled", Map.of("value", true),
        "globalSmtpEnabled", Map.of("value", true),
        "globalSmtpHost", Map.of("value", "smtp.example.org"),
        "globalSmtpPort", Map.of("value", "587"),
        "globalSmtpSecure", Map.of("value", false),
        "globalSmtpFrom", Map.of("value", "noreply@example.org"));
  }

  private static List<String> urlsIn(String document) {
    Matcher matcher = URL.matcher(document);
    java.util.ArrayList<String> urls = new java.util.ArrayList<>();
    while (matcher.find()) {
      urls.add(matcher.group());
    }
    return urls;
  }

  private record SentMail(String subject, String html, String text) {}
}
