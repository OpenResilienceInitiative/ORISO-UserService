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
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAvatarKind;
import de.caritas.cob.userservice.api.model.ConsultantAvatars;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
  private final @NonNull ConsultantDisplayNameResolver consultantDisplayNameResolver;

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

    // Captured before the entity is mutated, so a display-name-only edit can be detected below.
    String previousPublishedName =
        consultantDisplayNameResolver.resolveMatrixDisplayName(consultant);

    var updatedConsultant = updateDatabaseConsultant(updateConsultantDTO, consultant, adminEdit);
    // updateDatabaseConsultant mutates this very entity, so it already carries the new values.
    scheduleMatrixDisplayNameUpdate(consultant, identityDataChanged, previousPublishedName);
    if (appointmentDataChanged) {
      appointmentService.syncConsultantData(updatedConsultant);
    }
    emitCounselorRenameNotificationsIfNeeded(
        consultant,
        previousPublishedName,
        consultantDisplayNameResolver.resolveMatrixDisplayName(consultant));
    return updatedConsultant;
  }

  /**
   * Decides whether the counsellor's Matrix {@code displayname} must be pushed, and defers the push
   * itself until the surrounding transaction has committed.
   *
   * <p>ADR-002 §2 / #1200: the advice seeker is a real member of the shared room and can read every
   * member's displayname from {@code /joined_members}, so this must never be {@code firstName + " "
   * + lastName}. {@link ConsultantDisplayNameResolver} is the single place that decides which name
   * may go there — this method only decides <em>whether</em> to send it.
   *
   * <p>It sends on any identity edit (which also repairs profiles provisioned before the fix) and
   * on a change of the resolved pseudonym itself.
   *
   * <p><b>Why after the commit.</b> {@code updateConsultant} is {@code @Transactional} and work
   * that runs after this point is not fully contained — the rename-notification lookup and save can
   * throw, which rolls the consultant update back. Sending inside the transaction would leave
   * Synapse holding a name the database never kept, and nothing reconciles that drift. The values
   * are therefore resolved <em>now</em>, while the entity is loaded and carries the new state, and
   * only the outbound call waits for the commit. Without an active transaction (a direct call in a
   * unit test) there is nothing to wait for, so the push happens immediately.
   *
   * <p>Failures stay non-blocking: the Matrix profile is cosmetic next to the persisted update.
   */
  private void scheduleMatrixDisplayNameUpdate(
      Consultant consultant, boolean identityDataChanged, String previousMatrixDisplayName) {
    final String matrixUserId = consultant.getMatrixUserId();
    if (matrixUserId == null) {
      return;
    }
    final String consultantId = consultant.getId();
    final String newDisplayName =
        consultantDisplayNameResolver.resolveMatrixDisplayName(consultant);
    if (!identityDataChanged && Objects.equals(previousMatrixDisplayName, newDisplayName)) {
      return;
    }

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      pushMatrixDisplayName(consultantId, matrixUserId, newDisplayName);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            pushMatrixDisplayName(consultantId, matrixUserId, newDisplayName);
          }
        });
  }

  private void pushMatrixDisplayName(
      String consultantId, String matrixUserId, String newDisplayName) {
    try {
      matrixUserClient.updateUserDisplayName(matrixUserId, newDisplayName);
    } catch (Exception e) {
      log.warn(
          "Matrix display name update failed for consultant {}, but continuing", consultantId, e);
    }
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
    applyIfProvided(updateConsultantDTO.getDisplayName(), consultant::setDisplayName);
    applyIfProvided(
        updateConsultantDTO.getInternalDisplayName(), consultant::setInternalDisplayName);
    applyIfProvided(updateConsultantDTO.getSalutation(), consultant::setSalutation);
    applyIfProvided(updateConsultantDTO.getPosition(), consultant::setPosition);
    applyIfProvided(updateConsultantDTO.getTitle(), consultant::setTitle);
    applyIfProvided(updateConsultantDTO.getAdminRemarks(), consultant::setAdminRemarks);
    applyAvatar(updateConsultantDTO, consultant);
  }

  /**
   * Counsellor avatar choice (#1046). The kind is a typed enum, so it carries no empty-string
   * clear: null leaves the stored choice untouched, INITIALS drops an icon. The motif id follows
   * the usual convention (null untouched, empty clears). The resolved pair goes through {@link
   * ConsultantAvatars#apply}, the one place that rejects a half choice — so clearing the id of a
   * stored ICON demotes it to INITIALS instead of leaving an icon without a motif.
   */
  private void applyAvatar(UpdateAdminConsultantDTO updateConsultantDTO, Consultant consultant) {
    var requestedKind = updateConsultantDTO.getAvatarKind();
    var requestedId = updateConsultantDTO.getAvatarId();
    if (requestedKind == null && requestedId == null) {
      return;
    }
    ConsultantAvatarKind resolvedKind =
        requestedKind == null
            ? consultant.getAvatarKind()
            : ConsultantAvatarKind.fromNameOrNull(requestedKind.getValue());
    String resolvedId = requestedId == null ? consultant.getAvatarId() : requestedId;
    ConsultantAvatars.apply(consultant, resolvedKind, resolvedId);
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
    // A platform admin sees consultants across tenants, so nothing above stops a cross-tenant
    // assignment. Storing one is worse than rejecting it: attachStandingSupervisorIfAssigned is
    // best-effort and swallows the failure, so the case would run unsupervised while the admin
    // board shows a supervisor. Objects.equals covers single-tenant deployments, where both ids
    // are null.
    if (!java.util.Objects.equals(consultant.getTenantId(), standingSupervisor.getTenantId())) {
      throw new BadRequestException(
          "Consultant "
              + assignedSupervisorId
              + " cannot be a standing supervisor (different tenant)");
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

  /**
   * Tells the advice seekers of this counsellor's open cases that the name they see has changed.
   *
   * <p>ADR-002 §2 / #1201. Two things here are deliberate:
   *
   * <ul>
   *   <li><b>What triggers it</b> is a change of the <em>published</em> name — the pseudonym
   *       resolved by {@link ConsultantDisplayNameResolver} — not of {@code firstName + " " +
   *       lastName}. The advice seeker never saw the real name, so a real-name edit changes nothing
   *       for them and must not produce an entry; conversely a pseudonym-only edit changes
   *       everything they see and previously produced none.
   *   <li><b>What it carries</b> is no name at all. Neither name is passed on, and neither is
   *       logged: the old pseudonym plus the new one is a rename history, and with no pseudonym
   *       stored both values are the real name.
   * </ul>
   */
  private void emitCounselorRenameNotificationsIfNeeded(
      Consultant consultant, String previousPublishedName, String nextPublishedName) {
    if (consultant == null || consultant.getId() == null) {
      return;
    }
    if (Objects.equals(previousPublishedName, nextPublishedName)) {
      return;
    }
    var activeStatuses = List.of(SessionStatus.NEW, SessionStatus.IN_PROGRESS);
    var sessions = sessionRepository.findByConsultantAndStatusIn(consultant, activeStatuses);
    sessions.stream()
        .filter(session -> session.getUser() != null && session.getUser().getUserId() != null)
        .forEach(
            session ->
                eventNotificationService.createCounselorRenamedNotification(
                    session, session.getUser().getUserId()));
    log.info(
        "Counselor rename event created for consultantId={} sessions={}",
        consultant.getId(),
        sessions.size());
  }
}
