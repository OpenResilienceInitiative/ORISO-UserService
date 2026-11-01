package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.config.auth.RoutineTaskAuthentication;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Re-reads an invite's agency when the anonymous invitee accepts: it may have been deleted since
 * the invite was written (ORISO-Admin#1026 P2-3). Only the service token can read the delete date.
 */
@Component
@RequiredArgsConstructor
public class AcceptTimeAgencyCheck {

  private final @NonNull AgencyFacts agencyFacts;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull de.caritas.cob.userservice.api.admin.service.tenant.TenantService
      tenantService;

  @Value("${multitenancy.enabled}")
  private boolean multiTenancyEnabled;

  /**
   * Called before a pending invite claims a row or creates a centre/identity. Resume stays allowed.
   */
  public void requireOperationalTenant(AccountInvite invite) {
    tenantService.requireOperationalTenant(invite.getTenantId());
  }

  /** Logs in the Keycloak technical user and checks with that token. */
  public void requireLiveAgency(AccountInvite invite) {
    requireLiveAgency(invite, serviceToken());
  }

  /**
   * Answers 404 unless the agency exists, is not deleted and belongs to the invite's tenant. The
   * service token bypasses AgencyService's tenant filter, so the tenant is compared here; with
   * multitenancy an agency without a tenant fails closed.
   */
  public void requireLiveAgency(AccountInvite invite, String serviceToken) {
    TechnicalAccessTokenContext.runWith(
        serviceToken,
        () ->
            agencyFacts
                .find(invite.getAgencyId())
                .filter(agency -> !agency.deleted())
                .filter(agency -> belongsToInviteTenant(agency, invite))
                .orElseThrow(
                    () ->
                        new NotFoundException(
                            "agencyId " + invite.getAgencyId() + " does not exist")));
  }

  private boolean belongsToInviteTenant(AgencyFacts.Agency agency, AccountInvite invite) {
    if (agency.tenantId() == null) {
      return !multiTenancyEnabled;
    }
    return agency.tenantId().equals(invite.getTenantId());
  }

  /** The Keycloak technical user's access token; its failure text stays out of the invite. */
  public String serviceToken() {
    try {
      return RoutineTaskAuthentication.accessToken(
          TaskPurpose.INVITE_RESERVATIONS, identityClientConfig, identityAuthentication);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Service authentication unavailable");
    }
  }
}
