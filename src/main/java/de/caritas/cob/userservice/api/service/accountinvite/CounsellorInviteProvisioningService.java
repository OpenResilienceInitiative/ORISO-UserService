package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CounsellorInviteProvisioningService {

  private static final String DEFAULT_ROLE_SET = "CONSULTANT_DEFAULT";

  private final @NonNull AccountInviteService accountInviteService;
  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull ConsultantAdminFacade consultantAdminFacade;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull CreateConsultantSaga createConsultantSaga;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull IdentityClientConfig identityClientConfig;

  @Transactional(noRollbackFor = RuntimeException.class)
  public AccountInvite acceptInvite(String rawToken, ProvisionCounsellorCommand command) {
    AccountInvite invite = accountInviteService.requireActiveInvite(rawToken);
    if (invite.getTargetRole() != AccountInviteTargetRole.COUNSELLOR) {
      return accountInviteService.acceptInvite(
          rawToken, command == null ? null : command.acceptedByUserId());
    }
    if (invite.getProvisioningStatus() == AccountInviteProvisioningStatus.IN_PROGRESS) {
      throw new ConflictException("Account invite provisioning is already in progress");
    }
    validate(command, invite);

    invite.setProvisioningStatus(AccountInviteProvisioningStatus.IN_PROGRESS);
    invite.setProvisioningFailureReason(null);
    invite.setUpdateDate(LocalDateTime.now());
    accountInviteRepository.save(invite);

    String consultantId = null;
    var technicalUser = identityClientConfig.getTechnicalUser();
    String technicalAccessToken =
        identityClient
            .loginUser(technicalUser.getUsername(), technicalUser.getPassword())
            .getAccessToken();
    TechnicalAccessTokenContext.set(technicalAccessToken);
    TenantData requestTenant = snapshotTenantContext();
    TenantContext.setCurrentTenant(invite.getTenantId());
    try {
      var consultant = consultantAdminFacade.createNewConsultant(toConsultant(command, invite));
      if (consultant.getEmbedded() == null || consultant.getEmbedded().getId() == null) {
        throw new IllegalStateException("Consultant provisioning returned no user id");
      }
      consultantId = consultant.getEmbedded().getId();
      invite.setProvisionedUserId(consultantId);
      invite.setUpdateDate(LocalDateTime.now());
      accountInviteRepository.save(invite);

      consultantAdminFacade.createNewConsultantAgency(
          consultantId,
          new CreateConsultantAgencyDTO()
              .agencyId(invite.getAgencyId())
              .roleSetKey(DEFAULT_ROLE_SET));

      AccountInvite accepted = accountInviteService.acceptInvite(rawToken, consultantId);
      accepted.setProvisionedUserId(consultantId);
      accepted.setProvisioningStatus(AccountInviteProvisioningStatus.COMPLETED);
      accepted.setProvisioningFailureReason(null);
      accepted.setUpdateDate(LocalDateTime.now());
      return accountInviteRepository.save(accepted);
    } catch (RuntimeException failure) {
      rollbackPartiallyCreatedConsultant(consultantId, failure);
      invite.setProvisionedUserId(null);
      invite.setProvisioningStatus(AccountInviteProvisioningStatus.FAILED);
      invite.setProvisioningFailureReason(truncate(failure.getMessage(), 1024));
      invite.setUpdateDate(LocalDateTime.now());
      accountInviteRepository.save(invite);
      throw failure;
    } finally {
      restoreTenantContext(requestTenant);
      TechnicalAccessTokenContext.clear();
    }
  }

  private static TenantData snapshotTenantContext() {
    TenantData tenantData = TenantContext.getCurrentTenantData();
    return tenantData == null
        ? null
        : new TenantData(tenantData.getTenantId(), tenantData.getSubdomain());
  }

  private static void restoreTenantContext(TenantData tenantData) {
    if (tenantData == null) {
      TenantContext.clear();
    } else {
      TenantContext.setCurrentTenantData(tenantData);
    }
  }

  private void rollbackPartiallyCreatedConsultant(String consultantId, RuntimeException failure) {
    if (consultantId == null) {
      return;
    }
    try {
      consultantRepository
          .findById(consultantId)
          .ifPresent(createConsultantSaga::rollbackCreateNewConsultant);
    } catch (RuntimeException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private static CreateConsultantDTO toConsultant(
      ProvisionCounsellorCommand command, AccountInvite invite) {
    return new CreateConsultantDTO()
        .username(command.username().trim())
        .password(command.password())
        .firstname(invite.getFirstName())
        .lastname(invite.getLastName())
        .email(invite.getRecipientEmail().trim().toLowerCase())
        .formalLanguage(command.formalLanguage())
        .absent(false)
        .tenantId(invite.getTenantId())
        .isGroupchatConsultant(false)
        .topicIds(List.of(invite.getDepartmentId()));
  }

  private static void validate(ProvisionCounsellorCommand command, AccountInvite invite) {
    if (command == null) {
      throw new BadRequestException("Request body is required");
    }
    if (isBlank(command.username())) {
      throw new BadRequestException("username is required");
    }
    if (isBlank(command.password())) {
      throw new BadRequestException("password is required");
    }
    if (command.formalLanguage() == null) {
      throw new BadRequestException("formalLanguage is required");
    }
    if (invite.getTenantId() == null
        || invite.getAgencyId() == null
        || invite.getDepartmentId() == null) {
      throw new BadRequestException("Counsellor invite requires tenant, agency and department");
    }
    if (isBlank(invite.getFirstName()) || isBlank(invite.getLastName())) {
      throw new BadRequestException("Counsellor invite requires first and last name");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  public record ProvisionCounsellorCommand(
      String username, String password, Boolean formalLanguage, String acceptedByUserId) {}
}
