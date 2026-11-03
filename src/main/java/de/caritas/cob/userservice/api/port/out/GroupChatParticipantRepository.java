package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import jakarta.persistence.LockModeType;
import java.util.Collection;
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

  List<GroupChatParticipant> findBySeriesIdInAndConsultantId(
      Collection<Long> seriesIds, String consultantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT participant FROM GroupChatParticipant participant WHERE participant.seriesId = :seriesId")
  List<GroupChatParticipant> findBySeriesIdForUpdate(@Param("seriesId") Long seriesId);

  Optional<GroupChatParticipant> findBySeriesIdAndConsultantId(Long seriesId, String consultantId);

  /**
   * The chats (series ids) whose participants are exactly {@code consultantIds}: no one missing and
   * no one else. {@code creatorId} narrows the search to chats the creator takes part in and must
   * be one of {@code consultantIds}; {@code size} is their number.
   */
  @Query(
      "SELECT p.seriesId FROM GroupChatParticipant p WHERE p.seriesId IN "
          + "(SELECT m.seriesId FROM GroupChatParticipant m WHERE m.consultantId = :creatorId) "
          + "GROUP BY p.seriesId "
          + "HAVING COUNT(p) = :size AND COUNT(DISTINCT p.consultantId) = :size "
          + "AND SUM(CASE WHEN p.consultantId IN :consultantIds THEN 1 ELSE 0 END) = :size "
          + "ORDER BY p.seriesId")
  List<Long> findSeriesIdsWithExactParticipants(
      @Param("creatorId") String creatorId,
      @Param("consultantIds") Collection<String> consultantIds,
      @Param("size") long size);

  /** Whether the consultant takes part in the group chat of this session ({@code chat_id}). */
  boolean existsByChatIdAndConsultantId(Long chatId, String consultantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select participant from GroupChatParticipant participant "
          + "where participant.seriesId = :seriesId and participant.consultantId = :consultantId")
  Optional<GroupChatParticipant> findBySeriesIdAndConsultantIdForUpdate(
      @Param("seriesId") Long seriesId, @Param("consultantId") String consultantId);

  /** Delete all canonical participant relations before deleting their chat series. */
  void deleteBySeriesId(Long seriesId);

  /**
   * Delete all participants for a group chat.
   *
   * @param chatId the chat/session ID
   */
  void deleteByChatId(Long chatId);
}
