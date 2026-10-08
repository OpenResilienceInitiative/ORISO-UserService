package de.caritas.cob.userservice.api.admin.service.admin.create;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.ROLE_NOT_FOUND;
import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.apache.commons.lang3.Validate.notNull;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.*;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.ExistingAccountSetupIssuer;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.ws.rs.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@org.springframework.transaction.annotation.Transactional
@RequiredArgsConstructor
public class CreateAdminService {

  @Value("${multitenancy.enabled}")
  private boolean multiTenancyEnabled;

  @Value("${feature.multitenancy.with.single.domain.enabled}")
  private boolean multitenancyWithSingleDomain;

  private final @NonNull de.caritas.cob.userservice.api.adapters.keycloak.commands
          .IdentityCreationLocalCompletion
      localCompletion;
  private final @NonNull IdentityAccountProvisioning identityProvisioning;
  private final @NonNull UserAccountInputValidator userAccountInputValidator;
  private final @NonNull UserHelper userHelper;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService
      inactivityEnrollment;
  private final @NonNull AdminScope adminScope;
  private final @NonNull ExistingAccountSetupIssuer accountSetupIssuer;

  public Admin createNewAgencyAdmin(CreateAdminDTO createAdminDTO) {
    setTenantId(createAdminDTO);
    return createNewAdmin(
        createAdminDTO,
        Admin.AdminType.AGENCY,
        true,
        humanOrigin(createAdminDTO, Admin.AdminType.AGENCY));
  }

  /**
   * Server-side flows only (public invite onboarding): there is no caller, so the tenant comes from
   * the invite, never from the request.
   */
  @org.springframework.transaction.annotation.Transactional(noRollbackFor = RuntimeException.class)
  public Admin createNewAgencyAdminInTenant(
      CreateAdminDTO createAdminDTO, AccountInvite heldInvite) {
    var origin =
        IdentityCreationOrigin.heldInvitation(
            heldInvite,
            IdentityCreationOrigin.Kind.AGENCY_ADMIN,
            getDefaultRoles(Admin.AdminType.AGENCY).stream().map(UserRole::getValue).toList());
    return createNewAdmin(createAdminDTO, Admin.AdminType.AGENCY, false, origin);
  }

  public Admin createNewTenantAdmin(CreateAdminDTO createAdminDTO) {
    return createNewAdmin(
        createAdminDTO,
        Admin.AdminType.TENANT,
        true,
        humanOrigin(createAdminDTO, Admin.AdminType.TENANT));
  }

  /** The invited person chose this password themselves during redemption. */
  public Admin createNewTenantAdminFromInvite(
      CreateAdminDTO createAdminDTO, AccountInvite heldInvite) {
    var origin =
        IdentityCreationOrigin.heldInvitation(
            heldInvite,
            IdentityCreationOrigin.Kind.TENANT_ADMIN,
            getDefaultRoles(Admin.AdminType.TENANT).stream().map(UserRole::getValue).toList());
    return createNewAdmin(createAdminDTO, Admin.AdminType.TENANT, false, origin);
  }

  private IdentityCreationOrigin humanOrigin(CreateAdminDTO request, Admin.AdminType type) {
    Long tenant = request.getTenantId() == null ? null : request.getTenantId().longValue();
    return IdentityCreationOrigin.checkedHuman(
        adminScope,
        IdentityCreationOrigin.verifiedCaller(
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication()),
        tenant,
        type == Admin.AdminType.TENANT
            ? IdentityCreationOrigin.Kind.TENANT_ADMIN
            : IdentityCreationOrigin.Kind.AGENCY_ADMIN,
        getDefaultRoles(type).stream().map(UserRole::getValue).toList());
  }

