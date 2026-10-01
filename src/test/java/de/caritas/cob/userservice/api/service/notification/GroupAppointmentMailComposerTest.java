package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox;
import de.caritas.cob.userservice.api.model.GroupAppointmentOccurrenceState;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.LocalDateTime;
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

@ExtendWith(MockitoExtension.class)
class GroupAppointmentMailComposerTest {
  @Mock TenantService tenants;
  @Mock TenantTemplateSupplier tenantTemplates;
  @Mock EmailBrandingResolver branding;
  @Mock TenantEmailBrandValues brandValues;
  @Mock OrisoEmailRenderer renderer;
  @Mock TenantSystemEmailRouteService routes;
  @InjectMocks GroupAppointmentMailComposer composer;

  private GroupAppointmentMailOutbox mail;
  private GroupAppointmentMailEligibilityService.Eligible eligible;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(composer, "multitenancyEnabled", true);
    ReflectionTestUtils.setField(composer, "singleDomainMultitenancy", false);
    ReflectionTestUtils.setField(composer, "applicationBaseUrl", "https://oriso.example");
    var owner = new Consultant();
    owner.setTenantId(7L);
    var series = new Chat();
    series.setId(42L);
    series.setChatOwner(owner);
    mail =
        GroupAppointmentMailOutbox.builder()
            .seriesId(42L)
            .recipientRole(GroupAppointmentMailOutbox.RecipientRole.COUNSELOR)
            .eventType(GroupAppointmentMailOutbox.EventType.REMINDER)
            .scheduledStartUtc(LocalDateTime.of(2027, 3, 28, 7, 0))
            .timezone("Europe/Berlin")
            .build();
    eligible =
        new GroupAppointmentMailEligibilityService.Eligible(
            series,
            new GroupAppointmentOccurrenceState(),
            new GroupAppointmentEmailRecipientService.Recipient(
                "counselor", "counselor@example.org", OrisoEmailRenderer.Tone.DE_FORMAL));
  }

  @Test
  void composesRoleSpecificMailWithOwnerOriginAndDstCorrectTime() {
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("group-owner");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://group-owner.oriso.example");
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.OWN, "#a5000a");
    when(routes.resolve(7L)).thenReturn(Optional.of(route));
    when(branding.resolveNotification(7L, "https://group-owner.oriso.example"))
        .thenReturn(EmailBranding.neutral());
    when(brandValues.values(any(), eq(7L))).thenReturn(Map.of("platformName", "ORISO"));
    var rendered = new OrisoEmailRenderer.RenderedEmail("Termin", "<p>Termin</p>", "Termin");
    when(renderer.render(
            eq("selbsthilfe-termin-erinnerung-beratung"),
            eq(OrisoEmailRenderer.Tone.DE_FORMAL),
            any()))
        .thenReturn(rendered);

    var result = composer.compose(mail, eligible).orElseThrow();

    assertThat(result.tenantId()).isEqualTo(7L);
    assertThat(result.route()).isEqualTo(route);
    assertThat(result.purpose())
        .isEqualTo(TenantSystemEmailDelivery.Purpose.SELF_HELP_APPOINTMENT_REMINDER);
    var values = ArgumentCaptor.forClass(Map.class);
    verify(renderer)
        .render(
            eq("selbsthilfe-termin-erinnerung-beratung"),
            eq(OrisoEmailRenderer.Tone.DE_FORMAL),
            values.capture());
    assertThat(values.getValue())
        .containsEntry("appointmentUrl", "https://group-owner.oriso.example/login?seriesId=42");
    assertThat(String.valueOf(values.getValue().get("appointmentTime"))).startsWith("09:00 ");
  }

  @Test
  void missingTenantSubdomainFailsWithoutAPlatformUrlFallback() {
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(new RestrictedTenantDTO().id(7L));

    assertThatThrownBy(() -> composer.compose(mail, eligible))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("subdomain is missing");
  }
}
