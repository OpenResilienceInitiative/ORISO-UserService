package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Gives an existing account one more role (ORISO-Admin#1026): "+ agency admin" for a counsellor.
 * Only adding; removing a role stays in the Users area. Same rule as inviting: the caller may only
 * give roles they could invite, to accounts within their scope.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountRoleAddition {

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull AdminScope adminScope;
  private final @NonNull AccountInviteAccessPolicy accessPolicy;
  private final @NonNull CounsellorAgencyAdminGrantService agencyAdminGrant;
  private final @NonNull AuthenticatedUser authenticatedUser;

  private de.caritas.cob.userservice.api.port.out.IdentityClientConfig identityMode;
  private de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance maintenance;
  private org.springframework.transaction.support.TransactionTemplate localWrites;
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entities;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private AgencyFacts agencyFacts;

  @org.springframework.beans.factory.annotation.Autowired
  public void setScopedMaintenance(
      de.caritas.cob.userservice.api.port.out.IdentityClientConfig mode,
      @org.springframework.lang.Nullable
          de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance owner) {
    identityMode = Objects.requireNonNull(mode);
    maintenance = owner;
  }

  @org.springframework.beans.factory.annotation.Autowired
  public void setLocalTransactionManager(
      org.springframework.transaction.PlatformTransactionManager manager) {
    localWrites = new org.springframework.transaction.support.TransactionTemplate(manager);
  }

  /** {@code agencyId} may be omitted when the counsellor counsels in exactly one agency. */
  public record AddRoleCommand(AccountInviteTargetRole role, Long agencyId) {}

  public record AddedRole(
      String consultantId, AccountInviteTargetRole role, List<Long> agencyIds) {}

  /**
   * @throws CustomValidationHttpStatusException 409 ROLE_ALREADY_GRANTED
   */
  public AddedRole add(String consultantId, AddRoleCommand command) {
    if (identityMode != null && identityMode.isFiveActorMode()) {
      if (maintenance == null
          || !maintenance.isFiveActorMode()
          || entities == null
          || agencyFacts == null)
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      try (var addition = new ScopedAddition(consultantId, command)) {
        return maintenance.addAgencyAdmin(addition);
      }
    }
    return localWrites == null
        ? addLegacy(consultantId, command)
        : localWrites.execute(ignored -> addLegacy(consultantId, command));
  }

  private AddedRole addLegacy(String consultantId, AddRoleCommand command) {
    if (command == null || command.role() == null) {
      throw new BadRequestException("role is required");
    }
    if (command.role() != AccountInviteTargetRole.AGENCY_ADMIN) {
      throw new BadRequestException(
          "Only AGENCY_ADMIN can be added to an account; other role changes are made in the"
              + " Users area");
    }
    accessPolicy.assertMayInvite(command.role());
    Consultant consultant =
        TenantContext.supplyAcrossTenants(() -> consultantRepository.findById(consultantId))
            .filter(found -> found.getDeleteDate() == null)
            .orElseThrow(() -> new NotFoundException("Consultant not found"));
    adminScope.assertMay(Target.counsellor(consultantId));
    Long agencyId = agencyOf(consultant, command.agencyId());
    Optional<Admin> admin =
        TenantContext.supplyAcrossTenants(() -> adminRepository.findById(consultantId));
    if (admin.isPresent()
        && (admin.get().getType() != Admin.AdminType.AGENCY
            || agencyIdsOfAdmin(consultantId).contains(agencyId))) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.ROLE_ALREADY_GRANTED, HttpStatus.CONFLICT);
    }
    agencyAdminGrant.grantAgencyAdmin(consultant, agencyId);
    log.info(
        "Admin {} made counsellor {} agency admin of agency {}",
        authenticatedUser.getUserId(),
        consultantId,
        agencyId);
    return new AddedRole(
        consultantId, command.role(), agencyIdsOfAdmin(consultantId).stream().sorted().toList());
  }

  /** Only this actual public producer issues the immutable command and its local callback. */
  public final class ScopedAddition implements AutoCloseable {
    private final String account;
    private final AddRoleCommand command;
    private final Long agency;
    private final Long agencyTenant;
    private final String intent;
    private final Thread thread = Thread.currentThread();
    private boolean active = true;

    private ScopedAddition(String account, AddRoleCommand command) {
      this.account = account;
      this.command = command;
      requireInput();
      agency = agencyOf(currentConsultant(), command.agencyId());
      agencyTenant = currentConsultant().getTenantId();
      var existing = TenantContext.supplyAcrossTenants(() -> adminRepository.findById(account));
      if (existing.isPresent() && existing.get().getType() != Admin.AdminType.AGENCY)
        throw new CustomValidationHttpStatusException(
            HttpStatusExceptionReason.ROLE_ALREADY_GRANTED, HttpStatus.CONFLICT);
      var fields = new java.util.TreeMap<String, Object>();
      fields.put("role", command.role().name());
      fields.put("requestedAgencyId", command.agencyId());
      fields.put("resolvedAgencyId", agency);
      fields.put("resolvedAgencyTenant", agencyTenant);
      try {
        intent = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(fields);
      } catch (java.io.IOException invalid) {
        throw new BadRequestException("Role addition is invalid");
      }
    }

    public String accountId() {
      return account;
    }

    public String intentJson() {
      return intent;
    }

    public Long agencyId() {
      return agency;
    }

    public void requireJpaOwner(jakarta.persistence.EntityManagerFactory factory) {
      if (entities == null || factory == null || entities.getEntityManagerFactory() != factory)
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }

    private void requireInput() {
      if (!active || thread != Thread.currentThread())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      if (command == null || command.role() != AccountInviteTargetRole.AGENCY_ADMIN)
        throw new BadRequestException("Only AGENCY_ADMIN can be added to an account");
      accessPolicy.assertMayInvite(command.role());
      adminScope.assertMay(Target.counsellor(account));
    }

    public Consultant currentConsultant() {
      requireInput();
      var row =
          TenantContext.supplyAcrossTenants(() -> consultantRepository.findById(account))
              .filter(found -> found.getDeleteDate() == null)
              .orElseThrow(() -> new NotFoundException("Consultant not found"));
      if (org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()
          && entities.contains(row)) entities.refresh(row);
      return row;
    }

    public void validate() {
      var row = currentConsultant();
      if (!Objects.equals(agency, agencyOf(row, command.agencyId())))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Role source changed");
      var admin = TenantContext.supplyAcrossTenants(() -> adminRepository.findById(account));
      if (admin.isPresent()
          && (admin.get().getType() != Admin.AdminType.AGENCY
              || !Objects.equals(admin.get().getTenantId(), row.getTenantId())
              || agencyIdsOfAdmin(account).contains(agency)))
        throw new CustomValidationHttpStatusException(
            HttpStatusExceptionReason.ROLE_ALREADY_GRANTED, HttpStatus.CONFLICT);
    }

    private AgencyFacts.Agency checkedAgency() {
      if (org.springframework.transaction.support.TransactionSynchronizationManager
          .isActualTransactionActive())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      var found =
          agencyFacts
              .find(agency)
              .filter(
                  value ->
                      !value.deleted()
                          && Objects.equals(value.id(), agency)
                          && Objects.equals(value.tenantId(), currentConsultant().getTenantId()));
      return found.orElseThrow(
          () ->
              new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException(
                  "Role agency is outside the current account tenant"));
    }

    public void validateOutsideSql() {
      requireInput();
      if (!Objects.equals(agencyTenant, checkedAgency().tenantId()))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Role agency changed");
    }

    public void requireLocalCurrent() {
      requireInput();
      if (!org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()
          || org.springframework.transaction.support.TransactionSynchronizationManager.getResource(
                  this)
              != this
          || !entities.isJoinedToTransaction())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }

    public void apply() {
      requireLocalCurrent();
      validate();
      agencyAdminGrant.bindCheckedAgencyAdmin(this);
    }

    public void flush() {
      requireLocalCurrent();
      entities.flush();
    }

    public AddedRole currentResult() {
      requireInput();
      return new AddedRole(
          account, command.role(), agencyIdsOfAdmin(account).stream().sorted().toList());
    }

    @Override
    public void close() {
      active = false;
    }

    @Override
    public String toString() {
      return "ScopedAddition[source=redacted]";
    }
  }

  private Long agencyOf(Consultant consultant, Long requested) {
    List<Long> counselsIn =
        TenantContext.supplyAcrossTenants(
                () ->
                    consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(
                        consultant.getId()))
            .stream()
            .map(ConsultantAgency::getAgencyId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (requested == null) {
      if (counselsIn.size() != 1) {
        throw new BadRequestException("agencyId is required");
      }
      return counselsIn.get(0);
    }
    if (!counselsIn.contains(requested)) {
      throw new BadRequestException(
          "agencyId " + requested + " is not an agency the counsellor counsels in");
    }
    return requested;
  }

  private List<Long> agencyIdsOfAdmin(String adminId) {
    return TenantContext.supplyAcrossTenants(() -> adminAgencyRepository.findByAdminId(adminId))
        .stream()
        .map(AdminAgency::getAgencyId)
        .filter(Objects::nonNull)
        .toList();
  }
}
