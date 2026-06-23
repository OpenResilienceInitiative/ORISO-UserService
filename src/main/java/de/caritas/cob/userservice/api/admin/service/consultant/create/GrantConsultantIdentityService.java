package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static com.google.common.collect.Lists.newArrayList;
import static de.caritas.cob.userservice.api.config.auth.UserRole.CONSULTANT;
import static de.caritas.cob.userservice.api.config.auth.UserRole.GROUP_CHAT_CONSULTANT;
import static de.caritas.cob.userservice.api.helper.json.JsonSerializationUtils.serializeToJsonString;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NotificationsSettingsDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantResponseDTOBuilder;
import de.caritas.cob.userservice.api.admin.service.consultant.TransactionalStep;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionInfo;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.service.ConsultantService;
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

  private static final String GRANT_CONSULTANT_IDENTITY = "grantConsultantIdentity";

  private final @NonNull AdminRepository adminRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull RocketChatService rocketChatService;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull ConsultantAgencyRelationCreatorService
      consultantAgencyRelationCreatorService;
  private final @NonNull UserHelper userHelper;

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

    var admin =
        adminRepository
            .findById(adminId)
            .orElseThrow(
                () ->
                    new BadRequestException(String.format("Admin with id %s not found", adminId)));

    if (consultantRepository.findByIdAndDeleteDateIsNull(adminId).isPresent()) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.CONSULTANT_IDENTITY_ALREADY_GRANTED, HttpStatus.CONFLICT);
    }

    var encodedUsername = usernameTranscoder.encodeUsername(admin.getUsername());
    if (consultantRepository.findByUsernameAndDeleteDateIsNull(encodedUsername).isPresent()) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.CONSULTANT_IDENTITY_ALREADY_GRANTED, HttpStatus.CONFLICT);
    }

    assignKeycloakRoles(adminId, dto);

    String matrixUserId = createMatrixAccount(admin);
    String rocketChatUserId = resolveRocketChatUserId(admin);

    var consultant = buildConsultant(admin, encodedUsername, dto, rocketChatUserId, matrixUserId);
    saveConsultantOrRollback(adminId, dto, consultant);

    assignAgencies(adminId, dto);

    return ConsultantResponseDTOBuilder.getInstance(consultant).buildResponseDTO();
  }

  private void assignKeycloakRoles(String adminId, GrantConsultantIdentityDTO dto) {
    identityClient.ensureRole(adminId, CONSULTANT.getValue());
    if (dto.isGroupchatConsultant()) {
      identityClient.ensureRole(adminId, GROUP_CHAT_CONSULTANT.getValue());
    }
  }

  /**
   * Mirrors {@code CreateConsultantSaga}: Matrix account creation is non-fatal — on any failure the
   * matrixUserId is left {@code null} and the grant continues.
   */
  private String createMatrixAccount(de.caritas.cob.userservice.api.model.Admin admin) {
    try {
      var matrixPassword = userHelper.getRandomPassword();
      var matrixResponse =
          matrixSynapseService.createUser(
              admin.getUsername(),
              matrixPassword,
              admin.getFirstName() + " " + admin.getLastName());
      if (matrixResponse.getBody() != null && matrixResponse.getBody().getUserId() != null) {
        return matrixResponse.getBody().getUserId();
      }
      log.warn(
          "Matrix user creation response missing user_id while granting consultant identity to admin {}",
          admin.getId());
    } catch (Exception e) {
      log.error(
          "Matrix user creation failed while granting consultant identity to admin {}, but continuing",
          admin.getId(),
          e);
    }
    return null;
  }

  /**
   * Mirrors {@code CreateConsultantSaga}: reuse the admin's existing Rocket.Chat id when present;
   * otherwise best-effort create, falling back to a dummy id on failure.
   */
  private String resolveRocketChatUserId(de.caritas.cob.userservice.api.model.Admin admin) {
    if (isNotBlank(admin.getRcUserId())) {
      return admin.getRcUserId();
    }
    try {
      return rocketChatService.getUserID(
          usernameTranscoder.encodeUsername(admin.getUsername()),
          userHelper.getRandomPassword(),
          true);
    } catch (Exception e) {
      log.warn(
          "Unable to create Rocket.Chat user while granting consultant identity to admin {}. Using dummy id. Error: {}",
          admin.getId(),
          e.getMessage());
      return "dummy-rc";
    }
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
      String rocketChatUserId,
      String matrixUserId) {

    var consultant =
        Consultant.builder()
            .id(admin.getId())
            .username(encodedUsername)
            .firstName(admin.getFirstName())
            .lastName(admin.getLastName())
            .email(admin.getEmail())
            .absent(dto.isAbsent())
            .absenceMessage(dto.getAbsenceMessage())
            .teamConsultant(false)
            .rocketChatId(rocketChatUserId)
            .matrixUserId(matrixUserId)
            .encourage2fa(true)
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
      identityClient.removeRoleIfPresent(adminId, CONSULTANT.getValue());
      if (dto.isGroupchatConsultant()) {
        identityClient.removeRoleIfPresent(adminId, GROUP_CHAT_CONSULTANT.getValue());
      }
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
   * Assigns the requested agencies to the new consultant identity. Mirrors existing precedent: each
   * assignment is non-fatal — a failing agency is logged and does not roll back the grant.
   */
  private void assignAgencies(String adminId, GrantConsultantIdentityDTO dto) {
    if (dto.getAgencyIds() == null) {
      return;
    }
    for (Long agencyId : dto.getAgencyIds()) {
      try {
        consultantAgencyRelationCreatorService.createNewConsultantAgency(
            adminId, new CreateConsultantAgencyDTO().agencyId(agencyId));
      } catch (Exception e) {
        log.error(
            "Unable to assign agency {} to granted consultant identity {}: {}",
            agencyId,
            adminId,
            e.getMessage());
      }
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
