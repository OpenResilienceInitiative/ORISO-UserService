package de.caritas.cob.userservice.api.admin.service.agency;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_HAS_OPEN_ENQUIRIES;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_IS_STILL_ACTIVE;
import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.INITIAL;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static java.util.Objects.isNull;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.time.LocalDateTime;
import java.util.function.Predicate;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantAgencyDeletionValidationService {

  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull AgencyService agencyService;
  private final @NonNull SessionRepository sessionRepository;

  /**
   * No writes or native identity effects; the caller alone can settle its original PREPARED intent.
   */
  public de.caritas.cob.userservice.api.exception.httpresponses.customheader
          .HttpStatusExceptionReason
      validatePreflight(
          de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
                  .PendingConsultantPreflight
              pending) {
    pending.requireCurrent();
    var source = new java.util.concurrent.atomic.AtomicReference<java.util.List<Relation>>();
    pending.runOwnedLocal(() -> source.set(capture(pending.accountId())));
    for (var relation : source.get()) {
      if (!relation.last()) continue;
      pending.requireCurrent();
      if (org.springframework.transaction.support.TransactionSynchronizationManager
          .isActualTransactionActive())
        throw new de.caritas.cob.userservice.api.exception.httpresponses
            .ServiceUnavailableException("Agency reads must run outside SQL");
      AgencyDTO agency = agencyService.getAgencyWithoutCaching(relation.agency());
      var reason =
          new java.util.concurrent.atomic.AtomicReference<
              de.caritas.cob.userservice.api.exception.httpresponses.customheader
                  .HttpStatusExceptionReason>();
      pending.runOwnedLocal(
          () -> {
            requireSource(pending.accountId(), source.get());
            if (agency != null) {
              if (isFalse(agency.getOffline()))
                reason.set(CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_IS_STILL_ACTIVE);
              else if (hasSessionWithStatus(relation.agency(), NEW)
                  || hasSessionWithStatus(relation.agency(), INITIAL))
                reason.set(CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_HAS_OPEN_ENQUIRIES);
            }
          });
      if (reason.get() != null) return reason.get();
    }
    pending.runOwnedLocal(() -> requireSource(pending.accountId(), source.get()));
    return null;
  }

  /** Fixed ACK-owned split: remote agency reads outside SQL, relation changes in final SQL. */
  public PreparedRelations prepareAcknowledgedDeletion(
      de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
              .AcknowledgedPreDeletion
          acknowledged) {
    acknowledged.requireConsultantCurrent();
    var source = new java.util.concurrent.atomic.AtomicReference<java.util.List<Relation>>();
    acknowledged.runOwnedLocal(() -> source.set(capture(acknowledged.accountId())));
    var orphans = new java.util.HashSet<Long>();
    for (var relation : source.get()) {
      if (!relation.last()) continue;
      acknowledged.runOwnedLocal(() -> requireSource(acknowledged.accountId(), source.get()));
      if (org.springframework.transaction.support.TransactionSynchronizationManager
          .isActualTransactionActive())
        throw new de.caritas.cob.userservice.api.exception.httpresponses
            .ServiceUnavailableException("Agency reads must run outside SQL");
      AgencyDTO agency = agencyService.getAgencyWithoutCaching(relation.agency());
      if (agency == null) orphans.add(relation.id());
      else if (isFalse(agency.getOffline()))
        throw new CustomValidationHttpStatusException(
            CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_IS_STILL_ACTIVE);
      acknowledged.runOwnedLocal(
          () -> {
            requireSource(acknowledged.accountId(), source.get());
            if (agency != null
                && (hasSessionWithStatus(relation.agency(), NEW)
                    || hasSessionWithStatus(relation.agency(), INITIAL)))
              throw new CustomValidationHttpStatusException(
                  CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_HAS_OPEN_ENQUIRIES);
          });
    }
    return new PreparedRelations(this, acknowledged, source.get(), java.util.Set.copyOf(orphans));
  }

  private record Relation(
      Long id,
      String consultant,
      Long agency,
      LocalDateTime created,
      LocalDateTime updated,
      LocalDateTime deleted,
      Long tenant,
      String status,
      java.util.List<Peer> peers) {
    boolean last() {
      return peers.stream().allMatch(peer -> id.equals(peer.id()));
    }
  }

  private record Peer(
      Long id,
      String consultant,
      Long agency,
      LocalDateTime created,
      LocalDateTime updated,
      LocalDateTime deleted,
      Long tenant,
      String status) {}

  private java.util.List<Relation> capture(String account) {
    return consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(account).stream()
        .sorted(java.util.Comparator.comparing(ConsultantAgency::getId))
        .map(
            relation ->
                new Relation(
                    relation.getId(),
                    relation.getConsultant().getId(),
                    relation.getAgencyId(),
                    relation.getCreateDate(),
                    relation.getUpdateDate(),
                    relation.getDeleteDate(),
                    relation.getTenantId(),
                    relation.getStatus() == null ? null : relation.getStatus().name(),
                    consultantAgencyRepository
                        .findByAgencyIdAndDeleteDateIsNull(relation.getAgencyId())
                        .stream()
                        .sorted(java.util.Comparator.comparing(ConsultantAgency::getId))
                        .map(
                            peer ->
                                new Peer(
                                    peer.getId(),
                                    peer.getConsultant().getId(),
                                    peer.getAgencyId(),
                                    peer.getCreateDate(),
                                    peer.getUpdateDate(),
                                    peer.getDeleteDate(),
                                    peer.getTenantId(),
                                    peer.getStatus() == null ? null : peer.getStatus().name()))
                        .toList()))
        .toList();
  }

  private void requireSource(String account, java.util.List<Relation> source) {
    if (!source.equals(capture(account)))
      throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
          "The agency deletion source changed");
  }

  public static final class PreparedRelations {
    private final ConsultantAgencyDeletionValidationService issuer;
    private final de.caritas.cob.userservice.api.workflow.delete.service
            .PublicPreDeletionMaintenance.AcknowledgedPreDeletion
        acknowledged;
    private final java.util.List<Relation> source;
    private final java.util.Set<Long> orphans;
    private boolean applied;

    private PreparedRelations(
        ConsultantAgencyDeletionValidationService issuer,
        de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
                .AcknowledgedPreDeletion
            acknowledged,
        java.util.List<Relation> source,
        java.util.Set<Long> orphans) {
      this.issuer = issuer;
      this.acknowledged = acknowledged;
      this.source = source;
      this.orphans = orphans;
    }

    public void apply(
        de.caritas.cob.userservice.api.model.Consultant independentlyLoadedOriginal,
        LocalDateTime deletedAt) {
      acknowledged.requireCurrentConsultant(independentlyLoadedOriginal);
      if (applied || deletedAt == null)
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "The agency preparation was consumed");
      issuer.requireSource(independentlyLoadedOriginal.getId(), source);
      for (var relation : source) {
        if (relation.last()
            && !orphans.contains(relation.id())
            && (issuer.hasSessionWithStatus(relation.agency(), NEW)
                || issuer.hasSessionWithStatus(relation.agency(), INITIAL)))
          throw new CustomValidationHttpStatusException(
              CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_HAS_OPEN_ENQUIRIES);
        var actual = issuer.consultantAgencyRepository.findById(relation.id()).orElseThrow();
        actual.setDeleteDate(deletedAt);
        issuer.consultantAgencyRepository.save(actual);
      }
      applied = true;
    }

    @Override
    public String toString() {
      return "PreparedRelations[ownership=redacted]";
    }
  }

  /**
   * Validates if the given {@link ConsultantAgency} is valid to be deleted.
   *
   * @param consultantAgency the {@link ConsultantAgency} to be deleted
   */
  public void validateAndMarkForDeletion(ConsultantAgency consultantAgency) {
    validateAndMarkForDeletion(consultantAgency, nowInUtc());
  }

  /**
   * Like {@link #validateAndMarkForDeletion(ConsultantAgency)}, stamping the given delete date.
   *
   * @param consultantAgency the {@link ConsultantAgency} to be deleted
   * @param deletedAt the delete date to stamp on the relation
   */
  public void validateAndMarkForDeletion(
      ConsultantAgency consultantAgency, LocalDateTime deletedAt) {
    if (isTheLastConsultantInAgency(consultantAgency)) {
      AgencyDTO agency = this.agencyService.getAgencyWithoutCaching(consultantAgency.getAgencyId());
      if (isNull(agency)) {
        // Orphaned relation (#86): the agency no longer exists, so it can neither be active nor
        // accept enquiries. It must not block consultant deletion — soft-delete the relation.
        log.warn(
            "Agency {} referenced by consultant {} no longer exists - skipping agency guards and"
                + " soft-deleting the orphaned relation",
            consultantAgency.getAgencyId(),
            consultantAgency.getConsultant().getId());
      } else {
        if (isFalse(agency.getOffline())) {
          throw new CustomValidationHttpStatusException(
              CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_IS_STILL_ACTIVE);
        }
        if (hasOpenEnquiries(consultantAgency)) {
          throw new CustomValidationHttpStatusException(
              CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_HAS_OPEN_ENQUIRIES);
        }
      }
    }
    consultantAgency.setDeleteDate(deletedAt);
    consultantAgencyRepository.save(consultantAgency);
  }

  private boolean isTheLastConsultantInAgency(ConsultantAgency consultantAgency) {
    return this.consultantAgencyRepository
        .findByAgencyIdAndDeleteDateIsNull(consultantAgency.getAgencyId())
        .stream()
        .filter(relation -> isNull(relation.getDeleteDate()))
        .allMatch(sameConsultantAgencyRelation(consultantAgency));
  }

  private Predicate<ConsultantAgency> sameConsultantAgencyRelation(
      ConsultantAgency consultantAgency) {
    return relation -> relation.equals(consultantAgency);
  }

  private boolean hasOpenEnquiries(ConsultantAgency consultantAgency) {
    Long agencyId = consultantAgency.getAgencyId();
    return hasSessionWithStatus(agencyId, NEW) || hasSessionWithStatus(agencyId, INITIAL);
  }

  private boolean hasSessionWithStatus(Long agencyId, SessionStatus status) {
    return !this.sessionRepository
        .findByAgencyIdAndStatusAndConsultantIsNull(agencyId, status)
        .isEmpty();
  }
}
