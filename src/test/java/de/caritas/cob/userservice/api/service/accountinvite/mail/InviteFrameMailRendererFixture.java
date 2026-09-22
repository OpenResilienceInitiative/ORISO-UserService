package de.caritas.cob.userservice.api.service.accountinvite.mail;

import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.layout.EmailContentSanitizer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builds a real {@link InviteFrameMailRenderer} around a mocked branding resolver.
 *
 * <p>Everything except the tenant lookup is the production object: the real catalogue templates,
 * the real sanitiser, the real brand values. A test that stubs the renderer instead would prove
 * only that the stub was called — the whole point of these tests is what the recipient sees.
 */
public final class InviteFrameMailRendererFixture {

  public static final String APP_BASE_URL = "https://app.oriso.org";
  public static final String PLATFORM_NAME = "Online-Beratung";
  public static final String ORG_NAME = "ORISO";

  private InviteFrameMailRendererFixture() {}

  public static InviteFrameMailRenderer inviteFrameMailRenderer(EmailBrandingResolver resolver) {
    return new InviteFrameMailRenderer(
        resolver,
        new EmailContentSanitizer(),
        platformBrand(),
        new OrisoEmailRenderer(),
        APP_BASE_URL);
  }

  /**
   * {@link OrisoEmailBrand} takes its platform values from properties Spring is not filling here.
   */
  public static OrisoEmailBrand platformBrand() {
    OrisoEmailBrand brand = new OrisoEmailBrand();
    ReflectionTestUtils.setField(brand, "platformName", PLATFORM_NAME);
    ReflectionTestUtils.setField(brand, "orgName", ORG_NAME);
    ReflectionTestUtils.setField(brand, "orgAddress", "Musterstraße 1, 12345 Musterstadt");
    ReflectionTestUtils.setField(brand, "contactLine", "kontakt@example.org");
    ReflectionTestUtils.setField(brand, "logoUrl", "");
    return brand;
  }
}
