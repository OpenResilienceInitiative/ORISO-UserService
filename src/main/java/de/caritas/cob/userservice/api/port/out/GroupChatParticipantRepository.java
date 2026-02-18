package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

/**
 * Repository for {@link GroupChatParticipant}. Manages the many-to-many relationship between group
 * chats (sessions) and consultants.
 */
public interface GroupChatParticipantRepository extends CrudRepository<GroupChatParticipant, Long> {

  /**
   * Find all group chat participations for a consultant.
   *
   * @param consultantId the consultant ID
   * @return list of participations
   */
  List<GroupChatParticipant> findByConsultantId(String consultantId);

  /**
   * Find all participants for a group chat.
   *
   * @param chatId the chat/session ID
   * @return list of participants
   */
  List<GroupChatParticipant> findByChatId(Long chatId);

  /**
   * Delete all participants for a group chat.
   *
   * @param chatId the chat/session ID
   */
  void deleteByChatId(Long chatId);
}
