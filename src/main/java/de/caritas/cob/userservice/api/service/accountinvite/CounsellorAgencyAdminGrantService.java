package de.caritas.cob.userservice.api.service.accountinvite;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import java.util.List;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Makes the invitee the Beratungsstellen-Admin of the agency their invite just created
 * (ORISO-Admin#998). Somebody has to be able to administrate a brand new Beratungsstelle, and the
 * person who created it is the only candidate the flow knows.
 *
 * <p>Deliberately NOT {@code CreateAdminService.createNewAgencyAdmin}: that path creates a NEW
 * Keycloak user from a {@code CreateAdminDTO}. Here the identity already exists — it is the
 * consultant the same registration just provisioned — so this grants the same two realm roles that
 * path grants ({@code restricted-agency-admin}, {@code user-admin}) and writes the same two rows
 * ({@code admin} of type AGENCY plus its {@code admin_agency} relation). The result is
 * indistinguishable from an agency admin created through the admin panel, which is what lets the
 * invitee simply log into the Admin panel for their agency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CounsellorAgencyAdminGrantService {

  private static final List<UserRole> AGENCY_ADMIN_ROLES =
      Lists.newArrayList(UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);

  private final @NonNull IdentityClient identityClient;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;

  /**
   * Grants the agency-admin role set to an existing identity and binds it to the agency.
   *
   * @param userId the identity of the provisioned consultant
   * @param agencyId the agency they administrate
   * @param invite the invite carrying the person's name, email and tenant
   */
  public void grantAgencyAdmin(String userId, Long agencyId, AccountInvite invite) {
    AGENCY_ADMIN_ROLES.forEach(role -> identityClient.updateRole(userId, role));
    bindAdmin(
        userId,
        agencyId,
        () ->
            Admin.builder()
                .id(userId)
                .type(Admin.AdminType.AGENCY)
                .tenantId(invite.getTenantId())
                .username(invite.getRecipientEmail())
                .firstName(invite.getFirstName())
                .lastName(invite.getLastName())
                .email(invite.getRecipientEmail())
                .createDate(nowInUtc())
                .updateDate(nowInUtc())
                .build());
    log.info(
        "Granted agency admin rights for agency {} to the invitee of invite {}",
        agencyId,
        invite.getId());
  }

  /**
   * The same grant for a counsellor whose account exists. Rows first, realm roles last: inside the
   * caller's transaction a refused role rolls the rows back.
   */
  public void grantAgencyAdmin(Consultant consultant, Long agencyId) {
    bindAdmin(
        consultant.getId(),
        agencyId,
        () ->
            Admin.builder()
                .id(consultant.getId())
                .type(Admin.AdminType.AGENCY)
                .tenantId(consultant.getTenantId())
                .username(consultant.getUsername())
                .firstName(consultant.getFirstName())
                .lastName(consultant.getLastName())
                .email(consultant.getEmail())
                .createDate(nowInUtc())
                .updateDate(nowInUtc())
                .build());
    // Flushes both rows, so a constraint failure stops us before Keycloak changes.
    adminRepository.flush();
    AGENCY_ADMIN_ROLES.forEach(role -> identityClient.updateRole(consultant.getId(), role));
  }

  private void bindAdmin(String userId, Long agencyId, Supplier<Admin> newAdmin) {
    Admin savedAdmin = adminRepository.save(adminRepository.findById(userId).orElseGet(newAdmin));
    adminAgencyRepository.save(
        AdminAgency.builder()
            .admin(savedAdmin)
            .agencyId(agencyId)
            .createDate(nowInUtc())
            .updateDate(nowInUtc())
            .build());
  }
}
