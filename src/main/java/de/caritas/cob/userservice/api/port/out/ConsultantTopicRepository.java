package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ConsultantTopic;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

public interface ConsultantTopicRepository extends CrudRepository<ConsultantTopic, Long> {

  @Query("SELECT ct.topicId FROM ConsultantTopic ct WHERE ct.consultant.id = ?1")
  List<Long> findTopicIdsByConsultantId(String consultantId);
}
