package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ConsultantTopic;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rows are stored per counselling centre (#1264), so the flat queries use DISTINCT: routing and
 * every other flat reader keep seeing each topic / consultant once.
 */
public interface ConsultantTopicRepository extends CrudRepository<ConsultantTopic, Long> {

  @Query("SELECT DISTINCT ct.topicId FROM ConsultantTopic ct WHERE ct.consultant.id = ?1")
  List<Long> findTopicIdsByConsultantId(String consultantId);

  @Query(
      "SELECT DISTINCT ct.consultant.id, ct.topicId FROM ConsultantTopic ct WHERE ct.consultant.id IN ?1")
  List<Object[]> findTopicIdsByConsultantIdIn(Collection<String> consultantIds);

  @Query("SELECT DISTINCT ct.consultant.id FROM ConsultantTopic ct WHERE ct.topicId = ?1")
  List<String> findConsultantIdsByTopicId(Long topicId);

  /** Rows as {consultantId, agencyId (nullable), topicId}, for the per-centre admin view. */
  @Query(
      "SELECT ct.consultant.id, ct.agencyId, ct.topicId FROM ConsultantTopic ct"
          + " WHERE ct.consultant.id IN ?1")
  List<Object[]> findAgencyTopicRowsByConsultantIdIn(Collection<String> consultantIds);

  /** Pins rows without a centre to the one centre a flow is about (invite / onboarding). */
  @Transactional
  @Modifying(flushAutomatically = true)
  @Query(
      "UPDATE ConsultantTopic ct SET ct.agencyId = ?2"
          + " WHERE ct.consultant.id = ?1 AND ct.agencyId IS NULL")
  int assignUnscopedTopicsToAgency(String consultantId, Long agencyId);

  /** Drops a centre's topics when the centre is removed; rows without a centre stay. */
  @Transactional
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ConsultantTopic ct WHERE ct.consultant.id = ?1 AND ct.agencyId = ?2")
  int deleteByConsultantIdAndAgencyId(String consultantId, Long agencyId);
}
