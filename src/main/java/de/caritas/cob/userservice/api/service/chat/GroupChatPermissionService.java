package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Central authorization boundary for opening, moderating, and ending a Chat Series occurrence. */
@Service
@RequiredArgsConstructor
public class GroupChatPermissionService {

  private final GroupChatParticipantRepository participantRepository;
  private final ChatPermissionVerifier legacyPermissionVerifier;

  public void requireCanModerate(Chat chat, Consultant consultant) {
    if (chat == null || consultant == null) {
      throw forbidden();
    }

    var participants =
        chat.getId() == null
            ? java.util.List.<de.caritas.cob.userservice.api.model.GroupChatParticipant>of()
            : participantRepository.findBySeriesId(chat.getId());
    if (participants.isEmpty()) {
      if (!legacyPermissionVerifier.hasSameAgencyAssigned(chat, consultant)) {
        throw forbidden();
      }
      return;
    }

    var authorized =
        participants.stream()
            .filter(participant -> consultant.getId().equals(participant.getConsultantId()))
            .map(participant -> participant.getRole())
            .anyMatch(
                role -> role == ParticipantRole.OWNER || role == ParticipantRole.CO_MODERATOR);
    if (!authorized) {
      throw forbidden();
    }
  }

  private ForbiddenException forbidden() {
    return new ForbiddenException(
        "Only a Series Owner or Co-Moderator may moderate this occurrence");
  }
}
