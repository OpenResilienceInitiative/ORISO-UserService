package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.DIRECT_MEDIA;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationDto;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counsellor direct links (#1475, ADR-029): a reusable {@link AgencyInviteLink} of kind {@code
 * COUNSELLOR} whose redeemers each get their own 1:1 conversation with the owner, assigned the same
 * way as the contact link ({@code ?cid=}), so it never enters the enquiry inbox.
 */
@Service
@RequiredArgsConstructor
public class CounsellorDirectLinkService {

  static final int NAME_MAX = 100;
  private static final String FALLBACK_POSTCODE = "00000";
  private static final int TOKEN_BYTES = 24;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String COUNSELLOR = InviteLinkKind.COUNSELLOR.name();

  private final @NonNull AgencyInviteLinkRepository repository;
  private final @NonNull AgencyService agencyService;
  private final @NonNull TopicService topicService;
  private final @NonNull DirectInvitePolicy directInvitePolicy;
  private final @NonNull CreateNewSessionFacade createNewSessionFacade;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull TenantService tenantService;
  private final @NonNull DirectCallKnockService directCallKnockService;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull DirectInvitationService invitations;
  private final @NonNull SessionAppointmentRepository sessionAppointments;

  public List<AgencyInviteLink> listOwn(Consultant owner) {
    return repository.findAllByConsultantIdAndLinkKindAndChatTypeInOrderByCreateDateDesc(
        owner.getId(), COUNSELLOR, DIRECT_MEDIA);
  }

  public AgencyInviteLink create(Consultant owner, CreateCommand cmd) {
    if (cmd == null || cmd.medium() == null) {
      throw new BadRequestException("medium is required");
    }
    if (cmd.topicId() == null) {
      throw new BadRequestException("topicId is required");
    }
    var medium = cmd.medium();
    var agency = resolveOwnAgency(owner, cmd.agencyId());
    requireTopic(cmd.topicId());
    requireAllowed(agency, medium);

    var link =
        newLink(
            owner, agency.getTenantId(), agency.getId(), agency.getConsultingType(), cmd.topicId());
    link.setChatType(medium.name());
    link.setName(normalizeName(cmd.name()));
    link.setScheduledAt(cmd.scheduledAt());
    var temporary = cmd.temporary() != null ? cmd.temporary() : defaultTemporary(medium);
    applyAccess(link, cmd.accessMode(), temporary);
    link.setKnock(cmd.knock() == null || cmd.knock());
    if (cmd.appointmentStart() != null || cmd.appointmentEnd() != null) {
      invitations.attachAppointment(
          link, cmd.appointmentStart(), cmd.appointmentEnd(), cmd.validityAfterEndMinutes());
    } else if (cmd.validityAfterEndMinutes() != null) {
      throw new BadRequestException("validityAfterEndMinutes needs an appointment");
    }
    return repository.save(link);
  }

  /**
   * "Weitere Personen per Link einladen" from a 1:1 (ORISO-Frontend#2252): an invitation bound to
   * the conversation and one of its appointments. Only the conversation's counsellor finds the
   * conversation; anybody else gets 404.
   */
  @Transactional
  public AgencyInviteLink createForSession(
      Consultant owner, Long sessionId, SessionInvitationCommand cmd) {
    if (cmd == null || (cmd.appointmentId() == null) == (cmd.appointmentStart() == null)) {
      throw new BadRequestException("Exactly one of appointmentId and appointment is required");
    }
    var session =
        sessionRepository
            .findById(sessionId)
            .filter(found -> found.getConsultant() != null)
            .filter(found -> Objects.equals(found.getConsultant().getId(), owner.getId()))
            .filter(
                found ->
                    owner.getTenantId() == null
                        || found.getTenantId() == null
                        || Objects.equals(owner.getTenantId(), found.getTenantId()))
            .orElseThrow(() -> new NotFoundException("Session %s not found", sessionId));
    if (session.getStatus() == Session.SessionStatus.DONE
        || session.getStatus() == Session.SessionStatus.IN_ARCHIVE) {
      throw new ConflictException("A finished conversation takes no invitation.");
    }
    var medium = cmd.medium() == null ? InviteLinkChatType.VIDEO : cmd.medium();
    var tenantId = session.getTenantId() != null ? session.getTenantId() : owner.getTenantId();
    if (!directInvitePolicy.allows(tenantId, session.getAgencyId(), medium)) {
      throw new ForbiddenException("Direct invitations are not allowed here");
    }
    var link =
        newLink(
            owner,
            tenantId,
            session.getAgencyId(),
            session.getConsultingTypeId(),
            session.getMainTopicId());
    link.setChatType(medium.name());
    link.setName(normalizeName(cmd.name()));
    applyAccess(
        link, cmd.accessMode() == null ? DirectInvitationAccessMode.BOTH : cmd.accessMode(), true);
    link.setKnock(cmd.knock() == null || cmd.knock());
    link.setJoinsExistingSession(true);
    if (cmd.appointmentId() != null) {
      var appointment =
          sessionAppointments
              .findOfSessionForUpdate(cmd.appointmentId(), session.getId())
              .orElseThrow(() -> new NotFoundException("Session appointment not found."));
      invitations.attachExistingAppointment(link, appointment, cmd.validityAfterEndMinutes());
    } else {
      invitations.attachAppointment(
          link, cmd.appointmentStart(), cmd.appointmentEnd(), cmd.validityAfterEndMinutes());
      var appointment =
          sessionAppointments
              .findById(link.getAppointmentId())
              .orElseThrow(() -> new NotFoundException("Session appointment not found."));
      appointment.setSession(session);
      sessionAppointments.save(appointment);
    }
    return repository.save(link);
  }

