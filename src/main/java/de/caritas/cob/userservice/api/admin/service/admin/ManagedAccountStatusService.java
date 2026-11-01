package de.caritas.cob.userservice.api.admin.service.admin;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The selected identity's independent management pause and authoritative display metadata. */
@Service
@RequiredArgsConstructor
public class ManagedAccountStatusService {
  private final AuthenticatedUser actor;
  private final AdminScope scope;
  private final AdminRepository admins;
  private final ConsultantRepository consultants;
  private final IdentityAccountStatusLookup identities;
  private final IdentityProfileLookup profiles;
  private final IdentitySecondFactor factors;
  private final AccountInactivityService lifecycle;
  private final TenantService tenants;

  public record TwoFactor(Boolean required, Boolean configured, Boolean active) {}

  public record AccountStatus(
      String accountId,
      @tools.jackson.databind.annotation.JsonSerialize(
              using =
                  de.caritas.cob.userservice.api.adapters.web.dto.serialization
                      .DecodeUsernameJsonSerializer.class)
          String username,
      String createDate,
      String updateDate,
      Boolean active,
      boolean paused,
      Boolean loginEnabled,
      boolean absent,
      Boolean lifecycleRestricted,
      Boolean tenantActive,
      List<String> restrictions,
      TwoFactor twoFactor) {}

  private record Target(Admin admin, Consultant consultant) {
    String username() {
      return admin != null ? admin.getUsername() : consultant.getUsername();
    }

    Long tenantId() {
      return admin != null ? admin.getTenantId() : consultant.getTenantId();
    }

    boolean paused() {
      return admin != null && admin.isManagedPaused()
          || consultant != null && consultant.isManagedPaused();
    }

    LocalDateTime created() {
      return admin != null ? admin.getCreateDate() : consultant.getCreateDate();
    }

    LocalDateTime updated() {
      return admin != null ? admin.getUpdateDate() : consultant.getUpdateDate();
    }
  }

  public AccountStatus read(String id) {
    Target target = authorizedTarget(id, false);
    return status(id, target);
  }

