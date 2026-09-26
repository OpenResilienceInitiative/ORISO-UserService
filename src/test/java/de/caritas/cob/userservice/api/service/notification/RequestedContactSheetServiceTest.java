package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
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
    when(emailBrand.values("https://tenant.example.org", null))
        .thenReturn(new LinkedHashMap<>(Map.of("platformName", "ORISO")));
    when(renderer.render(eq("beraterin-kontakt"), any(), any()))
        .thenReturn(new OrisoEmailRenderer.RenderedEmail("Contact", "<p>Contact</p>", "Contact"));
    when(delivery.sendConfirmed(
            eq(7L),
            eq(route),
            eq(TenantSystemEmailDelivery.Purpose.CONTACT_SHEET),
            eq("asker@example.org"),
            any()))
        .thenReturn(true);

    service.send(42L, "asker");

    var values = ArgumentCaptor.forClass(Map.class);
    verify(renderer)
        .render(eq("beraterin-kontakt"), eq(OrisoEmailRenderer.Tone.EN), values.capture());
    org.assertj.core.api.Assertions.assertThat(values.getValue())
        .containsEntry("consultantName", "Centre")
        .containsEntry("consultantHours", "")
        .containsEntry("messageUrl", "https://tenant.example.org/sessions/user/view/session/42")
        .doesNotContainKey("bookingUrl");
    verify(delivery)
        .sendConfirmed(
            eq(7L),
            eq(route),
            eq(TenantSystemEmailDelivery.Purpose.CONTACT_SHEET),
            eq("asker@example.org"),
            any());
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