  private AgencyInviteLink newLink(
      Consultant owner, Long tenantId, Long agencyId, Integer consultingTypeId, Long topicId) {
    return AgencyInviteLink.builder()
        .token(generateToken())
        .tenantId(tenantId)
        .agencyId(agencyId)
        .consultingTypeId(consultingTypeId)
        .topicId(topicId)
        .linkKind(COUNSELLOR)
        .anonymity(InviteLinkAnonymity.FULL.name())
        .consultantId(owner.getId())
        .createdByUserId(owner.getId())
        .createdByUsername(owner.getUsername())
        .createDate(LocalDateTime.now())
        .status(InviteLinkStatus.ACTIVE.name())
        .build();
  }

  /** The access mode wins over the temporary switch; the switch follows it. */
  private static void applyAccess(
      AgencyInviteLink link, DirectInvitationAccessMode mode, boolean temporary) {
    var effective =
        mode != null
            ? mode
            : temporary
                ? DirectInvitationAccessMode.BOTH
                : DirectInvitationAccessMode.ACCOUNTS_ONLY;
    link.setAccessMode(effective);
    link.setTemporaryAccount(effective.createsTemporaryAccounts());
  }

  /** The medium is fixed after creation; every other field is optional. */
  public AgencyInviteLink update(Consultant owner, Long linkId, UpdateCommand cmd) {
    var link = requireOwn(owner, linkId);
    if (cmd == null) {
      return link;
    }
    if (cmd.name() != null) {
      link.setName(normalizeName(cmd.name()));
    }
    if (cmd.clearScheduledAt() && cmd.scheduledAt() != null) {
      throw new BadRequestException("scheduledAt and clearScheduledAt cannot be combined");
    }
    if (cmd.clearScheduledAt()) {
      link.setScheduledAt(null);
    } else if (cmd.scheduledAt() != null) {
      link.setScheduledAt(cmd.scheduledAt());
    }
    if (cmd.topicId() != null) {
      requireTopic(cmd.topicId());
      link.setTopicId(cmd.topicId());
    }
    if (cmd.accessMode() != null) {
      applyAccess(link, cmd.accessMode(), link.isTemporaryAccount());
    } else if (cmd.temporary() != null) {
      applyAccess(link, null, cmd.temporary());
    }
    if (cmd.knock() != null) {
      link.setKnock(cmd.knock());
    }
    if (cmd.appointmentStart() != null
        || cmd.appointmentEnd() != null
        || cmd.validityAfterEndMinutes() != null) {
      invitations.change(
          link, cmd.appointmentStart(), cmd.appointmentEnd(), cmd.validityAfterEndMinutes());
    }
    if (Boolean.TRUE.equals(cmd.active())
        && !InviteLinkStatus.ACTIVE.name().equals(link.getStatus())) {
      var allowed =
          directInvitePolicy.allows(
              link.getTenantId(),
              link.getAgencyId(),
              InviteLinkChatType.valueOf(link.getChatType()));
      if (!allowed) {
        throw new ForbiddenException("Direct invitations are not allowed here");
      }
      link.setStatus(InviteLinkStatus.ACTIVE.name());
    } else if (Boolean.FALSE.equals(cmd.active())) {
      link.setStatus(InviteLinkStatus.DISABLED.name());
    }
    link.setUpdateDate(LocalDateTime.now());
    return repository.save(link);
  }

