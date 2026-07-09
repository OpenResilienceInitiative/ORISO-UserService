package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ChatAgency;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ChatAgencyRepository extends CrudRepository<ChatAgency, Long> {

  @Query("SELECT ca FROM ChatAgency ca JOIN FETCH ca.chat WHERE ca.chat.id = :chatId")
  List<ChatAgency> findByChat_Id(@Param("chatId") Long chatId);

  @Query("SELECT ca FROM ChatAgency ca JOIN FETCH ca.chat WHERE ca.chat.id IN :chatIds")
  List<ChatAgency> findByChat_IdIn(@Param("chatIds") Set<Long> chatIds);
}
