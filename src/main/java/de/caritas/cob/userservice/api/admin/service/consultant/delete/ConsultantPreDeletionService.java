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

  private de.caritas.cob.userservice.api.port.out.ConsultantRepository preDeletionConsultants;

  @org.springframework.beans.factory.annotation.Autowired
  public void configurePreDeletionConsultants(
      de.caritas.cob.userservice.api.port.out.ConsultantRepository consultants) {
    preDeletionConsultants = java.util.Objects.requireNonNull(consultants);
  }

  /** Fixed C preparation; no relation may be written before the original native ACK. */
  public PreparedRelations prepareAcknowledgedDeletion(
      de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
              .AcknowledgedPreDeletion
          acknowledged,
      Boolean forceDeleteSessions) {
    acknowledged.requireConsultantCurrent();
    if (preDeletionConsultants == null)
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    var sessions = new java.util.concurrent.atomic.AtomicReference<java.util.List<SessionSource>>();
    acknowledged.runOwnedLocal(
        () -> {
          var actual = preDeletionConsultants.findById(acknowledged.accountId()).orElseThrow();
          acknowledged.requireCurrentConsultant(actual);
          requireSessionEligibility(actual, forceDeleteSessions);
          sessions.set(capture(actual));
        });
    var relations = agencyDeletionValidationService.prepareAcknowledgedDeletion(acknowledged);
    return new PreparedRelations(
        this, acknowledged, forceDeleteSessions, sessions.get(), relations);
  }

  private void requireSessionEligibility(Consultant actual, Boolean force) {
    if (isNotTrue(force) && hasConsultantActiveSessions(actual))
      throw new CustomValidationHttpStatusException(CONSULTANT_HAS_ACTIVE_OR_ARCHIVE_SESSIONS);
  }

  private record SessionSource(
      Long id,
      long version,
      long ownershipRevision,
      String owner,
      String user,
      Long tenant,
      String room,
      LocalDateTime created,
      LocalDateTime updated,
      String status) {}

  private java.util.List<SessionSource> capture(Consultant actual) {
    return sessionRepository
        .findByConsultantId(actual.getId(), org.springframework.data.domain.Pageable.unpaged())
        .getContent()
        .stream()
        .sorted(java.util.Comparator.comparing(de.caritas.cob.userservice.api.model.Session::getId))
        .map(
            value ->
                new SessionSource(
                    value.getId(),
                    value.getRowVersion(),
                    value.getOwnershipRevision(),
                    value.getConsultant() == null ? null : value.getConsultant().getId(),
                    value.getUser().getUserId(),
                    value.getTenantId(),
                    value.getMatrixRoomId(),
                    value.getCreateDate(),
                    value.getUpdateDate(),
                    value.getStatus().name()))
        .toList();
  }

  public static final class PreparedRelations {
    private final ConsultantPreDeletionService issuer;
    private final de.caritas.cob.userservice.api.workflow.delete.service
            .PublicPreDeletionMaintenance.AcknowledgedPreDeletion
        acknowledged;
    private final Boolean force;
    private final java.util.List<SessionSource> sessions;
    private final ConsultantAgencyDeletionValidationService.PreparedRelations relations;
    private boolean applied;

    private PreparedRelations(
        ConsultantPreDeletionService issuer,
        de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
                .AcknowledgedPreDeletion
            acknowledged,
        Boolean force,
        java.util.List<SessionSource> sessions,
        ConsultantAgencyDeletionValidationService.PreparedRelations relations) {
      this.issuer = issuer;
      this.acknowledged = acknowledged;
      this.force = force;
      this.sessions = sessions;
      this.relations = relations;
    }

    public void apply(Consultant independentlyLoadedOriginal, LocalDateTime deletedAt) {
      acknowledged.requireCurrentConsultant(independentlyLoadedOriginal);
      if (applied || !sessions.equals(issuer.capture(independentlyLoadedOriginal)))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "The original consultant sessions changed");
      issuer.requireSessionEligibility(independentlyLoadedOriginal, force);
      relations.apply(independentlyLoadedOriginal, deletedAt);
      applied = true;
    }

    @Override
    public String toString() {
      return "PreparedRelations[ownership=redacted]";
    }
  }

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

  private boolean hasConsultantActiveSessions(Consultant consultant) {
    return !this.sessionRepository
        .findByConsultantAndStatusIn(consultant, Lists.newArrayList(IN_PROGRESS, IN_ARCHIVE))
        .isEmpty();
  }
}
