package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.CallKnockDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CallKnockStatus;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateDirectLinkDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DirectLinkConversationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DirectLinkDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DirectLinkMedium;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateDirectLinkDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CounsellorDirectLinkService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CounsellorDirectLinkService.CreateCommand;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CounsellorDirectLinkService.UpdateCommand;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectCallKnockService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkStatus;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Serves the direct-link operations of {@code UsersApi} (#1475). Like {@link
 * IdentitySuggestionControllerDelegate}, it is called from {@link UserController} because the
 * generated interface owns the paths.
 */
@Service
@RequiredArgsConstructor
public class DirectLinkControllerDelegate {

  private final @NonNull CounsellorDirectLinkService service;
  private final @NonNull UserAccountService userAccountService;
  private final @NonNull TopicService topicService;
  private static final UsernameTranscoder USERNAMES = new UsernameTranscoder();

  private final @NonNull DirectCallKnockService knockService;
  private final @NonNull UserRepository userRepository;
  private final @NonNull de.caritas.cob.userservice.api.service.temporaryaccount
          .TemporaryAccountService
      temporaryAccountService;

  public ResponseEntity<de.caritas.cob.userservice.api.adapters.web.dto.TemporaryAccountDTO>
      getOwnTemporaryAccount() {
    return ResponseEntity.ok(
        temporaryAccountService.own(userAccountService.retrieveValidatedUser()));
  }

  public ResponseEntity<de.caritas.cob.userservice.api.adapters.web.dto.TemporaryAccountDTO>
      decideTemporaryAccountMessageRetention(
          de.caritas.cob.userservice.api.adapters.web.dto.TemporaryAccountRetentionDecisionDTO
              decision) {
    if (decision == null || decision.getKeepMessages() == null) {
      throw new de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException(
          "keepMessages is required");
    }
    return ResponseEntity.ok(
        temporaryAccountService.decide(
            userAccountService.retrieveValidatedUser(), decision.getKeepMessages()));
  }

  @Value("${app.base.url}")
  private String appBaseUrl;

  ResponseEntity<List<DirectLinkDTO>> getOwnDirectLinks() {
    var owner = userAccountService.retrieveValidatedConsultant();
    var topics = topicService.getAllTopicsMap();
    return ResponseEntity.ok(service.listOwn(owner).stream().map(l -> toDto(l, topics)).toList());
  }

  ResponseEntity<DirectLinkDTO> createDirectLink(CreateDirectLinkDTO body) {
    if (body == null) {
      throw new BadRequestException("Request body is required");
    }
    var owner = userAccountService.retrieveValidatedConsultant();
    var link =
        service.create(
            owner,
            new CreateCommand(
                body.getMedium() == null
                    ? null
                    : InviteLinkChatType.valueOf(body.getMedium().name()),
                body.getTopicId(),
                body.getName(),
                body.getTemporary(),
                body.getAgencyId()));
    return new ResponseEntity<>(toDto(link, topicService.getAllTopicsMap()), HttpStatus.CREATED);
  }

  ResponseEntity<DirectLinkDTO> updateDirectLink(Long linkId, UpdateDirectLinkDTO body) {
    var owner = userAccountService.retrieveValidatedConsultant();
    var cmd =
        body == null
            ? null
            : new UpdateCommand(
                body.getName(), body.getTopicId(), body.getTemporary(), body.getActive());
    var link = service.update(owner, linkId, cmd);
    return ResponseEntity.ok(toDto(link, topicService.getAllTopicsMap()));
  }

  ResponseEntity<Void> deleteDirectLink(Long linkId) {
    service.delete(userAccountService.retrieveValidatedConsultant(), linkId);
    return ResponseEntity.noContent().build();
  }

  ResponseEntity<DirectLinkConversationDTO> startDirectLinkConversation(String token) {
    var started = service.startConversation(token, userAccountService.retrieveValidatedUser());
    var dto =
        new DirectLinkConversationDTO()
            .sessionId(started.sessionId())
            .matrixRoomId(started.matrixRoomId())
            .medium(DirectLinkMedium.valueOf(started.medium().name()))
            .admission(DirectLinkConversationDTO.AdmissionEnum.valueOf(started.admission().name()))
            .created(started.created())
            .knockId(started.knockId())
            .knockStatus(
                started.knockStatus() == null
                    ? null
                    : CallKnockStatus.valueOf(started.knockStatus()))
            .callAdmitted(started.callAdmitted());
    return new ResponseEntity<>(dto, started.created() ? HttpStatus.CREATED : HttpStatus.OK);
  }

  ResponseEntity<List<CallKnockDTO>> getPendingCallKnocks(Long sessionId, String matrixRoomId) {
    var consultant = userAccountService.retrieveValidatedConsultant();
    return ResponseEntity.ok(
        toKnockDtos(knockService.pendingFor(consultant, sessionId, matrixRoomId)));
  }

