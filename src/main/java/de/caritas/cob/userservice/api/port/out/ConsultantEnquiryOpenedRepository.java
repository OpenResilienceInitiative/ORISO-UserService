package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ConsultantEnquiryOpened;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsultantEnquiryOpenedRepository
    extends JpaRepository<ConsultantEnquiryOpened, Long> {

  boolean existsByConsultantIdAndSessionId(String consultantId, Long sessionId);

  /** How many enquiries this counsellor has opened (for summary counts). */
  long countByConsultantId(String consultantId);

  /**
   * The subset of {@code sessionIds} this counsellor has opened; everything else in the pool is
   * still unopened for them. Marks of other counsellors never show up here.
   */
  @Query(
      "select e.sessionId from ConsultantEnquiryOpened e "
          + "where e.consultantId = :consultantId and e.sessionId in :sessionIds")
  List<Long> findOpenedSessionIds(
      @Param("consultantId") String consultantId, @Param("sessionIds") Collection<Long> sessionIds);
}
