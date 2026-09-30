package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.config.apiclient.MailServiceApiControllerFactory;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisation;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationFixture;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailClient;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailDelivery;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Exercises the real producer, selective seam, composition and MIME assembly with mocked SMTP wire
 * and environment integrations.
 */
class InquiryAcceptedMailDeliveryTest {
  private static final String TENANT_URL = "https://tenant-seven.example.org";
  private final ApplicationSettingsService settings = mock();
  private final TenantSystemEmailClient tenantClient = mock();
  private final TenantSystemEmailRouteService routes = mock();
  private final MailServiceApiControllerFactory upstream = mock();
  private final TenantTemplateSupplier tenantTemplates = mock();
  private final EmailBrandingResolver branding = mock();
  private EmailNotificationFacade facade;
  private User recipient;
  private Consultant counsellor;

  @BeforeEach
  void setUp() {
    var organisations =
        SenderOrganisationFixture.resolving(
            new SenderOrganisation(
                "Platform operator", "Operator Street", "contact@operator.example.org"),
            Map.of(
                7L,
                new SenderOrganisation(
                    "Tenant Seven Company", "Tenant Street", "contact@tenant-seven.example.org")));
    var brand = new OrisoEmailBrand(organisations);
    ReflectionTestUtils.setField(brand, "platformName", "Independent Platform");
    var values = new TenantEmailBrandValues(brand, organisations, "https://platform.example.org");
    var composer = new NotificationMailComposer(new OrisoEmailRenderer(true), branding, values);
    var delivery =
        new TenantSystemEmailDelivery(
            tenantClient, new PlatformSmtpSettingsProvider(settings), new OrisoEmailDispatcher());
    var sender = new NotificationMailSender(composer, routes, delivery);
    var service = new MailService(mock(), upstream, sender);
    IdentityClientConfig identity = mock();
    when(identity.getEmailDummySuffix()).thenReturn("@dummy.invalid");
    facade =
        new EmailNotificationFacade(
            service,
            brand,
            mock(),
            mock(),
            identity,
            mock(),
            mock(),
            mock(),
            tenantTemplates,
            mock(),
            mock(ReleaseToggleService.class));
    ReflectionTestUtils.setField(facade, "applicationBaseUrl", "https://platform.example.org");
    ReflectionTestUtils.setField(facade, "multiTenancyEnabled", true);
    when(tenantTemplates.getTemplateAttributes())
        .thenReturn(List.of(new TemplateDataDTO().key("url").value(TENANT_URL)));
    when(branding.resolveNotification(7L, TENANT_URL))
        .thenReturn(
            new EmailBranding(
                "Confidential Tenant Label",
                null,
                "#1c4f8f",
                TENANT_URL + "/impressum",
                TENANT_URL + "/datenschutz"));
    when(routes.resolve(7L))
        .thenReturn(
            Optional.of(
                new TenantSystemEmailRouteService.Route(
                    TenantSystemEmailRouteService.Mode.PLATFORM, null)));
    when(settings.getGlobalSmtpSettingsSnapshot())
        .thenReturn(
            Optional.of(PlatformSmtpSettingsFixture.credentials("smtp-user", "fixture-password")));
    recipient = new User("asker", null, "PrivateAskerName", "asker@example.org", false);
    recipient.setTenantId(7L);
    recipient.setLanguageCode(LanguageCode.en);
    counsellor = new Consultant();
    counsellor.setFirstName("PrivateCounsellorName");
    counsellor.setLastName("PrivateCounsellorSurname");
  }

