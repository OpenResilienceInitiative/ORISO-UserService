package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.TeamSessionRoomAccess;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Teamberatung is read-only for colleagues (FE#2494 *4, decision d): in a team session only the
 * assigned counsellor writes. Every other counsellor in the case room is lowered to the reader
 * power level, the same level a case-handover co-access viewer gets. The advice seeker, the owner
 * and active supervisors keep their level. A takeover (absence cover) makes its counsellor the
 * owner, so it keeps writing; a co-access grant is a reader by design.
 *
 * <p>Applies only to 1:1 cases created as Teamberatung ({@link Session#isTeamCounsellingCase()}),
 * never to group chats. The case keeps the model it was created under: a later switch of the centre
 * changes nothing here, and there is no sweep converting existing cases (Frank, 2026-11-25,
 * replacing decision "1 a" of 2026-11-23). Runs after accepting an enquiry and after a
 * reassignment.
 *
 * <p>Lowers colleagues; the only raise restores an owner that a run racing a reassignment left
 * below a colleague. Every change re-reads the committed owner first, so a repeated run is safe and
 * can also lower an equal-power former owner. Matrix is the only hard boundary for sending: there
 * is no REST send endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamSessionReadOnlyService {

  public static final int READER_POWER_LEVEL = -1;

  private final @NonNull TeamSessionRoomAccess matrix;
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull SessionSupervisorRepository supervisors;
  private final @NonNull SessionRepository sessions;

  /**
   * Enforces the reader level once the accepting transaction has committed. The case was marked
   * read-only in that transaction ({@code SessionOwnershipService}, initial acceptance), so {@code
   * TeamSessionReadOnlyRepairScheduler} repairs a failure.
   */
  public void enforceAfterCommit(Long sessionId) {
    if (sessionId == null) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      enforceSafely(sessionId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            enforceSafely(sessionId);
          }
        });
  }

  /**
   * FE#2494 *4: a reassignment keeps the case as it is. Only a case that is already read-only for
   * colleagues is enforced again for its new owner; a case accepted before read-only existed is not
   * marked and its colleagues keep writing.
   */
  public void reenforceAfterCommitIfMarked(Long sessionId) {
    if (sessionId == null) {
      return;
    }
    Runnable reenforce =
        () -> {
          try {
            sessions
                .findById(sessionId)
                .filter(session -> session.getTeamReadOnlySince() != null)
                .ifPresent(this::enforce);
          } catch (RuntimeException failure) {
            log.warn(
                "Team read-only access for session {} remains pending ({})",
                sessionId,
                failure.getClass().getSimpleName());
          }
        };
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      reenforce.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            reenforce.run();
          }
        });
  }

  /** Never throws; a failure stays for the repair job. */
  public void enforceSafely(Long sessionId) {
    try {
      sessions.findById(sessionId).ifPresent(this::enforce);
    } catch (RuntimeException failure) {
      log.warn(
          "Team read-only access for session {} remains pending ({})",
          sessionId,
          failure.getClass().getSimpleName());
    }
  }

  /**
   * @return true when every colleague in the room is read-only or there is nothing to do; false
   *     when Matrix could not be read or a colleague could not be lowered
   */
  public boolean enforce(Session session) {
    if (session == null || !session.isTeamCounsellingCase()) {
      return true;
    }
    Consultant owner = session.getConsultant();
    String roomId = session.getMatrixRoomId();
    if (owner == null || isBlank(owner.getMatrixUserId()) || isBlank(roomId)) {
      return true;
    }
    var members = matrix.getRoomMembers(roomId);
    if (members.isEmpty()) {
      return false;
    }
    Set<String> colleagueIds = colleagueMatrixIds(session, owner, members.get());
    if (colleagueIds.isEmpty()) {
      return true;
    }
    String ownerToken = matrix.loginAsUserAccessToken(owner.getMatrixUserId());
    if (isBlank(ownerToken)) {
      return false;
    }
    var powerLevels = matrix.getPowerLevels(roomId, ownerToken);
    if (powerLevels.isEmpty()) {
      return false;
    }
    int ownerPower = power(powerLevels.get(), owner.getMatrixUserId());
    // A run that raced a reassignment may have lowered the incoming owner before its commit. The
    // owner must never sit below a colleague: restore it first, with that colleague's token.
    String highest = highestColleague(powerLevels.get(), colleagueIds);
    int highestPower = highest == null ? Integer.MIN_VALUE : power(powerLevels.get(), highest);
    if (highestPower > ownerPower) {
      if (!ownerStillIs(session.getId(), owner.getMatrixUserId(), highest)) {
        log.warn(
            "Team read-only access for session {} stopped: the owner changed", session.getId());
        return false;
      }
      String colleagueToken = matrix.loginAsUserAccessToken(highest);
      if (isBlank(colleagueToken)
          || !matrix.setUserPowerLevel(
              roomId, owner.getMatrixUserId(), highestPower, colleagueToken)) {
        log.warn("Team read-only access for session {}: owner not restored", session.getId());
        return false;
      }
      // A reassignment may have committed between the check and the raise. Checking again after
      // the raise closes that gap: a commit after this check is followed by the reassignment's own
      // after-commit run, which sees the raised former owner and lowers it.
      if (!ownerStillIs(session.getId(), owner.getMatrixUserId(), highest)) {
        boolean undone =
            matrix.setUserPowerLevel(roomId, owner.getMatrixUserId(), ownerPower, ownerToken);
        log.warn(
            "Team read-only access for session {}: the owner changed during the restore, undo {}",
            session.getId(),
            undone ? "done" : "pending");
        return false;
      }
      ownerPower = highestPower;
    }
    boolean complete = true;
    for (String colleagueId : colleagueIds) {
      int colleaguePower = power(powerLevels.get(), colleagueId);
      if (colleaguePower <= READER_POWER_LEVEL) {
        continue;
      }
      // A reassignment may have happened since the room was read: never lower the current owner.
      if (!ownerStillIs(session.getId(), owner.getMatrixUserId(), colleagueId)) {
        log.warn(
            "Team read-only access for session {} stopped: the owner changed", session.getId());
        return false;
      }
      // Matrix forbids lowering an equal peer (a former owner after a reassignment), but lets that
      // peer lower itself; makeUserReadOnly uses the member token only for that case.
      String memberToken =
          colleaguePower >= ownerPower ? matrix.loginAsUserAccessToken(colleagueId) : null;
      if (matrix
          .makeUserReadOnly(
              roomId,
              colleagueId,
              READER_POWER_LEVEL,
              owner.getMatrixUserId(),
              ownerToken,
              memberToken)
          .isEmpty()) {
        complete = false;
      }
    }
    if (!complete) {
      log.warn("Team read-only access for session {} is incomplete", session.getId());
    }
    return complete;
  }

  private Set<String> colleagueMatrixIds(
      Session session, Consultant owner, java.util.List<String> members) {
    Set<String> exempt = new HashSet<>();
    exempt.add(owner.getMatrixUserId());
    if (session.getUser() != null && !isBlank(session.getUser().getMatrixUserId())) {
      exempt.add(session.getUser().getMatrixUserId());
    }
    supervisors.findBySessionIdAndIsActiveTrue(session.getId()).stream()
        .map(SessionSupervisor::getSupervisorConsultant)
        .filter(Objects::nonNull)
        .map(Consultant::getMatrixUserId)
        .filter(Objects::nonNull)
        .forEach(exempt::add);
    var candidates = members.stream().filter(id -> !exempt.contains(id)).toList();
    if (candidates.isEmpty()) {
      return Set.of();
    }
    // Only counsellors: technical accounts in the room keep their level.
    Set<String> colleagues = new LinkedHashSet<>();
    consultants.findAllByMatrixUserIdInAndDeleteDateIsNull(candidates).stream()
        .map(Consultant::getMatrixUserId)
        .filter(id -> !isBlank(id) && !exempt.contains(id))
        .forEach(colleagues::add);
    return colleagues;
  }

  private static String highestColleague(Map<String, Object> powerLevels, Set<String> ids) {
    String highest = null;
    for (String id : ids) {
      if (highest == null || power(powerLevels, id) > power(powerLevels, highest)) {
        highest = id;
      }
    }
    return highest;
  }

  private boolean ownerStillIs(Long sessionId, String ownerMatrixId, String colleagueId) {
    var current = sessions.findById(sessionId).map(Session::getConsultant).orElse(null);
    if (current == null || isBlank(current.getMatrixUserId())) {
      return false;
    }
    return current.getMatrixUserId().equals(ownerMatrixId)
        && !current.getMatrixUserId().equals(colleagueId);
  }

  private static int power(Map<String, Object> powerLevels, String userId) {
    int fallback = number(powerLevels.get("users_default"), 0);
    if (powerLevels.get("users") instanceof Map<?, ?> users) {
      return number(users.get(userId), fallback);
    }
    return fallback;
  }

  private static int number(Object value, int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }
}
