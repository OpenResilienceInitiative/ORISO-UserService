package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailPreviewService.InviteEmailPreview;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailPreviewService.PreviewCommand;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailTransport;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmailLayoutRenderer;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.layout.EmailContentSanitizer;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

/**
 * The Admin preview must show what is actually sent (ORISO-UserService#914). The decisive test here
 * renders a preview and pushes the identical content through the real dispatcher, then asserts the
 * two HTML documents are byte-identical — that is the guarantee replacing a second implementation
 * of the markup in the Admin repository.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InviteEmailPreviewServiceTest {

  @Mock private InviteEmailTemplateRepository templateRepository;
  @Mock private RestTemplate restTemplate;
  @Mock private ApplicationSettingsService applicationSettingsService;
  @Mock private InviteMailTransport inviteMailTransport;
  @Mock private EmailBrandingResolver emailBrandingResolver;

  private InviteAcceptUrlBuilder acceptUrlBuilder;
  private InviteMailDispatchService dispatchService;
  private InviteEmailPreviewService previewService;

  @BeforeEach
  void setUp() {
    acceptUrlBuilder =
        new InviteAcceptUrlBuilder("https://app.oriso.org", "https://admin.oriso.org");
    dispatchService =
        new InviteMailDispatchService(
            restTemplate,
            applicationSettingsService,
            inviteMailTransport,
            emailBrandingResolver,
            new BrandedEmailLayoutRenderer(new EmailContentSanitizer()),
            "http://consultingtypeservice:8080/service",
            "smtp-user",
            "smtp-pass");
    previewService =
        new InviteEmailPreviewService(
            templateRepository,
            acceptUrlBuilder,
            dispatchService,
            new de.caritas.cob.userservice.api.service.notification.AdminPanelUrl(
                "https://admin.configured.example"));

    when(restTemplate.getForObject(anyString(), any()))
        .thenReturn(
            Map.of(
                "globalFeatureSystemNotificationEmailsEnabled", Map.of("value", true),
                "globalSmtpEnabled", Map.of("value", true),
                "globalSmtpHost", Map.of("value", "smtp.example.org"),
                "globalSmtpPort", Map.of("value", "587"),
                "globalSmtpSecure", Map.of("value", false),
                "globalSmtpFrom", Map.of("value", "noreply@example.org"),
                "globalSmtpEmailThemeColor", Map.of("value", "#f8e71c")));
    when(emailBrandingResolver.resolve(any()))
        .thenReturn(new EmailBranding("Nord", null, "#f8e71c", null, null));
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("to@example.org", Instant.now()));
  }

  @Test
  void preview_Should_renderExactlyWhatTheDispatcherBuilds() {
    String subject = "Ihre Einladung";
    String body = "Hallo Maren Muster,\n\nbitte richten Sie Ihr Konto ein.";

    InviteEmailPreview preview =
        previewService.preview(
            new PreviewCommand(
                null, InviteEmailTemplateKind.TENANT_INVITE, subject, body, null, "de"));

    dispatchService.send("to@example.org", subject, body, preview.sampleAcceptUrl(), null, "de");

    ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(inviteMailTransport).send(any(), any(), any(), html.capture(), text.capture());

    assertThat(preview.html()).isEqualTo(html.getValue());
    assertThat(preview.plainText()).isEqualTo(text.getValue());
  }

  @Test
  void preview_Should_useTheRealAcceptUrlShapePerTemplateKind() {
    InviteEmailPreview tenantPreview =
        previewService.preview(
            new PreviewCommand(null, InviteEmailTemplateKind.TENANT_INVITE, null, null, null));
    InviteEmailPreview counsellorPreview =
        previewService.preview(
            new PreviewCommand(null, InviteEmailTemplateKind.COUNSELLOR_INVITE, null, null, null));

    assertThat(tenantPreview.sampleAcceptUrl())
        .isEqualTo(
            "https://admin.oriso.org/admin/tenant-onboarding/"
                + InviteEmailPreviewService.SAMPLE_TOKEN);
    // #997: counsellor invites land on the PUBLIC ADMIN wizard, not the app acceptance page.
    assertThat(counsellorPreview.sampleAcceptUrl())
        .isEqualTo(
            "https://admin.oriso.org/admin/counsellor-onboarding/"
                + InviteEmailPreviewService.SAMPLE_TOKEN);
  }

  /** A preview must never mint something that looks like a usable invite token. */
  @Test
  void preview_Should_useAnObviousSampleToken() {
    assertThat(
            previewService
                .preview(new PreviewCommand(null, null, null, null, null))
                .sampleAcceptUrl())
        .contains("SAMPLE-PREVIEW-TOKEN");
  }

  @Test
  void preview_Should_renderSampleContent_When_NothingIsGiven() {
    InviteEmailPreview preview =
        previewService.preview(new PreviewCommand(null, null, null, null, null));

    assertThat(preview.subject()).isEqualTo(InviteEmailPreviewService.SAMPLE_SUBJECT);
    assertThat(preview.html()).contains("Maren").contains("Muster");
    assertThat(preview.kind()).isEqualTo(InviteEmailTemplateKind.TENANT_INVITE);
  }

  @Test
  void preview_Should_renderAStoredTemplateWithItsPlaceholdersSubstituted() {
    InviteEmailTemplate template =
        InviteEmailTemplate.builder()
            .id(5L)
            .kind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
            .name("Counsellor DE")
            .language("de")
            .subject("Willkommen {{firstName}}")
            .body("Hallo {{firstName}} {{lastName}}, Link: {{inviteLink}}")
            .active(true)
            .build();
    when(templateRepository.findById(5L)).thenReturn(Optional.of(template));

    InviteEmailPreview preview =
        previewService.preview(new PreviewCommand(5L, null, null, null, null));

    assertThat(preview.subject()).isEqualTo("Willkommen Maren");
    assertThat(preview.templateName()).isEqualTo("Counsellor DE");
    assertThat(preview.html())
        .contains("Maren Muster")
        .doesNotContain("{{inviteLink}}")
        .contains(preview.sampleAcceptUrl());
  }

  @Test
  void preview_Should_throwNotFound_When_TemplateDoesNotExist() {
    when(templateRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> previewService.preview(new PreviewCommand(99L, null, null, null, null)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void preview_Should_resolveBrandingForTheRequestedTenant() {
    previewService.preview(new PreviewCommand(null, null, null, null, 21L));

    verify(emailBrandingResolver).resolve(21L);
  }

  @Test
  void preview_Should_renderTheConfiguredAdminUrl_ForTheSignedNotice() {
    // supplying a URL and never asserting it appears is the shape that made the previous version
    // of this test unable to fail: a preview ignoring the configured value entirely would pass.
    // The host asserted here is deliberately not one any other path defaults to.
    var preview =
        previewService.preview(
            new InviteEmailPreviewService.PreviewCommand(
                null,
                InviteEmailTemplateKind.DPA_SIGNED_NOTICE,
                "Signed",
                "Continue here: {{adminUrl}}",
                null,
                "de"));

    assertThat(preview.plainText()).contains("https://admin.configured.example/admin");
    // and the notice must not advertise an invite accept link it never carries
    assertThat(preview.plainText()).doesNotContain("/account-invite");
    // the HTML body is what the recipient actually sees, so it carries the same two invariants:
    // asserting only the plain text left the rendered mail free to carry a wrong Admin URL or an
    // accept action (CodeRabbit, #1065)
    assertThat(preview.html()).contains("https://admin.configured.example/admin");
    assertThat(preview.html()).doesNotContain("/account-invite");
    // that includes the machine-readable field: a consumer must not receive an accept URL
    // for a mail that has no primary action (CodeRabbit, #1065)
    assertThat(preview.sampleAcceptUrl()).isNull();
  }
}
