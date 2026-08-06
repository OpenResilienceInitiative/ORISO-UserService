package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseHandoverRequestRepository extends JpaRepository<CaseHandoverRequest, Long> {

  List<CaseHandoverRequest> findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
      Long sessionId, String requesterConsultantId);

  List<CaseHandoverRequest> findBySessionIdAndStatusOrderByCreatedAtDesc(
      Long sessionId, CaseHandoverRequest.Status status);

  Optional<CaseHandoverRequest> findByIdAndSessionId(Long id, Long sessionId);

  List<CaseHandoverRequest> findBySessionId(Long sessionId);

  List<CaseHandoverRequest> findByRequesterConsultantId(String requesterConsultantId);

  List<CaseHandoverRequest> findByPreviousConsultantId(String previousConsultantId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from CaseHandoverRequest request where request.session.id = :sessionId")
  int deleteAllBySessionId(@Param("sessionId") Long sessionId);
}
