package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.mailservice.generated.web.model.Dialect;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaseHandoverMailComposerTest {
  private final EmailBrandingResolver branding = mock(EmailBrandingResolver.class);
  private final TenantEmailBrandValues brandValues = mock(TenantEmailBrandValues.class);
  private final CaseHandoverMailComposer composer =
      new CaseHandoverMailComposer(new OrisoEmailRenderer(true), branding, brandValues);

  @Test
  void neutralConsentCopyHasNoCaseOrCounsellorInAnyOfSevenVariants() {
    prepareBrand();
    for (LanguageCode language :
        new LanguageCode[] {
          LanguageCode.de,
          LanguageCode.en,
          LanguageCode.fr,
          LanguageCode.ru,
          LanguageCode.ti,
          LanguageCode.tr
        }) {
      for (Dialect dialect :
          language == LanguageCode.de
              ? new Dialect[] {Dialect.FORMAL, Dialect.INFORMAL}
              : new Dialect[] {Dialect.FORMAL}) {
        var rendered =
            composer.compose(
                mail(CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED, language, dialect),
                "https://tenant.example.test");
        assertThat(rendered.subject())
            .isEqualTo(
                Map.of(
                        LanguageCode.de, "Neue Benachrichtigung",
                        LanguageCode.en, "New notification",
                        LanguageCode.fr, "Nouvelle notification",
                        LanguageCode.ru, "Новое уведомление",
                        LanguageCode.ti, "ሓድሽ ምልክታ",
                        LanguageCode.tr, "Yeni bildirim")
                    .get(language));
        assertThat(rendered.text())
            .contains(
                "https://tenant.example.test/sessions/user/view/session/77?caseHandoverRequestId=12")
            .doesNotContain("/sessions/consultant/sessionView/")
            .doesNotContain("#123", "Counsellor Name", "{{");
        assertThat(rendered.html()).doesNotContain("{{");
        var confirmed =
            composer.compose(
                mail(CaseHandoverEmailNotification.Outcome.GRANTED, language, dialect),
                "https://tenant.example.test");
        assertThat(confirmed.subject()).isNotBlank().doesNotContain("{{", "ORISO");
        assertThat(confirmed.text())
            .contains(
                "https://tenant.example.test/sessions/consultant/sessionView/%21room%3Aexample.test/77")
            .doesNotContain("/sessions/user/view/session/")
            .doesNotContain("{{", "Your access to the previous");
        assertThat(confirmed.html()).doesNotContain("{{");
      }
    }
  }

  @Test
  void temporaryAccessUsesItsOwnAccurateConsentRecipeInAllSevenTones() {
    prepareBrand();
    for (var tone : OrisoEmailRenderer.Tone.values()) {
      var language =
          tone == OrisoEmailRenderer.Tone.DE_FORMAL || tone == OrisoEmailRenderer.Tone.DE_INFORMAL
              ? LanguageCode.de
              : LanguageCode.valueOf(tone.name().toLowerCase());
      var dialect = tone == OrisoEmailRenderer.Tone.DE_INFORMAL ? Dialect.INFORMAL : Dialect.FORMAL;
      var mail =
          new CaseHandoverEmailNotification.Mail(
              12L,
              77L,
              "!room:example.test",
              CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED,
              40L,
              "asker@example.test",
              language,
              dialect,
              de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType.CO_ACCESS);
      var rendered = composer.compose(mail, "https://tenant.example.test");
      assertThat(rendered.text())
          .contains(
              "https://tenant.example.test/sessions/user/view/session/77?caseHandoverRequestId=12")
          .doesNotContain("{{", "Counsellor Name", "#123");
      assertThat(rendered.html()).doesNotContain("{{");
      if (language == LanguageCode.en)
        assertThat(rendered.text())
            .contains("temporarily access your conversation")
            .doesNotContain("You are now responsible");
    }
  }

  @Test
  void publicSmtpMimeFooterSelectsTheExistingConsentPreferenceForEachRequestKind()
      throws Exception {
    prepareBrand();
    for (var accessType :
        new de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType[] {
          de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType.CO_ACCESS,
          de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType.TAKEOVER
        }) {
      var mail =
          new CaseHandoverEmailNotification.Mail(
              12L,
              77L,
              "!room:example.test",
              CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED,
              40L,
              "asker@example.test",
              LanguageCode.en,
              Dialect.FORMAL,
              accessType);
      var rendered = composer.compose(mail, "https://tenant.example.test");
      var expected =
          "https://tenant.example.test/profile/einstellungen/email?mail="
              + (accessType
                      == de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType
                          .CO_ACCESS
                  ? "einsicht-angefragt"
                  : "uebergabe-angefragt");
      var captured = new java.util.ArrayList<jakarta.mail.Message>();
      try (var transport = org.mockito.Mockito.mockStatic(jakarta.mail.Transport.class)) {
        transport
            .when(
                () ->
                    jakarta.mail.Transport.send(
                        org.mockito.ArgumentMatchers.any(jakarta.mail.Message.class)))
            .thenAnswer(
                invocation -> {
                  captured.add(invocation.getArgument(0));
                  return null;
                });
        assertThat(
                new de.caritas.cob.userservice.api.service.email.OrisoEmailDispatcher()
                    .send(
                        new de.caritas.cob.userservice.api.service.email
                            .PlatformSmtpSettingsProvider.Settings(
                            "smtp.invalid",
                            587,
                            false,
                            "test",
                            "fixture",
                            "sender@example.test",
                            "#123456"),
                        "asker@example.test",
                        rendered))
            .isTrue();
      }
      assertThat(captured).hasSize(1);
      var mime = (jakarta.mail.Multipart) captured.getFirst().getContent();
      assertThat(mime.getBodyPart(0).getContent().toString())
          .contains(expected)
          .doesNotContain("&mail=");
      assertThat(mime.getBodyPart(1).getContent().toString())
          .contains(expected)
          .doesNotContain("&mail=");
    }
    var confirmed =
        composer.compose(
            mail(CaseHandoverEmailNotification.Outcome.GRANTED, LanguageCode.en, Dialect.FORMAL),
            "https://tenant.example.test");
    assertThat(confirmed.text())
        .contains("/profile/einstellungen/email?mail=uebergabe-bestaetigt")
        .doesNotContain("&mail=");
    var confirmations = new java.util.ArrayList<jakarta.mail.Message>();
    try (var transport = org.mockito.Mockito.mockStatic(jakarta.mail.Transport.class)) {
      transport
          .when(
              () ->
                  jakarta.mail.Transport.send(
                      org.mockito.ArgumentMatchers.any(jakarta.mail.Message.class)))
          .thenAnswer(
              invocation -> {
                confirmations.add(invocation.getArgument(0));
                return null;
              });
      assertThat(
              new de.caritas.cob.userservice.api.service.email.OrisoEmailDispatcher()
                  .send(
                      new de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider
                          .Settings(
                          "smtp.invalid",
                          587,
                          false,
                          "test",
                          "fixture",
                          "sender@example.test",
                          "#123456"),
                      "consultant@example.test",
                      confirmed))
          .isTrue();
    }
    assertThat(confirmations).hasSize(1);
    var confirmationMime = (jakarta.mail.Multipart) confirmations.getFirst().getContent();
    for (int part = 0; part < confirmationMime.getCount(); part++) {
      assertThat(confirmationMime.getBodyPart(part).getContent().toString())
          .contains("/profile/einstellungen/email?mail=uebergabe-bestaetigt")
          .doesNotContain("&mail=");
    }
  }

  @Test
  void confirmationTellsTheIncomingCounsellorTheyNowOwnTheCase() {
    prepareBrand();
    var rendered =
        composer.compose(
            mail(CaseHandoverEmailNotification.Outcome.GRANTED, LanguageCode.en, Dialect.FORMAL),
            "https://tenant.example.test");
    assertThat(rendered.text())
        .contains("You are now responsible")
        .contains(
            "https://tenant.example.test/sessions/consultant/sessionView/%21room%3Aexample.test/77")
        .doesNotContain("/sessions/user/view/session/")
        .doesNotContain("Your access to the previous", "{{");
  }

  @Test
  void invalidOrMissingUrlNeverRendersAnEmail() {
    assertThatThrownBy(
            () ->
                composer.compose(
                    mail(
                        CaseHandoverEmailNotification.Outcome.GRANTED,
                        LanguageCode.en,
                        Dialect.FORMAL),
                    ""))
        .isInstanceOf(IllegalArgumentException.class);
    when(branding.resolveNotification(40L, "https://other.example.test"))
        .thenThrow(
            new IllegalArgumentException("Notification URL does not match recipient tenant"));
    assertThatThrownBy(
            () ->
                composer.compose(
                    mail(
                        CaseHandoverEmailNotification.Outcome.GRANTED,
                        LanguageCode.en,
                        Dialect.FORMAL),
                    "https://other.example.test"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void confirmedMailWithNoMatrixRoomHasNoFallbackActionLink() {
    prepareBrand();
    var mail =
        new CaseHandoverEmailNotification.Mail(
            12L,
            77L,
            null,
            CaseHandoverEmailNotification.Outcome.GRANTED,
            40L,
            "incoming@example.test",
            LanguageCode.en,
            Dialect.FORMAL);

    assertThatThrownBy(() -> composer.compose(mail, "https://tenant.example.test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Takeover confirmation email requires a Matrix room");
  }

  private void prepareBrand() {
    EmailBranding tenantBrand =
        new EmailBranding(
            "Configured Service",
            null,
            "#123456",
            "https://tenant.example.test/impressum",
            "https://tenant.example.test/datenschutz");
    when(branding.resolveNotification(40L, "https://tenant.example.test")).thenReturn(tenantBrand);
    Map<String, String> values = new HashMap<>();
    values.put("platformName", "Configured Service");
    values.put("offeringName", "Configured Service");
    values.put("operatorName", "");
    values.put("logoUrl", "");
    values.put("primaryColor", "#123456");
    values.put("accentColor", "#123456");
    values.put("orgName", "");
    values.put("orgAddress", "");
    values.put("contactLine", "");
    values.put("privacyUrl", "https://tenant.example.test/datenschutz");
    values.put("imprintUrl", "https://tenant.example.test/impressum");
    when(brandValues.values(eq(tenantBrand), eq(40L))).thenReturn(values);
  }

  private static CaseHandoverEmailNotification.Mail mail(
      CaseHandoverEmailNotification.Outcome outcome, LanguageCode language, Dialect dialect) {
    return new CaseHandoverEmailNotification.Mail(
        12L, 77L, "!room:example.test", outcome, 40L, "recipient@example.test", language, dialect);
  }
}
