package de.caritas.cob.userservice.api.admin.service.admin;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.UserServiceMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetApplication;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminBase;
import de.caritas.cob.userservice.api.service.auth.PasswordResetService;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Read-only platform statistics accounts (ORISO-UserService#1666). Their own admin type, never
 * mixed into tenant- or agency-admin listings; every operation is confined to that type. Who may
 * call this is decided in SecurityConfig (platform admins only).
 */
@Service
@RequiredArgsConstructor
public class StatisticsReaderUserService {

  private final @NonNull CreateAdminService createAdminService;
  private final @NonNull UpdateAdminService updateAdminService;
  private final @NonNull DeleteAdminService deleteAdminService;
  private final @NonNull RetrieveAdminService retrieveAdminService;
  private final @NonNull UserServiceMapper userServiceMapper;
  private final @NonNull PasswordResetService passwordResetService;

  public AdminResponseDTO create(CreateAdminDTO createAdminDTO) {
    createAdminDTO.setEmail(lowerCase(createAdminDTO.getEmail()));
    // A statistics reader is never anyone's contact person.
    createAdminDTO.setContactEmail(null);
    Admin admin = createAdminService.createNewStatisticsReader(createAdminDTO);
    // The person chooses their own password through the existing admin "set password" mail.
    passwordResetService.requestPasswordReset(
        admin.getUsername(),
        LocaleContextHolder.getLocale().getLanguage(),
        PasswordResetApplication.ADMIN);
    return AdminResponseDTOBuilder.getInstance(admin).buildAgencyAdminResponseDTO();
  }

  public AdminResponseDTO find(String adminId) {
    Admin admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.STATISTICS);
    return AdminResponseDTOBuilder.getInstance(admin).buildAgencyAdminResponseDTO();
  }

  public AdminResponseDTO update(String adminId, PatchAdminDTO patchAdminDTO) {
    patchAdminDTO.setEmail(lowerCase(patchAdminDTO.getEmail()));
    patchAdminDTO.setContactEmail(null);
    Admin admin = updateAdminService.patchStatisticsReader(adminId, patchAdminDTO);
    return AdminResponseDTOBuilder.getInstance(admin).buildAgencyAdminResponseDTO();
  }

  public void delete(String adminId) {
    deleteAdminService.deleteStatisticsReader(adminId);
  }

  public Map<String, Object> search(String infix, PageRequest pageRequest) {
    Page<AdminBase> adminsPage =
        retrieveAdminService.findAllByInfix(infix, Admin.AdminType.STATISTICS, pageRequest);
    var adminIds = adminsPage.stream().map(AdminBase::getId).collect(Collectors.toSet());
    var fullAdmins = retrieveAdminService.findAllById(adminIds);
    return userServiceMapper.mapOfAdmin(
        adminsPage,
        fullAdmins,
        Lists.newArrayList(),
        Lists.newArrayList(),
        Collections.emptyMap(),
        Collections.emptySet());
  }

  private static String lowerCase(String email) {
    return email == null ? null : email.toLowerCase(Locale.ROOT);
  }
}
