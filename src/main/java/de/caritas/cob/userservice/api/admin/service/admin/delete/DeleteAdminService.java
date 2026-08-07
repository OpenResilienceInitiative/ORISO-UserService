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

  public void deleteAgencyAdmin(String adminId) {
    this.adminAgencyRepository.deleteByAdminId(adminId);
    this.identityAccountRemover.deleteUser(adminId);
    this.adminRepository.deleteById(adminId);
  }

  public void deleteTenantAdmin(String adminId) {
    this.identityAccountRemover.deleteUser(adminId);
    this.adminRepository.deleteById(adminId);
  }
}