  @ParameterizedTest
  @CsvSource({
    "de,true,Neue Nachricht auf Independent Platform,Bitte melden Sie sich an.",
    "de,false,Neue Nachricht auf Independent Platform,Bitte melde dich an.",
    "en,true,New message on Independent Platform,Please sign in.",
    "fr,true,Nouveau message sur Independent Platform,Veuillez vous connecter.",
    "ru,true,Новое сообщение на Independent Platform,'Пожалуйста, войдите в систему.'",
    "ti,true,ሓድሽ መልእኽቲ ኣብ Independent Platform,በጃኹም እተዉ።",
    "tr,true,Independent Platform üzerinde yeni mesaj,Lütfen giriş yapın."
  })
  void automaticNoticeReachesActualMimeUsingAdminSettingsInAllSevenVariants(
      LanguageCode language, boolean formal, String subject, String prompt) throws Exception {
    recipient.setLanguageCode(language);
    recipient.setLanguageFormal(formal);
    List<MimeMessage> sent = new ArrayList<>();
    try (var wire = mockStatic(OrisoSmtpTransport.class, CALLS_REAL_METHODS)) {
      wire.when(() -> OrisoSmtpTransport.send(any(Message.class)))
          .thenAnswer(
              call -> {
                var mime = (MimeMessage) call.getArgument(0);
                mime.saveChanges();
                sent.add(mime);
                return null;
              });

      facade.sendInquiryAcceptedNotification(recipient, counsellor, new TenantData(7L, "seven"));
    }

    assertThat(sent).hasSize(1);
    var message = sent.get(0);
    assertThat(message.getSubject()).isEqualTo(subject);
    assertThat(message.getAllRecipients()[0].toString()).isEqualTo("asker@example.org");
    assertThat(message.getFrom()[0].toString()).isEqualTo("noreply@example.org");
    assertThat(message.isMimeType("multipart/alternative")).isTrue();
    var parts = (Multipart) message.getContent();
    assertThat(parts.getCount()).isEqualTo(2);
    assertThat(parts.getBodyPart(0).isMimeType("text/plain")).isTrue();
    assertThat(parts.getBodyPart(1).isMimeType("text/html")).isTrue();
    for (int part = 0; part < parts.getCount(); part++) {
      assertThat(parts.getBodyPart(part).getContent().toString())
          .contains(prompt, TENANT_URL, "Independent Platform")
          .doesNotContain(
              "PrivateAskerName",
              "PrivateCounsellorName",
              "PrivateCounsellorSurname",
              "Confidential Tenant Label",
              "https://platform.example.org",
              "{{");
    }
    verify(settings).getGlobalSmtpSettingsSnapshot();
    verifyNoInteractions(tenantClient, upstream);
  }

  @Test
  void ownModeUsesOnlyTheSelectedTenantRelayAndApprovedNoticePurpose() {
    when(routes.resolve(7L))
        .thenReturn(
            Optional.of(
                new TenantSystemEmailRouteService.Route(
                    TenantSystemEmailRouteService.Mode.OWN, null)));

    facade.sendInquiryAcceptedNotification(recipient, counsellor, new TenantData(7L, "seven"));

    var email = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(tenantClient)
        .deliver(eq(7L), eq("FREE_TEXT_NOTICE"), eq("asker@example.org"), email.capture());
    assertThat(email.getValue().subject()).isEqualTo("New message on Independent Platform");
    assertThat(email.getValue().html()).contains(TENANT_URL, "Please sign in.");
    verifyNoInteractions(settings, upstream);
  }

  @Test
  void anotherRequestTenantCannotReachAnyMailRoute() {
    facade.sendInquiryAcceptedNotification(recipient, counsellor, new TenantData(8L, "eight"));
    verifyNoInteractions(routes, settings, tenantClient, upstream);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "https://.invalid"})
  void missingOrInvalidTenantUrlNeverUsesThePlatformUrlOrLegacyService(String url) {
    when(tenantTemplates.getTemplateAttributes())
        .thenReturn(List.of(new TemplateDataDTO().key("url").value(url)));
    facade.sendInquiryAcceptedNotification(recipient, counsellor, new TenantData(7L, "seven"));
    verifyNoInteractions(settings, tenantClient, upstream);
  }

  @Test
  void incompleteAdminSettingsNeverFallBackToLegacyMailService() {
    when(settings.getGlobalSmtpSettingsSnapshot()).thenReturn(Optional.empty());
    facade.sendInquiryAcceptedNotification(recipient, counsellor, new TenantData(7L, "seven"));
    verify(settings).getGlobalSmtpSettingsSnapshot();
    verifyNoInteractions(tenantClient, upstream);
  }
}
