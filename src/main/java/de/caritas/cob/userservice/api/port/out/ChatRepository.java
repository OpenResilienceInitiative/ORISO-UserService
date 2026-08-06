package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
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
              + "c.conversation_type, "
              + "c.consultant_id_owner, c.matrix_room_id, c.update_date, c.create_date, "
              + "c.hint_message, c.source_language, c.hint_message_translations, "
              + "c.group_chat_rules_translations FROM chat c JOIN chat_agency ca ON c"
              + ".id = ca.chat_id JOIN user_agency ua ON ca.agency_id = ua.agency_id AND ua.user_id = :user_id",
      nativeQuery = true)
  List<Chat> findByUserId(@Param(value = "user_id") String userId);

  @Query(
      value =
          "SELECT c.id, c.topic, c.consulting_type, c.initial_start_date, c.start_date, "
              + "c.duration, c.is_repetitive, c.chat_interval, c.is_active, c.max_participants, "
              + "c.repeat_count, c.current_occurrence_index, c.timezone, c.modality, "
              + "c.conversation_type, "
              + "c.consultant_id_owner, c.matrix_room_id, c.update_date, c.create_date, "
              + "c.hint_message, c.source_language, c.hint_message_translations, "
              + "c.group_chat_rules_translations FROM chat c "
              + "JOIN user_chat uc ON c.id = uc.chat_id AND uc.user_id = :user_id",
      nativeQuery = true)
  List<Chat> findAssignedByUserId(@Param(value = "user_id") String userId);

  @Query(
      "select distinct c from Chat c join fetch c.chatAgencies ca where ca.agencyId in :agency_ids")
  List<Chat> findByAgencyIds(@Param(value = "agency_ids") Set<Long> agencyIds);

  Optional<Chat> findByMatrixRoomId(String matrixRoomId);

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

  List<Chat> findAllByActiveIsFalseAndStartDateBetween(
      LocalDateTime startInclusive, LocalDateTime endInclusive);
}
