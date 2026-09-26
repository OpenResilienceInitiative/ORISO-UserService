package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.mailservice.generated.web.model.Dialect;
import de.caritas.cob.userservice.mailservice.generated.web.model.LanguageCode;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NotificationMailComposerTest {
  private final EmailBrandingResolver brandingResolver = mock(EmailBrandingResolver.class);
  private final TenantEmailBrandValues brandValues = mock(TenantEmailBrandValues.class);
  private NotificationMailComposer composer;

  @BeforeEach
  void setUp() {
    composer =
        new NotificationMailComposer(new OrisoEmailRenderer(), brandingResolver, brandValues);
    brand(7L, "Träger Sieben");
    brand(8L, "Träger Acht");
  }

  @ParameterizedTest
  @CsvSource({
    "enquiry-notification-consultant,neue-anfrage,Neue Anfrage in Ihrer Beratungsstelle",
    "direct-enquiry-notification-consultant,direkte-anfrage,Eine Anfrage richtet sich direkt an Sie",
    "assign-enquiry-notification,anfrage-zugewiesen,Neue Beratungsanfrage",
    "daily-enquiry-notification,tagesuebersicht,Ihre Tagesübersicht"
  })
  void composesTheFourExistingOccasionsWithCompleteFactsAndBothMimeParts(
      String legacyTemplate, String designTemplate, String expectedSubject) {
    var result = composer.compose(mail(legacyTemplate), 7L);

    assertThat(result.subject()).isEqualTo(expectedSubject);
    assertThat(result.html())
        .contains("Träger Sieben", "https://tenant.example.org/sessions/consultant/");
    assertThat(result.text())
        .contains("Träger Sieben", "https://tenant.example.org/sessions/consultant/");
    assertThat(result.html()).doesNotContain("{{", ">—<");
    assertThat(result.text()).doesNotContain("{{", "—");
    assertThat(result.html())
        .contains("https://tenant.example.org/profile/einstellungen/email?mail=" + designTemplate);
    assertThat(result.text())
        .contains("https://tenant.example.org/profile/einstellungen/email?mail=" + designTemplate);
  }

  @Test
  void usesTheExplicitTenantForEachMailWithoutChangingThreadState() {
    var first = composer.compose(mail("daily-enquiry-notification"), 7L);
    var second = composer.compose(mail("daily-enquiry-notification"), 8L);

    assertThat(first.html()).contains("Träger Sieben").doesNotContain("Träger Acht");
    assertThat(second.html()).contains("Träger Acht").doesNotContain("Träger Sieben");
  }

  @Test
  void refusesMissingFactsInsteadOfInventingDashes() {
    var incomplete = mail("enquiry-notification-consultant");
    incomplete.getTemplateData().removeIf(item -> "requestTopic".equals(item.getKey()));

    assertThatThrownBy(() -> composer.compose(incomplete, 7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requestTopic");
  }

  @Test
  void refusesMissingOrMalformedTenantUrl() {
    var missing = mail("daily-enquiry-notification");
    missing.getTemplateData().removeIf(item -> "url".equals(item.getKey()));
    assertThatThrownBy(() -> composer.compose(missing, 7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("url");

    var malformed = mail("daily-enquiry-notification");
    malformed.getTemplateData().removeIf(item -> "url".equals(item.getKey()));
    malformed.addTemplateDataItem(new TemplateDataDTO().key("url").value("https://.invalid"));
    assertThatThrownBy(() -> composer.compose(malformed, 7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("url");
  }

  @Test
  void refusesUnsupportedOccasions() {
    assertThatThrownBy(() -> composer.compose(mail("free-text"), 7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("free-text");
  }

  @Test
  void composesEveryNewAppLanguageInBothMimeParts() {
    for (var language :
        List.of(LanguageCode.FR, LanguageCode.RU, LanguageCode.TI, LanguageCode.TR)) {
      var result = composer.compose(mail("daily-enquiry-notification").language(language), 7L);
      assertThat(result.html())
          .contains("<html lang=\"" + language.getValue().toLowerCase() + "\">");
      assertThat(result.subject()).isNotBlank().doesNotContain("Ihre Tagesübersicht");
      assertThat(result.text()).isNotBlank().doesNotContain("Ihre Tagesübersicht");
    }
  }

  @Test
  void refusesConflictingTenantMetadata() {
    var wrongTenant = mail("daily-enquiry-notification");
    wrongTenant.addTemplateDataItem(new TemplateDataDTO().key("tenantId").value("8"));

    assertThatThrownBy(() -> composer.compose(wrongTenant, 7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId");
  }

  private void brand(long tenantId, String name) {
    var branding = new EmailBranding(name, null, "#1c4f8f", null, null);
    when(brandingResolver.resolveNotification(tenantId, "https://tenant.example.org"))
        .thenReturn(branding);
    Map<String, String> values = new LinkedHashMap<>();
    values.put("platformName", name);
    values.put("orgName", name);
    values.put("orgAddress", "Example Street 1");
    values.put("contactLine", "contact@example.org");
    values.put("logoUrl", "");
    values.put("primaryColor", "#1c4f8f");
    values.put("accentColor", "#1c4f8f");
    values.put("appUrl", "https://tenant.example.org");
    values.put("settingsUrl", "https://tenant.example.org/profile/einstellungen");
    values.put("privacyUrl", "https://tenant.example.org/datenschutz");
    values.put("imprintUrl", "https://tenant.example.org/impressum");
    values.put("unsubscribeUrl", "https://tenant.example.org/profile/einstellungen/email");
    when(brandValues.values(branding, tenantId)).thenReturn(values);
  }

  private MailDTO mail(String template) {
    return new MailDTO()
        .template(template)
        .email("recipient@example.org")
        .language(LanguageCode.DE)
        .dialect(Dialect.FORMAL)
        .templateData(
            new ArrayList<>(
                List.of(
                    new TemplateDataDTO().key("url").value("https://tenant.example.org"),
                    new TemplateDataDTO().key("requestTopic").value("Housing"),
                    new TemplateDataDTO().key("requestPostcode").value("12345"),
                    new TemplateDataDTO().key("requestReceivedAt").value("25 September 2026"),
                    new TemplateDataDTO().key("enquiries").value("3"),
                    new TemplateDataDTO().key("oldestRequestAge").value("2 days"),
                    new TemplateDataDTO().key("digestGeneratedAt").value("25 September 2026"))));
  }
}
