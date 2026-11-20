package de.caritas.cob.userservice.api.admin.service.consultant.delete;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.CONSULTANT_HAS_ACTIVE_OR_ARCHIVE_SESSIONS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_ARCHIVE;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isNotTrue;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyDeletionValidationService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Executes pre deletion steps like the validation for a consultant to be deleted; {@link
 * #deactivateIdentity} takes the login away once everything else has succeeded.
 */
@Service
@RequiredArgsConstructor
public class ConsultantPreDeletionService {

  private final @NonNull ConsultantAgencyDeletionValidationService agencyDeletionValidationService;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull IdentityDeactivator identityDeactivator;

  /**
   * Validates if {@link Consultant} can be deleted and marks its agency relations.
   *
   * @param consultant the {@link Consultant} to be deleted
   * @param forceDeleteSessions
   * @param deletedAt the counsellor's delete date, stamped on every relation this removes
   */
  public void performPreDeletionSteps(
      Consultant consultant, Boolean forceDeleteSessions, LocalDateTime deletedAt) {

    if (isNotTrue(forceDeleteSessions) && hasConsultantActiveSessions(consultant)) {
      throw new CustomValidationHttpStatusException(CONSULTANT_HAS_ACTIVE_OR_ARCHIVE_SESSIONS);
    }
    if (nonNull(consultant.getConsultantAgencies())) {
      consultant
          .getConsultantAgencies()
          .forEach(
              relation ->
                  agencyDeletionValidationService.validateAndMarkForDeletion(relation, deletedAt));
    }
  }

  /**
   * Marks the account as inactive in keycloak. Not part of the pre-deletion steps: Keycloak is not
   * rolled back with the database transaction, so it is the last step of a deletion, after
   * everything that can still fail (leaving the private rooms) has succeeded.
   */
  public void deactivateIdentity(Consultant consultant) {
    this.identityDeactivator.deactivateUser(consultant.getId());
  }

  /** Restores the agency relations that {@link #performPreDeletionSteps} removed. */
  public void restoreAgencyRelations(Consultant consultant, LocalDateTime deletedAt) {
    agencyDeletionValidationService.restoreRelationsMarkedWith(consultant, deletedAt);
  }

  /**
   * Turns the login back on after a cancelled deletion. Like {@link #deactivateIdentity} it must be
   * the last step of the transaction, because Keycloak is not rolled back with the database.
   */
  public void activateIdentity(Consultant consultant) {
    this.identityDeactivator.activateUser(consultant.getId());
  }

  private boolean hasConsultantActiveSessions(Consultant consultant) {
    return !this.sessionRepository
        .findByConsultantAndStatusIn(consultant, Lists.newArrayList(IN_PROGRESS, IN_ARCHIVE))
        .isEmpty();
  }
}
