package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.DIRECT_MEDIA;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationDto;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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

    var now = LocalDateTime.now();
    var link =
        AgencyInviteLink.builder()
            .token(generateToken())
            .tenantId(agency.getTenantId())
            .agencyId(agency.getId())
            .consultingTypeId(agency.getConsultingType())
            .topicId(cmd.topicId())
            .linkKind(COUNSELLOR)
            .chatType(medium.name())
            .anonymity(InviteLinkAnonymity.FULL.name())
            .consultantId(owner.getId())
            .name(normalizeName(cmd.name()))
            .temporaryAccount(cmd.temporary() != null ? cmd.temporary() : defaultTemporary(medium))
            .createdByUserId(owner.getId())
            .createdByUsername(owner.getUsername())
            .createDate(now)
            .status(InviteLinkStatus.ACTIVE.name())
            .build();
    return repository.save(link);
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
    if (cmd.topicId() != null) {
      requireTopic(cmd.topicId());
      link.setTopicId(cmd.topicId());
    }
    if (cmd.temporary() != null) {
      link.setTemporaryAccount(cmd.temporary());
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
   * link's medium (FE#1952).
   */
  public void delete(Consultant owner, Long linkId) {
    repository.delete(requireOwn(owner, linkId));
  }

  /**
   * Opens the caller's own conversation with the link owner, or returns the one they already have
   * for this owner and consulting type.
   */
  public StartedConversation startConversation(String token, User user) {
    var link =
        repository
            .findByToken(token)
            .orElseThrow(() -> new NotFoundException("Invite link not found"));
    if (!COUNSELLOR.equals(link.getLinkKind())
        || !InviteLinkChatType.isDirectMedium(link.getChatType())) {
      throw new BadRequestException("Not a counsellor direct link");
    }
    if (InviteLinkStatus.DISABLED.name().equals(link.getStatus())) {
      throw new DirectLinkInactiveException();
    }
    if (!InviteLinkStatus.ACTIVE.name().equals(link.getStatus())
        || (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now()))) {
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
    if (!directInvitePolicy.allows(link.getTenantId(), link.getAgencyId(), medium)) {
      throw new ForbiddenException("Direct invitations are not allowed here");
    }
    DirectLinkOwner.requireStillServing(consultantRepository, link);

    var agency = agencyService.getAgencyWithoutCaching(link.getAgencyId());
    var registration = new NewRegistrationDto();
    registration.setAgencyId(link.getAgencyId());
    registration.setConsultingType(String.valueOf(link.getConsultingTypeId()));
    registration.setMainTopicId(link.getTopicId());
    registration.setConsultantId(link.getConsultantId());
    registration.setPostcode(
        agency != null && agency.getPostcode() != null ? agency.getPostcode() : FALLBACK_POSTCODE);

    var response =
        createNewSessionFacade.initializeNewSession(registration, user, Lists.newArrayList());
    // A conversation without its room is a failed step; the next visit provisions it again.
    if (response.getMatrixRoomId() == null || response.getMatrixRoomId().isBlank()) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "conversation_room_unavailable");
    }
    // FE#1952: the counsellor's list names the link's medium instead of "Mail". First link wins;
    // a conversation that already has an origin keeps it.
    sessionRepository.recordDirectLinkOrigin(response.getSessionId(), medium.name());
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
        knock == null ? null : knock.getStatus() == GroupChatJoinRequest.Status.ADMITTED);
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

  private static String generateToken() {
    byte[] buf = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  public record CreateCommand(
      InviteLinkChatType medium, Long topicId, String name, Boolean temporary, Long agencyId) {}

  public record UpdateCommand(String name, Long topicId, Boolean temporary, Boolean active) {}

  public record StartedConversation(
      Long sessionId,
      String matrixRoomId,
      InviteLinkChatType medium,
      InviteLinkAdmission admission,
      boolean created,
      Long knockId,
      String knockStatus,
      Boolean callAdmitted) {}
}
