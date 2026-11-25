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
 * <p>Applies only to 1:1 counselling cases ({@link Session#isTeamCounsellingCase()}), never to
 * group chats. It does not depend on the centre's current case sharing model: a colleague in a team
 * case room never writes, also after a switch away from Teamberatung, and an unreadable setting
 * cannot leave anybody writing. Read access, which does depend on the model, is decided in {@code
 * CaseHandoverService}.
 *
 * <p>Only ever lowers a power level, so it is safe to repeat. Matrix is the only hard boundary for
 * sending: there is no REST send endpoint.
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

  /** Enforces once the caller's transaction has committed; the repair job retries a failure. */
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

  /** Never throws; a failure stays for the repair job. */
  public void enforceSafely(Long sessionId) {
    enforceSafely(sessionId, true);
  }

  /**
   * The repair sweep's entry: it lowers only colleagues below the owner. Matrix itself refuses an
   * owner token that lowers an equal peer, so a counsellor promoted by a reassignment the sweep did
   * not see stays writable. Equal-power former owners are lowered by the ownership change's own
   * after-commit run ({@link #enforceAfterCommit}), which sees the committed owner.
   */
  public void repairSafely(Long sessionId) {
    enforceSafely(sessionId, false);
  }

  private void enforceSafely(Long sessionId, boolean lowerEqualPeers) {
    try {
      sessions.findById(sessionId).ifPresent(session -> enforce(session, lowerEqualPeers));
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
    return enforce(session, true);
  }

  boolean enforce(Session session, boolean lowerEqualPeers) {
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
    boolean complete = true;
    for (String colleagueId : colleagueIds) {
      int colleaguePower = power(powerLevels.get(), colleagueId);
      if (colleaguePower <= READER_POWER_LEVEL) {
        continue;
      }
      if (colleaguePower >= ownerPower && !lowerEqualPeers) {
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
