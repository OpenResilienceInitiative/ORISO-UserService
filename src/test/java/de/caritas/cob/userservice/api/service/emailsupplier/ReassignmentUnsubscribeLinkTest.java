package de.caritas.cob.userservice.api.service.emailsupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.mailservice.generated.web.model.Dialect;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.util.List;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The two handover mails still go through the upstream MailService. Their footer link must name the
 * email settings screen and the mail it came from, so the screen can focus that mail's switch
 * (ORISO-Frontend#872).
 */
@ExtendWith(MockitoExtension.class)
class ReassignmentUnsubscribeLinkTest {

  @Mock private TenantTemplateSupplier tenantTemplateSupplier;

  @Test
  void requestMail_carriesTheOccasionOnItsUnsubscribeLink() {
    var mail =
        ReassignmentRequestEmailSupplier.builder()
            .receiverEmailAddress("asker@example.test")
            .receiverLanguageCode(LanguageCode.de)
            .receiverUsername("asker")
            .receiverDialect(Dialect.FORMAL)
            .applicationBaseUrl("https://app.example.test")
            .build()
            .generateEmails()
            .get(0);

    assertThat(valueOf(mail, "unsubscribe_url"))
        .isEqualTo("https://app.example.test/profile/einstellungen/email?mail=uebergabe-angefragt");
  }

  @Test
  void confirmationMail_carriesTheOccasionOnItsUnsubscribeLink() {
    var mail =
        ReassignmentConfirmationEmailSupplier.builder()
            .receiverConsultant(consultant())
            .senderConsultantName("sender")
            .applicationBaseUrl("https://app.example.test/")
            .build()
            .generateEmails()
            .get(0);

    assertThat(valueOf(mail, "unsubscribe_url"))
        .isEqualTo(
            "https://app.example.test/profile/einstellungen/email?mail=uebergabe-bestaetigt");
  }

  @Test
  void multiTenantMail_buildsTheLinkOnTheTenantsOwnAddress() {
    when(tenantTemplateSupplier.getTemplateAttributes())
        .thenReturn(
            List.of(
                new TemplateDataDTO().key("tenant_name").value("Träger"),
                new TemplateDataDTO().key("url").value("https://traeger.example.test")));

    var mail =
        ReassignmentConfirmationEmailSupplier.builder()
            .receiverConsultant(consultant())
            .senderConsultantName("sender")
            .tenantTemplateSupplier(tenantTemplateSupplier)
            .multiTenancyEnabled(true)
            .build()
            .generateEmails()
            .get(0);

    assertThat(valueOf(mail, "unsubscribe_url"))
        .isEqualTo(
            "https://traeger.example.test/profile/einstellungen/email?mail=uebergabe-bestaetigt");
  }

  @Test
  void mailWithoutAnAddress_sendsNoUnsubscribeLinkRatherThanARelativeOne() {
    when(tenantTemplateSupplier.getTemplateAttributes())
        .thenReturn(List.of(new TemplateDataDTO().key("tenant_name").value("Träger")));

    var mail =
        ReassignmentRequestEmailSupplier.builder()
            .receiverEmailAddress("asker@example.test")
            .receiverLanguageCode(LanguageCode.de)
            .receiverUsername("asker")
            .receiverDialect(Dialect.FORMAL)
            .tenantTemplateSupplier(tenantTemplateSupplier)
            .multiTenancyEnabled(true)
            .build()
            .generateEmails()
            .get(0);

    assertThat(mail.getTemplateData())
        .extracting(TemplateDataDTO::getKey)
        .doesNotContain("unsubscribe_url");
  }

  private static Consultant consultant() {
    var consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setLanguageCode(LanguageCode.de);
    return consultant;
  }

  private static String valueOf(MailDTO mail, String key) {
    return mail.getTemplateData().stream()
        .filter(data -> key.equals(data.getKey()))
        .map(TemplateDataDTO::getValue)
        .findFirst()
        .orElse(null);
  }
}
