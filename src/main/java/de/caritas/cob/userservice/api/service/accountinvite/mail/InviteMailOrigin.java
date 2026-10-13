package de.caritas.cob.userservice.api.service.accountinvite.mail;

import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailDelivery.Purpose;
import java.util.Objects;

/**
 * On whose behalf a mail leaves, which decides the transport (#1251): a Träger with its own mail
 * server sends through it, everyone else through the platform server.
 *
 * @param tenantId the Träger whose mail settings apply, or {@code null} for platform mail
 * @param purpose the occasion TenantService's relay must allow for this mail
 */
public record InviteMailOrigin(Long tenantId, Purpose purpose) {

  public InviteMailOrigin {
    Objects.requireNonNull(purpose, "purpose");
  }

  public static InviteMailOrigin of(Long tenantId, Purpose purpose) {
    return new InviteMailOrigin(tenantId, purpose);
  }

  public static InviteMailOrigin platform(Purpose purpose) {
    return new InviteMailOrigin(null, purpose);
  }
}
