package de.caritas.cob.userservice.api.admin.service.admin;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.UserServiceMapper;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminBase;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Listing of Global Support Admins (ADR-018): an additive support identity with its OWN table —
 * never mixed into the tenant- or agency-admin listings. Mirrors the tenant-admin search shape so
 * the Admin panel parses all admin tables identically.
 */
@Service
@RequiredArgsConstructor
public class SupportAdminUserService {

  private final @NonNull RetrieveAdminService retrieveAdminService;
  private final @NonNull UserServiceMapper userServiceMapper;
  private final @NonNull ConsultantRepository consultantRepository;

  public Map<String, Object> findSupportAdminsByInfix(String infix, PageRequest pageRequest) {
    Page<AdminBase> adminsPage =
        retrieveAdminService.findAllByInfix(infix, Admin.AdminType.SUPPORT, pageRequest);
    var adminIds = adminsPage.stream().map(AdminBase::getId).collect(Collectors.toSet());
    var fullAdmins = retrieveAdminService.findAllById(adminIds);

    Set<String> idsWithConsultantIdentity =
        adminIds.isEmpty()
            ? Collections.emptySet()
            : consultantRepository.findActiveIdsByIdIn(adminIds);

    return userServiceMapper.mapOfAdmin(
        adminsPage,
        fullAdmins,
        Lists.newArrayList(),
        Lists.newArrayList(),
        Collections.emptyMap(),
        idsWithConsultantIdentity);
  }
}
