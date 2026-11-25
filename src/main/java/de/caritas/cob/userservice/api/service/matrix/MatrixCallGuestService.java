package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallGuest;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallGuestRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Second call entry path (US#1477): the counsellor's admission is the authorisation. The guest is
 * invited into the call room only; the source conversation is never consulted for the guest and
 * never joined. When the call ends, the guest's seat and any explicit power level are removed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatrixCallGuestService {
  private final MatrixCallBindingRepository bindings;
  private final MatrixCallGuestRepository guests;
  private final MatrixCallConversationResolver conversations;
  private final UserRepository users;
  private final ConsultantRepository consultants;
  private final MatrixSynapseService matrix;
  private final AccountInactivityService accountLifecycle;
  private final SessionCallAuthority authority;
  private final org.springframework.transaction.PlatformTransactionManager transactionManager;

  /**
   * FE#2209 round 8 (F1): takes a revoked guest out of the call's LiveKit room through the
   * MatrixRTC gateway. Absent in slices that do not wire HTTP; then only the call room is left.
   */
  private final org.springframework.beans.factory.ObjectProvider<
          de.caritas.cob.userservice.api.service.matrixrtc.CallParticipantEviction>
      liveKitEviction;

  /** Failed removals after which every further failure is logged at ERROR for alerting. */
  public static final int REVOKE_ALERT_THRESHOLD = 10;

  /** Wait after a failed removal before the next try, so a stuck guest is not retried each sync. */
  @org.springframework.beans.factory.annotation.Value("${matrix.calls.guest-revoke-retry-ms:60000}")
  private long guestRevokeRetryMillis;

  public enum Outcome {
    ADMITTED,
    NOT_FOUND,
    ALREADY_PARTICIPANT,
    MATRIX_UNAVAILABLE
  }

  public record Admission(
      Outcome outcome, String callRoomId, String guestMatrixUserId, Long admittedAt) {
    static Admission of(Outcome outcome) {
      return new Admission(outcome, null, null, null);
    }
  }

  @Transactional
  public Admission admit(
      AuthenticatedUser actor, String sourceRoomId, String callId, String guestUserId) {
    if (!actor.isConsultant()) return Admission.of(Outcome.NOT_FOUND);
    return admitAs(actor.getTenantId(), actor.getUserId(), sourceRoomId, callId, guestUserId);
  }

  /** A running call as seen by one counsellor of its conversation (US#1477, US#1487). */
  public record CounsellorCall(
      Outcome outcome, MatrixCallBinding binding, String counsellorMatrixId, List<String> members) {
    static CounsellorCall of(Outcome outcome) {
      return new CounsellorCall(outcome, null, null, null);
    }
  }

  /**
   * The running call {@code callId} of {@code sourceRoomId}, locked, when {@code consultantId} is a
   * counsellor of that Träger and in the conversation; else {@code NOT_FOUND}. Never tells another
   * Träger's call apart from a missing one.
   */
  @Transactional
  public CounsellorCall counsellorCall(
      Long tenant, String consultantId, String sourceRoomId, String callId) {
    if (consultantId == null || tenant == null || blank(sourceRoomId) || blank(callId))
      return CounsellorCall.of(Outcome.NOT_FOUND);
    var conversation =
        conversations
            .resolve(sourceRoomId)
            .filter(value -> tenant.equals(value.getTenantId()))
            .orElse(null);
    String counsellor =
        consultants
            .findByIdAndDeleteDateIsNull(consultantId)
            .filter(value -> tenant.equals(value.getTenantId()))
            .map(value -> value.getMatrixUserId())
            .filter(value -> !blank(value))
            .orElse(null);
    if (conversation == null || counsellor == null) return CounsellorCall.of(Outcome.NOT_FOUND);
    // CodeRabbit 4585785683: the admitting counsellor (the actor, the link owner or the counsellor
    // who admitted a knock) vouches only while their Matrix id stands for them alone in the Träger,
    // the rule the media gate applies (SoleCounsellor); else a seat would be granted that the gate
    // refuses.
    if (!SoleCounsellor.is(consultants, counsellor, tenant, consultantId))
      return CounsellorCall.of(Outcome.NOT_FOUND);
    // FE#2209 review: a suspended account keeps its rows and Matrix membership; it vouches for
    // nobody.
    if (!isActive(consultantId)) return CounsellorCall.of(Outcome.NOT_FOUND);
    var members = callRoomMembers(sourceRoomId).orElse(null);
    if (members == null) return CounsellorCall.of(Outcome.MATRIX_UNAVAILABLE);
    // Only a counsellor who is in the conversation may open its call to somebody else.
    if (!members.contains(counsellor)) return CounsellorCall.of(Outcome.NOT_FOUND);
    var binding =
        bindings
            .findBySourceRoomIdAndCallId(sourceRoomId, callId)
            .filter(
                value ->
                    Objects.equals(value.getTenantId(), conversation.getTenantId())
                        && Objects.equals(value.getSessionId(), conversation.getSessionId())
                        && Objects.equals(value.getChatId(), conversation.getChatId()))
            // Serialise with the lifecycle, so a call cannot end between the check and the use.
            .flatMap(value -> bindings.lockByMediaRoomId(value.getMediaRoomId()))
            .filter(value -> value.getEndedAt() == null)
            .orElse(null);
    if (binding == null) return CounsellorCall.of(Outcome.NOT_FOUND);
    // CodeRabbit 4585785672: lock the session after the binding (lock order binding, session), as
    // the share-link join does, so the authority check reads a reassignment that committed after
    // this transaction's snapshot; a former counsellor can then no longer seat a guest.
    authority.lockSessionOf(binding);
    if (!authority.grantsGuestAuthority(binding)) return CounsellorCall.of(Outcome.NOT_FOUND);
    // FE#2209 review (Frank): in a 1:1 session call, any counsellor of the Träger who is in the
    // call itself, with a live MatrixRTC membership in its call room, may open it to somebody else.
    // Group chats are out of scope and keep the conversation rule (round 3).
    // CodeRabbit 4585987235: an unobserved call room (observation lost, e.g. a dropped batch) holds
    // device expiries nobody can vouch for; the media gate refuses its seats, so none is granted.
    if (binding.getSessionId() != null
        && (binding.getMediaObservedAt() == null
            || !binding.isInCall(counsellor, System.currentTimeMillis())))
      return CounsellorCall.of(Outcome.NOT_FOUND);
    return new CounsellorCall(Outcome.ADMITTED, binding, counsellor, members);
  }

  /**
   * The active counsellors of {@code tenant} who may vouch for the call: for a 1:1 session call,
   * those in the call (a live MatrixRTC membership in its call room, FE#2209 review); for a group
   * chat, as before, those in the conversation. Empty for another Träger's call.
   */
  @Transactional(readOnly = true)
  public List<String> counsellorsInCall(Long tenant, MatrixCallBinding binding) {
    return counsellorsInCall(tenant, binding, System.currentTimeMillis());
  }

  /** {@link #counsellorsInCall(Long, MatrixCallBinding)} at the instant {@code now}. */
  private List<String> counsellorsInCall(Long tenant, MatrixCallBinding binding, long now) {
    if (tenant == null || binding == null || !tenant.equals(binding.getTenantId()))
      return List.of();
    if (binding.getSessionId() == null) return counsellorsInConversation(tenant, binding);
    // Round 10 security scan: once the call room's observation is lost (the listener left it, or
    // a batch that may hold a departure was dropped), its memberships may be stale: nobody counts.
    if (binding.getMediaObservedAt() == null) return List.of();
    var live =
        binding.getDevices().values().stream()
            .map(device -> device.getSenderMatrixId())
            .distinct()
            .filter(member -> binding.isInCall(member, now))
            // ORISO-Frontend#2356: a counsellor seated as a guest is no counsellor of the call.
            // They
            // neither keep it open for others nor vouch for anybody.
            .filter(member -> !isSeatedGuest(binding, member))
            .toList();
    // Round 10 (F1): one not deleted account of the Träger per id, else nobody (fail closed).
    // CodeRabbit 4585785733: every live sender is resolved in one query, not one per sender.
    return activeCounsellorIds(live, tenant);
  }

  /** The active sole counsellors of {@code tenant} among {@code matrixIds}, in their order. */
  private List<String> activeCounsellorIds(List<String> matrixIds, Long tenant) {
    if (matrixIds.isEmpty()) return List.of();
    return SoleCounsellor.allOf(consultants, matrixIds, tenant).values().stream()
        .map(value -> value.getId())
        .filter(this::isActive)
        .toList();
  }

  /**
   * Seats {@code guestUserId} in the running call on the authority of counsellor {@code
   * consultantId}: the counsellor's own admission (US#1477), or the share link they created
   * (US#1487).
   */
  @Transactional
  public Admission admitAs(
      Long tenant, String consultantId, String sourceRoomId, String callId, String guestUserId) {
    if (blank(guestUserId)) return Admission.of(Outcome.NOT_FOUND);
    return seat(tenant, consultantId, sourceRoomId, callId, () -> identity(guestUserId, tenant));
  }

  /**
   * ORISO-Frontend#2356: seats a counsellor who holds a direct invitation's link in its running
   * call, on the authority of counsellor {@code consultantId} of the call's Träger. Unlike {@link
   * #admitAs}, the guest may work for ANOTHER Träger (product owner, 25 Nov 2026); that is why this
   * is a separate, explicitly named path and {@link #admitAs} keeps its same-Träger rule. The seat
   * is in the call room only and ends with the call, like every guest seat; the conversation is
   * never joined. Run inside {@code TenantContext.supplyAcrossTenants}: the guest's account is not
   * visible in the call's Träger otherwise.
   */
  @Transactional
  public Admission admitCounsellorAs(
      Long tenant,
      String consultantId,
      String sourceRoomId,
      String callId,
      String guestConsultantId) {
    if (blank(guestConsultantId)) return Admission.of(Outcome.NOT_FOUND);
    return seat(
        tenant,
        consultantId,
        sourceRoomId,
        callId,
        () -> counsellorOfAnyTraeger(guestConsultantId));
  }

  private Admission seat(
      Long tenant,
      String consultantId,
      String sourceRoomId,
      String callId,
      java.util.function.Supplier<Optional<Identity>> guestIdentity) {
    var call = counsellorCall(tenant, consultantId, sourceRoomId, callId);
    if (call.outcome() != Outcome.ADMITTED) return Admission.of(call.outcome());
    var binding = call.binding();
    // FE#2209 review: a binding that was never seen ending is not a running call; nobody is seated
    // in a call that is over or never started.
    if (!binding.isLive(System.currentTimeMillis())) return Admission.of(Outcome.NOT_FOUND);
    var members = call.members();
    String admitter = call.counsellorMatrixId();
    var guest = guestIdentity.get().orElse(null);
    if (guest == null || !isActive(guest.id())) return Admission.of(Outcome.NOT_FOUND);
    // A member of the conversation already has the normal join path; never record them as a
    // guest, or the end of the call would take a seat that belongs to the conversation.
    if (members.contains(guest.matrixId())) return Admission.of(Outcome.ALREADY_PARTICIPANT);
    var existing = guests.findByBindingIdAndMatrixUserId(binding.getId(), guest.matrixId());
    if (existing.isPresent() && existing.get().getRevokedAt() != null)
      return Admission.of(Outcome.NOT_FOUND);
    String token = matrix.loginAsUserAccessToken(admitter);
    if (token == null) return Admission.of(Outcome.MATRIX_UNAVAILABLE);
    // Seat first: a write that fails must never leave an invited guest the cleanup cannot find.
    var reserved =
        existing.isEmpty()
            ? guests.saveAndFlush(
                MatrixCallGuest.builder()
                    .bindingId(binding.getId())
                    .matrixUserId(guest.matrixId())
                    .admittedByMatrixId(admitter)
                    .admittedAt(System.currentTimeMillis())
                    .build())
            : null;
    try {
      matrix.inviteUserToRoom(binding.getMediaRoomId(), guest.matrixId(), token);
    } catch (MatrixInviteUserException failed) {
      // The invite may have landed anyway: only a confirmed leave or ban withdraws the seat; an
      // unreadable state keeps it, so the call-end cleanup still retracts whatever is there.
      var membership =
          matrix.getRoomMembership(binding.getMediaRoomId(), guest.matrixId(), token).orElse(null);
      if (!"join".equals(membership) && !"invite".equals(membership)) {
        if (reserved != null && ("leave".equals(membership) || "ban".equals(membership)))
          guests.delete(reserved);
        return Admission.of(Outcome.MATRIX_UNAVAILABLE);
      }
    }
    var row = reserved != null ? reserved : existing.get();
    return new Admission(
        Outcome.ADMITTED, binding.getMediaRoomId(), guest.matrixId(), row.getAdmittedAt());
  }

  /**
   * A counsellor removes a guest from the running call (#1487). The seat is revoked first, so the
   * media gate and {@link #admitAs} refuse the guest at once; the call-room removal is best effort,
   * because without a seat the guest gets no media anyway.
   */
  @Transactional
  public boolean revokeSeat(
      MatrixCallBinding binding, String guestMatrixId, String moderatorMatrixId) {
    if (binding == null || binding.getId() == null || blank(guestMatrixId)) return false;
    var seat =
        guests
            .findByBindingIdAndMatrixUserId(binding.getId(), guestMatrixId)
            .filter(row -> row.getRevokedAt() == null)
            .orElse(null);
    if (seat == null) return false;
    seat.revoke(System.currentTimeMillis());
    guests.save(seat);
    String room = binding.getMediaRoomId();
    String token =
        blank(moderatorMatrixId) ? null : matrix.loginAsUserAccessToken(moderatorMatrixId);
    if (token == null) {
      log.warn(
          "Call guest seat revoked without call-room removal in {} (no moderator token)", room);
      evictFromLiveKit(room, guestMatrixId);
      return true;
    }
    matrix.removeUserFromRoom(room, guestMatrixId, token);
    if (!isGone(room, guestMatrixId, token)) {
      matrix.kickUserFromRoom(room, guestMatrixId, token);
      if (!isGone(room, guestMatrixId, token))
        log.warn("Call guest seat revoked but still in call room {}", room);
    }
    if (!matrix.clearUserPowerLevel(room, guestMatrixId, token))
      log.warn("Call guest power level could not be cleared in call room {}", room);
    evictFromLiveKit(room, guestMatrixId);
    return true;
  }

  /**
   * FE#2209 review: whether this binding backs share links, knocks and seats at all. A 1:1 session
   * call does only while its caller is the session's assigned, active counsellor.
   */
  public boolean grantsGuestAuthority(MatrixCallBinding binding) {
    return authority.grantsGuestAuthority(binding);
  }

  /** Whether {@code matrixId} is the assigned, active counsellor of the binding's session. */
  public boolean isAssignedCounsellor(MatrixCallBinding binding, String matrixId) {
    return binding != null
        && authority.isAssignedCounsellor(binding.getSessionId(), binding.getTenantId(), matrixId);
  }

  /** True when a counsellor removed the person from this call; they cannot come back (#1487). */
  public boolean wasRemoved(MatrixCallBinding binding, String matrixUserId) {
    return binding.getId() != null
        && !blank(matrixUserId)
        && guests
            .findByBindingIdAndMatrixUserId(binding.getId(), matrixUserId)
            .map(row -> row.getRevokedAt() != null)
            .orElse(false);
  }

  /**
   * True while the call runs, still carries guest authority (CodeRabbit review 5889378142: the same
   * authority and liveness as admission) and the person holds an unrevoked guest seat in it.
   */
  public boolean isActiveGuest(MatrixCallBinding binding, String matrixUserId) {
    return binding.getId() != null
        && binding.getEndedAt() == null
        && !blank(matrixUserId)
        && grantsGuestAuthority(binding)
        && binding.isLive(System.currentTimeMillis())
        && guests.existsByBindingIdAndMatrixUserIdAndRevokedAtIsNull(binding.getId(), matrixUserId)
        // FE#2209 round 4: in a 1:1 session call a seat counts only while a counsellor is in the
        // call; a guest's own refreshed membership keeps nothing open after every counsellor left.
        // The media gate applies the same rule (sessionCallAdmitsGuests).
        && opensToGuests(binding);
  }

  /**
   * Security review 5901360577: how long a running 1:1 session call keeps its guest seats while no
   * counsellor holds a live MatrixRTC membership in it. The same grace a call gets before anybody
   * connects (START_GRACE_MILLIS, two minutes), so a counsellor's brief reconnect does not kick the
   * guests. The media gate refuses the seat during that window already; only the seat record and
   * the call-room membership wait for it.
   */
  public static final long COUNSELLOR_ABSENCE_GRACE_MILLIS =
      MatrixSessionCallBindingService.START_GRACE_MILLIS;

  /**
   * The media gate's rule for a seat of binding {@code bindingId} (security review 5901360577): a
   * running 1:1 session call is open to its guests only while it carries authority (its caller is
   * the session's assigned, active counsellor of the Träger) and at least one counsellor is in the
   * call. A group chat's call keeps its rule: an unended binding.
   */
  @Transactional(readOnly = true)
  public boolean sessionCallAdmitsGuests(Long bindingId) {
    if (bindingId == null) return false;
    var binding = bindings.findById(bindingId).orElse(null);
    return binding != null && binding.getEndedAt() == null && opensToGuests(binding);
  }

  /** The 1:1 rule of {@link #sessionCallAdmitsGuests}; always true for a group chat's call. */
  private boolean opensToGuests(MatrixCallBinding binding) {
    return binding.getSessionId() == null
        || (grantsGuestAuthority(binding)
            && !counsellorsInCall(binding.getTenantId(), binding).isEmpty());
  }

  /**
   * Security review 5901360577: a running 1:1 session call takes its guest seats back as soon as
   * the rule stops holding, not only once the binding ends. Lost authority (reassignment,
   * suspension, deactivation, another Träger) revokes at once; no counsellor in the call revokes
   * once no counsellor's membership was live for {@link #COUNSELLOR_ABSENCE_GRACE_MILLIS}. Call
   * with the binding locked (lock order: binding, then session); the revoked rows stay.
   */
  public void revokeOnceTheSessionCallRuleLapsed(MatrixCallBinding binding, long now) {
    if (binding == null
        || binding.getId() == null
        || binding.getEndedAt() != null
        || binding.getSessionId() == null) return;
    boolean lapsed =
        !grantsGuestAuthority(binding)
            // Round 10 security scan: unobserved, presence is unknown; no grace (fail closed).
            || binding.getMediaObservedAt() == null
            // Round 8 (F4): presence and grace read the same instant.
            || (counsellorsInCall(binding.getTenantId(), binding, now).isEmpty()
                && now - lastCounsellorPresence(binding) >= COUNSELLOR_ABSENCE_GRACE_MILLIS);
    if (lapsed) revokeAll(binding);
  }

  /**
   * When the last active counsellor of the Träger was seen in the call: the latest end of such a
   * counsellor's membership (a departure ends it at its own timestamp), else the invite.
   */
  private long lastCounsellorPresence(MatrixCallBinding binding) {
    Long tenant = binding.getTenantId();
    var senders =
        binding.getDevices().values().stream()
            .map(device -> device.getSenderMatrixId())
            .filter(member -> !isSeatedGuest(binding, member))
            .toList();
    var counsellors = SoleCounsellor.allOf(consultants, senders, tenant);
    return binding.getDevices().values().stream()
        .filter(
            device ->
                Optional.ofNullable(counsellors.get(device.getSenderMatrixId()))
                    .filter(value -> isActive(value.getId()))
                    .isPresent())
        .mapToLong(device -> device.getExpiresAt())
        .max()
        .orElse(binding.getInvitedAt());
  }

  /**
   * Takes every outstanding guest seat back once the call has ended. A removal that fails stays
   * outstanding and is retried with the next event the listener sees for the call room.
   */
  public void revokeOutstanding(MatrixCallBinding binding) {
    if (binding == null || binding.getEndedAt() == null || binding.getId() == null) return;
    revokeAll(binding);
  }

  private void revokeAll(MatrixCallBinding binding) {
    // Locking read: a seat committed while this transaction waited on the binding lock is seen.
    List<MatrixCallGuest> outstanding = guests.lockOutstandingByBindingId(binding.getId());
    long retryBefore = System.currentTimeMillis() - guestRevokeRetryMillis;
    for (MatrixCallGuest guest : outstanding) {
      if (guest.getLastRevokeAttemptAt() != null && guest.getLastRevokeAttemptAt() > retryBefore)
        continue;
      revoke(binding, guest);
    }
  }

  /**
   * FE#2209 security review: before the assigned counsellor takes over a binding that lost its
   * authority, every guest seat of it is taken back from the call room the takeover saw, so no seat
   * carries over into the counsellor's room. The Matrix removal runs with no transaction open
   * (#1630). The result is recorded only if, under the binding's row lock, the binding still points
   * at that room without authority (CodeRabbit 4562143165): once a concurrent takeover moved it, a
   * seat found now belongs to the new call and is left alone. Returns whether no seat of the room
   * it saw is outstanding any more.
   */
  public boolean revokeBeforeTakeOver(MatrixCallBinding seen) {
    if (seen == null || seen.getId() == null) return true;
    String room = seen.getMediaRoomId();
    for (MatrixCallGuest guest : guests.findByBindingIdAndRevokedAtIsNull(seen.getId())) {
      Removal removal = remove(seen, guest);
      Boolean recorded =
          new org.springframework.transaction.support.TransactionTemplate(transactionManager)
              .execute(
                  status -> {
                    var current = bindings.lockById(seen.getId()).orElse(null);
                    if (current == null
                        || !Objects.equals(room, current.getMediaRoomId())
                        || authority.grantsGuestAuthority(current)) {
                      return false;
                    }
                    guests
                        .findById(guest.getId())
                        .filter(seat -> seat.getRevokedAt() == null)
                        .ifPresent(
                            seat -> {
                              apply(seat, room, removal);
                              guests.save(seat);
                            });
                    return true;
                  });
      if (!Boolean.TRUE.equals(recorded)) return false;
      // Round 10 (F2): no transaction is open here, so the eviction would run at once; it runs only
      // after the TransactionTemplate committed revoked_at, so the media gate already refuses.
      evictFromLiveKit(room, guest.getMatrixUserId());
    }
    return guests.findByBindingIdAndRevokedAtIsNull(seen.getId()).isEmpty();
  }

  /**
   * Inside the takeover's transaction: whether every seat of the old call was taken back. The
   * revoked rows stay, so "removed, cannot come back" (#1487) still holds for the call id.
   */
  public boolean noSeatOutstanding(Long bindingId) {
    return guests.findByBindingIdAndRevokedAtIsNull(bindingId).isEmpty();
  }

  /** Group chats (out of FE#2209's scope): the Träger's counsellors in the conversation room. */
  private List<String> counsellorsInConversation(Long tenant, MatrixCallBinding binding) {
    var conversation =
        conversations
            .resolve(binding.getSourceRoomId())
            .filter(value -> tenant.equals(value.getTenantId()))
            .orElse(null);
    if (conversation == null) return List.of();
    return activeCounsellorIds(
        callRoomMembers(binding.getSourceRoomId()).orElse(List.of()), tenant);
  }

  private void revoke(MatrixCallBinding binding, MatrixCallGuest guest) {
    apply(guest, binding.getMediaRoomId(), remove(binding, guest));
    evictFromLiveKit(binding.getMediaRoomId(), guest.getMatrixUserId());
  }

  /**
   * FE#2209 round 8 (F1): the call-room kick does not end an open LiveKit connection, and LiveKit
   * refreshes a connected participant's token by itself, so the media gate is never asked again.
   * Also on a deferred removal: the seat is gone for the gate either way. Best effort.
   *
   * <p>Round 9 (F1): only after the revocation commits. The media gate reads revoked_at without a
   * lock, so a client that asks for a new token right after an earlier eviction would still be
   * admitted, and nothing would evict it again. After the commit, the HTTP call also holds neither
   * the binding lock nor the seat locks. A rolled back revocation evicts nobody. Without a
   * transaction it runs at once, so the takeover (round 10, F2) calls it only after its
   * TransactionTemplate committed revoked_at.
   */
  private void evictFromLiveKit(String mediaRoomId, String guestMatrixId) {
    var eviction = liveKitEviction == null ? null : liveKitEviction.getIfAvailable();
    if (eviction == null) return;
    Runnable evict = () -> evictQuietly(eviction, mediaRoomId, guestMatrixId);
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  evict.run();
                }
              });
    } else {
      evict.run();
    }
  }

  private static void evictQuietly(
      de.caritas.cob.userservice.api.service.matrixrtc.CallParticipantEviction eviction,
      String mediaRoomId,
      String guestMatrixId) {
    try {
      eviction.evictFromCallRoom(mediaRoomId, guestMatrixId);
    } catch (RuntimeException failure) {
      // The revocation has committed and stands; no identifiers in the log.
      log.warn("Call guest LiveKit eviction failed ({})", failure.getClass().getSimpleName());
    }
  }

  private enum Removal {
    REMOVED,
    NO_TOKEN,
    STILL_THERE,
    POWER_LEVEL_KEPT
  }

  private void apply(MatrixCallGuest guest, String room, Removal removal) {
    switch (removal) {
      case REMOVED -> guest.revoke(System.currentTimeMillis());
      case NO_TOKEN -> deferRevocation(guest, room, "no moderator token");
      case STILL_THERE -> deferRevocation(guest, room, "membership is not leave or ban");
        // A kept power level would apply again if the account were ever invited back.
      case POWER_LEVEL_KEPT -> deferRevocation(guest, room, "power level not cleared");
    }
  }

  /** Matrix only: takes the guest out of the binding's call room; records nothing. */
  private Removal remove(MatrixCallBinding binding, MatrixCallGuest guest) {
    String room = binding.getMediaRoomId();
    String guestId = guest.getMatrixUserId();
    // The caller first, then the admitting counsellor, whose token may hold the needed rights.
    String token = null;
    boolean gone = false;
    for (String moderator :
        java.util.stream.Stream.of(binding.getCallerMatrixId(), guest.getAdmittedByMatrixId())
            .filter(value -> !blank(value))
            .distinct()
            .toList()) {
      String candidate = matrix.loginAsUserAccessToken(moderator);
      if (candidate == null) continue;
      token = candidate;
      if (remove(room, guestId, candidate)) {
        gone = true;
        break;
      }
    }
    if (token == null) return Removal.NO_TOKEN;
    if (!gone) return Removal.STILL_THERE;
    if (!matrix.clearUserPowerLevel(room, guestId, token)) return Removal.POWER_LEVEL_KEPT;
    return Removal.REMOVED;
  }

  /**
   * The returned flags are not trusted: only the guest's m.room.member state proves removal. A
   * pending invite never shows in the joined list, so that list cannot prove it either.
   */
  private boolean remove(String room, String guestId, String token) {
    matrix.removeUserFromRoom(room, guestId, token);
    if (isGone(room, guestId, token)) return true;
    // Kick also retracts a pending invite.
    matrix.kickUserFromRoom(room, guestId, token);
    return isGone(room, guestId, token);
  }

  private void deferRevocation(MatrixCallGuest guest, String room, String reason) {
    guest.recordFailedRevokeAttempt(System.currentTimeMillis());
    if (guest.getRevokeAttempts() >= REVOKE_ALERT_THRESHOLD) {
      log.error(
          "CALL_GUEST_REVOCATION_STUCK call room {}: removal failed {} times ({})",
          room,
          guest.getRevokeAttempts(),
          reason);
    } else {
      log.warn("Call guest removal deferred for call room {} ({})", room, reason);
    }
  }

  /** Rooms one reconcile sweep passes through the lifecycle at most, per kind. */
  public static final int SWEEP_PAGE_SIZE = 100;

  /** Media rooms of ended calls that still hold an unrevoked guest seat (reconcile sweep). */
  public List<String> endedRoomsWithOutstandingGuests() {
    return guests.findEndedMediaRoomsWithOutstandingGuests(
        System.currentTimeMillis() - guestRevokeRetryMillis,
        org.springframework.data.domain.PageRequest.of(0, SWEEP_PAGE_SIZE));
  }

  /**
   * Running 1:1 session calls that still hold an unrevoked seat and whose rule may have lapsed at
   * {@code now}, least recently swept first (reconcile sweep, review 5901360577, round 8). Calls
   * whose assigned counsellor's authority holds and that have a counsellor in them are left out.
   */
  public List<String> runningSessionRoomsWithOutstandingGuests(long now) {
    return guests.findRunningSessionMediaRoomsWhoseRuleMayHaveLapsed(
        System.currentTimeMillis() - guestRevokeRetryMillis,
        now,
        org.springframework.data.domain.PageRequest.of(0, SWEEP_PAGE_SIZE));
  }

  /** Empty also when the lookup fails transiently, so callers answer "Matrix unavailable". */
  private Optional<List<String>> callRoomMembers(String sourceRoomId) {
    try {
      return matrix.getCallRoomMembers(sourceRoomId);
    } catch (MatrixSynapseService.CallLookupUnavailableException unavailable) {
      return Optional.empty();
    }
  }

  private boolean isGone(String room, String matrixId, String token) {
    return matrix
        .getRoomMembership(room, matrixId, token)
        .map(membership -> "leave".equals(membership) || "ban".equals(membership))
        .orElse(false);
  }

  private boolean isActive(String identityId) {
    return accountLifecycle
        .snapshot(identityId)
        .map(account -> account.status() == AccountInactivityService.Status.ACTIVE)
        .orElse(true);
  }

  private Optional<Identity> identity(String id, Long tenant) {
    return users
        .findByUserIdAndDeleteDateIsNull(id)
        .filter(user -> tenant.equals(user.getTenantId()) && !blank(user.getMatrixUserId()))
        .map(user -> new Identity(user.getUserId(), user.getMatrixUserId()))
        .or(
            () ->
                consultants
                    .findByIdAndDeleteDateIsNull(id)
                    .filter(
                        user -> tenant.equals(user.getTenantId()) && !blank(user.getMatrixUserId()))
                    .map(user -> new Identity(user.getId(), user.getMatrixUserId())));
  }

  /**
   * ORISO-Frontend#2356: a not deleted counsellor of any Träger whose Matrix id stands for them
   * alone in their own Träger (the {@link SoleCounsellor} rule the media gate applies).
   */
  private Optional<Identity> counsellorOfAnyTraeger(String consultantId) {
    return consultants
        .findByIdAndDeleteDateIsNull(consultantId)
        .filter(value -> value.getTenantId() != null && !blank(value.getMatrixUserId()))
        .filter(
            value ->
                SoleCounsellor.is(
                    consultants, value.getMatrixUserId(), value.getTenantId(), value.getId()))
        .map(value -> new Identity(value.getId(), value.getMatrixUserId()));
  }

  /** Whether {@code matrixId} holds an unrevoked guest seat in this call (ORISO-Frontend#2356). */
  public boolean isSeatedGuest(MatrixCallBinding binding, String matrixId) {
    return binding != null
        && binding.getId() != null
        && !blank(matrixId)
        && guests.existsByBindingIdAndMatrixUserIdAndRevokedAtIsNull(binding.getId(), matrixId);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private record Identity(String id, String matrixId) {}
}
