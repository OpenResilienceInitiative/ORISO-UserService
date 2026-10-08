package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationFixture;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RequestedContactSheetServiceTest {
  @Mock private SessionRepository sessions;
  @Mock private AgencyContactDetailsClient agencies;
  @Mock private TenantService tenants;
  @Mock private TenantTemplateSupplier tenantTemplates;
  @Mock private EmailBrandingResolver branding;
  @Mock private OrisoEmailBrand emailBrand;
  @Mock private OrisoEmailRenderer renderer;
  @Mock private TenantSystemEmailRouteService routes;
  @Mock private TenantSystemEmailDelivery delivery;
  @InjectMocks private RequestedContactSheetService service;

  @BeforeEach
  void configure() {
    ReflectionTestUtils.setField(service, "multitenancyEnabled", true);
  }

  @Test
  void sessionOwnerCanRequestMaintainedContactDetails() {
    var session = session(user("asker", "asker@example.org"));
    when(sessions.findById(42L)).thenReturn(Optional.of(session));
    when(agencies.read(9L, 7L))
        .thenReturn(
            new AgencyContactDetailsClient.ContactDetails(
                9L, 7L, "Centre", "+49 30 123", "centre@example.org", null));
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.org");
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(routes.resolve(7L)).thenReturn(Optional.of(route));
    var resolvedBrand =
        new EmailBranding(
            "Neighbour support",
            "https://assets.example.org/tenant-logo.png",
            "#124078",
            "https://tenant.example.org/impressum",
            "https://tenant.example.org/datenschutz");
    when(branding.resolveNotification(7L, "https://tenant.example.org")).thenReturn(resolvedBrand);
    when(branding.platformName()).thenReturn("Wayfinder");
    var actualBrand = new OrisoEmailBrand(SenderOrganisationFixture.platformOwner(), branding);
    var actualRenderer = spy(new OrisoEmailRenderer(true));
    ReflectionTestUtils.setField(service, "emailBrand", actualBrand);
    ReflectionTestUtils.setField(service, "renderer", actualRenderer);
    when(delivery.sendConfirmed(
            eq(7L),
            eq(route),
            eq(TenantSystemEmailDelivery.Purpose.CONTACT_SHEET),
            eq("asker@example.org"),
            any()))
        .thenReturn(true);

    service.send(42L, "asker");

    var values = ArgumentCaptor.forClass(Map.class);
    verify(actualRenderer)
        .render(eq("beraterin-kontakt"), eq(OrisoEmailRenderer.Tone.EN), values.capture());
    assertThat(values.getValue())
        .containsEntry("consultantName", "Centre")
        .containsEntry("consultantHours", "")
        .containsEntry("messageUrl", "https://tenant.example.org/sessions/user/view/session/42")
        .doesNotContainKey("bookingUrl");
    var rendered = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(delivery)
        .sendConfirmed(
            eq(7L),
            eq(route),
            eq(TenantSystemEmailDelivery.Purpose.CONTACT_SHEET),
            eq("asker@example.org"),
            rendered.capture());
    var email = rendered.getValue();
    assertThat(email.subject()).doesNotContain("Neighbour support", "Centre", "Wayfinder");
    assertThat(email.html())
        .contains("Neighbour support", "https://assets.example.org/tenant-logo.png", "#124078")
        .contains("https://tenant.example.org/impressum", "https://tenant.example.org/datenschutz")
        .doesNotContain("{{", "Online-Beratung");
    assertThat(email.text())
        .contains("Neighbour support", "Centre", "centre@example.org", "+49 30 123")
        .contains("https://tenant.example.org/impressum", "https://tenant.example.org/datenschutz")
        .doesNotContain("{{", "Online-Beratung");
    verify(branding).resolveNotification(7L, "https://tenant.example.org");
    verify(branding).platformName();
    verifyNoMoreInteractions(branding);
    verifyNoInteractions(emailBrand, renderer);
  }

  @Test
  void anotherUserCannotRequestTheSessionContactSheet() {
    when(sessions.findById(42L))
        .thenReturn(Optional.of(session(user("asker", "asker@example.org"))));

    assertThatThrownBy(() -> service.send(42L, "other")).isInstanceOf(ForbiddenException.class);

    verifyNoInteractions(agencies, delivery);
  }

  @Test
  void missingAddressFailsBeforeAnyAgencyReadOrSmtpAttempt() {
    when(sessions.findById(42L)).thenReturn(Optional.of(session(user("asker", ""))));

    assertThatThrownBy(() -> service.send(42L, "asker"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("An email address is required");

    verifyNoInteractions(agencies, delivery);
  }

  @Test
  void sessionTenantMustMatchTheRecipient() {
    var session = session(user("asker", "asker@example.org"));
    session.setTenantId(8L);
    when(sessions.findById(42L)).thenReturn(Optional.of(session));

    assertThatThrownBy(() -> service.send(42L, "asker"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Session has no valid agency");

    verifyNoInteractions(agencies, delivery);
  }

  @Test
  void inactiveSessionFailsBeforeAgencyReadOrDelivery() {
    var inactive = session(user("asker", "asker@example.org"));
    inactive.setStatus(Session.SessionStatus.DONE);
    when(sessions.findById(42L)).thenReturn(Optional.of(inactive));

    assertThatThrownBy(() -> service.send(42L, "asker"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Session is no longer active");

    verifyNoInteractions(agencies, delivery);
  }

  @Test
  void dummyAddressFailsBeforeAgencyReadOrDelivery() {
    ReflectionTestUtils.setField(service, "emailDummySuffix", "@dummy.invalid");
    when(sessions.findById(42L))
        .thenReturn(Optional.of(session(user("asker", "test@dummy.invalid"))));

    assertThatThrownBy(() -> service.send(42L, "asker"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("An email address is required");

    verifyNoInteractions(agencies, delivery);
  }

  @Test
  void noMaintainedContactMethodFailsWithoutDelivery() {
    when(sessions.findById(42L))
        .thenReturn(Optional.of(session(user("asker", "asker@example.org"))));
    when(agencies.read(9L, 7L))
        .thenReturn(new AgencyContactDetailsClient.ContactDetails(9L, 7L, "Centre", "", "", null));

    assertThatThrownBy(() -> service.send(42L, "asker"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("no maintained contact method");

    verifyNoInteractions(delivery);
  }

  @Test
  void missingSmtpRouteFailsWithoutDelivery() {
    prepareContactAndTenant();
    when(routes.resolve(7L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.send(42L, "asker"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMTP route is missing");

    verifyNoInteractions(delivery);
  }

  @Test
  void unconfirmedSendIsAnErrorRatherThanSuccess() {
    prepareContactAndTenant();
    when(routes.resolve(7L))
        .thenReturn(
            Optional.of(
                new TenantSystemEmailRouteService.Route(
                    TenantSystemEmailRouteService.Mode.PLATFORM, null)));
    when(branding.resolveNotification(7L, "https://tenant.example.org"))
        .thenReturn(
            new EmailBranding(
                "Platform",
                null,
                "#124078",
                "https://tenant.example.org/imprint",
                "https://tenant.example.org/privacy"));
    when(emailBrand.valuesForResolvedBrand(eq("https://tenant.example.org"), any()))
        .thenReturn(new HashMap<>());
    when(renderer.render(eq("beraterin-kontakt"), eq(OrisoEmailRenderer.Tone.EN), any()))
        .thenReturn(new OrisoEmailRenderer.RenderedEmail("Contact", "<p>Contact</p>", "Contact"));
    when(delivery.sendConfirmed(
            eq(7L),
            any(),
            eq(TenantSystemEmailDelivery.Purpose.CONTACT_SHEET),
            eq("asker@example.org"),
            any()))
        .thenReturn(false);

    assertThatThrownBy(() -> service.send(42L, "asker"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMTP delivery failed");

    verify(delivery)
        .sendConfirmed(
            eq(7L),
            any(),
            eq(TenantSystemEmailDelivery.Purpose.CONTACT_SHEET),
            eq("asker@example.org"),
            any());
  }

  @Test
  void malformedPublicUrlHasNamedErrorAndNoSmtpAttempt() {
    when(sessions.findById(42L))
        .thenReturn(Optional.of(session(user("asker", "asker@example.org"))));
    when(agencies.read(9L, 7L))
        .thenReturn(
            new AgencyContactDetailsClient.ContactDetails(9L, 7L, "Centre", "123", "", null));
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://bad host");

    assertThatThrownBy(() -> service.send(42L, "asker"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Contact-sheet app URL is invalid")
        .hasNoCause();

    verifyNoInteractions(routes, delivery);
  }

  private void prepareContactAndTenant() {
    when(sessions.findById(42L))
        .thenReturn(Optional.of(session(user("asker", "asker@example.org"))));
    when(agencies.read(9L, 7L))
        .thenReturn(
            new AgencyContactDetailsClient.ContactDetails(
                9L, 7L, "Centre", "123", "centre@example.org", null));
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("tenant");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.org");
  }

  private static Session session(User user) {
    return Session.builder()
        .id(42L)
        .tenantId(7L)
        .agencyId(9L)
        .user(user)
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("10000")
        .status(Session.SessionStatus.IN_PROGRESS)
        .build();
  }

  private static User user(String id, String email) {
    return User.builder()
        .userId(id)
        .username("seeker")
        .tenantId(7L)
        .email(email)
        .languageCode(LanguageCode.en)
        .languageFormal(true)
        .build();
  }
}
