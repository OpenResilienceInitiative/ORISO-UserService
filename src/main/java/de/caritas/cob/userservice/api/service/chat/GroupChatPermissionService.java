package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Central authorization boundary for opening, moderating, and ending a Chat Series occurrence. */
@Service
@RequiredArgsConstructor
public class GroupChatPermissionService {

  private final GroupChatConsultantAccess groupChatConsultantAccess;

  public void requireCanModerate(Chat chat, Consultant consultant) {
    if (!groupChatConsultantAccess.mayStartStopOrBan(chat, consultant)) {
      throw new ForbiddenException(
          "Only a Series Owner or Co-Moderator may moderate this occurrence");
    }
  }

  /** Shared role constraints for both a moderator's decision and its deferred execution. */
  public void requireCanAssignAdmissionRole(
      Chat series,
      List<GroupChatParticipant> participants,
      Consultant actor,
      Consultant target,
      ParticipantRole role) {
    if (role == null || role == ParticipantRole.OWNER)
      throw new BadRequestException("A join request can only be admitted as Participant");
    if (role != ParticipantRole.CO_MODERATOR) return;
    boolean owner =
        participants.isEmpty()
            ? series.getChatOwner() != null && actor.getId().equals(series.getChatOwner().getId())
            : participants.stream()
                .anyMatch(
                    p ->
                        actor.getId().equals(p.getConsultantId())
                            && p.getRole() == ParticipantRole.OWNER);
    if (!owner) throw new ForbiddenException("Only a Series Owner can admit a Co-Moderator");
    if (series.getChatOwner() == null) throw new BadRequestException("Chat Series has no owner");
    if (!Objects.equals(series.getChatOwner().getTenantId(), target.getTenantId()))
      throw new BadRequestException("Consultant does not belong to the chat owner's tenant");
  }
}
