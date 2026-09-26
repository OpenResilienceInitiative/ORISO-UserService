package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

  /** Applies the same access rule to a list with one membership query for the whole list. */
  public List<Chat> filterAccessible(List<Chat> chats, Consultant consultant) {
    if (chats == null || chats.isEmpty() || consultant == null) {
      return List.of();
    }
    var seriesIds =
        chats.stream().filter(Objects::nonNull).map(Chat::getId).filter(Objects::nonNull).toList();
    var memberSeriesIds =
        seriesIds.isEmpty()
            ? Set.<Long>of()
            : participantRepository
                .findBySeriesIdInAndConsultantId(seriesIds, consultant.getId())
                .stream()
                .map(GroupChatParticipant::getSeriesId)
                .collect(Collectors.toSet());
    return chats.stream()
        .filter(Objects::nonNull)
        .filter(
            chat ->
                memberSeriesIds.contains(chat.getId())
                    || (isSameTenantAsOwner(chat, consultant) && sharesAgency(chat, consultant)))
        .toList();
  }

  /**
   * Whether the counsellor may act as a moderator of the group: an Owner or Co-Moderator of the
   * group, or a same-Träger colleague of its Beratungsstelle. A plain member — also one admitted
   * from another Träger — may not.
   */
  public boolean mayModerate(Chat chat, Consultant consultant) {
    if (chat == null || consultant == null) {
      return false;
    }
    return participationOf(chat, consultant)
            .map(GroupChatParticipant::getRole)
            .filter(role -> role == ParticipantRole.OWNER || role == ParticipantRole.CO_MODERATOR)
            .isPresent()
        || (isSameTenantAsOwner(chat, consultant) && sharesAgency(chat, consultant));
  }

  private boolean isParticipant(Chat chat, Consultant consultant) {
    return participationOf(chat, consultant).isPresent();
  }

  private Optional<GroupChatParticipant> participationOf(Chat chat, Consultant consultant) {
    return chat.getId() == null
        ? Optional.empty()
        : participantRepository.findBySeriesIdAndConsultantId(chat.getId(), consultant.getId());
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
