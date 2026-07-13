package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GroupChatRoleService {

  private final GroupChatParticipantRepository participantRepository;
  private final ChatRepository chatRepository;
  private final ConsultantRepository consultantRepository;
  private final GroupChatMembershipService groupChatMembershipService;

  @Transactional
  public void changeRole(
      Long seriesId,
      String actingConsultantId,
      String targetConsultantId,
      ParticipantRole newRole) {
    var participants = participantRepository.findBySeriesIdForUpdate(seriesId);
    var actor =
        requireParticipant(
            participants,
            actingConsultantId,
            () -> new ForbiddenException("Actor is not a member of this Series"));
    if (actor.getRole() != ParticipantRole.OWNER) {
      throw new ForbiddenException("Only an Owner can change Series roles");
    }

    var target =
        requireParticipant(
            participants,
            targetConsultantId,
            () -> new NotFoundException("Series participant not found"));
    if (target.getRole() == ParticipantRole.OWNER && newRole != ParticipantRole.OWNER) {
      long ownerCount = ownerCount(participants);
      if (ownerCount <= 1) {
        throw new ConflictException("The last Series Owner cannot be demoted");
      }
    }

    target.setRole(newRole);
    participantRepository.save(target);
  }

  @Transactional
  public void transferPrimaryOwnership(
      Long seriesId, String actingOwnerId, String targetConsultantId) {
    var participants = participantRepository.findBySeriesIdForUpdate(seriesId);
    var actor =
        requireParticipant(
            participants,
            actingOwnerId,
            () -> new ForbiddenException("Actor is not a member of this Series"));
    if (actor.getRole() != ParticipantRole.OWNER) {
      throw new ForbiddenException("Only an Owner can transfer primary ownership");
    }
    var target =
        requireParticipant(
            participants,
            targetConsultantId,
            () -> new NotFoundException("Series participant not found"));
    var series =
        chatRepository
            .findById(seriesId)
            .orElseThrow(() -> new NotFoundException("Chat Series not found"));
    var newPrimaryOwner =
        consultantRepository
            .findById(targetConsultantId)
            .orElseThrow(() -> new NotFoundException("Consultant not found"));

    actor.setRole(ParticipantRole.CO_MODERATOR);
    target.setRole(ParticipantRole.OWNER);
    series.setChatOwner(newPrimaryOwner);
    participantRepository.save(actor);
    participantRepository.save(target);
    chatRepository.save(series);
  }

  @Transactional
  public void removeParticipant(
      Long seriesId, String actingConsultantId, String targetConsultantId) {
    var participants = participantRepository.findBySeriesIdForUpdate(seriesId);
    var actor =
        requireParticipant(
            participants,
            actingConsultantId,
            () -> new ForbiddenException("Actor is not a member of this Series"));
    if (actor.getRole() != ParticipantRole.OWNER) {
      throw new ForbiddenException("Only an Owner can remove Series participants");
    }

    var target =
        requireParticipant(
            participants,
            targetConsultantId,
            () -> new NotFoundException("Series participant not found"));
    if (target.getRole() == ParticipantRole.OWNER) {
      throw new ConflictException("An Owner must transfer or relinquish ownership before removal");
    }

    var series =
        chatRepository
            .findById(seriesId)
            .orElseThrow(() -> new NotFoundException("Chat Series not found"));
    var targetConsultant =
        consultantRepository
            .findById(targetConsultantId)
            .orElseThrow(() -> new NotFoundException("Consultant not found"));

    participantRepository.delete(target);
    groupChatMembershipService.removeLeavingMemberFromRoom(
        series, targetConsultant.getMatrixUserId());
  }

  @Transactional
  public void leaveSeries(Chat series, Consultant consultant) {
    if (series == null
        || series.getId() == null
        || consultant == null
        || consultant.getId() == null) {
      return;
    }

    var participants = participantRepository.findBySeriesIdForUpdate(series.getId());
    var participation =
        participants.stream()
            .filter(participant -> consultant.getId().equals(participant.getConsultantId()))
            .findFirst();
    if (participation.isPresent()) {
      var leavingParticipant = participation.get();
      if (leavingParticipant.getRole() == ParticipantRole.OWNER) {
        long ownerCount = ownerCount(participants);
        if (ownerCount <= 1) {
          throw new ConflictException(
              "The last Series Owner cannot leave before transferring ownership");
        }
      }
      participantRepository.delete(leavingParticipant);
    }

    groupChatMembershipService.removeLeavingMemberFromRoom(series, consultant.getMatrixUserId());
  }

  private GroupChatParticipant requireParticipant(
      List<GroupChatParticipant> participants,
      String consultantId,
      Supplier<? extends RuntimeException> exceptionSupplier) {
    return participants.stream()
        .filter(participant -> consultantId.equals(participant.getConsultantId()))
        .findFirst()
        .orElseThrow(exceptionSupplier);
  }

  private long ownerCount(List<GroupChatParticipant> participants) {
    return participants.stream()
        .filter(participant -> participant.getRole() == ParticipantRole.OWNER)
        .count();
  }
}