  /**
   * Conversations started through the link carry no reference to it and stay; they keep only the
   * link's medium (FE#1952) and its name (FE#2170).
   */
  public void delete(Consultant owner, Long linkId) {
    var link = requireOwn(owner, linkId);
    repository.delete(link);
    // An appointment nobody met at yet belongs to the link alone (ORISO-Frontend#2252).
    invitations
        .appointmentOf(link)
        .filter(appointment -> appointment.getSession() == null)
        .ifPresent(sessionAppointments::delete);
  }

  /**
   * Opens the caller's own conversation with the link owner, or returns the one they already have
   * for this owner and consulting type.
   */
  public StartedConversation startConversation(String token, User user) {
    var link = findForEntry(repository, token);
    if (!COUNSELLOR.equals(link.getLinkKind())
        || !InviteLinkChatType.isDirectMedium(link.getChatType())) {
      throw new BadRequestException("Not a counsellor direct link");
    }
    if (InviteLinkStatus.DISABLED.name().equals(link.getStatus())) {
      throw new DirectLinkInactiveException();
    }
    requireNotExpired(link);
    if (!InviteLinkStatus.ACTIVE.name().equals(link.getStatus())) {
      throw new BadRequestException("Invite link expired");
    }
    // A temporary account exists for the one link it was created through (#1476).
    var temporary = user.getTemporaryAccountSnapshot();
    if (temporary != null && !Objects.equals(temporary.getInviteLinkId(), link.getId())) {
      throw new ForbiddenException("A temporary account only opens its own direct link");
    }
    // Fail closed: a person without a Träger never reaches a link of one.
    if (!Objects.equals(user.getTenantId(), link.getTenantId())) {
      throw new ForbiddenException("Invite link belongs to another tenant");
    }
    tenantService.requireOperationalTenant(link.getTenantId());
    var medium = InviteLinkChatType.valueOf(link.getChatType());
    // A withdrawn "Direkteinladung erlauben" reads as a link that is no longer active (410).
    if (!directInvitePolicy.allows(link.getTenantId(), link.getAgencyId(), medium)) {
      throw new DirectLinkInactiveException();
    }
    DirectLinkOwner.requireStillServing(consultantRepository, link);
    // ORISO-Frontend#2252: who may enter is per invitation.
    var accessMode = link.effectiveAccessMode();
    if (!accessMode.admits(temporary != null)) {
      throw DirectInvitationRefusedException.forAccessMode(accessMode);
    }
    if (link.getAppointmentId() != null) {
      return startAppointmentConversation(link, user, medium);
    }
    var response =
        createNewSessionFacade.initializeNewSession(
            registrationFor(link), user, Lists.newArrayList());
    // A conversation without its room is a failed step; the next visit provisions it again.
    if (response.getMatrixRoomId() == null || response.getMatrixRoomId().isBlank()) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "conversation_room_unavailable");
    }
    // FE#1952: the counsellor's list names the link's medium instead of "Mail". First link wins;
    // a conversation that already has an origin keeps it.
    sessionRepository.recordDirectLinkOrigin(response.getSessionId(), medium.name());
    // ORISO-Frontend#2170: and the link's name, so the call is called that, not "Group call for
    // <room id>". A link without a name leaves the call to be called "Anruf".
    if (link.getName() != null && !link.getName().isBlank()) {
      sessionRepository.recordDirectLinkName(response.getSessionId(), link.getName());
    }
    // Calls never reach the room on their own: every visit knocks (ADR-029 §2).
    var knock =
        medium.admission() == InviteLinkAdmission.KNOCK
            ? directCallKnockService.knock(response.getSessionId(), user, link.getId(), medium)
            : null;
    return new StartedConversation(
        response.getSessionId(),
        response.getMatrixRoomId(),
        medium,
        medium.admission(),
        response.getStatus() != HttpStatus.CONFLICT,
        knock == null ? null : knock.getId(),
        knock == null ? null : knock.getStatus().name(),
        knock == null ? null : knock.getStatus() == GroupChatJoinRequest.Status.ADMITTED,
        false,
        null);
  }

  /**
   * ORISO-Frontend#2252: everybody shares one appointment conversation (or joins the 1:1 the
   * invitation came from). The chat works at once; the call admission starts 15 minutes before the
   * start, by knocking or, with "Anklopfen" off, straight away.
   */
  private StartedConversation startAppointmentConversation(
      AgencyInviteLink link, User user, InviteLinkChatType medium) {
    var opened =
        invitations.openAppointmentConversation(
            link,
            user,
            () -> createNewSessionFacade.initializeAppointmentSession(registrationFor(link), user));
    var session = opened.session();
    if (opened.created() && !link.isJoinsExistingSession()) {
      sessionRepository.recordDirectLinkOrigin(session.getId(), medium.name());
      if (link.getName() != null && !link.getName().isBlank()) {
        sessionRepository.recordDirectLinkName(session.getId(), link.getName());
      }
    }
    var schedule = invitations.scheduleOf(link).orElse(null);
    var callOpensAt = schedule == null ? null : schedule.callOpensAt();
    boolean call = medium != InviteLinkChatType.CHAT;
    var admission = call && link.isKnock() ? InviteLinkAdmission.KNOCK : InviteLinkAdmission.DIRECT;
    GroupChatJoinRequest knock = null;
    if (call && (schedule == null || schedule.callOpen(DirectInvitationService.nowUtc()))) {
      knock =
          link.isKnock()
              ? directCallKnockService.knock(session.getId(), user, link.getId(), medium)
              : directCallKnockService.admitWithoutKnock(
                  session.getId(), user, link.getId(), medium);
    }
    return new StartedConversation(
        session.getId(),
        session.getMatrixRoomId(),
        medium,
        admission,
        opened.created(),
        knock == null ? null : knock.getId(),
        knock == null ? null : knock.getStatus().name(),
        knock == null ? null : knock.getStatus() == GroupChatJoinRequest.Status.ADMITTED,
        true,
        callOpensAt);
  }

  private NewRegistrationDto registrationFor(AgencyInviteLink link) {
    var agency = agencyService.getAgencyWithoutCaching(link.getAgencyId());
    var registration = new NewRegistrationDto();
    registration.setAgencyId(link.getAgencyId());
    registration.setConsultingType(String.valueOf(link.getConsultingTypeId()));
    registration.setMainTopicId(link.getTopicId());
    registration.setConsultantId(link.getConsultantId());
    registration.setPostcode(
        agency != null && agency.getPostcode() != null ? agency.getPostcode() : FALLBACK_POSTCODE);
    return registration;
  }

  /**
   * A token as a person opens it: the current token, or one replaced by "Neuen Link senden", which
   * reads as expired (ORISO-Frontend#2252).
   */
  public static AgencyInviteLink findForEntry(AgencyInviteLinkRepository repository, String token) {
    return repository
        .findByToken(token)
        .orElseGet(
            () -> {
              if (repository.findFirstByPreviousToken(token).isPresent()) {
                throw new DirectLinkExpiredException();
              }
              throw new NotFoundException("Invite link not found");
            });
  }

  /** A counsellor direct link past its validity, or closed by the expiry sweep: 410 expired. */
  public static void requireNotExpired(AgencyInviteLink link) {
    if (!COUNSELLOR.equals(link.getLinkKind())) {
      return;
    }
    if (InviteLinkStatus.EXPIRED.name().equals(link.getStatus())
        || (link.getExpiresAt() != null
            && link.getExpiresAt().isBefore(DirectInvitationService.nowUtc()))) {
      throw new DirectLinkExpiredException();
    }
  }

  public static boolean defaultTemporary(InviteLinkChatType medium) {
    return medium != InviteLinkChatType.CHAT;
  }

  private AgencyInviteLink requireOwn(Consultant owner, Long linkId) {
    return repository
        .findByIdAndConsultantIdAndLinkKindAndChatTypeIn(
            linkId, owner.getId(), COUNSELLOR, DIRECT_MEDIA)
        .orElseThrow(() -> new NotFoundException("Direct link %s not found", linkId));
  }

  private AgencyDTO resolveOwnAgency(Consultant owner, Long requestedAgencyId) {
    Long agencyId = requestedAgencyId;
    if (agencyId == null) {
      var ids =
          owner.getConsultantAgencies() == null
              ? List.<Long>of()
              : owner.getConsultantAgencies().stream()
                  .filter(ca -> ca.getDeleteDate() == null)
                  .map(ConsultantAgency::getAgencyId)
                  .distinct()
                  .toList();
      if (ids.size() != 1) {
        throw new BadRequestException(
            "agencyId is required when the counsellor works for more or fewer than one agency");
      }
      agencyId = ids.getFirst();
    } else if (!DirectLinkOwner.worksFor(owner, agencyId)) {
      throw new ForbiddenException("Counsellor does not work for agency %s".formatted(agencyId));
    }
    var agency = agencyService.getAgencyWithoutCaching(agencyId);
    if (agency == null) {
      throw new NotFoundException("Agency %s not found", agencyId);
    }
    return agency;
  }

  private void requireTopic(Long topicId) {
    if (topicService.getTopicById(topicId) == null) {
      throw new NotFoundException("Topic %s not found", topicId);
    }
  }

  private void requireAllowed(AgencyDTO agency, InviteLinkChatType medium) {
    if (!directInvitePolicy.allows(agency.getTenantId(), agency.getId(), medium)) {
      throw new ForbiddenException("Direct invitations are not allowed here");
    }
  }

  private static String normalizeName(String name) {
    if (name == null) {
      return null;
    }
    var trimmed = name.trim();
    if (trimmed.length() > NAME_MAX) {
      throw new BadRequestException("name cannot exceed " + NAME_MAX + " characters");
    }
    return trimmed.isEmpty() ? null : trimmed;
  }

  static String generateToken() {
    byte[] buf = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  /**
   * {@code scheduledAt} is UTC and informational only (ORISO-Frontend#2142). With an appointment
   * (UTC start and end, ORISO-Frontend#2252) the link becomes a direct invitation that expires
   * {@code validityAfterEndMinutes} after the end.
   */
  public record CreateCommand(
      InviteLinkChatType medium,
      Long topicId,
      String name,
      Boolean temporary,
      Long agencyId,
      LocalDateTime scheduledAt,
      LocalDateTime appointmentStart,
      LocalDateTime appointmentEnd,
      Integer validityAfterEndMinutes,
      DirectInvitationAccessMode accessMode,
      Boolean knock) {

    public CreateCommand(
        InviteLinkChatType medium, Long topicId, String name, Boolean temporary, Long agencyId) {
      this(medium, topicId, name, temporary, agencyId, null);
    }

    public CreateCommand(
        InviteLinkChatType medium,
        Long topicId,
        String name,
        Boolean temporary,
        Long agencyId,
        LocalDateTime scheduledAt) {
      this(medium, topicId, name, temporary, agencyId, scheduledAt, null, null, null, null, null);
    }
  }

  /** An invitation from a 1:1: exactly one of {@code appointmentId} and start/end (UTC). */
  public record SessionInvitationCommand(
      UUID appointmentId,
      LocalDateTime appointmentStart,
      LocalDateTime appointmentEnd,
      InviteLinkChatType medium,
      String name,
      Integer validityAfterEndMinutes,
      DirectInvitationAccessMode accessMode,
      Boolean knock) {}

  /**
   * Absent fields stay unchanged. {@code clearScheduledAt} removes the date, since a missing {@code
   * scheduledAt} means "keep it".
   */
  public record UpdateCommand(
      String name,
      Long topicId,
      Boolean temporary,
      Boolean active,
      LocalDateTime scheduledAt,
      boolean clearScheduledAt,
      LocalDateTime appointmentStart,
      LocalDateTime appointmentEnd,
      Integer validityAfterEndMinutes,
      DirectInvitationAccessMode accessMode,
      Boolean knock) {

    public UpdateCommand(String name, Long topicId, Boolean temporary, Boolean active) {
      this(name, topicId, temporary, active, null, false);
    }

    public UpdateCommand(
        String name,
        Long topicId,
        Boolean temporary,
        Boolean active,
        LocalDateTime scheduledAt,
        boolean clearScheduledAt) {
      this(
          name,
          topicId,
          temporary,
          active,
          scheduledAt,
          clearScheduledAt,
          null,
          null,
          null,
          null,
          null);
    }
  }

  public record StartedConversation(
      Long sessionId,
      String matrixRoomId,
      InviteLinkChatType medium,
      InviteLinkAdmission admission,
      boolean created,
      Long knockId,
      String knockStatus,
      Boolean callAdmitted,
      boolean appointmentConversation,
      LocalDateTime callOpensAt) {}
}
