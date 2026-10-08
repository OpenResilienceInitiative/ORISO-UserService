package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.util.Optional;
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

  private final @NonNull UnitQueue unitQueue;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onUnitCreated(InviteUnitCreatedEvent event) {
    TenantData requestTenant = snapshotTenantContext();
    try {
      if (event.tenantId() != null) {
        TenantContext.setCurrentTenant(event.tenantId());
      }
      Optional<String> token = technicalToken();
      if (token.isPresent()) {
        TechnicalAccessTokenContext.offerDuring(
            token.get(),
            () -> unitQueue.release(event.unitType(), event.unitId(), event.tenantId()));
      } else {
        unitQueue.release(event.unitType(), event.unitId(), event.tenantId());
      }
    } catch (RuntimeException exception) {
      log.error(
          "Invites waiting for {} {} could not be released; they stay waiting",
          event.unitType(),
          event.unitId(),
          exception);
    } finally {
      restoreTenantContext(requestTenant);
    }
  }

  /** None when a caller already made the token ambient; then the allocation calls use that one. */
  private Optional<String> technicalToken() {
    if (TechnicalAccessTokenContext.get().isPresent()) {
      return Optional.empty();
    }
    try {
      var technicalUser =
          identityClientConfig.getTaskIdentity(
              de.caritas.cob.userservice.api.config.auth.TaskIdentity.INVITE_RESERVATIONS);
      return Optional.of(identityAuthentication.loginTask(technicalUser).accessToken());
    } catch (RuntimeException exception) {
      log.warn(
          "Technical login for the invite release failed ({}); releasing without it",
          exception.getClass().getSimpleName());
      return Optional.empty();
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
