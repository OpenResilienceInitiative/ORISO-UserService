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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
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

  private IdentityClientConfig identityMode;
  private JdbcTemplate jdbc;
  private TransactionTemplate localWrites;

  private de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance
      scopedMaintenance;

  @org.springframework.beans.factory.annotation.Autowired
  public void setHumanAdminMaintenance(
      @org.springframework.lang.Nullable
          de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance
              maintenance) {
    scopedMaintenance = maintenance;
  }

  @org.springframework.beans.factory.annotation.Autowired
  public void setScopedLocalWrites(
      IdentityClientConfig mode, JdbcTemplate jdbc, PlatformTransactionManager manager) {
    javax.sql.DataSource owner = null;
    if (manager instanceof JpaTransactionManager jpa) owner = jpa.getDataSource();
    else if (manager instanceof DataSourceTransactionManager sql) owner = sql.getDataSource();
    if (owner == null || owner != jdbc.getDataSource())
      throw new IllegalArgumentException("Account status SQL owner mismatch");
    this.identityMode = Objects.requireNonNull(mode);
    this.jdbc = jdbc;
    this.localWrites = new TransactionTemplate(manager);
  }

  private org.springframework.beans.factory.ObjectProvider<
          de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance>
      removedConsultants;

  @org.springframework.beans.factory.annotation.Autowired
  public void configureRemovedConsultantProofs(
      org.springframework.beans.factory.ObjectProvider<
              de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance>
          owners) {
    removedConsultants = Objects.requireNonNull(owners);
  }

  private Target activeReadTarget(String id, Target target) {
    if (identityMode == null
        || !identityMode.isFiveActorMode()
        || target.admin() == null
        || target.consultant() == null
        || target.consultant().getDeleteDate() == null) return target;
    var proof = removedConsultants == null ? null : removedConsultants.getIfAvailable();
    if (proof == null || localWrites == null)
      throw new de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException(
          "Retained Consultant proof unavailable");
    localWrites.execute(
        ignored -> {
          proof.requireRetainedConsultantRemoved(id, target.admin().getTenantId());
          return true;
        });
    return new Target(target.admin(), null);
  }

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
    return status(id, activeReadTarget(id, target));
  }

  public AccountStatus update(String id, boolean active) {
    if (identityMode != null && identityMode.isFiveActorMode()) return updateScoped(id, active);
    // REQUIRED preserves the original LEGACY transaction, including participation in its caller.
    return localWrites == null
        ? updateLegacy(id, active)
        : localWrites.execute(ignored -> updateLegacy(id, active));
  }

  private AccountStatus updateLegacy(String id, boolean active) {
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

  private record LocalRows(
      List<Map<String, Object>> admin,
      List<Map<String, Object>> consultant,
      List<Map<String, Object>> inactivity) {}

  private record LocalSelection(Target target, LocalRows rows) {}

  private AccountStatus updateScoped(String id, boolean active) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Account status requires no ambient SQL");
    LocalSelection selected = localWrites.execute(ignored -> selectedRows(id));
    AccountStatus before = status(id, selected.target());
    if (active) {
      var independent =
          before.restrictions().stream().filter(r -> !r.equals("MANAGED_PAUSE")).toList();
      if (!independent.isEmpty()) throw new ActivationRefused(before);
      if (before.loginEnabled() == null
          || before.lifecycleRestricted() == null
          || before.tenantActive() == null)
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "Account activation prerequisites unavailable");
    }
    Target committed =
        localWrites.execute(
            ignored -> {
              LocalSelection current = selectedRows(id);
              if (!selected.rows().equals(current.rows()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Account status changed");
              updatePause("admin", "admin_id", id, current.rows().admin(), !active);
              updatePause("consultant", "consultant_id", id, current.rows().consultant(), !active);
              return selectedRows(id).target();
            });
    // Fresh external display reads also happen after the selected local commit.
    return status(id, committed);
  }

  private LocalSelection selectedRows(String id) {
    var adminRows = jdbc.queryForList("select * from admin where admin_id=? for update", id);
    var consultantRows =
        jdbc.queryForList("select * from consultant where consultant_id=? for update", id);
    if (!jdbc.queryForList("select user_id from user where user_id=? for update", id).isEmpty())
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    var inactivity =
        jdbc.queryForList("select * from account_inactivity where identity_id=? for update", id);
    if (adminRows.size() > 1 || consultantRows.size() > 1 || inactivity.size() > 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    var admin = adminRows.isEmpty() ? null : statusAdmin(adminRows.getFirst());
    var consultant = consultantRows.isEmpty() ? null : statusConsultant(consultantRows.getFirst());
    Target target = new Target(admin, consultant);
    assertAuthorizedTarget(id, true, target);
    var reach = scope.current();
    if (reach.tenantId() != null && !Objects.equals(reach.tenantId(), target.tenantId()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (!inactivity.isEmpty()
        && !Objects.equals(sqlTenant(inactivity.getFirst()), target.tenantId()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Account lifecycle owner changed");
    if (consultant != null && consultant.getDeleteDate() != null)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (admin != null
        && consultant != null
        && !Objects.equals(decoded(admin.getUsername()), decoded(consultant.getUsername())))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return new LocalSelection(target, new LocalRows(adminRows, consultantRows, inactivity));
  }

  private void updatePause(
      String table, String column, String id, List<Map<String, Object>> rows, boolean paused) {
    if (rows.isEmpty() || sqlBoolean(rows.getFirst().get("managed_paused")) == paused) return;
    // The names are private literals; both complete rows remain locked and unchanged since
    // selection.
    if (jdbc.update(
            "update "
                + table
                + " set managed_paused=?,update_date=CURRENT_TIMESTAMP where "
                + column
                + "=? and managed_paused=?",
            paused,
            id,
            !paused)
        != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Account status changed");
  }

  private Admin statusAdmin(Map<String, Object> row) {
    return Admin.builder()
        .id((String) row.get("admin_id"))
        .tenantId(sqlTenant(row))
        .username((String) row.get("username"))
        .firstName((String) row.get("first_name"))
        .lastName((String) row.get("last_name"))
        .email((String) row.get("email"))
        .type(Admin.AdminType.valueOf((String) row.get("type")))
        .managedPaused(sqlBoolean(row.get("managed_paused")))
        .createDate(sqlDate(row.get("create_date")))
        .updateDate(sqlDate(row.get("update_date")))
        .build();
  }

  private Consultant statusConsultant(Map<String, Object> row) {
    return Consultant.builder()
        .id((String) row.get("consultant_id"))
        .tenantId(sqlTenant(row))
        .username((String) row.get("username"))
        .firstName((String) row.get("first_name"))
        .lastName((String) row.get("last_name"))
        .email((String) row.get("email"))
        .managedPaused(sqlBoolean(row.get("managed_paused")))
        .absent(sqlBoolean(row.get("is_absent")))
        .twoFactorRequired(
            row.get("two_factor_required") == null
                ? null
                : sqlBoolean(row.get("two_factor_required")))
        .status(
            row.get("status") == null ? null : ConsultantStatus.valueOf((String) row.get("status")))
        .deletionLifecycleState(
            row.get("deletion_lifecycle_state") == null
                ? null
                : de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState
                    .valueOf((String) row.get("deletion_lifecycle_state")))
        .createDate(sqlDate(row.get("create_date")))
        .updateDate(sqlDate(row.get("update_date")))
        .deleteDate(sqlDate(row.get("delete_date")))
        .build();
  }

  private static Long sqlTenant(Map<String, Object> row) {
    return row.get("tenant_id") == null ? null : ((Number) row.get("tenant_id")).longValue();
  }

  private static LocalDateTime sqlDate(Object value) {
    if (value == null) return null;
    return value instanceof java.sql.Timestamp timestamp
        ? timestamp.toLocalDateTime()
        : (LocalDateTime) value;
  }

  private static boolean sqlBoolean(Object value) {
    if (value instanceof Boolean flag) return flag;
    if (value instanceof Number number && (number.intValue() == 0 || number.intValue() == 1))
      return number.intValue() == 1;
    throw new ResponseStatusException(HttpStatus.CONFLICT, "Unknown account status");
  }

  private static String decoded(String username) {
    return new de.caritas.cob.userservice.api.helper.UsernameTranscoder().decodeUsername(username);
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
    var target = new Target(admin, consultant);
    assertAuthorizedTarget(id, write, target);
    return target;
  }

  private void assertAuthorizedTarget(String id, boolean write, Target target) {
    var admin = target.admin();
    var consultant = target.consultant();
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
      scope.assertMay(AdminScope.Target.admin(id));
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
    return status(
            id,
            activeReadTarget(id, new Target(admin, consultant)),
            loginEnabled,
            false,
            tenantStates)
        .active();
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
      if (identityMode != null && identityMode.isFiveActorMode()) {
        if (includeFactor && scopedMaintenance != null && scopedMaintenance.isFiveActorMode()) {
          var metadata = scopedMaintenance.factorStatus(id);
          configured = metadata.configured();
          factorActive = metadata.active();
        }
      } else {
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

  /** Read-only startup check of the actual installed production consumers. */
  public boolean hasScopedAccountBindings(
      de.caritas.cob.userservice.api.port.out.IdentityClientConfig mode,
      de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance owner,
      de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance proof,
      org.springframework.orm.jpa.JpaTransactionManager manager) {
    return identityMode == mode
        && scopedMaintenance == owner
        && removedConsultants != null
        && removedConsultants.getIfAvailable() == proof
        && localWrites != null
        && localWrites.getTransactionManager() == manager
        && jdbc != null
        && jdbc.getDataSource() == manager.getDataSource();
  }
}
