package de.caritas.cob.userservice.api.service.accountinvite.mail;

import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingFixture;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.layout.EmailContentSanitizer;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationFixture;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationResolver;

/**
 * Builds a real {@link InviteFrameMailRenderer} around a mocked branding resolver.
 *
 * <p>Everything except the tenant lookups is the production object: the real catalogue templates,
 * the real sanitiser, the real brand values. A test that stubs the renderer instead would prove
 * only that the stub was called — the whole point of these tests is what the recipient sees.
 */
public final class InviteFrameMailRendererFixture {

  public static final String APP_BASE_URL = "https://app.example.org";
  public static final String PLATFORM_NAME = "Online-Beratung";

  private InviteFrameMailRendererFixture() {}

  /** The platform owner of {@link SenderOrganisationFixture#PLATFORM_OWNER}, no Träger data. */
  public static InviteFrameMailRenderer inviteFrameMailRenderer(EmailBrandingResolver resolver) {
    return inviteFrameMailRenderer(resolver, SenderOrganisationFixture.platformOwner());
  }

  public static InviteFrameMailRenderer inviteFrameMailRenderer(
      EmailBrandingResolver resolver, SenderOrganisationResolver senderOrganisations) {
    return new InviteFrameMailRenderer(
        resolver,
        new EmailContentSanitizer(),
        tenantEmailBrandValues(senderOrganisations, APP_BASE_URL),
        new OrisoEmailRenderer());
  }

  public static TenantEmailBrandValues tenantEmailBrandValues(
      SenderOrganisationResolver senderOrganisations, String appBaseUrl) {
    return new TenantEmailBrandValues(
        platformBrand(senderOrganisations), senderOrganisations, appBaseUrl);
  }

  /** A platform brand backed by the same fixture resolver as catalogue mail tests. */
  public static OrisoEmailBrand platformBrand(SenderOrganisationResolver senderOrganisations) {
    return new OrisoEmailBrand(senderOrganisations, EmailBrandingFixture.platform(APP_BASE_URL));
  }
}
