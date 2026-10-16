package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.CreateConsultantAgencyDTOInputAdapter;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantTenantChangeAudit;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTenantChangeAuditRepository;
import de.caritas.cob.userservice.api.port.out.IdentityTenantUpdater;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService.RoomRef;
import de.caritas.cob.userservice.api.service.LogService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Moves a counsellor to another Träger (#1419, ORISO-Admin#952).
 *
 * <p>The decision is that cases stay with the old Träger. So the move does <em>not</em> touch a
 * single session, chat room or advice seeker: {@code session.consultant_id} keeps pointing at the
 * counsellor, which is what lets a colleague of the old Träger take the case over through the
 * existing case handover with {@code ASSIGNMENT_ENDED}. What the move changes is the counsellor's
 * own access: the centre assignments, the tenant of the account (database and identity provider)
 * and the chat rooms of the old Träger.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantTenantChangeService {

  /** A case counts as left behind while it is waiting for or in a conversation with the owner. */
  private static final List<SessionStatus> OPEN_CASE_STATES =
      List.of(SessionStatus.NEW, SessionStatus.IN_PROGRESS);

  private static final List<CaseHandoverRequest.Status> GRANTED_STATES =
      List.of(
          CaseHandoverRequest.Status.GRANTED,
          CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT);

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull ConsultantAgencyAdminService consultantAgencyAdminService;
  private final @NonNull ConsultantAgencyRelationCreatorService relationCreatorService;
  private final @NonNull ConsultantTopicAgencyCompatibilityValidator topicAgencyValidator;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull CaseHandoverMatrixRepairService matrixRepairService;
  private final @NonNull IdentityTenantUpdater identityTenantUpdater;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull ConsultantTenantChangeAuditRepository auditRepository;
  private final @NonNull Clock clock;

  @Value("${multitenancy.enabled}")
  private boolean multiTenancyEnabled;

  /** What the move did: ids and counts, never a case. */
  public record Result(
      String consultantId,
      Long previousTenantId,
      Long tenantId,
      List<Long> agencyIds,
      int casesLeftWithPreviousTenant,
      int matrixAccessRevocationsQueued) {}

  /**
   * @param consultantId the counsellor to move
   * @param newTenantId the Träger they move to
   * @param agencies the centres they get there; each must belong to {@code newTenantId}
   * @param topicIds topics they offer at the new centres, or {@code null} for none
   * @param actorId the platform admin who asked, for the audit entry
   * @throws BadRequestException on multi-tenancy off, same Träger or a centre of another Träger
   * @throws NotFoundException if the counsellor does not exist
   * @throws ConflictException if the account also holds an admin identity
   * @throws ServiceUnavailableException if the identity provider could not switch the tenant; the
   *     whole move is rolled back
   */
  @Transactional
  public Result changeTenant(
      String consultantId,
      Long newTenantId,
      List<CreateConsultantAgencyDTO> agencies,
      List<Long> topicIds,
      String actorId) {
    if (!multiTenancyEnabled) {
      throw new BadRequestException("A counsellor can only change the Träger with multi-tenancy");
    }
    var consultant =
        consultantRepository
            .findByIdAndDeleteDateIsNull(consultantId)
            .orElseThrow(
                () -> new NotFoundException("Consultant with id %s not found", consultantId));
    var previousTenantId = consultant.getTenantId();
    if (Objects.equals(previousTenantId, newTenantId)) {
      throw new BadRequestException("The consultant already belongs to this Träger");
    }
    if (adminRepository.existsById(consultantId)) {
      // The identity provider keeps one tenant per account, so the admin identity would move too.
      throw new ConflictException(
          "The account also holds an admin identity and cannot change the Träger as a consultant");
    }

    var newAgencyIds =
        agencies.stream().map(CreateConsultantAgencyDTO::getAgencyId).distinct().toList();
    // Checks that every centre exists, belongs to the new Träger and covers the topics.
    var topicsByAgency =
        topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(
            topicIds, newAgencyIds, newTenantId);

    var oldAgencyIds =
        consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(consultantId).stream()
            .map(ConsultantAgency::getAgencyId)
            .distinct()
            .toList();
    int casesLeft =
        Math.toIntExact(
            sessionRepository.countByConsultantIdAndStatusIn(consultantId, OPEN_CASE_STATES));
    var matrixUserId = consultant.getMatrixUserId();
    var rooms = isNotBlank(matrixUserId) ? roomsOf(consultantId, oldAgencyIds) : List.<RoomRef>of();

    if (isNotBlank(matrixUserId)) {
      // Moving back to a Träger before the rooms of an earlier move were left keeps those rooms.
      matrixRepairService.cancelPendingLeavesInTenant(matrixUserId, newTenantId);
    }

    // The old centres go first: this is where the "last counsellor of an active centre" guard can
    // still stop the whole move, before anything outside the database was touched.
    if (!oldAgencyIds.isEmpty()) {
      consultantAgencyAdminService.markConsultantAgenciesForDeletion(consultantId, oldAgencyIds);
    }
    dropDeletedRelationsFromLoadedConsultant(consultant);

    consultant.replaceTopicsPerAgency(topicsByAgency == null ? java.util.Map.of() : topicsByAgency);
    consultant.setTenantId(newTenantId);
    consultant.setAssignedSupervisorId(null);
    // Set again by the first team centre of the new Träger.
    consultant.setTeamConsultant(false);
    consultant.setUpdateDate(LocalDateTime.now(clock));
    consultantRepository.save(consultant);
    consultantRepository.clearAssignedSupervisor(consultantId);

    createRelations(consultantId, agencies, newAgencyIds);

    int queued =
        rooms.isEmpty() ? 0 : matrixRepairService.enqueueLeaves(matrixUserId, consultantId, rooms);

    switchIdentityTenant(consultantId, previousTenantId, newTenantId);

    auditRepository.save(
        ConsultantTenantChangeAudit.builder()
            .consultantId(consultantId)
            .actorId(actorId)
            .previousTenantId(previousTenantId)
            .newTenantId(newTenantId)
            .previousAgencyIds(csv(oldAgencyIds))
            .newAgencyIds(csv(newAgencyIds))
            .casesLeftWithPreviousTenant(casesLeft)
            .matrixAccessRevocationsQueued(queued)
            .createDate(LocalDateTime.now(clock))
            .build());
    log.info(
        "Consultant {} moved from Träger {} to {} by {}; {} open cases stay behind, {} chat rooms"
            + " queued to be left",
        consultantId,
        previousTenantId,
        newTenantId,
        actorId,
        casesLeft,
        queued);

    return new Result(consultantId, previousTenantId, newTenantId, newAgencyIds, casesLeft, queued);
  }

  private List<RoomRef> roomsOf(String consultantId, List<Long> oldAgencyIds) {
    // IN () is not valid SQL on every dialect: a counsellor without centre has no team rooms.
    Collection<Long> agencyIds = oldAgencyIds.isEmpty() ? List.of(-1L) : oldAgencyIds;
    var rows = sessionRepository.findRoomsReadableBy(consultantId, agencyIds, GRANTED_STATES);
    return rows.stream().map(row -> new RoomRef(row.sessionId(), row.roomId())).toList();
  }

  /**
   * The consultant was loaded together with its centre relations. Those that were just ended must
   * not be seen by the checks that run while the new relations are made.
   */
  private void dropDeletedRelationsFromLoadedConsultant(
      de.caritas.cob.userservice.api.model.Consultant consultant) {
    if (consultant.getConsultantAgencies() != null) {
      consultant.getConsultantAgencies().removeIf(relation -> relation.getDeleteDate() != null);
    }
  }

  private void createRelations(
      String consultantId, List<CreateConsultantAgencyDTO> agencies, List<Long> newAgencyIds) {
    Set<Long> additionalAgencyIds = Set.copyOf(newAgencyIds);
    var inputs =
        agencies.stream()
            .collect(
                Collectors.toMap(
                    CreateConsultantAgencyDTO::getAgencyId,
                    dto -> dto,
                    (first, second) -> first,
                    java.util.LinkedHashMap::new))
            .values()
            .stream()
            .map(
                dto ->
                    new CreateConsultantAgencyDTOInputAdapter(consultantId, dto) {
                      @Override
                      public Set<Long> getAdditionalAgencyIds() {
                        return additionalAgencyIds;
                      }
                    })
            .toList();
    inputs.forEach(relationCreatorService::prepareConsultantAgencyRelation);
    inputs.forEach(
        input ->
            relationCreatorService.completeConsultantAgencyAssigment(input, LogService::logInfo));
  }

  /**
   * Switches the tenant in the identity provider inside the move, so a failure there rolls the
   * database back, and puts the old tenant back if the transaction fails later (e.g. on commit).
   * Ending the login sessions waits for the commit and cannot undo the move.
   */
  private void switchIdentityTenant(String consultantId, Long previousTenantId, Long newTenantId) {
    try {
      identityTenantUpdater.updateTenant(consultantId, newTenantId);
    } catch (RuntimeException failure) {
      log.error(
          "Identity provider refused the tenant change of consultant {} ({})",
          consultantId,
          failure.getClass().getSimpleName());
      throw new ServiceUnavailableException("The identity provider could not change the Träger");
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      endSessionsQuietly(consultantId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            endSessionsQuietly(consultantId);
          }

          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              restoreIdentityTenant(consultantId, previousTenantId);
            }
          }
        });
  }

  private void endSessionsQuietly(String consultantId) {
    try {
      identityTenantUpdater.endSessions(consultantId);
    } catch (RuntimeException failure) {
      // The tenant is switched already: tokens of the old Träger no longer find the consultant.
      log.warn(
          "Login sessions of moved consultant {} could not be ended ({}); they expire with their"
              + " tokens",
          consultantId,
          failure.getClass().getSimpleName());
    }
  }

  private void restoreIdentityTenant(String consultantId, Long previousTenantId) {
    if (previousTenantId == null) {
      log.error(
          "Move of consultant {} was rolled back but the identity provider tenant could not be"
              + " restored: the previous tenant is unknown",
          consultantId);
      return;
    }
    try {
      identityTenantUpdater.updateTenant(consultantId, previousTenantId);
    } catch (RuntimeException failure) {
      log.error(
          "Move of consultant {} was rolled back but the identity provider tenant could not be"
              + " restored to {} ({})",
          consultantId,
          previousTenantId,
          failure.getClass().getSimpleName());
    }
  }

  private static String csv(List<Long> ids) {
    return ids.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
  }
}
