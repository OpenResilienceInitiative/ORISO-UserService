package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteRateLimitedException;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ADR-002 §1 "real members from room creation": joins every counsellor of the enquiry's agency into
 * the conversation room as a <em>silent member</em>, at the moment the room is created.
 *
 * <p>Why membership has to exist this early (FE#811): a counsellor's browser only ever sees rooms
 * its own Matrix client syncs, and {@code /sync} returns nothing for a non-member. Before this
 * service existed the holding room held only the agency service account and the advice seeker, so
 * every counsellor looking at an enquiry got an empty conversation with no error — they could not
 * judge whether the case was theirs to take.
 *
 * <p>Joining <em>before the first message</em> is the load-bearing detail, not an optimisation.
 * Rooms are Megolm-encrypted, and a Megolm session is only shared with the devices present when the
 * message is sent. A counsellor joined afterwards holds no key for anything sent earlier and has to
 * beg other devices for it ({@code matrixRoomHistoryKeyTransfer}), which only works while the
 * asker's browser happens to be online. Joining at creation removes that race entirely.
 *
 * <p>"Silent" is a client-side property: membership grants the technical ability to read, and the
 * confidentiality boundary is the app's own visibility rules plus the reveal gate and audit log
 * described in ADR-002 — not Matrix ACLs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgencySilentMembershipService {

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull SessionRoomGateway sessionRoomGateway;
  private final @NonNull UserHelper userHelper;
  private final @NonNull UsernameTranscoder usernameTranscoder;
  private final @NonNull ConsultantDisplayNameResolver consultantDisplayNameResolver;

  /**
   * Invites and joins every active counsellor of the given agency into the room.
   *
   * <p>Best-effort per counsellor: one counsellor without a resolvable Matrix account must never
   * cost the remaining counsellors their membership, and must never fail the advice seeker's
   * enquiry. Callers treat this as a side effect, not as a precondition.
   *
   * @param agencyId the enquiry's agency
   * @param roomId the Matrix room to join them into
   * @param agencyToken access token of the agency service account that created the room
   * @return number of counsellors that ended up joined
   */
  public int joinAgencyConsultants(Long agencyId, String roomId, String agencyToken) {
    if (agencyId == null || isBlank(roomId) || isBlank(agencyToken)) {
      return 0;
    }

    var consultants =
        consultantRepository.findByConsultantAgenciesAgencyIdAndDeleteDateIsNull(agencyId);
    if (consultants.isEmpty()) {
      log.warn("Agency {} has no consultants to join into room {}", agencyId, roomId);
      return 0;
    }

    int joined = 0;
    for (Consultant consultant : consultants) {
      if (joinConsultantIntoRoom(consultant, roomId, agencyToken)) {
        joined++;
      }
    }

    log.info(
        "Joined {} of {} consultants of agency {} as silent members of room {}",
        joined,
        consultants.size(),
        agencyId,
        roomId);
    return joined;
  }

  /**
   * The registration-time variant of {@link #joinAgencyConsultants}: joins counsellors only while
   * Synapse lets it happen without waiting, and hands everyone else back as {@code deferred}.
   *
   * <p>Why: Synapse paces invites per room ({@code rc_invites.per_room}, by default a burst of 10,
   * then one every ~3 s). A department of 32 counsellors therefore needs well over a minute of
   * invites, and doing that inside {@code POST /users/askers/new} ran past the 60 s ingress timeout
   * on Dev while still losing counsellors whose three short retries ran out. The pass stops at the
   * first rate-limited invite, since that limit is room-wide and every later invite would hit it
   * too, or when {@code budget} is spent. Counsellors joined here still precede the asker, as
   * ADR-002 §1 wants; the rest are joined in the background by {@link
   * AgencyDepartmentMembershipBackfill}.
   *
   * @param agencyId the enquiry's agency
   * @param roomId the Matrix room to join them into
   * @param agencyToken access token of the agency service account that created the room
   * @param budget how long the caller's request may spend on this pass
   * @return who joined, who still has to, and the server's retry delay if it throttled the pass
   */
  public DepartmentFanOut joinAgencyConsultantsWithoutWaiting(
      Long agencyId, String roomId, String agencyToken, Duration budget) {
    if (agencyId == null || isBlank(roomId) || isBlank(agencyToken)) {
      return DepartmentFanOut.NONE;
    }

    var consultants =
        consultantRepository.findByConsultantAgenciesAgencyIdAndDeleteDateIsNull(agencyId);
    if (consultants.isEmpty()) {
      log.warn("Agency {} has no consultants to join into room {}", agencyId, roomId);
      return DepartmentFanOut.NONE;
    }

    long deadline = System.nanoTime() + budget.toNanos();
    int joined = 0;
    long retryAfterMs = 0;
    List<Consultant> deferred = new ArrayList<>();
    for (Consultant consultant : consultants) {
      if (retryAfterMs > 0 || System.nanoTime() - deadline >= 0) {
        deferred.add(consultant);
        continue;
      }
      var attempt = joinIntoRoom(consultant, roomId, agencyToken, false);
      if (attempt.joined()) {
        joined++;
      } else {
        retryAfterMs = attempt.retryAfterMs();
        deferred.add(consultant);
      }
    }

    log.info(
        "Joined {} of {} consultants of agency {} as silent members of room {} in-request; {} deferred to background",
        joined,
        consultants.size(),
        agencyId,
        roomId,
        deferred.size());
    return new DepartmentFanOut(joined, List.copyOf(deferred), retryAfterMs);
  }

  /**
   * One counsellor's join without waiting on Synapse's rate limiter. A throttled invite comes back
   * as {@link JoinAttempt#rateLimited()} with the server's delay, so the caller decides whether to
   * wait.
   */
  public JoinAttempt tryJoinConsultantIntoRoom(
      Consultant consultant, String roomId, String agencyToken) {
    return joinIntoRoom(consultant, roomId, agencyToken, false);
  }

  /**
   * Invites and joins a single counsellor into an already existing room, provisioning their Matrix
   * account if they never had one.
   *
   * <p>Extracted for US#1060: a counsellor added to the agency after an enquiry arrived has to be
   * backfilled into the enquiry rooms that already exist (see {@link
   * AgencyLateJoinerMembershipService}), and that backfill needs exactly the same per-counsellor
   * membership semantics as the at-creation fan-out.
   *
   * @param consultant the counsellor to join
   * @param roomId the Matrix room to join them into
   * @param agencyToken access token of the agency service account that owns the room
   * @return whether the counsellor ended up joined
   */
  public boolean joinConsultantIntoRoom(Consultant consultant, String roomId, String agencyToken) {
    return joinIntoRoom(consultant, roomId, agencyToken, true).joined();
  }

  private JoinAttempt joinIntoRoom(
      Consultant consultant, String roomId, String agencyToken, boolean waitOnRateLimit) {
    try {
      var matrixUserId = ensureMatrixAccount(consultant);
      if (isBlank(matrixUserId)) {
        log.warn(
            "Consultant {} has no Matrix account, cannot join room {}", consultant.getId(), roomId);
        return JoinAttempt.FAILED;
      }

      try {
        if (waitOnRateLimit) {
          sessionRoomGateway.inviteUser(roomId, matrixUserId, agencyToken);
        } else {
          sessionRoomGateway.inviteUserOnce(roomId, matrixUserId, agencyToken);
        }
      } catch (MatrixInviteRateLimitedException rateLimited) {
        // Not invited, so the join below would only answer 403. Report the delay instead.
        return JoinAttempt.rateLimited(rateLimited.getRetryAfterMs());
      } catch (Exception inviteError) {
        // An already-invited or already-joined member answers with an error here. The join below
        // is the actual assertion, so let it decide rather than bailing out on a benign 403.
        log.debug(
            "Invite of consultant {} to room {} did not succeed: {}",
            matrixUserId,
            roomId,
            inviteError.getMessage());
      }

      var consultantToken = sessionRoomGateway.loginAsUser(matrixUserId);
      if (isBlank(consultantToken)) {
        log.warn("Could not mint Matrix token for consultant {}", matrixUserId);
        return JoinAttempt.FAILED;
      }

      return sessionRoomGateway.joinRoom(roomId, consultantToken)
          ? JoinAttempt.JOINED
          : JoinAttempt.FAILED;
    } catch (Exception ex) {
      log.warn(
          "Could not join consultant {} into room {}: {}",
          consultant.getId(),
          roomId,
          ex.getMessage());
      return JoinAttempt.FAILED;
    }
  }

  /**
   * Counsellor Matrix accounts are provisioned lazily elsewhere (on accept, or on direct-session
   * creation), so a counsellor who has never handled a case yet has none. Without one they cannot
   * be a room member and the enquiry stays invisible to them, which is the very bug being fixed —
   * so provision here too. Group-room creators use this before their first room exists.
   */
  /** Administrative invitation only: creating/assigning a self-help group is not participation. */
  public boolean inviteConsultantIntoRoom(Consultant consultant, String roomId, String ownerToken) {
    try {
      var actor = ensureMatrixAccount(consultant);
      if (isBlank(actor)) return false;
      sessionRoomGateway.inviteUser(roomId, actor, ownerToken);
      return true;
    } catch (Exception failure) {
      // Provider bodies, tokens and account data are not diagnostic categories.
      log.warn(
          "Self-help group invitation remains pending: failure={}",
          failure.getClass().getSimpleName());
      return false;
    }
  }

  public String ensureMatrixAccount(Consultant consultant) {
    if (!isBlank(consultant.getMatrixUserId())) {
      return consultant.getMatrixUserId();
    }

    var localpart = usernameTranscoder.decodeUsername(consultant.getUsername());
    var displayName = consultantDisplayNameResolver.resolveMatrixDisplayName(consultant);

    String matrixUserId;
    try {
      matrixUserId =
          sessionRoomGateway.createUser(localpart, userHelper.getRandomPassword(), displayName);
    } catch (Exception ex) {
      // Most commonly the account already exists from an earlier flow — verify by login probe
      // rather than trusting the error text.
      var candidate = sessionRoomGateway.userIdFor(localpart);
      matrixUserId = isBlank(sessionRoomGateway.loginAsUser(candidate)) ? null : candidate;
    }

    if (isBlank(matrixUserId)) {
      return null;
    }

    consultant.setMatrixUserId(matrixUserId);
    consultantRepository.save(consultant);
    log.info("Ensured Matrix account {} for consultant {}", matrixUserId, consultant.getId());
    return matrixUserId;
  }

  /** One counsellor's outcome; {@code retryAfterMs > 0} means Synapse throttled the invite. */
  public record JoinAttempt(boolean joined, long retryAfterMs) {
    static final JoinAttempt JOINED = new JoinAttempt(true, 0);
    static final JoinAttempt FAILED = new JoinAttempt(false, 0);

    static JoinAttempt rateLimited(long retryAfterMs) {
      return new JoinAttempt(false, Math.max(1, retryAfterMs));
    }

    public boolean rateLimited() {
      return retryAfterMs > 0;
    }
  }

  /**
   * Result of the in-request department pass: counsellors still to be joined, and the server's
   * retry delay when a rate limit ended the pass ({@code 0} otherwise).
   */
  public record DepartmentFanOut(int joined, List<Consultant> deferred, long retryAfterMs) {
    static final DepartmentFanOut NONE = new DepartmentFanOut(0, List.of(), 0);
  }
}
