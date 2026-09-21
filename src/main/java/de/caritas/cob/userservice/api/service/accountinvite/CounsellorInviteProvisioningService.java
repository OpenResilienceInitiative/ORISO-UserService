package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.ConsultantAvatarKind;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
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
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull CounsellorAgencyAdminGrantService counsellorAgencyAdminGrantService;

  @Transactional(noRollbackFor = RuntimeException.class)
  public AccountInvite acceptInvite(String rawToken, ProvisionCounsellorCommand command) {
    AccountInvite invite = accountInviteService.findInviteByToken(rawToken);
    // An AGENCY_ADMIN invite whose invitee also counsels takes this consultant path, but only from
    // the onboarding wizard, which asks for the agency-admin grant (ORISO-Admin#1026, slice 3).
    boolean agencyAdminAlsoCounselling =
        invite.getTargetRole() == AccountInviteTargetRole.AGENCY_ADMIN
            && command != null
            && Boolean.TRUE.equals(command.grantAgencyAdmin());
    if (invite.getTargetRole() != AccountInviteTargetRole.COUNSELLOR
        && !agencyAdminAlsoCounselling) {
      return accountInviteService.acceptInvite(
          rawToken, command == null ? null : command.acceptedByUserId());
    }
    if (invite.getStatus() != AccountInviteStatus.EMAIL_SENT
        || (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(LocalDateTime.now()))) {
      // Already-processed or expired links keep the resume/consumed contract
      // (idempotent 200 while the 2FA gate is pending, 410 with a reason code otherwise). No
      // caller-supplied user id is recorded on this path — provisioning identity is always the
      // server-created consultant id.
      return accountInviteService.acceptInvite(rawToken, null);
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
        identityAuthentication
            .login(technicalUser.getUsername(), technicalUser.getPassword())
            .accessToken();
    TechnicalAccessTokenContext.set(technicalAccessToken);
    TenantData requestTenant = snapshotTenantContext();
    TenantContext.setCurrentTenant(invite.getTenantId());
    try {
      var consultant = consultantAdminFacade.createNewConsultant(toConsultant(command, invite));
      if (consultant.getEmbedded() == null || consultant.getEmbedded().getId() == null) {
        throw new IllegalStateException("Consultant provisioning returned no user id");
      }
      consultantId = consultant.getEmbedded().getId();
      alignRequirementsWithInvite(consultantId, invite);
      invite.setProvisionedUserId(consultantId);
      invite.setUpdateDate(LocalDateTime.now());
      accountInviteRepository.save(invite);

      consultantAdminFacade.createNewConsultantAgency(
          consultantId,
          new CreateConsultantAgencyDTO()
              .agencyId(invite.getAgencyId())
              .roleSetKey(DEFAULT_ROLE_SET));

      if (Boolean.TRUE.equals(command.grantAgencyAdmin())) {
        // The invitee brought this Beratungsstelle into existence, so they administrate it —
        // a brand new agency has no other admin who could.
        counsellorAgencyAdminGrantService.grantAgencyAdmin(
            consultantId, invite.getAgencyId(), invite);
      }

      AccountInvite accepted = accountInviteService.acceptInvite(rawToken, consultantId);
      if (agencyAdminAlsoCounselling) {
        accepted.setAlsoCounsellor(true);
      }
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

  /**
   * Undoes the two create-path defaults that only hold when an administrator chose the credentials.
   * The invite already tracks the second-factor requirement, including {@code WAIVED}, and the
   * counsellor typed their own password seconds ago.
   */
  private void alignRequirementsWithInvite(String consultantId, AccountInvite invite) {
    var stillOwed = !AccountInviteService.isTwoFactorGateSatisfied(invite.getTwoFactorStatus());
    consultantRepository
        .findByIdAndDeleteDateIsNull(consultantId)
        .filter(
            consultant ->
                !Boolean.valueOf(stillOwed).equals(consultant.getTwoFactorRequired())
                    || Boolean.TRUE.equals(consultant.getPasswordChangeRequired()))
        .ifPresent(
            consultant -> {
              consultant.setTwoFactorRequired(stillOwed);
              consultant.setPasswordChangeRequired(false);
              consultantRepository.save(consultant);
            });
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
        .email(invite.getRecipientEmail().trim().toLowerCase(java.util.Locale.ROOT))
        .formalLanguage(command.formalLanguage())
        .absent(false)
        .tenantId(invite.getTenantId())
        .isGroupchatConsultant(false)
        // Wizard registrations may choose topics within the invite's coverage (validated
        // by CounsellorOnboardingService); the plain accept flow keeps the routed department.
        .topicIds(
            command.topicIds() == null || command.topicIds().isEmpty()
                ? List.of(invite.getDepartmentId())
                : List.copyOf(command.topicIds()))
        // Optional profile fields collected by the onboarding wizard; null on the
        // plain accept flow and simply left unset on the created consultant.
        .salutation(command.salutation())
        .position(command.position())
        .title(command.title())
        .displayName(command.displayName())
        .internalDisplayName(command.internalDisplayName())
        // Avatar choice. Parsed through the one shared null-safe helper: an unknown wire
        // value from the public wizard is simply "no choice", never a 500.
        .avatarKind(toWireAvatarKind(command.avatarKind()))
        .avatarId(command.avatarId());
  }

  private static CreateConsultantDTO.AvatarKindEnum toWireAvatarKind(String avatarKind) {
    ConsultantAvatarKind kind = ConsultantAvatarKind.fromNameOrNull(avatarKind);
    return kind == null ? null : CreateConsultantDTO.AvatarKindEnum.fromValue(kind.name());
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
    if (invite.getTenantId() == null || invite.getAgencyId() == null) {
      throw new BadRequestException("Counsellor invite requires tenant and agency");
    }
    // A new-Beratungsstelle invite routes to a reserved agency ID that carries no
    // department yet — the invitee picks the topics in the wizard and the first one becomes the
    // agency's department. Only when NO topics were chosen does the routed department have to
    // exist, because it is then the sole source of the consultant's topic assignment.
    if (invite.getDepartmentId() == null
        && (command.topicIds() == null || command.topicIds().isEmpty())) {
      throw new BadRequestException("Counsellor invite requires tenant, agency and department");
    }
    if (isBlank(invite.getFirstName()) || isBlank(invite.getLastName())) {
      throw new BadRequestException("Counsellor invite requires first and last name");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * Input of a counsellor provisioning. The first four fields carry the public accept flow
   * (ORISO-Frontend PR #956); the optional trailing fields are only filled by the onboarding wizard
   * (#997) and default to {@code null} on the plain accept path.
   */
  public record ProvisionCounsellorCommand(
      String username,
      String password,
      Boolean formalLanguage,
      String acceptedByUserId,
      String salutation,
      String position,
      String title,
      String displayName,
      String internalDisplayName,
      List<Long> topicIds,
      String avatarKind,
      String avatarId,
      /**
       * True when the wizard just created this invite's Beratungsstelle (#998): the provisioned
       * consultant then also becomes its Beratungsstellen-Admin. Never set on the plain accept
       * flow, where the agency already exists and has its own admins.
       */
      Boolean grantAgencyAdmin) {

    /** Plain accept-flow shape (no wizard profile fields). */
    public ProvisionCounsellorCommand(
        String username, String password, Boolean formalLanguage, String acceptedByUserId) {
      this(
          username,
          password,
          formalLanguage,
          acceptedByUserId,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false);
    }

    /** Wizard shape before #998 (existing agency, no admin grant). */
    public ProvisionCounsellorCommand(
        String username,
        String password,
        Boolean formalLanguage,
        String acceptedByUserId,
        String salutation,
        String position,
        String title,
        String displayName,
        String internalDisplayName,
        List<Long> topicIds) {
      this(
          username,
          password,
          formalLanguage,
          acceptedByUserId,
          salutation,
          position,
          title,
          displayName,
          internalDisplayName,
          topicIds,
          null,
          null,
          false);
    }

    /** Wizard shape with avatar, no admin grant (#1046). */
    public ProvisionCounsellorCommand(
        String username,
        String password,
        Boolean formalLanguage,
        String acceptedByUserId,
        String salutation,
        String position,
        String title,
        String displayName,
        String internalDisplayName,
        List<Long> topicIds,
        String avatarKind,
        String avatarId) {
      this(
          username,
          password,
          formalLanguage,
          acceptedByUserId,
          salutation,
          position,
          title,
          displayName,
          internalDisplayName,
          topicIds,
          avatarKind,
          avatarId,
          false);
    }

    /** New-Beratungsstelle grant without an avatar choice. */
    public ProvisionCounsellorCommand(
        String username,
        String password,
        Boolean formalLanguage,
        String acceptedByUserId,
        String salutation,
        String position,
        String title,
        String displayName,
        String internalDisplayName,
        List<Long> topicIds,
        Boolean grantAgencyAdmin) {
      this(
          username,
          password,
          formalLanguage,
          acceptedByUserId,
          salutation,
          position,
          title,
          displayName,
          internalDisplayName,
          topicIds,
          null,
          null,
          grantAgencyAdmin);
    }
  }
}
