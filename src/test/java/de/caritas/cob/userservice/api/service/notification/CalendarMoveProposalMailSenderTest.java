package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.RenderedEmail;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CalendarMoveProposalMailSenderTest {

  private static final long TENANT = 40L;
  private static final String BASE_URL = "https://tenant.example.test";
  private static final String OWNER = "owner";

  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final IdentityClientConfig identity = mock(IdentityClientConfig.class);
  private final TenantSystemEmailRouteService routes = mock(TenantSystemEmailRouteService.class);
  private final TenantSystemEmailDelivery delivery = mock(TenantSystemEmailDelivery.class);
  private final TenantService tenants = mock(TenantService.class);
  private final TenantTemplateSupplier tenantUrls = mock(TenantTemplateSupplier.class);
  private final EmailBrandingResolver branding = mock(EmailBrandingResolver.class);
  private final TenantEmailBrandValues brandValues = mock(TenantEmailBrandValues.class);
  private final TenantSystemEmailRouteService.Route route =
      new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.OWN, "#123456");
  private final CalendarMoveProposalMailSender sender =
      new CalendarMoveProposalMailSender(
          consultants,
          identity,
          routes,
          delivery,
          tenants,
          tenantUrls,
          branding,
          brandValues,
          new OrisoEmailRenderer(true));
  private final CalendarMoveProposalEmailNotification.Mail mail =
      new CalendarMoveProposalEmailNotification.Mail(
          UUID.randomUUID(),
          OWNER,
          "Anna Proposer",
          // 10:00 and 14:30 in Berlin, winter time.
          Instant.parse("2026-11-18T09:00:00Z"),
          Instant.parse("2026-11-19T13:30:00Z"));
  private Consultant owner;

  @BeforeEach
  void setUp() {
    owner = new Consultant();
    owner.setId(OWNER);
    owner.setTenantId(TENANT);
    owner.setEmail("owner@example.test");
    owner.setNotificationsEnabled(true);
    owner.setNotificationsSettings("{\"appointmentNotificationEnabled\":true}");
    owner.setLanguageCode(LanguageCode.de);
    owner.setLanguageFormal(true);
    when(consultants.findByIdAndDeleteDateIsNull(OWNER)).thenReturn(Optional.of(owner));
    when(identity.getEmailDummySuffix()).thenReturn("@dummy.example.test");
    when(routes.resolve(TENANT)).thenReturn(Optional.of(route));
    var tenant = new RestrictedTenantDTO().id(TENANT).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(TENANT)).thenReturn(tenant);
    when(tenantUrls.getTenantBaseUrl(tenant)).thenReturn(BASE_URL);
    prepareBrand();
    when(delivery.sendConfirmed(anyLong(), any(), any(), any(), any())).thenReturn(true);
  }

  @Test
  void send_Should_TellTheOwnerWhoProposesWhichTimes_AndNothingAboutTheConversation() {
    sender.send(mail);

    var sent = ArgumentCaptor.forClass(RenderedEmail.class);
    verify(delivery)
        .sendConfirmed(
            eq(TENANT),
            eq(route),
            eq(CalendarMoveProposalMailSender.PURPOSE),
            eq("owner@example.test"),
            sent.capture());
    var email = sent.getValue();
    assertThat(email.subject()).isEqualTo("Vorschlag: Termin verschieben");
    assertThat(email.text())
        .contains(
            "Anna Proposer schlägt vor, Ihren Termin am 18.11.2026 um 10:00 Uhr auf den"
                + " 19.11.2026 um 14:30 Uhr zu verschieben.")
        .contains(BASE_URL + "/booking/events")
        .doesNotContain("{{");
    assertThat(email.html()).contains(BASE_URL + "/booking/events").doesNotContain("{{");
  }

  @Test
  void send_Should_FillEveryLanguageAndTone() {
    for (var language :
        new LanguageCode[] {
          LanguageCode.de,
          LanguageCode.en,
          LanguageCode.fr,
          LanguageCode.ru,
          LanguageCode.ti,
          LanguageCode.tr
        }) {
      for (var formal :
          language == LanguageCode.de ? new boolean[] {true, false} : new boolean[] {true}) {
        owner.setLanguageCode(language);
        owner.setLanguageFormal(formal);
        var tone = CalendarMoveProposalMailSender.toneOf(owner);
        var rendered =
            new OrisoEmailRenderer(true)
                .render(
                    "calendar-move-proposal",
                    tone,
                    Map.of(
                        "proposerName", "Anna Proposer",
                        "currentDate", "18.11.2026",
                        "currentTime", "10:00",
                        "proposedDate", "19.11.2026",
                        "proposedTime", "14:30",
                        "calendarUrl", BASE_URL + "/booking/events"));
        assertThat(rendered.text())
            .as(tone.directory())
            .contains("Anna Proposer", "18.11.2026", "10:00", "19.11.2026", "14:30")
            .contains(BASE_URL + "/booking/events");
      }
    }
    assertThat(CalendarMoveProposalMailSender.toneOf(owner)).isEqualTo(OrisoEmailRenderer.Tone.TR);
    owner.setLanguageCode(LanguageCode.de);
    owner.setLanguageFormal(false);
    assertThat(CalendarMoveProposalMailSender.toneOf(owner))
        .isEqualTo(OrisoEmailRenderer.Tone.DE_INFORMAL);
    owner.setLanguageCode(LanguageCode.ar);
    owner.setLanguageFormal(true);
    assertThat(CalendarMoveProposalMailSender.toneOf(owner))
        .as("a language without templates falls back to German")
        .isEqualTo(OrisoEmailRenderer.Tone.DE_FORMAL);
  }

  @Test
  void send_Should_RespectTheOwnersAppointmentMailSwitch() {
    owner.setNotificationsSettings("{\"appointmentNotificationEnabled\":false}");

    sender.send(mail);

    verifyNoInteractions(delivery);
  }

  @Test
  void send_Should_SendNothing_When_NotificationsAreOffOrTheAddressIsADummy() {
    owner.setNotificationsEnabled(false);
    sender.send(mail);
    owner.setNotificationsEnabled(true);
    owner.setEmail("owner@dummy.example.test");
    sender.send(mail);
    when(consultants.findByIdAndDeleteDateIsNull(OWNER)).thenReturn(Optional.empty());
    sender.send(mail);

    verifyNoInteractions(delivery);
  }

  @Test
  void send_Should_SendNothing_When_TheTraegerSwitchedSystemMailOff() {
    when(routes.resolve(TENANT)).thenReturn(Optional.empty());

    sender.send(mail);

    verifyNoInteractions(delivery);
  }

  @Test
  void send_Should_LogAFailedDelivery_InsteadOfThrowing() {
    when(delivery.sendConfirmed(anyLong(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("smtp down"));

    assertThatCode(() -> sender.send(mail)).doesNotThrowAnyException();
  }

  private void prepareBrand() {
    EmailBranding tenantBrand =
        new EmailBranding(
            "Configured Service",
            null,
            "#123456",
            BASE_URL + "/impressum",
            BASE_URL + "/datenschutz");
    when(branding.resolveNotification(TENANT, BASE_URL)).thenReturn(tenantBrand);
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
    values.put("privacyUrl", BASE_URL + "/datenschutz");
    values.put("imprintUrl", BASE_URL + "/impressum");
    when(brandValues.values(eq(tenantBrand), eq(TENANT))).thenReturn(values);
  }
}
