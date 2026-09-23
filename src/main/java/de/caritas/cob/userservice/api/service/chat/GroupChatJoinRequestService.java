package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.ChatConverter;
import de.caritas.cob.userservice.api.helper.CustomLocalDateTime;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knock-to-join for self-help groups: a consultant who holds the invite link asks to join, and a
 * Series Owner or Co-Moderator admits or declines. A pending request grants no access at all.
 *
 * <p>Transitions are logged with ids only (no names, no group titles).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChatJoinRequestService {

  private final GroupChatJoinRequestRepository joinRequestRepository;
  private final GroupChatParticipantRepository participantRepository;
  private final ChatRepository chatRepository;
  private final ConsultantRepository consultantRepository;
  private final GroupChatPermissionService groupChatPermissionService;
  private final GroupChatConsultantAccess groupChatConsultantAccess;
  private final GroupChatMembershipService membershipService;

  /** Result of a knock: the request, and whether this call created it. */
  public record KnockResult(GroupChatJoinRequest request, boolean created) {}

  /** A pending request together with the caller's own moderating role in its Series. */
  public record PendingForModerator(
      GroupChatJoinRequest request, Chat series, ParticipantRole viewerRole) {}

  /**
   * Knock on a Series. Check order: unknown Series or wrong invite token → 403 (indistinguishable,
   * so the id reveals nothing); not a self-help group → 400; caller already has access → 409.
   */
  @Transactional
  public KnockResult knock(Long seriesId, String inviteToken, String consultantId) {
    var series =
        chatRepository
            .findById(seriesId)
            .filter(chat -> GroupChatInviteTokens.matches(chat.getInviteToken(), inviteToken))
            .orElseThrow(() -> new ForbiddenException("The invite link is not valid"));
    requireSelfHelp(series);
    var consultant = requireConsultant(consultantId);
    if (hasAccess(series, consultant)) {
      throw new ConflictException("Consultant already has access to this Series");
    }

    var pending =
        joinRequestRepository.findFirstBySeriesIdAndConsultantIdAndStatusOrderByIdDesc(
            seriesId, consultantId, Status.PENDING);
    if (pending.isPresent()) {
      return new KnockResult(pending.get(), false);
    }

    var request =
        joinRequestRepository.save(
            GroupChatJoinRequest.builder()
                .seriesId(seriesId)
                .consultantId(consultantId)
                .status(Status.PENDING)
                .requestedAt(CustomLocalDateTime.nowInUtc())
                .build());
    logTransition(request, consultantId, null);
    return new KnockResult(request, true);
  }

  public Optional<GroupChatJoinRequest> findOwn(Long seriesId, String consultantId) {
    return joinRequestRepository.findFirstBySeriesIdAndConsultantIdOrderByIdDesc(
        seriesId, consultantId);
  }

  @Transactional
  public void cancelOwn(Long seriesId, String consultantId) {
    joinRequestRepository
        .findFirstBySeriesIdAndConsultantIdAndStatusOrderByIdDesc(
            seriesId, consultantId, Status.PENDING)
        .ifPresent(request -> decide(request, Status.CANCELLED, consultantId, null));
  }

  /** Pending requests of every Series in which the caller is Owner or Co-Moderator. */
  public List<PendingForModerator> findPendingForModerator(String consultantId) {
    Map<Long, ParticipantRole> moderatedSeries =
        participantRepository.findByConsultantId(consultantId).stream()
            .filter(participation -> participation.getSeriesId() != null)
            .filter(participation -> isModeratorRole(participation.getRole()))
            .collect(
                Collectors.toMap(
                    GroupChatParticipant::getSeriesId,
                    GroupChatParticipant::getRole,
                    (left, right) -> left == ParticipantRole.OWNER ? left : right));
    if (moderatedSeries.isEmpty()) {
      return List.of();
    }

    var pending =
        joinRequestRepository.findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
            moderatedSeries.keySet(), Status.PENDING);
    var seriesById =
        StreamSupport.stream(
                chatRepository
                    .findAllById(
                        pending.stream().map(GroupChatJoinRequest::getSeriesId).distinct().toList())
                    .spliterator(),
                false)
            .collect(Collectors.toMap(Chat::getId, chat -> chat));
    return pending.stream()
        .filter(request -> seriesById.containsKey(request.getSeriesId()))
        .filter(request -> isSelfHelp(seriesById.get(request.getSeriesId())))
        .map(
            request ->
                new PendingForModerator(
                    request,
                    seriesById.get(request.getSeriesId()),
                    moderatedSeries.get(request.getSeriesId())))
        .toList();
  }

  @Transactional
  public void admit(Long seriesId, Long requestId, String actorId, ParticipantRole role) {
    var admittedRole = role == null ? ParticipantRole.PARTICIPANT : role;
    if (admittedRole == ParticipantRole.OWNER) {
      throw new BadRequestException("A join request can only be admitted as Participant");
    }
    var series = requireSelfHelpSeries(seriesId);
    var actor = requireConsultant(actorId);
    var participants = participantRepository.findBySeriesIdForUpdate(seriesId);
    groupChatPermissionService.requireCanModerate(series, actor);
    var request = requirePendingRequest(seriesId, requestId);
    var requester = requireConsultant(request.getConsultantId());

    if (admittedRole == ParticipantRole.CO_MODERATOR) {
      if (!isOwner(series, participants, actorId)) {
        throw new ForbiddenException("Only a Series Owner can admit a Co-Moderator");
      }
      if (!Objects.equals(series.getChatOwner().getTenantId(), requester.getTenantId())) {
        throw new BadRequestException("Consultant does not belong to the chat owner's tenant");
      }
    }

    var alreadyParticipant =
        participants.stream()
            .anyMatch(participant -> requester.getId().equals(participant.getConsultantId()));
    if (!alreadyParticipant) {
      var sessionId =
          participants.stream()
              .map(GroupChatParticipant::getChatId)
              .filter(Objects::nonNull)
              .findFirst()
              .orElseThrow(
                  () ->
                      new ConflictException(
                          "Chat Series has no participations and cannot admit members"));
      if (!membershipService.addMemberToRoom(series, requester.getMatrixUserId())) {
        throw new InternalServerErrorException(
            "Join request " + requestId + " could not be admitted to the Matrix room");
      }
      participantRepository.save(
          GroupChatParticipant.builder()
              .chatId(sessionId)
              .seriesId(seriesId)
              .consultantId(requester.getId())
              .role(admittedRole)
              .build());
    }

    decide(request, Status.ADMITTED, actorId, admittedRole);
  }

  @Transactional
  public void decline(Long seriesId, Long requestId, String actorId) {
    var series = requireSelfHelpSeries(seriesId);
    var actor = requireConsultant(actorId);
    groupChatPermissionService.requireCanModerate(series, actor);
    var request = requirePendingRequest(seriesId, requestId);
    decide(request, Status.DECLINED, actorId, null);
  }

  private void decide(
      GroupChatJoinRequest request, Status to, String actorId, ParticipantRole admittedRole) {
    var from = request.getStatus();
    request.setStatus(to);
    request.setDecidedAt(CustomLocalDateTime.nowInUtc());
    request.setDecidedBy(actorId);
    request.setAdmittedRole(admittedRole);
    joinRequestRepository.save(request);
    logTransition(request, actorId, from);
  }

  /** Same rule as reading the group (#1244), so a 409 here always means "you can open it". */
  private boolean hasAccess(Chat series, Consultant consultant) {
    return consultant.getId().equals(series.getChatOwner().getId())
        || groupChatConsultantAccess.mayAccess(series, consultant);
  }

  private boolean isOwner(
      Chat series, List<GroupChatParticipant> participants, String consultantId) {
    if (participants.isEmpty()) {
      return consultantId.equals(series.getChatOwner().getId());
    }
    return participants.stream()
        .anyMatch(
            participant ->
                consultantId.equals(participant.getConsultantId())
                    && participant.getRole() == ParticipantRole.OWNER);
  }

  private static boolean isModeratorRole(ParticipantRole role) {
    return role == ParticipantRole.OWNER || role == ParticipantRole.CO_MODERATOR;
  }

  private GroupChatJoinRequest requirePendingRequest(Long seriesId, Long requestId) {
    var request =
        joinRequestRepository
            .findById(requestId)
            .filter(candidate -> seriesId.equals(candidate.getSeriesId()))
            .orElseThrow(() -> new NotFoundException("Join request not found"));
    if (!request.isPending()) {
      throw new ConflictException("Join request is no longer pending");
    }
    return request;
  }

  private Chat requireSelfHelpSeries(Long seriesId) {
    var series =
        chatRepository
            .findById(seriesId)
            .orElseThrow(() -> new NotFoundException("Chat Series not found"));
    requireSelfHelp(series);
    return series;
  }

  private static void requireSelfHelp(Chat series) {
    if (!isSelfHelp(series)) {
      throw new BadRequestException("Only self-help groups accept join requests");
    }
  }

  private static boolean isSelfHelp(Chat series) {
    return ChatConverter.conversationTypeOf(series) == ConversationType.SELF_HELP;
  }

  private Consultant requireConsultant(String consultantId) {
    return consultantRepository
        .findByIdAndDeleteDateIsNull(consultantId)
        .orElseThrow(() -> new NotFoundException("Consultant not found"));
  }

  private void logTransition(GroupChatJoinRequest request, String actorId, Status from) {
    log.info(
        "Group chat join request {} of series {}: {} -> {} by consultant {} (requester {})",
        request.getId(),
        request.getSeriesId(),
        from == null ? "NEW" : from,
        request.getStatus(),
        actorId,
        request.getConsultantId());
  }
}
