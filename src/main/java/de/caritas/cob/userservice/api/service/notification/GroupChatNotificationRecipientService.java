package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves every identity already participating in a self-help group Series. */
@Service
@RequiredArgsConstructor
public class GroupChatNotificationRecipientService {

  private final GroupChatParticipantRepository participantRepository;
  private final UserChatRepository userChatRepository;

  public List<String> resolveRecipientIds(Chat series) {
    if (series == null || series.getId() == null) {
      return List.of();
    }

    var recipientIds = new LinkedHashSet<String>();
    participantRepository.findBySeriesId(series.getId()).stream()
        .map(participant -> participant.getConsultantId())
        .filter(GroupChatNotificationRecipientService::isPresent)
        .forEach(recipientIds::add);
    userChatRepository.findByChat(series).stream()
        .filter(relation -> relation.getUser() != null)
        .map(relation -> relation.getUser().getUserId())
        .filter(GroupChatNotificationRecipientService::isPresent)
        .forEach(recipientIds::add);
    return List.copyOf(recipientIds);
  }

  private static boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}
