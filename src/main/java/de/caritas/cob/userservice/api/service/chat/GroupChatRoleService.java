package de.caritas.cob.userservice.api.service.chat;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

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
import de.caritas.cob.userservice.api.tenant.TenantContext;
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
  private final MembersOnlyRevocation revocation;

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

    var demotedOwner =
        target.getRole() == ParticipantRole.OWNER && newRole != ParticipantRole.OWNER;
    target.setRole(newRole);
    participantRepository.save(target);
    if (demotedOwner) {
      // The chat must not keep naming someone who is no longer an owner as its primary owner.
      chatRepository
          .findById(seriesId)
          .ifPresent(series -> revocation.passOwnershipOn(series, targetConsultantId));
    }
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
    var newPrimaryOwner = participantAccount(targetConsultantId);

    actor.setRole(ParticipantRole.CO_MODERATOR);
    target.setRole(ParticipantRole.OWNER);
    series.setChatOwner(newPrimaryOwner);
    series.setUpdateDate(nowInUtc());
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
    var targetConsultant = participantAccount(targetConsultantId);

    revocation.revokeAccessOf(series, target);
    participantRepository.delete(target);
    groupChatMembershipService.removeParticipantFromRoom(
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
      revocation.revokeAccessOf(series, leavingParticipant);
      participantRepository.delete(leavingParticipant);
      if (leavingParticipant.getRole() == ParticipantRole.OWNER) {
        // Another owner remains (checked above); the chat must not keep naming the one who left.
        revocation.passOwnershipOn(series, consultant.getId());
      }
    }

    groupChatMembershipService.removeParticipantFromRoom(series, consultant.getMatrixUserId());
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

  /**
   * The account of someone who already is a participant. A colleague admitted from another Träger
   * (recipient scope PLATFORM) is one, and the lookup of the owner's Träger would not find them:
   * revoking or handing over must work for every participant, so it is resolved by participation
   * and across Träger. It never widens who can be picked.
   */
  private Consultant participantAccount(String consultantId) {
    return TenantContext.supplyAcrossTenants(() -> consultantRepository.findById(consultantId))
        .orElseThrow(() -> new NotFoundException("Consultant not found"));
  }

  private long ownerCount(List<GroupChatParticipant> participants) {
    return participants.stream()
        .filter(participant -> participant.getRole() == ParticipantRole.OWNER)
        .count();
  }
}
