package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static com.google.common.collect.Lists.newArrayList;
import static de.caritas.cob.userservice.api.config.auth.UserRole.CONSULTANT;
import static de.caritas.cob.userservice.api.config.auth.UserRole.GROUP_CHAT_CONSULTANT;
import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.helper.json.JsonSerializationUtils.serializeToJsonString;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NotificationsSettingsDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantResponseDTOBuilder;
import de.caritas.cob.userservice.api.admin.service.consultant.TransactionalStep;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionInfo;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-identity foundation: grants an existing admin user a full functional consultant identity.
 *
 * <p>The admin's existing Keycloak account is reused (no new Keycloak user is created and the
 * password is not changed). The {@code consultant} realm role is added, a Matrix account is created
 * (best effort), a {@link Consultant} row sharing the admin's Keycloak id is persisted and the
 * requested consultant_agency links are created — with keycloak-role rollback should the consultant
 * row fail to persist.
 *
 * <p>The admin and consultant tables are independent (different PKs, no cross-FKs) so the same
 * Keycloak id may legitimately hold both an {@code admin} row and a {@code consultant} row; no
 * schema change is required.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GrantConsultantIdentityService {

  private final ChatRecoveryEnrollmentPolicyService chatRecoveryEnrollmentPolicyService;
  private static final String GRANT_CONSULTANT_IDENTITY = "grantConsultantIdentity";

  private final @NonNull AdminRepository adminRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull IdentityRoleUpdater identityRoleUpdater;
  private final @NonNull MatrixUserClient matrixUserClient;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull ConsultantAgencyRelationCreatorService
      consultantAgencyRelationCreatorService;
  private final @NonNull UserHelper userHelper;
  private final @NonNull ConsultantTopicAgencyCompatibilityValidator
      consultantTopicAgencyCompatibilityValidator;
  private final @NonNull AdminScope adminScope;
  private final @NonNull ConsultantDisplayNameResolver consultantDisplayNameResolver;

  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  /**
   * Grants an existing admin user a full functional consultant identity.
   *
   * @param adminId the Keycloak id of the existing admin user
   * @param dto the consultant-specific attributes for the new identity
   * @return the {@link ConsultantAdminResponseDTO} describing the created consultant identity
   */
  @Transactional
  public ConsultantAdminResponseDTO grantConsultantIdentityToAdmin(
      String adminId, GrantConsultantIdentityDTO dto) {

    // Looked up across tenants on purpose: the route only requires user-admin, so the caller's
    // Träger and agencies are checked right below, and an admin of another Träger must be refused
    // (403) rather than reported as unknown.
    var admin =
        TenantContext.supplyAcrossTenants(() -> adminRepository.findById(adminId))
            .orElseThrow(
                () ->
                    new BadRequestException(String.format("Admin with id %s not found", adminId)));

    adminScope.assertMay(Target.admin(admin.getId()));
    adminScope.assertMay(Target.agencies(dto.getAgencyIds()));

    if (consultantRepository.findByIdAndDeleteDateIsNull(adminId).isPresent()) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.CONSULTANT_IDENTITY_ALREADY_GRANTED, HttpStatus.CONFLICT);
    }

    var encodedUsername = usernameTranscoder.encodeUsername(admin.getUsername());
    if (consultantRepository.findByUsernameAndDeleteDateIsNull(encodedUsername).isPresent()) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.CONSULTANT_IDENTITY_ALREADY_GRANTED, HttpStatus.CONFLICT);
    }

    consultantTopicAgencyCompatibilityValidator.validateGrantTopicsAgainstSelectedAgencies(
        dto.getTopicIds(), dto.getAgencyIds(), admin.getTenantId());

    var snapshot = chatRecoveryEnrollmentPolicyService.forNewConsultant(admin.getTenantId());
    assignKeycloakRoles(adminId, dto);

    String matrixUserId = createMatrixAccount(admin);

    var consultant = buildConsultant(admin, encodedUsername, dto, matrixUserId);
    consultant.setChatRecoveryMode(snapshot.mode());
    consultant.setChatRecoveryPolicyRevision(snapshot.revision());
    saveConsultantOrRollback(adminId, dto, consultant);

    try {
      assignAgencies(adminId, dto);
    } catch (RuntimeException e) {
      removeGrantedRoles(adminId, dto);
      throw e;
    }

    return ConsultantResponseDTOBuilder.getInstance(consultant).buildResponseDTO();
  }

  private void assignKeycloakRoles(String adminId, GrantConsultantIdentityDTO dto) {
    var requestedRoles =
        dto.isGroupchatConsultant()
            ? Set.of(CONSULTANT.getValue(), GROUP_CHAT_CONSULTANT.getValue())
            : Set.of(CONSULTANT.getValue());
    identityRoleUpdater.ensureRoles(adminId, requestedRoles);
  }

  /**
   * Mirrors {@code CreateConsultantSaga}: Matrix account creation is non-fatal — on any failure the
   * matrixUserId is left {@code null} and the grant continues.
   */
  private String createMatrixAccount(de.caritas.cob.userservice.api.model.Admin admin) {
    try {
      var matrixPassword = userHelper.getRandomPassword();
      // ADR-002 §2 / #1200: the Synapse displayname is readable by every member of a shared room
      // via /joined_members, the advice seeker included — so it must never be the real name. An
      // Admin carries no public display name of its own (nor does GrantConsultantIdentityDTO), so
      // the resolver falls back to the username the Matrix ID already exposes. The rule is NOT
      // repeated here: ConsultantDisplayNameResolver stays the only place that decides.
      var matrixDisplayName =
          consultantDisplayNameResolver.resolveMatrixDisplayName(null, admin.getUsername());
      var matrixUserId =
          matrixUserClient.createUserId(admin.getUsername(), matrixPassword, matrixDisplayName);
      if (matrixUserId != null) {
        return matrixUserId;
      }
      log.warn(
          "Chat account provisioning answered without a user_id while granting consultant identity"
              + " to admin {}; the consultant is created without a chat identity and must be"
              + " repaired via POST /useradmin/consultants/{}/chat-identity (#1194)",
          admin.getId(),
          admin.getId());
    } catch (Exception e) {
      log.error(
          "Chat account provisioning failed while granting consultant identity to admin {};"
              + " continuing without a chat identity. The consultant cannot be used for"
              + " counselling until it is repaired via POST"
              + " /useradmin/consultants/{}/chat-identity (#1194)",
          admin.getId(),
          admin.getId(),
          e);
    }
    return null;
  }

  /**
   * Builds the {@link Consultant} entity by mirroring {@code CreateConsultantSaga.buildConsultant}
   * — only the fields that method sets are set here. The consultant shares the admin's Keycloak id
   * and copies username/firstName/lastName/email/tenantId from the {@code Admin}.
   */
  private Consultant buildConsultant(
      de.caritas.cob.userservice.api.model.Admin admin,
      String encodedUsername,
      GrantConsultantIdentityDTO dto,
      String matrixUserId) {

    var now = nowInUtc();
    var consultant =
        Consultant.builder()
            .id(admin.getId())
            .createDate(now)
            .updateDate(now)
            .username(encodedUsername)
            .firstName(admin.getFirstName())
            .lastName(admin.getLastName())
            .email(admin.getEmail())
            .absent(dto.isAbsent())
            .absenceMessage(dto.getAbsenceMessage())
            .teamConsultant(false)
            .matrixUserId(matrixUserId)
            .encourage2fa(true)
            // Same kind of account as the admin create path, so it owes the same second factor.
            // passwordChangeRequired stays false: no new password is chosen here.
            .twoFactorRequired(true)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .languageFormal(dto.isFormalLanguage())
            .languages(Set.of())
            .tenantId(admin.getTenantId())
            .status(ConsultantStatus.CREATED)
            .walkThroughEnabled(true)
            .languageCode(LanguageCode.de)
            .notificationsEnabled(true)
            .notificationsSettings(serializeToJsonString(allActiveNotifications()))
            .build();

    consultant.replaceTopics(dto.getTopicIds());
    return consultant;
  }

  private void saveConsultantOrRollback(
      String adminId, GrantConsultantIdentityDTO dto, Consultant consultant) {
    try {
      consultantService.saveConsultant(consultant);
    } catch (Exception e) {
      log.error(
          "Unable to persist consultant identity for admin with id {} in database. Rolling back keycloak roles.",
          adminId,
          e);
      removeGrantedRoles(adminId, dto);
      throw new DistributedTransactionException(
          e,
          DistributedTransactionInfo.builder()
              .name(GRANT_CONSULTANT_IDENTITY)
              .completedTransactionalOperations(
                  newArrayList(TransactionalStep.ASSIGN_CONSULTANT_ROLE_IN_KEYCLOAK))
              .failedStep(TransactionalStep.CREATE_CONSULTANT_IN_MARIADB_FOR_EXISTING_USER)
              .build());
    }
  }

  /**
   * Assigns the requested agencies to the new consultant identity. Assignment failures are surfaced
   * to the caller so the grant does not silently leave a partially usable consultant identity.
   */
  private void assignAgencies(String adminId, GrantConsultantIdentityDTO dto) {
    if (dto.getAgencyIds() == null) {
      return;
    }
    for (Long agencyId : dto.getAgencyIds()) {
      consultantAgencyRelationCreatorService.createNewConsultantAgency(
          adminId, new CreateConsultantAgencyDTO().agencyId(agencyId));
    }
  }

  private void removeGrantedRoles(String adminId, GrantConsultantIdentityDTO dto) {
    identityClient.removeRoleIfPresent(adminId, CONSULTANT.getValue());
    if (dto.isGroupchatConsultant()) {
      identityClient.removeRoleIfPresent(adminId, GROUP_CHAT_CONSULTANT.getValue());
    }
  }

  private NotificationsSettingsDTO allActiveNotifications() {
    NotificationsSettingsDTO notificationsSettingsDTO = new NotificationsSettingsDTO();
    notificationsSettingsDTO.setReassignmentNotificationEnabled(true);
    notificationsSettingsDTO.setInitialEnquiryNotificationEnabled(true);
    notificationsSettingsDTO.setAppointmentNotificationEnabled(true);
    notificationsSettingsDTO.setNewChatMessageNotificationEnabled(true);
    return notificationsSettingsDTO;
  }
}
