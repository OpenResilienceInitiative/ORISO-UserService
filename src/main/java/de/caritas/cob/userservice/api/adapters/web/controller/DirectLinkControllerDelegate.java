package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.CallKnockDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CallKnockStatus;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateDirectLinkDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DirectLinkConversationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DirectLinkDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DirectLinkMedium;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateDirectLinkDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
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
  private final @NonNull DirectCallKnockService knockService;

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
                    : CallKnockStatus.valueOf(started.knockStatus()));
    return new ResponseEntity<>(dto, started.created() ? HttpStatus.CREATED : HttpStatus.OK);
  }

  ResponseEntity<List<CallKnockDTO>> getPendingCallKnocks() {
    var consultant = userAccountService.retrieveValidatedConsultant();
    return ResponseEntity.ok(
        knockService.pendingFor(consultant).stream().map(this::toKnockDto).toList());
  }

  ResponseEntity<List<CallKnockDTO>> admitAllCallKnocks() {
    var consultant = userAccountService.retrieveValidatedConsultant();
    return ResponseEntity.ok(
        knockService.admitAll(consultant).stream().map(this::toKnockDto).toList());
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

  ResponseEntity<CallKnockDTO> cancelOwnCallKnock(Long knockId) {
    var user = userAccountService.retrieveValidatedUser();
    return ResponseEntity.ok(toKnockDto(knockService.cancelOwn(knockId, user)));
  }

  private CallKnockDTO toKnockDto(GroupChatJoinRequest knock) {
    return new CallKnockDTO()
        .id(knock.getId())
        .sessionId(knock.getSessionId())
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
