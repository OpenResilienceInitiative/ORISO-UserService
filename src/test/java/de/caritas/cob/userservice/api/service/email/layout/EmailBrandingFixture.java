package de.caritas.cob.userservice.api.service.email.layout;

import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Stable platform branding for catalogue renderer tests; real tenant resolution has its own tests.
 */
public final class EmailBrandingFixture {

  private EmailBrandingFixture() {}

  public static EmailBrandingResolver platform() {
    return platform("https://app.oriso.org");
  }

  public static EmailBrandingResolver platform(String origin) {
    EmailBrandingResolver resolver = mock(EmailBrandingResolver.class);
    lenient().when(resolver.resolve(nullable(Long.class))).thenReturn(resolvedPlatform(origin));
    return resolver;
  }

  public static EmailBranding resolvedPlatform(String origin) {
    return new EmailBranding(
        "Online-Beratung",
        null,
        EmailColors.PLATFORM_ACCENT_DARK,
        origin + "/impressum",
        origin + "/datenschutz");
  }

  public static EmailBranding neutralWithLinks(String origin) {
    return new EmailBranding(
        "ORISO",
        null,
        EmailColors.PLATFORM_ACCENT_DARK,
        origin + "/impressum",
        origin + "/datenschutz");
  }
}
