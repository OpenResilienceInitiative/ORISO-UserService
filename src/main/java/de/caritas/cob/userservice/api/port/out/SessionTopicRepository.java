package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.SessionTopic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SessionTopicRepository extends JpaRepository<SessionTopic, Long> {

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Transactional
  @Query("delete from SessionTopic topic where topic.session.id = :sessionId")
  int deleteAllBySessionId(@Param("sessionId") Long sessionId);
}
