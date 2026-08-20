package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.tenantservice.generated.web.model.DpaSignInviteDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.PublicDpaForwardRequestDTO;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates a DPA sign link through the TenantService's PUBLIC forward endpoint
 * (ORISO-TenantService#179). No authentication is involved on purpose: the invite's tenant-ID
 * reservation pair IS the credential, validated fail-closed against the authoritative reservation
 * ledger on the TenantService side. The raw sign token inside the response is returned to the
 * caller and never logged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicDpaForwardClient {

  private final @NonNull TenantServiceApiControllerFactory tenantServiceApiControllerFactory;

  /**
   * @throws AccountInviteLinkException CONSUMED — the TenantService no longer accepts the
   *     reservation pair (410)
   * @throws ResponseStatusException 409 — no governing DPA is published to forward yet
   */
  public DpaSignInviteDTO createForwardSignLink(Long reservedTenantId, String reservationToken) {
    try {
      return tenantServiceApiControllerFactory
          .createControllerApi()
          .createPublicDpaForwardInvite(
              new PublicDpaForwardRequestDTO()
                  .reservedTenantId(reservedTenantId)
                  .tenantIdReservationToken(reservationToken));
    } catch (HttpClientErrorException.Gone exception) {
      log.info(
          "TenantService rejected the reservation pair for tenant {} as no longer usable",
          reservedTenantId,
          exception);
      throw new AccountInviteLinkException(AccountInviteLinkException.Reason.CONSUMED);
    } catch (HttpClientErrorException.Conflict exception) {
      // keep the upstream cause: without it the reason the operator DPA lookup failed is lost
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "No data processing agreement is published by the platform operator yet",
          exception);
    }
  }
}
