package de.caritas.cob.userservice.api.admin.service.admin.delete;

import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeleteAdminService {

  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull IdentityAccountRemover identityAccountRemover;

  private de.caritas.cob.userservice.api.port.out.IdentityClientConfig mode;
  private de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance maintenance;
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entities;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setScopedMaintenance(
      de.caritas.cob.userservice.api.port.out.IdentityClientConfig mode,
      de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance maintenance) {
    this.mode = mode;
    this.maintenance = maintenance;
  }

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.jdbc.core.JdbcTemplate legacyIdentityRows;

  @org.springframework.beans.factory.annotation.Autowired
  private de.caritas.cob.userservice.api.helper.AuthenticatedUser legacyRemovalHuman;

  private void requireLegacyPureAdministrator(String account) {
    if (legacyIdentityRows == null || legacyRemovalHuman == null)
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    if (account.equals(legacyRemovalHuman.getUserId()))
      throw new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException(
          "Another authorized Administrator must remove this Administrator identity");
    if (!legacyIdentityRows
        .queryForList("select consultant_id from consultant where consultant_id=?", account)
        .isEmpty())
      throw new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException(
          "Separate identity removal requires scoped account commands");
  }

  private de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
      preDeletion;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setPublicPreDeletion(
      de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance owner) {
    preDeletion = java.util.Objects.requireNonNull(owner);
  }

  private boolean scopedRemovalEnabled() {
    return mode != null && mode.isFiveActorMode();
  }

  public void deleteAgencyAdmin(String adminId) {
    if (scopedRemovalEnabled()) {
      removeScoped(adminId, "AGENCY");
      return;
    }
    requireLegacyPureAdministrator(adminId);
    this.identityAccountRemover.deleteUser(adminId);
    this.adminAgencyRepository.deleteByAdminId(adminId);
    this.adminRepository.deleteById(adminId);
  }

  public void deleteTenantAdmin(String adminId) {
    if (scopedRemovalEnabled()) {
      removeScoped(adminId, "TENANT");
      return;
    }
    requireLegacyPureAdministrator(adminId);
    this.identityAccountRemover.deleteUser(adminId);
    this.adminRepository.deleteById(adminId);
  }

  private void removeScoped(String account, String type) {
    if (maintenance == null
        || entities == null
        || !maintenance.isFiveActorMode()
        || org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive())
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    try (var removal = new ScopedRemoval(account, type)) {
      maintenance.removeAdministrator(removal);
    }
  }

  /** Only this actual delete producer can issue the held Administrator-only SQL callback. */
  public final class ScopedRemoval implements AutoCloseable {
    private final String account, type;
    private final Thread thread = Thread.currentThread();
    private boolean active = true;

    private ScopedRemoval(String account, String type) {
      this.account = account;
      this.type = type;
    }

    public void requireActive() {
      if (!active || thread != Thread.currentThread())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }

    public String accountId() {
      requireActive();
      return account;
    }

    public String adminType() {
      requireActive();
      return type;
    }

    public void requireJpaOwner(jakarta.persistence.EntityManagerFactory owner) {
      requireActive();
      if (owner == null || entities == null || owner != entities.getEntityManagerFactory())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }

    public String retainedConsultantRemovalBinding() {
      requireActive();
      if (preDeletion == null)
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      return preDeletion.retainedConsultantRemovalBinding(this);
    }

    public void requireCompletedRetainedConsultantPurge() {
      requireActive();
      if (preDeletion == null)
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      preDeletion.requireCompletedRetainedConsultantPurge(this);
    }

    public void applyRetainedConsultantAdministratorDeletion(
        String binding, String nativeBody, String receipt) {
      if (!retainedConsultantRemovalBinding().equals(binding))
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      apply();
      preDeletion.recordSubsequentAdministratorDeletion(this, binding, nativeBody, receipt);
    }

    public void apply() {
      requireActive();
      if (!entities.isJoinedToTransaction()
          || org.springframework.transaction.support.TransactionSynchronizationManager.getResource(
                  this)
              != this)
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      var current = adminRepository.findById(account).orElseThrow();
      entities.refresh(current);
      if (!type.equals(current.getType().name()))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Administrator type changed");
      adminAgencyRepository.deleteByAdminId(account);
      adminRepository.delete(current);
      entities.flush();
      entities.clear();
    }

    @Override
    public void close() {
      active = false;
    }

    @Override
    public String toString() {
      return "ScopedRemoval[source=redacted]";
    }
  }
}
