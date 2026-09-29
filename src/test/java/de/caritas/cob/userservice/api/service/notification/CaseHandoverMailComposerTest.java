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
      new CaseHandoverMailComposer(new OrisoEmailRenderer(), branding, brandValues);

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
        assertThat(rendered.subject()).isNotBlank().doesNotContain("#123", "Counsellor Name");
        assertThat(rendered.text())
            .contains(
                "https://tenant.example.test/sessions/user/view/session/77?caseHandoverRequestId=12")
            .doesNotContain("#123", "Counsellor Name", "{{");
        assertThat(rendered.html()).doesNotContain("{{");
        var confirmed =
            composer.compose(
                mail(CaseHandoverEmailNotification.Outcome.GRANTED, language, dialect),
                "https://tenant.example.test");
        assertThat(confirmed.subject()).isNotBlank().doesNotContain("{{", "ORISO");
        assertThat(confirmed.text())
            .contains("https://tenant.example.test")
            .doesNotContain("{{", "Your access to the previous");
        assertThat(confirmed.html()).doesNotContain("{{");
      }
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
        12L, 77L, outcome, 40L, "recipient@example.test", language, dialect);
  }
}
