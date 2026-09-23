package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Decides whether a counsellor may see a group chat (#1237).
 *
 * <p>A member or moderator of the group always may, whatever their Träger — so a counsellor who is
 * admitted to a group later keeps working. Everyone else must work in the group's Beratungsstelle
 * and belong to the same Träger as the group's owner. The same-Beratungsstelle rule is today's
 * behaviour, kept as is while the product question around it is open.
 */
@Component
@RequiredArgsConstructor
public class GroupChatConsultantAccess {

  private final GroupChatParticipantRepository participantRepository;

  public boolean mayAccess(Chat chat, Consultant consultant) {
    if (chat == null || consultant == null) {
      return false;
    }
    return isParticipant(chat, consultant)
        || (isSameTenantAsOwner(chat, consultant) && sharesAgency(chat, consultant));
  }

  private boolean isParticipant(Chat chat, Consultant consultant) {
    return chat.getId() != null
        && participantRepository
            .findBySeriesIdAndConsultantId(chat.getId(), consultant.getId())
            .isPresent();
  }

  private static boolean isSameTenantAsOwner(Chat chat, Consultant consultant) {
    return chat.getChatOwner() != null
        && Objects.equals(chat.getChatOwner().getTenantId(), consultant.getTenantId());
  }

  private static boolean sharesAgency(Chat chat, Consultant consultant) {
    Set<Long> consultantAgencyIds =
        nullSafe(consultant.getConsultantAgencies()).stream()
            .map(ConsultantAgency::getAgencyId)
            .collect(Collectors.toSet());
    return nullSafe(chat.getChatAgencies()).stream()
        .map(ChatAgency::getAgencyId)
        .anyMatch(consultantAgencyIds::contains);
  }

  private static <T> Collection<T> nullSafe(Collection<T> values) {
    return values == null ? Set.of() : values;
  }
}
