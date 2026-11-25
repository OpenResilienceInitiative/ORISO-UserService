package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.Admission;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.Outcome;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * ORISO-Frontend#2356 (product owner, 25 Nov 2026): a signed-in counsellor who holds the link of a
 * direct invitation joins its <b>call only</b>, with their own account, also from another Träger.
 * The conversation's counsellor lets them in like a guest: with "Anklopfen" on they knock and wait,
 * with it off they are seated at once. A counsellor who is already in the conversation (its
 * counsellor, a supervisor, a member of its room) is sent to the conversation instead.
 *
 * <p>What a joined counsellor gets is a guest seat in the call room of the running call ({@link
 * MatrixCallGuestService#admitCounsellorAs}). It ends with the call. The conversation, its history
 * and its session id stay closed: the session id is returned only to a member of the caller's own
 * Träger. Nothing is created before a call runs, and a link without an appointment has no shared
 * conversation whose call could be joined.
 *
 * <p>The link belongs to the owner's Träger, the caller may belong to another one, so the work runs
 * across Träger on purpose; the caller was authenticated in their own Träger before.
 */
@Service
@RequiredArgsConstructor
public class DirectLinkStaffJoinService {

  private static final String COUNSELLOR = InviteLinkKind.COUNSELLOR.name();

  private final @NonNull AgencyInviteLinkRepository links;
  private final @NonNull DirectInvitationService invitations;
  private final @NonNull SessionRepository sessions;
  private final @NonNull SessionSupervisorRepository supervisors;
  private final @NonNull MatrixCallBindingRepository bindings;
  private final @NonNull MatrixCallGuestService callGuests;
  private final @NonNull DirectCallKnockService knocks;
  private final @NonNull DirectInvitePolicy directInvitePolicy;
  private final @NonNull TenantService tenantService;
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull MatrixSynapseService matrix;
  private final @NonNull PlatformTransactionManager transactionManager;

  public enum StaffOutcome {
    /** Already in the conversation: go there ({@code sessionId}). */
    MEMBER,
    /** Knocked; wait for the conversation's counsellor and ask again. */
    WAITING,
    /** Seated in the running call: join the call room. */
    SEATED,
    /** No call runs (or no counsellor of the Träger is in it); nothing was written. Ask again. */
    CALL_NOT_RUNNING,
    /** The knock the caller waited on was declined or withdrawn. */
    REFUSED,
    /**
     * A link without an appointment: there is no shared conversation whose call could be joined.
     */
    NO_SHARED_CALL
  }

  /** Call data only once the counsellor holds a seat; the session id only for a member. */
  public record StaffJoin(
      StaffOutcome outcome,
      InviteLinkChatType medium,
      Long sessionId,
      Long knockId,
      Status knockStatus,
      String sourceRoomId,
      String callId,
      String callRoomId,
      String guestMatrixUserId) {

    static StaffJoin of(StaffOutcome outcome, InviteLinkChatType medium) {
      return new StaffJoin(outcome, medium, null, null, null, null, null, null, null);
    }

    static StaffJoin knock(
        StaffOutcome outcome, InviteLinkChatType medium, GroupChatJoinRequest knock) {
      return new StaffJoin(
          outcome, medium, null, knock.getId(), knock.getStatus(), null, null, null, null);
    }
  }

  /**
   * One visit of the link by counsellor {@code callerId}. Repeating it is the poll: it returns the
   * open knock, the seat once admitted, or that no call runs yet.
   *
   * @param waitingFor the knock the caller's page waits on. When that knock was withdrawn (the
   *     waiting ran out, "Anklopfen" was switched) the answer is {@link StaffOutcome#REFUSED}. A
   *     decline holds for the rest of the call either way; a new call takes a new knock.
   */
  public StaffJoin join(String token, String callerId, Long waitingFor) {
    return TenantContext.supplyAcrossTenants(
        () ->
            new TransactionTemplate(transactionManager)
                .execute(status -> joinAcrossTraeger(token, callerId, waitingFor)));
  }

  /** The counsellor left the waiting page: their open knocks through this link no longer wait. */
  public void leave(String token, String callerId) {
    TenantContext.supplyAcrossTenants(
        () ->
            links
                .findByToken(token)
                .filter(DirectLinkStaffJoinService::isCallInvitation)
                .map(link -> knocks.cancelWaitingStaffKnocks(link.getId(), callerId))
                .orElse(0));
  }

  private StaffJoin joinAcrossTraeger(String token, String callerId, Long waitingFor) {
    var link = CounsellorDirectLinkService.findForEntry(links, token);
    if (!isCallInvitation(link)) {
      throw new BadRequestException("Not an audio or video direct link");
    }
    if (InviteLinkStatus.DISABLED.name().equals(link.getStatus())) {
      throw new DirectLinkInactiveException();
    }
    CounsellorDirectLinkService.requireNotExpired(link);
    if (!InviteLinkStatus.ACTIVE.name().equals(link.getStatus())) {
      throw new BadRequestException("Invite link expired");
    }
    tenantService.requireOperationalTenant(link.getTenantId());
    var medium = InviteLinkChatType.valueOf(link.getChatType());
    if (!directInvitePolicy.allows(link.getTenantId(), link.getAgencyId(), medium)) {
      throw new DirectLinkInactiveException();
    }
    DirectLinkOwner.requireStillServing(consultants, link);
    var caller =
        consultants
            .findByIdAndDeleteDateIsNull(callerId)
            .filter(found -> found.getTenantId() != null)
            .orElseThrow(() -> new ForbiddenException("Only counsellors join as counsellor"));
    if (link.getAppointmentId() == null) {
      return StaffJoin.of(StaffOutcome.NO_SHARED_CALL, medium);
    }
    var session = invitations.appointmentOf(link).map(SessionAppointment::getSession).orElse(null);
    // Nobody opened the invitation yet, or its conversation belongs elsewhere: no call to join.
    if (session == null
        || blank(session.getMatrixRoomId())
        || !Objects.equals(session.getTenantId(), link.getTenantId())) {
      return StaffJoin.of(StaffOutcome.CALL_NOT_RUNNING, medium);
    }
    if (isInConversation(session, caller)) {
      return member(session, caller, medium);
    }
    var binding = lockRunningCall(session, link);
    if (binding == null) {
      return StaffJoin.of(StaffOutcome.CALL_NOT_RUNNING, medium);
    }
    // Removed by a counsellor: neither knocking nor joining brings them back into this call.
    if (callGuests.wasRemoved(binding, caller.getMatrixUserId())) {
      throw new ForbiddenException("Removed from this call");
    }
    var latest =
        knocks
            .latestStaffKnock(session.getId(), caller.getId(), StaffCallClock.startOf(binding))
            .orElse(null);
    var admitted = latest != null && latest.getStatus() == Status.ADMITTED ? latest : null;
    if (admitted == null && link.isKnock()) {
      if (latest != null && knocks.stillWaitingStaffKnock(latest)) {
        return StaffJoin.knock(StaffOutcome.WAITING, medium, latest);
      }
      // A decline holds for this call, whatever the page sends: no ringing again and again. A
      // knock the counsellor withdrew themself (left the page) opens anew on the next visit.
      if (latest != null
          && (latest.getStatus() == Status.DECLINED
              || (!latest.isPending() && Objects.equals(waitingFor, latest.getId())))) {
        return StaffJoin.knock(StaffOutcome.REFUSED, medium, latest);
      }
      return StaffJoin.knock(
          StaffOutcome.WAITING,
          medium,
          knocks.openStaffKnock(session, caller.getId(), link.getId(), medium));
    }
    if (admitted == null) {
      admitted = knocks.admitStaffWithoutKnock(session, caller.getId(), link.getId(), medium);
    }
    var seat = seatOnAuthority(link, binding, admitted, caller.getId());
    return switch (seat.outcome()) {
      case ADMITTED ->
          new StaffJoin(
              StaffOutcome.SEATED,
              medium,
              null,
              admitted.getId(),
              admitted.getStatus(),
              binding.getSourceRoomId(),
              binding.getCallId(),
              seat.callRoomId(),
              seat.guestMatrixUserId());
        // Joined the conversation's room meanwhile: the normal way in.
      case ALREADY_PARTICIPANT -> member(session, caller, medium);
      case MATRIX_UNAVAILABLE ->
          throw new ResponseStatusException(
              HttpStatus.BAD_GATEWAY, "call_state_lookup_unavailable");
        // No counsellor of the Träger is in the call any more to vouch.
      case NOT_FOUND -> StaffJoin.of(StaffOutcome.CALL_NOT_RUNNING, medium);
    };
  }

  private static boolean isCallInvitation(AgencyInviteLink link) {
    return COUNSELLOR.equals(link.getLinkKind())
        && (InviteLinkChatType.AUDIO.name().equals(link.getChatType())
            || InviteLinkChatType.VIDEO.name().equals(link.getChatType()));
  }

  /** The conversation's counsellor, an active supervisor, or a member of its Matrix room. */
  private boolean isInConversation(Session session, Consultant caller) {
    if (session.getConsultant() != null
        && Objects.equals(session.getConsultant().getId(), caller.getId())) {
      return true;
    }
    if (supervisors
        .findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(session.getId(), caller.getId())
        .isPresent()) {
      return true;
    }
    if (blank(caller.getMatrixUserId())) {
      return false;
    }
    try {
      return matrix
          .getCallRoomMembers(session.getMatrixRoomId())
          .orElseThrow(DirectLinkStaffJoinService::matrixUnavailable)
          .contains(caller.getMatrixUserId());
    } catch (MatrixSynapseService.CallLookupUnavailableException unavailable) {
      throw matrixUnavailable();
    }
  }

  /** Fail closed: a conversation is opened only to a member of its own Träger. */
  private static StaffJoin member(Session session, Consultant caller, InviteLinkChatType medium) {
    if (!Objects.equals(caller.getTenantId(), session.getTenantId())) {
      throw new ForbiddenException("Conversation of another Träger");
    }
    return new StaffJoin(
        StaffOutcome.MEMBER, medium, session.getId(), null, null, null, null, null, null);
  }

  /**
   * The conversation's running call that may take guests, locked in the order every call path uses
   * (binding, then conversation): not ended, live, in its own call room, and placed by the
   * conversation's assigned, active counsellor. Null when there is none.
   */
  private MatrixCallBinding lockRunningCall(Session session, AgencyInviteLink link) {
    long now = System.currentTimeMillis();
    var running =
        bindings.findBySessionIdIn(List.of(session.getId())).stream()
            .filter(binding -> isRunningCallOf(binding, session, link, now))
            .max(Comparator.comparing(MatrixCallBinding::getId))
            .flatMap(binding -> bindings.lockById(binding.getId()))
            .filter(binding -> isRunningCallOf(binding, session, link, now))
            .orElse(null);
    if (running == null) {
      return null;
    }
    sessions.lockFresh(session.getId());
    return callGuests.grantsGuestAuthority(running) ? running : null;
  }

  private static boolean isRunningCallOf(
      MatrixCallBinding binding, Session session, AgencyInviteLink link, long now) {
    var source = binding.getSourceRoomId();
    var media = binding.getMediaRoomId();
    return Objects.equals(binding.getSessionId(), session.getId())
        && Objects.equals(binding.getTenantId(), link.getTenantId())
        && Objects.equals(source, session.getMatrixRoomId())
        && !blank(media)
        && !media.equals(source)
        && binding.isLive(now);
  }

  /**
   * As for a share link's guest: the counsellor who admitted the knock vouches, then the link
   * owner, then any other counsellor of the Träger still in the call. Each must be active and in
   * the call, so seating stops once no such counsellor is left.
   */
  private Admission seatOnAuthority(
      AgencyInviteLink link,
      MatrixCallBinding binding,
      GroupChatJoinRequest admitted,
      String guestConsultantId) {
    var tried = new LinkedHashSet<String>();
    if (admitted.getDecidedBy() != null) {
      tried.add(admitted.getDecidedBy());
    }
    if (link.getConsultantId() != null) {
      tried.add(link.getConsultantId());
    }
    var seat = new Admission(Outcome.NOT_FOUND, null, null, null);
    for (var counsellor : tried) {
      seat = admitAs(link, binding, counsellor, guestConsultantId);
      if (seat.outcome() != Outcome.NOT_FOUND) {
        return seat;
      }
    }
    for (var counsellor : callGuests.counsellorsInCall(link.getTenantId(), binding)) {
      if (tried.contains(counsellor)) {
        continue;
      }
      seat = admitAs(link, binding, counsellor, guestConsultantId);
      if (seat.outcome() != Outcome.NOT_FOUND) {
        return seat;
      }
    }
    return seat;
  }

  private Admission admitAs(
      AgencyInviteLink link, MatrixCallBinding binding, String counsellor, String guest) {
    return callGuests.admitCounsellorAs(
        link.getTenantId(), counsellor, binding.getSourceRoomId(), binding.getCallId(), guest);
  }

  private static ResponseStatusException matrixUnavailable() {
    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "call_state_lookup_unavailable");
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
