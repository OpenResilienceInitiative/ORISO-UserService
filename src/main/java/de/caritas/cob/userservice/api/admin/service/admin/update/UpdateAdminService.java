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

  public Admin updateAgencyAdmin(
      final String adminId, final UpdateAgencyAdminDTO updateAgencyAdminDTO) {
    final Admin admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.AGENCY);
    assertAdminHasTenantIdNotNullAndNotZero(admin);
    final UserDTO userDTO = buildValidatedUserDTO(updateAgencyAdminDTO, admin);
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

  private IdentityProfileUpdate profileUpdate(UserDTO userDTO, String firstName, String lastName) {
    return new IdentityProfileUpdate(
        userDTO.getUsername(), userDTO.getEmail(), userDTO.getTenantId(), firstName, lastName);
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
    this.identityProfileUpdater.updateProfile(
        admin.getId(),
        profileUpdate(
            userDTO, updateTenantAdminDTO.getFirstname(), updateTenantAdminDTO.getLastname()));

    return this.adminRepository.save(buildAdmin(updateTenantAdminDTO, admin));
  }

  public Admin patchAgencyAdmin(final String adminId, final PatchAdminDTO patchAdminDTO) {
    final Admin admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.AGENCY);
    assertAdminHasTenantIdNotNullAndNotZero(admin);
    final UserDTO userDTO = buildValidatedUserDTO(patchAdminDTO, admin);
    this.identityProfileUpdater.updateProfile(
        admin.getId(),
        profileUpdate(userDTO, patchAdminDTO.getFirstname(), patchAdminDTO.getLastname()));

    return this.adminRepository.save(patchAdminEntity(patchAdminDTO, admin));
  }

  public Admin patchTenantAdmin(String adminId, PatchAdminDTO patchAdminDTO) {
    final Admin admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.TENANT);
    final UserDTO userDTO = buildValidatedUserDTO(patchAdminDTO, admin);
    this.identityProfileUpdater.updateProfile(
        admin.getId(),
        profileUpdate(userDTO, patchAdminDTO.getFirstname(), patchAdminDTO.getLastname()));

    return this.adminRepository.save(patchAdminEntity(patchAdminDTO, admin));
  }
}
