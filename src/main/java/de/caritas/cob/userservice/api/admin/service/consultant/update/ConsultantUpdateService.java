package de.caritas.cob.userservice.api.admin.service.consultant.update;

import static de.caritas.cob.userservice.api.config.auth.UserRole.GROUP_CHAT_CONSULTANT;
import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static java.util.Objects.isNull;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UpdateConsultantDTOAbsenceInputAdapter;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Language;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantPublicSlugService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service class to provide update functionality for consultants. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultantUpdateService {

  private final @NonNull IdentityClient identityClient;
  private final @NonNull IdentityProfileUpdater identityProfileUpdater;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull ConsultantPublicSlugService consultantPublicSlugService;
  private final @NonNull UserAccountInputValidator userAccountInputValidator;
  private final @NonNull MatrixUserClient matrixUserClient;
  private final @NonNull AppointmentService appointmentService;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull ConsultantTopicAgencyCompatibilityValidator
      consultantTopicAgencyCompatibilityValidator;

  /**
   * Updates the basic data of consultant with given id.
   *
   * @param consultantId the id of the consultant to update
   * @param updateConsultantDTO the update input data
   * @return the updated persisted {@link Consultant}
   */
  @Transactional
  public Consultant updateConsultant(
      String consultantId, UpdateAdminConsultantDTO updateConsultantDTO) {
    return updateConsultant(consultantId, updateConsultantDTO, true);
  }

  @Transactional
  public Consultant updateConsultant(
      String consultantId, UpdateAdminConsultantDTO updateConsultantDTO, boolean adminEdit) {
    this.userAccountInputValidator.validateAbsence(
        new UpdateConsultantDTOAbsenceInputAdapter(updateConsultantDTO));

    Consultant consultant =
        this.consultantService
            .getConsultant(consultantId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        String.format("Consultant with id %s does not exist", consultantId)));

    consultantTopicAgencyCompatibilityValidator.validateTopicUpdateAgainstAssignedAgencies(
        consultant.getId(), updateConsultantDTO.getTopicIds(), consultant.getTenantId());

    String previousDisplayName = displayNameOf(consultant.getFirstName(), consultant.getLastName());
    String nextDisplayName =
        displayNameOf(updateConsultantDTO.getFirstname(), updateConsultantDTO.getLastname());
    boolean identityDataChanged = identityDataChanged(consultant, updateConsultantDTO);
    boolean appointmentDataChanged =
        identityDataChanged
            || !Objects.equals(consultant.isAbsent(), updateConsultantDTO.getAbsent());

    if (identityDataChanged) {
      UserDTO userDTO = buildValidatedUserDTO(updateConsultantDTO, consultant);
      this.identityProfileUpdater.updateProfile(
          consultant.getId(),
          new IdentityProfileUpdate(
              userDTO.getUsername(),
              userDTO.getEmail(),
              userDTO.getTenantId(),
              updateConsultantDTO.getFirstname(),
              updateConsultantDTO.getLastname()));
    }

    if (updateConsultantDTO.getIsGroupchatConsultant() != null
        && updateConsultantDTO.getIsGroupchatConsultant()) {
      identityClient.updateRole(consultant.getId(), GROUP_CHAT_CONSULTANT.getValue());
    }
    if (updateConsultantDTO.getIsGroupchatConsultant() != null
        && !updateConsultantDTO.getIsGroupchatConsultant()) {
      identityClient.removeRoleIfPresent(consultant.getId(), GROUP_CHAT_CONSULTANT.getValue());
    }

    // Update Matrix user display name using the admin API (no password needed).
    if (identityDataChanged && consultant.getMatrixUserId() != null) {
      try {
        String newDisplayName =
            updateConsultantDTO.getFirstname() + " " + updateConsultantDTO.getLastname();
        matrixUserClient.updateUserDisplayName(consultant.getMatrixUserId(), newDisplayName);
      } catch (Exception e) {
        // Matrix update failures are non-blocking
      }
    }

    var updatedConsultant = updateDatabaseConsultant(updateConsultantDTO, consultant, adminEdit);
    if (appointmentDataChanged) {
      appointmentService.syncConsultantData(updatedConsultant);
    }
    if (identityDataChanged) {
      emitCounselorRenameNotificationsIfNeeded(consultant, previousDisplayName, nextDisplayName);
    }
    return updatedConsultant;
  }

  private boolean identityDataChanged(
      Consultant consultant, UpdateAdminConsultantDTO updateConsultantDTO) {
    return !Objects.equals(consultant.getFirstName(), updateConsultantDTO.getFirstname())
        || !Objects.equals(consultant.getLastName(), updateConsultantDTO.getLastname())
        || !Objects.equals(consultant.getEmail(), updateConsultantDTO.getEmail());
  }

  private UserDTO buildValidatedUserDTO(
      UpdateAdminConsultantDTO updateConsultantDTO, Consultant consultant) {
    UserDTO userDTO = new UserDTO();
    userDTO.setEmail(updateConsultantDTO.getEmail());
    userDTO.setUsername(consultant.getUsername());
    userDTO.setTenantId(consultant.getTenantId());

    this.userAccountInputValidator.validateUserDTO(userDTO);
    return userDTO;
  }

  private Consultant updateDatabaseConsultant(
      UpdateAdminConsultantDTO updateConsultantDTO, Consultant consultant, boolean adminEdit) {
    consultant.setFirstName(updateConsultantDTO.getFirstname());
    consultant.setLastName(updateConsultantDTO.getLastname());
    consultant.setEmail(updateConsultantDTO.getEmail());
    consultant.setLanguageFormal(updateConsultantDTO.getFormalLanguage());
    consultant.setLanguages(languagesOf(updateConsultantDTO, consultant));
    consultant.setAbsent(updateConsultantDTO.getAbsent());
    consultant.setAbsenceMessage(updateConsultantDTO.getAbsenceMessage());
    applyPersonalInfo(updateConsultantDTO, consultant);
    consultant.replaceTopics(updateConsultantDTO.getTopicIds());
    // Always update supervisor field if provided (even if false)
    if (updateConsultantDTO.getIsSupervisor() != null) {
      consultant.setSupervisor(updateConsultantDTO.getIsSupervisor());
    }
    if (adminEdit) {
      if (Boolean.TRUE.equals(updateConsultantDTO.getRejectPendingPublicSlug())) {
        consultantPublicSlugService.rejectPendingSlug(consultant);
      } else {
        consultantPublicSlugService.applyAdminSlug(consultant, updateConsultantDTO.getPublicSlug());
      }
    } else {
      consultantPublicSlugService.requestSlug(consultant, updateConsultantDTO.getPublicSlug());
    }
    applyStandingSupervisor(updateConsultantDTO, consultant);
    consultant.setUpdateDate(nowInUtc());
    if (updateConsultantDTO.getTermsAndConditionsConfirmation() != null
        && updateConsultantDTO.getTermsAndConditionsConfirmation()) {
      consultant.setTermsAndConditionsConfirmation(LocalDateTime.now());
    }
    if (updateConsultantDTO.getDataPrivacyConfirmation() != null
        && updateConsultantDTO.getDataPrivacyConfirmation()) {
      consultant.setDataPrivacyConfirmation(LocalDateTime.now());
    }

    return this.consultantService.saveConsultant(consultant);
  }

  /**
   * Personal-info fields (#994) follow the "null leaves untouched, empty string clears" convention
   * so the consultant self-service path (which never sends them) cannot wipe admin-entered values.
   * {@code adminRemarks} write access is enforced upstream in {@link
   * de.caritas.cob.userservice.api.admin.service.consultant.ConsultantAdminService}: for callers
   * without tenant-level admin rights the field is nulled before it reaches this method.
   */
  private void applyPersonalInfo(
      UpdateAdminConsultantDTO updateConsultantDTO, Consultant consultant) {
    applyIfProvided(updateConsultantDTO.getSalutation(), consultant::setSalutation);
    applyIfProvided(updateConsultantDTO.getPosition(), consultant::setPosition);
    applyIfProvided(updateConsultantDTO.getTitle(), consultant::setTitle);
    applyIfProvided(updateConsultantDTO.getAdminRemarks(), consultant::setAdminRemarks);
  }

  private void applyIfProvided(String value, java.util.function.Consumer<String> setter) {
    if (value == null) {
      return;
    }
    setter.accept(value.isBlank() ? null : value);
  }

  /**
   * "Supervision (auto-assigned)" (grill 2026-07-13): set or clear this counsellor's standing
   * supervisor — the colleague auto-attached read-only to every case the counsellor accepts.
   *
   * <p>Omitted/null leaves the assignment untouched (mirroring the sibling {@code isSupervisor}
   * convention); an empty string clears it. Clearing only stops FUTURE auto-attachment —
   * supervisors already attached to in-flight cases stay until removed explicitly, since
   * retroactively stripping oversight from live cases is not the admin's intent here.
   *
   * @throws BadRequestException if the target is not flagged {@code isSupervisor}, does not exist,
   *     or is the counsellor themselves
   */
  private void applyStandingSupervisor(
      UpdateAdminConsultantDTO updateConsultantDTO, Consultant consultant) {
    String assignedSupervisorId = updateConsultantDTO.getAssignedSupervisorId();
    if (assignedSupervisorId == null) {
      return;
    }
    if (assignedSupervisorId.isBlank()) {
      consultant.setAssignedSupervisorId(null);
      return;
    }
    if (assignedSupervisorId.equals(consultant.getId())) {
      throw new BadRequestException("A consultant cannot be their own standing supervisor");
    }
    Consultant standingSupervisor =
        this.consultantService
            .getConsultant(assignedSupervisorId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "Standing supervisor not found: " + assignedSupervisorId));
    if (!standingSupervisor.isSupervisor()) {
      throw new BadRequestException(
          "Consultant "
              + assignedSupervisorId
              + " cannot be a standing supervisor (isSupervisor is false)");
    }
    consultant.setAssignedSupervisorId(assignedSupervisorId);
  }

  private Set<Language> languagesOf(
      UpdateAdminConsultantDTO updateConsultantDTO, Consultant consultant) {
    var languages = updateConsultantDTO.getLanguages();

    return isNull(languages)
        ? Set.of()
        : languages.stream()
            .map(LanguageCode::getByCode)
            .map(languageCode -> new Language(consultant, languageCode))
            .collect(Collectors.toSet());
  }

  private void emitCounselorRenameNotificationsIfNeeded(
      Consultant consultant, String previousDisplayName, String nextDisplayName) {
    if (consultant == null || consultant.getId() == null) {
      return;
    }
    if (previousDisplayName.equals(nextDisplayName)) {
      return;
    }
    var activeStatuses = List.of(SessionStatus.NEW, SessionStatus.IN_PROGRESS);
    var sessions = sessionRepository.findByConsultantAndStatusIn(consultant, activeStatuses);
    sessions.stream()
        .filter(session -> session.getUser() != null && session.getUser().getUserId() != null)
        .forEach(
            session ->
                eventNotificationService.createCounselorRenamedNotification(
                    session, session.getUser().getUserId(), previousDisplayName, nextDisplayName));
    log.info(
        "Counselor rename event created for consultantId={} from='{}' to='{}' sessions={}",
        consultant.getId(),
        previousDisplayName,
        nextDisplayName,
        sessions.size());
  }

  private String displayNameOf(String firstName, String lastName) {
    String first = firstName == null ? "" : firstName.trim();
    String last = lastName == null ? "" : lastName.trim();
    String combined = (first + " " + last).trim();
    return combined.isBlank() ? "Counselor" : combined;
  }
}
