package de.caritas.cob.userservice.api.service.email.sender;

import lombok.NonNull;
import org.springframework.stereotype.Component;

/**
 * Who a mail footer names as its sender (Frank, 2026-09-23): the platform owner's organisation as
 * entered in the Admin panel, with a Träger's own values laid over it field by field. Nothing is
 * made up — a value nobody entered stays empty, and the footer then omits its line.
 */
@Component
public class SenderOrganisationResolver {

  private final PlatformOperatorOrganisationClient platformOperator;
  private final TraegerOrganisationClient traeger;

  public SenderOrganisationResolver(
      @NonNull PlatformOperatorOrganisationClient platformOperator,
      @NonNull TraegerOrganisationClient traeger) {
    this.platformOperator = platformOperator;
    this.traeger = traeger;
  }

  /** The platform owner alone — for mails the platform itself sends. */
  public SenderOrganisation platform() {
    return platformOperator.fetch().orElse(SenderOrganisation.NONE);
  }

  /**
   * @param tenantId the Träger a mail is sent for; {@code null} or a tenant that does not exist yet
   *     yields the platform owner alone
   */
  public SenderOrganisation forTenant(Long tenantId) {
    SenderOrganisation platform = platform();
    if (tenantId == null) {
      return platform;
    }
    return traeger.fetch(tenantId).map(platform::overriddenBy).orElse(platform);
  }
}
