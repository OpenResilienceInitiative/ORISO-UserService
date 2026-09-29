package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.Status;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdviceSeekerReplyEmailServiceTest {
  @Mock private SessionRepository sessions;
  @Mock private TenantService tenants;
  @Mock private TenantTemplateSupplier tenantTemplates;
  @Mock private EmailBrandingResolver branding;
  @Mock private OrisoEmailBrand emailBrand;
  @Mock private TenantSystemEmailRouteService routes;
  @Mock private TenantSystemEmailDelivery delivery;
  @Mock private ReplyEmailDeliveryWriter writer;

  private AdviceSeekerReplyEmailService service;

  @BeforeEach
  void setUp() {
    service =
        new AdviceSeekerReplyEmailService(
            sessions,
            tenants,
            tenantTemplates,
            branding,
            emailBrand,
            new OrisoEmailRenderer(),
            routes,
            delivery,
            writer);
    ReflectionTestUtils.setField(service, "multitenancyEnabled", true);
  }

  @Test
  void twoRepliesSendTwoNeutralMailsButAReplayedEventDoesNotSendAgain() {
    User asker = asker(true, "asker@example.net");
    Session session = session(asker);
    when(sessions.findByMatrixRoomId("!room")).thenReturn(Optional.of(session));
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.net");
    when(routes.resolve(7L))
        .thenReturn(
            Optional.of(
                new TenantSystemEmailRouteService.Route(
                    TenantSystemEmailRouteService.Mode.PLATFORM, null)));
    when(emailBrand.values("https://tenant.example.net", null)).thenReturn(neutralBrand());
    when(writer.reserve(eq("asker"), anyString(), eq(7L), eq(42L)))
        .thenReturn(1L, 2L)
        .thenThrow(new DataIntegrityViolationException("duplicate"));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));
    when(writer.claim(2L)).thenReturn(Optional.of(claim(2L)));

    service.onConsultantReply("!room", "$first");
    service.onConsultantReply("!room", "$second");
    service.onConsultantReply("!room", "$first");
    service.deliverPending(1L);
    service.deliverPending(2L);

    var rendered = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(delivery, org.mockito.Mockito.times(2))
        .sendReply(eq(7L), any(), eq("asker@example.net"), rendered.capture(), any());
    assertThat(rendered.getAllValues()).hasSize(2);
    for (var mail : rendered.getAllValues()) {
      assertThat(mail.subject()).isEqualTo("Sie haben eine neue Nachricht");
      assertThat(mail.html()).contains("https://tenant.example.net/sessions/user/view/session/42");
      assertThat(mail.html()).contains("mail=neue-nachricht");
      assertThat(mail.html()).doesNotContain("Counselling Centre", "Counsellor Real Name", "{{");
      assertThat(mail.text()).doesNotContain("Counselling Centre", "Counsellor Real Name", "{{");
    }
    verify(writer).finish(1L, Status.SENT);
    verify(writer).finish(2L, Status.SENT);
  }

  @Test
  void disabledEmailChoiceDoesNotReserveOrSend() {
    var session = session(asker(false, "asker@example.net"));
    when(sessions.findByMatrixRoomId("!room")).thenReturn(Optional.of(session));

    service.onConsultantReply("!room", "$first");

    verifyNoInteractions(writer, delivery);
  }

  @Test
  void absentAddressDoesNotReserveOrSend() {
    var session = session(asker(true, ""));
    when(sessions.findByMatrixRoomId("!room")).thenReturn(Optional.of(session));

    service.onConsultantReply("!room", "$first");

    verifyNoInteractions(writer, delivery);
  }

  @Test
  void pendingDeletionNeitherReservesNorDeliversReplyMail() {
    var asker = asker(true, "asker@example.net");
    asker.setDeleteDate(java.time.LocalDateTime.now());
    var session = session(asker);
    when(sessions.findByMatrixRoomId("!room")).thenReturn(Optional.of(session));
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));

    service.onConsultantReply("!room", "$first");
    service.deliverPending(1L);

    verify(writer, never()).reserve(anyString(), anyString(), anyLong(), anyLong());
    verify(writer).finish(1L, Status.REJECTED);
    verifyNoInteractions(delivery);
  }

  @Test
  void eventWithoutIdNeverClaimsAnUnrepeatableDelivery() {
    service.onConsultantReply("!room", null);
    verifyNoInteractions(sessions, writer, delivery);
  }

  @Test
  void invalidTenantUrlDefersTheQueuedEmailWithoutSMTPFallback() {
    var session = session(asker(true, "asker@example.net"));
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("not-a-public-url");
    when(routes.resolve(7L))
        .thenReturn(
            Optional.of(
                new TenantSystemEmailRouteService.Route(
                    TenantSystemEmailRouteService.Mode.PLATFORM, null)));

    service.deliverPending(1L);

    verify(writer).retryLater(1L);
    verify(delivery, never()).sendReply(anyLong(), any(), anyString(), any(), any());
  }

  @Test
  void uncertainSmtpResultStopsAutomaticReplay() {
    prepareReadyClaim();
    org.mockito.Mockito.doThrow(new IllegalStateException("SMTP acknowledgement lost"))
        .when(delivery)
        .sendReply(anyLong(), any(), anyString(), any(), any());

    assertThatThrownBy(() -> service.deliverPending(1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SMTP acknowledgement lost");

    verify(writer).finish(1L, Status.UNCERTAIN);
    verify(writer, never()).retryLater(1L);
  }

  @Test
  void tenantConfigurationRejectionRetriesAfterRepair() {
    prepareReadyClaim();
    org.mockito.Mockito.doThrow(
            new TenantSystemEmailRouteService.ConfigurationException("OWN route invalid"))
        .when(delivery)
        .sendReply(anyLong(), any(), anyString(), any(), any());

    service.deliverPending(1L);

    verify(writer).retryLater(1L);
    verify(writer, never()).finish(1L, Status.UNCERTAIN);
  }

  @Test
  void missingPlatformSmtpConfigurationDefersBeforeAttemptingSend() {
    when(sessions.findById(42L)).thenReturn(Optional.of(session(asker(true, "asker@example.net"))));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(routes.resolve(7L)).thenReturn(Optional.of(route));
    org.mockito.Mockito.doThrow(new IllegalStateException("SMTP is not configured"))
        .when(delivery)
        .requireConfigured(route);

    service.deliverPending(1L);

    verify(writer).retryLater(1L);
    verify(delivery, never()).sendReply(anyLong(), any(), anyString(), any(), any());
  }

  @Test
  void roomCannotRouteMailThroughAnotherRecipientsTenant() {
    var session = session(asker(true, "asker@example.net"));
    session.setTenantId(8L);
    when(sessions.findByMatrixRoomId("!room")).thenReturn(Optional.of(session));

    assertThatThrownBy(() -> service.onConsultantReply("!room", "$first"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Reply email recipient tenant is missing or inconsistent");
    verifyNoInteractions(routes, writer, delivery);
  }

  private static Session session(User asker) {
    return Session.builder()
        .id(42L)
        .tenantId(7L)
        .user(asker)
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("10000")
        .status(Session.SessionStatus.IN_PROGRESS)
        .build();
  }

  private static ReplyEmailDelivery claim(long id) {
    var claim = new ReplyEmailDelivery();
    claim.setId(id);
    claim.setSessionId(42L);
    claim.setTenantId(7L);
    claim.setRecipientUserId("asker");
    claim.setCorrelationId("ab2e5141-2f26-456a-9e46-0ff642918115");
    return claim;
  }

  private TenantSystemEmailRouteService.Route prepareReadyClaim() {
    var session = session(asker(true, "asker@example.net"));
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(writer.claim(1L)).thenReturn(Optional.of(claim(1L)));
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.net");
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(routes.resolve(7L)).thenReturn(Optional.of(route));
    when(emailBrand.values("https://tenant.example.net", null)).thenReturn(neutralBrand());
    return route;
  }

  private static User asker(boolean enabled, String address) {
    var user =
        User.builder()
            .userId("asker")
            .username("anonymous")
            .email(address)
            .tenantId(7L)
            .languageCode(LanguageCode.de)
            .languageFormal(true)
            .notificationsEnabled(true)
            .notificationsSettings("{\"newChatMessageNotificationEnabled\":" + enabled + "}")
            .build();
    return user;
  }

  private static Map<String, String> neutralBrand() {
    return new LinkedHashMap<>(
        Map.ofEntries(
            Map.entry("platformName", "ORISO"),
            Map.entry("offeringName", "ORISO"),
            Map.entry("operatorName", ""),
            Map.entry("orgName", ""),
            Map.entry("orgAddress", ""),
            Map.entry("contactLine", ""),
            Map.entry("logoUrl", ""),
            Map.entry("primaryColor", "#a5000a"),
            Map.entry("accentColor", "#cc1e1c"),
            Map.entry("appUrl", "https://tenant.example.net"),
            Map.entry("settingsUrl", "https://tenant.example.net/profile/einstellungen"),
            Map.entry("unsubscribeUrl", "https://tenant.example.net/profile/einstellungen/email"),
            Map.entry("privacyUrl", "https://tenant.example.net/datenschutz"),
            Map.entry("imprintUrl", "https://tenant.example.net/impressum")));
  }
}
