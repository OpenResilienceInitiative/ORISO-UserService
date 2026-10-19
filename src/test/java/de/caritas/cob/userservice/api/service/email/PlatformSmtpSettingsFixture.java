package de.caritas.cob.userservice.api.service.email;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import java.util.Optional;

/** Provider fixture that exercises the actual Admin Settings DTO, without a network call. */
public final class PlatformSmtpSettingsFixture {
  private PlatformSmtpSettingsFixture() {}

  public static PlatformSmtpSettingsProvider configured(String username, String password) {
    ApplicationSettingsService service = mock(ApplicationSettingsService.class);
    when(service.getGlobalSmtpSettingsSnapshot())
        .thenReturn(Optional.of(credentials(username, password)));
    return new PlatformSmtpSettingsProvider(service);
  }

  public static ApplicationSettingsSmtpCredentialsDTO credentials(
      String username, String password) {
    return new ApplicationSettingsSmtpCredentialsDTO()
        .globalFeatureSystemNotificationEmailsEnabled(true)
        .globalSmtpEnabled(true)
        .globalSmtpHost("smtp.example.org")
        .globalSmtpPort("587")
        .globalSmtpSecure(false)
        .globalSmtpUsername(username)
        .globalSmtpPassword(password)
        .globalSmtpFrom("noreply@example.org");
  }
}
