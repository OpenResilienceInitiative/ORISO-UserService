package de.caritas.cob.userservice.api.admin.service.admin.update;

import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAgencyAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UpdateAdminService {

  private final @NonNull IdentityProfileUpdater identityProfileUpdater;
  private final @NonNull UserAccountInputValidator userAccountInputValidator;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull RetrieveAdminService retrieveAdminService;

  private de.caritas.cob.userservice.api.port.out.IdentityClientConfig identityMode;
  private de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance
      scopedMaintenance;

  @org.springframework.beans.factory.annotation.Autowired
  public void setScopedAdminMaintenance(
      de.caritas.cob.userservice.api.port.out.IdentityClientConfig mode,
      @org.springframework.lang.Nullable
          de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance
              maintenance) {
    this.identityMode = java.util.Objects.requireNonNull(mode);
    this.scopedMaintenance = maintenance;
  }

  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager scopedEntities;

  private boolean fiveActors() {
    return identityMode != null && identityMode.isFiveActorMode();
  }

  private Admin updateScoped(Admin admin, IdentityProfileUpdate profile) {
    if (scopedMaintenance == null || !scopedMaintenance.isFiveActorMode())
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    // The private writer returns a fresh detached SQL result after its acknowledged conditional
    // commit.
    // Do not dirty or re-save the earlier JPA entity.
    return scopedMaintenance.updateAdminProfile(admin.getId(), profile);
  }

  public Admin updateAgencyAdmin(
      final String adminId, final UpdateAgencyAdminDTO updateAgencyAdminDTO) {
    final Admin admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.AGENCY);
    assertAdminHasTenantIdNotNullAndNotZero(admin);
    final UserDTO userDTO = buildValidatedUserDTO(updateAgencyAdminDTO, admin);
    if (fiveActors())
      return updateScoped(
          admin,
          profileUpdate(
              userDTO, updateAgencyAdminDTO.getFirstname(), updateAgencyAdminDTO.getLastname()));
    this.identityProfileUpdater.updateProfile(
        admin.getId(),
        profileUpdate(
            userDTO, updateAgencyAdminDTO.getFirstname(), updateAgencyAdminDTO.getLastname()));

    return this.adminRepository.save(buildAdmin(updateAgencyAdminDTO, admin));
  }

  private void assertAdminHasTenantIdNotNullAndNotZero(Admin admin) {
    if (admin.getTenantId() != null && admin.getTenantId() == 0) {
      throw new IllegalArgumentException("Admin has tenant id 0");
    }
  }

  private UserDTO buildValidatedUserDTO(
      final UpdateAgencyAdminDTO updateAgencyAdminDTO, final Admin admin) {
    UserDTO userDTO = new UserDTO();
    userDTO.setEmail(updateAgencyAdminDTO.getEmail());
    userDTO.setUsername(admin.getUsername());
    userDTO.setTenantId(admin.getTenantId());

    this.userAccountInputValidator.validateUserDTO(userDTO);
    return userDTO;
  }

  private IdentityProfileUpdate profileUpdate(UserDTO userDTO, String firstName, String lastName) {
    return new IdentityProfileUpdate(
        userDTO.getUsername(), userDTO.getEmail(), userDTO.getTenantId(), firstName, lastName);
  }

  private UserDTO buildValidatedUserDTO(
      final UpdateTenantAdminDTO updateTenantAdminDTO, final Admin admin) {
    UserDTO userDTO = new UserDTO();
    userDTO.setEmail(updateTenantAdminDTO.getEmail());
    userDTO.setUsername(admin.getUsername());
    userDTO.setTenantId(Long.valueOf(updateTenantAdminDTO.getTenantId()));
    this.userAccountInputValidator.validateUserDTO(userDTO);
    return userDTO;
  }

  private UserDTO buildValidatedUserDTO(final PatchAdminDTO patchAdminDTO, final Admin admin) {
    UserDTO userDTO = new UserDTO();
    userDTO.setEmail(patchAdminDTO.getEmail());
    userDTO.setUsername(admin.getUsername());
    userDTO.setTenantId(Long.valueOf(admin.getTenantId()));
    this.userAccountInputValidator.validateUserDTO(userDTO);
    return userDTO;
  }

  private Admin buildAdmin(final UpdateAgencyAdminDTO updateAgencyAdminDTO, final Admin admin) {
    admin.setLastName(updateAgencyAdminDTO.getLastname());
    admin.setFirstName(updateAgencyAdminDTO.getFirstname());
    admin.setEmail(updateAgencyAdminDTO.getEmail());
    return admin;
  }

  private Admin buildAdmin(final UpdateTenantAdminDTO updateTenantAdminDTO, final Admin admin) {
    admin.setLastName(updateTenantAdminDTO.getLastname());
    admin.setFirstName(updateTenantAdminDTO.getFirstname());
    admin.setEmail(updateTenantAdminDTO.getEmail());
    admin.setTenantId(Long.valueOf(updateTenantAdminDTO.getTenantId()));
    return admin;
  }

  private Admin patchAdminEntity(final PatchAdminDTO patchAdminDTO, final Admin admin) {
    admin.setLastName(patchAdminDTO.getLastname());
    admin.setFirstName(patchAdminDTO.getFirstname());
    admin.setEmail(patchAdminDTO.getEmail());
    return admin;
  }

  public Admin updateTenantAdmin(String adminId, UpdateTenantAdminDTO updateTenantAdminDTO) {
    final Admin admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.TENANT);
    final UserDTO userDTO = buildValidatedUserDTO(updateTenantAdminDTO, admin);
    if (fiveActors()) {
      if (scopedMaintenance == null || !scopedMaintenance.isFiveActorMode())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      try (var source =
          new ScopedTenantEdit(
              adminId,
              updateTenantAdminDTO,
              profileUpdate(
                  userDTO,
                  updateTenantAdminDTO.getFirstname(),
                  updateTenantAdminDTO.getLastname()))) {
        return scopedMaintenance.updateTenantAdmin(source);
      }
    }
    this.identityProfileUpdater.updateProfile(
        admin.getId(),
        profileUpdate(
            userDTO, updateTenantAdminDTO.getFirstname(), updateTenantAdminDTO.getLastname()));

    return this.adminRepository.save(buildAdmin(updateTenantAdminDTO, admin));
  }

  /** Only the actual full TenantAdmin DTO entry can mint this live edit source. */
  public final class ScopedTenantEdit implements AutoCloseable {
    private final String account;
    private final UpdateTenantAdminDTO input;
    private final IdentityProfileUpdate desired;
    private final Thread owner = Thread.currentThread();
    private boolean active = true;

    private ScopedTenantEdit(
        String account, UpdateTenantAdminDTO input, IdentityProfileUpdate desired) {
      this.account = account;
      this.input = input;
      this.desired = desired;
      requireLive();
    }

    public void requireLive() {
      if (!active
          || owner != Thread.currentThread()
          || input == null
          || desired == null
          || input.getTenantId() == null
          || input.getTenantId() < 1
          || !java.util.Objects.equals(desired.tenantId(), input.getTenantId().longValue())
          || !java.util.Objects.equals(desired.firstName(), input.getFirstname())
          || !java.util.Objects.equals(desired.lastName(), input.getLastname())
          || !java.util.Objects.equals(desired.email(), input.getEmail()))
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }

    public String accountId() {
      requireLive();
      return account;
    }

    public IdentityProfileUpdate profile() {
      requireLive();
      return desired;
    }

    public void requireJpaOwner(jakarta.persistence.EntityManagerFactory factory) {
      requireLive();
      if (factory == null
          || scopedEntities == null
          || scopedEntities.getEntityManagerFactory() != factory)
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }

    public void apply(
        de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance.CheckedAccount
            checked) {
      requireLive();
      checked.requireTenantEditCallback(this);
      if (!scopedEntities.isJoinedToTransaction())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      var row =
          de.caritas.cob.userservice.api.tenant.TenantContext.supplyAcrossTenants(
                  () -> adminRepository.findByIdAndType(account, Admin.AdminType.TENANT))
              .orElseThrow();
      scopedEntities.refresh(row);
      row.setTenantId(desired.tenantId());
      row.setFirstName(desired.firstName());
      row.setLastName(desired.lastName());
      row.setEmail(desired.email());
      adminRepository.save(row);
      scopedEntities.flush();
    }

    public Admin result() {
      requireLive();
      return de.caritas.cob.userservice.api.tenant.TenantContext.supplyAcrossTenants(
              () -> adminRepository.findByIdAndType(account, Admin.AdminType.TENANT))
          .orElseThrow();
    }

    @Override
    public void close() {
      active = false;
    }

    @Override
    public String toString() {
      return "ScopedTenantEdit[ownership=redacted]";
    }
  }

  public Admin patchAgencyAdmin(final String adminId, final PatchAdminDTO patchAdminDTO) {
    final Admin admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.AGENCY);
    assertAdminHasTenantIdNotNullAndNotZero(admin);
    final UserDTO userDTO = buildValidatedUserDTO(patchAdminDTO, admin);
    if (fiveActors())
      return updateScoped(
          admin, profileUpdate(userDTO, patchAdminDTO.getFirstname(), patchAdminDTO.getLastname()));
    this.identityProfileUpdater.updateProfile(
        admin.getId(),
        profileUpdate(userDTO, patchAdminDTO.getFirstname(), patchAdminDTO.getLastname()));

    return this.adminRepository.save(patchAdminEntity(patchAdminDTO, admin));
  }

  public Admin patchTenantAdmin(String adminId, PatchAdminDTO patchAdminDTO) {
    final Admin admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.TENANT);
    final UserDTO userDTO = buildValidatedUserDTO(patchAdminDTO, admin);
    if (fiveActors())
      return updateScoped(
          admin, profileUpdate(userDTO, patchAdminDTO.getFirstname(), patchAdminDTO.getLastname()));
    this.identityProfileUpdater.updateProfile(
        admin.getId(),
        profileUpdate(userDTO, patchAdminDTO.getFirstname(), patchAdminDTO.getLastname()));

    return this.adminRepository.save(patchAdminEntity(patchAdminDTO, admin));
  }
}
