package de.caritas.cob.userservice.api.admin.service.admin;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSupportAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSupportAdminDTO.SecondFactorStatusEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSupportAdminSearchResultDTO;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class GlobalSupportAdminUserService {

  private final @NonNull RetrieveAdminService retrieveAdminService;
  private final @NonNull CreateAdminService createAdminService;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull AuthenticatedUser authenticatedUser;

  public GlobalSupportAdminDTO create(CreateAdminDTO request) {
    requirePlatformAdmin();
    return map(
        createAdminService.createNewGlobalSupportAdmin(request),
        SecondFactorStatusEnum.PENDING_2_FA);
  }

  public GlobalSupportAdminSearchResultDTO search(String infix, PageRequest pageRequest) {
    requirePlatformAdmin();
    var page = retrieveAdminService.findAllByInfix(infix, Admin.AdminType.SUPPORT, pageRequest);
    var ids = page.stream().map(Admin.AdminBase::getId).collect(Collectors.toSet());
    Map<String, Admin> adminsById =
        retrieveAdminService.findAllById(ids).stream()
            .collect(Collectors.toMap(Admin::getId, Function.identity()));

    var items =
        page.stream()
            .map(
                adminBase -> {
                  var admin = adminsById.get(adminBase.getId());
                  if (admin == null) {
                    throw new IllegalStateException(
                        "Global Support Admin projection has no matching entity");
                  }
                  return map(admin, secondFactorStatus(admin));
                })
            .toList();

    return new GlobalSupportAdminSearchResultDTO(items, Math.toIntExact(page.getTotalElements()));
  }

  public void requireActiveSecondFactor() {
    if (!authenticatedUser.isGlobalSupportAdmin()) {
      throw new AccessDeniedException("Global Support Admin role is required");
    }
    var admin =
        retrieveAdminService.findAdmin(authenticatedUser.getUserId(), Admin.AdminType.SUPPORT);
    if (secondFactorStatus(admin) != SecondFactorStatusEnum.ACTIVE) {
      throw new AccessDeniedException(
          "An active second factor is required for Global Support Admin operations");
    }
  }

  private void requirePlatformAdmin() {
    if (!authenticatedUser.isPlatformAdmin()) {
      throw new AccessDeniedException(
          "Only Platform Admins may manage Global Support Admin accounts");
    }
  }

  private SecondFactorStatusEnum secondFactorStatus(Admin admin) {
    try {
      var otpInfo = identityClient.getOtpCredential(admin.getUsername());
      return otpInfo != null && Boolean.TRUE.equals(otpInfo.getOtpSetup())
          ? SecondFactorStatusEnum.ACTIVE
          : SecondFactorStatusEnum.PENDING_2_FA;
    } catch (RuntimeException exception) {
      log.warn(
          "Could not resolve second-factor status for Global Support Admin {}",
          admin.getId(),
          exception);
      return SecondFactorStatusEnum.UNAVAILABLE;
    }
  }

  private GlobalSupportAdminDTO map(Admin admin, SecondFactorStatusEnum secondFactorStatus) {
    return new GlobalSupportAdminDTO(
            admin.getId(),
            admin.getUsername(),
            admin.getFirstName(),
            admin.getLastName(),
            admin.getEmail(),
            secondFactorStatus)
        .createDate(admin.getCreateDate() == null ? null : admin.getCreateDate().toString())
        .updateDate(admin.getUpdateDate() == null ? null : admin.getUpdateDate().toString());
  }
}
