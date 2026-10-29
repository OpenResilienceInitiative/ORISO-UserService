package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.Admission;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.Outcome;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Share link of one running audio or video call (#1487). A counsellor in the call creates it; it
 * carries "Anklopfen" ({@code knock}) and "Temporäres Konto" ({@code temporaryAccount}), both on by
 * default and changeable while the call runs. A guest who opens it either knocks on the knock queue
 * of #1479 or is seated in the call room straight away through the guest path of #1477. The link
 * works only while its call runs: every use checks the call, and a sweep closes links whose call
 * ended without anybody noticing.
 */
@Service
@RequiredArgsConstructor
public class CallShareLinkService {

  static final String CALL = InviteLinkKind.CALL.name();
  private static final int TOKEN_BYTES = 24;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final @NonNull AgencyInviteLinkRepository links;
  private final @NonNull MatrixCallBindingRepository bindings;
  private final @NonNull MatrixCallGuestService callGuests;
  private final @NonNull SessionRepository sessions;
  private final @NonNull DirectCallKnockService knocks;
  private final @NonNull DirectInvitePolicy directInvitePolicy;
  private final @NonNull TemporaryAccountPolicy temporaryAccountPolicy;
  private final @NonNull TenantService tenantService;
  private final @NonNull UserRepository users;

  /** Backstop when the end of a call is never observed: no link outlives this. */
  @Value("${call-share-links.max-lifetime-minutes:480}")
  private long maxLifetimeMinutes = 480;

  /** Creates the share link of the call, or returns the one it already has (settings unchanged). */
  @Transactional
  public ShareLink create(
      Long tenant,
      String consultantId,
      String sourceRoomId,
      String callId,
      Boolean knock,
      Boolean temporaryAccount) {
    var binding = requireCounsellorCall(tenant, consultantId, sourceRoomId, callId);
    var existing = links.findByCallBindingId(binding.getId());
    if (existing.isPresent()) {
      return view(existing.get(), false);
    }
    var medium = mediumOf(binding);
    var session =
        binding.getSessionId() == null
            ? Optional.<Session>empty()
            : sessions.findById(binding.getSessionId());
    // The entry room needs the Beratungsstelle for its consent and legal texts (decision pending,
    // see the PR); policies read it from the call, not from the link.
    Long agencyId = session.map(Session::getAgencyId).orElse(null);
    if (!directInvitePolicy.allows(tenant, agencyId, medium)) {
      throw new ForbiddenException("Direct invitations are not allowed here");
    }
    boolean temporaryAllowed = temporaryAccountPolicy.allows(tenant, agencyId, medium);
    var now = LocalDateTime.now();
    var link =
        AgencyInviteLink.builder()
            .token(generateToken())
            .tenantId(tenant)
            .agencyId(agencyId)
            .consultingTypeId(session.map(Session::getConsultingTypeId).orElse(null))
            .topicId(session.map(Session::getMainTopicId).orElse(null))
            .linkKind(CALL)
            .chatType(medium.name())
            .anonymity(InviteLinkAnonymity.FULL.name())
            .consultantId(consultantId)
            .createdByUserId(consultantId)
            .knock(knock == null || knock)
            .temporaryAccount((temporaryAccount == null || temporaryAccount) && temporaryAllowed)
            .callBindingId(binding.getId())
            .createDate(now)
            .expiresAt(now.plusMinutes(maxLifetimeMinutes))
            .status(InviteLinkStatus.ACTIVE.name())
            .build();
    return view(links.save(link), true);
  }

  /** The share link of a running call, for a counsellor in that call. */
  @Transactional
  public ShareLink current(Long tenant, String consultantId, String sourceRoomId, String callId) {
    var binding = requireCounsellorCall(tenant, consultantId, sourceRoomId, callId);
    var link =
        links
            .findByCallBindingId(binding.getId())
            .orElseThrow(() -> new NotFoundException("No share link for this call"));
    requireUsable(link, binding);
    return view(link, false);
  }

  /**
   * Changes the settings of a running call's link; the change applies to the same link at once.
   * Only a counsellor in that call may change it, whoever created it. Switching "Anklopfen" off
   * lets everybody who is waiting in.
   */
  @Transactional
  public ShareLink update(
      Long tenant, String consultantId, Long linkId, Boolean knock, Boolean temporaryAccount) {
    var link =
        links
            .findByIdForUpdate(linkId)
            .filter(value -> CALL.equals(value.getLinkKind()))
            .filter(value -> tenant != null && tenant.equals(value.getTenantId()))
            .orElseThrow(() -> new NotFoundException("Share link %s not found", linkId));
    var stored = bindingOf(link);
    requireUsable(link, stored);
    // Not in the call: indistinguishable from a link that does not exist (no IDOR).
    var binding =
        requireCounsellorCall(tenant, consultantId, stored.getSourceRoomId(), stored.getCallId());
    if (temporaryAccount != null) {
      link.setTemporaryAccount(
          temporaryAccount
              && temporaryAccountPolicy.allows(tenant, agencyOf(binding), mediumOf(binding)));
    }
    if (knock != null && knock != link.isKnock()) {
      link.setKnock(knock);
      if (knock) {
        // Guests already in the call stay; whoever joins anew knocks again.
        knocks.cancelAdmissionsOnCallLink(link.getId());
      } else {
        knocks.admitWaitingOnCallLink(link.getId(), consultantId);
      }
    }
    link.setUpdateDate(LocalDateTime.now());
    return view(links.save(link), false);
  }

  /**
   * A guest opens the link. With "Anklopfen" on, the guest knocks and waits for the counsellor; an
   * earlier admission on this link holds (let in once). Otherwise, or once admitted, the guest is
   * seated in the call room only, on the authority of the counsellor who admitted them (any
   * counsellor in the call may), else of the link owner, else of any counsellor still in it.
   */
  @Transactional
  public Join join(String token, User guest) {
    var found =
        links
            .findByToken(token)
            .filter(value -> CALL.equals(value.getLinkKind()))
            .orElseThrow(() -> new NotFoundException("Share link not found"));
    // Serialise visits on the link, so parallel visits of one guest insert one knock.
    var link = links.findByIdForUpdate(found.getId()).orElseThrow(CallShareLinkEndedException::new);
    var binding = bindingOf(link);
    requireUsable(link, binding);
    // Fail closed: a person without a Träger never reaches a link of one.
    if (!Objects.equals(guest.getTenantId(), link.getTenantId())) {
      throw new ForbiddenException("Share link belongs to another tenant");
    }
    tenantService.requireOperationalTenant(link.getTenantId());
    var medium = mediumOf(binding);
    if (!directInvitePolicy.allows(link.getTenantId(), agencyOf(binding), medium)) {
      throw new ForbiddenException("Direct invitations are not allowed here");
    }
    // Removed by a counsellor: neither knocking nor joining brings the guest back into this call.
    if (callGuests.wasRemoved(binding, guest.getMatrixUserId())) {
      throw new ForbiddenException("Removed from this call");
    }
    var admitted = knocks.callLinkAdmission(link.getId(), guest).orElse(null);
    if (link.isKnock() && admitted == null) {
      var knock = knocks.knockOnCallLink(link.getId(), guest, medium.name());
      return Join.waiting(medium, knock);
    }
    var seat = seatOnAuthority(link, binding, admitted, guest);
    return switch (seat.outcome()) {
      case ADMITTED ->
          Join.seated(
              medium,
              admitted,
              binding.getSourceRoomId(),
              binding.getCallId(),
              seat.callRoomId(),
              seat.guestMatrixUserId());
      case ALREADY_PARTICIPANT -> throw new AlreadyInConversationException();
      case MATRIX_UNAVAILABLE ->
          throw new ResponseStatusException(
              HttpStatus.BAD_GATEWAY, "call_state_lookup_unavailable");
      case NOT_FOUND -> throw new NotFoundException("Share link not found");
    };
  }

  /**
   * A counsellor in the call removes a guest (#1487): the seat is revoked, every open or granted
   * knock of that guest on the link is declined, and the guest cannot come back for this call.
   */
  @Transactional
  public void removeGuest(Long tenant, String consultantId, Long linkId, String guestMatrixUserId) {
    var link =
        links
            .findByIdForUpdate(linkId)
            .filter(value -> CALL.equals(value.getLinkKind()))
            .filter(value -> tenant != null && tenant.equals(value.getTenantId()))
            .orElseThrow(() -> new NotFoundException("Share link %s not found", linkId));
    var stored = bindingOf(link);
    requireUsable(link, stored);
    var call =
        callGuests.counsellorCall(
            tenant, consultantId, stored.getSourceRoomId(), stored.getCallId());
    if (call.outcome() == Outcome.MATRIX_UNAVAILABLE) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "call_state_lookup_unavailable");
    }
    if (call.outcome() != Outcome.ADMITTED) {
      throw new NotFoundException("Share link %s not found", linkId);
    }
    boolean seated =
        callGuests.revokeSeat(call.binding(), guestMatrixUserId, call.counsellorMatrixId());
    var guest =
        guestMatrixUserId == null
            ? Optional.<User>empty()
            : users.findByMatrixUserIdAndDeleteDateIsNull(guestMatrixUserId);
    guest.ifPresent(
        value -> knocks.declineGuestOnCallLink(link.getId(), value.getUserId(), consultantId));
    if (!seated && guest.isEmpty()) {
      throw new NotFoundException("Guest not found in this call");
    }
  }

  /**
   * Sweep step for one link: closes it when its call is over or it outlived the backstop, and
   * cancels every knock still waiting on it. Returns whether the link was closed.
   */
  @Transactional
  public boolean expireIfOver(Long linkId) {
    var link = links.findByIdForUpdate(linkId).orElse(null);
    if (link == null
        || !CALL.equals(link.getLinkKind())
        || !InviteLinkStatus.ACTIVE.name().equals(link.getStatus())) {
      return false;
    }
    var binding =
        link.getCallBindingId() == null
            ? Optional.<MatrixCallBinding>empty()
            : bindings.findById(link.getCallBindingId());
    if (binding.isPresent()
        && isLive(binding.get(), System.currentTimeMillis())
        && !outlived(link)) {
      return false;
    }
    link.setStatus(InviteLinkStatus.EXPIRED.name());
    link.setUpdateDate(LocalDateTime.now());
    links.save(link);
    knocks.cancelWaitingOnCallLink(link.getId());
    return true;
  }

  /** One page of active share links after {@code afterId}, oldest first. */
  @Transactional(readOnly = true)
  public List<Long> activeLinkIds(long afterId, int pageSize) {
    return links
        .findByLinkKindAndStatusAndIdGreaterThanOrderByIdAsc(
            CALL, InviteLinkStatus.ACTIVE.name(), afterId, PageRequest.of(0, pageSize))
        .stream()
        .map(AgencyInviteLink::getId)
        .toList();
  }

  /**
   * A call runs while its end is not recorded and somebody's call membership is still valid. The
   * second half closes the link even when the hang-up event itself was missed.
   */
  static boolean isLive(MatrixCallBinding binding, long now) {
    if (binding.getEndedAt() != null) {
      return false;
    }
    if (binding.getStartedAt() == null) {
      return binding.getInviteExpiresAt() > now;
    }
    return binding.getDevices().values().stream().anyMatch(device -> device.getExpiresAt() > now);
  }

  /** Whether temporary accounts may be offered; the effective value is the setting AND this. */
  @Transactional(readOnly = true)
  public boolean temporaryAccountAllowed(AgencyInviteLink link) {
    var binding =
        link.getCallBindingId() == null
            ? null
            : bindings.findById(link.getCallBindingId()).orElse(null);
    return temporaryAccountPolicy.allows(
        link.getTenantId(),
        binding == null ? null : agencyOf(binding),
        InviteLinkChatType.valueOf(link.getChatType()));
  }

  private Long agencyOf(MatrixCallBinding binding) {
    return binding.getSessionId() == null
        ? null
        : sessions.findById(binding.getSessionId()).map(Session::getAgencyId).orElse(null);
  }

  /** For the public link context: throws 410 once the call is over. */
  @Transactional(readOnly = true)
  public void requireUsable(AgencyInviteLink link) {
    requireUsable(link, bindingOf(link));
  }

  private void requireUsable(AgencyInviteLink link, MatrixCallBinding binding) {
    if (!InviteLinkStatus.ACTIVE.name().equals(link.getStatus())
        || outlived(link)
        || !isLive(binding, System.currentTimeMillis())) {
      throw new CallShareLinkEndedException();
    }
  }

  private boolean outlived(AgencyInviteLink link) {
    return link.getExpiresAt() != null && !link.getExpiresAt().isAfter(LocalDateTime.now());
  }

  private MatrixCallBinding bindingOf(AgencyInviteLink link) {
    if (link.getCallBindingId() == null) {
      throw new CallShareLinkEndedException();
    }
    return bindings.findById(link.getCallBindingId()).orElseThrow(CallShareLinkEndedException::new);
  }

  /**
   * The admitting counsellor, then the link owner, then any other counsellor still in the
   * conversation vouches: either may have left it while the call runs on.
   */
  private Admission seatOnAuthority(
      AgencyInviteLink link, MatrixCallBinding binding, GroupChatJoinRequest admitted, User guest) {
    var tried = new LinkedHashSet<String>();
    if (admitted != null && admitted.getDecidedBy() != null) {
      tried.add(admitted.getDecidedBy());
    }
    tried.add(link.getConsultantId());
    var seat = new Admission(Outcome.NOT_FOUND, null, null, null);
    for (var counsellor : tried) {
      seat = admitAs(link, binding, counsellor, guest);
      if (seat.outcome() != Outcome.NOT_FOUND) {
        return seat;
      }
    }
    for (var counsellor :
        callGuests.counsellorsInConversation(link.getTenantId(), binding.getSourceRoomId())) {
      if (tried.contains(counsellor)) {
        continue;
      }
      seat = admitAs(link, binding, counsellor, guest);
      if (seat.outcome() != Outcome.NOT_FOUND) {
        return seat;
      }
    }
    return seat;
  }

  private Admission admitAs(
      AgencyInviteLink link, MatrixCallBinding binding, String counsellor, User guest) {
    return callGuests.admitAs(
        link.getTenantId(),
        counsellor,
        binding.getSourceRoomId(),
        binding.getCallId(),
        guest.getUserId());
  }

  private MatrixCallBinding requireCounsellorCall(
      Long tenant, String consultantId, String sourceRoomId, String callId) {
    var call = callGuests.counsellorCall(tenant, consultantId, sourceRoomId, callId);
    if (call.outcome() == Outcome.MATRIX_UNAVAILABLE) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "call_state_lookup_unavailable");
    }
    if (call.outcome() != Outcome.ADMITTED) {
      throw new NotFoundException("Call not found");
    }
    if (!isLive(call.binding(), System.currentTimeMillis())) {
      throw new CallShareLinkEndedException();
    }
    return call.binding();
  }

  private ShareLink view(AgencyInviteLink link, boolean created) {
    boolean allowed = temporaryAccountAllowed(link);
    return new ShareLink(link, link.isTemporaryAccount() && allowed, allowed, created);
  }

  private static InviteLinkChatType mediumOf(MatrixCallBinding binding) {
    return binding.isVideo() ? InviteLinkChatType.VIDEO : InviteLinkChatType.AUDIO;
  }

  private static String generateToken() {
    byte[] buf = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  /**
   * @param temporaryAccount effective: the counsellor's setting, and allowed by platform and Träger
   */
  public record ShareLink(
      AgencyInviteLink link,
      boolean temporaryAccount,
      boolean temporaryAccountAllowed,
      boolean created) {}

  /** What a guest's visit led to; call data only once the guest holds a seat. */
  public record Join(
      InviteLinkChatType medium,
      InviteLinkAdmission admission,
      boolean callAdmitted,
      Long knockId,
      GroupChatJoinRequest.Status knockStatus,
      String sourceRoomId,
      String callId,
      String callRoomId,
      String guestMatrixUserId) {

    static Join waiting(InviteLinkChatType medium, GroupChatJoinRequest knock) {
      return new Join(
          medium,
          InviteLinkAdmission.KNOCK,
          false,
          knock.getId(),
          knock.getStatus(),
          null,
          null,
          null,
          null);
    }

    static Join seated(
        InviteLinkChatType medium,
        GroupChatJoinRequest admission,
        String sourceRoomId,
        String callId,
        String callRoomId,
        String guestMatrixUserId) {
      return new Join(
          medium,
          admission == null ? InviteLinkAdmission.DIRECT : InviteLinkAdmission.KNOCK,
          true,
          admission == null ? null : admission.getId(),
          admission == null ? null : admission.getStatus(),
          sourceRoomId,
          callId,
          callRoomId,
          guestMatrixUserId);
    }
  }

  /** The guest is a member of the call's conversation and joins it the normal way. */
  public static class AlreadyInConversationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AlreadyInConversationException() {
      super("already_conversation_member");
    }
  }
}
