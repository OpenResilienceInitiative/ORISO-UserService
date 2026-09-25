package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * {@code fallbackExecution}: the agency-admin/counsellor wizard has no outer transaction.
 * Onboarding is anonymous, so this runs as the technical user; a failure never fails it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueuedInviteReleaseListener {

  private final @NonNull AccountInviteService accountInviteService;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onUnitCreated(InviteUnitCreatedEvent event) {
    TenantData requestTenant = snapshotTenantContext();
    boolean technicalTokenSet = false;
    try {
      technicalTokenSet = useTechnicalToken();
      if (event.tenantId() != null) {
        TenantContext.setCurrentTenant(event.tenantId());
      }
      accountInviteService.releaseWaitingInvites(
          event.unitType(), event.unitId(), event.tenantId());
    } catch (RuntimeException exception) {
      log.error(
          "Invites waiting for {} {} could not be released; they stay waiting",
          event.unitType(),
          event.unitId(),
          exception);
    } finally {
      if (technicalTokenSet) {
        TechnicalAccessTokenContext.clear();
      }
      restoreTenantContext(requestTenant);
    }
  }

  private boolean useTechnicalToken() {
    if (TechnicalAccessTokenContext.get().isPresent()) {
      return false;
    }
    try {
      var technicalUser = identityClientConfig.getTechnicalUser();
      TechnicalAccessTokenContext.set(
          identityAuthentication
              .login(technicalUser.getUsername(), technicalUser.getPassword())
              .accessToken());
      return true;
    } catch (RuntimeException exception) {
      log.warn(
          "Technical login for the invite release failed ({}); releasing without it",
          exception.getClass().getSimpleName());
      return false;
    }
  }

  private static TenantData snapshotTenantContext() {
    TenantData tenantData = TenantContext.getCurrentTenantData();
    return tenantData == null
        ? null
        : new TenantData(tenantData.getTenantId(), tenantData.getSubdomain());
  }

  private static void restoreTenantContext(TenantData tenantData) {
    if (tenantData == null) {
      TenantContext.clear();
    } else {
      TenantContext.setCurrentTenantData(tenantData);
    }
  }
}
