package de.caritas.cob.userservice.api.service.email.layout;

import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Stable platform branding for catalogue renderer tests; real tenant resolution has its own tests.
 */
public final class EmailBrandingFixture {

  /** An arbitrary dark test colour; production code has no built-in brand colour. */
  public static final String TEST_COLOUR = "#a5000a";

  private EmailBrandingFixture() {}

  public static EmailBrandingResolver platform() {
    return platform("https://app.oriso.org");
  }

  public static EmailBrandingResolver platform(String origin) {
    EmailBrandingResolver resolver = mock(EmailBrandingResolver.class);
    lenient().when(resolver.platformName()).thenReturn("Online-Beratung");
    lenient().when(resolver.resolve(nullable(Long.class))).thenReturn(resolvedPlatform(origin));
    return resolver;
  }

  public static EmailBranding resolvedPlatform(String origin) {
    return new EmailBranding(
        "Online-Beratung", null, TEST_COLOUR, origin + "/impressum", origin + "/datenschutz");
  }

  public static EmailBranding neutralWithLinks(String origin) {
    return new EmailBranding(
        "ORISO", null, TEST_COLOUR, origin + "/impressum", origin + "/datenschutz");
  }
}
