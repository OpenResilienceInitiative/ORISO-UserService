package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

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

  List<GroupChatParticipant> findBySeriesId(Long seriesId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT participant FROM GroupChatParticipant participant WHERE participant.seriesId = :seriesId")
  List<GroupChatParticipant> findBySeriesIdForUpdate(@Param("seriesId") Long seriesId);

  Optional<GroupChatParticipant> findBySeriesIdAndConsultantId(Long seriesId, String consultantId);

  /** Delete all canonical participant relations before deleting their chat series. */
  void deleteBySeriesId(Long seriesId);

  /**
   * Delete all participants for a group chat.
   *
   * @param chatId the chat/session ID
   */
  void deleteByChatId(Long chatId);
}
