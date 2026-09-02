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
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
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
    assertCallerMayAccessTenantAdmin(admin);
    var responseDTO = AdminResponseDTOBuilder.getInstance(admin).buildAgencyAdminResponseDTO();
    responseDTO
        .getEmbedded()
        .setHasOtherIdentity(!consultantRepository.findActiveIdsByIdIn(Set.of(adminId)).isEmpty());
    return responseDTO;
  }

  public AdminResponseDTO updateTenantAdmin(
      final String adminId, final UpdateTenantAdminDTO updateTenantAdminDTO) {
    validateUpdateAdmin(updateTenantAdminDTO);
    assertCallerMayAccessTenantAdmin(adminId);
    final Admin updatedAdmin = updateAdminService.updateTenantAdmin(adminId, updateTenantAdminDTO);
    var responseDTO =
        AdminResponseDTOBuilder.getInstance(updatedAdmin).buildAgencyAdminResponseDTO();
    enrichResponseWithSubdomain(updatedAdmin, responseDTO);
    return responseDTO;
  }

  private void enrichResponseWithSubdomain(Admin updatedAdmin, AdminResponseDTO responseDTO) {
    if (isConcreteTenantId(updatedAdmin.getTenantId())) {
      var tenantData = tenantService.getRestrictedTenantData(updatedAdmin.getTenantId());
      responseDTO.getEmbedded().setTenantSubdomain(tenantData.getSubdomain());
    }
  }

  public void deleteTenantAdmin(final String adminId) {
    assertCallerMayAccessTenantAdmin(adminId);
    this.deleteAdminService.deleteTenantAdmin(adminId);
  }

  /**
   * Enforces that the caller may act on a tenant admin identified by id. A platform admin keeps the
   * full view; every other caller must belong to the target's tenant (#968). Loads the target admin
   * to read its tenant so a caller cannot bypass the search-side scoping by guessing an admin id.
   */
  private void assertCallerMayAccessTenantAdmin(String targetAdminId) {
    if (authenticatedUser.isPlatformAdmin()) {
      return;
    }
    Admin target = retrieveAdminService.findAdmin(targetAdminId, Admin.AdminType.TENANT);
    assertCallerMayAccessTenantAdmin(target);
  }

  private void assertCallerMayAccessTenantAdmin(Admin target) {
    if (authenticatedUser.isPlatformAdmin()) {
      return;
    }
    Long callerTenantId = authenticatedUser.getTenantId();
    if (callerTenantId == null || !callerTenantId.equals(target.getTenantId())) {
      log.warn(
          "Tenant admin {} (tenant {}) attempted to access tenant admin {} in tenant {}",
          authenticatedUser.getUserId(),
          callerTenantId,
          target.getId(),
          target.getTenantId());
      throw new ForbiddenException(
          "Tenant admin is not allowed to access an admin outside their own tenant");
    }
  }

  /**
   * Enforces the caller may list tenant admins for the supplied tenant id. Platform admins may
   * cross tenants; every other caller may only list their own tenant. Prevents the sibling leak of
   * {@link #findTenantAdminsByInfix} on GET /useradmin/tenantadmins?tenantId=X (#968).
   */
  private void assertCallerMayListTenantAdminsOf(Long tenantId) {
    if (authenticatedUser.isPlatformAdmin()) {
      return;
    }
    Long callerTenantId = authenticatedUser.getTenantId();
    if (callerTenantId == null || !callerTenantId.equals(tenantId)) {
      log.warn(
          "Tenant admin {} (tenant {}) attempted to list tenant admins of tenant {}",
          authenticatedUser.getUserId(),
          callerTenantId,
          tenantId);
      throw new ForbiddenException(
          "Tenant admin is not allowed to list admins of a foreign tenant");
    }
  }

  public Map<String, Object> findTenantAdminsByInfix(String infix, PageRequest pageRequest) {
    Page<AdminBase> adminsPage = findScopedTenantAdminsByInfix(infix, pageRequest);
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

  /**
   * Returns the infix-matched tenant admins visible to the current caller. A platform admin keeps
   * the full list; every other caller — including a single-tenant admin and a tenant super admin
   * bound to their own tenant — is scoped to their own tenant. Closes the cross-tenant leak in
   * /useradmin/tenantadmins/search (#968) where any holder of the tenant-admin authority could
   * enumerate admins of every other tenant.
   */
  private Page<AdminBase> findScopedTenantAdminsByInfix(String infix, PageRequest pageRequest) {
    if (authenticatedUser.isPlatformAdmin()) {
      return retrieveAdminService.findAllByInfix(infix, Admin.AdminType.TENANT, pageRequest);
    }
    return retrieveAdminService.findAllByInfixScopedToTenant(
        infix, Admin.AdminType.TENANT, authenticatedUser.getTenantId(), pageRequest);
  }

  private Map<Long, String> tenantIdsToNameMap(List<Admin> fullAdmins) {
    Set<Long> tenantIds =
        fullAdmins.stream()
            .map(Admin::getTenantId)
            .filter(this::isConcreteTenantId)
            .collect(Collectors.toSet());
    if (tenantIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return tenantService.getRestrictedTenantData(tenantIds).stream()
        .filter(tenant -> tenant.getId() != null && tenant.getName() != null)
        .collect(
            Collectors.toMap(
                tenant -> tenant.getId(),
                tenant -> tenant.getName(),
                (existing, replacement) -> existing));
  }

  private boolean isConcreteTenantId(Long tenantId) {
    return tenantId != null && !TenantContext.TECHNICAL_TENANT_ID.equals(tenantId);
  }

  public List<AdminResponseDTO> findTenantAdmins(Long tenantId) {
    assertCallerMayListTenantAdminsOf(tenantId);
    var admins = retrieveAdminService.findTenantAdminsByTenantId(tenantId);
    return admins.stream()
        .map(admin -> AdminResponseDTOBuilder.getInstance(admin).buildAgencyAdminResponseDTO())
        .collect(Collectors.toList());
  }

  public AdminResponseDTO patchTenantAdmin(String adminId, PatchAdminDTO patchAdminDTO) {
    assertCallerMayAccessTenantAdmin(adminId);
    final Admin updatedAdmin = updateAdminService.patchTenantAdmin(adminId, patchAdminDTO);
    var responseDTO =
        AdminResponseDTOBuilder.getInstance(updatedAdmin).buildAgencyAdminResponseDTO();
    enrichResponseWithSubdomain(updatedAdmin, responseDTO);
    return responseDTO;
  }
}