  List<UserRole> getDefaultRoles(Admin.AdminType adminType) {
    if (Admin.AdminType.AGENCY.equals(adminType)) {
      return Lists.newArrayList(UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
    }
    if (Admin.AdminType.TENANT.equals(adminType)) {
      return getUserRolesForTenantAdmin();
    }
    return Lists.newArrayList();
  }

  private void setTenantId(CreateAdminDTO createAdminDTO) {
    if (multiTenancyEnabled) {
      setTenantIdForMultiTenancy(createAdminDTO);
    } else {
      createAdminDTO.setTenantId(null);
    }
  }

  private void setTenantIdForMultiTenancy(CreateAdminDTO createAdminDTO) {
    if (authenticatedUser.isTenantSuperAdmin()) {
      notNull(createAdminDTO.getTenantId());
      adminScope.assertMay(Target.tenant(createAdminDTO.getTenantId().longValue()));
    } else {
      Long ownTenant = adminScope.current().tenantId();
      createAdminDTO.setTenantId(
          (ownTenant == null ? TenantContext.TECHNICAL_TENANT_ID : ownTenant).intValue());
    }
  }

  private ArrayList<UserRole> getUserRolesForTenantAdmin() {
    if (multitenancyWithSingleDomain) {
      return Lists.newArrayList(UserRole.USER_ADMIN, UserRole.AGENCY_ADMIN, UserRole.TENANT_ADMIN);
    } else {
      return Lists.newArrayList(
          UserRole.USER_ADMIN, UserRole.AGENCY_ADMIN, UserRole.TENANT_ADMIN, UserRole.TOPIC_ADMIN);
    }
  }

  private Admin createNewAdmin(
      final CreateAdminDTO createAdminDTO,
      Admin.AdminType adminType,
      boolean temporaryPassword,
      IdentityCreationOrigin origin) {
    var inactivityPolicy =
        inactivityEnrollment.capture(
            createAdminDTO.getTenantId() == null ? null : createAdminDTO.getTenantId().longValue(),
            de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Group.OTHER);
    final UserDTO validated = buildValidatedUserDTO(createAdminDTO);
    final String password =
        StringUtils.isNotBlank(createAdminDTO.getPassword())
            ? createAdminDTO.getPassword()
            : userHelper.getRandomPassword();
    var receipt =
        identityProvisioning.create(
            java.util.UUID.randomUUID(),
            new KeycloakTaskCommands.AccountCreation(
                new UsernameTranscoder().decodeUsername(validated.getUsername()),
                validated.getEmail(),
                createAdminDTO.getFirstname(),
                createAdminDTO.getLastname(),
                null,
                validated.getTenantId(),
                password,
                temporaryPassword,
                origin.roles(),
                origin.registrationKind()),
            origin);
    identityProvisioning.acquireLocalSaga(receipt);
    final String keycloakUserId = receipt.accountId();
    Admin saved = null;
    try {
      var admin = buildAdmin(createAdminDTO, adminType, keycloakUserId);
      saved = adminRepository.saveAndFlush(admin);
      inactivityEnrollment.enroll(keycloakUserId, admin.getTenantId(), inactivityPolicy);
      if (temporaryPassword) localCompletion.admin(saved);
    } catch (CustomValidationHttpStatusException e) {
      rollbackProvisioning(saved, keycloakUserId, inactivityPolicy);
      throw e;
    } catch (NotFoundException e) {
      // A required Keycloak realm role (e.g. restricted-agency-admin or user-admin) is missing.
      // Surface a specific, machine-readable reason so the admin panel can show a clear message
      // instead of a generic 500, while still rolling back the partially created user.
      rollbackProvisioning(saved, keycloakUserId, inactivityPolicy);
      throw new CustomValidationHttpStatusException(ROLE_NOT_FOUND, HttpStatus.NOT_FOUND);
    } catch (RuntimeException e) {
      rollbackProvisioning(saved, keycloakUserId, inactivityPolicy);
      throw new InternalServerErrorException(
          String.format("Could not complete admin provisioning for type %s", adminType), e);
    }
    // The identity and admin row exist before the setup link is issued. A mail failure is reported
    // to the caller and operator; it must never roll back only the Keycloak half of that account.
    if (temporaryPassword) {
      accountSetupIssuer.issueAfterCreation(
          adminType == Admin.AdminType.TENANT
              ? AccountInviteTargetRole.TENANT_ADMIN
              : AccountInviteTargetRole.AGENCY_ADMIN,
          keycloakUserId,
          password);
    }
    return saved;
  }

  private void rollbackProvisioning(
      Admin saved,
      String keycloakUserId,
      de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy policy) {
    // The owned failed outcome is durable before independently committed local deletion.
    identityProvisioning.compensateForLocalRollback(keycloakUserId);
    compensate(
        "admin row",
        () -> {
          if (saved != null) adminRepository.deleteById(saved.getId());
        });
    compensate(
        "inactivity lifecycle",
        () -> inactivityEnrollment.discardUncompletedCreation(keycloakUserId, policy));
  }

  private void compensate(String resource, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException failure) {
      org.apache.commons.logging.LogFactory.getLog(getClass())
          .warn(
              "Admin provisioning compensation failed for "
                  + resource
                  + ": "
                  + failure.getClass().getSimpleName());
    }
  }

  private UserDTO buildValidatedUserDTO(final CreateAdminDTO createAdminDTO) {
    UserDTO userDto = new UserDTO();
    userDto.setUsername(new UsernameTranscoder().encodeUsername(createAdminDTO.getUsername()));
    userDto.setEmail(createAdminDTO.getEmail());

    Integer tenantId = createAdminDTO.getTenantId();
    userDto.setTenantId(tenantId == null ? null : Long.valueOf(tenantId));

    this.userAccountInputValidator.validateUserDTO(userDto);
    return userDto;
  }

  private Admin buildAdmin(
      final CreateAdminDTO createAgencyAdminDTO,
      Admin.AdminType adminType,
      final String keycloakUserId) {
    final Integer tenantId = createAgencyAdminDTO.getTenantId();
    return Admin.builder()
        .id(keycloakUserId)
        .type(adminType)
        .tenantId(tenantId == null ? null : Long.valueOf(tenantId))
        .username(createAgencyAdminDTO.getUsername())
        .firstName(createAgencyAdminDTO.getFirstname())
        .lastName(createAgencyAdminDTO.getLastname())
        .email(createAgencyAdminDTO.getEmail())
        .createDate(nowInUtc())
        .updateDate(nowInUtc())
        .build();
  }
}
