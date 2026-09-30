package de.caritas.cob.userservice.api.service.servicenotice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeCampaignRepository;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeDraftService.DraftInput;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServiceNoticeDraftServiceTest {
  private final ServiceNoticeCampaignRepository campaigns =
      mock(ServiceNoticeCampaignRepository.class);
  private final EmailBrandingResolver branding = mock(EmailBrandingResolver.class);
  private final TenantEmailBrandValues brandValues = mock(TenantEmailBrandValues.class);
  private final AtomicReference<ServiceNoticeCampaign> stored = new AtomicReference<>();
  private ServiceNoticeDraftService service;

  @BeforeEach
  void setUp() {
    service =
        new ServiceNoticeDraftService(
            campaigns, new OrisoEmailRenderer(true), branding, brandValues);
    when(campaigns.findByCampaignKey("maintenance-1"))
        .thenAnswer(call -> Optional.ofNullable(stored.get()));
    when(campaigns.saveAndFlush(any(ServiceNoticeCampaign.class)))
        .thenAnswer(
            call -> {
              ServiceNoticeCampaign draft = call.getArgument(0);
              draft.setId(42L);
              stored.set(draft);
              return draft;
            });
    var brand =
        new EmailBranding(
            "Independent Platform",
            null,
            "#123456",
            "https://app.example.org/impressum",
            "https://app.example.org/datenschutz");
    when(branding.resolve(0L)).thenReturn(brand);
    var values = new HashMap<String, String>();
    values.put("platformName", "Independent Platform");
    values.put("orgName", "Example Charity");
    values.put("orgAddress", "Main Street 1");
    values.put("contactLine", "help@example.org");
    values.put("primaryColor", "#123456");
    values.put("accentColor", "#123456");
    values.put("logoUrl", "");
    values.put("imprintUrl", "https://app.example.org/impressum");
    values.put("privacyUrl", "https://app.example.org/datenschutz");
    values.put("settingsUrl", "https://app.example.org/profile/settings");
    values.put("unsubscribeUrl", "https://app.example.org/profile/settings/notifications");
    when(brandValues.values(brand, null)).thenReturn(values);
  }

  @Test
  void sameCampaignKeyCanBeRetriedButCannotSilentlyChangeTheDraft() {
    var input = input("https://status.operator.dev/maintenance");
    assertThat(service.save("maintenance-1", input, "operator-1").status()).isEqualTo("DRAFT");
    assertThat(service.save("maintenance-1", input, "operator-1").campaignKey())
        .isEqualTo("maintenance-1");
    verify(campaigns).saveAndFlush(any(ServiceNoticeCampaign.class));
    assertThatThrownBy(
            () ->
                service.save(
                    "maintenance-1", input("https://status.operator.dev/another"), "operator-1"))
        .isInstanceOf(ServiceNoticeDraftService.DraftConflict.class);
    assertThatThrownBy(() -> service.save("maintenance-1", input, "operator-2"))
        .isInstanceOf(ServiceNoticeDraftService.DraftConflict.class);
    assertThat(stored.get().getStatusUrl()).isEqualTo("https://status.operator.dev/maintenance");
  }

  @Test
  void rejectsIncompleteWindowAndNonPublicStatusLinkBeforeWriting() {
    assertThatThrownBy(
            () ->
                service.save(
                    "maintenance-1",
                    new DraftInput(
                        LocalDate.of(2026, 10, 2),
                        LocalTime.of(15, 0),
                        LocalTime.of(14, 0),
                        "https://status.operator.dev"),
                    "operator-1"))
        .isInstanceOf(IllegalArgumentException.class);
    for (String url :
        new String[] {
          "http://status.operator.dev",
          "https://localhost/maintenance",
          "https://127.0.0.1/maintenance",
          "https://operator:secret@status.example.org/maintenance",
          "https://status.operator.dev/path#secret",
          "https://status.example.org/maintenance"
        }) {
      assertThatThrownBy(() -> service.save("maintenance-1", input(url), "operator-1"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(stored.get()).isNull();
  }

  @Test
  void previewsActualGeneratedSubjectHtmlAndTextInEveryInstalledVariantWithoutDispatch() {
    service.save("maintenance-1", input("https://status.operator.dev/maintenance"), "operator-1");
    for (var tone : OrisoEmailRenderer.Tone.values()) {
      var preview = service.preview("maintenance-1", tone.directory());
      assertThat(preview.variant()).isEqualTo(tone.directory());
      assertThat(preview.subject()).contains("2026-10-02").doesNotContain("{{");
      assertThat(preview.preheader()).isNotBlank().doesNotContain("{{");
      assertThat(preview.html())
          .contains(
              "Independent Platform", "https://status.operator.dev/maintenance", "14:00", "15:00")
          .doesNotContain("{{");
      assertThat(preview.text())
          .contains(
              "Independent Platform", "https://status.operator.dev/maintenance", "14:00", "15:00")
          .doesNotContain("{{");
    }
    verify(branding, org.mockito.Mockito.times(7)).resolve(0L);
  }

  private static DraftInput input(String statusUrl) {
    return new DraftInput(
        LocalDate.of(2026, 10, 2), LocalTime.of(14, 0), LocalTime.of(15, 0), statusUrl);
  }
}
