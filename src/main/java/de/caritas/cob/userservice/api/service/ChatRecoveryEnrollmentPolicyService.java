package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Resolves creation defaults without caching; existing identities never re-enroll. */
@Service
@RequiredArgsConstructor
public class ChatRecoveryEnrollmentPolicyService {
  private final TenantService tenantService;
  private final UserRepository userRepository;
  private final ConsultantRepository consultantRepository;

  @Value("${multitenancy.enabled}")
  private boolean multitenancyEnabled;

  public record RecoveryPolicySnapshot(String mode, long revision) {}

  public RecoveryPolicySnapshot forNewAsker(Long tenantId) {
    return current(tenantId, false);
  }

  public RecoveryPolicySnapshot forNewConsultant(Long tenantId) {
    return current(tenantId, true);
  }

  public RecoveryPolicySnapshot forExistingIdentity(String id) {
    var user = userRepository.findById(id);
    if (user.isPresent()) {
      return new RecoveryPolicySnapshot(
          user.get().getEffectiveChatRecoveryMode(),
          user.get().getEffectiveChatRecoveryPolicyRevision());
    }
    return consultantRepository
        .findById(id)
        .map(
            c ->
                new RecoveryPolicySnapshot(
                    c.getEffectiveChatRecoveryMode(), c.getEffectiveChatRecoveryPolicyRevision()))
        .orElse(new RecoveryPolicySnapshot("RECOVERY_KEY", 0));
  }

  private RecoveryPolicySnapshot current(Long tenantId, boolean consultant) {
    try {
      if ((tenantId == null && multitenancyEnabled) || (tenantId != null && tenantId <= 0))
        throw new IllegalStateException("Concrete tenant required");
      var tenant =
          tenantId == null
              ? tenantService.getSingleTenancyTenantDataFresh()
              : tenantService.getRestrictedTenantDataFresh(tenantId);
      var policy = tenant.getSettings().getTenantAdminControls().getChatRecoverySettings();
      var mode = consultant ? policy.getConsultant() : policy.getAsker();
      if (mode == null || policy.getRevision() == null || policy.getRevision() < 0)
        throw new IllegalStateException("Invalid recovery policy");
      return new RecoveryPolicySnapshot(mode.toString(), policy.getRevision());
    } catch (RuntimeException exception) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.CHAT_RECOVERY_POLICY_UNAVAILABLE, HttpStatus.BAD_GATEWAY);
    }
  }
}
