package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteSmtpSettings;
import de.caritas.cob.userservice.api.service.email.layout.*;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.mailservice.generated.web.model.*;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationEmailServiceTest {
  private final GlobalSmtpSettingsResolver smtp = mock(GlobalSmtpSettingsResolver.class);
  private final OrisoEmailDispatcher dispatcher = mock(OrisoEmailDispatcher.class);
  private final EmailBrandingResolver branding = mock(EmailBrandingResolver.class);
  private NotificationEmailService service;

  @BeforeEach
  void setUp() {
    when(smtp.resolve())
        .thenReturn(
            new InviteSmtpSettings(
                "smtp.example.org", 587, false, "test", "test", "sender@example.org"));
    when(branding.resolve(7L))
        .thenReturn(
            new EmailBranding(
                "Träger Sieben",
                "https://app.example.org/service/tenant/public/branding/7/logo",
                "#1c4f8f",
                "https://app.example.org/impressum",
                "https://app.example.org/datenschutz"));
    var brand = new OrisoEmailBrand(branding);
    service = new NotificationEmailService(new OrisoEmailRenderer(), brand, smtp, dispatcher);
    ReflectionTestUtils.setField(service, "applicationBaseUrl", "https://app.example.org");
    TenantContext.setCurrentTenant(7L);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void keepsEachRecipientsBrandingWhenASchedulerBatchContainsDifferentTenants() {
    when(branding.resolve(8L))
        .thenReturn(
            new EmailBranding(
                "Träger Acht",
                "https://app.example.org/service/tenant/public/branding/8/logo",
                "#164f2b",
                null,
                null));
    TenantContext.setCurrentTenant(7L);
    var first = mail("daily-enquiry-notification").email("seven@example.org");
    first.addTemplateDataItem(new TemplateDataDTO().key("tenantId").value("7"));
    var second = mail("daily-enquiry-notification").email("eight@example.org");
    second.addTemplateDataItem(new TemplateDataDTO().key("tenantId").value("8"));
    service.send(new MailsDTO().mails(List.of(first, second)));
    var rendered = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(dispatcher, times(2)).sendOrThrow(any(), any(), rendered.capture());
    assertThat(rendered.getAllValues().get(0).html())
        .contains("Träger Sieben", "/branding/7/logo")
        .doesNotContain("Träger Acht");
    assertThat(rendered.getAllValues().get(1).html())
        .contains("Träger Acht", "/branding/8/logo")
        .doesNotContain("Träger Sieben");
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(7L);
  }

  @ParameterizedTest
  @CsvSource({
    "enquiry-notification-consultant, Neue Anfrage in Ihrer Beratungsstelle",
    "direct-enquiry-notification-consultant, Eine Anfrage richtet sich direkt an Sie",
    "assign-enquiry-notification, Neue Beratungsanfrage",
    "daily-enquiry-notification, Ihre Tagesübersicht"
  })
  void sendsExistingDesignWithEffectiveBrandingAndBothParts(String template, String subject) {
    service.send(new MailsDTO().mails(List.of(mail(template))));
    var rendered = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(dispatcher).sendOrThrow(any(), eq("recipient@example.org"), rendered.capture());
    assertThat(rendered.getValue().subject()).isEqualTo(subject);
    assertThat(rendered.getValue().html())
        .contains("Träger Sieben", "/branding/7/logo", "#1c4f8f")
        .doesNotContain("{{");
    assertThat(rendered.getValue().text())
        .contains("https://app.example.org")
        .doesNotContain("{{", "<table");
  }

  @Test
  void preservesFreeTextSubjectAndMeaningInBothParts() {
    var mail = mail("free-text");
    mail.addTemplateDataItem(
        new TemplateDataDTO().key("subject").value("Ihre Anfrage wurde angenommen"));
    mail.addTemplateDataItem(
        new TemplateDataDTO()
            .key("text")
            .value("Gute Nachrichten: Müller & Team hat Ihre Anfrage angenommen."));
    service.send(new MailsDTO().mails(List.of(mail)));
    var rendered = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(dispatcher).sendOrThrow(any(), any(), rendered.capture());
    assertThat(rendered.getValue().subject()).isEqualTo("Ihre Anfrage wurde angenommen");
    assertThat(rendered.getValue().html()).contains("Müller &amp; Team").doesNotContain("{{");
    assertThat(rendered.getValue().text())
        .contains("Müller & Team", "Zu Träger Sieben")
        .doesNotContain("Einladung annehmen");
    assertThat(rendered.getValue().html())
        .contains("Zu Träger Sieben", "border-radius:24px")
        .doesNotContain("Einladung annehmen");
  }

  @Test
  void preservesAuthoredFormattingWithoutUnsafeHtmlInOperationalMessage() {
    var mail = mail("free-text");
    mail.addTemplateDataItem(new TemplateDataDTO().key("subject").value("Operational message"));
    mail.addTemplateDataItem(
        new TemplateDataDTO()
            .key("text")
            .value(
                "<p>First &amp; second</p><ul><li>Keep this item</li></ul><script>unsafe()</script>"));
    service.send(new MailsDTO().mails(List.of(mail)));
    var rendered = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(dispatcher).sendOrThrow(any(), any(), rendered.capture());
    assertThat(rendered.getValue().html())
        .contains("<li>Keep this item</li>", "Zu Träger Sieben")
        .doesNotContain("<script", "unsafe()", "Einladung annehmen");
    assertThat(rendered.getValue().text())
        .contains("First & second", "Keep this item")
        .doesNotContain("<li>", "unsafe()", "Einladung annehmen");
  }

  @Test
  void doesNotSendCounsellorHandoverCopyToAnAdviceSeeker() {
    service.send(new MailsDTO().mails(List.of(mail("reassign-request-notification"))));
    var rendered = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(dispatcher).sendOrThrow(any(), any(), rendered.capture());
    assertThat(rendered.getValue().text())
        .contains("Beratung")
        .doesNotContain("an Sie zu übergeben", "Bis Sie zustimmen");
  }

  @Test
  void failsBeforeAnyDeliveryForUnsupportedOccasions() {
    assertThatThrownBy(
            () ->
                service.send(
                    new MailsDTO()
                        .mails(
                            List.of(mail("enquiry-notification-consultant"), mail("unsupported")))))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void attemptsAllRecipientsAndReportsOnlyAggregateFailure() {
    doThrow(new SmtpSendException("private-address@example.org provider detail"))
        .when(dispatcher)
        .sendOrThrow(any(), eq("first@example.org"), any());
    doThrow(new SmtpSendException("third@example.org provider detail"))
        .when(dispatcher)
        .sendOrThrow(any(), eq("third@example.org"), any());
    assertThatThrownBy(
            () ->
                service.send(
                    new MailsDTO()
                        .mails(
                            List.of(
                                mail("daily-enquiry-notification").email("first@example.org"),
                                mail("daily-enquiry-notification").email("second@example.org"),
                                mail("daily-enquiry-notification").email("third@example.org")))))
        .isInstanceOf(SmtpSendException.class)
        .hasMessage("Notification batch partially failed: 2 of 3")
        .hasNoCause();
    verify(dispatcher).sendOrThrow(any(), eq("second@example.org"), any());
    verify(dispatcher).sendOrThrow(any(), eq("third@example.org"), any());
  }

  @Test
  void propagatesSmtpFailureInsteadOfReportingAcceptance() {
    doThrow(new SmtpSendException("test transport failure"))
        .when(dispatcher)
        .sendOrThrow(any(), any(), any());
    assertThatThrownBy(
            () ->
                service.send(
                    new MailsDTO().mails(List.of(mail("enquiry-notification-consultant")))))
        .isInstanceOf(SmtpSendException.class);
  }

  private MailDTO mail(String template) {
    return new MailDTO()
        .template(template)
        .email("recipient@example.org")
        .language(LanguageCode.DE)
        .templateData(
            new java.util.ArrayList<>(
                List.of(
                    new TemplateDataDTO().key("url").value("https://app.example.org"),
                    new TemplateDataDTO().key("beratungsstelle").value("Beratungsstelle"),
                    new TemplateDataDTO().key("plz").value("12345"),
                    new TemplateDataDTO().key("enquiries").value("3"))));
  }
}