  @Transactional
  public AccountStatus update(String id, boolean active) {
    Target target = authorizedTarget(id, true);
    AccountStatus before = status(id, target);
    if (active) {
      List<String> independent =
          before.restrictions().stream().filter(r -> !r.equals("MANAGED_PAUSE")).toList();
      if (!independent.isEmpty()) throw new ActivationRefused(before);
      if (before.loginEnabled() == null
          || before.lifecycleRestricted() == null
          || before.tenantActive() == null)
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "Account activation prerequisites unavailable");
    }
    // Both projections describe one identity. No membership, absence or provider-enabled write.
    if (target.admin() != null) {
      target.admin().setManagedPaused(!active);
      admins.save(target.admin());
    }
    if (target.consultant() != null) {
      target.consultant().setManagedPaused(!active);
      consultants.save(target.consultant());
    }
    return status(id, target);
  }

  public static class ActivationRefused extends RuntimeException {
    private final AccountStatus status;

    ActivationRefused(AccountStatus status) {
      super("Independent account restrictions remain");
      this.status = status;
    }

    public AccountStatus status() {
      return status;
    }
  }

  private Target authorizedTarget(String id, boolean write) {
    var admin = TenantContext.supplyAcrossTenants(() -> admins.findById(id)).orElse(null);
    var consultant = TenantContext.supplyAcrossTenants(() -> consultants.findById(id)).orElse(null);
    if (admin == null && consultant == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    Set<String> authorities =
        actor.getGrantedAuthorities() == null ? Set.of() : actor.getGrantedAuthorities();
    boolean permitted;
    if (admin != null) {
      permitted =
          switch (admin.getType()) {
            case TENANT -> authorities.contains(TENANT_ADMIN);
            case AGENCY -> authorities.contains(USER_ADMIN);
            default -> false;
          };
      scope.assertMay(write ? AdminScope.Target.managedAdmin(id) : AdminScope.Target.admin(id));
    } else {
      permitted =
          write ? authorities.contains(CONSULTANT_UPDATE) : authorities.contains(USER_ADMIN);
      scope.assertMay(AdminScope.Target.counsellor(id));
    }
    if (admin != null && consultant != null) {
      if (!Objects.equals(admin.getTenantId(), consultant.getTenantId()))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
      scope.assertMay(AdminScope.Target.counsellor(id));
    }
    if (!permitted) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return new Target(admin, consultant);
  }

  private AccountStatus status(String id, Target target) {
    Boolean enabled;
    try {
      enabled = identities.findEnabledById(id).orElse(null);
    } catch (RuntimeException unavailable) {
      enabled = null;
    }
    return status(id, target, enabled, true, new HashMap<>());
  }

  /**
   * Input IDs already passed the existing list scope. Factor enrollment is never read for lists.
   */
  public Boolean activeForList(String id, Boolean loginEnabled, Map<Long, Boolean> tenantStates) {
    var admin = TenantContext.supplyAcrossTenants(() -> admins.findById(id)).orElse(null);
    var consultant = TenantContext.supplyAcrossTenants(() -> consultants.findById(id)).orElse(null);
    if (admin == null && consultant == null) return null;
    return status(id, new Target(admin, consultant), loginEnabled, false, tenantStates).active();
  }

  private AccountStatus status(
      String id,
      Target target,
      Boolean enabled,
      boolean includeFactor,
      Map<Long, Boolean> tenantStates) {
    var snapshot = lifecycle.snapshot(id);
    Boolean lifecycleRestricted =
        snapshot.map(s -> s.status() != AccountInactivityService.Status.ACTIVE).orElse(null);
    var consultant = target.consultant();
    boolean absent = consultant != null && consultant.isAbsent();
    boolean consultantRestricted =
        consultant != null
            && (consultant.getDeleteDate() != null
                || (consultant.getStatus() != null
                    && consultant.getStatus() != ConsultantStatus.CREATED)
                || (consultant.getDeletionLifecycleState() != null
                    && consultant.getDeletionLifecycleState()
                        != de.caritas.cob.userservice.api.workflow.delete.model
                            .DeletionLifecycleState.ACTIVE));
    if (consultantRestricted) lifecycleRestricted = true;
    Long tenantId = target.tenantId();
    Boolean tenantActive;
    if (tenantStates.containsKey(tenantId)) tenantActive = tenantStates.get(tenantId);
    else {
      tenantActive = tenantActive(tenantId);
      tenantStates.put(tenantId, tenantActive);
    }
    var reasons = new ArrayList<String>();
    if (target.paused()) reasons.add("MANAGED_PAUSE");
    if (Boolean.FALSE.equals(enabled)) reasons.add("LOGIN_DISABLED");
    if (absent) reasons.add("ABSENT");
    if (Boolean.TRUE.equals(lifecycleRestricted)) reasons.add("LIFECYCLE_RESTRICTED");
    if (Boolean.FALSE.equals(tenantActive)) reasons.add("TENANT_INACTIVE");
    Boolean effective =
        !reasons.isEmpty()
            ? Boolean.FALSE
            : enabled == null || lifecycleRestricted == null || tenantActive == null
                ? null
                : Boolean.TRUE;
    Boolean required =
        target.admin() != null
                && (target.admin().getType() == Admin.AdminType.TENANT
                    || target.admin().getType() == Admin.AdminType.AGENCY)
            ? Boolean.TRUE
            : consultant != null ? consultant.getTwoFactorRequired() : null;
    Boolean configured = null, factorActive = null;
    try {
      var profile = includeFactor ? profiles.findById(id).orElse(null) : null;
      // Never resolve a target's second factor through an unverified or observer username.
      var otp =
          profile != null && id.equals(profile.id()) && profile.username() != null
              ? factors.getOtpCredential(profile.username())
              : null;
      if (otp != null && otp.setup() != null) {
        factorActive = otp.setup();
        configured = Boolean.TRUE.equals(otp.setup()) || otp.type() != null;
      }
    } catch (RuntimeException unavailable) {
      /* Unknown metadata; never expose provider exception or enrollment secrets. */
    }
    return new AccountStatus(
        id,
        target.username(),
        string(target.created()),
        string(target.updated()),
        effective,
        target.paused(),
        enabled,
        absent,
        lifecycleRestricted,
        tenantActive,
        List.copyOf(reasons),
        new TwoFactor(required, configured, factorActive));
  }

  public boolean isManuallyPaused(String id) {
    return TenantContext.supplyAcrossTenants(() -> admins.existsByIdAndManagedPausedTrue(id))
        || TenantContext.supplyAcrossTenants(() -> consultants.existsByIdAndManagedPausedTrue(id));
  }

  private Boolean tenantActive(Long tenantId) {
    if (tenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) return true;
    try {
      var tenant = tenants.getRestrictedTenantDataFresh(tenantId);
      return tenant == null ? null : !Boolean.FALSE.equals(tenant.getActive());
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  private static String string(LocalDateTime value) {
    return value == null ? null : value.toString();
  }
}