  ResponseEntity<List<CallKnockDTO>> admitAllCallKnocks(Long sessionId, String matrixRoomId) {
    var consultant = userAccountService.retrieveValidatedConsultant();
    return ResponseEntity.ok(
        toKnockDtos(knockService.admitAll(consultant, sessionId, matrixRoomId)));
  }

  ResponseEntity<CallKnockDTO> admitCallKnock(Long knockId) {
    var consultant = userAccountService.retrieveValidatedConsultant();
    return ResponseEntity.ok(toKnockDto(knockService.admit(knockId, consultant)));
  }

  ResponseEntity<CallKnockDTO> declineCallKnock(Long knockId) {
    var consultant = userAccountService.retrieveValidatedConsultant();
    return ResponseEntity.ok(toKnockDto(knockService.decline(knockId, consultant)));
  }

  ResponseEntity<CallKnockDTO> getOwnCallKnock(Long knockId) {
    var user = userAccountService.retrieveValidatedUser();
    return ResponseEntity.ok(toKnockDto(knockService.getOwn(knockId, user)));
  }

  ResponseEntity<CallKnockDTO> getOwnCallKnockForSession(Long sessionId) {
    var user = userAccountService.retrieveValidatedUser();
    return ResponseEntity.ok(toKnockDto(knockService.ownFor(sessionId, user)));
  }

  ResponseEntity<CallKnockDTO> cancelOwnCallKnock(Long knockId) {
    var user = userAccountService.retrieveValidatedUser();
    return ResponseEntity.ok(toKnockDto(knockService.cancelOwn(knockId, user)));
  }

  /** One user lookup for the whole list. */
  private List<CallKnockDTO> toKnockDtos(List<GroupChatJoinRequest> knocks) {
    var ids = knocks.stream().map(GroupChatJoinRequest::getUserId).distinct().toList();
    var names = new HashMap<String, String>();
    userRepository.findAllById(ids).forEach(u -> names.put(u.getUserId(), displayName(u)));
    return knocks.stream().map(k -> toKnockDto(k, names.get(k.getUserId()))).toList();
  }

  private CallKnockDTO toKnockDto(GroupChatJoinRequest knock) {
    var name =
        knock.getUserId() == null
            ? null
            : userRepository.findById(knock.getUserId()).map(this::displayName).orElse(null);
    return toKnockDto(knock, name);
  }

  /** The pseudonym the person registered with; never the e-mail address. */
  private String displayName(User user) {
    return user.getUsername() == null ? null : USERNAMES.decodeUsername(user.getUsername());
  }

  private CallKnockDTO toKnockDto(GroupChatJoinRequest knock, String displayName) {
    return new CallKnockDTO()
        .displayName(displayName)
        .id(knock.getId())
        // A call-link guest must never learn the conversation of the call (#1487).
        .sessionId(
            knock.getVia() == GroupChatJoinRequest.Via.CALL_LINK ? null : knock.getSessionId())
        .callShareLinkId(
            knock.getVia() == GroupChatJoinRequest.Via.CALL_LINK ? knock.getInviteLinkId() : null)
        .status(CallKnockStatus.valueOf(knock.getStatus().name()))
        .medium(DirectLinkMedium.valueOf(knock.getMedium()))
        .requestedAt(knock.getRequestedAt().atOffset(ZoneOffset.UTC))
        .decidedAt(
            knock.getDecidedAt() == null ? null : knock.getDecidedAt().atOffset(ZoneOffset.UTC));
  }

  DirectLinkDTO toDto(AgencyInviteLink link, Map<Long, TopicDTO> topics) {
    var medium = InviteLinkChatType.valueOf(link.getChatType());
    var topic = link.getTopicId() == null ? null : topics.get(link.getTopicId());
    return new DirectLinkDTO()
        .id(link.getId())
        .token(link.getToken())
        .url(entryUrl(link.getToken()))
        .name(link.getName())
        .topicId(link.getTopicId())
        .topicName(topic == null ? null : topic.getName())
        .agencyId(link.getAgencyId())
        .medium(DirectLinkMedium.valueOf(medium.name()))
        .temporary(link.isTemporaryAccount())
        .active(InviteLinkStatus.ACTIVE.name().equals(link.getStatus()))
        .admission(DirectLinkDTO.AdmissionEnum.valueOf(medium.admission().name()))
        .createDate(
            link.getCreateDate() == null ? null : link.getCreateDate().atOffset(ZoneOffset.UTC));
  }

  /** Same entry route as the admin invite links: the frontend's /invite/:token. */
  private String entryUrl(String token) {
    var base = appBaseUrl == null ? "" : appBaseUrl.replaceAll("/+$", "");
    return base + "/invite/" + token;
  }
}
