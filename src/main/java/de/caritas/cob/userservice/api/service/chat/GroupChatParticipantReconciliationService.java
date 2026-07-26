package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps a chat Series' explicit consultant selection and Matrix room membership in sync. */
@Service
@RequiredArgsConstructor
public class GroupChatParticipantReconciliationService {

  private final GroupChatParticipantRepository participantRepository;
  private final ConsultantRepository consultantRepository;
  private final GroupChatMembershipService membershipService;

  /**
   * Reconciles co-moderators only when the client explicitly supplies {@code consultantIds}. A null
   * value preserves legacy clients' existing participants.
   */
  @Transactional
  public void reconcile(Chat series, List<String> consultantIds) {
    if (consultantIds == null) {
      return;
    }

    var participants = participantRepository.findBySeriesIdForUpdate(series.getId());
    var ownerId = series.getChatOwner().getId();
    var desiredIds = new LinkedHashSet<>(consultantIds);
    desiredIds.remove(ownerId);
    desiredIds.removeIf(id -> id == null || id.isBlank());

    var participantsByConsultantId =
        participants.stream()
            .collect(
                Collectors.toMap(
                    GroupChatParticipant::getConsultantId,
                    Function.identity(),
                    (left, right) -> left));

    for (var existing : participants) {
      if (existing.getRole() == ParticipantRole.CO_MODERATOR
          && !desiredIds.contains(existing.getConsultantId())) {
        consultantRepository
            .findByIdAndDeleteDateIsNull(existing.getConsultantId())
            .ifPresent(
                consultant ->
                    membershipService.removeLeavingMemberFromRoom(
                        series, consultant.getMatrixUserId()));
        participantRepository.delete(existing);
      }
    }

    var sessionId =
        participants.stream()
            .map(GroupChatParticipant::getChatId)
            .findFirst()
            .orElseThrow(
                () ->
                    new ConflictException(
                        "Chat Series has no owner participation and cannot be updated"));

    for (var consultantId : desiredIds) {
      var existing = participantsByConsultantId.get(consultantId);
      if (existing != null) {
        if (existing.getRole() == ParticipantRole.PARTICIPANT) {
          existing.setRole(ParticipantRole.CO_MODERATOR);
          participantRepository.save(existing);
        }
        continue;
      }

      var consultant =
          consultantRepository
              .findByIdAndDeleteDateIsNull(consultantId)
              .orElseThrow(
                  () -> new BadRequestException("Consultant " + consultantId + " does not exist"));
      if (!Objects.equals(series.getChatOwner().getTenantId(), consultant.getTenantId())) {
        throw new BadRequestException("Consultant does not belong to the chat owner's tenant");
      }
      if (!membershipService.addMemberToRoom(series, consultant.getMatrixUserId())) {
        throw new InternalServerErrorException(
            "Consultant " + consultantId + " could not join the Matrix room");
      }

      participantRepository.save(
          GroupChatParticipant.builder()
              .chatId(sessionId)
              .seriesId(series.getId())
              .consultantId(consultantId)
              .role(ParticipantRole.CO_MODERATOR)
              .build());
    }
  }
}
