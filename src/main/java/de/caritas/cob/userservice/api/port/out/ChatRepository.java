package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ChatRepository extends CrudRepository<Chat, Long> {

  @Query(
      value =
          "SELECT c.id, c.topic, c.consulting_type, c.initial_start_date, c.start_date, "
              + "c.duration, c.is_repetitive, c.chat_interval, c.is_active, c.max_participants, "
              + "c.repeat_count, c.current_occurrence_index, c.timezone, c.modality, "
              + "c.conversation_type, c.members_only, "
              + "c.consultant_id_owner, c.matrix_room_id, c.update_date, c.create_date, "
              + "c.hint_message, c.source_language, c.hint_message_translations, "
              + "c.group_chat_rules_translations, c.invite_token FROM chat c JOIN chat_agency ca ON c"
              + ".id = ca.chat_id JOIN user_agency ua ON ca.agency_id = ua.agency_id AND ua.user_id = :user_id",
      nativeQuery = true)
  List<Chat> findByUserId(@Param(value = "user_id") String userId);

  @Query(
      value =
          "SELECT c.id, c.topic, c.consulting_type, c.initial_start_date, c.start_date, "
              + "c.duration, c.is_repetitive, c.chat_interval, c.is_active, c.max_participants, "
              + "c.repeat_count, c.current_occurrence_index, c.timezone, c.modality, "
              + "c.conversation_type, c.members_only, "
              + "c.consultant_id_owner, c.matrix_room_id, c.update_date, c.create_date, "
              + "c.hint_message, c.source_language, c.hint_message_translations, "
              + "c.group_chat_rules_translations, c.invite_token FROM chat c "
              + "JOIN user_chat uc ON c.id = uc.chat_id AND uc.user_id = :user_id",
      nativeQuery = true)
  List<Chat> findAssignedByUserId(@Param(value = "user_id") String userId);

  @Query(
      "select distinct c from Chat c join fetch c.chatAgencies ca where ca.agencyId in :agency_ids")
  List<Chat> findByAgencyIds(@Param(value = "agency_ids") Set<Long> agencyIds);

  Optional<Chat> findByMatrixRoomId(String matrixRoomId);

  /** Whether the room belongs to a members-only chat (#1418). */
  boolean existsByMatrixRoomId(String matrixRoomId);

  @Query("select c.matrixRoomId from Chat c where c.matrixRoomId in :room_ids")
  Set<String> findExistingMatrixRoomIds(@Param("room_ids") Collection<String> roomIds);

  boolean existsByMatrixRoomIdAndMembersOnlyTrue(String matrixRoomId);

  /** Of these rooms, the ones that belong to a members-only chat (#1418). */
  @Query(
      "select c.matrixRoomId from Chat c where c.membersOnly = true and c.matrixRoomId in :room_ids")
  Set<String> findMembersOnlyMatrixRoomIds(
      @Param(value = "room_ids") Collection<String> matrixRoomIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Chat c where c.matrixRoomId = :matrixRoomId")
  Optional<Chat> findByMatrixRoomIdForUpdate(@Param("matrixRoomId") String matrixRoomId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Chat c where c.id = :chatId")
  Optional<Chat> findByIdForUpdate(@Param("chatId") Long chatId);

  @Query(
      "select distinct c from Chat c left join fetch c.chatAgencies where c.matrixRoomId in :room_ids")
  List<Chat> findByMatrixRoomIdIn(@Param(value = "room_ids") Set<String> matrixRoomIds);

  @Query("select distinct c from Chat c left join fetch c.chatAgencies where c.id in :chat_ids")
  List<Chat> findByIdsWithChatAgencies(@Param(value = "chat_ids") Set<Long> chatIds);

  @Query(
      "select distinct c from Chat c "
          + "left join fetch c.chatAgencies "
          + "left join fetch c.chatUsers "
          + "where c.id = :chat_id")
  Optional<Chat> findByIdWithPermissionRelations(@Param(value = "chat_id") Long chatId);

  List<Chat> findByChatOwner(Consultant chatOwner);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<Chat> findAllByActiveIsTrue();

  /** Candidate discovery only; each chat is locked and revalidated before it is stopped. */
  @Query("select c from Chat c where c.active = true")
  List<Chat> findActiveDeactivationCandidates();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select chat from Chat chat where chat.id = :seriesId")
  Optional<Chat> findSeriesForAppointmentMailUpdate(@Param("seriesId") Long seriesId);

  /**
   * Candidate discovery only. The recurring branch uses an upper bound on days per period plus two
   * days for UTC offset changes. The occurrence projector resolves the exact dates and edits.
   */
  @Query(
      """
      select c.id from Chat c
      where c.active = false and c.currentOccurrenceIndex < c.repeatCount
        and (
          c.startDate between :windowStart and :windowEnd
          or (
            c.repeatCount > 1 and c.initialStartDate <= :windowEnd
            and timestampdiff(day, c.initialStartDate, :windowStart) <=
              (c.repeatCount - 1) * case cast(c.chatInterval as String)
                when 'DAILY' then 1
                when 'WEEKLY' then 7
                when 'BIWEEKLY' then 14
                when 'MONTHLY' then 31
                when 'QUARTERLY' then 93
                when 'YEARLY' then 366
                else 0 end + 2
          )
          or exists (
            select e.id from ChatOccurrenceException e
            where e.series = c and e.overrideStartUtc between :windowStart and :windowEnd
          )
        )
      """)
  List<Long> findInactiveReminderCandidateIds(
      @Param("windowStart") LocalDateTime windowStart, @Param("windowEnd") LocalDateTime windowEnd);
}
