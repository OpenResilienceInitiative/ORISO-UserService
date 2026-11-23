package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestAdmitDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatJoinRequestStatusDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.GroupChatJoinRequestDtoMapper;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.service.chat.GroupChatJoinRequestService;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Knock-to-join endpoints for self-help group Series (ORISO-Frontend#1499). */
@Component
@RequiredArgsConstructor
class GroupChatJoinRequestControllerDelegate {

  private final @NonNull GroupChatJoinRequestService joinRequestService;
  private final @NonNull GroupChatJoinRequestDtoMapper joinRequestDtoMapper;
  private final @NonNull AuthenticatedUser authenticatedUser;

  ResponseEntity<GroupChatJoinRequestStatusDTO> knock(Long seriesId, String inviteToken) {
    var result = joinRequestService.knock(seriesId, inviteToken, authenticatedUser.getUserId());
    return new ResponseEntity<>(
        joinRequestDtoMapper.toStatusDto(result.request()),
        result.created() ? HttpStatus.CREATED : HttpStatus.OK);
  }

  ResponseEntity<GroupChatJoinRequestStatusDTO> getOwn(Long seriesId) {
    return joinRequestService
        .findOwn(seriesId, authenticatedUser.getUserId())
        .map(joinRequestDtoMapper::toStatusDto)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  ResponseEntity<Void> cancelOwn(Long seriesId) {
    joinRequestService.cancelOwn(seriesId, authenticatedUser.getUserId());
    return ResponseEntity.noContent().build();
  }

  ResponseEntity<List<GroupChatJoinRequestDTO>> getPending() {
    return ResponseEntity.ok(
        joinRequestDtoMapper.pendingRequestsFor(authenticatedUser.getUserId()));
  }

  ResponseEntity<Void> admit(Long seriesId, Long requestId, GroupChatJoinRequestAdmitDTO body) {
    var role =
        body == null || body.getRole() == null
            ? ParticipantRole.PARTICIPANT
            : ParticipantRole.valueOf(body.getRole().getValue());
    joinRequestService.admit(seriesId, requestId, authenticatedUser.getUserId(), role);
    return ResponseEntity.noContent().build();
  }

  ResponseEntity<Void> decline(Long seriesId, Long requestId) {
    joinRequestService.decline(seriesId, requestId, authenticatedUser.getUserId());
    return ResponseEntity.noContent().build();
  }
}
