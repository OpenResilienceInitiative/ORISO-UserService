package de.caritas.cob.userservice.api.admin.service.admin;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.UserServiceMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminBase;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantAdminUserService {

  private final @NonNull RetrieveAdminService retrieveAdminService;
  private final @NonNull CreateAdminService createAdminService;
  private final @NonNull UpdateAdminService updateAdminService;
  private final @NonNull DeleteAdminService deleteAdminService;
  private final @NonNull UserServiceMapper userServiceMapper;
  private final @NonNull TenantService tenantService;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull ConsultantRepository consultantRepository;

  @Value("${multitenancy.enabled}")
  private boolean multiTenancyEnabled;

  public AdminResponseDTO createNewTenantAdmin(final CreateAdminDTO createTenantAdminDTO) {
    validateCreateAdmin(createTenantAdminDTO);
    final Admin newAdmin = createAdminService.createNewTenantAdmin(createTenantAdminDTO);
    return AdminResponseDTOBuilder.getInstance(newAdmin).buildAgencyAdminResponseDTO();
  }

  private void validateCreateAdmin(CreateAdminDTO createTenantAdminDTO) {
    validateTenantId(createTenantAdminDTO.getTenantId());
  }

  private void validateUpdateAdmin(UpdateTenantAdminDTO updateTenantAdminDTO) {
    validateTenantId(updateTenantAdminDTO.getTenantId());
  }

  private void validateTenantId(Integer inputTenantId) {
    if (inputTenantId == null) {
      throw new BadRequestException("Tenant id must be provided");
    }
    if (inputTenantId.equals(0) && !authenticatedUser.isPlatformAdmin()) {
      throw new ForbiddenException("Only platform admins can create platform admin accounts");
    }
  }

  public AdminResponseDTO findTenantAdmin(final String adminId) {
    final Admin admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.TENANT);
    var responseDTO = AdminResponseDTOBuilder.getInstance(admin).buildAgencyAdminResponseDTO();
    responseDTO
        .getEmbedded()
        .setHasOtherIdentity(!consultantRepository.findActiveIdsByIdIn(Set.of(adminId)).isEmpty());
    return responseDTO;
  }

  public AdminResponseDTO updateTenantAdmin(
      final String adminId, final UpdateTenantAdminDTO updateTenantAdminDTO) {
    validateUpdateAdmin(updateTenantAdminDTO);
    final Admin updatedAdmin = updateAdminService.updateTenantAdmin(adminId, updateTenantAdminDTO);
    var responseDTO =
        AdminResponseDTOBuilder.getInstance(updatedAdmin).buildAgencyAdminResponseDTO();
    enrichResponseWithSubdomain(updatedAdmin, responseDTO);
    return responseDTO;
  }

  private void enrichResponseWithSubdomain(Admin updatedAdmin, AdminResponseDTO responseDTO) {
    if (updatedAdmin.getTenantId() != null) {
      var tenantData = tenantService.getRestrictedTenantData(updatedAdmin.getTenantId());
      responseDTO.getEmbedded().setTenantSubdomain(tenantData.getSubdomain());
    }
  }

  public void deleteTenantAdmin(final String adminId) {
    this.deleteAdminService.deleteTenantAdmin(adminId);
  }

  public Map<String, Object> findTenantAdminsByInfix(String infix, PageRequest pageRequest) {
    Page<AdminBase> adminsPage =
        retrieveAdminService.findAllByInfix(infix, Admin.AdminType.TENANT, pageRequest);
    var adminIds = adminsPage.stream().map(AdminBase::getId).collect(Collectors.toSet());
    var fullAdmins = retrieveAdminService.findAllById(adminIds);

    var tenantIdsToNameMap = tenantIdsToNameMap(fullAdmins);

    Set<String> idsWithConsultantIdentity =
        adminIds.isEmpty()
            ? Collections.emptySet()
            : consultantRepository.findActiveIdsByIdIn(adminIds);

    return userServiceMapper.mapOfAdmin(
        adminsPage,
        fullAdmins,
        Lists.newArrayList(),
        Lists.newArrayList(),
        tenantIdsToNameMap,
        idsWithConsultantIdentity);
  }

  private Map<Long, String> tenantIdsToNameMap(List<Admin> fullAdmins) {
    Set<Long> tenantIds =
        fullAdmins.stream()
            .map(Admin::getTenantId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    return tenantService.getRestrictedTenantData(tenantIds).stream()
        .filter(tenant -> tenant.getId() != null && tenant.getName() != null)
        .collect(
            Collectors.toMap(
                tenant -> tenant.getId(),
                tenant -> tenant.getName(),
                (existing, replacement) -> existing));
  }

  public List<AdminResponseDTO> findTenantAdmins(Long tenantId) {
    var admins = retrieveAdminService.findTenantAdminsByTenantId(tenantId);
    return admins.stream()
        .map(admin -> AdminResponseDTOBuilder.getInstance(admin).buildAgencyAdminResponseDTO())
        .collect(Collectors.toList());
  }

  public AdminResponseDTO patchTenantAdmin(String adminId, PatchAdminDTO patchAdminDTO) {

    final Admin updatedAdmin = updateAdminService.patchTenantAdmin(adminId, patchAdminDTO);
    var responseDTO =
        AdminResponseDTOBuilder.getInstance(updatedAdmin).buildAgencyAdminResponseDTO();
    enrichResponseWithSubdomain(updatedAdmin, responseDTO);
    return responseDTO;
  }
}
